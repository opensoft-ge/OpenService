package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** Recovery-email enrollment and revision-bound dual-proof address replacement. */
final class EmailEnrollment {
    private EmailEnrollment() {}

    static Map<String, Object> status(Store.Db db, String actor) throws SQLException {
        var user = db.one("SELECT email,email_verified FROM os_user WHERE user_id=?", actor);
        var pending =
                db.one(
                        "SELECT email,expires_at,previous_email,old_confirmed,new_confirmed FROM"
                            + " os_email_enrollment WHERE user_id=? AND cancelled=0",
                        actor);
        if (pending != null)
            pending.put(
                    "expires_at",
                    Instant.ofEpochMilli(((Number) pending.get("expires_at")).longValue())
                            .toString());
        return map(
                "email",
                user.get("email"),
                "verified",
                ((Number) user.get("email_verified")).intValue() == 1,
                "pending",
                pending);
    }

    static Map<String, Object> request(
            Store store, Store.Db db, Map<String, Object> caller, Object body) throws Exception {
        String actor = caller.get("user_id").toString();
        var values = object(body);
        if (!values.keySet().equals(Set.of("email", "current_password"))
                || !(values.get("current_password") instanceof String password)
                || !Passwords.withinLimit(password))
            throw new Failure(400, "Provide email and current_password");
        String email = EmailOnboarding.email(values.get("email"));
        if (!store.environment.getProperty("DELIVERY_ENABLED", Boolean.class, false))
            throw new Failure(503, "Email delivery is not configured");
        SmtpDelivery.base(store.environment);
        var account = db.one("SELECT name FROM os_user WHERE user_id=? AND active=1", actor);
        if (account == null) throw new Failure(401, "Active account required");
        // Authentication commits its attempt evidence before any enrollment mutation.
        var verified =
                Authentication.verify(db, account.get("name").toString(), password, "email-enroll");
        if (verified == null) throw new Failure(400, "Current password is incorrect");
        if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439,4)");
        var user =
                db.one(
                        "SELECT email,email_verified FROM os_user WHERE user_id=?"
                                + (db.postgres ? " FOR UPDATE" : ""),
                        actor);
        Authentication.requireCurrent(db, caller);
        String previous = user.get("email").toString();
        if (!previous.isEmpty() && ((Number) user.get("email_verified")).intValue() != 1)
            throw new Failure(409, "Verify the registered address before changing it");
        if (db.one("SELECT user_id FROM os_user WHERE email=?", email) != null)
            throw new Failure(409, "Email address is unavailable");
        long now = System.currentTimeMillis();
        if (db.count(
                        "SELECT COUNT(*) FROM os_email_enrollment WHERE user_id=? AND created_at>?",
                        actor,
                        now - 60_000)
                > 0)
            throw new Failure(
                    429, "Wait at least one minute before requesting another verification message");
        String token = Passwords.token(), hash = Passwords.tokenHash(token);
        long expires = now + 30 * 60_000;
        db.update(
                "INSERT INTO"
                    + " os_email_enrollment(user_id,token_hash,email,auth_revision,expires_at,created_at)"
                    + " VALUES(?,?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET"
                    + " token_hash=excluded.token_hash,email=excluded.email,auth_revision=excluded.auth_revision,expires_at=excluded.expires_at,created_at=excluded.created_at,cancelled=0",
                actor,
                hash,
                email,
                verified.get("auth_revision"),
                expires,
                now);
        String oldToken = Passwords.token();
        db.update(
                "UPDATE os_email_enrollment SET"
                        + " previous_email=?,old_token_hash=?,old_confirmed=?,new_confirmed=0 WHERE"
                        + " user_id=?",
                previous,
                Passwords.tokenHash(oldToken),
                previous.isEmpty() ? 1 : 0,
                actor);
        if (!previous.isEmpty())
            Outbox.enqueue(
                    db,
                    store.secret,
                    "email.enrollment",
                    Passwords.tokenHash(oldToken),
                    map("email", previous, "path", "/verify-email/" + oldToken),
                    expires);
        String job =
                Outbox.enqueue(
                        db,
                        store.secret,
                        "email.enrollment",
                        hash,
                        map("email", email, "path", "/verify-email/" + token),
                        expires);
        SecurityEvents.record(
                db, actor, actor, "account.email_enrollment_requested", map("job_id", job));
        return map("delivery_id", job, "expires_at", Instant.ofEpochMilli(expires).toString());
    }

    static Map<String, Object> describe(Store.Db db, String token) throws Exception {
        var row =
                db.one(
                        "SELECT"
                            + " e.user_id,e.email,e.previous_email,e.expires_at,e.old_confirmed,e.new_confirmed,u.name,CASE"
                            + " WHEN e.old_token_hash=? THEN 1 ELSE 0 END AS old_proof FROM"
                            + " os_email_enrollment e JOIN os_user u ON u.user_id=e.user_id WHERE"
                            + " ((e.token_hash=? AND e.new_confirmed=0) OR (e.old_token_hash=? AND"
                            + " e.old_confirmed=0)) AND e.expires_at>? AND u.active=1 AND"
                            + " u.auth_revision=e.auth_revision AND u.email=e.previous_email AND"
                            + " e.cancelled=0",
                        Passwords.tokenHash(token),
                        Passwords.tokenHash(token),
                        Passwords.tokenHash(token),
                        System.currentTimeMillis());
        if (row == null)
            throw new Failure(400, "Email verification link is unavailable or expired");
        row.put(
                "delivery_email",
                ((Number) row.get("old_proof")).intValue() == 1
                        ? row.get("previous_email")
                        : row.get("email"));
        return row;
    }

    static void confirm(Store.Db db, String token) throws Exception {
        if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439,2)");
        var row = describe(db, token);
        String actor = row.get("user_id").toString();
        if (db.postgres) db.all("SELECT user_id FROM os_user WHERE user_id=? FOR UPDATE", actor);
        row = describe(db, token);
        if (db.one("SELECT user_id FROM os_user WHERE email=?", row.get("email")) != null)
            throw new Failure(409, "Email address is unavailable");
        boolean old = ((Number) row.get("old_proof")).intValue() == 1;
        db.update(
                "UPDATE os_email_enrollment SET "
                        + (old ? "old_confirmed" : "new_confirmed")
                        + "=1 WHERE user_id=?",
                actor);
        if (((Number) row.get(old ? "new_confirmed" : "old_confirmed")).intValue() == 0) {
            SecurityEvents.record(
                    db,
                    actor,
                    actor,
                    "account.email_change_proof",
                    map("proof", old ? "old_address" : "new_address"));
            return;
        }
        db.update(
                "UPDATE os_user SET email=?,email_verified=1 WHERE user_id=?",
                row.get("email"),
                actor);
        Service.revokeSessions(db, actor);
        db.update("DELETE FROM os_email_enrollment WHERE user_id=? AND cancelled=0", actor);
        SecurityEvents.record(db, actor, actor, "account.email_enrollment_confirmed", map());
    }

    static void cancel(Store.Db db, String actor) throws SQLException {
        if (db.postgres) {
            db.all("SELECT pg_advisory_xact_lock(18439,2)");
            db.all("SELECT user_id FROM os_user WHERE user_id=? FOR UPDATE", actor);
        }
        if (db.update(
                        "UPDATE os_email_enrollment SET cancelled=1,email='',expires_at=0 WHERE"
                                + " user_id=? AND cancelled=0",
                        actor)
                > 0)
            SecurityEvents.record(db, actor, actor, "account.email_enrollment_cancelled", map());
    }
}
