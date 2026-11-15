package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class PermissionComparisonAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("CompareAgent", "agent");
        h.account("CompareReader", "reader");
        h.account("CompareRequester", "requester");
        String target, other, admin;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "CompareAgent");
            other = RecordAccess.owner(db, "CompareReader");
            admin = RecordAccess.owner(db, "admin");
        }
        var config = new ConfigurationAcceptance(h);
        try {
            var definition = ConfigurationAcceptance.definition();
            object(object(definition.get("field_rules")).get("amount"))
                    .put("read_roles", List.of("admin"));
            assertEquals(
                    200,
                    config.install(ConfigurationAcceptance.bundle(config.table, definition), false)
                            .statusCode());
            String record = h.add(config.table).get("sys_id").toString();
            String root = "/api/openservice/users/" + target + "/permission-comparison",
                    q = "?table=" + config.table + "&record_id=" + record + "&with=CompareReader",
                    api = root + q,
                    actual = "/api/now/table/" + config.table + "/" + record;
            assertEquals(401, h.get(api, null).statusCode());
            assertEquals(403, h.get(api, auth("CompareReader")).statusCode());
            assertEquals(405, h.json("POST", root, map(), auth("admin")).statusCode());
            for (String suffix :
                    List.of(
                            "",
                            q + "&x=1",
                            q + "&with=admin",
                            q.replace("CompareReader", "bad-name"),
                            q.replace("CompareReader", ""),
                            q.replace("CompareReader", "a".repeat(81)),
                            q.replace(record, "bad")))
                assertEquals(400, h.get(root + suffix, auth("admin")).statusCode());
            for (String wrong :
                    List.of(
                            api.replace(target, id()),
                            api.replace("CompareReader", "AbsentUser"),
                            api.replace(record, id())))
                assertEquals(404, h.get(wrong, auth("admin")).statusCode());
            var result = object(parsed(h.get(api, auth("admin"))).get("result"));
            var subjects = (List<?>) result.get("subjects");
            assertEquals(2, subjects.size());
            String plain = "?table=" + config.table + "&record_id=" + record;
            for (int i = 0; i < 2; i++) {
                String id = i == 0 ? target : other;
                var diagnosis = object(object(subjects.get(i)).get("diagnosis"));
                assertEquals(
                        object(
                                parsed(
                                                h.get(
                                                        "/api/openservice/users/"
                                                                + id
                                                                + "/permissions"
                                                                + plain,
                                                        auth("admin")))
                                        .get("result")),
                        diagnosis);
                assertFalse(
                        object(object(diagnosis.get("permissions")).get("fields"))
                                .containsKey("amount"));
                assertFalse(diagnosis.containsKey("data"));
            }
            assertEquals(
                    true,
                    object(
                                    object(
                                                    object(
                                                                    object(subjects.getFirst())
                                                                            .get("diagnosis"))
                                                            .get("permissions"))
                                            .get("update"))
                            .get("allowed"));
            assertEquals(
                    false,
                    object(
                                    object(
                                                    object(
                                                                    object(subjects.getLast())
                                                                            .get("diagnosis"))
                                                            .get("permissions"))
                                            .get("update"))
                            .get("allowed"));
            assertEquals(200, h.get(actual, auth("CompareReader")).statusCode());
            assertEquals(
                    403,
                    h.json(
                                    "PATCH",
                                    actual,
                                    map("short_description", "Denied change"),
                                    auth("CompareReader"))
                            .statusCode());
            assertEquals(
                    200,
                    h.json(
                                    "PATCH",
                                    actual,
                                    map("short_description", "Private business value"),
                                    auth("CompareAgent"))
                            .statusCode());
            assertFalse(html(h.get(api, auth("admin"))).contains("Private business value"));
            var same =
                    (List<?>)
                            object(
                                            parsed(
                                                            h.get(
                                                                    api.replace(
                                                                            "CompareReader",
                                                                            "CompareAgent"),
                                                                    auth("admin")))
                                                    .get("result"))
                                    .get("subjects");
            assertEquals(same.getFirst(), same.getLast());
            try (var db = h.store.open()) {
                db.viewerId = admin;
                db.viewerRole = "admin";
                var before = db.one("SELECT * FROM os_record WHERE sys_id=?", record);
                long events = db.count("SELECT COUNT(*) FROM os_security_event");
                PermissionComparison.inspect(
                        db,
                        admin,
                        target,
                        Map.of(
                                "with",
                                "CompareReader",
                                "table",
                                config.table,
                                "record_id",
                                record));
                assertEquals(admin, db.viewerId);
                assertEquals("admin", db.viewerRole);
                assertEquals(before, db.one("SELECT * FROM os_record WHERE sys_id=?", record));
                assertEquals(events, db.count("SELECT COUNT(*) FROM os_security_event"));
                assertEquals(
                        403,
                        assertThrows(
                                        Failure.class,
                                        () ->
                                                PermissionComparison.inspect(
                                                        db, other, target, Map.of()))
                                .status);
                RecordAccess.change(
                        db,
                        Service.record(db, config.table, record),
                        admin,
                        map("visibility", "private", "owner_id", target));
                db.connection.commit();
            }
            subjects =
                    (List<?>)
                            object(parsed(h.get(api, auth("admin"))).get("result")).get("subjects");
            assertEquals(
                    map("allowed", true, "reason", "allowed"),
                    object(object(subjects.getFirst()).get("diagnosis")).get("read"));
            assertEquals(
                    map("allowed", false, "reason", "record_visibility"),
                    object(object(subjects.getLast()).get("diagnosis")).get("read"));
            assertEquals(
                    map(), object(object(subjects.getLast()).get("diagnosis")).get("permissions"));
            assertEquals(404, h.get(actual, auth("CompareReader")).statusCode());
            var requester =
                    (List<?>)
                            object(
                                            parsed(
                                                            h.get(
                                                                    api.replace(
                                                                            "CompareReader",
                                                                            "CompareRequester"),
                                                                    auth("admin")))
                                                    .get("result"))
                                    .get("subjects");
            assertEquals(
                    map(), object(object(requester.getLast()).get("diagnosis")).get("permissions"));
            String group = h.add("sys_user_group").get("sys_id").toString();
            try (var db = h.store.open()) {
                RecordAccess.change(
                        db,
                        Service.record(db, config.table, record),
                        admin,
                        map("visibility", "group", "group_id", group));
                GroupMembers.change(db, group, admin, map("user_id", other), true);
                db.connection.commit();
            }
            subjects =
                    (List<?>)
                            object(parsed(h.get(api, auth("admin"))).get("result")).get("subjects");
            assertEquals(
                    map(), object(object(subjects.getFirst()).get("diagnosis")).get("permissions"));
            assertEquals(
                    map("allowed", true, "reason", "allowed"),
                    object(object(subjects.getLast()).get("diagnosis")).get("read"));
            assertEquals(404, h.get(actual, auth("CompareAgent")).statusCode());
            assertEquals(200, h.get(actual, auth("CompareReader")).statusCode());
            assertEquals(
                    200,
                    h.json(
                                    "PATCH",
                                    "/api/openservice/users/" + target + "/status",
                                    map("active", false),
                                    auth("admin"))
                            .statusCode());
            new Store(h.environment);
            subjects =
                    (List<?>)
                            object(parsed(h.get(api, auth("admin"))).get("result")).get("subjects");
            assertEquals(
                    map("allowed", false, "reason", "account_inactive"),
                    object(object(subjects.getFirst()).get("diagnosis")).get("read"));
            assertEquals(
                    map(), object(object(subjects.getFirst()).get("diagnosis")).get("permissions"));
            String ui = root.replace("/api/openservice/users/", "/admin/accounts/");
            assertEquals(200, h.get(ui, auth("admin")).statusCode());
            assertTrue(html(h.get(ui + q, auth("admin"))).contains("account inactive"));
        } finally {
            config.cleanup();
        }
    }
}
