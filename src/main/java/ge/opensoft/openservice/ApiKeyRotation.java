package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.util.Map;

/** Rotation replaces only the secret, without extending authority or lifetime. */
final class ApiKeyRotation {
    private ApiKeyRotation() {}

    static Map<String, Object> rotate(Store.Db db, String actor, String target, String key)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        Service.account(db, target);
        var current =
                db.one(
                        "SELECT k.expires_at FROM os_api_key k JOIN os_user u ON"
                            + " u.user_id=k.user_id WHERE k.key_id=? AND k.user_id=? AND"
                            + " k.enabled=1 AND k.expires_at>? AND k.auth_revision=u.auth_revision AND u.active=1"
                            + " AND u.api_only=1 AND u.role<>'admin' AND"
                            + " u.password_reset_required=0",
                        key,
                        target,
                        System.currentTimeMillis());
        if (current == null) throw new Failure(409, "Choose a valid, unexpired API key to rotate");
        String token = "osk_" + Passwords.token();
        db.update(
                "UPDATE os_api_key SET token_hash=? WHERE key_id=?",
                Passwords.tokenHash(token),
                key);
        SecurityEvents.record(db, actor, target, "account.api_key_rotated", map("key_id", key));
        return map("key_id", key, "token", token, "expires_at", current.get("expires_at"));
    }
}
