package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.text.Normalizer;
import java.util.*;

/**
 * Operators publish versioned SHA-256/NFC whole-password corpora without sending plaintext values.
 */
final class PasswordPolicies {
    private PasswordPolicies() {}

    static Map<String, Object> status(Store.Db db) throws Exception {
        var row = db.one("SELECT definition FROM os_password_policy WHERE policy_id=1");
        return row == null
                ? map(
                        "minimum_length",
                        15,
                        "source",
                        "not configured",
                        "version",
                        "",
                        "digest_count",
                        0)
                : object(row.get("definition"));
    }

    static void replace(Store.Db db, String actor, Object input) throws Exception {
        Service.requireAccountAdministrator(db, actor);
        var body = object(input);
        require(
                body.keySet().equals(Set.of("minimum_length", "source", "version", "digests")),
                "Provide minimum_length, source, version and digests");
        require(
                body.get("minimum_length") instanceof Integer n && n >= 15 && n <= 128,
                "Minimum length must be 15–128 characters");
        String source = WorkJobs.text(body.get("source"), 160),
                version = WorkJobs.text(body.get("version"), 80);
        require(body.get("digests") instanceof List<?>, "Digests must be a list");
        var digests = (List<?>) body.get("digests");
        require(!digests.isEmpty() && digests.size() <= 100000, "Provide 1–100000 SHA-256 digests");
        for (var digest : digests)
            require(
                    digest instanceof String s && s.matches("[a-f0-9]{64}"),
                    "Use lowercase SHA-256 digests of NFC-normalized passwords");
        db.update("DELETE FROM os_password_corpus");
        for (var digest : digests)
            db.update(
                    "INSERT INTO os_password_corpus(digest) VALUES(?) ON CONFLICT(digest) DO"
                        + " NOTHING",
                    digest);
        var policy =
                map(
                        "minimum_length",
                        body.get("minimum_length"),
                        "source",
                        source,
                        "version",
                        version,
                        "digest_count",
                        db.count("SELECT COUNT(*) FROM os_password_corpus"),
                        "updated_at",
                        now());
        db.update(
                "INSERT INTO os_password_policy(policy_id,definition) VALUES(1,?) ON"
                    + " CONFLICT(policy_id) DO UPDATE SET definition=excluded.definition",
                JSON.writeValueAsString(policy));
        SecurityEvents.record(db, actor, actor, "policy.password_corpus_replaced", policy);
    }

    static void check(Store.Db db, String raw) throws Exception {
        String password = Normalizer.normalize(raw, Normalizer.Form.NFC);
        int minimum = ((Number) status(db).get("minimum_length")).intValue();
        require(
                password.codePointCount(0, password.length()) >= minimum,
                "Password must contain at least "
                        + minimum
                        + " characters under the organization policy");
        if (db.one(
                        "SELECT digest FROM os_password_corpus WHERE digest=?",
                        Passwords.tokenHash(password))
                != null)
            throw new Failure(
                    400,
                    "This password appears in the organization's screening corpus; choose a unique"
                        + " password");
    }
}
