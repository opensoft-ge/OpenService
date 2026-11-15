package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;

final class ApiKeyWritesAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("WriteMachine", "agent");
        h.account("WriteReader", "reader");
        String target, reader, admin;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "WriteMachine");
            reader = RecordAccess.owner(db, "WriteReader");
            admin = RecordAccess.owner(db, "admin");
            db.update("UPDATE os_user SET api_only=1 WHERE user_id IN (?,?)", target, reader);
            db.connection.commit();
            assertEquals(
                    403,
                    assertThrows(Failure.class, () -> ApiKeyWrites.issue(db, reader, target, map()))
                            .status);
        }
        String api = "/api/openservice/users/" + target + "/api-keys/write",
                form = "/admin/accounts/" + target + "/api-keys/write";
        var body = map("label", "Writer", "table", "incident", "days", 1);
        assertEquals(401, h.json("POST", api, body, null).statusCode());
        assertEquals(403, h.json("POST", api, body, auth("WriteReader")).statusCode());
        assertEquals(405, h.get(api, auth("admin")).statusCode());
        assertEquals(400, h.json("POST", api + "?x=1", body, auth("admin")).statusCode());
        assertEquals(
                409, h.json("POST", api.replace(target, reader), body, auth("admin")).statusCode());
        assertEquals(
                404, h.json("POST", api.replace(target, id()), body, auth("admin")).statusCode());
        var issued = h.json("POST", api, body, auth("admin"));
        assertEquals(201, issued.statusCode());
        var key = object(parsed(issued).get("result"));
        String kid = key.get("key_id").toString(), bearer = "Bearer " + key.get("token");
        assertEquals("read_create_update", key.get("access"));
        var created =
                h.json(
                        "POST",
                        "/api/now/table/incident",
                        map("short_description", "Created by key"),
                        bearer);
        assertEquals(201, created.statusCode());
        String record = object(parsed(created).get("result")).get("sys_id").toString(),
                path = "/api/now/table/incident/" + record;
        for (String method : List.of("PATCH", "PUT"))
            assertEquals(
                    200,
                    h.json(method, path, map("short_description", "Updated by key"), bearer)
                            .statusCode());
        for (String method : List.of("DELETE", "POST"))
            assertEquals(403, h.json(method, path, map(), bearer).statusCode());
        var options = h.json("OPTIONS", path, map(), bearer);
        assertEquals(200, options.statusCode());
        assertEquals(0, options.body().length);
        assertTrue(options.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        assertEquals(403, h.json("PATCH", "/api/now/table/incident", map(), bearer).statusCode());
        assertEquals(
                403,
                h.json("POST", "/api/now/table/problem", map("short_description", "Denied"), bearer)
                        .statusCode());
        assertEquals(403, h.json("POST", "/api/openservice/requests", map(), bearer).statusCode());
        var cross =
                HttpRequest.newBuilder(URI.create(h.base() + path))
                        .header("Authorization", bearer)
                        .header("Origin", "https://external.invalid")
                        .header("Content-Type", "application/json")
                        .method(
                                "PATCH",
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"short_description\":\"bad\"}"))
                        .build();
        assertEquals(
                403, h.client.send(cross, HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        try (var db = h.store.open()) {
            RecordAccess.change(
                    db,
                    Service.record(db, "incident", record),
                    admin,
                    map("visibility", "private", "owner_id", reader));
            db.connection.commit();
        }
        assertEquals(
                404,
                h.json("PATCH", path, map("short_description", "Denied"), bearer).statusCode());
        try (var db = h.store.open()) {
            RecordAccess.change(
                    db,
                    Service.record(db, "incident", record),
                    admin,
                    map("visibility", "workspace", "owner_id", ""));
            db.connection.commit();
        }
        var a = new ConfigurationAcceptance(h);
        try {
            var definition = ConfigurationAcceptance.definition();
            object(object(definition.get("field_rules")).get("amount"))
                    .put("read_roles", List.of("admin"));
            definition.put(
                    "record_roles",
                    map(
                            "create",
                            List.of("admin"),
                            "update",
                            List.of("agent"),
                            "delete",
                            List.of()));
            assertEquals(
                    200,
                    a.install(ConfigurationAcceptance.bundle(a.table, definition), false)
                            .statusCode());
            var scoped =
                    object(
                            parsed(
                                            h.json(
                                                    "POST",
                                                    api,
                                                    map(
                                                            "label",
                                                            "Restricted",
                                                            "table",
                                                            a.table,
                                                            "days",
                                                            1),
                                                    auth("admin")))
                                    .get("result"));
            String auth = "Bearer " + scoped.get("token"), collection = "/api/now/table/" + a.table;
            assertEquals(
                    403,
                    h.json("POST", collection, map("short_description", "Denied"), auth)
                            .statusCode());
            var row = a.result(h.json("POST", collection, map("short_description", "Managed", "amount", "3"), auth("admin")), 201);
            assertEquals(
                    403,
                    h.json("PATCH", collection + "/" + row.get("sys_id"), map("amount", "4"), auth)
                            .statusCode());
            assertEquals(
                    200,
                    h.json(
                                    "PATCH",
                                    collection + "/" + row.get("sys_id"),
                                    map("short_description", "Allowed"),
                                    auth)
                            .statusCode());
            try (var db = h.store.open()) {
                ApiKeys.revoke(db, admin, target, scoped.get("key_id").toString());
                db.connection.commit();
            }
        } finally {
            a.cleanup();
        }
        new Store(h.environment);
        assertEquals(
                200,
                h.json("PATCH", path, map("short_description", "Restarted"), bearer).statusCode());
        if (h.store.postgres)
            try (var worker = Executors.newSingleThreadExecutor()) {
                Future<HttpResponse<byte[]>> pending;
                try (var db = h.store.open()) {
                    db.all("SELECT pg_advisory_xact_lock(18439,4)");
                    pending =
                            worker.submit(
                                    () ->
                                            h.json(
                                                    "PATCH",
                                                    path,
                                                    map("short_description", "Must not happen"),
                                                    bearer));
                    boolean waiting = false;
                    for (int i = 0; i < 150; i++) {
                        waiting =
                                db.count(
                                                "SELECT COUNT(*) FROM pg_locks WHERE"
                                                    + " locktype='advisory' AND classid=18439 AND"
                                                    + " objid=4 AND NOT granted")
                                        > 0;
                        if (waiting) break;
                        Thread.sleep(20);
                    }
                    assertTrue(waiting);
                    ApiKeyState.change(db, admin, target, kid, map("enabled", false));
                    db.connection.commit();
                }
                assertEquals(401, pending.get(30, TimeUnit.SECONDS).statusCode());
            }
        try (var db = h.store.open()) {
            assertEquals(
                    "Restarted",
                    object(Service.record(db, "incident", record).get("data"))
                            .get("short_description"));
            ApiKeyState.change(db, admin, target, kid, map("enabled", true));
            db.connection.commit();
            var rotated = ApiKeyRotation.rotate(db, admin, target, kid);
            assertEquals(
                    1,
                    ((Number)
                                    db.one("SELECT can_write FROM os_api_key WHERE key_id=?", kid)
                                            .get("can_write"))
                            .intValue());
            db.connection.rollback();
        }
        String csrf = h.login("admin");
        assertEquals(
                403,
                h.form(
                                form,
                                map("csrf", "bad", "label", "x", "table", "incident", "days", "1"),
                                null)
                        .statusCode());
        assertEquals(
                200,
                h.form(
                                form,
                                map(
                                        "csrf",
                                        csrf,
                                        "label",
                                        "Form writer",
                                        "table",
                                        "incident",
                                        "days",
                                        "1"),
                                null)
                        .statusCode());
        try (var db = h.store.open()) {
            assertEquals(
                    3,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                + " action='account.api_key_write_scope_issued'"));
            assertFalse(
                    db.all("SELECT detail FROM os_security_event")
                            .toString()
                            .contains(key.get("token").toString()));
            db.update("ALTER TABLE os_api_key DROP COLUMN can_write");
            db.update("DELETE FROM os_migration WHERE version>=26");
            db.connection.commit();
            Migrations.apply(db);
            assertEquals(
                    0,
                    ((Number)
                                    db.one("SELECT can_write FROM os_api_key WHERE key_id=?", kid)
                                            .get("can_write"))
                            .intValue());
            db.connection.rollback();
            assertEquals(25, db.count("SELECT COUNT(*) FROM os_migration"));
            Migrations.apply(db);
            db.connection.commit();
        }
        assertEquals(
                403,
                h.json(
                                "PATCH",
                                path,
                                map("short_description", "Denied"),
                                "Bearer " + key.get("token"))
                        .statusCode());
    }
}
