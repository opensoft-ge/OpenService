package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class ApprovalAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("Reviewer", "agent");
        h.account("ReviewReader", "reader");
        String record = h.add("incident").get("sys_id").toString();
        String path = "/api/openservice/records/incident/" + record + "/approvals";
        assertEquals(200, h.get(path, auth("admin")).statusCode());
        var created =
                h.json(
                        "POST",
                        path,
                        map(
                                "expected_revision",
                                0,
                                "approver",
                                "Reviewer",
                                "target_state",
                                "2",
                                "reason",
                                "Review the proposed start of work"),
                        auth("admin"));
        assertEquals(201, created.statusCode(), html(created));
        var approval = object(parsed(created).get("result"));
        String id = approval.get("approval_id").toString();
        assertEquals("requested", approval.get("status"));
        assertEquals(true, approval.get("current"));
        assertEquals(403, h.get(path, auth("ReviewReader")).statusCode());
        assertEquals(
                403,
                h.json(
                                "POST",
                                path + "/" + id + "/decision",
                                map("decision", "approved", "comment", ""),
                                auth("admin"))
                        .statusCode());
        assertEquals(
                200,
                h.json(
                                "POST",
                                path + "/" + id + "/decision",
                                map("decision", "approved", "comment", "Reviewed this revision"),
                                auth("Reviewer"))
                        .statusCode());
        assertEquals(
                409,
                h.json(
                                "POST",
                                path + "/" + id + "/decision",
                                map("decision", "rejected", "comment", "Second decision"),
                                auth("Reviewer"))
                        .statusCode());
        assertEquals(
                "1",
                object(
                                parsed(h.get("/api/now/table/incident/" + record, auth("admin")))
                                        .get("result"))
                        .get("state"));
        new Store(h.environment);
        var rows = objects(parsed(h.get(path, auth("Reviewer"))).get("result"));
        assertEquals(1, rows.size());
        assertEquals("approved", rows.getFirst().get("status"));
        assertEquals("Reviewed this revision", rows.getFirst().get("comment"));
        assertFalse(rows.getFirst().containsKey("sequence"));
        assertFalse(rows.getFirst().containsKey("decision_revision"));
        boundaries(h, path, record);
        permissions(h);
        pagination(h);
    }

    static Map<String, Object> proposal(String reviewer) {
        return map(
                "expected_revision",
                0,
                "approver",
                reviewer,
                "target_state",
                "2",
                "reason",
                "Please review");
    }

    static String create(WorkflowSupport h, String path, String reviewer, String actor)
            throws Exception {
        var response = h.json("POST", path, proposal(reviewer), auth(actor));
        assertEquals(201, response.statusCode(), html(response));
        return object(parsed(response).get("result")).get("approval_id").toString();
    }

    static void boundaries(WorkflowSupport h, String path, String record) throws Exception {
        assertEquals(409, h.json("POST", path, proposal("Reviewer"), auth("admin")).statusCode());
        for (Object invalid : List.of(List.of(), map(), map("extra", true)))
            assertEquals(400, h.json("POST", path, invalid, auth("admin")).statusCode());
        for (String key : List.of("expected_revision", "approver", "target_state", "reason")) {
            var wrong = proposal("Reviewer");
            wrong.put(key, null);
            assertEquals(400, h.json("POST", path, wrong, auth("admin")).statusCode(), key);
        }
        for (Object revision : List.of(-1, "0", 0.5, 2147483648L)) {
            var wrong = proposal("Reviewer");
            wrong.put("expected_revision", revision);
            assertEquals(400, h.json("POST", path, wrong, auth("admin")).statusCode());
        }
        var stale = proposal("Reviewer");
        stale.put("expected_revision", 1);
        assertEquals(409, h.json("POST", path, stale, auth("admin")).statusCode());
        for (String reason : List.of(" ", "x".repeat(2001), "bad\0text")) {
            var wrong = proposal("Reviewer");
            wrong.put("reason", reason);
            assertEquals(400, h.json("POST", path, wrong, auth("admin")).statusCode());
        }
        h.account("DormantReviewer", "agent");
        try (var db = h.store.open()) {
            db.update("UPDATE os_user SET active=0 WHERE name='DormantReviewer'");
            db.connection.commit();
        }
        for (String reviewer :
                List.of("unknown", "DormantReviewer", "ReviewReader", "admin", "bad\0name"))
            assertEquals(400, h.json("POST", path, proposal(reviewer), auth("admin")).statusCode());
        for (String target : List.of("unknown", "1", "7")) {
            var wrong = proposal("Reviewer");
            wrong.put("target_state", target);
            assertEquals(400, h.json("POST", path, wrong, auth("admin")).statusCode());
        }
        for (String query :
                List.of(
                        "?unexpected=x",
                        "?before=bad",
                        "?before=",
                        "?before=" + id(),
                        "?before=x&before=y"))
            assertEquals(400, h.get(path + query, auth("admin")).statusCode());
        assertEquals(405, h.json("PUT", path, map(), auth("admin")).statusCode());
        assertEquals(
                400,
                h.json("POST", path + "?x=y", proposal("Reviewer"), auth("admin")).statusCode());
        assertEquals(404, h.get(path.replace(record, id()), auth("admin")).statusCode());
        assertEquals(401, h.get(path, null).statusCode());
        String second = h.add("incident").get("sys_id").toString(),
                other = path.replace(record, second);
        var large = proposal("Reviewer");
        large.put("reason", "😀".repeat(2000));
        var response = h.json("POST", other, large, auth("admin"));
        assertEquals(201, response.statusCode());
        String approval = object(parsed(response).get("result")).get("approval_id").toString();
        String decision = other + "/" + approval + "/decision";
        assertEquals(
                404,
                h.json(
                                "POST",
                                path + "/" + approval + "/decision",
                                map("decision", "approved", "comment", ""),
                                auth("Reviewer"))
                        .statusCode());
        assertEquals(405, h.get(decision, auth("Reviewer")).statusCode());
        for (Object wrong :
                List.of(
                        map(),
                        map("decision", "invalid", "comment", ""),
                        map("decision", false, "comment", ""),
                        map("decision", null, "comment", ""),
                        map("decision", "approved", "comment", null),
                        map("decision", "rejected", "comment", " "),
                        map("decision", "approved", "comment", "x".repeat(2001)),
                        map("decision", "approved", "comment", "a\0b")))
            assertEquals(400, h.json("POST", decision, wrong, auth("Reviewer")).statusCode());
        assertEquals(
                200,
                h.json(
                                "POST",
                                decision,
                                map("decision", "rejected", "comment", "😀".repeat(2000)),
                                auth("Reviewer"))
                        .statusCode());
        assertEquals(400, h.get(path + "?before=" + approval, auth("admin")).statusCode());
        // A new record revision invalidates an undecided proposal without erasing its history.
        String third = h.add("incident").get("sys_id").toString(),
                changed = path.replace(record, third);
        String pending = create(h, changed, "Reviewer", "admin");
        assertEquals(
                200,
                h.json(
                                "PATCH",
                                "/api/now/table/incident/" + third,
                                map("description", "Changed context"),
                                auth("admin"))
                        .statusCode());
        assertEquals(
                409,
                h.json(
                                "POST",
                                changed + "/" + pending + "/decision",
                                map("decision", "approved", "comment", ""),
                                auth("Reviewer"))
                        .statusCode());
        assertEquals(
                false,
                objects(parsed(h.get(changed, auth("Reviewer"))).get("result"))
                        .getFirst()
                        .get("current"));
        String browser = other.replace("/api/openservice/records/", "/record/");
        String csrf = h.login("admin");
        assertEquals(
                400,
                h.form(browser, map("csrf", csrf, "expected_revision", "bad"), null).statusCode());
        assertEquals(
                400,
                h.form(browser, map("csrf", csrf, "expected_revision", "2147483648"), null)
                        .statusCode());
        assertEquals(403, h.form(browser, map("expected_revision", "0"), null).statusCode());
        assertEquals(
                409,
                h.form(
                                browser,
                                map(
                                        "csrf",
                                        csrf,
                                        "expected_revision",
                                        "2147483647",
                                        "approver",
                                        "Reviewer",
                                        "target_state",
                                        "2",
                                        "reason",
                                        "At maximum revision"),
                                null)
                        .statusCode());
    }

    static void permissions(WorkflowSupport h) throws Exception {
        h.account("SecondReviewer", "agent");
        String record = h.add("incident").get("sys_id").toString();
        String path = "/api/openservice/records/incident/" + record + "/approvals";
        String approval = create(h, path, "Reviewer", "admin");
        String decision = path + "/" + approval + "/decision";
        String owner;
        try (var db = h.store.open()) {
            owner = RecordAccess.owner(db, "SecondReviewer");
        }
        assertEquals(
                200,
                h.json(
                                "PATCH",
                                "/api/openservice/access/incident/" + record,
                                map("visibility", "private", "owner_id", owner),
                                auth("admin"))
                        .statusCode());
        assertEquals(404, h.get(path, auth("Reviewer")).statusCode());
        assertEquals(
                404,
                h.json(
                                "POST",
                                decision,
                                map("decision", "approved", "comment", ""),
                                auth("Reviewer"))
                        .statusCode());
        assertEquals(400, h.json("POST", path, proposal("Reviewer"), auth("admin")).statusCode());
        assertEquals(
                201, h.json("POST", path, proposal("admin"), auth("SecondReviewer")).statusCode());
        assertEquals(
                200,
                h.json(
                                "PATCH",
                                "/api/openservice/access/incident/" + record,
                                map("visibility", "workspace", "owner_id", ""),
                                auth("admin"))
                        .statusCode());
        Map<String, Object> original;
        try (var db = h.store.open()) {
            original = Service.schema(db, "incident");
        }
        try {
            var definition = new LinkedHashMap<>(original);
            definition.put("version", 1);
            writeSchema(h, definition);
            assertEquals(
                    409,
                    h.json(
                                    "POST",
                                    decision,
                                    map("decision", "approved", "comment", ""),
                                    auth("Reviewer"))
                            .statusCode());
            assertEquals(
                    false,
                    objects(parsed(h.get(path, auth("Reviewer"))).get("result"))
                            .getFirst()
                            .get("current"));
            definition = new LinkedHashMap<>(original);
            definition.put("record_roles", map("update", List.of("admin")));
            writeSchema(h, definition);
            assertEquals(
                    403,
                    h.json(
                                    "POST",
                                    decision,
                                    map("decision", "approved", "comment", ""),
                                    auth("Reviewer"))
                            .statusCode());
            assertEquals(
                    403,
                    h.json("POST", path, proposal("admin"), auth("SecondReviewer")).statusCode());
            definition = new LinkedHashMap<>(original);
            definition.put(
                    "field_rules",
                    map("state", map("type", "string", "read_roles", List.of("admin"))));
            writeSchema(h, definition);
            assertEquals(403, h.get(path, auth("Reviewer")).statusCode());
            assertEquals(
                    400,
                    h.json("POST", path, proposal("SecondReviewer"), auth("admin")).statusCode());
            definition = new LinkedHashMap<>(original);
            definition.put(
                    "field_rules",
                    map("state", map("type", "string", "write_roles", List.of("admin"))));
            writeSchema(h, definition);
            assertEquals(
                    403,
                    h.json(
                                    "POST",
                                    decision,
                                    map("decision", "approved", "comment", ""),
                                    auth("Reviewer"))
                            .statusCode());
            definition = new LinkedHashMap<>(original);
            definition.put("transition_rules", map("2", map("roles", List.of("admin"))));
            writeSchema(h, definition);
            assertEquals(
                    403,
                    h.json(
                                    "POST",
                                    decision,
                                    map("decision", "approved", "comment", ""),
                                    auth("Reviewer"))
                            .statusCode());
        } finally {
            writeSchema(h, original);
        }
        assertEquals(
                200,
                h.json(
                                "POST",
                                decision,
                                map("decision", "approved", "comment", ""),
                                auth("Reviewer"))
                        .statusCode());
        boolean postgres;
        try (var db = h.store.open()) {
            postgres = db.postgres;
        }
        if (postgres) {
            try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                java.util.concurrent.Future<java.net.http.HttpResponse<byte[]>> read;
                try (var db = h.store.open()) {
                    db.all("SELECT pg_advisory_xact_lock(18439,4)");
                    read = worker.submit(() -> h.get(path, auth("Reviewer")));
                    boolean waiting = false;
                    for (int attempt = 0; attempt < 150; attempt++) {
                        waiting =
                                db.count(
                                                "SELECT COUNT(*) FROM pg_locks WHERE"
                                                    + " locktype='advisory' AND classid=18439 AND"
                                                    + " objid=4 AND NOT granted")
                                        > 0;
                        if (waiting) break;
                        Thread.sleep(20);
                    }
                    assertTrue(
                            waiting,
                            "Approval read must serialize with authorization/configuration"
                                    + " changes");
                    Service.changeRole(
                            db,
                            RecordAccess.owner(db, "admin"),
                            RecordAccess.owner(db, "Reviewer"),
                            map("role", "reader"));
                    db.connection.commit();
                }
                assertEquals(401, read.get(30, java.util.concurrent.TimeUnit.SECONDS).statusCode());
            }
            try (var db = h.store.open()) {
                Service.changeRole(
                        db,
                        RecordAccess.owner(db, "admin"),
                        RecordAccess.owner(db, "Reviewer"),
                        map("role", "agent"));
                db.connection.commit();
            }
        }
        String change = h.add("change_request").get("sys_id").toString(),
                changes = "/api/openservice/records/change_request/" + change + "/approvals";
        var proposal = proposal("Reviewer");
        proposal.put("target_state", "review");
        assertEquals(201, h.json("POST", changes, proposal, auth("admin")).statusCode());
        assertEquals(
                200,
                h.json(
                                "PATCH",
                                "/api/now/table/change_request/" + change,
                                map("state", "review"),
                                auth("admin"))
                        .statusCode());
        proposal.put("expected_revision", 1);
        proposal.put("target_state", "approved");
        assertEquals(400, h.json("POST", changes, proposal, auth("admin")).statusCode());
        proposal.put("approver", "admin");
        var created = h.json("POST", changes, proposal, auth("Reviewer"));
        assertEquals(201, created.statusCode(), html(created));
        assertEquals(
                200,
                h.json(
                                "POST",
                                changes
                                        + "/"
                                        + object(parsed(created).get("result")).get("approval_id")
                                        + "/decision",
                                map("decision", "approved", "comment", ""),
                                auth("admin"))
                        .statusCode());
    }

    static void writeSchema(WorkflowSupport h, Map<String, Object> definition) throws Exception {
        try (var db = h.store.open()) {
            db.update(
                    "UPDATE os_schema SET definition="
                            + db.jsonParameter()
                            + " WHERE name='incident'",
                    JSON.writeValueAsString(definition));
            db.connection.commit();
        }
    }

    static void pagination(WorkflowSupport h) throws Exception {
        String record = h.add("incident").get("sys_id").toString(),
                path = "/api/openservice/records/incident/" + record + "/approvals";
        create(h, path, "Reviewer", "admin");
        try (var db = h.store.open()) {
            for (int n = 1; n <= 100; n++)
                db.update(
                        "INSERT INTO"
                            + " os_approval(approval_id,record_id,record_revision,schema_version,target_state,requested_by,requester_name,approver_id,approver_name,status,reason,comment,created_at,decided_at,decision_revision)"
                            + " SELECT"
                            + " ?,record_id,?,schema_version,target_state,requested_by,requester_name,approver_id,approver_name,status,reason,comment,created_at,decided_at,decision_revision"
                            + " FROM os_approval WHERE record_id=? LIMIT 1",
                        id(),
                        n,
                        record);
            db.connection.commit();
        }
        var first = parsed(h.get(path, auth("admin")));
        assertEquals(100, objects(first.get("result")).size());
        assertEquals(1, ((Number) first.get("pending_count")).intValue());
        var next = parsed(h.get(path + "?before=" + first.get("next_before"), auth("admin")));
        assertEquals(1, objects(next.get("result")).size());
        assertEquals("", next.get("next_before"));
        assertEquals(
                0,
                objects(
                                parsed(
                                                h.get(
                                                        path
                                                                + "?before="
                                                                + objects(next.get("result"))
                                                                        .getFirst()
                                                                        .get("approval_id"),
                                                        auth("admin")))
                                        .get("result"))
                        .size());
    }
}
