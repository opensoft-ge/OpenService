package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class AdminSessionsAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("InspectedReader", "reader");
        h.account("InspectAgent", "agent");
        String target, actor;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "InspectedReader");
            actor = RecordAccess.owner(db, "admin");
        }
        String api = "/api/openservice/users/" + target + "/sessions",
                page = "/admin/accounts/" + target + "/sessions";
        assertEquals(401, h.get(api, null).statusCode());
        assertEquals(302, h.get(page, null).statusCode());
        h.login("InspectedReader");
        String own = SessionRevocationAcceptance.current(h);
        for (String name : List.of("InspectedReader", "InspectAgent")) {
            assertEquals(403, h.get(api, auth(name)).statusCode());
            assertEquals(403, h.get(page, auth(name)).statusCode());
        }
        var inventory = parsed(h.get(api, auth("admin")));
        assertEquals("InspectedReader", object(inventory.get("account")).get("name"));
        assertEquals(false, inventory.get("truncated"));
        var rows = objects(inventory.get("result"));
        assertEquals(1, rows.size());
        assertEquals(own, rows.getFirst().get("session_id"));
        // Current means the browser cookie, even when a separate administrator header is used.
        assertEquals(true, rows.getFirst().get("current"));
        assertEquals(
                Set.of("session_id", "current", "created_at", "last_seen", "expires_at"),
                rows.getFirst().keySet());
        // Use a different cookie jar: signing in again replaces the same browser session.
        h.client.close();
        h.client = SessionLimitAcceptance.client();
        h.login("admin");
        assertEquals(
                false, objects(parsed(h.get(api, null)).get("result")).getFirst().get("current"));
        assertEquals(200, h.get(page, null).statusCode());
        for (String path : List.of(api, page)) {
            assertEquals(400, h.get(path + "?user=other", null).statusCode());
            assertEquals(405, h.protectedJson("POST", path, map(), auth("admin")).statusCode());
        }
        assertEquals(
                404,
                h.get("/api/openservice/users/" + "f".repeat(32) + "/sessions", auth("admin"))
                        .statusCode());
        try (var db = h.store.open()) {
            var user = db.one("SELECT * FROM os_user WHERE user_id=?", target);
            long now = System.currentTimeMillis();
            for (int i = 0; i < 101; i++)
                BrowserSessions.register(db, user, "inspection-" + i, now);
            BrowserSessions.register(db, user, "expired", now - SessionLifetime.MAX_AGE_MILLIS);
            BrowserSessions.register(db, user, "idle", now - BrowserSessions.IDLE_MILLIS);
            BrowserSessions.register(db, user, "future", now + 60000);
            assertEquals(
                    403,
                    assertThrows(
                                    Failure.class,
                                    () -> AdminSessions.list(db, target, target, "", now))
                            .status);
            db.connection.commit();
        }
        inventory = parsed(h.get(api, auth("admin")));
        assertEquals(true, inventory.get("truncated"));
        assertEquals(100, objects(inventory.get("result")).size());
        assertTrue(
                objects(inventory.get("result")).stream()
                        .noneMatch(r -> r.containsKey("session_hash")));
        assertEquals(
                200,
                h.json(
                                "PATCH",
                                "/api/openservice/users/" + target + "/status",
                                map("active", false),
                                auth("admin"))
                        .statusCode());
        assertEquals(List.of(), parsed(h.get(api, auth("admin"))).get("result"));
        assertTrue(
                new String(
                                h.get(page, auth("admin")).body(),
                                java.nio.charset.StandardCharsets.UTF_8)
                        .contains("This account is inactive"));
        assertEquals(
                200,
                h.json(
                                "PATCH",
                                "/api/openservice/users/" + target + "/status",
                                map("active", true),
                                auth("admin"))
                        .statusCode());
        assertEquals(List.of(), parsed(h.get(api, auth("admin"))).get("result"));
        assertEquals(
                200,
                h.json(
                                "POST",
                                "/api/openservice/users/" + target + "/access-mode",
                                map("api_only", true),
                                auth("admin"))
                        .statusCode());
        assertEquals(List.of(), parsed(h.get(api, auth("admin"))).get("result"));
        new Store(h.environment);
        assertEquals(List.of(), parsed(h.get(api, auth("admin"))).get("result"));
    }
}
