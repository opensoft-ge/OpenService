package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.Map;

/** An administrator can require replacement; only replacement can satisfy the requirement. */
final class PasswordReset {
    private PasswordReset() {}

    static boolean required(Map<String, Object> user) {
        return ((Number) user.get("password_reset_required")).intValue() == 1;
    }

    static Map<String, Object> state(Store.Db db, String id) throws SQLException {
        var user = db.one("SELECT password_reset_required FROM os_user WHERE user_id=?", id);
        if (user == null) throw new Failure(404, "User not found");
        return map("required", required(user));
    }

    static Map<String, Object> require(Store.Db db, String actor, String target)
            throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        var state = state(db, target);
        if ((Boolean) state.get("required")) return state;
        db.update("UPDATE os_user SET password_reset_required=1 WHERE user_id=?", target);
        Service.revokeSessions(db, target);
        SecurityEvents.record(db, actor, target, "account.password_change_required", map());
        return map("required", true);
    }
}
