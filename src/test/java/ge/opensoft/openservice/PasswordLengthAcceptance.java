package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class PasswordLengthAcceptance {
    static String credentials(String name, String password) {
        return "Basic "
                + Base64.getEncoder()
                        .encodeToString(
                                (name + ":" + password)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static void run(WorkflowSupport h) throws Exception {
        for (String password : List.of("x".repeat(14), "🌟".repeat(14), "🌟".repeat(1025))) {
            var response =
                    h.json(
                            "POST",
                            "/api/openservice/users",
                            map("name", "LengthRejected", "role", "reader", "password", password),
                            auth("admin"));
            assertEquals(400, response.statusCode(), html(response));
            assertFalse(html(response).contains(password));
        }
        for (String password : List.of("x".repeat(15), "🌟".repeat(15), "🌟".repeat(1024))) {
            String name = "Length" + id();
            assertEquals(
                    201,
                    h.json(
                                    "POST",
                                    "/api/openservice/users",
                                    map("name", name, "role", "reader", "password", password),
                                    auth("admin"))
                            .statusCode());
            assertEquals(
                    200, h.get("/api/openservice/me", credentials(name, password)).statusCode());
        }
        h.account("LegacyLength", "reader");
        String legacy = "x".repeat(12), hash = Passwords.hash(legacy), target;
        try (var db = h.store.open()) {
            db.update("UPDATE os_user SET password=? WHERE name='LegacyLength'", hash);
            target = RecordAccess.owner(db, "LegacyLength");
            db.connection.commit();
        }
        new Store(h.environment);
        assertEquals(
                200,
                h.get("/api/openservice/me", credentials("LegacyLength", legacy)).statusCode());
        assertEquals(
                400,
                h.json(
                                "POST",
                                "/api/openservice/me/password",
                                map("current_password", legacy, "new_password", "🌟".repeat(14)),
                                credentials("LegacyLength", legacy))
                        .statusCode());
        try (var db = h.store.open()) {
            assertEquals(
                    hash,
                    db.one("SELECT password FROM os_user WHERE user_id=?", target).get("password"));
        }
        String path =
                object(
                                parsed(
                                                h.json(
                                                        "POST",
                                                        "/api/openservice/invitations",
                                                        map(
                                                                "name",
                                                                "LengthInvite",
                                                                "role",
                                                                "reader"),
                                                        auth("admin")))
                                        .get("result"))
                        .get("accept_path")
                        .toString();
        String token = csrf(h.get(path, null));
        assertEquals(
                400,
                h.form(path, map("csrf", token, "password", "🌟".repeat(14)), null).statusCode());
        assertEquals(200, h.get(path, null).statusCode());
        assertEquals(
                302,
                h.form(path, map("csrf", token, "password", "🌟".repeat(15)), null).statusCode());
        path =
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
        token = csrf(h.get(path, null));
        assertEquals(
                400,
                h.form(path, map("csrf", token, "password", "🌟".repeat(14)), null).statusCode());
        assertEquals(200, h.get(path, null).statusCode());
        assertEquals(
                302,
                h.form(path, map("csrf", token, "password", "🌟".repeat(15)), null).statusCode());
        assertEquals(
                401,
                h.get("/api/openservice/me", credentials("LegacyLength", legacy)).statusCode());
        assertEquals(
                200,
                h.json(
                                "POST",
                                "/api/openservice/me/password",
                                map(
                                        "current_password",
                                        "🌟".repeat(15),
                                        "new_password",
                                        "z".repeat(15)),
                                credentials("LegacyLength", "🌟".repeat(15)))
                        .statusCode());
        assertEquals(
                200,
                h.get("/api/openservice/me", credentials("LegacyLength", "z".repeat(15)))
                        .statusCode());
    }
}
