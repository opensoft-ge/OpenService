package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class ApiKeyLabelAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("LabelMachine", "agent");
        h.account("LabelOther", "reader");
        String target, other, admin, key, token, foreign;
        Map<String, Object> before;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "LabelMachine");
            other = RecordAccess.owner(db, "LabelOther");
            admin = RecordAccess.owner(db, "admin");
            db.update("UPDATE os_user SET api_only=1 WHERE user_id IN (?,?)", target, other);
            var issued =
                    ApiKeyWrites.issue(
                            db,
                            admin,
                            target,
                            map("label", "Original", "table", "incident", "days", 30));
            key = issued.get("key_id").toString();
            token = "Bearer " + issued.get("token");
            foreign =
                    ApiKeys.issue(
                                    db,
                                    admin,
                                    other,
                                    map("label", "Other", "table", "incident", "days", 30))
                            .get("key_id")
                            .toString();
            before = db.one("SELECT * FROM os_api_key WHERE key_id=?", key);
            db.connection.commit();
            assertEquals(
                    403,
                    assertThrows(Failure.class, () -> ApiKeyLabels.rename(db, other, target, map()))
                            .status);
        }
        String root = "/api/openservice/users/" + target + "/api-keys",
                api = root + "/label",
                form = "/admin/accounts/" + target + "/api-keys/label";
        var body = map("key_id", key, "label", "Revised");
        assertEquals(401, h.json("POST", api, body, null).statusCode());
        assertEquals(403, h.json("POST", api, body, auth("LabelOther")).statusCode());
        assertEquals(405, h.get(api, auth("admin")).statusCode());
        assertEquals(400, h.json("POST", api + "?x=1", body, auth("admin")).statusCode());
        assertEquals(
                404, h.json("POST", api.replace(target, id()), body, auth("admin")).statusCode());
        for (Object invalid :
                List.of(
                        map(),
                        map("key_id", key, "label", "ok", "extra", true),
                        map("key_id", 5, "label", "ok"),
                        map("key_id", "bad", "label", "ok")))
            assertEquals(400, h.json("POST", api, invalid, auth("admin")).statusCode());
        for (Object label :
                Arrays.asList(null, 5, " ", "x".repeat(81), "🙂".repeat(81), "bad\0label")) {
            assertEquals(
                    400,
                    h.json("POST", api, map("key_id", key, "label", label), auth("admin"))
                            .statusCode());
            assertEquals(
                    400,
                    h.json(
                                    "POST",
                                    root,
                                    map("label", label, "table", "incident", "days", 1),
                                    auth("admin"))
                            .statusCode());
        }
        for (String missing : List.of(id(), foreign))
            assertEquals(
                    404,
                    h.json("POST", api, map("key_id", missing, "label", "ok"), auth("admin"))
                            .statusCode());
        try (var db = h.store.open()) {
            ApiKeyLabels.rename(db, admin, target, body);
            db.connection.rollback();
            assertEquals(before, db.one("SELECT * FROM os_api_key WHERE key_id=?", key));
        }
        assertEquals(
                map("key_id", key, "label", "Revised", "changed", true),
                object(parsed(h.json("POST", api, body, auth("admin"))).get("result")));
        assertEquals(
                false,
                object(parsed(h.json("POST", api, body, auth("admin"))).get("result"))
                        .get("changed"));
        try (var db = h.store.open()) {
            var after = db.one("SELECT * FROM os_api_key WHERE key_id=?", key);
            after.put("label", before.get("label"));
            assertEquals(before, after);
            var events =
                    db.all(
                            "SELECT * FROM os_security_event WHERE"
                                + " action='account.api_key_labeled'");
            assertEquals(1, events.size());
            assertEquals(admin, events.getFirst().get("actor_id"));
            assertEquals(target, events.getFirst().get("target_id"));
            assertEquals(map("key_id", key), object(events.getFirst().get("detail")));
            assertFalse(JSON.writeValueAsString(events).contains(token.substring(7)));
        }
        assertEquals(200, h.get("/api/now/table/incident", token).statusCode());
        assertEquals(
                201,
                h.json(
                                "POST",
                                "/api/now/table/incident",
                                map("short_description", "After rename"),
                                token)
                        .statusCode());
        String csrf = h.login("admin");
        assertEquals(
                403,
                h.form(form, map("csrf", "bad", "key_id", key, "label", "No"), null).statusCode());
        String label = "<script>alert(1)</script>";
        assertEquals(
                302,
                h.form(form, map("csrf", csrf, "key_id", key, "label", label), null).statusCode());
        String html = html(h.get(form.substring(0, form.length() - 6), auth("admin")));
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains(label));
        assertEquals(
                200,
                h.json("POST", api, map("key_id", key, "label", "🙂".repeat(80)), auth("admin"))
                        .statusCode());
        assertEquals(
                201,
                h.json(
                                "POST",
                                root,
                                map("label", "🙂".repeat(80), "table", "incident", "days", 1),
                                auth("admin"))
                        .statusCode());
        new Store(h.environment);
        try (var db = h.store.open()) {
            assertEquals(
                    "🙂".repeat(80),
                    db.one("SELECT label FROM os_api_key WHERE key_id=?", key).get("label"));
            db.update("UPDATE os_api_key SET enabled=0,expires_at=0 WHERE key_id=?", key);
            db.connection.commit();
        }
        assertEquals(200, h.json("POST", api, body, auth("admin")).statusCode());
        assertEquals(401, h.get("/api/now/table/incident", token).statusCode());
        try (var db = h.store.open()) {
            var row = db.one("SELECT * FROM os_api_key WHERE key_id=?", key);
            assertEquals(0, ((Number) row.get("enabled")).intValue());
            assertEquals(0, ((Number) row.get("expires_at")).longValue());
        }
    }
}
