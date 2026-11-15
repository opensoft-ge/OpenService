package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class ApiKeyStatusAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("StatusMachine", "reader");
        h.account("StatusOther", "reader");
        String target, admin, key, token;
        long revision, expires;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "StatusMachine");
            admin = RecordAccess.owner(db, "admin");
            db.update("UPDATE os_user SET api_only=1 WHERE user_id=?", target);
            var issued =
                    ApiKeys.issue(
                            db,
                            admin,
                            target,
                            map("label", "Status", "table", "incident", "days", 3));
            key = issued.get("key_id").toString();
            token = issued.get("token").toString();
            expires = ((Number) issued.get("expires_at")).longValue();
            revision =
                    ((Number)
                                    db.one(
                                                    "SELECT auth_revision FROM os_user WHERE"
                                                        + " user_id=?",
                                                    target)
                                            .get("auth_revision"))
                            .longValue();
            db.connection.commit();
        }
        String api = "/api/openservice/users/" + target + "/api-keys", bearer = "Bearer " + token;
        assertEquals(401, h.get(api, null).statusCode());
        assertEquals(403, h.get(api, auth("StatusOther")).statusCode());
        assertEquals(403, h.get(api, bearer).statusCode());
        assertEquals(200, h.get("/api/now/table/incident", bearer).statusCode());
        var cases =
                List.of(
                        map(
                                "status",
                                "Eligible",
                                "active",
                                1,
                                "api",
                                1,
                                "role",
                                "reader",
                                "reset",
                                0,
                                "revision",
                                revision,
                                "enabled",
                                1,
                                "expiry",
                                expires),
                        map(
                                "status",
                                "Expired",
                                "active",
                                0,
                                "api",
                                0,
                                "role",
                                "admin",
                                "reset",
                                1,
                                "revision",
                                revision + 1,
                                "enabled",
                                0,
                                "expiry",
                                0L),
                        map(
                                "status",
                                "Suspended",
                                "active",
                                0,
                                "api",
                                0,
                                "role",
                                "admin",
                                "reset",
                                1,
                                "revision",
                                revision + 1,
                                "enabled",
                                0,
                                "expiry",
                                expires),
                        map(
                                "status",
                                "Account inactive",
                                "active",
                                0,
                                "api",
                                0,
                                "role",
                                "admin",
                                "reset",
                                1,
                                "revision",
                                revision + 1,
                                "enabled",
                                1,
                                "expiry",
                                expires),
                        map(
                                "status",
                                "Browser account",
                                "active",
                                1,
                                "api",
                                0,
                                "role",
                                "admin",
                                "reset",
                                1,
                                "revision",
                                revision + 1,
                                "enabled",
                                1,
                                "expiry",
                                expires),
                        map(
                                "status",
                                "Administrator account",
                                "active",
                                1,
                                "api",
                                1,
                                "role",
                                "admin",
                                "reset",
                                1,
                                "revision",
                                revision + 1,
                                "enabled",
                                1,
                                "expiry",
                                expires),
                        map(
                                "status",
                                "Password change required",
                                "active",
                                1,
                                "api",
                                1,
                                "role",
                                "reader",
                                "reset",
                                1,
                                "revision",
                                revision + 1,
                                "enabled",
                                1,
                                "expiry",
                                expires),
                        map(
                                "status",
                                "Account changed",
                                "active",
                                1,
                                "api",
                                1,
                                "role",
                                "reader",
                                "reset",
                                0,
                                "revision",
                                revision + 1,
                                "enabled",
                                1,
                                "expiry",
                                expires));
        for (var state : cases) {
            Map<String, Object> before;
            try (var db = h.store.open()) {
                db.update(
                        "UPDATE os_user SET"
                            + " active=?,api_only=?,role=?,password_reset_required=?,auth_revision=?"
                            + " WHERE user_id=?",
                        state.get("active"),
                        state.get("api"),
                        state.get("role"),
                        state.get("reset"),
                        state.get("revision"),
                        target);
                db.update(
                        "UPDATE os_api_key SET enabled=?,expires_at=? WHERE key_id=?",
                        state.get("enabled"),
                        state.get("expiry"),
                        key);
                db.connection.commit();
                before = db.one("SELECT * FROM os_api_key WHERE key_id=?", key);
            }
            var response = h.get(api, auth("admin"));
            assertEquals(200, response.statusCode());
            var row = objects(parsed(response).get("result")).getFirst();
            assertEquals(state.get("status"), row.get("credential_status"));
            assertEquals(key, row.get("key_id"));
            assertFalse(row.containsKey("auth_revision"));
            assertFalse(row.containsKey("token_hash"));
            assertFalse(html(response).contains(token));
            try (var db = h.store.open()) {
                assertEquals(before, db.one("SELECT * FROM os_api_key WHERE key_id=?", key));
            }
            assertEquals(
                    state.get("status").equals("Eligible") ? 200 : 401,
                    h.get("/api/now/table/incident", bearer).statusCode());
        }
        new Store(h.environment);
        assertEquals(
                "Account changed",
                objects(parsed(h.get(api, auth("admin"))).get("result"))
                        .getFirst()
                        .get("credential_status"));
        try (var db = h.store.open()) {
            db.update("UPDATE os_user SET auth_revision=? WHERE user_id=?", revision, target);
            db.connection.commit();
            var row = db.one("SELECT * FROM os_api_key WHERE key_id=?", key);
            var account = db.one("SELECT * FROM os_user WHERE user_id=?", target);
            assertEquals("Expired", ApiKeyStatus.describe(row, account, expires));
            assertEquals("Eligible", ApiKeyStatus.describe(row, account, expires - 1));
        }
        assertEquals(403, h.get("/api/now/table/problem", bearer).statusCode());
        assertEquals(
                403,
                h.json(
                                "POST",
                                "/api/now/table/incident",
                                map("short_description", "No scope escalation"),
                                bearer)
                        .statusCode());
        assertEquals(
                "Eligible",
                objects(parsed(h.get(api, auth("admin"))).get("result"))
                        .getFirst()
                        .get("credential_status"));
    }
}
