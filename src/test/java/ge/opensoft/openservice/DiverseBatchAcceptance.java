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
