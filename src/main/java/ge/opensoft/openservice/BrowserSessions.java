package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.time.Instant;
import java.util.Map;

/** Persistent session inventory, never a substitute for the servlet's authentication cookie. */
final class BrowserSessions {
    static final long IDLE_MILLIS = 30 * 60 * 1000L;

    private BrowserSessions() {}

    static void register(Store.Db db, Map<String, Object> user, String session, long issued)
            throws Exception {
        db.update(
                "DELETE FROM os_browser_session WHERE expires_at<=? OR last_seen<=?",
                issued,
                issued - IDLE_MILLIS);
        db.update(
                "INSERT INTO os_browser_session(session_id,session_hash,user_id,auth_revision,"
                        + "created_at,last_seen,expires_at) VALUES(?,?,?,?,?,?,?)",
                id(),
                Passwords.tokenHash(session),
                user.get("user_id"),
                user.get("auth_revision"),
                issued,
                issued,
                issued + SessionLifetime.MAX_AGE_MILLIS);
    }

    static boolean touch(Store.Db db, Map<String, Object> user, String session, long now)
            throws Exception {
        boolean valid =
                db.update(
                                "UPDATE os_browser_session SET last_seen=CASE WHEN last_seen>? THEN"
                                    + " last_seen ELSE ? END WHERE session_hash=? AND"
                                    + " user_id=? AND auth_revision=? AND created_at<=? AND"
                                    + " last_seen>? AND expires_at>?",
                                now,
                                now,
                                Passwords.tokenHash(session),
                                user.get("user_id"),
                                user.get("auth_revision"),
                                now,
                                now - IDLE_MILLIS,
                                now)
                        == 1;
        // Release the heartbeat row lock before acquiring any business-write advisory lock.
        db.connection.commit();
        return valid;
    }

    static void remove(Store.Db db, String session) throws Exception {
        db.update(
                "DELETE FROM os_browser_session WHERE session_hash=?",
                Passwords.tokenHash(session));
    }

    /** Called after the business-write lock, without releasing that transaction. */
    static void requireCurrent(Store.Db db, Map<String, Object> user, String session, long now)
            throws Exception {
        if (db.count(
                        "SELECT COUNT(*) FROM os_browser_session WHERE session_hash=? AND user_id=?"
                            + " AND auth_revision=? AND created_at<=? AND last_seen>? AND"
                            + " expires_at>?",
                        Passwords.tokenHash(session),
                        user.get("user_id"),
                        user.get("auth_revision"),
                        now,
                        now - IDLE_MILLIS,
                        now)
                != 1) throw new Failure(401, "Browser session ended; sign in again");
    }

    static boolean revoke(Store.Db db, Map<String, Object> user, String selected, String current)
            throws Exception {
        var row =
                db.one(
                        "SELECT session_hash FROM os_browser_session WHERE session_id=? AND"
                            + " user_id=?",
                        selected,
                        user.get("user_id"));
        if (row == null) throw new Failure(404, "Browser session not found");
        db.update("DELETE FROM os_browser_session WHERE session_id=?", selected);
        String userId = user.get("user_id").toString();
        SecurityEvents.record(
                db, userId, userId, "account.session_revoked", map("session_id", selected));
        return row.get("session_hash").equals(Passwords.tokenHash(current));
    }

    static Map<String, Object> mine(Store.Db db, Map<String, Object> user, String session, long now)
            throws Exception {
        var rows =
                db.all(
                        "SELECT session_id,session_hash,created_at,last_seen,expires_at FROM"
                            + " os_browser_session WHERE user_id=? AND auth_revision=? AND"
                            + " created_at<=? AND last_seen>? AND expires_at>?"
                            + " ORDER BY created_at DESC,session_id DESC LIMIT 101",
                        user.get("user_id"),
                        user.get("auth_revision"),
                        now,
                        now - IDLE_MILLIS,
                        now);
        boolean truncated = rows.size() > 100;
        if (truncated) rows.removeLast();
        for (var row : rows) {
            row.put("current", row.remove("session_hash").equals(Passwords.tokenHash(session)));
            for (String field : new String[] {"created_at", "last_seen", "expires_at"})
                row.put(
                        field,
                        Instant.ofEpochMilli(((Number) row.get(field)).longValue()).toString());
        }
        return map("result", rows, "truncated", truncated);
    }
}
