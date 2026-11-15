package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Account-specific session oversight using the same validity rules as self-service. */
final class AdminSessions {
    private AdminSessions() {}

    static String reason(Object body) {
        var value = object(body);
        if (!value.keySet().equals(Set.of("reason"))
                || !(value.get("reason") instanceof String reason)
                || reason.isBlank()
                || reason.codePointCount(0, reason.length()) > 500
                || reason.indexOf('\0') >= 0)
            throw new Failure(400, "Provide a reason of 1 to 500 characters");
        return reason.strip();
    }

    static boolean revoke(
            Store.Db db, String actor, String target, String selected, String current, Object body)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        String reason = reason(body);
        Service.account(db, target);
        var row =
                db.one(
                        "SELECT session_hash FROM os_browser_session WHERE session_id=? AND"
                            + " user_id=?",
                        selected,
                        target);
        if (row == null) throw new Failure(404, "Browser session not found");
        db.update(
                "DELETE FROM os_browser_session WHERE session_id=? AND user_id=?",
                selected,
                target);
        SecurityEvents.record(
                db,
                actor,
                target,
                "account.session_ended_by_admin",
                map("session_id", selected, "reason", reason));
        return row.get("session_hash").equals(Passwords.tokenHash(current));
    }

    static Map<String, Object> revokeAll(
            Store.Db db, String actor, String target, String current, Object body)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        String reason = reason(body);
        Service.account(db, target);
        boolean endedCurrent =
                db.count(
                                "SELECT COUNT(*) FROM os_browser_session WHERE user_id=? AND"
                                    + " session_hash=?",
                                target,
                                Passwords.tokenHash(current))
                        == 1;
        int removed = db.update("DELETE FROM os_browser_session WHERE user_id=?", target);
        if (removed > 0)
            SecurityEvents.record(
                    db,
                    actor,
                    target,
                    "account.all_sessions_ended_by_admin",
                    map("removed_count", removed, "reason", reason));
        return map("removed_count", removed, "current", endedCurrent);
    }

    static Map<String, Object> list(
            Store.Db db, String actor, String target, String current, long now) throws Exception {
        Service.requireAccountAdministrator(db, actor);
        var account = Service.account(db, target);
        var user =
                db.one(
                        "SELECT user_id,auth_revision,api_only FROM os_user WHERE user_id=?",
                        target);
        var result =
                !account.get("active").equals(true) || AccessMode.apiOnly(user)
                        ? map("result", List.of(), "truncated", false)
                        : BrowserSessions.mine(db, user, current, now);
        result.put("account", account);
        return result;
    }
}
