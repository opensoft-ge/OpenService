package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.*;

final class EmailEnrollmentAcceptance {
    static final String API = "/api/openservice/me/email";

    static Map<String, Object> payload(String email) {
        return map("email", email, "current_password", PASSWORD);
    }

    static String request(WorkflowSupport h, String name, String email) throws Exception {
        var response = h.json("POST", API, payload(email), auth(name));
        assertEquals(202, response.statusCode(), html(response));
        assertFalse(html(response).contains("verify-email/"));
        return object(parsed(response).get("issued")).get("delivery_id").toString();
    }

    static String path(WorkflowSupport h, String job) throws Exception {
        try (var db = h.store.open()) {
            return Outbox.decrypt(
                            h.store.secret,
                            db.one("SELECT envelope FROM os_outbox WHERE job_id=?", job)
                                    .get("envelope")
                                    .toString())
                    .get("path")
                    .toString();
        }
    }

    static void age(WorkflowSupport h, String name) throws Exception {
        try (var db = h.store.open()) {
            db.update(
                    "UPDATE os_email_enrollment SET created_at=0 WHERE user_id=?",
                    RecordAccess.owner(db, name));
            db.connection.commit();
        }
    }

    static void run(WorkflowSupport h) throws Exception {
        h.account("EmailLocal", "reader");
        h.account("EmailOther", "requester");
        assertEquals(401, h.get(API, null).statusCode());
        assertEquals(405, h.json("PUT", API, map(), auth("EmailLocal")).statusCode());
        assertEquals(
                405, h.json("DELETE", "/account/email", map(), auth("EmailLocal")).statusCode());
        assertEquals(400, h.get(API + "?other=x", auth("EmailLocal")).statusCode());
        assertEquals(
                503,
                h.json("POST", API, payload("local@example.invalid"), auth("EmailLocal"))
                        .statusCode());
        for (var body :
                List.of(
                        map(),
                        map("email", "a@example.invalid", "current_password", 5),
                        map("email", "a@example.invalid", "current_password", "x".repeat(1025)),
                        payload("invalid")))
            assertEquals(400, h.json("POST", API, body, auth("EmailLocal")).statusCode());
        var properties = ((ConfigurableEnvironment) h.environment).getPropertySources();
        try (var sink = new MailSink()) {
            properties.addFirst(
                    new MapPropertySource("enrollment-mail", ReleaseAcceptance.settings(sink)));
            assertEquals(
                    400,
                    h.json(
                                    "POST",
                                    API,
                                    map(
                                            "email",
                                            "local@example.invalid",
                                            "current_password",
                                            "wrong"),
                                    auth("EmailLocal"))
                            .statusCode());
            try (var db = h.store.open()) {
                for (int i = 0; i < 1000; i++)
                    DeliveryOperationsAcceptance.seed(db, "queued", 1, 0, Long.MAX_VALUE);
                db.connection.commit();
            }
            assertEquals(
                    429,
                    h.json("POST", API, payload("local@example.invalid"), auth("EmailLocal"))
                            .statusCode());
            try (var db = h.store.open()) {
                assertEquals(0L, db.count("SELECT COUNT(*) FROM os_email_enrollment"));
                assertEquals(
                        0L,
                        db.count(
                                "SELECT COUNT(*) FROM os_security_event WHERE"
                                    + " action='account.email_enrollment_requested'"));
                db.update("DELETE FROM os_outbox");
                db.connection.commit();
            }
            String first = request(h, "EmailLocal", "Local@EXAMPLE.invalid"),
                    firstPath = path(h, first);
            assertEquals(
                    429,
                    h.json("POST", API, payload("local@example.invalid"), auth("EmailLocal"))
                            .statusCode());
            assertNull(object(parsed(h.get(API, auth("EmailOther"))).get("result")).get("pending"));
            assertFalse(html(h.get(API, auth("EmailOther"))).contains("local@example.invalid"));
            assertEquals(
                    400,
                    h.json("DELETE", API, map("extra", true), auth("EmailLocal")).statusCode());
            assertEquals(200, h.json("DELETE", API, map(), auth("EmailLocal")).statusCode());
            assertEquals(200, h.json("DELETE", API, map(), auth("EmailLocal")).statusCode());
            assertEquals(400, h.get(firstPath, null).statusCode());
            assertEquals(
                    429,
                    h.json("POST", API, payload("local@example.invalid"), auth("EmailLocal"))
                            .statusCode());
            age(h, "EmailLocal");
            String replaced = request(h, "EmailLocal", "wrongtarget@example.invalid"),
                    replacedPath = path(h, replaced);
            age(h, "EmailLocal");
            String current = request(h, "EmailLocal", "Local@example.invalid"),
                    currentPath = path(h, current);
            assertEquals(400, h.get(replacedPath, null).statusCode());
            try (var db = h.store.open()) {
                assertTrue(
                        EmailOnboarding.valid(
                                db,
                                "email.enrollment",
                                map("email", "local@example.invalid", "path", currentPath)));
                assertFalse(
                        EmailOnboarding.valid(
                                db,
                                "email.enrollment",
                                map("email", "wrong@example.invalid", "path", currentPath)));
                assertFalse(
                        db.one(
                                        "SELECT token_hash FROM os_email_enrollment WHERE"
                                                + " user_id=?",
                                        RecordAccess.owner(db, "EmailLocal"))
                                .get("token_hash")
                                .toString()
                                .contains(currentPath.substring(14)));
            }
            assertEquals(
                    current,
                    objects(
                                    parsed(
                                                    h.get(
                                                            "/api/openservice/delivery?kind=enrollment&state=queued",
                                                            auth("admin")))
                                            .get("result"))
                            .getFirst()
                            .get("job_id"));
            var worker = new DeliveryWorker(h.store, h.environment);
            for (int i = 0; i < 3; i++) assertTrue(worker.runOne(System.currentTimeMillis() + 100));
            assertTrue(sink.receive().contains(currentPath));
            try (var db = h.store.open()) {
                assertEquals(
                        2L, db.count("SELECT COUNT(*) FROM os_outbox WHERE state='cancelled'"));
            }
            h.login("EmailLocal");
            var page = h.get(currentPath, null);
            assertEquals(200, page.statusCode());
            assertEquals(405, h.json("DELETE", currentPath, map(), null).statusCode());
            assertEquals(400, h.get(currentPath + "?extra=x", null).statusCode());
            assertEquals(403, h.form(currentPath, map(), null).statusCode());
            assertEquals(302, h.form(currentPath, map("csrf", csrf(page)), null).statusCode());
            assertEquals(401, h.get(API, null).statusCode());
            assertEquals(400, h.get(currentPath, null).statusCode());
            var status = object(parsed(h.get(API, auth("EmailLocal"))).get("result"));
            assertEquals("local@example.invalid", status.get("email"));
            assertEquals(true, status.get("verified"));
            assertNull(status.get("pending"));
            assertEquals(
                    409,
                    h.json("POST", API, payload("new@example.invalid"), auth("EmailLocal"))
                            .statusCode());
            assertEquals(
                    409,
                    h.json("POST", API, payload("LOCAL@example.invalid"), auth("EmailOther"))
                            .statusCode());
            assertTrue(
                    html(h.get("/account/email", auth("EmailLocal")))
                            .contains("Registered address: local@example.invalid"));
            String forgotCsrf = csrf(h.get("/forgot", null));
            assertEquals(
                    200,
                    h.form(
                                    "/forgot",
                                    map("email", "local@example.invalid", "csrf", forgotCsrf),
                                    null)
                            .statusCode());
            assertTrue(worker.runOne(System.currentTimeMillis() + 100));
            assertTrue(sink.receive().contains("/recover/"));
            assertEquals(
                    302,
                    h.form(
                                    "/login",
                                    map(
                                            "username",
                                            "EmailOther",
                                            "password",
                                            PASSWORD,
                                            "csrf",
                                            csrf(h.get("/login", null))),
                                    null)
                            .statusCode());
            assertEquals(200, h.get("/account", null).statusCode());
            assertEquals(
                    403,
                    h.form(
                                    "/account/email",
                                    map(
                                            "email",
                                            "other@example.invalid",
                                            "current_password",
                                            PASSWORD),
                                    null)
                            .statusCode());
            String token = csrf(h.get("/account/email", null));
            for (int i = 0; i < 5; i++)
                assertEquals(
                        400,
                        h.form(
                                        "/account/email",
                                        map(
                                                "email",
                                                "other@example.invalid",
                                                "current_password",
                                                "bad",
                                                "csrf",
                                                token),
                                        null)
                                .statusCode());
            assertEquals(
                    429,
                    h.form(
                                    "/account/email",
                                    map(
                                            "email",
                                            "other@example.invalid",
                                            "current_password",
                                            "bad",
                                            "csrf",
                                            token),
                                    null)
                            .statusCode());
            try (var db = h.store.open()) {
                assertEquals(
                        1L,
                        db.count(
                                "SELECT COUNT(*) FROM os_security_event WHERE"
                                        + " action='account.email_enrollment_cancelled'"));
                assertEquals(
                        1L,
                        db.count(
                                "SELECT COUNT(*) FROM os_security_event WHERE"
                                        + " action='account.email_enrollment_confirmed'"));
                assertFalse(
                        db.all("SELECT detail FROM os_security_event")
                                .toString()
                                .contains("@example.invalid"));
            }
        } finally {
            properties.remove("enrollment-mail");
        }
    }
}
