package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

final class ApiKeyStateAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("SuspendMachine", "agent");
        h.account("SuspendOther", "reader");
        String target, other, admin, key, token, second;
        Map<String, Object> before;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "SuspendMachine");
            other = RecordAccess.owner(db, "SuspendOther");
            admin = RecordAccess.owner(db, "admin");
            db.update("UPDATE os_user SET api_only=1 WHERE user_id=?", target);
            var issued =
                    ApiKeys.issue(
                            db,
                            admin,
                            target,
                            map("label", "Suspend", "table", "incident", "days", 1));
            key = issued.get("key_id").toString();
            token = issued.get("token").toString();
            second =
                    ApiKeys.issue(
                                    db,
                                    admin,
                                    target,
                                    map("label", "Independent", "table", "incident", "days", 1))
                            .get("token")
                            .toString();
            before = db.one("SELECT key_id,token_hash,user_id,auth_revision,label,table_name,created_at,expires_at,enabled,can_write FROM os_api_key WHERE key_id=?", key);
            db.connection.commit();
        }
        String api = "/api/openservice/users/" + target + "/api-keys/" + key + "/state",
                form = "/admin/accounts/" + target + "/api-keys/" + key + "/state";
        var off = map("enabled", false);
        var on = map("enabled", true);
        assertEquals(401, h.json("POST", api, off, null).statusCode());
        assertEquals(403, h.json("POST", api, off, auth("SuspendOther")).statusCode());
        assertEquals(405, h.get(api, auth("admin")).statusCode());
        assertEquals(400, h.json("POST", api + "?extra=1", off, auth("admin")).statusCode());
        assertEquals(
                404, h.json("POST", api.replace(target, other), off, auth("admin")).statusCode());
        assertEquals(
                404, h.json("POST", api.replace(target, id()), off, auth("admin")).statusCode());
        for (Object bad :
                List.of(
                        map(),
                        map("enabled", "false"),
                        map("enabled", false, "extra", 1),
                        map("enabled", null),
                        List.of()))
            assertEquals(400, h.json("POST", api, bad, auth("admin")).statusCode());
        assertEquals(200, h.json("POST", api, off, auth("admin")).statusCode());
        assertEquals(200, h.json("POST", api, off, auth("admin")).statusCode());
        assertEquals(401, h.get("/api/now/table/incident", "Bearer " + token).statusCode());
        assertEquals(200, h.get("/api/now/table/incident", "Bearer " + second).statusCode());
        new Store(h.environment);
        assertEquals(401, h.get("/api/now/table/incident", "Bearer " + token).statusCode());
        assertEquals(
                409,
                h.json("POST", api.replace("/state", "/rotate"), map(), auth("admin"))
                        .statusCode());
        try (var db = h.store.open()) {
            assertEquals(
                    false,
                    ApiKeys.list(db, admin, target).stream()
                            .filter(k -> k.get("key_id").equals(key))
                            .findFirst()
                            .orElseThrow()
                            .get("enabled"));
            ApiKeyState.change(db, admin, target, key, on);
            db.connection.rollback();
            assertEquals(
                    0,
                    ((Number)
                                    db.one("SELECT enabled FROM os_api_key WHERE key_id=?", key)
                                            .get("enabled"))
                            .intValue());
            assertEquals(
                    403,
                    assertThrows(
                                    Failure.class,
                                    () -> ApiKeyState.change(db, other, target, key, on))
                            .status);
        }
        String csrf = h.login("admin");
        assertEquals(403, h.form(form, map("csrf", "bad", "enabled", "true"), null).statusCode());
        assertEquals(400, h.form(form, map("csrf", csrf, "enabled", "yes"), null).statusCode());
        assertEquals(302, h.form(form, map("csrf", csrf, "enabled", "true"), null).statusCode());
        assertEquals(200, h.get("/api/now/table/incident", "Bearer " + token).statusCode());
        assertEquals(302, h.form(form, map("csrf", csrf, "enabled", "false"), null).statusCode());
        assertEquals(200, h.json("POST", api, on, auth("admin")).statusCode());
        try (var db = h.store.open()) {
            assertEquals(before, db.one("SELECT key_id,token_hash,user_id,auth_revision,label,table_name,created_at,expires_at,enabled,can_write FROM os_api_key WHERE key_id=?", key));
            assertEquals(
                    4,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                + " action='account.api_key_state_changed'"));
            assertFalse(db.all("SELECT detail FROM os_security_event").toString().contains(token));
            for (String mutation :
                    List.of(
                            "UPDATE os_api_key SET expires_at=0 WHERE key_id='" + key + "'",
                            "UPDATE os_user SET auth_revision=auth_revision+1 WHERE user_id='"
                                    + target
                                    + "'")) {
                db.update(mutation);
                ApiKeyState.change(db, admin, target, key, off);
                ApiKeyState.change(db, admin, target, key, on);
                assertEquals(
                        401,
                        assertThrows(
                                        Failure.class,
                                        () ->
                                                ApiKeys.authenticate(
                                                        db,
                                                        "Bearer " + token,
                                                        "/api/now/table/incident",
                                                        "GET"))
                                .status);
                db.connection.rollback();
            }
            // Reconstruct a populated schema-24 database, then verify default and rollback.
            db.update("ALTER TABLE os_api_key DROP COLUMN enabled");
            db.update("DELETE FROM os_migration WHERE version>=25");
            db.connection.commit();
            Migrations.apply(db);
            assertEquals(
                    1,
                    ((Number)
                                    db.one("SELECT enabled FROM os_api_key WHERE key_id=?", key)
                                            .get("enabled"))
                            .intValue());
            db.connection.rollback();
            assertEquals(24, db.count("SELECT COUNT(*) FROM os_migration"));
            Migrations.apply(db);
            db.connection.commit();
        }
        assertEquals(200, h.get("/api/now/table/incident", "Bearer " + token).statusCode());
    }
}
