package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.util.Map;
import java.util.Set;

/** Suspension never extends a credential's lifetime or account authority. */
final class ApiKeyState {
    private ApiKeyState() {}

    static Map<String, Object> change(
            Store.Db db, String actor, String target, String key, Object body) throws Exception {
        Service.requireAccountAdministrator(db, actor);
        Service.account(db, target);
        var value = object(body);
        if (!value.keySet().equals(Set.of("enabled"))
                || !(value.get("enabled") instanceof Boolean enabled))
            throw new Failure(400, "Provide one boolean enabled field");
        var row =
                db.one("SELECT enabled FROM os_api_key WHERE user_id=? AND key_id=?", target, key);
        if (row == null) throw new Failure(404, "API key not found");
        boolean before = ((Number) row.get("enabled")).intValue() == 1;
        if (before != enabled) {
            db.update("UPDATE os_api_key SET enabled=? WHERE key_id=?", enabled ? 1 : 0, key);
            SecurityEvents.record(
                    db,
                    actor,
                    target,
                    "account.api_key_state_changed",
                    map("key_id", key, "before", before, "after", enabled));
        }
        return map("enabled", enabled);
    }

    static boolean form(String value) {
        if (!Set.of("true", "false").contains(value))
            throw new Failure(400, "Choose enabled or suspended");
        return Boolean.parseBoolean(value);
    }
}
