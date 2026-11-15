package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class PasswordExclusionAcceptance {
    static final String API = "/api/openservice/password-exclusions",
            PAGE = "/admin/password-exclusions",
            BLOCKED = "obvious-organization-password";

    static Map<String, Object> entry(String password) {
        return map("label", "Organization example", "password", password);
    }

    static void run(WorkflowSupport h) throws Exception {
        assertEquals(401, h.get(API, null).statusCode());
        var response = h.json("POST", API, entry(BLOCKED), auth("admin"));
        assertEquals(201, response.statusCode(), html(response));
        var item = object(parsed(response).get("result"));
        assertEquals(Set.of("exclusion_id", "label", "created_at"), item.keySet());
        String id = item.get("exclusion_id").toString(), remove = API + "/" + id;
        assertFalse(html(response).contains(BLOCKED));
        assertFalse(html(h.get(API, auth("admin"))).contains("digest"));
        assertEquals(409, h.json("POST", API, entry(BLOCKED), auth("admin")).statusCode());
        for (Object bad :
                List.of(
                        List.of(),
                        map(),
                        map("label", 1, "password", BLOCKED),
                        map("label", " ", "password", BLOCKED),
                        map("label", "x".repeat(81), "password", BLOCKED),
                        map("label", "bad\nlabel", "password", BLOCKED),
                        map("label", "ok", "password", 1),
                        entry(""),
                        entry("x".repeat(1025)),
                        map("label", "ok", "password", BLOCKED, "extra", 1)))
            assertEquals(400, h.json("POST", API, bad, auth("admin")).statusCode());
        for (String role : List.of("agent", "reader", "requester")) {
            String name = "Excluded" + role;
            h.account(name, role);
            assertEquals(403, h.get(API, auth(name)).statusCode());
            assertEquals(403, h.get(PAGE, auth(name)).statusCode());
            assertEquals(403, h.json("POST", API, entry("other-value"), auth(name)).statusCode());
            assertEquals(403, h.json("DELETE", remove, map(), auth(name)).statusCode());
        }
        var account = map("name", "ExcludedNew", "role", "reader", "password", BLOCKED);
        assertEquals(
                400, h.json("POST", "/api/openservice/users", account, auth("admin")).statusCode());
        String csrf = h.login("admin");
        var form =
                h.form(
                        "/admin/accounts",
                        map(
                                "csrf",
                                csrf,
                                "name",
                                "ExcludedNew",
                                "role",
                                "reader",
                                "password",
                                BLOCKED),
                        null);
        assertEquals(400, form.statusCode());
        assertFalse(html(form).contains(BLOCKED));
        assertEquals(
                400,
                h.json(
                                "POST",
                                "/api/openservice/me/password",
                                map("current_password", PASSWORD, "new_password", BLOCKED),
                                auth("Excludedreader"))
                        .statusCode());
        assertEquals(200, h.get("/api/openservice/me", auth("Excludedreader")).statusCode());
        String invite =
                object(
                                parsed(
                                                h.json(
                                                        "POST",
                                                        "/api/openservice/invitations",
                                                        map(
                                                                "name",
                                                                "ExcludedInvite",
                                                                "role",
                                                                "reader"),
                                                        auth("admin")))
                                        .get("result"))
                        .get("accept_path")
                        .toString();
        String joinCsrf = csrf(h.get(invite, null));
        assertEquals(
                400, h.form(invite, map("csrf", joinCsrf, "password", BLOCKED), null).statusCode());
        assertEquals(200, h.get(invite, null).statusCode());
        String target =
                object(parsed(h.get("/api/openservice/me", auth("Excludedreader"))).get("result"))
                        .get("user_id")
                        .toString();
        String recover =
                object(
                                parsed(
                                                h.json(
                                                        "POST",
                                                        "/api/openservice/users/"
                                                                + target
                                                                + "/recovery",
                                                        map("current_password", PASSWORD),
                                                        auth("admin")))
                                        .get("result"))
                        .get("reset_path")
                        .toString();
        String recoverCsrf = csrf(h.get(recover, null));
        assertEquals(
                400,
                h.form(recover, map("csrf", recoverCsrf, "password", BLOCKED), null).statusCode());
        assertEquals(200, h.get(recover, null).statusCode());
        new Store(h.environment);
        assertEquals(
                400, h.json("POST", "/api/openservice/users", account, auth("admin")).statusCode());
        try (var db = h.store.open()) {
            var stored = db.one("SELECT * FROM os_password_exclusion WHERE exclusion_id=?", id);
            assertEquals(Passwords.tokenHash(BLOCKED), stored.get("digest"));
            assertFalse(stored.toString().contains(BLOCKED));
            assertEquals(
                    0L,
                    db.count(
                            "SELECT COUNT(*) FROM os_user WHERE name='ExcludedNew' OR"
                                + " name='ExcludedInvite'"));
            var audit =
                    db.one(
                            "SELECT detail FROM os_security_event WHERE"
                                + " action='policy.password_excluded'");
            assertEquals(map("exclusion_id", id), audit.get("detail"));
        }
        assertEquals(405, h.json("PUT", API, map(), auth("admin")).statusCode());
        assertEquals(405, h.get(remove, auth("admin")).statusCode());
        assertEquals(
                400,
                h.json("POST", API + "?page=1", entry("another-excluded-value"), auth("admin"))
                        .statusCode());
        assertEquals(400, h.json("DELETE", remove + "?x=1", map(), auth("admin")).statusCode());
        assertEquals(400, h.json("DELETE", remove, map("extra", true), auth("admin")).statusCode());
        assertEquals(
                404,
                h.json("DELETE", API + "/" + "0".repeat(32), map(), auth("admin")).statusCode());
        assertEquals(403, h.form(PAGE + "/" + id + "/remove", map(), null).statusCode());
        assertEquals(
                302, h.form(PAGE + "/" + id + "/remove", map("csrf", csrf), null).statusCode());
        assertEquals(
                201, h.json("POST", "/api/openservice/users", account, auth("admin")).statusCode());
        assertEquals(
                302, h.form(invite, map("csrf", joinCsrf, "password", BLOCKED), null).statusCode());
        // Recovery also works after removing the exclusion; its rejected attempt did not consume
        // it.
        recoverCsrf = csrf(h.get(recover, null));
        assertEquals(
                302,
                h.form(recover, map("csrf", recoverCsrf, "password", BLOCKED), null).statusCode());
        assertEquals(400, h.get(recover, null).statusCode());
        String boundary =
                object(
                                parsed(
                                                h.json(
                                                        "POST",
                                                        API,
                                                        map(
                                                                "label",
                                                                "x".repeat(80),
                                                                "password",
                                                                "🌟".repeat(1024)),
                                                        auth("admin")))
                                        .get("result"))
                        .get("exclusion_id")
                        .toString();
        assertEquals(
                200, h.json("DELETE", API + "/" + boundary, map(), auth("admin")).statusCode());
        try (var db = h.store.open()) {
            for (int i = 0; i < 1000; i++)
                db.update(
                        "INSERT INTO os_password_exclusion(exclusion_id,label,digest,created_at)"
                            + " VALUES(?,?,?,?)",
                        id(),
                        "Fixture " + i,
                        Passwords.tokenHash("fixture-value-" + i),
                        now());
            db.connection.commit();
        }
        var full = parsed(h.get(API + "?page=10", auth("admin")));
        assertEquals(1000, full.get("total"));
        assertEquals(10, full.get("pages"));
        assertEquals(100, objects(full.get("result")).size());
        assertEquals(
                409,
                h.json("POST", API, entry("limit-must-block-this"), auth("admin")).statusCode());
        for (String query :
                List.of(
                        "page=0",
                        "page=01",
                        "page=1000000",
                        "page=11",
                        "page=1&page=2",
                        "unknown=1"))
            assertEquals(400, h.get(API + "?" + query, auth("admin")).statusCode());
        try (var db = h.store.open()) {
            db.update("DELETE FROM os_password_exclusion");
            db.connection.commit();
        }
        assertEquals(List.of(), parsed(h.get(API, auth("admin"))).get("result"));
    }
}
