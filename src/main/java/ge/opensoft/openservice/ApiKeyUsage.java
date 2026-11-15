package ge.opensoft.openservice;

/** Successful application requests only; no request content or network identifiers are retained. */
final class ApiKeyUsage {
    private ApiKeyUsage() {}

    static void accepted(Store.Db db, String key) throws Exception {
        long now = System.currentTimeMillis();
        db.update(
                "UPDATE os_api_key SET use_count=use_count+1,last_used_at=CASE WHEN last_used_at>?"
                    + " THEN last_used_at ELSE ? END WHERE key_id=?",
                now,
                now,
                key);
    }
}
