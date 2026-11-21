package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

final class ConnectorFailover {
  private ConnectorFailover() {}

  static void claim(Store store, Store.Db db, Map<String, Object> job) throws Exception {
    String worker = store.environment.getProperty("CONNECTOR_WORKER_ID", "");
    if (worker.isBlank()
        || job == null
        || !"connector.write".equals(job.get("kind"))
        || !Integer.valueOf(1).equals(job.get("attempts"))) return;
    if (!get(db, "job-history-contract", job.get("job_id").toString()).get("revision").equals(0))
      db.update(
          "INSERT INTO os_connector_worker(job_id,executor) VALUES(?,?) ON CONFLICT(job_id) DO"
              + " NOTHING",
          job.get("job_id"),
          WorkJobs.text(worker, 120));
  }

  static boolean recover(Store store, long now) throws Exception {
    String worker = store.environment.getProperty("CONNECTOR_WORKER_ID", "");
    if (worker.isBlank()) return false;
    try (var db = store.open()) {
      boolean worked = recoverNext(store, db, worker, now);
      db.connection.commit();
      return worked;
    }
  }

  private static boolean recoverNext(Store store, Store.Db db, String worker, long now)
      throws Exception {
    lock(db);
    var job =
        db.one(
            "SELECT w.job_id,w.owner_id,w.auth_revision FROM os_work w JOIN os_connector_worker j"
                + " ON j.job_id=w.job_id WHERE w.state='failed' AND j.executor<>? AND"
                + " j.recovered_by='' AND j.retry_at<=? ORDER BY j.retry_at,w.job_id LIMIT 1",
            worker,
            now);
    if (job == null) return false;
    String id = job.get("job_id").toString();
    if (db.count("SELECT COUNT(*) FROM os_connector_resolution WHERE job_id=?", id) != 0) {
      db.update("UPDATE os_connector_worker SET recovered_by='manual' WHERE job_id=?", id);
      return true;
    }
    var point = db.connection.setSavepoint();
    try {
      require(
          EnterpriseData.currentAdmin(db, job.get("owner_id"), job.get("auth_revision")),
          "Job authority changed");
      db.viewerId = job.get("owner_id").toString();
      db.viewerRole = "admin";
      ProviderHistory.run(store, db, "verify_effect", map("job_id", id));
      db.update(
          "UPDATE os_connector_worker SET recovered_by=? WHERE job_id=?",
          WorkJobs.text(worker, 120),
          id);
    } catch (Exception unavailable) {
      db.connection.rollback(point);
      db.update("UPDATE os_connector_worker SET retry_at=? WHERE job_id=?", now + 60000, id);
    }
    return true;
  }

  static Object evidence(Store.Db db, String id) throws Exception {
    var contract = definition(db, "job-history-contract", id);
    return map(
        "contract",
        contract,
        "worker",
        db.one("SELECT executor,recovered_by,retry_at FROM os_connector_worker WHERE job_id=?", id),
        "evidence",
        get(db, "job-history-evidence", id),
        "job",
        ConnectorWrites.reconciliation(db, id));
  }
}
