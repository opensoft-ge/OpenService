package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class AccessModeAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("IntegrationReader", "reader");
        h.account("IntegrationAgent", "agent");
        h.account("IntegrationRequester", "requester");
        h.account("IntegrationAdmin", "admin");
        String reader, admin;
        try (var db = h.store.open()) {
            reader = RecordAccess.owner(db, "IntegrationReader");
            admin = RecordAccess.owner(db, "admin");
        }
        String route = "/api/openservice/users/" + reader + "/access-mode";
        String oldRecovery =
                object(
                                parsed(
                                                h.json(
                                                        "POST",
                                                        "/api/openservice/users/"
                                                                + reader
                                                                + "/recovery",
                                                        map("current_password", PASSWORD),
                                                        auth("admin")))
                                        .get("result"))
                        .get("reset_path")
                        .toString();
        assertEquals(200, h.get(route, auth("admin")).statusCode());
        assertEquals(map("api_only", false), parsed(h.get(route, auth("admin"))).get("result"));
        String token = h.login("IntegrationReader");
        assertEquals(200, h.get("/account", null).statusCode());
        assertEquals(200, h.json("POST", route, map("api_only", true), auth("admin")).statusCode());
        assertEquals(400, h.get(oldRecovery, null).statusCode());
        assertEquals(401, h.get("/api/openservice/me", null).statusCode());
        assertEquals(302, h.get("/account", null).statusCode());
        for (String path :
                List.of(
                        "/",
                        "/account",
                        "/admin/accounts",
                        "/export/incident",
                        "/password-required"))
            assertEquals(403, h.get(path, auth("IntegrationReader")).statusCode(), path);
        assertEquals(200, h.get("/api/openservice/me", auth("IntegrationReader")).statusCode());
        String confidential = h.add("incident").get("sys_id").toString();
        assertEquals(
                200,
                h.json(
                                "PATCH",
                                "/api/openservice/access/incident/" + confidential,
                                map("visibility", "private", "owner_id", admin),
                                auth("admin"))
                        .statusCode());
        assertEquals(
                404,
                h.get("/api/now/table/incident/" + confidential, auth("IntegrationReader"))
                        .statusCode());
        assertEquals(
                List.of(),
                parsed(h.get("/api/now/table/incident", auth("IntegrationReader"))).get("result"));
        assertEquals(
                403,
                h.json(
                                "POST",
                                "/api/now/table/incident",
                                map("short_description", "Denied"),
                                auth("IntegrationReader"))
                        .statusCode());
        assertEquals(
                401,
                h.form(
                                "/login",
                                map(
                                        "csrf",
                                        csrf(h.get("/login", null)),
                                        "username",
                                        "IntegrationReader",
                                        "password",
                                        PASSWORD),
                                null)
                        .statusCode());
        assertEquals(200, h.get("/api/openservice/me", auth("IntegrationReader")).statusCode());
        assertEquals(200, h.json("POST", route, map("api_only", true), auth("admin")).statusCode());
        try (var db = h.store.open()) {
            assertEquals(
                    1,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE target_id=? AND"
                                    + " action='account.access_mode_changed'",
                            reader));
        }
        new Store(h.environment);
        assertEquals(403, h.get("/account", auth("IntegrationReader")).statusCode());
        for (Object invalid : Arrays.asList(null, "true", 1, List.of()))
            assertEquals(
                    400,
                    h.json("POST", route, map("api_only", invalid), auth("admin")).statusCode());
        for (Object invalid : List.of(map(), map("api_only", true, "extra", true), List.of()))
            assertEquals(400, h.json("POST", route, invalid, auth("admin")).statusCode());
        assertEquals(403, h.get(route, auth("IntegrationReader")).statusCode());
        assertEquals(
                403,
                h.json("POST", route, map("api_only", false), auth("IntegrationReader"))
                        .statusCode());
        assertEquals(401, h.get(route, null).statusCode());
        String adminCsrf = h.login("admin");
        String browserMode = "/admin/accounts/" + reader + "/access-mode";
        assertEquals(
                403,
                h.form(browserMode, map("csrf", "wrong", "api_only", "false"), null).statusCode());
        assertEquals(400, h.form(browserMode, map("csrf", adminCsrf), null).statusCode());
        assertEquals(
                400,
                h.form(browserMode, map("csrf", adminCsrf, "api_only", "maybe"), null)
                        .statusCode());
        assertEquals(405, h.get(browserMode, auth("admin")).statusCode());
        assertEquals(map("api_only", true), parsed(h.get(route, auth("admin"))).get("result"));
        assertEquals(400, h.get(route + "?extra=1", auth("admin")).statusCode());
        assertEquals(
                400,
                h.json("POST", route + "?extra=1", map("api_only", true), auth("admin"))
                        .statusCode());
        assertEquals(405, h.json("DELETE", route, map(), auth("admin")).statusCode());
        assertEquals(
                404,
                h.get("/api/openservice/users/" + "f".repeat(32) + "/access-mode", auth("admin"))
                        .statusCode());
        assertEquals(
                404,
                h.json(
                                "POST",
                                "/api/openservice/users/" + "f".repeat(32) + "/access-mode",
                                map("api_only", true),
                                auth("admin"))
                        .statusCode());
        assertEquals(
                200,
                h.json(
                                "POST",
                                "/api/openservice/users/" + reader + "/require-password-change",
                                map(),
                                auth("admin"))
                        .statusCode());
        assertEquals(403, h.get("/api/openservice/me", auth("IntegrationReader")).statusCode());
        assertEquals(
                200,
                h.json(
                                "POST",
                                "/api/openservice/me/password",
                                map(
                                        "current_password",
                                        PASSWORD,
                                        "new_password",
                                        "integration-reader-replaced"),
                                auth("IntegrationReader"))
                        .statusCode());
        assertEquals(
                200,
                h.get(
                                "/api/openservice/me",
                                PasswordLengthAcceptance.credentials(
                                        "IntegrationReader", "integration-reader-replaced"))
                        .statusCode());
        assertEquals(
                403,
                h.get(
                                "/account",
                                PasswordLengthAcceptance.credentials(
                                        "IntegrationReader", "integration-reader-replaced"))
                        .statusCode());
        String freshRecovery =
                object(
                                parsed(
                                                h.json(
                                                        "POST",
                                                        "/api/openservice/users/"
                                                                + reader
                                                                + "/recovery",
                                                        map("current_password", PASSWORD),
                                                        auth("admin")))
                                        .get("result"))
                        .get("reset_path")
                        .toString();
        assertEquals(
                302,
                h.form(
                                freshRecovery,
                                map(
                                        "csrf",
                                        csrf(h.get(freshRecovery, null)),
                                        "password",
                                        "integration-reader-recovered"),
                                null)
                        .statusCode());
        assertEquals(
                200,
                h.get(
                                "/api/openservice/me",
                                PasswordLengthAcceptance.credentials(
                                        "IntegrationReader", "integration-reader-recovered"))
                        .statusCode());
        assertEquals(
                403,
                h.get(
                                "/account",
                                PasswordLengthAcceptance.credentials(
                                        "IntegrationReader", "integration-reader-recovered"))
                        .statusCode());
        for (String name :
                List.of("IntegrationAgent", "IntegrationRequester", "IntegrationAdmin")) {
            String id;
            try (var db = h.store.open()) {
                id = RecordAccess.owner(db, name);
            }
            assertEquals(
                    200,
                    h.json(
                                    "POST",
                                    "/api/openservice/users/" + id + "/access-mode",
                                    map("api_only", true),
                                    auth("admin"))
                            .statusCode());
            assertEquals(200, h.get("/api/openservice/me", auth(name)).statusCode());
            assertEquals(403, h.get("/account", auth(name)).statusCode());
        }
        assertEquals(
                409,
                h.json(
                                "POST",
                                "/api/openservice/users/" + admin + "/access-mode",
                                map("api_only", true),
                                auth("admin"))
                        .statusCode());
        assertEquals(
                409,
                h.json(
                                "PATCH",
                                "/api/openservice/users/" + admin + "/status",
                                map("active", false),
                                auth("IntegrationAdmin"))
                        .statusCode());
        assertEquals(
                409,
                h.json(
                                "PATCH",
                                "/api/openservice/users/" + admin + "/role",
                                map("role", "reader"),
                                auth("IntegrationAdmin"))
                        .statusCode());
        assertEquals(
                200, h.json("POST", route, map("api_only", false), auth("admin")).statusCode());
        assertEquals(
                200,
                h.get(
                                "/account",
                                PasswordLengthAcceptance.credentials(
                                        "IntegrationReader", "integration-reader-recovered"))
                        .statusCode());
    }
}
