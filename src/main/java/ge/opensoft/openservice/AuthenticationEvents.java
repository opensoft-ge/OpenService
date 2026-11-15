package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Bounded local authentication journal; all outcome/mechanism values come from trusted callers. */
final class AuthenticationEvents {
    private AuthenticationEvents() {}

    static void record(Store.Db db, String name, String identity, String mechanism, String outcome)
            throws SQLException {
        var user =
                name.indexOf(0) >= 0
                        ? null
                        : db.one("SELECT user_id,name FROM os_user WHERE name=?", name);
        db.update(
                "INSERT INTO"
                    + " os_auth_event(event_id,occurred_at,user_id,user_name,identity_hash,mechanism,outcome)"
                    + " VALUES(?,?,?,?,?,?,?)",
                id(),
                now(),
                user == null ? "" : user.get("user_id"),
                user == null ? "" : user.get("name"),
                identity,
                mechanism,
                outcome);
        db.update(
                "DELETE FROM os_auth_event WHERE sequence<(SELECT sequence FROM os_auth_event ORDER"
                        + " BY sequence DESC LIMIT 1 OFFSET 9999)");
    }

    static final Map<String, String> METHODS =
            Map.of(
                    "browser",
                    "Browser sign-in",
                    "basic",
                    "API sign-in",
                    "email-enroll",
                    "Recovery email verification",
                    "password-change",
                    "Password change",
                    "recovery-issue",
                    "Administrator recovery verification");

    /** Own-account projection: caller identity comes only from the authenticated request. */
    static Map<String, Object> mine(Store.Db db, String userId, Map<String, String> args)
            throws SQLException {
        if (!Set.of("before", "download").containsAll(args.keySet())
                || !Set.of("true", "false").contains(args.getOrDefault("download", "false")))
            throw new Failure(400, "Unsupported activity parameter");
        String before = args.get("before");
        var parameters = new ArrayList<Object>();
        parameters.add(userId);
        if (before != null) {
            if (!before.matches("[0-9a-f]{32}")) throw new Failure(400, "Invalid activity cursor");
            var cursor =
                    db.one(
                            "SELECT sequence FROM os_auth_event WHERE user_id=? AND event_id=?",
                            userId,
                            before);
            if (cursor == null)
                throw new Failure(400, "Activity cursor is unavailable; return to latest events");
            parameters.add(cursor.get("sequence"));
        }
        var rows =
                db.all(
                        "SELECT event_id,occurred_at,mechanism,outcome FROM os_auth_event WHERE"
                            + " user_id=?"
                                + (before == null ? "" : " AND sequence<?")
                                + " ORDER BY sequence DESC LIMIT 101",
                        parameters.toArray());
        String next = "";
        if (rows.size() > 100) {
            rows.remove(100);
            next = rows.getLast().get("event_id").toString();
        }
        return map(
                "result",
                rows,
                "next_before",
                next,
                "before",
                before == null ? "" : before,
                "download",
                Boolean.parseBoolean(args.getOrDefault("download", "false")));
    }

    static Map<String, Object> page(Store.Db db, Map<String, String> args) throws SQLException {
        return EventJournal.page(
                db,
                "os_auth_event",
                "event_id,occurred_at,user_id,user_name,identity_hash,mechanism,outcome",
                args);
    }
}
