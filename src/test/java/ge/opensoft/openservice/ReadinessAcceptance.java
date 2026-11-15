package ge.opensoft.openservice;

import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class ReadinessAcceptance {
    static void check(WorkflowSupport h, int status, String value) throws Exception {
        var response = h.get("/readyz", null);
        assertEquals(status, response.statusCode(), html(response));
        assertEquals(Map.of("status", value), parsed(response));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
    }

    static void run(WorkflowSupport h) throws Exception {
        check(h, 200, "ready");
        assertEquals(200, h.get("/readyz", "Basic invalid").statusCode());
        assertEquals(400, h.get("/readyz?verbose=true", null).statusCode());
        assertEquals(200, h.get("/readyz?", null).statusCode());
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE"))
            assertEquals(405, h.json(method, "/readyz", Map.of(), null).statusCode());
        try (var db = h.store.open()) {
            db.update("UPDATE os_migration SET version=999 WHERE version=1");
            db.connection.commit();
        }
        try {
            check(h, 503, "not_ready");
        } finally {
            try (var db = h.store.open()) {
                db.update("UPDATE os_migration SET version=1 WHERE version=999");
                db.connection.commit();
            }
        }
        Map<String, Object> last;
        try (var db = h.store.open()) {
            last = db.one("SELECT * FROM os_migration WHERE version=?", Migrations.VERSION);
            db.update("DELETE FROM os_migration WHERE version=?", Migrations.VERSION);
            db.connection.commit();
        }
        try {
            check(h, 503, "not_ready");
        } finally {
            try (var db = h.store.open()) {
                db.update(
                        "INSERT INTO os_migration(version,name) VALUES(?,?)",
                        last.get("version"),
                        last.get("name"));
                db.connection.commit();
            }
        }
        check(h, 200, "ready");
    }
}
