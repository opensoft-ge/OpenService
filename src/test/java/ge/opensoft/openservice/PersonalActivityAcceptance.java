package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class PersonalActivityAcceptance {
    static final String API = "/api/openservice/me/authentication-events",
            PAGE = "/account/authentication-events";

    static void run(WorkflowSupport h) throws Exception {
        assertEquals(401, h.get(API, null).statusCode());
        assertEquals(302, h.get(PAGE, null).statusCode());
        h.account("ActivityOwner", "reader");
        h.account("ActivityOther", "agent");
        String csrf = h.login("ActivityOwner");
        assertEquals(200, h.get(API, null).statusCode());
        String firstId, foreignId;
        try (var db = h.store.open()) {
            db.update("DELETE FROM os_auth_event");
            for (int i = 0; i < 101; i++) {
                AuthenticationEvents.record(
                        db,
                        "ActivityOwner",
                        Passwords.tokenHash("ActivityOwner"),
                        "browser",
                        i % 2 == 0 ? "succeeded" : "failed");
                AuthenticationEvents.record(
                        db,
                        "ActivityOther",
                        Passwords.tokenHash("ActivityOther"),
                        "basic",
                        "limited");
                AuthenticationEvents.record(
                        db,
                        "MissingIdentity",
                        Passwords.tokenHash("MissingIdentity"),
                        "browser",
                        "failed");
            }
            firstId =
                    db.one(
                                    "SELECT event_id FROM os_auth_event WHERE"
                                        + " user_name='ActivityOwner' ORDER BY sequence LIMIT 1")
                            .get("event_id")
                            .toString();
            foreignId =
                    db.one(
                                    "SELECT event_id FROM os_auth_event WHERE"
                                        + " user_name='ActivityOther' ORDER BY sequence DESC LIMIT"
                                        + " 1")
                            .get("event_id")
                            .toString();
            db.connection.commit();
        }
        var first = parsed(h.get(API, null));
        var rows = objects(first.get("result"));
        assertEquals(100, rows.size());
        assertEquals(
                Set.of("event_id", "occurred_at", "mechanism", "outcome"),
                rows.getFirst().keySet());
        assertTrue(rows.stream().allMatch(r -> r.get("mechanism").equals("browser")));
        String cursor = first.get("next_before").toString();
        assertEquals(rows.getLast().get("event_id"), cursor);
        assertFalse(html(h.get(API, null)).contains("identity_hash"));
        assertFalse(html(h.get(PAGE, null)).contains("ActivityOther"));
        for (String query :
                List.of(
                        "user_id=other",
                        "name=ActivityOther",
                        "before=",
                        "before=1",
                        "before=" + "g".repeat(32),
                        "before=" + "0".repeat(32),
                        "before=" + foreignId,
                        "download=1",
                        "before=" + cursor + "&before=" + cursor)) {
            assertEquals(400, h.get(API + "?" + query, null).statusCode(), query);
            assertEquals(400, h.get(PAGE + "?" + query, null).statusCode(), query);
        }
        try (var db = h.store.open()) {
            AuthenticationEvents.record(
                    db,
                    "ActivityOwner",
                    Passwords.tokenHash("ActivityOwner"),
                    "password-change",
                    "succeeded");
            db.connection.commit();
        }
        var older = parsed(h.get(API + "?before=" + cursor, null));
        assertEquals(1, objects(older.get("result")).size());
        assertEquals(firstId, objects(older.get("result")).getFirst().get("event_id"));
        assertEquals("", older.get("next_before"));
        for (String path : List.of(API, PAGE)) {
            var download = h.get(path + "?download=true&before=" + cursor, null);
            assertEquals(200, download.statusCode());
            assertEquals(older, parsed(download));
            assertTrue(
                    download.headers()
                            .firstValue("Content-Disposition")
                            .orElseThrow()
                            .contains("my-authentication-events.json"));
            assertEquals("no-store", download.headers().firstValue("Cache-Control").orElseThrow());
        }
        assertEquals(405, h.json("POST", API, map(), auth("admin")).statusCode());
        assertEquals(405, h.form(PAGE, map("csrf", csrf), auth("admin")).statusCode());
        new Store(h.environment);
        assertEquals(older, parsed(h.get(API + "?before=" + cursor, null)));
        try (var db = h.store.open()) {
            db.update("DELETE FROM os_auth_event WHERE event_id=?", cursor);
            db.connection.commit();
        }
        assertEquals(400, h.get(API + "?before=" + cursor, null).statusCode());
        for (String role : List.of("admin", "agent", "reader", "requester")) {
            String name = "Personal" + role;
            h.account(name, role);
            var response = h.get(API, auth(name));
            assertEquals(200, response.statusCode());
            var own = objects(parsed(response).get("result"));
            assertFalse(own.isEmpty());
            try (var db = h.store.open()) {
                for (var event : own)
                    assertEquals(
                            name,
                            db.one(
                                            "SELECT user_name FROM os_auth_event WHERE event_id=?",
                                            event.get("event_id"))
                                    .get("user_name"));
            }
        }
        try (var db = h.store.open()) {
            db.update("DELETE FROM os_auth_event WHERE user_name='ActivityOwner'");
            db.connection.commit();
        }
        assertEquals(List.of(), parsed(h.get(API + "?download=false", null)).get("result"));
        assertTrue(html(h.get(PAGE, null)).contains("No retained verification events"));
        // A new identity with the same username cannot inherit the old account's history.
        try (var db = h.store.open()) {
            AuthenticationEvents.record(
                    db, "ActivityOwner", Passwords.tokenHash("ActivityOwner"), "browser", "failed");
            db.update("UPDATE os_user SET user_id=? WHERE name='ActivityOwner'", id());
            db.connection.commit();
        }
        assertEquals(401, h.get(API, null).statusCode());
        assertEquals(1, objects(parsed(h.get(API, auth("ActivityOwner"))).get("result")).size());
    }
}
