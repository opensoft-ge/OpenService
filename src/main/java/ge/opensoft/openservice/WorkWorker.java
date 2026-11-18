package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
final class WorkWorker {
    private final Store store;

    WorkWorker(Store store) {
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${WORK_POLL_MS:1000}")
    void tick() {
        if (!store.environment.getProperty("WORK_ENABLED", Boolean.class, false)) return;
        try {
            runOne(System.currentTimeMillis());
            AttachmentScanning.runOne(store);
            try (var db = store.open()) {
                ServiceClocks.tick(db, System.currentTimeMillis());
                db.update(
                        "DELETE FROM os_notice WHERE acknowledged=1 AND created_at<?",
                        System.currentTimeMillis() - 30L * 86400000);
                NoticeTargets.cleanup(db);
                SupportConversations.cleanup(db);
                ReportMeasurements.cleanup(db);
                db.connection.commit();
            }
        } catch (Exception failure) {
            org.slf4j.LoggerFactory.getLogger(WorkWorker.class)
                    .warn("Platform worker unavailable; durable work remains pending");
        }
    }

    boolean runOne(long now) throws Exception {
        EventWorkflows.dispatch(store);
        ReportSchedules.dispatch(store);
        Map<String, Object> job;
        try (var db = store.open()) {
            job = WorkJobs.claim(db, now);
            db.connection.commit();
        }
        if (job == null) return false;
        return runClaimed(job, now);
    }

    boolean runClaimed(Map<String, Object> job, long now) throws Exception {
        try (var db = store.open()) {
            if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439,4)");
            var current =
                    db.one(
                            "SELECT * FROM os_work WHERE job_id=? AND lease=? AND"
                                    + " state='processing'"
                                    + (db.postgres ? " FOR UPDATE" : ""),
                            job.get("job_id"),
                            job.get("lease"));
            if (current != null) {
                var owner = db.one("SELECT * FROM os_user WHERE user_id=?", job.get("owner_id"));
                String state = "succeeded", error = "", result = "";
                boolean continuing = false;
                var savepoint = db.connection.setSavepoint();
                try {
                    if (owner == null
                            || ((Number) owner.get("active")).intValue() != 1
                            || !owner.get("role").equals("admin")
                            || ((Number) owner.get("auth_revision")).longValue()
                                    != ((Number) job.get("auth_revision")).longValue())
                        throw new Failure(403, "Job authority is no longer current");
                    db.viewerId = owner.get("user_id").toString();
                    db.viewerRole = "admin";
                    var payload = Outbox.decrypt(store.secret, job.get("envelope").toString());
                    Object output =
                            switch (job.get("kind").toString()) {
                                case "report.capture" -> ReportSchedules.execute(db,job,payload);
                                case "workflow.run" -> EventWorkflows.execute(db,job,payload);
                                case "notification" ->
                                        Notifications.deliver(
                                                db, job.get("job_id").toString(), payload, now);
                                case "connector.write" -> ConnectorWrites.execute(store,db,job,payload);
                                case "connector.read" -> Connectors.read(store, db, payload);
                                case "approval.reminder" -> ScheduledApprovalReminders.deliver(db,db.viewerId,payload);
                                case "migration.fetch" -> MigrationFetch.step(db,payload,store.migrationOrigins);
                                case "migration.step" ->
                                        TransferJobs.step(
                                                db,
                                                WorkJobs.text(payload.get("migration_id"), 32),
                                                WorkJobs.text(payload.get("token"), 4096),
                                                store.migrationOrigins);
                                default -> throw new Failure(400, "Unsupported job kind");
                            };
                    continuing=job.get("kind").equals("migration.fetch") && Boolean.TRUE.equals(object(output).get("continue"));
                    if(continuing)state="queued";
                    if(job.get("kind").equals("connector.write") && !Boolean.TRUE.equals(object(output).get("acknowledged"))){state="failed";error="REMOTE_OUTCOME_REQUIRES_REVIEW";}
                    // Keep bounded receipts only. Migration details remain in the migration
                    // journal.
                    result =
                            Outbox.encrypt(
                                    store.secret,
                                    JSON.writeValueAsString(
                                            map(
                                                    "completed",
                                                    !continuing,
                                                    "kind",
                                                    job.get("kind"),
                                                    "receipt",
                                                    job.get("kind").equals("migration.step")
                                                            ? map()
                                                            : output)));
                } catch (Exception failure) {
                    db.connection.rollback(savepoint);
                    continuing=false;
                    boolean permanent = failure instanceof Failure f && f.status < 500;
                    state =
                            permanent
                                    ? "cancelled"
                                    : ((Number) job.get("attempts")).intValue() >= 5
                                            ? "failed"
                                            : "queued";
                    error = permanent ? "AUTHORITY_OR_INPUT_UNAVAILABLE" : "EXECUTION_FAILED";
                    if(job.get("kind").equals("connector.write")){state="failed";error="RECONCILIATION_REQUIRED";}
                }
                boolean terminal = Set.of("succeeded", "cancelled").contains(state);
                db.update(
                        "UPDATE os_work SET state=?,attempts=?,error=?,result=?,envelope="
                                + (terminal ? "''" : "envelope")
                                + ",lease='',available_at=? WHERE job_id=? AND lease=?",
                        state,
                        continuing?0:job.get("attempts"),
                        error,
                        result,
                        now + (continuing?1000:Math.min(300000L, 1000L << ((Number) job.get("attempts")).intValue())),
                        job.get("job_id"),
                        job.get("lease"));
                WorkAttempts.finish(db,job.get("job_id"),job.get("lease"),Math.max(now,System.currentTimeMillis()),continuing?"continued":state.equals("queued")?"retry_scheduled":state,error);
                EventWorkflows.finished(db,job.get("job_id").toString(),state,error);
                WorkFailureAlerts.send(db,job.get("job_id").toString(),state,Math.max(now,System.currentTimeMillis()));
                db.connection.commit();
            }
        }
        return true;
    }
}
