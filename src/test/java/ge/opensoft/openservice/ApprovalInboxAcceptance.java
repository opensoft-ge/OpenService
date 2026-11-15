package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class ApprovalInboxAcceptance {
    static final String API = "/api/openservice/me/approvals";

    static List<Map<String, Object>> rows(WorkflowSupport h, String query, String user)
            throws Exception {
        var response = h.get(API + query, auth(user));
        assertEquals(200, response.statusCode(), html(response));
        return objects(parsed(response).get("result"));
    }

    static void run(WorkflowSupport h) throws Exception {
        h.account("InboxAgent", "agent");
        h.account("InboxOther", "agent");
        h.account("InboxReader", "reader");
        h.account("InboxRequester", "requester");
        assertEquals(0, rows(h, "", "InboxAgent").size());
        String record = h.add("incident").get("sys_id").toString(),
                path = "/api/openservice/records/incident/" + record + "/approvals";
        String approval = ApprovalAcceptance.create(h, path, "InboxAgent", "admin");
        String foreign = ApprovalAcceptance.create(h, path, "InboxOther", "admin");
        assertEquals(0, rows(h, "", "admin").size());
        var own = rows(h, "", "InboxAgent");
        assertEquals(1, own.size());
        assertEquals(approval, own.getFirst().get("approval_id"));
        assertEquals(true, own.getFirst().get("current"));
        assertEquals("incident", own.getFirst().get("record_table"));
        assertFalse(own.getFirst().containsKey("sequence"));
        assertFalse(own.getFirst().containsKey("decision_revision"));
        assertFalse(own.getFirst().containsKey("data"));
        assertFalse(own.getFirst().containsKey("short_description"));
        for (String user : List.of("InboxReader", "InboxRequester"))
            assertEquals(403, h.get(API, auth(user)).statusCode());
        assertEquals(401, h.get(API, null).statusCode());
        assertEquals(405, h.json("POST", API, map(), auth("admin")).statusCode());
        for (String query :
                List.of(
                        "?unexpected=x",
                        "?status=",
                        "?status=unknown",
                        "?before=",
                        "?before=bad",
                        "?before=" + id(),
                        "?before=" + foreign,
                        "?status=requested&status=approved",
                        "?before=" + approval + "&before=" + approval))
            assertEquals(400, h.get(API + query, auth("InboxAgent")).statusCode(), query);
        String secret = "HiddenSummary" + id();
        assertEquals(
                200,
                h.json(
                                "PATCH",
                                "/api/now/table/incident/" + record,
                                map("short_description", secret),
                                auth("admin"))
                        .statusCode());
        assertEquals(false, rows(h, "", "InboxAgent").getFirst().get("current"));
        Map<String, Object> original;
        try (var db = h.store.open()) {
            original = Service.schema(db, "incident");
        }
        try {
            var definition = new LinkedHashMap<>(original);
            definition.put(
                    "field_rules",
                    map(
                            "short_description",
                            map("type", "string", "read_roles", List.of("admin"))));
            ApprovalAcceptance.writeSchema(h, definition);
            assertFalse(html(h.get(API, auth("InboxAgent"))).contains(secret));
            definition.put(
                    "field_rules",
                    map("state", map("type", "string", "read_roles", List.of("admin"))));
            ApprovalAcceptance.writeSchema(h, definition);
            assertEquals(0, rows(h, "", "InboxAgent").size());
        } finally {
            ApprovalAcceptance.writeSchema(h, original);
        }
        String group = h.add("sys_user_group").get("sys_id").toString();
        assertEquals(
                200,
                h.json(
                                "PATCH",
                                "/api/openservice/access/incident/" + record,
                                map("visibility", "group", "group_id", group),
                                auth("admin"))
                        .statusCode());
        assertEquals(0, rows(h, "", "InboxAgent").size());
        try (var db = h.store.open()) {
            GroupMembers.change(
                    db,
                    group,
                    RecordAccess.owner(db, "admin"),
                    map("user_id", RecordAccess.owner(db, "InboxAgent")),
                    true);
            db.connection.commit();
        }
        assertEquals(1, rows(h, "", "InboxAgent").size());
        assertEquals(0, rows(h, "", "InboxOther").size());
        try (var db = h.store.open()) {
            GroupMembers.change(
                    db,
                    group,
                    RecordAccess.owner(db, "admin"),
                    map("user_id", RecordAccess.owner(db, "InboxAgent")),
                    false);
            db.connection.commit();
        }
        assertEquals(0, rows(h, "", "InboxAgent").size());
        assertEquals(
                204,
                h.request("DELETE", "/api/now/table/incident/" + record, null, null, auth("admin"))
                        .statusCode());
        assertEquals(0, rows(h, "", "InboxAgent").size());
        String adminRecord = h.add("incident").get("sys_id").toString();
        String adminPath = "/api/openservice/records/incident/" + adminRecord + "/approvals";
        ApprovalAcceptance.create(h, adminPath, "admin", "InboxAgent");
        assertEquals(1, rows(h, "", "admin").size());
        assertEquals(true, rows(h, "", "admin").getFirst().get("current"));
        try {
            var changed = new LinkedHashMap<>(original);
            changed.put("version", 42);
            ApprovalAcceptance.writeSchema(h, changed);
            assertEquals(false, rows(h, "", "admin").getFirst().get("current"));
        } finally {
            ApprovalAcceptance.writeSchema(h, original);
        }
        pagination(h);
        noReadableStateTables(h);
        new Store(h.environment);
        assertEquals(1, rows(h, "?status=approved", "InboxAgent").size());
    }

    static void pagination(WorkflowSupport h) throws Exception {
        String visible = h.add("incident").get("sys_id").toString(),
                privateRecord = h.add("incident").get("sys_id").toString();
        String path = "/api/openservice/records/incident/" + visible + "/approvals",
                privatePath = path.replace(visible, privateRecord);
        String first = ApprovalAcceptance.create(h, path, "InboxAgent", "admin");
        try (var db = h.store.open()) {
            for (int i = 1; i <= 100; i++) copy(db, first, i);
            db.connection.commit();
        }
        String hidden = ApprovalAcceptance.create(h, privatePath, "InboxAgent", "admin");
        try (var db = h.store.open()) {
            for (int i = 1; i <= 140; i++) copy(db, hidden, i);
            RecordAccess.change(
                    db,
                    Service.record(db, "incident", privateRecord),
                    RecordAccess.owner(db, "admin"),
                    map("visibility", "private", "owner_id", RecordAccess.owner(db, "admin")));
            db.connection.commit();
        }
        var page = parsed(h.get(API, auth("InboxAgent")));
        assertEquals(100, objects(page.get("result")).size());
        assertTrue(
                objects(page.get("result")).stream()
                        .allMatch(r -> r.get("record_id").equals(visible)));
        String cursor = page.get("next_before").toString();
        assertFalse(cursor.isBlank());
        var older = parsed(h.get(API + "?before=" + cursor, auth("InboxAgent")));
        assertEquals(1, objects(older.get("result")).size());
        assertEquals(first, objects(older.get("result")).getFirst().get("approval_id"));
        assertEquals("", older.get("next_before"));
        assertEquals(0, rows(h, "?before=" + first, "InboxAgent").size());
        assertEquals(
                200,
                h.json(
                                "POST",
                                path + "/" + first + "/decision",
                                map(
                                        "decision",
                                        "approved",
                                        "comment",
                                        "Approved for queue history"),
                                auth("InboxAgent"))
                        .statusCode());
        assertEquals(100, rows(h, "", "InboxAgent").size());
        assertEquals(1, rows(h, "?status=approved", "InboxAgent").size());
        assertEquals(0, rows(h, "?status=rejected", "InboxAgent").size());
        assertEquals(1, rows(h, "?status=approved&before=" + cursor, "InboxAgent").size());
    }

    static void copy(Store.Db db, String source, int revision) throws Exception {
        db.update(
                "INSERT INTO"
                    + " os_approval(approval_id,record_id,record_revision,schema_version,target_state,requested_by,requester_name,approver_id,approver_name,status,reason,comment,created_at,decided_at,decision_revision)"
                    + " SELECT"
                    + " ?,record_id,?,schema_version,target_state,requested_by,requester_name,approver_id,approver_name,status,reason,comment,created_at,decided_at,decision_revision"
                    + " FROM os_approval WHERE approval_id=?",
                id(),
                revision,
                source);
    }

    static void noReadableStateTables(WorkflowSupport h) throws Exception {
        List<Map<String, Object>> schemas;
        try (var db = h.store.open()) {
            schemas = db.all("SELECT name,definition FROM os_schema");
            for (var row : schemas) {
                var definition = new LinkedHashMap<>(object(row.get("definition")));
                if (!object(definition.get("fields")).containsKey("state")) continue;
                var rules = new LinkedHashMap<>(TypedFields.rules(definition));
                rules.put("state", map("type", "string", "read_roles", List.of("admin")));
                definition.put("field_rules", rules);
                db.update(
                        "UPDATE os_schema SET definition=" + db.jsonParameter() + " WHERE name=?",
                        JSON.writeValueAsString(definition),
                        row.get("name"));
            }
            db.connection.commit();
        }
        try {
            assertEquals(0, rows(h, "", "InboxAgent").size());
        } finally {
            try (var db = h.store.open()) {
                for (var row : schemas)
                    db.update(
                            "UPDATE os_schema SET definition="
                                    + db.jsonParameter()
                                    + " WHERE name=?",
                            JSON.writeValueAsString(row.get("definition")),
                            row.get("name"));
                db.connection.commit();
            }
        }
    }
}
