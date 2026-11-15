package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class ApprovalRequirementsAcceptance {
    static Map<String, Object> snapshot(WorkflowSupport h, String path, String actor)
            throws Exception {
        var response = h.get(path, auth(actor));
        assertEquals(200, response.statusCode(), html(response));
        return parsed(response);
    }

    static Map<String, Object> target(Map<String, Object> page, String state) {
        return objects(page.get("requirements")).stream()
                .filter(r -> r.get("target_state").equals(state))
                .findFirst()
                .orElseThrow();
    }

    static void check(Map<String, Object> row, int required, int qualifying, boolean met) {
        assertEquals(required, row.get("required"));
        assertEquals(qualifying, row.get("qualifying"));
        assertEquals(met, row.get("approval_satisfied"));
        assertEquals(
                Set.of("target_state", "label", "required", "qualifying", "approval_satisfied"),
                row.keySet());
    }

    static void run(WorkflowSupport h) throws Exception {
        h.account("CountOne", "agent");
        h.account("CountTwo", "agent");
        h.account("CountRequester", "requester");
        h.account("CountReader", "reader");
        Map<String, Object> original;
        try (var db = h.store.open()) {
            original = Service.schema(db, "incident");
        }
        try {
            ApprovalAcceptance.writeSchema(h, ApprovalGateAcceptance.definition(original, 2));
            String record = h.add("incident").get("sys_id").toString();
            String path = "/api/openservice/records/incident/" + record + "/approvals";
            var first = snapshot(h, path, "admin");
            assertEquals(0, first.get("record_revision"));
            assertEquals(0, first.get("schema_version"));
            check(target(first, "2"), 2, 0, false);
            ApprovalGateAcceptance.approve(h, path, "CountOne");
            check(target(snapshot(h, path, "admin"), "2"), 2, 1, false);
            ApprovalGateAcceptance.approve(h, path, "CountTwo");
            check(target(snapshot(h, path, "admin"), "2"), 2, 2, true);
            check(target(snapshot(h, path, "CountOne"), "2"), 2, 1, false);
            try (var db = h.store.open()) {
                assertEquals(
                        0,
                        ((Number) Service.record(db, "incident", record).get("revision"))
                                .intValue());
                assertEquals(
                        0,
                        db.count(
                                "SELECT COUNT(*) FROM os_event WHERE record_id=? AND"
                                    + " action='updated'",
                                record));
                db.update("UPDATE os_user SET auth_revision=auth_revision+1 WHERE name='CountTwo'");
                db.connection.commit();
            }
            check(target(snapshot(h, path, "admin"), "2"), 2, 1, false);
            assertEquals(403, h.json("GET", path, null, auth("CountReader")).statusCode());
            assertEquals(403, h.json("GET", path, null, auth("CountRequester")).statusCode());
            assertEquals(
                    404,
                    h.json("GET", path.replace(record, id()), null, auth("admin")).statusCode());
            var hidden = new LinkedHashMap<>(ApprovalGateAcceptance.definition(original, 2));
            hidden.put(
                    "field_rules",
                    map("state", map("type", "string", "read_roles", List.of("admin"))));
            ApprovalAcceptance.writeSchema(h, hidden);
            assertEquals(403, h.json("GET", path, null, auth("CountOne")).statusCode());
            ApprovalAcceptance.writeSchema(h, ApprovalGateAcceptance.definition(original, 0));
            check(target(snapshot(h, path, "admin"), "2"), 0, 0, true);
            assertEquals(
                    200,
                    h.json(
                                    "PATCH",
                                    "/api/now/table/incident/" + record,
                                    map("state", "2"),
                                    auth("admin"))
                            .statusCode());
            var terminal = new LinkedHashMap<>(original);
            terminal.put("flow", map());
            ApprovalAcceptance.writeSchema(h, terminal);
            assertEquals(List.of(), snapshot(h, path, "admin").get("requirements"));
        } finally {
            ApprovalAcceptance.writeSchema(h, original);
        }
    }
}
