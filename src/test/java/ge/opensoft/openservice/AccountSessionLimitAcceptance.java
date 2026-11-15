package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class AccountSessionLimitAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("PersonalLimit", "reader");
        h.account("OtherLimit", "agent");
        String target;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "PersonalLimit");
        }
        String api = "/api/openservice/users/" + target + "/session-limit";
        String form = "/admin/accounts/" + target + "/session-limit";
        assertEquals(401, h.get(api, null).statusCode());
        assertEquals(403, h.get(api, auth("PersonalLimit")).statusCode());
        assertEquals(403, h.json("POST", api, map("maximum", 1), auth("OtherLimit")).statusCode());
        assertEquals(
                map("maximum", 0, "effective_maximum", 10),
                parsed(h.get(api, auth("admin"))).get("result"));
        assertEquals(400, h.get(api + "?x=1", auth("admin")).statusCode());
        assertEquals(405, h.json("PUT", api, map(), auth("admin")).statusCode());
        assertEquals(404, h.get(api.replace(target, id()), auth("admin")).statusCode());
        assertEquals(400, h.json("POST", api, map("maximum", 1.5), auth("admin")).statusCode());
        assertEquals(403, h.form(form, map("maximum", "1"), auth("admin")).statusCode());
        assertEquals(400, h.protectedForm(form, map("maximum", "01"), auth("admin")).statusCode());
        assertEquals(405, h.get(form, auth("admin")).statusCode());
        h.login("PersonalLimit");
        var first = h.client;
        try (var second = SessionLimitAcceptance.client();
                var third = SessionLimitAcceptance.client();
                var unrelated = SessionLimitAcceptance.client()) {
            h.client = second;
            h.login("PersonalLimit");
            assertEquals(200, h.json("POST", api, map("maximum", 1), auth("admin")).statusCode());
            // Policy changes preserve existing sessions until a subsequent sign-in.
            assertEquals(200, h.get("/api/openservice/me", null).statusCode());
            assertTrue(
                    html(h.get("/account/sessions", null))
                            .contains("Browser session limit: 1."));
            h.client = first;
            assertEquals(200, h.get("/api/openservice/me", null).statusCode());
            h.client = unrelated;
            h.login("OtherLimit");
            h.client = third;
            h.login("PersonalLimit");
            assertEquals(
                    1,
                    objects(parsed(h.get("/api/openservice/me/sessions", null)).get("result"))
                            .size());
            for (var client : List.of(first, second)) {
                h.client = client;
                assertEquals(401, h.get("/api/openservice/me", null).statusCode());
            }
            h.client = unrelated;
            assertEquals(200, h.get("/api/openservice/me", null).statusCode());
            h.client = third;
            assertEquals(200, h.get("/api/openservice/me", auth("PersonalLimit")).statusCode());
            assertEquals(
                    302, h.protectedForm(form, map("maximum", "0"), auth("admin")).statusCode());
            h.client = first;
            h.login("PersonalLimit");
            h.client = third;
            assertEquals(200, h.get("/api/openservice/me", null).statusCode());
            assertEquals(
                    map("maximum", 0, "effective_maximum", 10),
                    parsed(h.get(api, auth("admin"))).get("result"));
            try (var db = h.store.open()) {
                var events =
                        db.all(
                                "SELECT * FROM os_security_event WHERE"
                                    + " action='account.session_limit_changed'");
                assertEquals(2, events.size());
                assertTrue(events.stream().allMatch(e -> e.get("target_id").equals(target)));
                assertEquals(
                        1,
                        db.count(
                                "SELECT COUNT(*) FROM os_security_event WHERE"
                                    + " action='account.sessions_limited'"));
            }
        } finally {
            h.client = first;
        }
    }
}
