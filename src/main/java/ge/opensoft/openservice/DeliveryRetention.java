package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** Explicit, bounded retention of terminal metadata; never a business-data or audit purge. */
final class DeliveryRetention {
    private DeliveryRetention() {}

    static final String ELIGIBLE = "state IN ('delivered','cancelled') AND expires_at<? AND NOT EXISTS (SELECT 1 FROM os_retention_hold h WHERE h.job_id=os_outbox.job_id)";

    static Map<String, Object> run(
            Store.Db db, String actor, Map<String, Object> values, boolean apply, long now)
            throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        if (!values.keySet().equals(apply ? Set.of("days", "confirm") : Set.of("days")))
            throw new Failure(
                    400, "Provide retention days and explicit purge confirmation when applying");
        String supplied = Objects.toString(values.get("days"), "");
        if (!supplied.matches("[1-9][0-9]{1,3}"))
            throw new Failure(400, "Retention days must be an integer from 30 to 3650");
        int days = Integer.parseInt(supplied);
        if (days < 30 || days > 3650)
            throw new Failure(400, "Retention days must be from 30 to 3650");
        long cutoff = now - days * 86_400_000L;
        int deleted = 0;
        if (apply) {
            if (!"PURGE".equals(values.get("confirm")))
                throw new Failure(400, "Confirm permanent removal with PURGE");
            deleted =
                    db.update(
                            "DELETE FROM os_outbox WHERE job_id IN (SELECT job_id FROM os_outbox"
                                + " WHERE "
                                    + ELIGIBLE
                                    + " ORDER BY expires_at,job_id LIMIT 500)",
                            cutoff);
            SecurityEvents.record(
                    db,
                    actor,
                    actor,
                    "delivery.metadata_purged",
                    map("deleted", deleted, "cutoff_epoch_ms", cutoff, "days", days));
        }
        return map(
                "days",
                days,
                "cutoff_epoch_ms",
                cutoff,
                "cutoff",
                Instant.ofEpochMilli(cutoff).toString(),
                "eligible",
                db.count("SELECT COUNT(*) FROM os_outbox WHERE " + ELIGIBLE, cutoff),
                "batch_limit",
                500,
                "deleted",
                deleted,
                "applied",
                apply);
    }
}
