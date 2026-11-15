package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.util.Map;

/** Explicit atomic conversion; normal Basic/access-mode controls retain their safeguards. */
final class ApiKeyOnly {
    private ApiKeyOnly() {}

    static Map<String, Object> replace(Store.Db db, String actor, String target, Object body)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        Service.account(db, target);
        db.update("UPDATE os_user SET allow_basic=0 WHERE user_id=?", target);
        Service.revokeSessions(db, target);
        int removed = db.update("DELETE FROM os_api_key WHERE user_id=?", target);
        var issued = ApiKeys.issue(db, actor, target, body);
        SecurityEvents.record(
                db,
                actor,
                target,
                "account.api_key_only_enabled",
                map("key_id", issued.get("key_id"), "removed_count", removed));
        return issued;
    }
}
