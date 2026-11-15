package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class ProvisioningPasswordAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        for (String role : Service.ROLES.keySet()) {
            String name = "Provision" + role;
            var response =
                    h.json(
                            "POST",
                            "/api/openservice/users",
                            map(
                                    "name",
                                    name,
                                    "role",
                                    role,
                                    "password",
                                    PASSWORD,
                                    "require_password_change",
                                    true),
                            auth("admin"));
            assertEquals(201, response.statusCode(), html(response));
            assertEquals(map("name", name, "role", role), parsed(response).get("result"));
            assertEquals(403, h.get("/api/openservice/me", auth(name)).statusCode());
            try (var db = h.store.open()) {
                String target = RecordAccess.owner(db, name);
                assertEquals(map("required", true), PasswordReset.state(db, target));
                assertEquals(
                        1,
                        db.count(
                                "SELECT COUNT(*) FROM os_security_event WHERE target_id=? AND"
                                        + " action='account.created'",
                                target));
                assertEquals(
                        1,
                        db.count(
                                "SELECT COUNT(*) FROM os_security_event WHERE target_id=? AND"
                                        + " action='account.password_change_required'",
                                target));
            }
        }
        new Store(h.environment);
        assertEquals(403, h.get("/api/openservice/me", auth("Provisionreader")).statusCode());
        for (Object invalid : Arrays.asList(null, "true", 1, List.of(), map())) {
            assertEquals(
                    400,
                    h.json(
                                    "POST",
                                    "/api/openservice/users",
                                    map(
                                            "name",
                                            "InvalidProvision",
                                            "role",
                                            "reader",
                                            "password",
                                            PASSWORD,
                                            "require_password_change",
                                            invalid),
                                    auth("admin"))
                            .statusCode());
        }
        assertEquals(
                400,
                h.json(
                                "POST",
                                "/api/openservice/users",
                                map(
                                        "name",
                                        "InvalidProvision",
                                        "role",
                                        "reader",
                                        "password",
                                        PASSWORD,
                                        "require_password_change",
                                        true,
                                        "extra",
                                        true),
                                auth("admin"))
                        .statusCode());
        assertEquals(
                201,
                h.json(
                                "POST",
                                "/api/openservice/users",
                                map(
                                        "name",
                                        "PermanentProvision",
                                        "role",
                                        "reader",
                                        "password",
                                        PASSWORD,
                                        "require_password_change",
                                        false),
                                auth("admin"))
                        .statusCode());
        assertEquals(200, h.get("/api/openservice/me", auth("PermanentProvision")).statusCode());
        assertEquals(
                403,
                h.json(
                                "POST",
                                "/api/openservice/users",
                                map(
                                        "name",
                                        "DeniedProvision",
                                        "role",
                                        "reader",
                                        "password",
                                        PASSWORD,
                                        "require_password_change",
                                        true),
                                auth("PermanentProvision"))
                        .statusCode());
        assertEquals(401, h.json("POST", "/api/openservice/users", map(), null).statusCode());
        assertEquals(
                400,
                h.json(
                                "POST",
                                "/api/openservice/users",
                                map(
                                        "name",
                                        "PermanentProvision",
                                        "role",
                                        "admin",
                                        "password",
                                        PASSWORD,
                                        "require_password_change",
                                        true),
                                auth("admin"))
                        .statusCode());
        assertEquals(200, h.get("/api/openservice/me", auth("PermanentProvision")).statusCode());
        h.account("LegacyProvision", "reader");
        assertEquals(200, h.get("/api/openservice/me", auth("LegacyProvision")).statusCode());
        String invitation =
                object(
                                parsed(
                                                h.json(
                                                        "POST",
                                                        "/api/openservice/invitations",
                                                        map(
                                                                "name",
                                                                "InvitedProvision",
                                                                "role",
                                                                "reader"),
                                                        auth("admin")))
                                        .get("result"))
                        .get("accept_path")
                        .toString();
        assertEquals(
                302,
                h.form(
                                invitation,
                                map("csrf", csrf(h.get(invitation, null)), "password", PASSWORD),
                                null)
                        .statusCode());
        assertEquals(200, h.get("/api/openservice/me", auth("InvitedProvision")).statusCode());
        assertEquals(
                200,
                h.json(
                                "POST",
                                "/api/openservice/me/password",
                                map(
                                        "current_password",
                                        PASSWORD,
                                        "new_password",
                                        "new-provisioned-password"),
                                auth("Provisionreader"))
                        .statusCode());
        assertEquals(
                200,
                h.get(
                                "/api/openservice/me",
                                PasswordLengthAcceptance.credentials(
                                        "Provisionreader", "new-provisioned-password"))
                        .statusCode());
        var login = h.get("/login", null);
        assertEquals(
                302,
                h.form(
                                "/login",
                                map("csrf", csrf(login), "username", "admin", "password", PASSWORD),
                                null)
                        .statusCode());
        String token = csrf(h.get("/admin/accounts", null));
        assertEquals(
                302,
                h.form(
                                "/admin/accounts?role=admin&page=1&status=active",
                                map(
                                        "csrf",
                                        token,
                                        "name",
                                        "FilteredProvision",
                                        "role",
                                        "reader",
                                        "password",
                                        PASSWORD,
                                        "require_password_change",
                                        "true"),
                                null)
                        .statusCode());
        try (var db = h.store.open()) {
            var account = db.one("SELECT * FROM os_user WHERE name='FilteredProvision'");
            assertEquals("reader", account.get("role"));
            assertTrue(PasswordReset.required(account));
        }
        for (String repeated :
                List.of(
                        "role=reader&role=admin",
                        "require_password_change=true&require_password_change=false")) {
            assertEquals(
                    400,
                    h.request(
                                    "POST",
                                    "/admin/accounts?role=agent",
                                    ("csrf=" + h.basicCsrf(auth("admin")) + "&name=RepeatedProvision&password=" + PASSWORD + "&" + repeated)
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    "application/x-www-form-urlencoded",
                                    auth("admin"))
                            .statusCode());
        }
        assertEquals(
                400,
                h.form(
                                "/admin/accounts",
                                map(
                                        "csrf",
                                        token,
                                        "name",
                                        "InvalidFormProvision",
                                        "role",
                                        "reader",
                                        "password",
                                        PASSWORD,
                                        "require_password_change",
                                        "maybe"),
                                null)
                        .statusCode());
        try (var db = h.store.open()) {
            assertEquals(
                    0,
                    db.count(
                            "SELECT COUNT(*) FROM os_user WHERE name IN"
                                + " ('InvalidProvision','DeniedProvision','InvalidFormProvision')"));
        }
    }
}
