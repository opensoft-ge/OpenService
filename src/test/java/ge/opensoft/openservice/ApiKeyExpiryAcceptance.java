package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;

final class ApiKeyExpiryAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("ExpiryMachine", "agent");
        h.account("ExpiryOther", "reader");
        String target, other, admin, key, token, foreign;
        Map<String, Object> original;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "ExpiryMachine");
            other = RecordAccess.owner(db, "ExpiryOther");
            admin = RecordAccess.owner(db, "admin");
            db.update("UPDATE os_user SET api_only=1 WHERE user_id IN (?,?)", target, other);
            var issued =
                    ApiKeyWrites.issue(
                            db,
                            admin,
                            target,
                            map("label", "Worker", "table", "incident", "days", 30));
            key = issued.get("key_id").toString();
            token = issued.get("token").toString();
            foreign =
                    ApiKeys.issue(
                                    db,
                                    admin,
                                    other,
                                    map("label", "Other", "table", "incident", "days", 30))
                            .get("key_id")
                            .toString();
            db.update("UPDATE os_api_key SET enabled=0 WHERE key_id=?", key);
            db.connection.commit();
            original = db.one("SELECT * FROM os_api_key WHERE key_id=?", key);
            assertEquals(
                    403,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            ApiKeyExpiry.shorten(
                                                    db,
                                                    other,
                                                    target,
                                                    map("key_id", key, "hours", 24)))
                            .status);
            ApiKeyExpiry.shorten(db, admin, target, map("key_id", key, "hours", 24));
            db.connection.rollback();
            assertEquals(original, db.one("SELECT * FROM os_api_key WHERE key_id=?", key));
        }
        String api = "/api/openservice/users/" + target + "/api-keys/expiry",
                form = "/admin/accounts/" + target + "/api-keys/expiry";
        var body = map("key_id", key, "hours", 24);
        assertEquals(401, h.json("POST", api, body, null).statusCode());
        assertEquals(403, h.json("POST", api, body, auth("ExpiryOther")).statusCode());
        assertEquals(405, h.get(api, auth("admin")).statusCode());
        assertEquals(400, h.json("POST", api + "?x=1", body, auth("admin")).statusCode());
        assertEquals(
                404, h.json("POST", api.replace(target, id()), body, auth("admin")).statusCode());
        for (Object invalid :
                List.of(
                        map(),
                        map("key_id", key, "hours", 1, "extra", true),
                        map("key_id", 4, "hours", 1),
                        map("key_id", "bad", "hours", 1),
                        map("key_id", key, "hours", "1"),
                        map("key_id", key, "hours", 1.5),
                        map("key_id", key, "hours", 0),
                        map("key_id", key, "hours", 2161),
                        map("key_id", key, "hours", null)))
            assertEquals(400, h.json("POST", api, invalid, auth("admin")).statusCode());
        for (String id : List.of(id(), foreign))
            assertEquals(
                    404,
                    h.json("POST", api, map("key_id", id, "hours", 1), auth("admin")).statusCode());
        assertEquals(
                409,
                h.json("POST", api, map("key_id", key, "hours", 2160), auth("admin")).statusCode());
        long before = System.currentTimeMillis();
        var result = h.json("POST", api, body, auth("admin"));
        assertEquals(200, result.statusCode());
        long expiry = ((Number) object(parsed(result).get("result")).get("expires_at")).longValue();
        assertTrue(
                expiry >= before + 86400000L && expiry <= System.currentTimeMillis() + 86400000L);
        assertEquals(409, h.json("POST", api, body, auth("admin")).statusCode());
        try (var db = h.store.open()) {
            var after = db.one("SELECT * FROM os_api_key WHERE key_id=?", key);
            after.put("expires_at", original.get("expires_at"));
            assertEquals(original, after);
            assertEquals(
                    1,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                + " action='account.api_key_expiry_shortened'"));
            ApiKeyState.change(db, admin, target, key, map("enabled", true));
            db.connection.commit();
        }
        new Store(h.environment);
        assertEquals(200, h.get("/api/now/table/incident", "Bearer " + token).statusCode());
        assertEquals(
                201,
                h.json(
                                "POST",
                                "/api/now/table/incident",
                                map("short_description", "Retained write scope"),
                                "Bearer " + token)
                        .statusCode());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> shorten =
                    () -> {
                        start.await();
                        return h.json("POST", api, map("key_id", key, "hours", 1), auth("admin"))
                                .statusCode();
                    };
            var a = executor.submit(shorten);
            var b = executor.submit(shorten);
            start.countDown();
            assertEquals(
                    Set.of(200, 409),
                    Set.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
        }
        String formKey;
        try (var db = h.store.open()) {
            var row = db.one("SELECT * FROM os_api_key WHERE key_id=?", key);
            var rotated = ApiKeyRotation.rotate(db, admin, target, key);
            assertEquals(row.get("expires_at"), rotated.get("expires_at"));
            db.update("UPDATE os_api_key SET expires_at=0 WHERE key_id=?", key);
            db.connection.commit();
            formKey =
                    ApiKeys.issue(
                                    db,
                                    admin,
                                    target,
                                    map("label", "Form key", "table", "incident", "days", 90))
                            .get("key_id")
                            .toString();
            db.connection.commit();
        }
        assertEquals(
                409,
                h.json("POST", api, map("key_id", key, "hours", 1), auth("admin")).statusCode());
        String csrf = h.login("admin");
        assertEquals(
                403,
                h.form(form, map("key_id", formKey, "hours", "1", "csrf", "bad"), null)
                        .statusCode());
        for (String hours : List.of("", "0", "-1", "01", "1.5", "99999"))
            assertEquals(
                    400,
                    h.form(form, map("key_id", formKey, "hours", hours, "csrf", csrf), null)
                            .statusCode());
        assertEquals(
                400,
                h.form(form, map("key_id", formKey, "hours", "9999", "csrf", csrf), null)
                        .statusCode());
        assertEquals(
                302,
                h.form(form, map("key_id", formKey, "hours", "1", "csrf", csrf), null)
                        .statusCode());
        try (var db = h.store.open()) {
            var events =
                    db.all(
                            "SELECT * FROM os_security_event WHERE"
                                + " action='account.api_key_expiry_shortened'");
            assertEquals(3, events.size());
            for (var event : events) {
                var detail = object(event.get("detail"));
                assertEquals(admin, event.get("actor_id"));
                assertEquals(target, event.get("target_id"));
                assertTrue(
                        ((Number) detail.get("after")).longValue()
                                < ((Number) detail.get("before")).longValue());
            }
            assertFalse(JSON.writeValueAsString(events).contains(token));
        }
    }
}
