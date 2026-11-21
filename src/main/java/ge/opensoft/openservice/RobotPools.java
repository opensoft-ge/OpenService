package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class RobotPools {
  private RobotPools() {}

  static Object run(Store.Db db, String op, Map<String, Object> b) throws Exception {
    String pool = id(b, "pool_id");
    if (op.equals("robot_pool")) {
      String group = id(b, "group_id");
      CatalogueRouting.group(db, group);
      return DeliveryWorkspace.put(db, "robot-pool", pool, b.get("revision"), map("group", group));
    }
    var spec = definition(db, "robot-pool", pool);
    if (op.equals("claim_robot_job")) {
      checkWorker(db, spec);
      long now = System.currentTimeMillis();
      Map<String, Object> available = null;
      for (var row : list(db, "permit-robot-job")) {
        var job = object(row.get("definition"));
        if (!pool.equals(job.get("pool"))) continue;
        boolean leased = "leased".equals(job.get("state"));
        if (leased && ((Number) job.get("until")).longValue() > now) {
          if (db.viewerId.equals(job.get("worker"))) return ticket(job);
        } else if (available == null && (leased || "pending".equals(job.get("state")))) {
          available = row;
        }
      }
      if (available != null) {
        var job = object(available.get("definition"));
        job.putAll(
            map(
                "state",
                "leased",
                "worker",
                db.viewerId,
                "lease",
                Values.id(),
                "until",
                now + 120000,
                "attempts",
                ((Number) job.get("attempts")).intValue() + 1));
        save(db, "permit-robot-job", job.get("job").toString(), available.get("revision"), job);
        return ticket(job);
      }
      return map("state", "idle");
    }
    String jobId = id(b, "job_id");
    var row = get(db, "permit-robot-job", jobId);
    var job = object(row.get("definition"));
    if (op.equals("queue_robot_permit")) {
      var payload = IndustryCases.details(db, "permit", object(b.get("manifest")));
      String reference = text(b, "reference", 120);
      definition(db, "case-program", "permit");
      if (job.isEmpty()) {
        job =
            map(
                "job",
                jobId,
                "pool",
                pool,
                "reference",
                reference,
                "owner",
                db.viewerId,
                "manifest",
                payload,
                "owner_auth_revision",
                db.one("SELECT auth_revision FROM os_user WHERE user_id=?",db.viewerId).get("auth_revision"),
                "state",
                "pending",
                "attempts",
                0);
        save(db, "permit-robot-job", jobId, 0, job);
      } else
        require(
            db.viewerId.equals(job.get("owner"))
                && pool.equals(job.get("pool"))
                && reference.equals(job.get("reference"))
                && payload.equals(job.get("manifest")),
            "Job reference conflict");
      return status(job);
    }
    if (!pool.equals(job.get("pool"))) throw new Failure(404, "Unknown robot job");
    if (op.equals("robot_job_status") || op.equals("cancel_robot_job")) {
      if (!db.viewerId.equals(job.get("owner"))) throw new Failure(404, "Unknown robot job");
      if (op.equals("cancel_robot_job") && Set.of("pending", "leased").contains(job.get("state"))) {
        job.put("state", "cancelled");
        save(db, "permit-robot-job", jobId, row.get("revision"), job);
      }
      return status(job);
    }
    checkWorker(db, spec);
    require(
        db.viewerId.equals(job.get("worker")) && id(b, "lease_id").equals(job.get("lease")),
        "This worker does not own the lease");
    if ("done".equals(job.get("state"))) return status(job);
    require(
        "leased".equals(job.get("state"))
            && ((Number) job.get("until")).longValue() > System.currentTimeMillis(),
        "The lease is no longer active");
    String worker = db.viewerId, role = db.viewerRole;
    var owner = Service.account(db, job.get("owner").toString());
    var checkpoint = db.connection.setSavepoint();
    try {
      require(db.count("SELECT COUNT(*) FROM os_user WHERE user_id=? AND auth_revision=? AND active=1 AND password_reset_required=0",
          job.get("owner"),job.get("owner_auth_revision"))==1,"Requester authority has changed");
      db.viewerId = job.get("owner").toString();
      db.viewerRole = owner.get("role").toString();
      var receipt =
          object(
              IndustryCases.run(
                  db,
                  "file_industry_case",
                  map(
                      "kind",
                      "permit",
                      "new_id",
                      jobId,
                      "reference",
                      job.get("reference"),
                      "manifest",
                      job.get("manifest"))));
      job.putAll(map("state", "done", "case", object(receipt.get("definition")).get("case")));
    } catch (Failure failure) {
      db.connection.rollback(checkpoint);
      job.putAll(map("state", "review", "reason", failure.getMessage()));
    } finally {
      db.viewerId = worker;
      db.viewerRole = role;
      db.connection.releaseSavepoint(checkpoint);
    }
    save(db, "permit-robot-job", jobId, row.get("revision"), job);
    return status(job);
  }

  private static void checkWorker(Store.Db db, Map<String, Object> spec) throws Exception {
    if (!IndustryCases.member(db, spec.get("group")))
      throw new Failure(403, "Robot pool membership required");
  }

  private static Map<String, Object> ticket(Map<String, Object> job) {
    return map(
        "job",
        job.get("job"),
        "state",
        "leased",
        "lease",
        job.get("lease"),
        "until",
        job.get("until"));
  }

  private static Map<String, Object> status(Map<String, Object> job) {
    var result = new LinkedHashMap<>(job);
    result.keySet().removeAll(Set.of("lease", "manifest", "owner_auth_revision"));
    return result;
  }
}
