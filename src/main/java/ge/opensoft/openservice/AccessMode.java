package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

/** Channel restrictions complement role and record policy; they never grant permissions. */
final class AccessMode {
    private AccessMode() {}

    static boolean apiOnly(Map<String, Object> user) {
        return ((Number) user.get("api_only")).intValue() == 1;
    }

    static Map<String, Object> state(Store.Db db, String id) throws SQLException {
        var user = db.one("SELECT api_only FROM os_user WHERE user_id=?", id);
        if (user == null) throw new Failure(404, "User not found");
        return map("api_only", apiOnly(user));
    }

    static boolean lastBrowserAdministrator(Store.Db db, String id) throws SQLException {
        return db.one(
                                "SELECT user_id FROM os_user WHERE user_id=? AND active=1 AND"
                                    + " role='admin' AND api_only=0",
                                id)
                        != null
                && db.count(
                                "SELECT COUNT(*) FROM os_user WHERE active=1 AND role='admin' AND"
                                    + " api_only=0")
                        <= 1;
    }

    static Map<String, Object> change(Store.Db db, String actor, String target, Object body)
            throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        var value = object(body);
        if (!value.keySet().equals(Set.of("api_only"))
                || !(value.get("api_only") instanceof Boolean enabled))
            throw new Failure(400, "Provide one boolean api_only field");
        var before = state(db, target);
        if (before.get("api_only").equals(enabled)) return before;
        if (enabled && BasicAccess.state(db, target).get("allow_basic").equals(false))
            throw new Failure(409, "Allow Basic authentication before selecting API-only access");
        if (enabled && lastBrowserAdministrator(db, target))
            throw new Failure(409, "Keep at least one active browser administrator");
        db.update("UPDATE os_user SET api_only=? WHERE user_id=?", enabled ? 1 : 0, target);
        Service.revokeSessions(db, target);
        SecurityEvents.record(
                db,
                actor,
                target,
                "account.access_mode_changed",
                map("before", before.get("api_only"), "after", enabled));
        return map("api_only", enabled);
    }
}
