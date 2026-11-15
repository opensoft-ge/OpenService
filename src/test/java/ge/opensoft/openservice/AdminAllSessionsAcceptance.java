package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;

final class AdminAllSessionsAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("AllSessionsTarget", "reader");
        h.account("AllSessionsOther", "agent");
        String target, other, admin;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "AllSessionsTarget");
            other = RecordAccess.owner(db, "AllSessionsOther");
            admin = RecordAccess.owner(db, "admin");
        }
        String api = "/api/openservice/users/" + target + "/sessions/revoke";
        String page = "/admin/accounts/" + target + "/sessions/revoke";
        assertEquals(401, h.json("POST", api, map("reason", "test"), null).statusCode());
        var recovery = h.json("POST", "/api/openservice/users/" + target + "/recovery",
                map("current_password", PASSWORD), auth("admin"));
        assertEquals(201, recovery.statusCode());
        String resetPath = object(parsed(recovery).get("result")).get("reset_path").toString();
        h.login("AllSessionsTarget");
        var first = h.client;
        try (var second = SessionLimitAcceptance.client();
                var unrelated = SessionLimitAcceptance.client();
                var administrator = SessionLimitAcceptance.client()) {
            h.client = second;
            h.login("AllSessionsTarget");
            h.client = unrelated;
            h.login("AllSessionsOther");
            h.client = administrator;
            String csrf = h.login("admin");
            for (String role : List.of("AllSessionsTarget", "AllSessionsOther"))
                assertEquals(
                        403, h.json("POST", api, map("reason", "test"), auth(role)).statusCode());
            assertEquals(405, h.get(api, auth("admin")).statusCode());
            assertEquals(
                    400,
                    h.json("POST", api + "?x=1", map("reason", "test"), auth("admin"))
                            .statusCode());
            assertEquals(400, h.json("POST", api, map("reason", " "), auth("admin")).statusCode());
            assertEquals(
                    400,
                    h.json("POST", api, map("reason", "test", "extra", 1), auth("admin"))
                            .statusCode());
            assertEquals(
                    404,
                    h.json("POST", api.replace(target, id()), map("reason", "test"), auth("admin"))
                            .statusCode());
            assertEquals(403, h.form(page, map("reason", "test"), auth("admin")).statusCode());
            try (var workers = Executors.newFixedThreadPool(2)) {
                var start = new CountDownLatch(1);
                Callable<Integer> work =
                        () -> {
                            start.await();
                            var response =
                                    h.json(
                                            "POST",
                                            api,
                                            map("reason", "  Support request  "),
                                            auth("admin"));
                            assertEquals(200, response.statusCode());
                            var result = object(parsed(response).get("result"));
                            assertEquals(false, result.get("current"));
                            return ((Number) result.get("removed_count")).intValue();
                        };
                var a = workers.submit(work);
                var b = workers.submit(work);
                start.countDown();
                assertEquals(
                        Set.of(0, 2),
                        Set.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
            }
            for (var client : List.of(first, second)) {
                h.client = client;
                assertEquals(401, h.get("/api/openservice/me", null).statusCode());
            }
            h.client = unrelated;
            assertEquals(200, h.get("/api/openservice/me", null).statusCode());
            h.client = administrator;
            assertEquals(200, h.get(resetPath, null).statusCode());
            try (var db = h.store.open()) {
                var events =
                        db.all(
                                "SELECT * FROM os_security_event WHERE"
                                    + " action='account.all_sessions_ended_by_admin'");
                assertEquals(1, events.size());
                var event = events.getFirst();
                assertEquals(admin, event.get("actor_id"));
                assertEquals(target, event.get("target_id"));
                assertEquals(
                        map("removed_count", 2, "reason", "Support request"),
                        object(event.get("detail")));
                assertEquals(
                        1,
                        db.count("SELECT COUNT(*) FROM os_browser_session WHERE user_id=?", other));
            }
            assertEquals(
                    302,
                    h.form(page, map("csrf", csrf, "reason", "Already ended"), null).statusCode());
            String own = "/api/openservice/users/" + admin + "/sessions/revoke";
            var ownResult = h.json("POST", own, map("reason", "End mine too"), auth("admin"));
            assertEquals(200, ownResult.statusCode());
            assertEquals(map("removed_count", 1, "current", true), parsed(ownResult).get("result"));
            assertEquals(401, h.get("/api/openservice/me", null).statusCode());
            assertEquals(200, h.get("/api/openservice/me", auth("AllSessionsTarget")).statusCode());
            h.login("AllSessionsTarget");
            assertEquals(200, h.get("/api/openservice/me", null).statusCode());
        } finally {
            h.client = first;
        }
    }
}
