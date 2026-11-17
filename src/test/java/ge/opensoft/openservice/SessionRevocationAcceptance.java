package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;

final class SessionRevocationAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("RevokeOwner", "reader");
        h.account("RevokeOther", "reader");
        h.login("RevokeOwner");
        var first = h.client;
        String firstId = current(h);
        try (var second =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .build()) {
            h.client = second;
            String csrf = h.login("RevokeOwner");
            String secondId = current(h), path = api(firstId);
            assertEquals(404, h.json("POST", path, map(), auth("RevokeOther")).statusCode());
            assertEquals(405, h.get(path, null).statusCode());
            assertEquals(
                    400,
                    h.json("POST", path + "?all=true", map(), auth("RevokeOwner")).statusCode());
            assertEquals(
                    400,
                    h.json("POST", path, map("user_id", "other"), auth("RevokeOwner"))
                            .statusCode());
            assertEquals(403, h.form(browser(firstId), map("csrf", "bad"), null).statusCode());
            assertEquals(200, h.get("/account", null).statusCode());
            HttpResponse<byte[]> revoked;
            try (var workers = Executors.newFixedThreadPool(2)) {
                var start = new CountDownLatch(1);
                var a = workers.submit(() -> { start.await(); return h.json("POST", path, map(), auth("RevokeOwner")); });
                var b = workers.submit(() -> { start.await(); return h.json("POST", path, map(), auth("RevokeOwner")); });
                start.countDown();
                var firstResult = a.get(15, TimeUnit.SECONDS);
                var secondResult = b.get(15, TimeUnit.SECONDS);
                assertEquals(Set.of(200, 404), Set.of(firstResult.statusCode(), secondResult.statusCode()));
                revoked = firstResult.statusCode() == 200 ? firstResult : secondResult;
            }
            assertEquals(map("revoked", true, "current", false), parsed(revoked).get("result"));
            assertEquals(404, h.json("POST", path, map(), auth("RevokeOwner")).statusCode());
            assertEquals(200, h.get("/account", null).statusCode());
            h.client = first;
            assertEquals(401, h.get("/api/openservice/me", null).statusCode());
            assertEquals(302, h.get("/account", null).statusCode());
            h.client = second;
            assertEquals(
                    400,
                    h.form(browser(secondId) + "?other=true", map("csrf", csrf), null)
                            .statusCode());
            var own = h.json("POST", api(secondId), map(), auth("RevokeOwner"));
            assertEquals(map("revoked", true, "current", true), parsed(own).get("result"));
            assertEquals(401, h.get("/api/openservice/me", null).statusCode());
            h.login("RevokeOwner");
            String thirdId = current(h);
            assertEquals(
                    302,
                    h.form(browser(thirdId), map("csrf", csrf(h.get("/account", null))), null)
                            .statusCode());
            assertEquals(401, h.get("/api/openservice/me", null).statusCode());
        } finally {
            h.client = first;
        }
        try (var db = h.store.open()) {
            var rows =
                    db.all(
                            "SELECT * FROM os_security_event WHERE"
                                + " action='account.session_revoked' ORDER BY sequence");
            assertEquals(3, rows.size());
            assertEquals(map("session_id", firstId), rows.getFirst().get("detail"));
            assertFalse(rows.toString().contains("session_hash"));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_browser_session"));
        }
        h.account("RevokeRequester", "requester");
        String loginCsrf = csrf(h.get("/login", null));
        assertEquals(302, h.form("/login", map("csrf", loginCsrf, "username", "RevokeRequester", "password", PASSWORD), null).statusCode());
        String own = current(h);
        assertEquals(302, h.form(browser(own), map("csrf", csrf(h.get("/account", null))), null).statusCode());
        assertEquals(401, h.get("/api/openservice/me", null).statusCode());
        if (h.store.postgres) queuedWrite(h);
    }

    static String current(WorkflowSupport h) throws Exception {
        return objects(parsed(h.get("/api/openservice/me/sessions", null)).get("result")).stream()
                .filter(r -> Boolean.TRUE.equals(r.get("current")))
                .findFirst()
                .orElseThrow()
                .get("session_id")
                .toString();
    }

    static String api(String id) {
        return "/api/openservice/me/sessions/" + id + "/revoke";
    }

    static String browser(String id) {
        return "/account/sessions/" + id + "/revoke";
    }

    static void queuedWrite(WorkflowSupport h) throws Exception {
        h.account("QueuedAgent", "agent");
        String csrf = h.login("QueuedAgent"), selected = current(h);
        var request =
                HttpRequest.newBuilder(URI.create(h.base() + "/api/now/table/incident"))
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-Token", csrf)
                        .POST(
                                HttpRequest.BodyPublishers.ofByteArray(
                                        JSON.writeValueAsBytes(
                                                map("short_description", "Must not be written"))))
                        .build();
        try (var executor = Executors.newSingleThreadExecutor();
                var db = h.store.open()) {
            db.all("SELECT pg_advisory_xact_lock(18439,4)");
            var write =
                    executor.submit(
                            () -> h.client.send(request, HttpResponse.BodyHandlers.ofByteArray()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (db.count(
                            "SELECT COUNT(*) FROM pg_locks WHERE locktype='advisory' AND"
                                + " classid=18439 AND objid=4 AND NOT granted")
                    == 0) {
                if (System.nanoTime() > deadline) {
                    db.connection.rollback();
                    fail("Write did not reach the advisory lock");
                }
                Thread.sleep(10);
            }
            BrowserSessions.revoke(
                    db,
                    db.one("SELECT * FROM os_user WHERE name='QueuedAgent'"),
                    selected,
                    "other-cookie");
            db.connection.commit();
            var response = write.get(10, TimeUnit.SECONDS);
            assertEquals(401, response.statusCode(), html(response));
            assertTrue(html(response).contains("Browser session ended"));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_record WHERE \"table\"='incident'"));
        }
    }
}
