package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Durable, bounded administrator work. Deduplication keys are scoped to the submitting account. */
final class WorkJobs {
    static final String COLUMNS =
            "job_id,owner_id,kind,dedupe,state,attempts,available_at,created_at,error";

    private WorkJobs() {}

    static String text(Object value, int limit) {
        require(value instanceof String, "A text value is required");
        String result = (String) value;
        require(
                !result.isBlank() && result.length() <= limit && result.indexOf('\0') < 0,
                "Invalid or oversized text");
        return result;
    }

    static Map<String, Object> enqueue(Store store, Store.Db db, String actor, Object input)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        var body = object(input);
        require(
                body.keySet().equals(Set.of("kind", "key", "payload")),
                "Provide kind, key and payload");
        String kind = text(body.get("kind"), 40), key = text(body.get("key"), 80);
        require(
                Set.of("notification", "connector.read", "migration.step").contains(kind),
                "Unsupported job kind");
        var payload = object(body.get("payload"));
        String serialized = JSON.writeValueAsString(new TreeMap<>(payload));
        require(serialized.length() <= 16000, "Job payload is too large");
        String fingerprint = Passwords.tokenHash(kind + ":" + serialized);
        var existing =
                db.one(
                        "SELECT "
                                + COLUMNS
                                + ",fingerprint FROM os_work WHERE owner_id=? AND dedupe=?",
                        actor,
                        key);
        if (existing != null) {
            if (!existing.remove("fingerprint").equals(fingerprint))
                throw new Failure(409, "This key already identifies different work");
            return existing;
        }
        require(
                db.count("SELECT COUNT(*) FROM os_work") < 5000,
                "Work retention limit reached; clean up completed jobs");
        var user = db.one("SELECT auth_revision FROM os_user WHERE user_id=?", actor);
        String id = id();
        long now = System.currentTimeMillis();
        db.update(
                "INSERT INTO"
                    + " os_work(job_id,owner_id,auth_revision,kind,dedupe,fingerprint,envelope,state,attempts,available_at,created_at,lease,error,result)"
                    + " VALUES(?,?,?,?,?,?,?,'queued',0,?,?,'','','')",
                id,
                actor,
                user.get("auth_revision"),
                kind,
                key,
                fingerprint,
                Outbox.encrypt(store.secret, serialized),
                now,
                now);
        SecurityEvents.record(db, actor, actor, "work.queued", map("job_id", id, "kind", kind));
        return db.one("SELECT " + COLUMNS + " FROM os_work WHERE job_id=?", id);
    }

    static Map<String, Object> claim(Store.Db db, long now) throws SQLException {
        db.update(
                "UPDATE os_work SET state='failed',lease='',error='LEASE_EXHAUSTED' WHERE"
                    + " state='processing' AND attempts>=5 AND available_at<=?",
                now);
        var job =
                db.one(
                        "SELECT * FROM os_work WHERE state IN ('queued','processing') AND"
                            + " available_at<=? ORDER BY created_at,job_id LIMIT 1"
                                + (db.postgres ? " FOR UPDATE SKIP LOCKED" : ""),
                        now);
        if (job == null) return null;
        job.put("lease", id());
        job.put("attempts", ((Number) job.get("attempts")).intValue() + 1);
        db.update(
                "UPDATE os_work SET state='processing',lease=?,attempts=?,available_at=? WHERE"
                    + " job_id=?",
                job.get("lease"),
                job.get("attempts"),
                now + 120000,
                job.get("job_id"));
        return job;
    }

    static List<Map<String, Object>> list(Store.Db db, String actor) throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        return db.all(
                "SELECT " + COLUMNS + " FROM os_work ORDER BY created_at DESC,job_id LIMIT 100");
    }

    static void control(Store.Db db, String actor, String id, String operation)
            throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        require(
                Set.of("retry", "cancel", "cleanup").contains(operation),
                "Choose retry, cancel or cleanup");
        if (operation.equals("cleanup")) {
            db.update(
                    "DELETE FROM os_work WHERE state IN ('succeeded','cancelled') AND created_at<?",
                    System.currentTimeMillis() - 30L * 86400000);
        } else {
            var row = db.one("SELECT state FROM os_work WHERE job_id=?", id);
            if (row == null) throw new Failure(404, "Job not found");
            require(
                    Set.of("queued", "failed").contains(row.get("state")),
                    "Only queued or failed work can be controlled");
            boolean retry = operation.equals("retry");
            db.update(
                    "UPDATE os_work SET state=?,attempts=0,available_at=?,error='',envelope="
                            + (retry ? "envelope" : "''")
                            + " WHERE job_id=?",
                    retry ? "queued" : "cancelled",
                    System.currentTimeMillis(),
                    id);
        }
        SecurityEvents.record(db, actor, actor, "work." + operation, map("job_id", id));
    }
}
