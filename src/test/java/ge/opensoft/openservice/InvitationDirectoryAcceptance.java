package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class InvitationDirectoryAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("DirectoryReader", "reader");
        String admin, old, token;
        try (var db = h.store.open()) {
            admin = RecordAccess.owner(db, "admin");
            var first = Invitations.issue(db, admin, map("name", "Roster000", "role", "reader"));
            old = first.get("invite_id").toString();
            token = first.get("accept_path").toString();
            for (int i = 1; i < 106; i++)
                Invitations.issue(
                        db,
                        admin,
                        map(
                                "name",
                                String.format("Roster%03d", i),
                                "role",
                                i % 2 == 0 ? "reader" : "agent"));
            Invitations.issue(db, admin, map("name", "UnrelatedInvite", "role", "requester"));
            db.connection.commit();
            assertEquals(
                    403,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            InvitationDirectory.list(
                                                    db,
                                                    RecordAccess.owner(db, "DirectoryReader"),
                                                    Map.of()))
                            .status);
        }
        String root = "/api/openservice/invitation-directory";
        assertEquals(401, h.get(root, null).statusCode());
        assertEquals(403, h.get(root, auth("DirectoryReader")).statusCode());
        assertEquals(405, h.json("POST", root, map(), auth("admin")).statusCode());
        for (String query :
                List.of(
                        "?x=1",
                        "?page=0",
                        "?page=01",
                        "?page=1000000",
                        "?page=999999",
                        "?page=1&page=2",
                        "?role=owner",
                        "?q=" + "a".repeat(81),
                        "?q=%00"))
            assertEquals(400, h.get(root + query, auth("admin")).statusCode());
        var first = object(parsed(h.get(root, auth("admin"))).get("result"));
        assertEquals(107, ((Number) first.get("total")).intValue());
        assertEquals(5, ((Number) first.get("pages")).intValue());
        assertEquals(25, ((List<?>) first.get("invitations")).size());
        var ids = new HashSet<String>();
        for (int page = 1; page <= 5; page++)
            for (Object row :
                    (List<?>)
                            object(
                                            parsed(h.get(root + "?page=" + page, auth("admin")))
                                                    .get("result"))
                                    .get("invitations"))
                assertTrue(ids.add(object(row).get("invite_id").toString()));
        assertEquals(107, ids.size());
        assertTrue(ids.contains(old));
        var filtered =
                object(parsed(h.get(root + "?q=rOsTeR&role=reader", auth("admin"))).get("result"));
        assertEquals(53, ((Number) filtered.get("total")).intValue());
        var response = h.get(root + "?q=Roster000", auth("admin"));
        assertEquals(200, response.statusCode());
        var rows = (List<?>) object(parsed(response).get("result")).get("invitations");
        assertEquals(1, rows.size());
        assertEquals(old, object(rows.getFirst()).get("invite_id"));
        for (String forbidden : List.of("token_hash", "accept_path", "email", token))
            assertFalse(html(response).contains(forbidden));
        for (String q : List.of("%25", "_", "missing")) {
            var empty = object(parsed(h.get(root + "?q=" + q, auth("admin"))).get("result"));
            assertEquals(0, ((Number) empty.get("total")).intValue());
            assertEquals(1, ((Number) empty.get("pages")).intValue());
        }
        assertEquals(
                100,
                ((List<?>)
                                parsed(h.get("/api/openservice/invitations", auth("admin")))
                                        .get("result"))
                        .size());
        assertEquals(
                200,
                h.json(
                                "POST",
                                "/api/openservice/invitations/" + old + "/revoke",
                                map(),
                                auth("admin"))
                        .statusCode());
        assertEquals(400, h.get(token, null).statusCode());
        var revoked =
                (List<?>)
                        object(parsed(h.get(root + "?q=Roster000", auth("admin"))).get("result"))
                                .get("invitations");
        assertEquals("revoked", object(revoked.getFirst()).get("state"));
        assertEquals(
                200, h.get("/admin/invitation-directory?q=missing", auth("admin")).statusCode());
        assertTrue(
                html(h.get("/admin/invitation-directory?q=missing", auth("admin")))
                        .contains("No invitations match"));
        new Store(h.environment);
        assertEquals(
                revoked,
                object(parsed(h.get(root + "?q=Roster000", auth("admin"))).get("result"))
                        .get("invitations"));
    }
}
