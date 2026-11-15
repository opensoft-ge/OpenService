package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;

final class AdminSessionRevocationAcceptance {
    static String api(String user, String session) {
        return "/api/openservice/users/" + user + "/sessions/" + session + "/revoke";
    }

    static void run(WorkflowSupport h) throws Exception {
        h.account("AdminEndedReader", "reader");
        h.account("AdminUnrelated", "agent");
        String target, other, admin;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "AdminEndedReader");
            other = RecordAccess.owner(db, "AdminUnrelated");
            admin = RecordAccess.owner(db, "admin");
        }
        h.login("AdminEndedReader");
        String selected = SessionRevocationAcceptance.current(h);
        var first = h.client;
        try (var second = SessionLimitAcceptance.client()) {
            h.client = second;
            h.login("AdminEndedReader");
            String remaining = SessionRevocationAcceptance.current(h);
            String api = api(target, selected);
            assertEquals(
                    403, h.json("POST", api, map("reason", "Help request"), null).statusCode());
            assertEquals(
                    403,
                    h.json("POST", api, map("reason", "Help request"), auth("AdminUnrelated"))
                            .statusCode());
            assertEquals(
                    404,
                    h.json(
                                    "POST",
                                    api(other, selected),
                                    map("reason", "Help request"),
                                    auth("admin"))
                            .statusCode());
            assertEquals(
                    404,
                    h.json(
                                    "POST",
                                    api("f".repeat(32), selected),
                                    map("reason", "Help request"),
                                    auth("admin"))
                            .statusCode());
            for (Object bad : Arrays.asList(null, 1, "", " ", "x".repeat(501), "bad\0reason"))
                assertEquals(
                        400, h.json("POST", api, map("reason", bad), auth("admin")).statusCode());
            for (Object bad : List.of(map(), map("reason", "ok", "extra", 1), List.of()))
                assertEquals(400, h.json("POST", api, bad, auth("admin")).statusCode());
            assertEquals(
                    400,
                    h.json("POST", api + "?extra=1", map("reason", "ok"), auth("admin"))
                            .statusCode());
            assertEquals(405, h.get(api, auth("admin")).statusCode());
            assertEquals(403, h.form("/admin/accounts/"+target+"/sessions/"+selected+"/revoke",
                    map("reason", "Cross-site form"), auth("admin")).statusCode());
            try (var workers = Executors.newFixedThreadPool(2)) {
                var start = new CountDownLatch(1);
                var a =
                        workers.submit(
                                () -> {
                                    start.await();
                                    return h.json(
                                            "POST",
                                            api,
                                            map("reason", "  Help request  "),
                                            auth("admin"));
                                });
                var b =
                        workers.submit(
                                () -> {
                                    start.await();
                                    return h.json(
                                            "POST",
                                            api,
                                            map("reason", "  Help request  "),
                                            auth("admin"));
                                });
                start.countDown();
                var ra = a.get(15, TimeUnit.SECONDS);
                var rb = b.get(15, TimeUnit.SECONDS);
                assertEquals(Set.of(200, 404), Set.of(ra.statusCode(), rb.statusCode()));
                assertEquals(
                        map("revoked", true, "current", false),
                        parsed(ra.statusCode() == 200 ? ra : rb).get("result"));
            }
            assertEquals(200, h.get("/account", null).statusCode());
            h.client = first;
            assertEquals(401, h.get("/api/openservice/me", null).statusCode());
            h.client = second;
            assertEquals(200, h.get("/api/openservice/me", auth("AdminEndedReader")).statusCode());
            try (var db = h.store.open()) {
                var events =
                        db.all(
                                "SELECT * FROM os_security_event WHERE"
                                    + " action='account.session_ended_by_admin'");
                assertEquals(1, events.size());
                var event = events.getFirst();
                assertEquals(admin, event.get("actor_id"));
                assertEquals(target, event.get("target_id"));
                assertEquals(
                        map("session_id", selected, "reason", "Help request"),
                        object(event.get("detail")));
            }
            assertEquals(
                    200,
                    h.json(
                                    "POST",
                                    api(target, remaining),
                                    map("reason", "🙂".repeat(500)),
                                    auth("admin"))
                            .statusCode());
            assertEquals(401, h.get("/api/openservice/me", null).statusCode());
            String csrf = h.login("admin"), own = SessionRevocationAcceptance.current(h);
            String form = "/admin/accounts/" + admin + "/sessions/" + own + "/revoke";
            assertEquals(
                    403, h.form(form, map("csrf", "wrong", "reason", "test"), null).statusCode());
            assertEquals(400, h.form(form, map("csrf", csrf), null).statusCode());
            assertEquals(
                    302,
                    h.form(form, map("csrf", csrf, "reason", "End this browser"), null)
                            .statusCode());
            assertEquals(401, h.get("/api/openservice/me", null).statusCode());
        } finally {
            h.client = first;
        }
    }
}
