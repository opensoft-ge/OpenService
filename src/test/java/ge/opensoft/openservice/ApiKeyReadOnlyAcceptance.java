package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;

final class ApiKeyReadOnlyAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("ReduceMachine", "agent");
        h.account("ReduceOther", "reader");
        String target, other, admin, key, token, foreign;
        Map<String, Object> before;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "ReduceMachine");
            other = RecordAccess.owner(db, "ReduceOther");
            admin = RecordAccess.owner(db, "admin");
            db.update("UPDATE os_user SET api_only=1 WHERE user_id IN (?,?)", target, other);
            var issued =
                    ApiKeyWrites.issue(
                            db,
                            admin,
                            target,
                            map("label", "Writer", "table", "incident", "days", 30));
            key = issued.get("key_id").toString();
            token = "Bearer " + issued.get("token");
            foreign =
                    ApiKeys.issue(
                                    db,
                                    admin,
                                    other,
                                    map("label", "Other", "table", "incident", "days", 30))
                            .get("key_id")
                            .toString();
            db.connection.commit();
            assertEquals(
                    403,
                    assertThrows(
                                    Failure.class,
                                    () -> ApiKeyReadOnly.reduce(db, other, target, map()))
                            .status);
        }
        String api = "/api/openservice/users/" + target + "/api-keys/read-only",
                form = "/admin/accounts/" + target + "/api-keys/read-only";
        var body = map("key_id", key, "reason", "Least privilege review");
        assertEquals(401, h.json("POST", api, body, null).statusCode());
        assertEquals(403, h.json("POST", api, body, auth("ReduceOther")).statusCode());
        assertEquals(405, h.get(api, auth("admin")).statusCode());
        assertEquals(400, h.json("POST", api + "?x=1", body, auth("admin")).statusCode());
        assertEquals(
                404, h.json("POST", api.replace(target, id()), body, auth("admin")).statusCode());
        for (Object invalid :
                List.of(
                        map(),
                        map("key_id", key, "reason", "ok", "extra", true),
                        map("key_id", 5, "reason", "ok"),
                        map("key_id", "bad", "reason", "ok"),
                        map("key_id", key, "reason", null),
                        map("key_id", key, "reason", " "),
                        map("key_id", key, "reason", "x".repeat(501)),
                        map("key_id", key, "reason", "bad\0reason")))
            assertEquals(400, h.json("POST", api, invalid, auth("admin")).statusCode());
        for (String missing : List.of(id(), foreign))
            assertEquals(
                    404,
                    h.json("POST", api, map("key_id", missing, "reason", "ok"), auth("admin"))
                            .statusCode());
        var created =
                h.json(
                        "POST",
                        "/api/now/table/incident",
                        map("short_description", "Before reduction"),
                        token);
        assertEquals(201, created.statusCode());
        String record = object(parsed(created).get("result")).get("sys_id").toString(),
                path = "/api/now/table/incident/" + record;
        try (var db = h.store.open()) {
            before = db.one("SELECT * FROM os_api_key WHERE key_id=?", key);
            ApiKeyReadOnly.reduce(db, admin, target, body);
            db.connection.rollback();
            assertEquals(before, db.one("SELECT * FROM os_api_key WHERE key_id=?", key));
        }
        if (h.store.postgres) {
            try (var worker = Executors.newSingleThreadExecutor()) {
                Future<Integer> pending;
                try (var db = h.store.open()) {
                    db.all("SELECT pg_advisory_xact_lock(18439,4)");
                    pending =
                            worker.submit(
                                    () ->
                                            h.json(
                                                            "PATCH",
                                                            path,
                                                            map(
                                                                    "short_description",
                                                                    "Must not change"),
                                                            token)
                                                    .statusCode());
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
                    assertEquals(
                            true, ApiKeyReadOnly.reduce(db, admin, target, body).get("changed"));
                    db.connection.commit();
                }
                assertEquals(403, pending.get(30, TimeUnit.SECONDS));
            }
        } else
            assertEquals(
                    true,
                    object(parsed(h.json("POST", api, body, auth("admin"))).get("result"))
                            .get("changed"));
        var repeat = h.json("POST", api, body, auth("admin"));
        assertEquals(200, repeat.statusCode());
        assertEquals(
                map("key_id", key, "access", "read_only", "changed", false),
                object(parsed(repeat).get("result")));
        try (var db = h.store.open()) {
            var after = db.one("SELECT * FROM os_api_key WHERE key_id=?", key);
            after.put("can_write", before.get("can_write"));
            assertEquals(before, after);
            assertEquals(
                    "Before reduction",
                    object(Service.record(db, "incident", record).get("data"))
                            .get("short_description"));
            assertEquals(
                    1,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                    + " action='account.api_key_reduced'"));
        }
        new Store(h.environment);
        assertEquals(200, h.get(path, token).statusCode());
        for (String method : List.of("PATCH", "PUT"))
            assertEquals(
                    403,
                    h.json(method, path, map("short_description", "Forbidden"), token)
                            .statusCode());
        assertEquals(
                403,
                h.json(
                                "POST",
                                "/api/now/table/incident",
                                map("short_description", "Forbidden"),
                                token)
                        .statusCode());
        String replacement;
        try (var db = h.store.open()) {
            replacement = "Bearer " + ApiKeyRotation.rotate(db, admin, target, key).get("token");
            db.connection.commit();
        }
        assertEquals(200, h.get(path, replacement).statusCode());
        assertEquals(
                403,
                h.json("PATCH", path, map("short_description", "Forbidden"), replacement)
                        .statusCode());
        String csrf = h.login("admin"), formKey;
        try (var db = h.store.open()) {
            var issued =
                    ApiKeyWrites.issue(
                            db,
                            admin,
                            target,
                            map("label", "Suspended writer", "table", "incident", "days", 1));
            formKey = issued.get("key_id").toString();
            db.update("UPDATE os_api_key SET enabled=0,expires_at=0 WHERE key_id=?", formKey);
            db.connection.commit();
        }
        assertEquals(
                403,
                h.form(form, map("csrf", "bad", "key_id", formKey, "reason", "ok"), null)
                        .statusCode());
        assertEquals(
                302,
                h.form(
                                form,
                                map(
                                        "csrf",
                                        csrf,
                                        "key_id",
                                        formKey,
                                        "reason",
                                        "Remove unnecessary authority"),
                                null)
                        .statusCode());
        try (var db = h.store.open()) {
            var row = db.one("SELECT * FROM os_api_key WHERE key_id=?", formKey);
            assertEquals(0, ((Number) row.get("can_write")).intValue());
            assertEquals(0, ((Number) row.get("enabled")).intValue());
            assertEquals(0, ((Number) row.get("expires_at")).longValue());
            var events =
                    db.all(
                            "SELECT * FROM os_security_event WHERE"
                                    + " action='account.api_key_reduced'");
            assertEquals(2, events.size());
            for (var event : events) {
                assertEquals(admin, event.get("actor_id"));
                assertEquals(target, event.get("target_id"));
                assertEquals("read_only", object(event.get("detail")).get("access"));
            }
            assertFalse(JSON.writeValueAsString(events).contains(token.substring(7)));
        }
    }
}
