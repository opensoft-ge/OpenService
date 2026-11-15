package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class ApiKeyAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("KeyReader", "reader");
        h.account("KeyOther", "agent");
        String target, other;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "KeyReader");
            other = RecordAccess.owner(db, "KeyOther");
        }
        String api = "/api/openservice/users/" + target + "/api-keys",
                form = "/admin/accounts/" + target + "/api-keys";
        var value = map("label", "<script>fixture</script>", "table", "incident", "days", 1);
        assertEquals(401, h.get(api, null).statusCode());
        assertEquals(403, h.get(api, auth("KeyReader")).statusCode());
        assertEquals(409, h.json("POST", api, value, auth("admin")).statusCode());
        assertEquals(
                200,
                h.json(
                                "POST",
                                "/api/openservice/users/" + target + "/access-mode",
                                map("api_only", true),
                                auth("admin"))
                        .statusCode());
        var issued = h.json("POST", api, value, auth("admin"));
        assertEquals(201, issued.statusCode(), html(issued));
        var key = object(parsed(issued).get("result"));
        String token = key.get("token").toString(),
                bearer = "Bearer " + token,
                kid = key.get("key_id").toString();
        assertTrue(token.matches("osk_[A-Za-z0-9_-]{32}"));
        assertEquals(200, h.get("/api/now/table/incident", bearer).statusCode());
        assertEquals(
                200, h.request("HEAD", "/api/now/table/incident", null, null, bearer).statusCode());
        String record =
                object(
                                parsed(
                                                h.json(
                                                        "POST",
                                                        "/api/now/table/incident",
                                                        map("short_description", "API key record"),
                                                        auth("admin")))
                                        .get("result"))
                        .get("sys_id")
                        .toString();
        assertEquals(200, h.get("/api/now/table/incident/" + record, bearer).statusCode());
        try (var db = h.store.open()) {
            RecordAccess.change(
                    db,
                    Service.record(db, "incident", record),
                    RecordAccess.owner(db, "admin"),
                    map("visibility", "private", "owner_id", other));
            db.connection.commit();
        }
        assertEquals(404, h.get("/api/now/table/incident/" + record, bearer).statusCode());
        assertEquals(
                0, objects(parsed(h.get("/api/now/table/incident", bearer)).get("result")).size());
        var configured = new ConfigurationAcceptance(h);
        try {
            var definition = ConfigurationAcceptance.definition();
            object(object(definition.get("field_rules")).get("amount"))
                    .put("read_roles", List.of("admin"));
            assertEquals(
                    200,
                    configured
                            .install(
                                    ConfigurationAcceptance.bundle(configured.table, definition),
                                    false)
                            .statusCode());
            assertEquals(
                    201,
                    h.json(
                                    "POST",
                                    "/api/now/table/" + configured.table,
                                    map("short_description", "public", "amount", "1.5"),
                                    auth("admin"))
                            .statusCode());
            String scoped =
                    "Bearer "
                            + object(
                                            parsed(
                                                            h.json(
                                                                    "POST",
                                                                    api,
                                                                    map(
                                                                            "label",
                                                                            "Field test",
                                                                            "table",
                                                                            configured.table,
                                                                            "days",
                                                                            1),
                                                                    auth("admin")))
                                                    .get("result"))
                                    .get("token");
            var rows =
                    objects(
                            parsed(h.get("/api/now/table/" + configured.table, scoped))
                                    .get("result"));
            assertEquals(1, rows.size());
            assertFalse(rows.getFirst().containsKey("amount"));
            assertEquals(
                    400,
                    h.get(
                                    "/api/now/table/"
                                            + configured.table
                                            + "?sysparm_query=amount=1.5",
                                    scoped)
                            .statusCode());
            try (var db = h.store.open()) {
                db.update("DELETE FROM os_api_key WHERE table_name=?", configured.table);
                db.connection.commit();
            }
        } finally {
            configured.cleanup();
        }
        assertEquals(
                403,
                h.json(
                                "POST",
                                "/api/now/table/incident",
                                map("short_description", "denied"),
                                bearer)
                        .statusCode());
        for (String path :
                List.of(
                        "/api/now/table/problem",
                        "/api/openservice/me",
                        "/account",
                        "/export/incident",
                        api)) assertEquals(403, h.get(path, bearer).statusCode(), path);
        assertEquals(401, h.get("/api/now/table/incident", "Bearer invalid").statusCode());
        assertEquals(401, h.get("/api/now/table/incident", "Digest invalid").statusCode());
        assertEquals(401, h.get("/api/now/table/incident", "Basic !!!").statusCode());
        assertEquals(
                401, h.get("/api/now/table/incident", "Bearer osk_" + "a".repeat(32)).statusCode());
        var listed = h.get(api, auth("admin"));
        assertEquals(1, objects(parsed(listed).get("result")).size());
        assertFalse(html(listed).contains(token));
        assertFalse(html(listed).contains("token_hash"));
        new Store(h.environment);
        assertEquals(200, h.get("/api/now/table/incident", bearer).statusCode());
        assertEquals(400, h.get(api + "?extra=1", auth("admin")).statusCode());
        assertEquals(405, h.json("PUT", api, value, auth("admin")).statusCode());
        String revoke = api + "/" + kid + "/revoke";
        assertEquals(405, h.get(revoke, auth("admin")).statusCode());
        assertEquals(403, h.json("POST", revoke, map(), auth("KeyOther")).statusCode());
        assertEquals(
                false,
                object(
                                parsed(
                                                h.json(
                                                        "POST",
                                                        "/api/openservice/users/"
                                                                + other
                                                                + "/api-keys/"
                                                                + kid
                                                                + "/revoke",
                                                        map(),
                                                        auth("admin")))
                                        .get("result"))
                        .get("revoked"));
        assertEquals(
                true,
                object(parsed(h.json("POST", revoke, map(), auth("admin"))).get("result"))
                        .get("revoked"));
        assertEquals(
                false,
                object(parsed(h.json("POST", revoke, map(), auth("admin"))).get("result"))
                        .get("revoked"));
        assertEquals(401, h.get("/api/now/table/incident", bearer).statusCode());
        String csrf = h.login("admin");
        assertEquals(
                403,
                h.form(
                                form,
                                map("csrf", "bad", "label", "UI", "table", "incident", "days", "1"),
                                null)
                        .statusCode());
        assertEquals(
                400,
                h.form(
                                form,
                                map("csrf", csrf, "label", "UI", "table", "incident", "days", "91"),
                                null)
                        .statusCode());
        var created =
                h.form(
                        form,
                        map("csrf", csrf, "label", "UI", "table", "incident", "days", "90"),
                        null);
        assertEquals(200, created.statusCode(), html(created));
        assertTrue(html(created).contains("Save this key now"));
        String uiId =
                objects(parsed(h.get(api, auth("admin"))).get("result"))
                        .getFirst()
                        .get("key_id")
                        .toString();
        assertEquals(
                302, h.form(form + "/" + uiId + "/revoke", map("csrf", csrf), null).statusCode());
        assertFalse(html(h.get(form, null)).contains("Save this key now"));
        try (var db = h.store.open()) {
            assertEquals(
                    3,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                + " action='account.api_key_issued'"));
            assertEquals(
                    2,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                + " action='account.api_key_revoked'"));
            assertFalse(db.all("SELECT detail FROM os_security_event").toString().contains(token));
        }
    }
}
