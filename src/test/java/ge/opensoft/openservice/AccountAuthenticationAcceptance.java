package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class AccountAuthenticationAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("HistoryTarget", "reader");
        h.account("HistoryOther", "agent");
        String target, other;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "HistoryTarget");
            other = RecordAccess.owner(db, "HistoryOther");
        }
        String api = "/api/openservice/users/" + target + "/authentication-events";
        String page = "/admin/accounts/" + target + "/authentication-events";
        assertEquals(401, h.get(api, null).statusCode());
        assertEquals(302, h.get(page, null).statusCode());
        for (String name : List.of("HistoryTarget", "HistoryOther")) {
            assertEquals(403, h.get(api, auth(name)).statusCode());
            assertEquals(403, h.get(page, auth(name)).statusCode());
        }
        String foreign;
        try (var db = h.store.open()) {
            db.update("DELETE FROM os_auth_event");
            for (int i = 0; i < 102; i++)
                AuthenticationEvents.record(
                        db,
                        "HistoryTarget",
                        Passwords.tokenHash("HistoryTarget"),
                        i == 0 ? "browser" : "basic",
                        i == 0 ? "failed" : "succeeded");
            AuthenticationEvents.record(
                    db, "HistoryOther", Passwords.tokenHash("HistoryOther"), "browser", "limited");
            foreign =
                    db.one("SELECT event_id FROM os_auth_event WHERE user_id=?", other)
                            .get("event_id")
                            .toString();
            db.connection.commit();
        }
        var first = parsed(h.get(api, auth("admin")));
        assertEquals(Set.of("account", "result", "next_before"), first.keySet());
        assertEquals(target, object(first.get("account")).get("user_id"));
        var rows = objects(first.get("result"));
        assertEquals(100, rows.size());
        for (var row : rows)
            assertEquals(Set.of("event_id", "occurred_at", "mechanism", "outcome"), row.keySet());
        assertFalse(first.toString().contains(foreign));
        assertFalse(first.toString().contains("identity_hash"));
        String before = first.get("next_before").toString();
        assertTrue(before.matches("[0-9a-f]{32}"));
        var older = parsed(h.get(api + "?before=" + before, auth("admin")));
        assertEquals(2, objects(older.get("result")).size());
        assertEquals("", older.get("next_before"));
        var ids = new HashSet<String>();
        for (var row : rows) assertTrue(ids.add(row.get("event_id").toString()));
        for (var row : objects(older.get("result")))
            assertTrue(ids.add(row.get("event_id").toString()));
        assertTrue(
                objects(older.get("result")).stream()
                        .anyMatch(r -> r.get("outcome").equals("failed")));
        for (String path : List.of(api, page)) {
            for (String query :
                    List.of(
                            "before=bad",
                            "before=" + foreign,
                            "before=" + id(),
                            "before=" + before + "&before=" + before,
                            "download=maybe",
                            "extra=1"))
                assertEquals(400, h.get(path + "?" + query, auth("admin")).statusCode());
            assertEquals(405, h.protectedJson("POST", path, map(), auth("admin")).statusCode());
            assertEquals(404, h.get(path.replace(target, id()), auth("admin")).statusCode());
            var download = h.get(path + "?before=" + before + "&download=true", auth("admin"));
            assertEquals(200, download.statusCode());
            assertTrue(
                    download.headers()
                            .firstValue("Content-Disposition")
                            .orElseThrow()
                            .contains("account-authentication-events.json"));
            assertEquals(older, parsed(download));
        }
        assertEquals(200, h.get(page, auth("admin")).statusCode());
        assertTrue(
                html(h.get(page + "?before=" + before + "&download=false", auth("admin")))
                        .contains("HistoryTarget"));
        assertEquals(
                200,
                h.json(
                                "PATCH",
                                "/api/openservice/users/" + target + "/status",
                                map("active", false),
                                auth("admin"))
                        .statusCode());
        var inactive = parsed(h.get(api, auth("admin")));
        assertEquals(false, object(inactive.get("account")).get("active"));
        assertEquals(100, objects(inactive.get("result")).size());
        try (var db = h.store.open()) {
            db.update("DELETE FROM os_auth_event WHERE user_id=?", target);
            db.connection.commit();
        }
        assertEquals(List.of(), parsed(h.get(api, auth("admin"))).get("result"));
        assertEquals(400, h.get(api + "?before=" + before, auth("admin")).statusCode());
    }
}
