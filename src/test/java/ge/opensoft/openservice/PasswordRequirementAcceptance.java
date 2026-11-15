package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class PasswordRequirementAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("RequiredReader", "reader");
        String target;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "RequiredReader");
        }
        String api = "/api/openservice/users/" + target + "/require-password-change";
        assertEquals(200, h.get(api, auth("admin")).statusCode());
        assertEquals(map("result", map("required", false)), parsed(h.get(api, auth("admin"))));
        String oldCsrf = h.login("RequiredReader");
        String oldRecovery =
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
        assertEquals(200, h.get("/api/openservice/me", null).statusCode());
        assertEquals(403, h.get(api, null).statusCode());
        assertEquals(403, h.json("POST", api, map(), auth("RequiredReader")).statusCode());
        assertEquals(400, h.json("POST", api, map("required", false), auth("admin")).statusCode());
        assertEquals(400, h.json("POST", api + "?x=1", map(), auth("admin")).statusCode());
        assertEquals(400, h.get(api + "?x=1", auth("admin")).statusCode());
        assertEquals(405, h.json("DELETE", api, map(), auth("admin")).statusCode());
        assertEquals(
                404,
                h.get(
                                "/api/openservice/users/"
                                        + "0".repeat(32)
                                        + "/require-password-change",
                                auth("admin"))
                        .statusCode());
        assertEquals(200, h.json("POST", api, map(), auth("admin")).statusCode());
        assertEquals(400, h.get(oldRecovery, null).statusCode());
        assertEquals(map("result", map("required", true)), parsed(h.get(api, auth("admin"))));
        long revision;
        try (var db = h.store.open()) {
            revision =
                    ((Number)
                                    db.one(
                                                    "SELECT auth_revision FROM os_user WHERE"
                                                            + " user_id=?",
                                                    target)
                                            .get("auth_revision"))
                            .longValue();
        }
        assertEquals(200, h.json("POST", api, map(), auth("admin")).statusCode());
        try (var db = h.store.open()) {
            assertEquals(
                    revision,
                    ((Number)
                                    db.one(
                                                    "SELECT auth_revision FROM os_user WHERE"
                                                            + " user_id=?",
                                                    target)
                                            .get("auth_revision"))
                            .longValue());
            assertEquals(
                    1,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                    + " action='account.password_change_required'"));
        }
        assertEquals(401, h.get("/api/openservice/me", null).statusCode());
        new Store(h.environment);
        for (String path :
                List.of(
                        "/api/openservice/me",
                        "/api/openservice/schema",
                        "/api/now/table/incident",
                        "/api/now/stats/incident",
                        "/api/openservice/configuration",
                        "/api/openservice/me/authentication-events"))
            assertEquals(403, h.get(path, auth("RequiredReader")).statusCode(), path);
        assertEquals(
                403,
                h.json(
                                "POST",
                                "/api/now/table/incident",
                                map("short_description", "must-not-exist"),
                                auth("RequiredReader"))
                        .statusCode());
        assertEquals(302, h.get("/", auth("RequiredReader")).statusCode());
        assertEquals(
                "/password-required",
                h.get("/", auth("RequiredReader")).headers().firstValue("Location").orElseThrow());
        assertEquals(302, h.get("/password-required", auth("admin")).statusCode());
        String token = csrf(h.get("/login", null));
        var login =
                h.form(
                        "/login",
                        map("csrf", token, "username", "RequiredReader", "password", PASSWORD),
                        null);
        assertEquals(302, login.statusCode());
        assertEquals("/password-required", login.headers().firstValue("Location").orElseThrow());
        var page = h.get("/password-required", null);
        assertEquals(200, page.statusCode());
        assertFalse(html(page).contains("Connected workspace"));
        token = csrf(page);
        assertEquals(
                403,
                h.form(
                                "/password-required",
                                map(
                                        "csrf",
                                        oldCsrf,
                                        "current_password",
                                        PASSWORD,
                                        "new_password",
                                        "replacement-secret-long"),
                                null)
                        .statusCode());
        assertEquals(400, h.get("/password-required?x=1", null).statusCode());
        assertEquals(
                405,
                h.json("PUT", "/password-required", map(), auth("RequiredReader")).statusCode());
        for (int i = 0; i < 6; i++) {
            var bad =
                    h.form(
                            "/password-required",
                            map(
                                    "csrf",
                                    token,
                                    "current_password",
                                    "wrong",
                                    "new_password",
                                    "replacement-secret-long"),
                            null);
            assertEquals(i < 5 ? 400 : 429, bad.statusCode());
            assertFalse(html(bad).contains("Connected workspace"));
            if (i == 5) assertEquals("300", bad.headers().firstValue("Retry-After").orElseThrow());
        }
        try (var db = h.store.open()) {
            db.update("DELETE FROM os_auth_attempt");
            db.connection.commit();
        }
        assertEquals(
                400,
                h.form(
                                "/password-required",
                                map(
                                        "csrf",
                                        token,
                                        "current_password",
                                        PASSWORD,
                                        "new_password",
                                        PASSWORD),
                                null)
                        .statusCode());
        assertEquals(
                302,
                h.form(
                                "/password-required",
                                map(
                                        "csrf",
                                        token,
                                        "current_password",
                                        PASSWORD,
                                        "new_password",
                                        "replacement-secret-long"),
                                null)
                        .statusCode());
        assertEquals(401, h.get("/api/openservice/me", null).statusCode());
        assertEquals(
                200,
                h.get(
                                "/api/openservice/me",
                                PasswordLengthAcceptance.credentials(
                                        "RequiredReader", "replacement-secret-long"))
                        .statusCode());
        assertEquals(map("result", map("required", false)), parsed(h.get(api, auth("admin"))));
        for (String role : List.of("admin", "agent", "requester")) {
            String name = "Required" + role;
            h.account(name, role);
            String id;
            try (var db = h.store.open()) {
                id = RecordAccess.owner(db, name);
            }
            String route = "/api/openservice/users/" + id + "/require-password-change";
            assertEquals(200, h.json("POST", route, map(), auth("admin")).statusCode());
            assertEquals(403, h.get("/api/openservice/me", auth(name)).statusCode());
            assertEquals(
                    400,
                    h.json(
                                    "POST",
                                    "/api/openservice/me/password",
                                    map("current_password", PASSWORD, "new_password", "short"),
                                    auth(name))
                            .statusCode());
            assertEquals(
                    200,
                    h.json(
                                    "POST",
                                    "/api/openservice/me/password",
                                    map(
                                            "current_password",
                                            PASSWORD,
                                            "new_password",
                                            "new-long-password-value"),
                                    auth(name))
                            .statusCode());
            assertEquals(
                    200,
                    h.get(
                                    "/api/openservice/me",
                                    PasswordLengthAcceptance.credentials(
                                            name, "new-long-password-value"))
                            .statusCode());
        }
        h.account("RequiredRecovery", "reader");
        String recoverId;
        try (var db = h.store.open()) {
            recoverId = RecordAccess.owner(db, "RequiredRecovery");
        }
        String route = "/api/openservice/users/" + recoverId;
        assertEquals(
                200,
                h.json("POST", route + "/require-password-change", map(), auth("admin"))
                        .statusCode());
        String recover =
                object(
                                parsed(
                                                h.json(
                                                        "POST",
                                                        route + "/recovery",
                                                        map("current_password", PASSWORD),
                                                        auth("admin")))
                                        .get("result"))
                        .get("reset_path")
                        .toString();
        token = csrf(h.get(recover, null));
        assertEquals(
                302,
                h.form(recover, map("csrf", token, "password", "recovered-required-password"), null)
                        .statusCode());
        assertEquals(
                map("result", map("required", false)),
                parsed(h.get(route + "/require-password-change", auth("admin"))));
        assertEquals(
                200,
                h.get(
                                "/api/openservice/me",
                                PasswordLengthAcceptance.credentials(
                                        "RequiredRecovery", "recovered-required-password"))
                        .statusCode());
    }
}
