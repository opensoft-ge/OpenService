package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

final class ReleaseAcceptance {
    static String link(String message, String kind) {
        var match = Pattern.compile("/" + kind + "/[A-Za-z0-9_-]{32}").matcher(message);
        assertTrue(match.find(), message);
        return match.group();
    }

    static Map<String, Object> settings(MailSink sink) {
        return map(
                "DELIVERY_ENABLED",
                "true",
                "PUBLIC_BASE_URL",
                "https://example.invalid",
                "MAIL_HOST",
                "localhost",
                "MAIL_PORT",
                Integer.toString(sink.server.getLocalPort()),
                "MAIL_FROM",
                "accounts@example.invalid",
                "MAIL_STARTTLS",
                "false",
                "MAIL_ALLOW_INSECURE_LOCAL",
                "true");
    }

    static void email(WorkflowSupport h) throws Exception {
        h.account("Reader", "reader");
        String endpoint = "/api/openservice/email-invitations";
        assertEquals(503, h.json("POST", endpoint, map(), auth("admin")).statusCode());
        var properties = ((ConfigurableEnvironment) h.environment).getPropertySources();
        try (var sink = new MailSink()) {
            properties.addFirst(new MapPropertySource("release-mail", settings(sink)));
            var payload =
                    map(
                            "name",
                            "EmailUser",
                            "role",
                            "reader",
                            "email",
                            "Recipient@EXAMPLE.invalid");
            assertEquals(403, h.json("POST", endpoint, payload, auth("Reader")).statusCode());
            assertEquals(405, h.get(endpoint, auth("admin")).statusCode());
            assertEquals(400, h.json("POST", endpoint, map(), auth("admin")).statusCode());
            assertEquals(
                    400,
                    h.json(
                                    "POST",
                                    endpoint,
                                    map(
                                            "name",
                                            "Bad",
                                            "role",
                                            "reader",
                                            "email",
                                            "bad\r\n@example.invalid"),
                                    auth("admin"))
                            .statusCode());
            var response = h.json("POST", endpoint, payload, auth("admin"));
            assertEquals(201, response.statusCode(), html(response));
            var result = object(parsed(response).get("result"));
            assertFalse(result.containsKey("accept_path"));
            String job = result.get("delivery_id").toString();
            assertEquals(
                    job,
                    objects(
                                    parsed(
                                                    h.get(
                                                            "/api/openservice/delivery?kind=invitation",
                                                            auth("admin")))
                                            .get("result"))
                            .getFirst()
                            .get("job_id"));
            assertFalse(
                    html(h.get("/api/openservice/delivery", auth("admin"))).contains("Recipient"));
            assertEquals(403, h.get("/admin/delivery", auth("Reader")).statusCode());
            assertEquals(200, h.get("/admin/delivery", auth("admin")).statusCode());
            sink.reject.set(1);
            var worker = new DeliveryWorker(h.store, h.environment);
            assertTrue(worker.runOne(System.currentTimeMillis() + 100));
            try (var db = h.store.open()) {
                var row = db.one("SELECT * FROM os_outbox WHERE job_id=?", job);
                assertEquals("queued", row.get("state"));
                assertEquals("DELIVERY_FAILED", row.get("error"));
                assertFalse(row.get("envelope").toString().contains("recipient"));
                db.update("UPDATE os_outbox SET available_at=0 WHERE job_id=?", job);
                db.connection.commit();
            }
            // A new worker instance resumes persisted work through real SMTP.
            assertTrue(
                    new DeliveryWorker(h.store, h.environment).runOne(System.currentTimeMillis()));
            String invitation = link(sink.receive(), "join");
            String token = csrf(h.get(invitation, null));
            assertEquals(
                    302,
                    h.form(invitation, map("csrf", token, "password", PASSWORD), null)
                            .statusCode());
            assertEquals(400, h.get(invitation, null).statusCode());
            String user;
            try (var db = h.store.open()) {
                var account = db.one("SELECT * FROM os_user WHERE name='EmailUser'");
                assertEquals("recipient@example.invalid", account.get("email"));
                assertEquals(1, ((Number) account.get("email_verified")).intValue());
                user = account.get("user_id").toString();
            }
            assertEquals(409, h.json("POST", endpoint, payload, auth("admin")).statusCode());
            String recoveryCsrf = csrf(h.get("/forgot", null));
            assertEquals(
                    403,
                    h.form(
                                    "/forgot",
                                    map("email", "recipient@example.invalid", "csrf", "bad"),
                                    null)
                            .statusCode());
            assertEquals(
                    400,
                    h.form("/forgot", map("email", "a".repeat(255), "csrf", recoveryCsrf), null)
                            .statusCode());
            var known =
                    h.form(
                            "/forgot",
                            map("email", "recipient@example.invalid", "csrf", recoveryCsrf),
                            null);
            var unknown =
                    h.form(
                            "/forgot",
                            map("email", "missing@example.invalid", "csrf", recoveryCsrf),
                            null);
            assertEquals(html(known), html(unknown));
            assertEquals(
                    200,
                    h.form(
                                    "/forgot",
                                    map("email", "recipient@example.invalid", "csrf", recoveryCsrf),
                                    null)
                            .statusCode());
            assertTrue(worker.runOne(System.currentTimeMillis() + 100));
            String recovery = link(sink.receive(), "recover");
            var recoveryPage = h.get(recovery, null);
            String resetCsrf = csrf(recoveryPage);
            assertEquals(
                    400,
                    h.form(recovery, map("csrf", resetCsrf, "password", PASSWORD), null)
                            .statusCode());
            assertEquals(
                    302,
                    h.form(recovery, map("csrf", resetCsrf, "password", PASSWORD + "new"), null)
                            .statusCode());
            assertEquals(400, h.get(recovery, null).statusCode());
            assertEquals(401, h.get("/api/openservice/me", auth("EmailUser")).statusCode());
            String loginCsrf = csrf(h.get("/login", null));
            assertEquals(
                    302,
                    h.form(
                                    "/login",
                                    map(
                                            "csrf",
                                            loginCsrf,
                                            "username",
                                            "EmailUser",
                                            "password",
                                            PASSWORD + "new"),
                                    null)
                            .statusCode());
            assertEquals(200, h.get("/account", null).statusCode());
            assertEquals(
                    200,
                    h.json(
                                    "PATCH",
                                    "/api/openservice/users/" + user + "/status",
                                    map("active", false),
                                    auth("admin"))
                            .statusCode());
            assertEquals(401, h.get("/api/openservice/me", null).statusCode());
            assertEquals(
                    409,
                    h.json(
                                    "POST",
                                    "/api/openservice/delivery/" + job + "/retry",
                                    map(),
                                    auth("admin"))
                            .statusCode());
            try (var db = h.store.open()) {
                db.update(
                        "UPDATE os_outbox SET state='failed',envelope='encrypted-test-envelope'"
                                + " WHERE job_id=?",
                        job);
                db.connection.commit();
            }
            assertEquals(
                    200,
                    h.json(
                                    "POST",
                                    "/api/openservice/delivery/" + job + "/retry",
                                    map(),
                                    auth("admin"))
                            .statusCode());
            try (var db = h.store.open()) {
                db.update("UPDATE os_outbox SET state='failed' WHERE job_id=?", job);
                db.connection.commit();
            }
            assertEquals(
                    302,
                    h.form("/admin/delivery/" + job + "/retry", map(), auth("admin")).statusCode());
            // Browser form queues email without disclosing the bearer link.
            String adminCsrf = h.login("admin");
            var queued =
                    h.form(
                            "/admin/invitations",
                            map(
                                    "csrf",
                                    adminCsrf,
                                    "name",
                                    "FormEmail",
                                    "role",
                                    "reader",
                                    "email",
                                    "form@example.invalid"),
                            null);
            assertEquals(201, queued.statusCode(), html(queued));
            assertTrue(html(queued).contains("queued for email"));
            assertFalse(html(queued).contains("/join/"));
        } finally {
            properties.remove("release-mail");
        }
    }

