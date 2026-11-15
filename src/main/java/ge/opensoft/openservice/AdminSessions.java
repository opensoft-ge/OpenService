package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.util.List;
import java.util.Map;

/** Account-specific session oversight using the same validity rules as self-service. */
final class AdminSessions {
    private AdminSessions() {}

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
