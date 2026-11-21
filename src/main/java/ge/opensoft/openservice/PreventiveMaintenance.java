package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

final class PreventiveMaintenance {
  private PreventiveMaintenance() {}

  static Object command(Store.Db db, String op, Map<String, Object> b) throws Exception {
    String id = id(b, "plan_id");
    if (op.equals("maintenance_plan")) {
      String parent = id(b, "case_id"), group = id(b, "group_id"), depot = id(b, "stockroom_id");
      Service.readRecord(db, "customer_case", parent);
      require(
          db.count("SELECT COUNT(*) FROM os_customer_case WHERE record_id=?", parent) > 0,
          "Choose an entitled site case");
      CatalogueRouting.group(db, group);
      AssetCustody.stockroom(db, depot, true);
      var plan =
          map(
              "case",
              parent,
              "group",
              group,
              "depot",
              depot,
              "model",
              text(b, "model", 120),
              "site",
              text(b, "location", 120),
              "skills",
              FieldPlanning.skills(b.get("skills")),
              "technician",
              DutyRosters.responder(db, b.get("technician_id")),
              "next",
              ChangeScheduling.instant(b.get("start")),
              "days",
              integer(b.get("days"), 1, 366),
              "minutes",
              integer(b.get("minutes"), 15, 480),
              "owner",
              db.viewerId);
      return put(db, "maintenance-plan", id, b.get("revision"), plan);
    }
    definition(db, "maintenance-plan", id);
    String occurrence = key(id, ChangeScheduling.instant(b.get("start")));
    var job = definition(db, "maintenance-job", occurrence);
    String task = job.get("task").toString();
    var work = FieldPlanning.task(db, task);
    FieldDispatch.team(db, work);
    if (op.equals("maintenance_status"))
      return map(
          "job",
          job,
          "dispatch",
          FieldDispatch.current(db, task),
          "work",
          work,
          "transfer",
          get(db, "depot-transfer", key(task, job.getOrDefault("asset", ""))));
    var current = get(db, "maintenance-job", occurrence);
    require(!job.containsKey("asset"), "A part has already been reserved");
    var stock =
        object(
            DepotTransfers.source(
                db,
                map(
                    "task_id",
                    task,
                    "model",
                    job.get("model"),
                    "entries",
                    List.of(job.get("depot")),
                    "start",
                    b.get("new_start"),
                    "minutes",
                    job.get("minutes"),
                    "days",
                    3)));
    if (Boolean.TRUE.equals(stock.get("shortage"))) {
      job.putAll(
          map(
              "state",
              "shortage",
              "original_commitment",
              b.get("start"),
              "alternatives",
              stock.get("alternatives")));
    } else {
      String start = text(b, "new_start", 40);
      String parent = job.get("case").toString();
      FieldDispatch.command(
          db,
          "customer_case",
          parent,
          task,
          map(
              "operation",
              "assign",
              "technician_id",
              job.get("technician"),
              "location",
              job.get("site"),
              "start",
              start,
              "end",
              Instant.ofEpochMilli(
                      ChangeScheduling.instant(start)
                          + 60000L * ((Number) job.get("minutes")).longValue())
                  .toString(),
              "revision",
              FieldDispatch.current(db, task).get("revision"),
              "record_revision",
              Service.readRecord(db, "customer_case", parent).get("revision"),
              "task_revision",
              work.get("revision")));
      var part = objects(stock.get("stock")).getFirst();
      DepotTransfers.command(
          db,
          "reserve_serial",
          map(
              "task_id",
              task,
              "asset_id",
              part.get("asset_id"),
              "stockroom_id",
              job.get("depot"),
              "revision",
              0));
      job.putAll(map("state", "reserved", "asset", part.get("asset_id"), "scheduled", start));
    }
    return put(db, "maintenance-job", occurrence, current.get("revision"), job);
  }