    static void access(WorkflowSupport h) throws Exception {
        h.account("Reader", "reader");
        h.account("Agent", "agent");
        h.account("RequesterA", "requester");
        h.account("RequesterB", "requester");
        var a = new ConfigurationAcceptance(h);
        try {
            var def = ConfigurationAcceptance.definition();
            object(def.get("field_rules"))
                    .put("amount", map("type", "decimal", "read_roles", List.of("admin")));
            assertEquals(
                    200,
                    a.install(ConfigurationAcceptance.bundle(a.table, def), false).statusCode());
            String api = "/api/now/table/" + a.table;
            var created =
                    h.json(
                            "POST",
                            api,
                            map(
                                    "short_description",
                                    "Visible",
                                    "amount",
                                    "9876543.21",
                                    "quantity",
                                    "17"),
                            auth("admin"));
            assertEquals(201, created.statusCode(), html(created));
            String id = object(parsed(created).get("result")).get("sys_id").toString();
            for (String path :
                    List.of(
                            api,
                            api + "/" + id,
                            api + "/" + id + "?sysparm_fields=amount,short_description",
                            "/record/" + a.table + "/" + id,
                            "/workspace/" + a.table,
                            "/export/" + a.table,
                            "/audit",
                            "/dictionary",
                            "/analytics/" + a.table)) {
                var response = h.get(path, auth("Reader"));
                assertEquals(200, response.statusCode(), path + html(response));
                assertFalse(html(response).contains("9876543.21"), path);
            }
            assertTrue(html(h.get(api + "/" + id, auth("admin"))).contains("9876543.21"));
            assertEquals(
                    403, h.get("/export/" + a.table + "?source=true", auth("Reader")).statusCode());
            for (String query : List.of("amount=9876543.21", "ORDERBYamount"))
                assertEquals(
                        400,
                        h.get(
                                        api
                                                + "?sysparm_query="
                                                + URLEncoder.encode(query, StandardCharsets.UTF_8),
                                        auth("Reader"))
                                .statusCode());
            assertEquals(
                    400,
                    h.get(
                                    "/api/now/stats/"
                                            + a.table
                                            + "?sysparm_count=true&sysparm_group_by=amount",
                                    auth("Reader"))
                            .statusCode());
            assertEquals(
                    400,
                    h.get("/api/openservice/analytics/" + a.table + "?field=amount", auth("Reader"))
                            .statusCode());
            assertFalse(
                    html(h.json(
                                    "PATCH",
                                    api + "/" + id,
                                    map("short_description", "Changed"),
                                    auth("Agent")))
                            .contains("9876543.21"));
            assertFalse(
                    html(h.get(
                                    "/api/openservice/permissions/" + a.table + "/" + id,
                                    auth("Reader")))
                            .contains("amount"));
            // Blind submissions, including a correct guess, cannot test a hidden stored value.
            for (String guess : List.of("9876543.21", "1"))
                assertEquals(
                        403,
                        h.json("PATCH", api + "/" + id, map("amount", guess), auth("Agent"))
                                .statusCode());
            assertEquals(
                    403,
                    h.json(
                                    "POST",
                                    api,
                                    map("short_description", "Blind write", "amount", "1"),
                                    auth("Agent"))
                            .statusCode());
            def.put("version", 1);
            object(def.get("field_rules"))
                    .put(
                            "short_description",
                            map("type", "string", "read_roles", List.of("admin")));
            object(def.get("field_rules"))
                    .put("quantity", map("type", "integer", "read_roles", List.of("reader")));
            assertEquals(
                    200,
                    a.install(ConfigurationAcceptance.bundle(a.table, def), false).statusCode());
            var visible = object(parsed(h.get(api + "/" + id, auth("Reader"))).get("result"));
            assertEquals("17", visible.get("quantity"));
            assertFalse(visible.containsKey("short_description"));
            assertEquals(
                    403,
                    h.get("/workspace/" + a.table + "?search=Changed", auth("Reader"))
                            .statusCode());
            for (Object invalid :
                    List.of(List.of("unknown"), List.of("reader", "reader"), "reader")) {
                var bad = ConfigurationAcceptance.definition();
                object(bad.get("field_rules"))
                        .put("amount", map("type", "decimal", "read_roles", invalid));
                assertEquals(
                        400,
                        a.install(ConfigurationAcceptance.bundle(a.table + "_invalid", bad), false)
                                .statusCode());
            }
            try (var db = h.store.open()) {
                db.viewerId = RecordAccess.owner(db, "Reader");
                db.viewerRole = "reader";
                assertEquals(
                        map(),
                        FieldAccess.event(
                                        db,
                                        map(
                                                "record_id",
                                                "f".repeat(32),
                                                "action",
                                                "delete",
                                                "detail",
                                                map("before", map("amount", "secret"))))
                                .get("detail"));
            }
            String reqApi = "/api/openservice/requests";
            var first =
                    h.json(
                            "POST",
                            reqApi,
                            map(
                                    "short_description",
                                    "Owned request",
                                    "description",
                                    "Visible detail"),
                            auth("RequesterA"));
            assertEquals(201, first.statusCode(), html(first));
            String request = object(parsed(first).get("result")).get("sys_id").toString();
            assertEquals(
                    400,
                    h.json(
                                    "POST",
                                    reqApi,
                                    map("short_description", "Bad", "state", "3"),
                                    auth("RequesterA"))
                            .statusCode());
            var second =
                    h.json(
                            "POST",
                            reqApi,
                            map("short_description", "Other request"),
                            auth("RequesterB"));
            String other = object(parsed(second).get("result")).get("sys_id").toString();
            assertEquals(403, h.json("POST", reqApi, map(), auth("Reader")).statusCode());
            assertEquals(200, h.get("/requests", auth("RequesterA")).statusCode());
            assertEquals(302, h.get("/", auth("RequesterA")).statusCode());
            String ownPath = "/api/now/table/sc_request/" + request;
            assertEquals(200, h.get(ownPath, auth("RequesterA")).statusCode());
            assertEquals(404, h.get(ownPath, auth("RequesterB")).statusCode());
            assertFalse(html(h.get(reqApi, auth("RequesterA"))).contains(other));
            assertFalse(html(h.get("/export/sc_request", auth("RequesterA"))).contains(other));
            assertEquals(
                    "1",
                    object(
                                                    parsed(
                                                                    h.get(
                                                                            "/api/now/stats/sc_request?sysparm_count=true",
                                                                            auth("RequesterA")))
                                                            .get("result"))
                                            .get("stats")
                                    instanceof Map<?, ?> m
                            ? m.get("count")
                            : "missing");
            assertEquals(
                    403,
                    h.json("PATCH", ownPath, map("short_description", "Denied"), auth("RequesterA"))
                            .statusCode());
            assertEquals(
                    403,
                    h.json(
                                    "POST",
                                    "/api/now/table/sc_request",
                                    map("short_description", "Bypass"),
                                    auth("RequesterA"))
                            .statusCode());
            assertEquals(
                    201,
                    h.json(
                                    "POST",
                                    "/api/openservice/comments/sc_request/" + request,
                                    map("body", "Shared reply"),
                                    auth("RequesterA"))
                            .statusCode());
            assertEquals(
                    404,
                    h.json(
                                    "POST",
                                    "/api/openservice/comments/sc_request/" + other,
                                    map("body", "Denied"),
                                    auth("RequesterA"))
                            .statusCode());
            try (var db = h.store.open()) {
                Service.audit(
                        db,
                        Service.record(db, "sc_request", request),
                        "admin",
                        "note",
                        map("body", "internal-sensitive-note"));
                db.update(
                        "INSERT INTO os_attachment(sys_id,record_id,name,content) VALUES(?,?,?,?)",
                        "e".repeat(32),
                        request,
                        "private.txt",
                        new byte[] {1, 2, 3});
                db.connection.commit();
            }
            assertFalse(
                    html(h.get("/record/sc_request/" + request, auth("RequesterA")))
                            .contains("internal-sensitive-note"));
            assertFalse(
                    html(h.get("/audit", auth("RequesterA"))).contains("internal-sensitive-note"));
            for (String path :
                    List.of(
                            "/attachment/" + "e".repeat(32),
                            "/api/now/attachment/" + "e".repeat(32) + "/file")) {
                assertEquals(200, h.get(path, auth("RequesterA")).statusCode());
                assertEquals(404, h.get(path, auth("RequesterB")).statusCode());
            }
        } finally {
            a.cleanup();
        }
    }
}
