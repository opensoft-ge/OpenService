package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.util.concurrent.*;

final class SessionLimitAcceptance {
    static HttpClient client() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
    }

    static void run(WorkflowSupport h) throws Exception {
        h.account("LimitedOwner", "reader");
        h.account("UnrelatedOwner", "reader");
        h.login("LimitedOwner");
        String oldest = SessionRevocationAcceptance.current(h);
        var original = h.client;
        try (var db = h.store.open()) {
            var user = db.one("SELECT * FROM os_user WHERE name='LimitedOwner'");
            long issued = System.currentTimeMillis() - 10000;
            db.update(
                    "UPDATE os_browser_session SET created_at=? WHERE session_id=?",
                    issued - 1000,
                    oldest);
            for (int i = 0; i < 10; i++)
                BrowserSessions.register(db, user, "old-browser-" + i, issued + i);
            BrowserSessions.register(
                    db,
                    db.one("SELECT * FROM os_user WHERE name='UnrelatedOwner'"),
                    "unrelated",
                    issued);
            db.connection.commit();
        }
        try (var first = client();
                var second = client();
                var executor = Executors.newFixedThreadPool(2)) {
            String firstToken =
                    csrf(
                            first.send(
                                    HttpRequest.newBuilder(URI.create(h.base() + "/login"))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofByteArray()));
            String secondToken =
                    csrf(
                            second.send(
                                    HttpRequest.newBuilder(URI.create(h.base() + "/login"))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofByteArray()));
            var start = new CountDownLatch(1);
            var a =
                    executor.submit(
                            () -> {
                                start.await();
                                return signIn(h, first, firstToken);
                            });
            var b =
                    executor.submit(
                            () -> {
                                start.await();
                                return signIn(h, second, secondToken);
                            });
            start.countDown();
            assertEquals(302, a.get(15, TimeUnit.SECONDS).statusCode());
            assertEquals(302, b.get(15, TimeUnit.SECONDS).statusCode());
            assertEquals(401, h.get("/api/openservice/me", null).statusCode());
            h.client = first;
            assertEquals(
                    10,
                    objects(parsed(h.get("/api/openservice/me/sessions", null)).get("result"))
                            .size());
            SessionRevocationAcceptance.current(h);
            h.client = second;
            assertEquals(200, h.get("/account", null).statusCode());
            SessionRevocationAcceptance.current(h);
            assertEquals(200, h.get("/api/openservice/me", auth("LimitedOwner")).statusCode());
        } finally {
            h.client = original;
        }
        try (var db = h.store.open()) {
            assertEquals(
                    0,
                    db.count("SELECT COUNT(*) FROM os_browser_session WHERE session_id=?", oldest));
            assertEquals(
                    0,
                    db.count(
                            "SELECT COUNT(*) FROM os_browser_session WHERE session_hash=?",
                            Passwords.tokenHash("old-browser-0")));
            assertEquals(
                    0,
                    db.count(
                            "SELECT COUNT(*) FROM os_browser_session WHERE session_hash=?",
                            Passwords.tokenHash("old-browser-1")));
            assertEquals(
                    1,
                    db.count(
                            "SELECT COUNT(*) FROM os_browser_session WHERE session_hash=?",
                            Passwords.tokenHash("unrelated")));
            var events =
                    db.all(
                            "SELECT detail FROM os_security_event WHERE"
                                + " action='account.sessions_limited'");
            assertEquals(2, events.size());
            assertEquals(
                    3,
                    events.stream()
                            .mapToInt(
                                    e ->
                                            ((Number) object(e.get("detail")).get("removed"))
                                                    .intValue())
                            .sum());
            assertTrue(
                    events.stream()
                            .allMatch(e -> object(e.get("detail")).get("maximum").equals(10)));
        }
    }

    static HttpResponse<byte[]> signIn(WorkflowSupport h, HttpClient client, String csrf)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(h.base() + "/login"))
                        .timeout(java.time.Duration.ofSeconds(10))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "username=LimitedOwner&password="
                                                + PASSWORD
                                                + "&csrf="
                                                + csrf))
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }
}
