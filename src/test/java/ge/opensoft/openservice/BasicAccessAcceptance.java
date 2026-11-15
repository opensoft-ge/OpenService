package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class BasicAccessAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("BrowserOnly", "reader");
        h.account("PolicyAgent", "agent");
        String target;
        try (var db = h.store.open()) {
            target = RecordAccess.owner(db, "BrowserOnly");
        }
        String api = "/api/openservice/users/" + target + "/basic-auth";
        String form = "/admin/accounts/" + target + "/basic-auth";
        assertEquals(401, h.get(api, null).statusCode());
        assertEquals(map("allow_basic", true), parsed(h.get(api, auth("admin"))).get("result"));
        h.login("BrowserOnly");
        assertEquals(
                200, h.json("POST", api, map("allow_basic", false), auth("admin")).statusCode());
        assertEquals(401, h.get("/api/openservice/me", null).statusCode());
        for (String path : List.of("/api/openservice/me", "/account", "/export/incident"))
            assertEquals(401, h.get(path, auth("BrowserOnly")).statusCode(), path);
        h.login("BrowserOnly");
        assertEquals(200, h.get("/api/openservice/me", null).statusCode());
        assertEquals(200, h.get("/account", null).statusCode());
        // An explicit blocked header cannot fall back to a valid cookie.
        assertEquals(401, h.get("/api/openservice/me", auth("BrowserOnly")).statusCode());
        assertEquals(200, h.get("/api/openservice/me", null).statusCode());
        assertEquals(403, h.get(api, auth("PolicyAgent")).statusCode());
        assertEquals(
                403,
                h.json("POST", api, map("allow_basic", true), auth("PolicyAgent")).statusCode());
        assertEquals(
                409,
                h.json(
                                "POST",
                                "/api/openservice/users/" + target + "/access-mode",
                                map("api_only", true),
                                auth("admin"))
                        .statusCode());
        assertEquals(
                200, h.json("POST", api, map("allow_basic", false), auth("admin")).statusCode());
        new Store(h.environment);
        assertEquals(map("allow_basic", false), parsed(h.get(api, auth("admin"))).get("result"));
        for (Object bad : Arrays.asList(null, "true", 1, List.of()))
            assertEquals(
                    400, h.json("POST", api, map("allow_basic", bad), auth("admin")).statusCode());
        for (Object bad : List.of(map(), map("allow_basic", true, "extra", true), List.of()))
            assertEquals(400, h.json("POST", api, bad, auth("admin")).statusCode());
        assertEquals(400, h.get(api + "?extra=1", auth("admin")).statusCode());
        assertEquals(405, h.json("DELETE", api, map(), auth("admin")).statusCode());
        assertEquals(
                404,
                h.get("/api/openservice/users/" + "f".repeat(32) + "/basic-auth", auth("admin"))
                        .statusCode());
        assertEquals(
                404,
                h.json(
                                "POST",
                                "/api/openservice/users/" + "f".repeat(32) + "/basic-auth",
                                map("allow_basic", false),
                                auth("admin"))
                        .statusCode());
        String token = h.login("admin");
        assertEquals(405, h.get(form, null).statusCode());
        assertEquals(
                403,
                h.form(form, map("csrf", "invalid", "allow_basic", "true"), null).statusCode());
        assertEquals(
                400, h.form(form, map("csrf", token, "allow_basic", "invalid"), null).statusCode());
        assertEquals(
                302, h.form(form, map("csrf", token, "allow_basic", "true"), null).statusCode());
        assertEquals(200, h.get("/api/openservice/me", auth("BrowserOnly")).statusCode());
        assertEquals(
                200,
                h.json(
                                "POST",
                                "/api/openservice/users/" + target + "/access-mode",
                                map("api_only", true),
                                auth("admin"))
                        .statusCode());
        assertEquals(
                409, h.json("POST", api, map("allow_basic", false), auth("admin")).statusCode());
        assertEquals(200, h.get("/api/openservice/me", auth("BrowserOnly")).statusCode());
        try (var db = h.store.open()) {
            var events =
                    db.all(
                            "SELECT detail FROM os_security_event WHERE"
                                + " action='account.basic_access_changed' AND target_id=? ORDER BY"
                                + " sequence",
                            target);
            assertEquals(2, events.size());
            assertEquals(
                    map("before", true, "after", false), object(events.getFirst().get("detail")));
            assertEquals(
                    map("before", false, "after", true), object(events.getLast().get("detail")));
        }
    }
}
