package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import org.springframework.core.env.Environment;

import java.util.Map;

/** Deployment policy for new browser sign-ins; the current sign-in is always retained. */
final class SessionLimit {
    private SessionLimit() {}

    static int read(Environment environment) {
        String value = environment.getProperty("MAX_BROWSER_SESSIONS", "10");
        if (!value.matches("[1-9][0-9]{0,2}") || Integer.parseInt(value) > 100)
            throw new IllegalArgumentException(
                    "MAX_BROWSER_SESSIONS must be an integer from 1 to 100");
        return Integer.parseInt(value);
    }

    static void enforce(Store.Db db, Map<String, Object> user, String current, int maximum)
            throws Exception {
        int removed =
                db.update(
                        "DELETE FROM os_browser_session WHERE user_id=? AND auth_revision=? AND"
                            + " session_hash<>? AND session_id NOT IN (SELECT session_id FROM"
                            + " os_browser_session WHERE user_id=? AND auth_revision=? AND"
                            + " session_hash<>? ORDER BY created_at DESC,session_id DESC LIMIT ?)",
                        user.get("user_id"),
                        user.get("auth_revision"),
                        Passwords.tokenHash(current),
                        user.get("user_id"),
                        user.get("auth_revision"),
                        Passwords.tokenHash(current),
                        maximum - 1);
        if (removed > 0) {
            String userId = user.get("user_id").toString();
            SecurityEvents.record(
                    db,
                    userId,
                    userId,
                    "account.sessions_limited",
                    map("removed", removed, "maximum", maximum));
        }
    }
}
