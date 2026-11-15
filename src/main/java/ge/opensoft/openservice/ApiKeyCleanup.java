package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.util.Map;

/** Explicit expired-credential cleanup; live and merely suspended keys are retained. */
final class ApiKeyCleanup {
    private ApiKeyCleanup() {}

    static Map<String, Object> expired(Store.Db db, String actor, String target, Object body)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        String reason = AdminSessions.reason(body);
        Service.account(db, target);
        long cutoff = System.currentTimeMillis();
        int removed =
                db.update(
                        "DELETE FROM os_api_key WHERE user_id=? AND expires_at<=?", target, cutoff);
        if (removed > 0)
            SecurityEvents.record(
                    db,
                    actor,
                    target,
                    "account.expired_api_keys_removed",
                    map(
                            "removed_count",
                            removed,
                            "reason",
                            reason,
                            "expired_at_or_before",
                            cutoff));
        return map("removed_count", removed);
    }
}
