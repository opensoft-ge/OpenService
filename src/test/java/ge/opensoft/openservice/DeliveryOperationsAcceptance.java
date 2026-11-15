package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class DeliveryOperationsAcceptance {
    static String seed(Store.Db db, String state, long created, long available, long expires)
            throws Exception {
        String job = id();
        db.update(
                "INSERT INTO os_outbox VALUES(?,?,?,? ,?,0,?,?,?,'','')",
                job,
                "invitation",
                id(),
                "private-envelope",
                state,
                available,
                expires,
                created);
        return job;
    }

    static void diagnostics(WorkflowSupport h) throws Exception {
        String api = "/api/openservice/delivery";
        h.account("QueueReader", "reader");
        assertEquals(403, h.get(api, auth("QueueReader")).statusCode());
        assertEquals(401, h.get(api, null).statusCode());
        var empty = object(parsed(h.get(api, auth("admin"))).get("diagnostics"));
        assertEquals(0, ((Number) empty.get("total")).intValue());
        assertEquals(0, ((Number) empty.get("oldest_pending_age_seconds")).intValue());
        long now = System.currentTimeMillis();
        try (var db = h.store.open()) {
            seed(db, "failed", now - 1_000_000, 0, now + 600_000);
            for (int i = 0; i < 105; i++) seed(db, "delivered", now - 1000, 0, now + 600_000);
            seed(db, "queued", now - 120_000, 0, now + 600_000);
            seed(db, "queued", now - 10_000, now + 600_000, now + 600_000);
            seed(db, "queued", now - 200_000, 0, now);
            seed(db, "processing", now - 30_000, now, now + 600_000);
            seed(db, "cancelled", now - 40_000, 0, now + 600_000);
            var metrics = Outbox.diagnostics(db, now);
            for (var expected :
                    map(
                                    "total",
                                    111,
                                    "failed",
                                    1,
                                    "queued",
                                    3,
                                    "processing",
                                    1,
                                    "delivered",
                                    105,
                                    "cancelled",
                                    1,
                                    "ready",
                                    1,
                                    "expired_leases",
                                    1,
                                    "expired_links",
                                    1,
                                    "oldest_pending_age_seconds",
                                    120)
                            .entrySet())
                assertEquals(
                        expected.getValue(),
                        ((Number) metrics.get(expected.getKey())).intValue(),
                        expected.getKey());
            db.connection.commit();
        }
        var response = h.get(api, auth("admin"));
        assertEquals(200, response.statusCode());
        assertEquals(100, objects(parsed(response).get("result")).size());
        assertEquals(
                111,
                ((Number) object(parsed(response).get("diagnostics")).get("total")).intValue());
        assertFalse(html(response).contains("private-envelope"));
        assertFalse(html(response).contains("reference_id"));
        assertTrue(
                html(h.get("/admin/delivery", auth("admin")))
                        .contains("Complete queue diagnostics"));
        try (var db = h.store.open()) {
            db.update("DELETE FROM os_outbox");
            seed(db, "queued", now + 1_000, now + 1_000, now + 2_000);
            assertEquals(0L, Outbox.diagnostics(db, now).get("oldest_pending_age_seconds"));
        }
    }
}
