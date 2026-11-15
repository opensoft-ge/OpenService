package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.util.*;

/** One-way authority reduction; never renews, resumes or replaces a credential. */
final class ApiKeyReadOnly {
    private ApiKeyReadOnly() {}

    static Map<String, Object> reduce(Store.Db db, String actor, String target, Object body)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        Service.account(db, target);
        var value = object(body);
        if (!value.keySet().equals(Set.of("key_id", "reason"))
                || !(value.get("key_id") instanceof String key)
                || !key.matches("[0-9a-f]{32}"))
            throw new Failure(400, "Provide a key ID and reason");
        String reason = AdminSessions.reason(map("reason", value.get("reason")));
        var row =
                db.one(
                        "SELECT can_write FROM os_api_key WHERE user_id=? AND key_id=?",
                        target,
                        key);
        if (row == null) throw new Failure(404, "API key not found");
        boolean changed = ((Number) row.get("can_write")).intValue() == 1;
        if (changed) {
            db.update("UPDATE os_api_key SET can_write=0 WHERE key_id=?", key);
            SecurityEvents.record(
                    db,
                    actor,
                    target,
                    "account.api_key_reduced",
                    map("key_id", key, "reason", reason, "access", "read_only"));
        }
        return map("key_id", key, "access", "read_only", "changed", changed);
    }
}
