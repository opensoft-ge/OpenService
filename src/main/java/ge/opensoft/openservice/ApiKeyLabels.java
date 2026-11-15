package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class ApiKeyLabels {
    private ApiKeyLabels() {}

    static String label(Object input) {
        if (!(input instanceof String label)
                || label.isBlank()
                || label.codePointCount(0, label.length()) > 80
                || label.indexOf(0) >= 0)
            throw new Failure(400, "Provide a nonblank label of 1–80 characters without NUL");
        return label;
    }

    static Map<String, Object> rename(Store.Db db, String actor, String target, Object body)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        Service.account(db, target);
        var value = object(body);
        if (!value.keySet().equals(Set.of("key_id", "label"))
                || !(value.get("key_id") instanceof String key)
                || !key.matches("[0-9a-f]{32}")) throw new Failure(400, "Provide key_id and label");
        String label = label(value.get("label"));
        var row = db.one("SELECT label FROM os_api_key WHERE user_id=? AND key_id=?", target, key);
        if (row == null) throw new Failure(404, "API key not found");
        boolean changed = !label.equals(row.get("label"));
        if (changed) {
            db.update("UPDATE os_api_key SET label=? WHERE key_id=?", label, key);
            SecurityEvents.record(db, actor, target, "account.api_key_labeled", map("key_id", key));
        }
        return map("key_id", key, "label", label, "changed", changed);
    }
}
