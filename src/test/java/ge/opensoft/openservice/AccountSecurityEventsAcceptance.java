package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class AccountSecurityEventsAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("SecuritySubject", "reader");
        h.account("SecurityOther", "reader");
        String target, other, admin, foreign;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "SecuritySubject");
            other = RecordAccess.owner(db, "SecurityOther");
            admin = RecordAccess.owner(db, "admin");
            for (int i = 0; i < 110; i++) {
                SecurityEvents.record(db, admin, target, "account.test_change", map("ordinal", i));
                if (i % 20 == 0)
                    SecurityEvents.record(db, admin, other, "account.other_change", map());
            }
            foreign =
                    db.one(
                                    "SELECT event_id FROM os_security_event WHERE target_id=? ORDER"
                                            + " BY sequence",
                                    other)
                            .get("event_id")
                            .toString();
            db.connection.commit();
            assertEquals(
                    403,
                    assertThrows(
                                    Failure.class,
                                    () -> AccountSecurityEvents.page(db, other, target, Map.of()))
                            .status);
        }
        assertEquals(
                200,
                h.json(
                                "PATCH",
                                "/api/openservice/users/" + target + "/role",
                                map("role", "agent"),
                                auth("admin"))
                        .statusCode());
        String api = "/api/openservice/users/" + target + "/security-events",
                ui = "/admin/accounts/" + target + "/security-events";
        assertEquals(401, h.get(api, null).statusCode());
        assertEquals(403, h.get(api, auth("SecurityOther")).statusCode());
        assertEquals(405, h.json("POST", api, map(), auth("admin")).statusCode());
        assertEquals(404, h.get(api.replace(target, id()), auth("admin")).statusCode());
        for (String q :
                List.of(
                        "?x=1",
                        "?download=maybe",
                        "?before=bad",
                        "?before=" + foreign,
                        "?before=" + id(),
                        "?download=true&download=false"))
            assertEquals(400, h.get(api + q, auth("admin")).statusCode());
        var first = parsed(h.get(api, auth("admin")));
        var rows = (List<?>) first.get("result");
        assertEquals(100, rows.size());
        assertEquals("account.role_changed", object(rows.getFirst()).get("action"));
        for (Object row : rows) assertEquals(target, object(row).get("target_id"));
        String cursor = first.get("next_before").toString();
        assertFalse(cursor.isEmpty());
        var second = parsed(h.get(api + "?before=" + cursor, auth("admin")));
        assertEquals(12, ((List<?>) second.get("result")).size());
        assertEquals(
                "account.created",
                object(((List<?>) second.get("result")).getLast()).get("action"));
        assertEquals("", second.get("next_before"));
        var downloaded = h.get(api + "?before=" + cursor + "&download=true", auth("admin"));
        assertEquals(second, parsed(downloaded));
        assertTrue(
                downloaded
                        .headers()
                        .firstValue("Content-Disposition")
                        .orElseThrow()
                        .contains("account-security-events.json"));
        assertEquals(
                second, parsed(h.get(ui + "?before=" + cursor + "&download=true", auth("admin"))));
        assertEquals(200, h.get(ui, auth("admin")).statusCode());
        assertEquals(200, h.get(ui + "?before=" + cursor, auth("admin")).statusCode());
        var ids = new HashSet<Object>();
        for (Object row : rows) assertTrue(ids.add(object(row).get("event_id")));
        for (Object row : (List<?>) second.get("result"))
            assertTrue(ids.add(object(row).get("event_id")));
        assertEquals(112, ids.size());
        assertEquals(
                200,
                h.json(
                                "PATCH",
                                "/api/openservice/users/" + target + "/status",
                                map("active", false),
                                auth("admin"))
                        .statusCode());
        var after = parsed(h.get(api + "?before=" + cursor, auth("admin")));
        assertEquals(second.get("result"), after.get("result"));
        assertEquals(false, object(after.get("account")).get("active"));
        new Store(h.environment);
        assertEquals(after, parsed(h.get(api + "?before=" + cursor, auth("admin"))));
        try (var db = h.store.open()) {
            db.update("DELETE FROM os_security_event WHERE event_id=?", cursor);
            db.connection.commit();
        }
        assertEquals(400, h.get(api + "?before=" + cursor, auth("admin")).statusCode());
        assertTrue(
                html(h.get(ui.replace(target, other) + "?before=" + foreign, auth("admin")))
                        .contains("No retained security changes"));
    }
}
