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
        return prefix(db,Long.MAX_VALUE);
    }

    static Map<String,Object> inspect(Store.Db db,Object input) throws SQLException {
        var args=object(input);
        if(args.isEmpty()) return checkpoint(db);
        require(args.keySet().equals(Set.of("algorithm","count","last_sequence","hash")),
                "Provide the complete retained checkpoint");
        require("sha256-chain-v1".equals(args.get("algorithm")),"Unsupported checkpoint algorithm");
        String hash=WorkJobs.text(args.get("hash"),64);
        require(hash.matches("[0-9a-f]{64}"),"Invalid checkpoint hash");
        long count=number(args.get("count")),sequence=number(args.get("last_sequence"));
        var observed=prefix(db,sequence);
        boolean verified=count==((Number)observed.get("count")).longValue()
                && sequence==((Number)observed.get("last_sequence")).longValue()
                && hash.equals(observed.get("hash"));
        return map("verified",verified,"observed",observed);
    }

    static long number(Object input) {
        String value=WorkJobs.text(input,19);
        require(value.matches("[0-9]+"),"Checkpoint counts must be nonnegative integers");
        try { return Long.parseLong(value); }
        catch(NumberFormatException invalid) { throw new Failure(400,"Checkpoint count exceeds the limit"); }
    }

    static Map<String, Object> prefix(Store.Db db,long through) throws SQLException {
        String hash = "0".repeat(64);
        long count = 0, sequence = 0;
        try (var query =
                        db.statement(
                                "SELECT"
                                    + " sequence,event_id,occurred_at,actor_id,actor_name,target_id,target_name,action,detail"
                                    + " FROM os_security_event WHERE sequence<=? ORDER BY sequence",through);
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
