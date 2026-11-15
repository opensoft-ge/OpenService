package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class InvitationStateAcceptance {
    static Map<String, Object> page(WorkflowSupport h, String path) throws Exception {
        return object(parsed(h.get(path, auth("admin"))).get("result"));
    }

    static void run(WorkflowSupport h) throws Exception {
        h.account("StateIssuer", "admin");
        h.account("StateReader", "reader");
        String admin, issuer, expired, invalidated, unavailable, revoked, accepted, first;
        try (var db = h.store.open()) {
            admin = RecordAccess.owner(db, "admin");
            issuer = RecordAccess.owner(db, "StateIssuer");
            first =
                    Invitations.issue(db, admin, map("name", "Available00", "role", "reader"))
                            .get("invite_id")
                            .toString();
            for (int i = 1; i < 27; i++)
                Invitations.issue(
                        db,
                        admin,
                        map(
                                "name",
                                String.format(Locale.ROOT, "Available%02d", i),
                                "role",
                                "reader"));
            expired =
                    Invitations.issue(db, issuer, map("name", "AvailableExpired", "role", "reader"))
                            .get("invite_id")
                            .toString();
            invalidated =
                    Invitations.issue(db, issuer, map("name", "AvailableInvalid", "role", "reader"))
                            .get("invite_id")
                            .toString();
            unavailable =
                    Invitations.issue(db, admin, map("name", "AvailableConflict", "role", "reader"))
                            .get("invite_id")
                            .toString();
            revoked =
                    Invitations.issue(db, admin, map("name", "AvailableRevoked", "role", "reader"))
                            .get("invite_id")
                            .toString();
            accepted =
                    Invitations.issue(db, admin, map("name", "AvailableAccepted", "role", "reader"))
                            .get("invite_id")
                            .toString();
            db.update(
                    "UPDATE os_invitation SET expires_at=0 WHERE invite_id IN (?,?,?)",
                    expired,
                    revoked,
                    accepted);
            db.update("UPDATE os_user SET active=0 WHERE user_id=?", issuer);
            Service.createAccount(
                    db, map("name", "AvailableConflict", "password", PASSWORD, "role", "reader"));
            Invitations.revoke(db, admin, revoked);
            db.update("UPDATE os_invitation SET state='accepted' WHERE invite_id=?", accepted);
            db.connection.commit();
        }
        String api = "/api/openservice/invitation-directory?q=Available&role=reader";
        var states =
                Map.of(
                        "expired",
                        expired,
                        "invalidated",
                        invalidated,
                        "unavailable",
                        unavailable,
                        "revoked",
                        revoked,
                        "accepted",
                        accepted);
        for (var entry : states.entrySet()) {
            var result = page(h, api + "&state=" + entry.getKey());
            assertEquals(1, ((Number) result.get("total")).intValue());
            var row = object(((List<?>) result.get("invitations")).getFirst());
            assertEquals(entry.getValue(), row.get("invite_id"));
            assertEquals(entry.getKey(), row.get("state"));
            assertFalse(row.containsKey("token_hash"));
            assertFalse(row.containsKey("email"));
        }
        var pending = page(h, api + "&state=pending");
        assertEquals(27, ((Number) pending.get("total")).intValue());
        assertEquals(25, ((List<?>) pending.get("invitations")).size());
        assertEquals(2, ((Number) pending.get("pages")).intValue());
        var page2 = page(h, api + "&state=pending&page=2");
        assertEquals(2, ((List<?>) page2.get("invitations")).size());
        for (Object row : (List<?>) page2.get("invitations"))
            assertEquals("pending", object(row).get("state"));
        assertEquals(32, ((Number) page(h, api).get("total")).intValue());
        assertEquals(
                0,
                ((Number)
                                page(h, api.replace("role=reader", "role=agent") + "&state=pending")
                                        .get("total"))
                        .intValue());
        for (String suffix : List.of("&state=unknown", "&state=pending&state=expired"))
            assertEquals(400, h.get(api + suffix, auth("admin")).statusCode());
        assertEquals(401, h.get(api + "&state=pending", null).statusCode());
        assertEquals(403, h.get(api + "&state=pending", auth("StateReader")).statusCode());
        try (var db = h.store.open()) {
            assertEquals(
                    "pending",
                    db.one("SELECT state FROM os_invitation WHERE invite_id=?", expired)
                            .get("state"));
            db.update(
                    "UPDATE os_user SET active=1,auth_revision=auth_revision+1 WHERE user_id=?",
                    issuer);
            db.update("UPDATE os_invitation SET expires_at=0 WHERE invite_id=?", first);
            db.connection.commit();
        }
        assertEquals(2, ((Number) page(h, api + "&state=expired").get("total")).intValue());
        assertEquals(1, ((Number) page(h, api + "&state=invalidated").get("total")).intValue());
        try (var db = h.store.open()) {
            db.update("UPDATE os_user SET auth_revision=auth_revision-1 WHERE user_id=?", issuer);
            db.update("DELETE FROM os_user WHERE name='AvailableConflict'");
            db.connection.commit();
        }
        new Store(h.environment);
        assertEquals(28, ((Number) page(h, api + "&state=pending").get("total")).intValue());
        assertEquals(0, ((Number) page(h, api + "&state=invalidated").get("total")).intValue());
        assertEquals(0, ((Number) page(h, api + "&state=unavailable").get("total")).intValue());
    }
}
