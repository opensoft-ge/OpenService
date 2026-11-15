package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.util.*;
import java.util.regex.Pattern;

/** Revocable, expiring, table-bound credentials; only digests are persisted. */
final class ApiKeys {
    private ApiKeys() {}

    static List<Map<String, Object>> list(Store.Db db, String actor, String target)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        Service.account(db, target);
        var rows =
                db.all(
                        "SELECT key_id,label,table_name,created_at,expires_at,enabled,can_write,use_count,last_used_at FROM os_api_key WHERE"
                            + " user_id=? ORDER BY created_at,key_id",
                        target);
        for (var row : rows) {
            long lastUsed = ((Number) row.get("last_used_at")).longValue();
            row.put("last_used_at_utc", lastUsed == 0 ? "Never" : java.time.Instant.ofEpochMilli(lastUsed).toString());
            row.put("access", ((Number) row.remove("can_write")).intValue() == 1 ? "Read, create and update" : "Read only");
            row.put("enabled", ((Number) row.get("enabled")).intValue() == 1);
            row.put(
                    "expires_at_utc",
                    java.time.Instant.ofEpochMilli(((Number) row.get("expires_at")).longValue())
                            .toString());
        }
        return rows;
    }

    static Map<String, Object> issue(Store.Db db, String actor, String target, Object body)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        var account = db.one("SELECT * FROM os_user WHERE user_id=?", target);
        if (account == null) throw new Failure(404, "Account not found");
        if (((Number) account.get("active")).intValue() != 1
                || !AccessMode.apiOnly(account)
                || account.get("role").equals("admin")
                || PasswordReset.required(account))
            throw new Failure(
                    409,
                    "Choose an active, non-administrator API-only account with no password"
                        + " replacement requirement");
        var value = object(body);
        if (!value.keySet().equals(Set.of("label", "table", "days"))
                || !(value.get("label") instanceof String label)
                || label.isBlank()
                || label.codePointCount(0, label.length()) > 80
                || !(value.get("table") instanceof String table)
                || !(value.get("days") instanceof Integer days)
                || days < 1
                || days > 90)
            throw new Failure(
                    400, "Provide label (1–80 characters), table and integer days (1–90)");
        Service.schema(db, table);
        if (db.count("SELECT COUNT(*) FROM os_api_key WHERE user_id=?", target) >= 20)
            throw new Failure(409, "Revoke an existing key before issuing more than 20 keys");
        String key = id(), token = "osk_" + Passwords.token();
        long created = System.currentTimeMillis(), expires = created + days * 86400000L;
        db.update(
                "INSERT INTO"
                    + " os_api_key(key_id,token_hash,user_id,auth_revision,label,table_name,created_at,expires_at)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                key,
                Passwords.tokenHash(token),
                target,
                account.get("auth_revision"),
                label,
                table,
                created,
                expires);
        SecurityEvents.record(
                db,
                actor,
                target,
                "account.api_key_issued",
                map("key_id", key, "table", table, "expires_at", expires));
        return map("key_id", key, "token", token, "expires_at", expires);
    }

    static Map<String, Object> revoke(Store.Db db, String actor, String target, String key)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        Service.account(db, target);
        int removed = db.update("DELETE FROM os_api_key WHERE user_id=? AND key_id=?", target, key);
        if (removed != 0)
            SecurityEvents.record(db, actor, target, "account.api_key_revoked", map("key_id", key));
        return map("revoked", removed != 0);
    }

    static Map<String, Object> authenticate(Store.Db db, String header, String path, String method)
            throws Exception {
        if (!header.matches("Bearer osk_[A-Za-z0-9_-]{32}"))
            throw new Failure(401, "Invalid API key");
        var key =
                db.one(
                        "SELECT * FROM os_api_key WHERE token_hash=? AND enabled=1 AND expires_at>?",
                        Passwords.tokenHash(header.substring(7)),
                        System.currentTimeMillis());
        if (key == null) throw new Failure(401, "Invalid API key");
        var user =
                db.one(
                        "SELECT * FROM os_user WHERE user_id=? AND active=1 AND api_only=1 AND"
                            + " role<>'admin' AND password_reset_required=0 AND auth_revision=?",
                        key.get("user_id"),
                        key.get("auth_revision"));
        if (user == null) throw new Failure(401, "Invalid API key");
        String collection = "/api/now/table/" + Pattern.quote(key.get("table_name").toString());
        boolean record = path.matches(collection + "/[0-9a-f]{32}");
        if (!path.matches(collection) && !record)
            throw new Failure(403, "API key does not permit this operation");
        if (!Set.of("GET", "HEAD").contains(method)
                && (((Number) key.get("can_write")).intValue() != 1
                    || (record ? !Set.of("PATCH", "PUT").contains(method) : !method.equals("POST"))))
            throw new Failure(403, "API key does not permit this operation");
        user.put("api_key_id", key.get("key_id"));
        return user;
    }

    static int days(String input) {
        if (!input.matches("(?:[1-9]|[1-8][0-9]|90)")) throw new Failure(400, "Choose 1–90 days");
        return Integer.parseInt(input);
    }
}
