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
                "email.invitation",
                id(),
                "private-envelope",
                state,
                available,
                expires,
                created);
        return job;
    }

    static void retention(WorkflowSupport h) throws Exception {
        String api = "/api/openservice/delivery/retention";
        h.account("RetentionAgent", "agent");
        assertEquals(401, h.get(api, null).statusCode());
        assertEquals(403, h.get(api, auth("RetentionAgent")).statusCode());
        assertEquals(
                403,
                h.json("POST", api, map("days", 30, "confirm", "PURGE"), auth("RetentionAgent"))
                        .statusCode());
        assertEquals(405, h.json("DELETE", api, map(), auth("admin")).statusCode());
        for (String query :
                List.of(
                        "days=29",
                        "days=3651",
                        "days=1",
                        "days=030",
                        "days=x",
                        "days=30&days=90",
                        "unknown=x"))
            assertEquals(400, h.get(api + "?" + query, auth("admin")).statusCode(), query);
        for (var body :
                List.of(
                        map(),
                        map("days", 30),
                        map("days", 30, "confirm", "no"),
                        map("days", null, "confirm", "PURGE"),
                        map("days", 30.5, "confirm", "PURGE"),
                        map("days", 30, "confirm", "PURGE", "unknown", true)))
            assertEquals(400, h.json("POST", api, body, auth("admin")).statusCode());
        assertEquals(
                400,
                h.json("POST", api + "?days=30", map("days", 30, "confirm", "PURGE"), auth("admin"))
                        .statusCode());
        long now = System.currentTimeMillis(), cutoff = now - 30L * 86_400_000;
        String boundary;
        List<String> keep = new ArrayList<>();
        try (var db = h.store.open()) {
            for (int i = 0; i < 501; i++) seed(db, i % 2 == 0 ? "delivered" : "cancelled", 1, 0, 1);
            for (String state : List.of("queued", "processing", "failed"))
                keep.add(seed(db, state, 1, 0, 1));
            keep.add(seed(db, "delivered", 1, 0, Long.MAX_VALUE));
            boundary = seed(db, "cancelled", 1, 0, cutoff);
            assertEquals(
                    501L,
                    DeliveryRetention.run(
                                    db,
                                    RecordAccess.owner(db, "admin"),
                                    map("days", 30),
                                    false,
                                    now)
                            .get("eligible"));
            assertEquals(
                    0L,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                + " action='delivery.metadata_purged'"));
            assertThrows(
                    Failure.class,
                    () ->
                            DeliveryRetention.run(
                                    db,
                                    RecordAccess.owner(db, "RetentionAgent"),
                                    map("days", 30),
                                    false,
                                    now));
            db.update(
                    "UPDATE os_outbox SET expires_at=? WHERE job_id=?",
                    cutoff + 86_400_000L,
                    boundary);
            keep.add(boundary);
            db.connection.commit();
        }
        var preview = object(parsed(h.get(api, auth("admin"))).get("result"));
        assertEquals(90, preview.get("days"));
        assertEquals(501, preview.get("eligible"));
        assertEquals(false, preview.get("applied"));
        assertEquals(200, h.get(api + "?days=3650", auth("admin")).statusCode());
        var applied =
                object(
                        parsed(
                                        h.json(
                                                "POST",
                                                api,
                                                map("days", 30, "confirm", "PURGE"),
                                                auth("admin")))
                                .get("result"));
        assertEquals(500, applied.get("deleted"));
        assertEquals(1, applied.get("eligible"));
        applied =
                object(
                        parsed(
                                        h.json(
                                                "POST",
                                                api,
                                                map("days", "30", "confirm", "PURGE"),
                                                auth("admin")))
                                .get("result"));
        assertEquals(1, applied.get("deleted"));
        assertEquals(0, applied.get("eligible"));
        applied =
                object(
                        parsed(
                                        h.json(
                                                "POST",
                                                api,
                                                map("days", 30, "confirm", "PURGE"),
                                                auth("admin")))
                                .get("result"));
        assertEquals(0, applied.get("deleted"));
        try (var db = h.store.open()) {
            assertEquals(5L, db.count("SELECT COUNT(*) FROM os_outbox"));
            for (String job : keep)
                assertNotNull(db.one("SELECT job_id FROM os_outbox WHERE job_id=?", job));
            var events =
                    db.all(
                            "SELECT detail FROM os_security_event WHERE"
                                + " action='delivery.metadata_purged' ORDER BY sequence");
            assertEquals(3, events.size());
            assertEquals(500, object(events.getFirst().get("detail")).get("deleted"));
            assertFalse(events.toString().contains("private-envelope"));
        }
        h.login("admin");
        assertEquals(
                403,
                h.form("/admin/delivery/retention", map("days", 30, "confirm", "PURGE"), null)
                        .statusCode());
    }

    static void cancellation(WorkflowSupport h) throws Exception {
        String api = "/api/openservice/delivery/";
        String queued, failed, processing, delivered, cancelled;
        h.account("CancelReader", "reader");
        try (var db = h.store.open()) {
            queued = seed(db, "queued", 1, 0, Long.MAX_VALUE);
            failed = seed(db, "failed", 1, 0, 1);
            processing = seed(db, "processing", 1, Long.MAX_VALUE, Long.MAX_VALUE);
            delivered = seed(db, "delivered", 1, 0, Long.MAX_VALUE);
            cancelled = seed(db, "cancelled", 1, 0, Long.MAX_VALUE);
            db.connection.commit();
        }
        String path = api + queued + "/cancel";
        assertEquals(401, h.json("POST", path, map(), null).statusCode());
        assertEquals(403, h.json("POST", path, map(), auth("CancelReader")).statusCode());
        assertEquals(405, h.get(path, auth("admin")).statusCode());
        assertEquals(
                400, h.json("POST", path + "?state=queued", map(), auth("admin")).statusCode());
        assertEquals("cancelled", parsed(h.json("POST", path, map(), auth("admin"))).get("result"));
        assertEquals(
                200, h.json("POST", api + failed + "/cancel", map(), auth("admin")).statusCode());
        for (String job : List.of(queued, processing, delivered, cancelled, id()))
            assertEquals(
                    409, h.json("POST", api + job + "/cancel", map(), auth("admin")).statusCode());
        try (var db = h.store.open()) {
            for (String job : List.of(queued, failed)) {
                var row = db.one("SELECT * FROM os_outbox WHERE job_id=?", job);
                assertEquals("cancelled", row.get("state"));
                assertEquals("", row.get("envelope"));
                assertEquals("", row.get("lease"));
                assertEquals("OPERATOR_CANCELLED", row.get("error"));
                Outbox.finish(
                        db, map("job_id", job, "lease", "", "attempts", 1), "delivered", "", 0);
                assertEquals(
                        "cancelled",
                        db.one("SELECT state FROM os_outbox WHERE job_id=?", job).get("state"));
            }
            assertNull(Outbox.claim(db, System.currentTimeMillis()));
            assertEquals(
                    "private-envelope",
                    db.one("SELECT envelope FROM os_outbox WHERE job_id=?", processing)
                            .get("envelope"));
            var events =
                    db.all(
                            "SELECT detail FROM os_security_event WHERE"
                                    + " action='delivery.cancelled'");
            assertEquals(2, events.size());
            assertEquals(
                    Set.of(queued, failed),
                    events.stream()
                            .map(e -> object(e.get("detail")).get("job_id"))
                            .collect(java.util.stream.Collectors.toSet()));
            assertThrows(
                    Failure.class,
                    () -> Outbox.cancel(db, RecordAccess.owner(db, "CancelReader"), processing));
        }
        h.login("admin");
        assertEquals(
                403, h.form("/admin/delivery/" + processing + "/cancel", map(), null).statusCode());
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
            db.update("UPDATE os_outbox SET kind='email.recovery' WHERE job_id=?", failure);
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
