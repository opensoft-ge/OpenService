package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.time.Instant;
import java.util.*;

final class EmailOnboarding {
    private EmailOnboarding() {}

    static String email(Object value) {
        if (!(value instanceof String text)
                || text.length() > 254
                || !text.matches(
                        "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}"))
            throw new Failure(400, "Provide a valid email address");
        return text.toLowerCase(Locale.ROOT);
    }

    static Map<String, Object> invite(Store store, Store.Db db, String actor, Object body)
            throws Exception {
        if (!store.environment.getProperty("DELIVERY_ENABLED", Boolean.class, false))
            throw new Failure(503, "Email delivery is not configured");
        SmtpDelivery.base(store.environment);
        var values = object(body);
        if (!values.keySet().equals(Set.of("name", "role", "email")))
            throw new Failure(400, "Provide username, role and recipient email");
        String email = email(values.get("email"));
        Service.requireAccountAdministrator(db, actor);
        if (db.one("SELECT user_id FROM os_user WHERE email=?", email) != null)
            throw new Failure(409, "Email address already belongs to an account");
        if (db.count(
                        "SELECT COUNT(*) FROM os_outbox WHERE kind='email.invitation' AND"
                                + " created_at>?",
                        System.currentTimeMillis() - 60_000)
                >= 20) throw new Failure(429, "Invitation delivery limit reached; try later");
        var invite =
                Invitations.issue(
                        db, actor, map("name", values.get("name"), "role", values.get("role")));
        db.update(
                "UPDATE os_invitation SET email=? WHERE invite_id=?",
                email,
                invite.get("invite_id"));
        String job =
                Outbox.enqueue(
                        db,
                        store.secret,
                        "email.invitation",
                        invite.get("invite_id").toString(),
                        map("email", email, "path", invite.get("accept_path")),
                        Instant.parse(invite.get("expires_at").toString()).toEpochMilli());
        SecurityEvents.record(
                db,
                actor,
                actor,
                "account.invitation_queued",
                map("invite_id", invite.get("invite_id"), "job_id", job));
        invite.remove("accept_path");
        invite.put("delivery_id", job);
        return invite;
    }

    static void forgot(Store store, Store.Db db, String supplied, String remote) throws Exception {
        // Identical HTTP response for unknown, disabled, unverified and throttled identities.
        if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439,5)");
        long now = System.currentTimeMillis();
        db.update("DELETE FROM os_recovery_budget WHERE requested_at<?", now - 3_600_000);
        String identity = Passwords.tokenHash(supplied.toLowerCase(Locale.ROOT));
        String address = Passwords.tokenHash("remote:" + remote);
        if (db.count(
                                "SELECT COUNT(*) FROM os_recovery_budget WHERE identity_hash=? AND"
                                        + " requested_at>?",
                                identity,
                                now - 60_000)
                        > 0
                || db.count("SELECT COUNT(*) FROM os_recovery_budget WHERE remote_hash=?", address)
                        >= 10
                || db.count("SELECT COUNT(*) FROM os_recovery_budget") >= 1000) return;
        db.update(
                "INSERT INTO os_recovery_budget(request_id,identity_hash,remote_hash,requested_at)"
                        + " VALUES(?,?,?,?)",
                id(),
                identity,
                address,
                now);
        if (!store.environment.getProperty("DELIVERY_ENABLED", Boolean.class, false)) return;
        if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439,6)");
        if (db.count(
                        "SELECT COUNT(*) FROM os_outbox WHERE state IN"
                                + " ('queued','processing','failed')")
                >= 1000) return;
        var user =
                db.one(
                        "SELECT * FROM os_user WHERE email=? AND email_verified=1 AND active=1",
                        supplied.toLowerCase(Locale.ROOT));
        if (user == null) return;
        var link = RecoveryLinks.email(db, user);
        String path = link.get("reset_path").toString();
        Outbox.enqueue(
                db,
                store.secret,
                "email.recovery",
                Passwords.tokenHash(path),
                map("email", user.get("email"), "path", path),
                Instant.parse(link.get("expires_at").toString()).toEpochMilli());
    }

    static boolean valid(Store.Db db, String kind, Map<String, Object> payload) throws Exception {
        String path = payload.get("path").toString();
        try {
            if (kind.equals("email.enrollment")) {
                return payload.get("email")
                        .equals(EmailEnrollment.describe(db, path.substring(14)).get("email"));
            }
            if (kind.equals("email.invitation")) {
                String token = path.substring(6);
                Invitations.describe(db, token);
                return payload.get("email")
                        .equals(
                                db.one(
                                                "SELECT email FROM os_invitation WHERE"
                                                        + " token_hash=?",
                                                Passwords.tokenHash(token))
                                        .get("email"));
            }
            if (kind.equals("email.recovery")) {
                String token = path.substring(9);
                RecoveryLinks.describe(db, token);
                return db.one(
                                "SELECT u.user_id FROM os_user u JOIN os_recovery r ON"
                                    + " r.user_id=u.user_id WHERE r.token_hash=? AND u.email=? AND"
                                    + " u.email_verified=1",
                                Passwords.tokenHash(token),
                                payload.get("email"))
                        != null;
            }
            return false;
        } catch (Failure unavailable) {
            return false;
        }
    }
}
