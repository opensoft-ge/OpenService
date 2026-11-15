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

    static void directory(WorkflowSupport h) throws Exception {
        String api = "/api/openservice/delivery";
        for (String query :
                List.of(
                        "unknown=1",
                        "page=0",
                        "page=-1",
                        "page=1000000",
                        "page=01",
                        "page=abc",
                        "page=2",
                        "state=wrong",
                        "kind=wrong",
                        "state=queued&state=failed"))
            assertEquals(400, h.get(api + "?" + query, auth("admin")).statusCode(), query);
        h.account("QueueAgent", "agent");
        assertEquals(403, h.get(api + "?state=failed", auth("QueueAgent")).statusCode());
        assertEquals(405, h.json("POST", api, map(), auth("admin")).statusCode());
        List<String> ids = new ArrayList<>();
        String failure;
        try (var db = h.store.open()) {
            for (int i = 0; i < 101; i++) ids.add(seed(db, "delivered", 100, 0, Long.MAX_VALUE));
            failure = seed(db, "failed", 1, 0, Long.MAX_VALUE);
            db.update("UPDATE os_outbox SET kind='recovery' WHERE job_id=?", failure);
            db.connection.commit();
        }
        Collections.sort(ids);
        var first = parsed(h.get(api, auth("admin")));
        var second = parsed(h.get(api + "?page=2", auth("admin")));
        assertEquals(102, ((Number) first.get("total")).intValue());
        assertEquals(2, ((Number) first.get("pages")).intValue());
        assertEquals(
                ids.subList(0, 100),
                objects(first.get("result")).stream().map(r -> r.get("job_id")).toList());
        assertEquals(
                List.of(ids.get(100), failure),
                objects(second.get("result")).stream().map(r -> r.get("job_id")).toList());
        var filtered = parsed(h.get(api + "?state=failed&kind=recovery", auth("admin")));
        assertEquals(failure, objects(filtered.get("result")).getFirst().get("job_id"));
        assertEquals(102, ((Number) object(filtered.get("diagnostics")).get("total")).intValue());
        assertEquals(1, ((Number) filtered.get("total")).intValue());
        assertTrue(
                objects(
                                parsed(h.get(api + "?kind=invitation&state=queued", auth("admin")))
                                        .get("result"))
                        .isEmpty());
        assertEquals(400, h.get(api + "?state=failed&page=2", auth("admin")).statusCode());
        assertFalse(html(h.get(api + "?state=failed", auth("admin"))).contains("private-envelope"));
        assertTrue(html(h.get("/admin/delivery?page=2", auth("admin"))).contains("Page 2 of 2"));
    }
}
