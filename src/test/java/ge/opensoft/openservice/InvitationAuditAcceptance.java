package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;

final class InvitationAuditAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("AuditIssuer", "admin");
        h.account("AuditReviewer", "admin");
        h.account("AuditObserver", "reader");
        String root = "/api/openservice/invitations", issuer, reviewer;
        try (var db = h.store.open()) {
            issuer = RecordAccess.owner(db, "AuditIssuer");
            reviewer = RecordAccess.owner(db, "AuditReviewer");
        }
        var payload = map("name", "InviteAuditTarget", "role", "reader");
        var first =
                object(parsed(h.json("POST", root, payload, auth("AuditIssuer"))).get("result"));
        var second =
                object(parsed(h.json("POST", root, payload, auth("AuditReviewer"))).get("result"));
        assertEquals(400, h.get(first.get("accept_path").toString(), null).statusCode());
        String revoke = root + "/" + second.get("invite_id") + "/revoke";
        assertEquals(401, h.json("POST", revoke, map(), null).statusCode());
        assertEquals(403, h.json("POST", revoke, map(), auth("AuditObserver")).statusCode());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> call =
                    () -> {
                        start.await();
                        return h.json("POST", revoke, map(), auth("AuditIssuer")).statusCode();
                    };
            var a = workers.submit(call);
            var b = workers.submit(call);
            start.countDown();
            assertEquals(200, a.get(30, TimeUnit.SECONDS));
            assertEquals(200, b.get(30, TimeUnit.SECONDS));
        }
        List<Map<String, Object>> events;
        try (var db = h.store.open()) {
            events =
                    db.all(
                            "SELECT actor_id,target_id,action,detail FROM os_security_event WHERE"
                                    + " action LIKE 'invitation.%' ORDER BY sequence");
            assertEquals(4, events.size());
            assertEquals(
                    List.of(
                            "invitation.issued",
                            "invitation.revoked",
                            "invitation.issued",
                            "invitation.revoked"),
                    events.stream().map(e -> e.get("action")).toList());
            assertEquals(issuer, events.get(0).get("target_id"));
            assertEquals(reviewer, events.get(1).get("actor_id"));
            assertEquals(issuer, events.get(1).get("target_id"));
            assertEquals(issuer, events.get(3).get("actor_id"));
            assertEquals(reviewer, events.get(3).get("target_id"));
            assertEquals(
                    map(
                            "invite_id",
                            first.get("invite_id"),
                            "invited_username",
                            "InviteAuditTarget",
                            "invited_role",
                            "reader"),
                    events.get(1).get("detail"));
            Invitations.issue(db, issuer, map("name", "RolledBackInvite", "role", "agent"));
            db.connection.rollback();
            assertEquals(
                    4,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE action LIKE"
                                    + " 'invitation.%'"));
            assertEquals(
                    0,
                    db.count("SELECT COUNT(*) FROM os_invitation WHERE name='RolledBackInvite'"));
        }
        assertEquals(
                404,
                h.json("POST", root + "/" + id() + "/revoke", map(), auth("AuditIssuer"))
                        .statusCode());
        var accepted =
                object(
                        parsed(
                                        h.json(
                                                "POST",
                                                root,
                                                map(
                                                        "name",
                                                        "AcceptedAuditTarget",
                                                        "role",
                                                        "reader"),
                                                auth("AuditIssuer")))
                                .get("result"));
        try (var db = h.store.open()) {
            Invitations.accept(db, accepted.get("accept_path").toString().substring(6), PASSWORD);
            db.connection.commit();
        }
        assertEquals(
                409,
                h.json(
                                "POST",
                                root + "/" + accepted.get("invite_id") + "/revoke",
                                map(),
                                auth("AuditIssuer"))
                        .statusCode());
        String journal = "/api/openservice/security-events";
        assertEquals(403, h.get(journal, auth("AuditObserver")).statusCode());
        for (String endpoint :
                List.of(journal, journal + "?download=true", "/admin/security-events")) {
            var result = h.get(endpoint, auth("admin"));
            assertEquals(200, result.statusCode());
            String text = html(result);
            assertTrue(text.contains("invitation.revoked"));
            assertTrue(text.contains("InviteAuditTarget"));
            for (String secret :
                    List.of(
                            "token_hash",
                            first.get("accept_path").toString(),
                            second.get("accept_path").toString(),
                            PASSWORD)) assertFalse(text.contains(secret));
        }
        new Store(h.environment);
        try (var db = h.store.open()) {
            assertEquals(
                    5,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE action LIKE"
                                    + " 'invitation.%'"));
        }
        h.account("RetiredIssuer", "admin");
        var retired =
                object(
                        parsed(
                                        h.json(
                                                "POST",
                                                root,
                                                map("name", "RetiredInvite", "role", "reader"),
                                                auth("RetiredIssuer")))
                                .get("result"));
        String retiredId;
        try (var db = h.store.open()) {
            retiredId = RecordAccess.owner(db, "RetiredIssuer");
            db.update("DELETE FROM os_user WHERE user_id=?", retiredId);
            db.connection.commit();
        }
        assertEquals(
                200,
                h.json(
                                "POST",
                                root + "/" + retired.get("invite_id") + "/revoke",
                                map(),
                                auth("admin"))
                        .statusCode());
        try (var db = h.store.open()) {
            var last =
                    db.one(
                            "SELECT target_id,target_name,detail FROM os_security_event WHERE"
                                + " action='invitation.revoked' ORDER BY sequence DESC LIMIT 1");
            assertEquals(retiredId, last.get("target_id"));
            assertEquals("Removed account", last.get("target_name"));
            assertEquals(retired.get("invite_id"), object(last.get("detail")).get("invite_id"));
        }
    }
}
