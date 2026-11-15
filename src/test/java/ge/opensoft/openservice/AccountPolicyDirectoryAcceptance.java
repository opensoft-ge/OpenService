package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class AccountPolicyDirectoryAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("PolicyViewer", "reader");
        try (var db = h.store.open()) {
            String password =
                    db.one("SELECT password FROM os_user WHERE name='admin'")
                            .get("password")
                            .toString();
            for (int i = 0; i < 31; i++)
                db.update(
                        "INSERT INTO"
                            + " os_user(name,password,role,user_id,active,api_only,allow_basic)"
                            + " VALUES(?,?,?,?,?,?,?)",
                        String.format(Locale.ROOT, "Channel%02d", i),
                        password,
                        "reader",
                        id(),
                        i == 30 ? 0 : 1,
                        i < 28 ? 1 : 0,
                        i == 27 || i == 29 ? 1 : 0);
            db.connection.commit();
        }
        String api = "/api/openservice/users",
                query = "?q=Channel&role=reader&status=active&channel=api&basic=blocked";
        assertEquals(401, h.get(api + query, null).statusCode());
        assertEquals(403, h.get(api + query, auth("PolicyViewer")).statusCode());
        var first = parsed(h.get(api + query, auth("admin")));
        assertEquals(27, ((Number) first.get("total")).intValue());
        assertEquals(2, ((Number) first.get("pages")).intValue());
        assertEquals(25, ((List<?>) first.get("result")).size());
        for (Object row : (List<?>) first.get("result")) {
            var item = object(row);
            assertEquals(1, ((Number) item.get("api_only")).intValue());
            assertEquals(0, ((Number) item.get("allow_basic")).intValue());
            assertEquals(
                    Set.of("user_id", "name", "role", "active", "api_only", "allow_basic"),
                    item.keySet());
        }
        assertEquals(
                2,
                ((List<?>) parsed(h.get(api + query + "&page=2", auth("admin"))).get("result"))
                        .size());
        for (String filter :
                List.of(
                        "channel=api&basic=allowed",
                        "channel=browser&basic=allowed",
                        "channel=browser&basic=blocked"))
            assertEquals(
                    1,
                    ((Number)
                                    parsed(
                                                    h.get(
                                                            api
                                                                    + "?q=Channel&status=active&"
                                                                    + filter,
                                                            auth("admin")))
                                            .get("total"))
                            .intValue());
        assertEquals(
                1,
                ((Number)
                                parsed(
                                                h.get(
                                                        api
                                                                + "?q=Channel&status=inactive&channel=browser&basic=blocked",
                                                        auth("admin")))
                                        .get("total"))
                        .intValue());
        for (String suffix :
                List.of(
                        "?channel=other",
                        "?basic=false",
                        "?channel=api&channel=browser",
                        "?basic=allowed&basic=blocked"))
            for (String root : List.of(api, "/admin/accounts"))
                assertEquals(400, h.get(root + suffix, auth("admin")).statusCode());
        String page = html(h.get("/admin/accounts" + query, auth("admin")));
        assertTrue(page.contains("channel=api&amp;basic=blocked"));
        assertTrue(page.contains("API only"));
        assertTrue(page.contains("Basic blocked"));
        assertEquals(401, h.get("/api/now/table/incident", auth("Channel00")).statusCode());
        try (var db = h.store.open()) {
            db.update("UPDATE os_user SET api_only=0 WHERE name='Channel00'");
            db.connection.commit();
        }
        assertEquals(
                26, ((Number) parsed(h.get(api + query, auth("admin"))).get("total")).intValue());
        new Store(h.environment);
        assertEquals(
                26, ((Number) parsed(h.get(api + query, auth("admin"))).get("total")).intValue());
    }
}
