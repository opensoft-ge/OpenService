package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class AccountPermissionsAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("InspectAgent", "agent");
        h.account("InspectReader", "reader");
        h.account("InspectRequester", "requester");
        String target, other, requester, admin;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "InspectAgent");
            other = RecordAccess.owner(db, "InspectReader");
            requester = RecordAccess.owner(db, "InspectRequester");
            admin = RecordAccess.owner(db, "admin");
        }
        var a = new ConfigurationAcceptance(h);
        try {
            var definition = ConfigurationAcceptance.definition();
            object(object(definition.get("field_rules")).get("quantity"))
                    .put("write_roles", List.of("admin"));
            object(object(definition.get("field_rules")).get("amount"))
                    .put("read_roles", List.of("admin"));
            assertEquals(
                    200,
                    a.install(ConfigurationAcceptance.bundle(a.table, definition), false)
                            .statusCode());
            var row = h.add(a.table);
            String record = row.get("sys_id").toString();
            String root = "/api/openservice/users/" + target + "/permissions",
                    query = "?table=" + a.table + "&record_id=" + record,
                    api = root + query;
            String tableApi = "/api/now/table/" + a.table + "/" + record;
            assertEquals(401, h.get(api, null).statusCode());
            assertEquals(403, h.get(api, auth("InspectAgent")).statusCode());
            assertEquals(403, h.get(api, auth("InspectReader")).statusCode());
            assertEquals(400, h.get(root, auth("admin")).statusCode());
            assertEquals(400, h.get(root + "?table=" + a.table, auth("admin")).statusCode());
            assertEquals(
                    400,
                    h.get(root + "?table=" + a.table + "&record_id=bad", auth("admin"))
                            .statusCode());
            assertEquals(
                    400, h.get(root + "?table=&record_id=" + record, auth("admin")).statusCode());
            assertEquals(400, h.get(api + "&extra=1", auth("admin")).statusCode());
            assertEquals(400, h.get(api + "&record_id=" + record, auth("admin")).statusCode());
            assertEquals(405, h.json("POST", root, map(), auth("admin")).statusCode());
            assertEquals(404, h.get(api.replace(target, id()), auth("admin")).statusCode());
            assertEquals(
                    404,
                    h.get(root + "?table=" + a.table + "&record_id=" + id(), auth("admin"))
                            .statusCode());
            assertEquals(
                    404,
                    h.get(root + "?table=incident&record_id=" + record, auth("admin"))
                            .statusCode());
            var response = h.get(api, auth("admin"));
            assertEquals(200, response.statusCode());
            var result = object(parsed(response).get("result"));
            assertEquals(map("allowed", true, "reason", "allowed"), result.get("read"));
            var policy = object(result.get("permissions"));
            assertEquals(
                    object(
                            parsed(
                                            h.get(
                                                    "/api/openservice/permissions/"
                                                            + a.table
                                                            + "/"
                                                            + record,
                                                    auth("InspectAgent")))
                                    .get("result")),
                    policy);
            assertFalse(object(policy.get("fields")).containsKey("amount"));
            assertEquals(
                    map("allowed", false, "reason", "field_roles"),
                    object(policy.get("fields")).get("quantity"));
            assertFalse(result.containsKey("data"));
            assertFalse(html(response).contains("token_hash"));
            assertEquals(
                    403,
                    h.json("PATCH", tableApi, map("quantity", "9"), auth("InspectAgent"))
                            .statusCode());
            assertEquals(
                    200,
                    h.json(
                                    "PATCH",
                                    tableApi,
                                    map("short_description", "Policy permitted"),
                                    auth("InspectAgent"))
                            .statusCode());
            assertFalse(html(h.get(api, auth("admin"))).contains("Policy permitted"));
            var requesterResult =
                    object(
                            parsed(h.get(api.replace(target, requester), auth("admin")))
                                    .get("result"));
            assertEquals(
                    map("allowed", false, "reason", "record_visibility"),
                    requesterResult.get("read"));
            assertEquals(map(), requesterResult.get("permissions"));
            try (var db = h.store.open()) {
                db.viewerId = admin;
                db.viewerRole = "admin";
                var args = Map.of("table", a.table, "record_id", record);
                assertEquals(
                        403,
                        assertThrows(
                                        Failure.class,
                                        () -> AccountPermissions.inspect(db, target, other, args))
                                .status);
                AccountPermissions.inspect(db, admin, target, args);
                assertEquals(admin, db.viewerId);
                assertEquals("admin", db.viewerRole);
                if (db.postgres) {
                    try (var concurrent = h.store.open()) {
                        concurrent.update("SET LOCAL lock_timeout='1s'");
                        assertEquals(
                                1,
                                concurrent.update(
                                        "UPDATE os_record SET updated=updated WHERE sys_id=?",
                                        record));
                        concurrent.connection.rollback();
                    }
                }
                var malformed = new LinkedHashMap<>(Service.schema(db, a.table));
                malformed.put("fields", List.of());
                db.update(
                        "UPDATE os_schema SET definition=" + db.jsonParameter() + " WHERE name=?",
                        JSON.writeValueAsString(malformed),
                        a.table);
                assertThrows(
                        Failure.class, () -> AccountPermissions.inspect(db, admin, target, args));
                assertEquals(admin, db.viewerId);
                assertEquals("admin", db.viewerRole);
                db.connection.rollback();
                RecordAccess.change(
                        db,
                        Service.record(db, a.table, record),
                        admin,
                        map("visibility", "private", "owner_id", other));
                db.connection.commit();
            }
            result = object(parsed(h.get(api, auth("admin"))).get("result"));
            assertEquals(map("allowed", false, "reason", "record_visibility"), result.get("read"));
            assertEquals(map(), result.get("permissions"));
            assertEquals(404, h.get(tableApi, auth("InspectAgent")).statusCode());
            result = object(parsed(h.get(api.replace(target, other), auth("admin"))).get("result"));
            assertEquals(true, object(result.get("read")).get("allowed"));
            assertEquals(
                    false, object(object(result.get("permissions")).get("update")).get("allowed"));
            String group = h.add("sys_user_group").get("sys_id").toString();
            try (var db = h.store.open()) {
                RecordAccess.change(
                        db,
                        Service.record(db, a.table, record),
                        admin,
                        map("visibility", "group", "group_id", group));
                GroupMembers.change(db, group, admin, map("user_id", target), true);
                db.connection.commit();
            }
            assertEquals(
                    true,
                    object(object(parsed(h.get(api, auth("admin"))).get("result")).get("read"))
                            .get("allowed"));
            assertEquals(200, h.get(tableApi, auth("InspectAgent")).statusCode());
            try (var db = h.store.open()) {
                Service.changeActive(db, admin, target, map("active", false));
                db.connection.commit();
            }
            result = object(parsed(h.get(api, auth("admin"))).get("result"));
            assertEquals(map("allowed", false, "reason", "account_inactive"), result.get("read"));
            assertEquals(map(), result.get("permissions"));
            assertEquals(401, h.get(tableApi, auth("InspectAgent")).statusCode());
            new Store(h.environment);
            assertEquals(result, object(parsed(h.get(api, auth("admin"))).get("result")));
            String ui = "/admin/accounts/" + target + "/permissions";
            assertEquals(200, h.get(ui, auth("admin")).statusCode());
            assertTrue(
                    html(h.get(ui + query, auth("admin")))
                            .contains("Account inactive: access is blocked"));
            assertEquals(404, h.get(ui.replace(target, id()), auth("admin")).statusCode());
            try (var db = h.store.open()) {
                Service.changeActive(db, admin, target, map("active", true));
                GroupMembers.change(db, group, admin, map("user_id", target), false);
                db.connection.commit();
            }
            assertTrue(
                    html(h.get(ui + query, auth("admin")))
                            .contains("Record visibility blocks access"));
            assertEquals(404, h.get(tableApi, auth("InspectAgent")).statusCode());
            assertEquals(
                    "admin",
                    object(parsed(h.get("/api/openservice/me", auth("admin"))).get("result"))
                            .get("role"));
        } finally {
            a.cleanup();
        }
    }
}
