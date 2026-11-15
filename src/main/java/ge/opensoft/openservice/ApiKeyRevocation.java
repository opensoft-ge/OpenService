package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.util.Map;

/** Account-wide credential withdrawal, serialized with issuance. */
final class ApiKeyRevocation {
    private ApiKeyRevocation() {}

    static Map<String, Object> all(Store.Db db, String actor, String target, Object body)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        String reason = AdminSessions.reason(body);
        Service.account(db, target);
        int removed = db.update("DELETE FROM os_api_key WHERE user_id=?", target);
        if (removed > 0)
            SecurityEvents.record(
                    db,
                    actor,
                    target,
                    "account.all_api_keys_revoked",
                    map("removed_count", removed, "reason", reason));
        return map("removed_count", removed);
    }
}