  static void tick(Store.Db db, long now) throws Exception {
    String user = db.viewerId, role = db.viewerRole;
    try {
      for (var row : list(db, "maintenance-plan")) {
        var plan = object(row.get("definition"));
        long start = ((Number) plan.get("next")).longValue();
        if (start > now + 86400000L) continue;
        String owner = plan.get("owner").toString();
        if (db.count(
                "SELECT COUNT(*) FROM os_user WHERE user_id=? AND active=1 AND role='admin' AND"
                    + " password_reset_required=0",
                owner)
            == 0) continue;
        db.viewerId = owner;
        db.viewerRole = "admin";
        var point = db.connection.setSavepoint();
        try {
          String id = row.get("subject").toString(),
              occurrence = key(id, start),
              parent = plan.get("case").toString();
          if (get(db, "maintenance-job", occurrence).get("revision").equals(0)) {
            var gate = WorkTasks.plan(db, parent);
            if (((Number) gate.get("revision")).intValue() == 0) {
              WorkTasks.command(
                  db,
                  "customer_case",
                  parent,
                  map(
                      "operation",
                      "configure",
                      "close_at",
                      "closed",
                      "revision",
                      0,
                      "record_revision",
                      Service.readRecord(db, "customer_case", parent).get("revision")));
              gate = WorkTasks.plan(db, parent);
            }
            WorkTasks.command(
                db,
                "customer_case",
                parent,
                map(
                    "operation",
                    "create",
                    "group_id",
                    plan.get("group"),
                    "summary",
                    "Preventive maintenance at " + plan.get("site"),
                    "details",
                    "Required part: " + plan.get("model"),
                    "required",
                    true,
                    "predecessors",
                    List.of(),
                    "revision",
                    gate.get("revision"),
                    "record_revision",
                    Service.readRecord(db, "customer_case", parent).get("revision")));
            String task =
                db.one(
                        "SELECT task_id FROM os_case_task WHERE parent_id=? ORDER BY ordinal DESC"
                            + " LIMIT 1",
                        parent)
                    .get("task_id")
                    .toString();
            FieldVisits.command(
                db,
                "customer_case",
                parent,
                task,
                map(
                    "operation",
                    "configure",
                    "record_revision",
                    Service.readRecord(db, "customer_case", parent).get("revision"),
                    "task_revision",
                    WorkTasks.task(db, parent, task).get("revision"),
                    "revision",
                    0,
                    "checklist",
                    List.of("Verify serialized replacement"),
                    "evidence_required",
                    false));
            FieldPlanning.command(
                db,
                task,
                map("operation", "requirements", "skills", plan.get("skills"), "revision", 0));
            var job = new LinkedHashMap<>(plan);
            job.putAll(map("task", task, "state", "needs_parts", "commitment", start));
            save(db, "maintenance-job", occurrence, 0, job);
            var reservation = db.connection.setSavepoint();
            try {
              String scheduled = Instant.ofEpochMilli(start).toString();
              command(
                  db,
                  "maintenance_dispatch",
                  map("plan_id", id, "start", scheduled, "new_start", scheduled));
            } catch (Failure unavailable) {
              db.connection.rollback(reservation);
              job.put("reason", unavailable.getMessage());
              save(db, "maintenance-job", occurrence, 1, job);
            } finally {
              db.connection.releaseSavepoint(reservation);
            }
          }
          plan.remove("error");
          plan.put("next", start + 86400000L * ((Number) plan.get("days")).longValue());
        } catch (Failure failure) {
          db.connection.rollback(point);
          plan.put("error", failure.getMessage());
        } finally {
          db.connection.releaseSavepoint(point);
        }
        if (!plan.equals(
            object(get(db, "maintenance-plan", row.get("subject").toString()).get("definition"))))
          save(db, "maintenance-plan", row.get("subject").toString(), row.get("revision"), plan);
      }
    } finally {
      db.viewerId = user;
      db.viewerRole = role;
    }
  }
}
