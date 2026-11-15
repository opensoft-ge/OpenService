package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.util.*;

/** Reduces remaining lifetime only; it cannot renew or re-enable a credential. */
final class ApiKeyExpiry {
    private ApiKeyExpiry() {}

    static Map<String, Object> shorten(Store.Db db, String actor, String target, Object body)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        Service.account(db, target);
        var value = object(body);
        if (!value.keySet().equals(Set.of("key_id", "hours"))
                || !(value.get("key_id") instanceof String key)
                || !key.matches("[0-9a-f]{32}")
                || !(value.get("hours") instanceof Integer hours)
                || hours < 1
                || hours > 2160)
            throw new Failure(400, "Provide a key ID and whole remaining hours from 1 to 2160");
        var row =
                db.one(
                        "SELECT expires_at FROM os_api_key WHERE user_id=? AND key_id=?",
                        target,
                        key);
        if (row == null) throw new Failure(404, "API key not found");
        long before = ((Number) row.get("expires_at")).longValue();
        long expiry = System.currentTimeMillis() + hours * 3600000L;
        if (expiry >= before)
            throw new Failure(
                    409,
                    "New expiry must be earlier than the existing expiry; keys cannot be extended"
                        + " or renewed");
        db.update("UPDATE os_api_key SET expires_at=? WHERE key_id=?", expiry, key);
        SecurityEvents.record(
                db,
                actor,
                target,
                "account.api_key_expiry_shortened",
                map("key_id", key, "before", before, "after", expiry));
        return map("key_id", key, "expires_at", expiry);
    }

    static int hours(String value) {
        if (!value.matches("[1-9][0-9]{0,3}"))
            throw new Failure(400, "Whole remaining hours are required");
        return Integer.parseInt(value);
    }
}
