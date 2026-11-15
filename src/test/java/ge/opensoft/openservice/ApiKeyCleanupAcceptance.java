package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;

final class ApiKeyCleanupAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("CleanupMachine", "agent");
        h.account("CleanupOther", "reader");
        String target, other, admin, live, suspended, expired;
        List<Map<String, Object>> retained;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "CleanupMachine");
            other = RecordAccess.owner(db, "CleanupOther");
            admin = RecordAccess.owner(db, "admin");
            db.update("UPDATE os_user SET api_only=1 WHERE user_id IN (?,?)", target, other);
            var keys = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < 20; i++)
                keys.add(
                        ApiKeys.issue(
                                db,
                                admin,
                                target,
                                map("label", "Key " + i, "table", "incident", "days", 1)));
            live = keys.get(0).get("token").toString();
            suspended = keys.get(1).get("token").toString();
            expired = keys.get(2).get("token").toString();
            db.update(
                    "UPDATE os_api_key SET enabled=0 WHERE key_id IN (?,?)",
                    keys.get(1).get("key_id"),
                    keys.get(3).get("key_id"));
            db.update(
                    "UPDATE os_api_key SET expires_at=0 WHERE key_id IN (?,?)",
                    keys.get(2).get("key_id"),
                    keys.get(3).get("key_id"));
            ApiKeys.issue(db, admin, other, map("label", "Other", "table", "incident", "days", 1));
            db.update("UPDATE os_api_key SET expires_at=0 WHERE user_id=?", other);
            db.connection.commit();
            retained =
                    db.all(
                            "SELECT * FROM os_api_key WHERE user_id=? AND expires_at>0 ORDER BY"
                                + " key_id",
                            target);
            assertEquals(
                    403,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            ApiKeyCleanup.expired(
                                                    db, other, target, map("reason", "test")))
                            .status);
            assertEquals(
                    2,
                    ApiKeyCleanup.expired(db, admin, target, map("reason", "rollback"))
                            .get("removed_count"));
            db.connection.rollback();
            assertEquals(20, ApiKeys.list(db, admin, target).size());
            assertEquals(
                    0,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                + " action='account.expired_api_keys_removed'"));
        }
        String root = "/api/openservice/users/" + target + "/api-keys",
                api = root + "/cleanup",
                form = "/admin/accounts/" + target + "/api-keys/cleanup";
        var body = map("reason", "  Routine cleanup  ");
        var issuance = map("label", "New", "table", "incident", "days", 1);
        assertEquals(409, h.json("POST", root, issuance, auth("admin")).statusCode());
        assertEquals(401, h.json("POST", api, body, null).statusCode());
        assertEquals(403, h.json("POST", api, body, auth("CleanupOther")).statusCode());
        assertEquals(405, h.get(api, auth("admin")).statusCode());
        assertEquals(400, h.json("POST", api + "?extra=1", body, auth("admin")).statusCode());
        assertEquals(
                404, h.json("POST", api.replace(target, id()), body, auth("admin")).statusCode());
        for (Object invalid :
                List.of(
                        map(),
                        map("reason", " "),
                        map("reason", "x", "extra", true),
                        map("reason", 5),
                        map("reason", "x".repeat(501))))
            assertEquals(400, h.json("POST", api, invalid, auth("admin")).statusCode());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> cleanup =
                    () -> {
                        start.await();
                        var response = h.json("POST", api, body, auth("admin"));
                        assertEquals(200, response.statusCode());
                        return ((Number)
                                        object(parsed(response).get("result")).get("removed_count"))
                                .intValue();
                    };
            var a = executor.submit(cleanup);
            var b = executor.submit(cleanup);
            start.countDown();
            assertEquals(
                    Set.of(0, 2), Set.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
        }
        try (var db = h.store.open()) {
            assertEquals(
                    retained,
                    db.all("SELECT * FROM os_api_key WHERE user_id=? ORDER BY key_id", target));
            assertEquals(1, ApiKeys.list(db, admin, other).size());
            var events =
                    db.all(
                            "SELECT * FROM os_security_event WHERE"
                                + " action='account.expired_api_keys_removed'");
            assertEquals(1, events.size());
            var event = events.getFirst();
            var detail = object(event.get("detail"));
            assertEquals(admin, event.get("actor_id"));
            assertEquals(target, event.get("target_id"));
            assertEquals(2, detail.get("removed_count"));
            assertEquals("Routine cleanup", detail.get("reason"));
            assertTrue(((Number) detail.get("expired_at_or_before")).longValue() > 0);
            assertFalse(JSON.writeValueAsString(events).contains(live));
            assertFalse(JSON.writeValueAsString(events).contains("token_hash"));
        }
        assertEquals(200, h.get("/api/now/table/incident", "Bearer " + live).statusCode());
        assertEquals(401, h.get("/api/now/table/incident", "Bearer " + suspended).statusCode());
        assertEquals(401, h.get("/api/now/table/incident", "Bearer " + expired).statusCode());
        var newKey = h.json("POST", root, issuance, auth("admin"));
        assertEquals(201, newKey.statusCode());
        String newId = object(parsed(newKey).get("result")).get("key_id").toString();
        new Store(h.environment);
        String csrf = h.login("admin");
        assertEquals(403, h.form(form, map("reason", "test", "csrf", "bad"), null).statusCode());
        assertEquals(400, h.form(form, map("reason", " ", "csrf", csrf), null).statusCode());
        try (var db = h.store.open()) {
            assertEquals(19, ApiKeys.list(db, admin, target).size());
            db.update("UPDATE os_api_key SET expires_at=0 WHERE key_id=?", newId);
            db.connection.commit();
        }
        assertEquals(
                302, h.form(form, map("reason", "Form cleanup", "csrf", csrf), null).statusCode());
        assertEquals(
                302,
                h.form(form, map("reason", "Repeat cleanup", "csrf", csrf), null).statusCode());
        try (var db = h.store.open()) {
            assertEquals(18, ApiKeys.list(db, admin, target).size());
            assertEquals(
                    2,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                + " action='account.expired_api_keys_removed'"));
        }
    }
}
