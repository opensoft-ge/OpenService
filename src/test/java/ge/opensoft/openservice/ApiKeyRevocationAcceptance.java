package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;

final class ApiKeyRevocationAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("RevokeMachine", "agent");
        h.account("RevokeOther", "reader");
        String target, other, admin, otherToken;
        var tokens = new ArrayList<String>();
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "RevokeMachine");
            other = RecordAccess.owner(db, "RevokeOther");
            admin = RecordAccess.owner(db, "admin");
            db.update("UPDATE os_user SET api_only=1 WHERE user_id IN (?,?)", target, other);
            for (int i = 0; i < 3; i++) {
                var key =
                        ApiKeys.issue(
                                db,
                                admin,
                                target,
                                map("label", "Key " + i, "table", "incident", "days", 1));
                tokens.add(key.get("token").toString());
                if (i == 1)
                    db.update("UPDATE os_api_key SET enabled=0 WHERE key_id=?", key.get("key_id"));
                if (i == 2)
                    db.update(
                            "UPDATE os_api_key SET expires_at=0 WHERE key_id=?", key.get("key_id"));
            }
            otherToken =
                    ApiKeys.issue(
                                    db,
                                    admin,
                                    other,
                                    map("label", "Other", "table", "incident", "days", 1))
                            .get("token")
                            .toString();
            db.connection.commit();
            assertEquals(
                    403,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            ApiKeyRevocation.all(
                                                    db, other, target, map("reason", "test")))
                            .status);
            ApiKeyRevocation.all(db, admin, target, map("reason", "rollback"));
            db.connection.rollback();
            assertEquals(3, ApiKeys.list(db, admin, target).size());
        }
        String api = "/api/openservice/users/" + target + "/api-keys/revoke",
                form = "/admin/accounts/" + target + "/api-keys/revoke";
        var body = map("reason", "  Integration retired  ");
        assertEquals(401, h.json("POST", api, body, null).statusCode());
        assertEquals(403, h.json("POST", api, body, auth("RevokeOther")).statusCode());
        assertEquals(405, h.get(api, auth("admin")).statusCode());
        assertEquals(400, h.json("POST", api + "?extra=1", body, auth("admin")).statusCode());
        assertEquals(
                404, h.json("POST", api.replace(target, id()), body, auth("admin")).statusCode());
        for (Object invalid :
                List.of(
                        map(),
                        map("reason", " "),
                        map("reason", "x", "extra", true),
                        map("reason", "x".repeat(501)),
                        map("reason", 4)))
            assertEquals(400, h.json("POST", api, invalid, auth("admin")).statusCode());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> revoke =
                    () -> {
                        start.await();
                        var response = h.json("POST", api, body, auth("admin"));
                        assertEquals(200, response.statusCode());
                        return ((Number)
                                        object(parsed(response).get("result")).get("removed_count"))
                                .intValue();
                    };
            var a = executor.submit(revoke);
            var b = executor.submit(revoke);
            start.countDown();
            assertEquals(
                    Set.of(0, 3), Set.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
        }
        for (String token : tokens)
            assertEquals(401, h.get("/api/now/table/incident", "Bearer " + token).statusCode());
        assertEquals(200, h.get("/api/now/table/incident", "Bearer " + otherToken).statusCode());
        assertEquals(200, h.get("/api/now/table/incident", auth("RevokeMachine")).statusCode());
        new Store(h.environment);
        String csrf = h.login("admin");
        assertEquals(403, h.form(form, map("reason", "test", "csrf", "bad"), null).statusCode());
        assertEquals(400, h.form(form, map("reason", " ", "csrf", csrf), null).statusCode());
        assertEquals(302, h.form(form, map("reason", "test", "csrf", csrf), null).statusCode());
        try (var db = h.store.open()) {
            var events =
                    db.all(
                            "SELECT * FROM os_security_event WHERE"
                                + " action='account.all_api_keys_revoked'");
            assertEquals(1, events.size());
            assertEquals(admin, events.getFirst().get("actor_id"));
            assertEquals(target, events.getFirst().get("target_id"));
            assertEquals(
                    map("removed_count", 3, "reason", "Integration retired"),
                    object(events.getFirst().get("detail")));
            assertEquals(0, ApiKeys.list(db, admin, target).size());
            assertTrue(Service.account(db, target).get("active").equals(true));
            ApiKeys.issue(db, admin, target, map("label", "New", "table", "incident", "days", 1));
            db.connection.commit();
        }
        assertEquals(
                302,
                h.form(form, map("reason", "Form revocation", "csrf", csrf), null).statusCode());
        try (var db = h.store.open()) {
            assertEquals(
                    2,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                + " action='account.all_api_keys_revoked'"));
            assertEquals(0, ApiKeys.list(db, admin, target).size());
        }
    }
}
