package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class ApiKeyOnlyAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("OnlyMachine", "agent");
        h.account("OnlyOther", "reader");
        String target, other, admin, oldToken;
        Map<String, Object> original;
        List<Map<String, Object>> keys;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "OnlyMachine");
            other = RecordAccess.owner(db, "OnlyOther");
            admin = RecordAccess.owner(db, "admin");
            db.update("UPDATE os_user SET api_only=1 WHERE user_id=?", target);
            oldToken =
                    ApiKeys.issue(
                                    db,
                                    admin,
                                    target,
                                    map("label", "Old", "table", "incident", "days", 1))
                            .get("token")
                            .toString();
            original = db.one("SELECT * FROM os_user WHERE user_id=?", target);
            keys = db.all("SELECT * FROM os_api_key WHERE user_id=?", target);
            db.connection.commit();
            assertEquals(
                    403,
                    assertThrows(Failure.class, () -> ApiKeyOnly.replace(db, other, target, map()))
                            .status);
        }
        var recovery = h.json("POST", "/api/openservice/users/" + target + "/recovery", map("current_password", PASSWORD), auth("admin"));
        assertEquals(201, recovery.statusCode());
        String resetPath = object(parsed(recovery).get("result")).get("reset_path").toString();
        assertEquals(200, h.get(resetPath, null).statusCode());
        var body = map("label", "Only key", "table", "incident", "days", 1);
        String api = "/api/openservice/users/" + target + "/api-keys/key-only",
                form = "/admin/accounts/" + target + "/api-keys/key-only";
        assertEquals(401, h.json("POST", api, body, null).statusCode());
        assertEquals(403, h.json("POST", api, body, auth("OnlyOther")).statusCode());
        assertEquals(405, h.get(api, auth("admin")).statusCode());
        assertEquals(400, h.json("POST", api + "?x=1", body, auth("admin")).statusCode());
        assertEquals(
                404, h.json("POST", api.replace(target, id()), body, auth("admin")).statusCode());
        assertEquals(
                409, h.json("POST", api.replace(target, other), body, auth("admin")).statusCode());
        assertEquals(
                409, h.json("POST", api.replace(target, admin), body, auth("admin")).statusCode());
        assertEquals(
                400,
                h.json("POST", api, map("label", "", "table", "incident", "days", 1), auth("admin"))
                        .statusCode());
        assertEquals(
                404,
                h.json(
                                "POST",
                                api,
                                map("label", "new", "table", "unknown_table", "days", 1),
                                auth("admin"))
                        .statusCode());
        try (var db = h.store.open()) {
            assertEquals(original, db.one("SELECT * FROM os_user WHERE user_id=?", target));
            assertEquals(keys, db.all("SELECT * FROM os_api_key WHERE user_id=?", target));
            assertEquals(true, BasicAccess.state(db, other).get("allow_basic"));
            assertEquals(true, BasicAccess.state(db, admin).get("allow_basic"));
            ApiKeyOnly.replace(db, admin, target, body);
            db.connection.rollback();
            assertEquals(original, db.one("SELECT * FROM os_user WHERE user_id=?", target));
        }
        assertEquals(200, h.get(resetPath, null).statusCode());
        var response = h.json("POST", api, body, auth("admin"));
        assertEquals(201, response.statusCode());
        assertEquals(400, h.get(resetPath, null).statusCode());
        var result = object(parsed(response).get("result"));
        String token = result.get("token").toString();
        assertEquals(401, h.get("/api/now/table/incident", auth("OnlyMachine")).statusCode());
        assertEquals(401, h.get("/api/now/table/incident", "Bearer " + oldToken).statusCode());
        assertEquals(200, h.get("/api/now/table/incident", "Bearer " + token).statusCode());
        assertEquals(403, h.get("/api/now/table/problem", "Bearer " + token).statusCode());
        new Store(h.environment);
        assertEquals(200, h.get("/api/now/table/incident", "Bearer " + token).statusCode());
        String csrf = h.login("admin");
        assertEquals(
                403,
                h.form(
                                form,
                                map("csrf", "bad", "label", "x", "table", "incident", "days", "1"),
                                null)
                        .statusCode());
        assertEquals(
                400,
                h.form(
                                form,
                                map("csrf", csrf, "label", "x", "table", "incident", "days", "0"),
                                null)
                        .statusCode());
        assertEquals(
                200,
                h.form(
                                form,
                                map(
                                        "csrf",
                                        csrf,
                                        "label",
                                        "Replacement",
                                        "table",
                                        "incident",
                                        "days",
                                        "2"),
                                null)
                        .statusCode());
        assertEquals(401, h.get("/api/now/table/incident", "Bearer " + token).statusCode());
        try (var db = h.store.open()) {
            assertEquals(false, BasicAccess.state(db, target).get("allow_basic"));
            assertEquals(true, AccessMode.state(db, target).get("api_only"));
            assertEquals(1, ApiKeys.list(db, admin, target).size());
            var events =
                    db.all(
                            "SELECT * FROM os_security_event WHERE"
                                + " action='account.api_key_only_enabled'");
            assertEquals(2, events.size());
            assertEquals(admin, events.getFirst().get("actor_id"));
            assertEquals(target, events.getFirst().get("target_id"));
            assertEquals(
                    1,
                    ((Number) object(events.getFirst().get("detail")).get("removed_count"))
                            .intValue());
            assertFalse(events.toString().contains(token));
            assertNull(Authentication.verify(db, "OnlyMachine", PASSWORD, "browser"));
            BasicAccess.change(db, admin, target, map("allow_basic", true));
            db.connection.commit();
        }
        assertEquals(200, h.get("/api/now/table/incident", auth("OnlyMachine")).statusCode());
    }
}
