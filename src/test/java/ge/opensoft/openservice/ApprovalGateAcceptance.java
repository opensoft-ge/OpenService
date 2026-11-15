package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class ApprovalGateAcceptance {
    static Map<String, Object> definition(Map<String, Object> original, int count) {
        var d = new LinkedHashMap<>(original);
        d.putIfAbsent("version", 0);
        d.put("transition_rules", map("2", map("min_approvals", count)));
        return d;
    }

    static void run(WorkflowSupport h) throws Exception {
        h.account("GateOne", "agent");
        h.account("GateTwo", "agent");
        Map<String, Object> original;
        try (var db = h.store.open()) {
            original = Service.schema(db, "incident");
        }
        try {
            var config = definition(original, 2);
            var response =
                    h.json(
                            "POST",
                            "/api/openservice/configuration?dry_run=false",
                            ConfigurationAcceptance.bundle("incident", config),
                            auth("admin"));
            assertEquals(200, response.statusCode(), html(response));
            String record = h.add("incident").get("sys_id").toString();
            String resource = "/api/now/table/incident/" + record;
            String reviews = "/api/openservice/records/incident/" + record + "/approvals";
            assertEquals(
                    409, h.json("PATCH", resource, map("state", "2"), auth("admin")).statusCode());
            approve(h, reviews, "GateOne");
            assertEquals(
                    409, h.json("PATCH", resource, map("state", "2"), auth("admin")).statusCode());
            approve(h, reviews, "GateTwo");
            assertEquals(
                    409,
                    h.json("PATCH", resource, map("state", "2"), auth("GateOne")).statusCode());
            assertEquals(
                    409,
                    h.json(
                                    "PATCH",
                                    resource,
                                    map("state", "2", "short_description", "Unreviewed edit"),
                                    auth("admin"))
                            .statusCode());
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
            }
            assertEquals(
                    200, h.json("PATCH", resource, map("state", "2"), auth("admin")).statusCode());
            assertEquals(
                    200,
                    h.json(
                                    "PATCH",
                                    resource,
                                    map("description", "Normal edit in protected state"),
                                    auth("admin"))
                            .statusCode());
            invalidConfiguration(h, original);
            revokedEvidence(h, original);
            terminalStates(h);
            concurrentRevocation(h);
            imports(h, original);
        } finally {
            ApprovalAcceptance.writeSchema(h, original);
        }
    }

    static void approve(WorkflowSupport h, String path, String name) throws Exception {
        String approval = ApprovalAcceptance.create(h, path, name, "admin");
        assertEquals(
                200,
                h.json(
                                "POST",
                                path + "/" + approval + "/decision",
                                map("decision", "approved", "comment", "Reviewed"),
                                auth(name))
                        .statusCode());
    }

    static void invalidConfiguration(WorkflowSupport h, Map<String, Object> original)
            throws Exception {
        Map<String, Object> current;
        try (var db = h.store.open()) {
            current = Service.schema(db, "incident");
        }
        for (Object count : List.of(-1, 11, "1", 1.5, true)) {
            var d = definition(current, 1);
            d.put("transition_rules", map("2", map("min_approvals", count)));
            assertEquals(
                    400,
                    h.json(
                                    "POST",
                                    "/api/openservice/configuration",
                                    ConfigurationAcceptance.bundle("incident", d),
                                    auth("admin"))
                            .statusCode());
        }
        var initial = definition(current, 1);
        initial.put("transition_rules", map("1", map("min_approvals", 1)));
        assertEquals(
                400,
                h.json(
                                "POST",
                                "/api/openservice/configuration",
                                ConfigurationAcceptance.bundle("incident", initial),
                                auth("admin"))
                        .statusCode());
        var disabled = definition(current, 0);
        disabled.put(
                "transition_rules",
                map("1", map("min_approvals", 0), "2", map("min_approvals", 10)));
        assertEquals(
                200,
                h.json(
                                "POST",
                                "/api/openservice/configuration?dry_run=true",
                                ConfigurationAcceptance.bundle("incident", disabled),
                                auth("admin"))
                        .statusCode());
    }

    static void revokedEvidence(WorkflowSupport h, Map<String, Object> original) throws Exception {
        ApprovalAcceptance.writeSchema(h, definition(original, 1));
        for (String change :
                List.of(
                        "credential",
                        "inactive",
                        "role",
                        "group",
                        "record",
                        "configuration",
                        "state-field",
                        "state-policy")) {
            String record = h.add("incident").get("sys_id").toString();
            String path = "/api/openservice/records/incident/" + record + "/approvals";
            String resource = "/api/now/table/incident/" + record;
            approve(h, path, "GateOne");
            Map<String, Object> user;
            try (var db = h.store.open()) {
                user = db.one("SELECT * FROM os_user WHERE name='GateOne'");
            }
            try {
                try (var db = h.store.open()) {
                    switch (change) {
                        case "credential" ->
                                db.update(
                                        "UPDATE os_user SET auth_revision=auth_revision+1 WHERE"
                                                + " name='GateOne'");
                        case "inactive" ->
                                db.update("UPDATE os_user SET active=0 WHERE name='GateOne'");
                        case "role" ->
                                db.update("UPDATE os_user SET role='reader' WHERE name='GateOne'");
                        case "group" ->
                                RecordAccess.change(
                                        db,
                                        Service.record(db, "incident", record),
                                        RecordAccess.owner(db, "admin"),
                                        map(
                                                "visibility",
                                                "private",
                                                "owner_id",
                                                RecordAccess.owner(db, "admin")));
                        default -> {}
                    }
                    db.connection.commit();
                }
                if (change.equals("record"))
                    assertEquals(
                            200,
                            h.json(
                                            "PATCH",
                                            resource,
                                            map("description", "New context"),
                                            auth("admin"))
                                    .statusCode());
                if (Set.of("configuration", "state-field", "state-policy").contains(change)) {
                    var d = definition(original, 1);
                    if (change.equals("configuration")) d.put("version", 42);
                    if (change.equals("state-field"))
                        d.put(
                                "field_rules",
                                map(
                                        "state",
                                        map("type", "string", "write_roles", List.of("admin"))));
                    if (change.equals("state-policy"))
                        d.put(
                                "transition_rules",
                                map("2", map("min_approvals", 1, "roles", List.of("admin"))));
                    ApprovalAcceptance.writeSchema(h, d);
                }
                assertEquals(
                        409,
                        h.json("PATCH", resource, map("state", "2"), auth("admin")).statusCode(),
                        change);
            } finally {
                try (var db = h.store.open()) {
                    db.update(
                            "UPDATE os_user SET active=?,role=?,auth_revision=? WHERE"
                                    + " name='GateOne'",
                            user.get("active"),
                            user.get("role"),
                            user.get("auth_revision"));
                    db.connection.commit();
                }
                ApprovalAcceptance.writeSchema(h, definition(original, 1));
            }
        }
    }

    static void terminalStates(WorkflowSupport h) throws Exception {
        for (String status : List.of("requested", "rejected", "withdrawn")) {
            String id = h.add("incident").get("sys_id").toString();
            String path = "/api/openservice/records/incident/" + id + "/approvals";
            String approval = ApprovalAcceptance.create(h, path, "GateOne", "admin");
            if (status.equals("rejected"))
                assertEquals(
                        200,
                        h.json(
                                        "POST",
                                        path + "/" + approval + "/decision",
                                        map("decision", "rejected", "comment", "Not ready"),
                                        auth("GateOne"))
                                .statusCode());
            if (status.equals("withdrawn"))
                assertEquals(
                        200,
                        h.json(
                                        "POST",
                                        path + "/" + approval + "/withdraw",
                                        map("comment", "Not needed"),
                                        auth("admin"))
                                .statusCode());
            assertEquals(
                    409,
                    h.json(
                                    "PATCH",
                                    "/api/now/table/incident/" + id,
                                    map("state", "2"),
                                    auth("admin"))
                            .statusCode());
        }
    }

    static void concurrentRevocation(WorkflowSupport h) throws Exception {
        if (!h.store.postgres) return;
        String record = h.add("incident").get("sys_id").toString();
        String path = "/api/openservice/records/incident/" + record + "/approvals";
        approve(h, path, "GateOne");
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<java.net.http.HttpResponse<byte[]>> write;
            try (var db = h.store.open()) {
                db.all("SELECT pg_advisory_xact_lock(18439,4)");
                write =
                        worker.submit(
                                () ->
                                        h.json(
                                                "PATCH",
                                                "/api/now/table/incident/" + record,
                                                map("state", "2"),
                                                auth("admin")));
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
                assertTrue(waiting, "Transition must serialize with authorization changes");
                Service.changeRole(
                        db,
                        RecordAccess.owner(db, "admin"),
                        RecordAccess.owner(db, "GateOne"),
                        map("role", "reader"));
                db.connection.commit();
            }
            assertEquals(409, write.get(30, java.util.concurrent.TimeUnit.SECONDS).statusCode());
        }
        try (var db = h.store.open()) {
            Service.changeRole(
                    db,
                    RecordAccess.owner(db, "admin"),
                    RecordAccess.owner(db, "GateOne"),
                    map("role", "agent"));
            db.connection.commit();
        }
    }

    static void imports(WorkflowSupport h, Map<String, Object> original) throws Exception {
        ApprovalAcceptance.writeSchema(h, definition(original, 1));
        var imported =
                map(
                        "sys_id",
                        id(),
                        "short_description",
                        "Unreviewed import",
                        "state",
                        "2",
                        "priority",
                        "3");
        for (String dry : List.of("true", "false")) {
            var response =
                    h.json(
                            "POST",
                            "/api/openservice/import/incident?dry_run=" + dry,
                            map("result", List.of(imported)),
                            auth("admin"));
            assertEquals(400, response.statusCode(), html(response));
            assertTrue(html(response).contains("unprotected state"), html(response));
        }
        MigrationSource.reset();
        MigrationSource.tables.put("incident", List.of(imported));
        String job = TransferValidationAcceptance.staged(h, TransferValidationAcceptance.basic());
        for (String action : List.of("preview", "apply")) {
            var response =
                    h.json(
                            "POST",
                            TransferAcceptance.API + "/" + job + "/" + action,
                            map(),
                            auth("admin"));
            assertEquals(409, response.statusCode(), html(response));
            assertTrue(html(response).contains("unprotected state"), html(response));
        }
        try (var db = h.store.open()) {
            assertNull(
                    db.one("SELECT sys_id FROM os_record WHERE sys_id=?", imported.get("sys_id")));
        }
        var existingRow = h.add("incident");
        String existing = existingRow.get("sys_id").toString();
        imported.put("sys_id", existing);
        imported.put("short_description", existingRow.get("short_description"));
        imported.put("number", existingRow.get("number"));
        imported.put("priority", existingRow.get("priority"));
        MigrationSource.tables.put("incident", List.of(imported));
        String update =
                TransferValidationAcceptance.staged(h, TransferValidationAcceptance.basic());
        assertEquals(
                409,
                h.json(
                                "POST",
                                TransferAcceptance.API + "/" + update + "/apply",
                                map(),
                                auth("admin"))
                        .statusCode());
        try (var db = h.store.open()) {
            assertEquals(
                    "1", object(Service.record(db, "incident", existing).get("data")).get("state"));
        }
    }
}
