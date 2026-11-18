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
                body.keySet().equals(Set.of("kind", "key", "payload"))
                        || body.keySet().equals(Set.of("kind", "key", "payload", "run_at")),
                "Provide kind, key, payload and optional run_at");
        String kind = text(body.get("kind"), 40), key = text(body.get("key"), 80);
        require(
                Set.of("notification", "connector.read", "connector.write", "migration.step", "migration.fetch", "approval.reminder", "workflow.run", "report.capture").contains(kind),
                "Unsupported job kind");
        var payload = object(body.get("payload"));
        String serialized = JSON.writeValueAsString(new TreeMap<>(payload));
        require(serialized.length() <= 16000, "Job payload is too large");
        long now = System.currentTimeMillis(), availableAt = now;
        boolean scheduled = body.containsKey("run_at");
        if (scheduled) {
            try {
                var instant = java.time.Instant.parse(text(body.get("run_at"), 40));
                require(instant.getNano() % 1_000_000 == 0, "run_at supports millisecond precision");
                availableAt = instant.toEpochMilli();
            } catch (java.time.DateTimeException | ArithmeticException invalid) {
                throw new Failure(400, "Use an ISO instant for run_at");
            }
        }
        String fingerprint = Passwords.tokenHash(kind + ":" + serialized + (scheduled ? ":" + availableAt : ""));
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
        require(!scheduled || (availableAt >= now && availableAt <= now + 366L * 86400000),
                "Schedule work within the next 366 days");
        require(
                db.count("SELECT COUNT(*) FROM os_work") < 5000,
                "Work retention limit reached; clean up completed jobs");
        if(kind.equals("connector.write")){require(key.equals(payload.get("key")),"Job key must match the reviewed action key");ConnectorActions.validate(store,db,payload,true);}
        if(kind.equals("migration.fetch"))MigrationFetch.validate(db,actor,payload);
        if(kind.equals("approval.reminder"))ScheduledApprovalReminders.validate(db,actor,payload);
        if(kind.equals("report.capture"))ReportSchedules.validate(db,actor,key,payload);
        if(kind.equals("workflow.run"))EventWorkflows.validate(db,actor,key,payload);
        var user = db.one("SELECT auth_revision FROM os_user WHERE user_id=?", actor);
        String id = id();
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
                availableAt,
                now);
        if(kind.equals("report.capture"))ReportSchedules.queued(db,actor,id,payload);
        if(kind.equals("workflow.run"))EventWorkflows.queued(db,id,payload);
        SecurityEvents.record(db, actor, actor, "work.queued", map("job_id", id, "kind", kind));
        return db.one("SELECT " + COLUMNS + " FROM os_work WHERE job_id=?", id);
    }

    static Map<String, Object> claim(Store.Db db, long now) throws SQLException {
        for(var expired:db.all("SELECT job_id,lease FROM os_work WHERE state='processing' AND attempts>=5 AND available_at<=?"+(db.postgres?" FOR UPDATE SKIP LOCKED":""),now)){
            WorkAttempts.finish(db,expired.get("job_id"),expired.get("lease"),now,"failed","LEASE_EXHAUSTED");
            db.update("UPDATE os_work SET state='failed',lease='',error='LEASE_EXHAUSTED' WHERE job_id=?",expired.get("job_id"));
            EventWorkflows.finished(db,expired.get("job_id").toString(),"failed","LEASE_EXHAUSTED");
            WorkFailureAlerts.send(db,expired.get("job_id").toString(),"failed",now);
        }
        var job =
                db.one(
                        "SELECT * FROM os_work WHERE state IN ('queued','processing') AND"
                            + " available_at<=? ORDER BY created_at,job_id LIMIT 1"
                                + (db.postgres ? " FOR UPDATE SKIP LOCKED" : ""),
                        now);
        if (job == null) return null;
        WorkAttempts.finish(db,job.get("job_id"),job.get("lease"),now,"lease_expired","LEASE_EXPIRED");
        job.put("lease", id());
        job.put("attempts", ((Number) job.get("attempts")).intValue() + 1);
        db.update(
                "UPDATE os_work SET state='processing',lease=?,attempts=?,available_at=? WHERE"
                    + " job_id=?",
                job.get("lease"),
                job.get("attempts"),
                now + 120000,
                job.get("job_id"));
        WorkAttempts.start(db,job,now);
        return job;
    }

    static List<Map<String, Object>> list(Store.Db db, String actor) throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        return db.all(
                "SELECT " + COLUMNS + " FROM os_work ORDER BY created_at DESC,job_id LIMIT 100");
    }

    static Map<String,Object> detail(Store store, Store.Db db, String actor, String id) throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        var row = db.one("SELECT "+COLUMNS+",result FROM os_work WHERE job_id=?",id);
        if(row==null) throw new Failure(404,"Job not found");
        row.put("available_at_utc",java.time.Instant.ofEpochMilli(((Number)row.get("available_at")).longValue()).toString());
        String encrypted=row.remove("result").toString();
        row.put("receipt",map());
        row.put("receipt_status","not_available");
        if(!encrypted.isEmpty()) {
            try {
                var receipt=object(Outbox.decrypt(store.secret,encrypted).get("receipt"));
                var allowed=switch(row.get("kind").toString()) {
                    case "report.capture" -> Set.of("schedule_id","measurement_id","created");
                    case "workflow.run" -> Set.of("run_id","record_id","steps");
                    case "notification" -> Set.of("delivered");
                    case "approval.reminder" -> Set.of("delivered","duplicate");
                    case "connector.write" -> Set.of("action_id","action_revision","alias","http_status","bytes","sha256","acknowledged","correlation_id");
                    case "connector.read" -> Set.of("alias","revision","bytes","sha256");
                    case "migration.fetch" -> Set.of("migration_id","status","phase","staged_rows");
                    default -> Set.<String>of();
                };
                var safe=new LinkedHashMap<>(receipt);
                safe.keySet().retainAll(allowed);
                row.put("receipt",safe);
                row.put("receipt_status","available");
            } catch(Exception invalid) {
                row.put("receipt_status","unreadable");
            }
        }
        return row;
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
            db.update("DELETE FROM os_work_attempt WHERE NOT EXISTS (SELECT 1 FROM os_work WHERE os_work.job_id=os_work_attempt.job_id)");
            db.update("DELETE FROM os_connector_resolution WHERE NOT EXISTS (SELECT 1 FROM os_work WHERE os_work.job_id=os_connector_resolution.job_id)");
        } else {
            var row = db.one("SELECT state,kind,attempts FROM os_work WHERE job_id=?", id);
            if (row == null) throw new Failure(404, "Job not found");
            require(
                    Set.of("queued", "failed").contains(row.get("state")),
                    "Only queued or failed work can be controlled");
            boolean retry = operation.equals("retry");
            require(!retry || !row.get("kind").equals("connector.write") || ((Number)row.get("attempts")).intValue()==0,"Attempted connector writes cannot be replayed; reconcile the external outcome");
            db.update(
                    "UPDATE os_work SET state=?,attempts=0,available_at=?,error='',envelope="
                            + (retry ? "envelope" : "''")
                            + " WHERE job_id=?",
                    retry ? "queued" : "cancelled",
                    System.currentTimeMillis(),
                    id);
        }
        if(!operation.equals("cleanup"))EventWorkflows.finished(db,id,operation.equals("retry")?"queued":"cancelled","");
        SecurityEvents.record(db, actor, actor, "work." + operation, map("job_id", id));
    }
}
