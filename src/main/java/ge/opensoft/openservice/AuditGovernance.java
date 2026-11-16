package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import org.apache.commons.codec.digest.DigestUtils;

import java.sql.SQLException;
import java.util.*;

/**
 * Independent journal access and portable commitments for an externally retained audit checkpoint.
 */
final class AuditGovernance {
    private AuditGovernance() {}

    static void requireViewer(Store.Db db, String actor) throws SQLException {
        if (db.one(
                        "SELECT user_id FROM os_user WHERE user_id=? AND active=1 AND (role='admin'"
                            + " OR audit_viewer=1)",
                        actor)
                == null) throw new Failure(403, "Audit viewer permission required");
    }

    static void grant(Store.Db db, String actor, Object input) throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        var body = object(input);
        require(body.keySet().equals(Set.of("user_id", "enabled")), "Provide user_id and enabled");
        String target = WorkJobs.text(body.get("user_id"), 32);
        require(body.get("enabled") instanceof Boolean, "Enabled must be boolean");
        Service.account(db, target);
        db.update(
                "UPDATE os_user SET audit_viewer=? WHERE user_id=?",
                body.get("enabled").equals(true) ? 1 : 0,
                target);
        Service.revokeSessions(db, target);
        SecurityEvents.record(
                db,
                actor,
                target,
                "account.audit_access_changed",
                map("enabled", body.get("enabled")));
    }

    static Map<String, Object> checkpoint(Store.Db db) throws SQLException {
        String hash = "0".repeat(64);
        long count = 0, sequence = 0;
        try (var query =
                        db.statement(
                                "SELECT"
                                    + " sequence,event_id,occurred_at,actor_id,actor_name,target_id,target_name,action,detail"
                                    + " FROM os_security_event ORDER BY sequence");
                var rows = query.executeQuery()) {
            while (rows.next()) {
                var row = new ArrayList<String>();
                for (int column = 1; column <= 9; column++) row.add(rows.getString(column));
                hash = DigestUtils.sha256Hex(hash + "\n" + JSON.writeValueAsString(row));
                count++;
                sequence = rows.getLong(1);
            }
        }
        return map(
                "algorithm",
                "sha256-chain-v1",
                "count",
                count,
                "last_sequence",
                sequence,
                "hash",
                hash);
    }
}
