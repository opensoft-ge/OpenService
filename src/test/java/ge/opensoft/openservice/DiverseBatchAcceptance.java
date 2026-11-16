package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class DiverseBatchAcceptance {
    static final String ROOT = "/api/openservice/operations/";

    static Map<String, Object> result(WorkflowSupport h, String path) throws Exception {
        var response = h.get(ROOT + path, auth("admin"));
        assertEquals(200, response.statusCode(), html(response));
        return object(parsed(response).get("result"));
    }

    static void retireConnector(WorkflowSupport h) throws Exception {
        h.account("RetireAgent", "agent");
        try (var db = h.store.open()) {
            db.update("INSERT INTO os_connector(alias,origin,envelope,revision) VALUES(?,?,?,1)",
                    "retirable", "https://example.invalid",
                    Outbox.encrypt(h.store.secret, JSON.writeValueAsString(map("token", "synthetic-retirement-token"))));
            db.connection.commit();
        }
        String path = ROOT + "connectors/retirable";
        var reason = map("reason", "Integration no longer used");
        assertEquals(401, h.json("DELETE", path, reason, null).statusCode());
        assertEquals(403, h.json("DELETE", path, reason, auth("RetireAgent")).statusCode());
        assertEquals(400, h.json("DELETE", path, map(), auth("admin")).statusCode());
        assertEquals(400, h.json("DELETE", path, map("reason", " "), auth("admin")).statusCode());
        assertEquals(400, h.json("DELETE", ROOT + "connectors", reason, auth("admin")).statusCode());
        String job = PlatformOperationsAcceptance.post(h, "jobs", map("kind", "connector.read", "key", "retired-test",
                "payload", map("alias", "retirable", "path", "/health"))).get("job_id").toString();
        assertEquals(200, h.json("DELETE", path, reason, auth("admin")).statusCode());
        assertEquals(404, h.json("DELETE", path, reason, auth("admin")).statusCode());
        assertTrue(new WorkWorker(h.store).runOne(System.currentTimeMillis()));
        try (var db = h.store.open()) {
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_connector WHERE alias='retirable'"));
            var row = db.one("SELECT state,envelope,error FROM os_work WHERE job_id=?", job);
            assertEquals("cancelled", row.get("state"));
            assertEquals("", row.get("envelope"));
            assertEquals("AUTHORITY_OR_INPUT_UNAVAILABLE", row.get("error"));
            var events = db.all("SELECT detail FROM os_security_event WHERE action='connector.retired'");
            assertEquals(1, events.size());
            assertTrue(events.toString().contains("Integration no longer used"));
            assertFalse(events.toString().contains("synthetic-retirement-token"));
        }
    }

    static void impact(WorkflowSupport h) throws Exception {
        String source = h.add("cmdb_ci").get("sys_id").toString();
        String middle;
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            middle = PlatformFoundationTest.record(db, "cmdb_ci", "Middle");
            db.update("INSERT INTO os_dependency VALUES(?,?)", middle, source);
            db.update("INSERT INTO os_dependency VALUES(?,?)", source, middle);
            for (int i = 0; i < 99; i++) {
                String id = PlatformFoundationTest.record(db, "cmdb_ci", "Dependent " + i);
                db.update("INSERT INTO os_dependency VALUES(?,?)", id, middle);
            }
            db.connection.commit();
        }
        var exact = result(h, "dependencies/" + source);
        assertEquals(100, objects(exact.get("impacted")).size());
        assertEquals(false, exact.get("truncated"));
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            for (int i = 0; i < 110; i++) {
                String id = PlatformFoundationTest.record(db, "cmdb_ci", "Overflow " + i);
                db.update("INSERT INTO os_dependency VALUES(?,?)", id, middle);
            }
            db.connection.commit();
        }
        var overflow = result(h, "dependencies/" + source);
        var rows = objects(overflow.get("impacted"));
        assertEquals(100, rows.size());
        assertEquals(true, overflow.get("truncated"));
        assertEquals(100, rows.stream().map(r -> r.get("sys_id")).distinct().count());
        assertFalse(rows.stream().anyMatch(r -> source.equals(r.get("sys_id"))));
        assertEquals(401, h.get(ROOT + "dependencies/" + source, null).statusCode());
    }
}
