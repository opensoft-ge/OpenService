package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

/** Per-account Basic authentication policy, independent of roles and browser access. */
final class BasicAccess {
    private BasicAccess() {}

    static boolean allowed(Map<String, Object> user) {
        return ((Number) user.get("allow_basic")).intValue() == 1;
    }

    static Map<String, Object> state(Store.Db db, String id) throws SQLException {
        var user = db.one("SELECT allow_basic FROM os_user WHERE user_id=?", id);
        if (user == null) throw new Failure(404, "User not found");
        return map("allow_basic", allowed(user));
    }

    static Map<String, Object> change(Store.Db db, String actor, String target, Object body)
            throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        var value = object(body);
        if (!value.keySet().equals(Set.of("allow_basic"))
                || !(value.get("allow_basic") instanceof Boolean enabled))
            throw new Failure(400, "Provide one boolean allow_basic field");
        var before = state(db, target);
        if (before.get("allow_basic").equals(enabled)) return before;
        if (!enabled && AccessMode.state(db, target).get("api_only").equals(true))
            throw new Failure(409, "Allow browser access before disabling Basic authentication");
        db.update("UPDATE os_user SET allow_basic=? WHERE user_id=?", enabled ? 1 : 0, target);
        Service.revokeSessions(db, target);
        SecurityEvents.record(
                db,
                actor,
                target,
                "account.basic_access_changed",
                map("before", before.get("allow_basic"), "after", enabled));
        return map("allow_basic", enabled);
    }
}
