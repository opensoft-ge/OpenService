package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Organization-specific whole-value exclusions; never a store of account credentials. */
final class PasswordExclusions {
    private PasswordExclusions() {}

    static void check(Store.Db db, String password) throws Exception {
        if (db.one(
                        "SELECT exclusion_id FROM os_password_exclusion WHERE digest=?",
                        Passwords.tokenHash(password))
                != null)
            throw new Failure(
                    400,
                    "That password is excluded. Choose a different, unique password or"
                            + " passphrase.");
    }

    static Map<String, Object> page(Store.Db db, Map<String, String> args) throws SQLException {
        if (!Set.of("page").containsAll(args.keySet())
                || !args.getOrDefault("page", "1").matches("[1-9][0-9]{0,5}"))
            throw new Failure(400, "Use a positive page of at most six digits");
        int page = Integer.parseInt(args.getOrDefault("page", "1"));
        long total = db.count("SELECT COUNT(*) FROM os_password_exclusion"),
                pages = Math.max(1, (total + 99) / 100);
        if (page > pages)
            throw new Failure(400, "Page is outside the exclusion list; refresh the first page");
        var rows =
                db.all(
                        "SELECT exclusion_id,label,created_at FROM os_password_exclusion ORDER BY"
                                + " created_at DESC,exclusion_id LIMIT 100 OFFSET ?",
                        (page - 1) * 100);
        return map("result", rows, "page", page, "pages", pages, "total", total, "capacity", 1000);
    }

    static Map<String, Object> add(Store.Db db, String actor, Object body) throws Exception {
        Service.requireAccountAdministrator(db, actor);
        var value = object(body);
        if (!value.keySet().equals(Set.of("label", "password"))
                || !(value.get("label") instanceof String label)
                || label.isBlank()
                || label.strip().codePointCount(0, label.strip().length()) > 80
                || label.codePoints().anyMatch(Character::isISOControl)
                || !(value.get("password") instanceof String password)
                || password.isEmpty()
                || password.codePointCount(0, password.length()) > 1024)
            throw new Failure(
                    400,
                    "Provide a descriptive label of 1–80 characters and a value of 1–1024"
                            + " characters to exclude");
        String digest = Passwords.tokenHash(password);
        if (db.one("SELECT exclusion_id FROM os_password_exclusion WHERE digest=?", digest) != null)
            throw new Failure(409, "This value is already excluded");
        if (db.count("SELECT COUNT(*) FROM os_password_exclusion") >= 1000)
            throw new Failure(409, "Exclusion limit reached; remove an entry first");
        String id = id(), created = now();
        db.update(
                "INSERT INTO os_password_exclusion(exclusion_id,label,digest,created_at)"
                        + " VALUES(?,?,?,?)",
                id,
                label.strip(),
                digest,
                created);
        SecurityEvents.record(
                db, actor, actor, "policy.password_excluded", map("exclusion_id", id));
        return map("exclusion_id", id, "label", label.strip(), "created_at", created);
    }

    static void remove(Store.Db db, String actor, String id) throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        if (db.update("DELETE FROM os_password_exclusion WHERE exclusion_id=?", id) == 0)
            throw new Failure(404, "Password exclusion not found");
        SecurityEvents.record(
                db, actor, actor, "policy.password_exclusion_removed", map("exclusion_id", id));
    }
}
