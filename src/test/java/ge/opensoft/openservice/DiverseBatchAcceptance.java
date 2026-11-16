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

    static void knowledgeHistory(WorkflowSupport h) throws Exception {
        assertEquals(405, h.get(ROOT + "assist", auth("admin")).statusCode());
        h.account("HistoryReader", "reader");
        String article = h.add("kb_knowledge").get("sys_id").toString();
        String tablePath = "/api/now/table/kb_knowledge/" + article;
        assertEquals(200, h.json("PATCH", tablePath, map("text", "Published content"), auth("admin")).statusCode());
        PlatformOperationsAcceptance.post(h, "knowledge/" + article, map("operation", "publish"));
        assertEquals(200, h.json("PATCH", tablePath, map("text", "PRIVATE DRAFT"), auth("admin")).statusCode());
        try (var db = h.store.open()) {
            for (int i=2; i<=102; i++) db.update("INSERT INTO os_publication(article_id,version,title,content,published_at,retired) VALUES(?,?,?,?,?,1)", article,i,"Title "+i,"Snapshot "+i,now());
            db.connection.commit();
        }
        String path = "knowledge/" + article;
        var first = result(h, path);
        assertEquals(100, objects(first.get("versions")).size());
        assertEquals("3", first.get("next_before"));
        assertEquals(102, ((Number)objects(first.get("versions")).getFirst().get("version")).intValue());
        var last = result(h, path + "?before=3");
        assertEquals(2, objects(last.get("versions")).size());
        assertEquals("", last.get("next_before"));
        assertEquals("Published content", objects(last.get("versions")).getLast().get("content"));
        assertFalse(last.toString().contains("PRIVATE DRAFT"));
        assertTrue(objects(result(h, path + "?before=1").get("versions")).isEmpty());
        assertEquals(401, h.get(ROOT + path, null).statusCode());
        assertEquals(403, h.get(ROOT + path, auth("HistoryReader")).statusCode());
        assertEquals(404, h.get(ROOT + "knowledge/missing", auth("admin")).statusCode());
        for (String query : List.of("before=0", "before=no", "before=2147483648", "extra=1", "before=2&before=3"))
            assertEquals(400, h.get(ROOT + path + "?" + query, auth("admin")).statusCode(), query);
        assertEquals(400, h.json("POST", ROOT + path + "?before=2", map("operation", "retire"), auth("admin")).statusCode());
    }

    static void cancelClocks(WorkflowSupport h) throws Exception {
        h.account("ClockReader", "reader");
        String record = h.add("incident").get("sys_id").toString();
        PlatformOperationsAcceptance.post(h, "calendars", map("name", "cancel-hours", "calendar", PlatformFoundationTest.calendar()));
        for (String initial : List.of("running", "paused")) {
            String clock = PlatformOperationsAcceptance.post(h, "clocks", map("table", "incident", "record_id", record, "calendar", "cancel-hours", "minutes", 120)).get("sla_id").toString();
            if (initial.equals("paused")) PlatformOperationsAcceptance.post(h, "clocks/"+clock, map("operation", "pause"));
            assertEquals(403, h.json("POST", ROOT+"clocks/"+clock, map("operation", "cancel"), auth("ClockReader")).statusCode());
            PlatformOperationsAcceptance.post(h, "clocks/"+clock, map("operation", "cancel"));
            assertEquals(400, h.json("POST", ROOT+"clocks/"+clock, map("operation", "resume"), auth("admin")).statusCode());
            try (var db = h.store.open()) {
                var before = db.one("SELECT state,last_tick,remaining_ms FROM os_sla WHERE sla_id=?", clock);
                assertEquals("cancelled", before.get("state"));
                ServiceClocks.tick(db, System.currentTimeMillis() + 86400000);
                assertEquals(before, db.one("SELECT state,last_tick,remaining_ms FROM os_sla WHERE sla_id=?", clock));
                assertEquals(1, db.count("SELECT COUNT(*) FROM os_security_event WHERE action='sla.cancel' AND CAST(detail AS TEXT) LIKE ?", "%"+clock+"%"));
            }
        }
        String late = PlatformOperationsAcceptance.post(h, "clocks", map("table", "incident", "record_id", record, "calendar", "cancel-hours", "minutes", 120)).get("sla_id").toString();
        try (var db = h.store.open()) {
            db.update("UPDATE os_sla SET last_tick=? WHERE sla_id=?", System.currentTimeMillis()-7L*86400000, late);
            db.connection.commit();
        }
        PlatformOperationsAcceptance.post(h, "clocks/"+late, map("operation", "cancel"));
        try (var db = h.store.open()) {
            assertEquals("breached", db.one("SELECT state FROM os_sla WHERE sla_id=?", late).get("state"));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_security_event WHERE action='sla.cancel' AND CAST(detail AS TEXT) LIKE ?", "%"+late+"%"));
        }
        assertEquals(400, h.json("POST", ROOT+"clocks/"+late, map("operation", "cancel"), auth("admin")).statusCode());
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
