package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.net.http.HttpClient;
import java.util.*;

final class BrowserSessionsAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        String path = "/api/openservice/me/sessions";
        h.account("SessionOwner", "reader");
        h.account("SessionOther", "reader");
        assertEquals(401, h.get(path, null).statusCode());
        assertTrue(objects(parsed(h.get(path, auth("SessionOwner"))).get("result")).isEmpty());
        h.login("SessionOwner");
        var first = h.client;
        var one = parsed(h.get(path, null));
        assertEquals(false, one.get("truncated"));
        var row = objects(one.get("result")).getFirst();
        assertEquals(true, row.get("current"));
        assertEquals(
                Set.of("session_id", "created_at", "last_seen", "expires_at", "current"),
                row.keySet());
        assertEquals(32, row.get("session_id").toString().length());
        String cookie =
                ((CookieManager) h.client.cookieHandler().orElseThrow())
                        .getCookieStore().getCookies().stream()
                                .filter(c -> c.getName().equals("JSESSIONID"))
                                .findFirst()
                                .orElseThrow()
                                .getValue();
        try (var db = h.store.open()) {
            var stored =
                    db.one(
                            "SELECT * FROM os_browser_session WHERE session_id=?",
                            row.get("session_id"));
            assertEquals(Passwords.tokenHash(cookie), stored.get("session_hash"));
            assertFalse(stored.toString().contains(cookie));
        }
        assertTrue(objects(parsed(h.get(path, auth("SessionOther"))).get("result")).isEmpty());
        try (var second =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .build()) {
            h.client = second;
            h.login("SessionOwner");
            var rows = objects(parsed(h.get(path, null)).get("result"));
            assertEquals(2, rows.size());
            assertEquals(
                    1, rows.stream().filter(r -> Boolean.TRUE.equals(r.get("current"))).count());
            h.login("SessionOwner");
            assertEquals(
                    2,
                    objects(parsed(h.get(path, null)).get("result")).size(),
                    "Repeat login replaces the prior cookie entry");
            assertEquals(
                    302,
                    h.form("/logout", map("csrf", csrf(h.get("/account", null))), null)
                            .statusCode());
            h.client = first;
            assertEquals(1, objects(parsed(h.get(path, null)).get("result")).size());
        } finally {
            h.client = first;
        }
        assertEquals(400, h.get(path + "?user_id=other", null).statusCode());
        assertEquals(400, h.get("/account/sessions?all=true", null).statusCode());
        assertEquals(405, h.json("POST", path, map(), auth("admin")).statusCode());
        new Store(h.environment);
        assertEquals(
                1,
                objects(parsed(h.get(path, null)).get("result")).size(),
                "Inventory persists across store reinitialization");
        try (var db = h.store.open()) {
            db.update(
                    "UPDATE os_browser_session SET last_seen=?",
                    System.currentTimeMillis() - BrowserSessions.IDLE_MILLIS);
            db.connection.commit();
        }
        assertEquals(401, h.get(path, null).statusCode());
        h.login("SessionOwner");
        try (var db = h.store.open()) {
            assertEquals(
                    1,
                    db.count("SELECT COUNT(*) FROM os_browser_session"),
                    "Expired entries are pruned on sign-in");
            var user = db.one("SELECT * FROM os_user WHERE name='SessionOwner'");
            long now = System.currentTimeMillis();
            for (int i = 0; i < 101; i++) BrowserSessions.register(db, user, "inventory-" + i, now);
            db.connection.commit();
        }
        var bounded = parsed(h.get(path, null));
        assertEquals(true, bounded.get("truncated"));
        assertEquals(100, objects(bounded.get("result")).size());
        assertFalse(bounded.toString().contains("session_hash"));
    }
}
