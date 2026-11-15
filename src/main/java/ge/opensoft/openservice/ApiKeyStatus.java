package ge.opensoft.openservice;

import java.util.Map;

/** Credential eligibility only; table, record, field and operation permissions still apply. */
final class ApiKeyStatus {
    private ApiKeyStatus() {}

    static String describe(Map<String, Object> key, Map<String, Object> account, long now) {
        if (((Number) key.get("expires_at")).longValue() <= now) return "Expired";
        if (((Number) key.get("enabled")).intValue() != 1) return "Suspended";
        if (((Number) account.get("active")).intValue() != 1) return "Account inactive";
        if (!AccessMode.apiOnly(account)) return "Browser account";
        if (account.get("role").equals("admin")) return "Administrator account";
        if (PasswordReset.required(account)) return "Password change required";
        if (((Number) key.get("auth_revision")).longValue()
                != ((Number) account.get("auth_revision")).longValue()) return "Account changed";
        return "Eligible";
    }
}
