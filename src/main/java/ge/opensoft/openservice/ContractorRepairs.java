package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class ContractorRepairs {
  private ContractorRepairs() {}

  static Object run(Store.Db db, String op, Map<String, Object> b) throws Exception {
    if (op.equals("contract_company")) {
      String group = id(b, "group_id"), dispatcher = id(b, "user_id");
      CatalogueRouting.group(db, group);
      require(member(db, group, dispatcher), "Dispatcher must belong to company");
      return put(
          db,
          "contract-company",
          group,
          b.get("revision"),
          map("dispatcher", dispatcher, "approved", b.get("active")));
    }
    if (op.equals("contract_queue")) {
      var queue = new ArrayList<>();
      for (var row : list(db, "contract-repair")) {
        var job = object(row.get("definition"));
        boolean offered = "offered".equals(job.get("state"));
        for (Object company : (List<?>) job.get("companies")) {
          if (!offered && !company.equals(job.get("company"))) continue;
          var enrollment = definition(db, "contract-company", company.toString());
          if (Boolean.TRUE.equals(enrollment.get("approved"))
              && member(db, company.toString(), db.viewerId)
              && (db.viewerId.equals(enrollment.get("dispatcher"))
                  || db.viewerId.equals(job.get("tech"))))
            queue.add(
                map(
                    "task",
                    row.get("subject"),
                    "state",
                    job.get("state"),
                    "revision",
                    row.get("revision"),
                    "expires",
                    job.get("expires")));
        }
      }
      return queue;
    }
    String task = id(b, "task_id");
    var row = get(db, "contract-repair", task);
    var job = object(row.get("definition"));
    if (op.equals("offer_repair")) {
      String parent = id(b, "case_id");
      Service.readRecord(db, "customer_case", parent);
      var nativeTask = WorkTasks.task(db, parent, task);
      var entitled =
          db.one(
              "SELECT user_id,entitlement_id,account_id FROM os_customer_case WHERE record_id=?",
              parent);
      require(entitled != null, "Entitled customer repair required");
      var coverage =
          db.one(
              "SELECT active,starts,ends FROM os_customer_entitlement WHERE entitlement_id=?",
              entitled.get("entitlement_id"));
      long now = System.currentTimeMillis();
      require(
          coverage != null
              && coverage.get("active").equals(1)
              && ((Number) coverage.get("starts")).longValue() <= now
              && ((Number) coverage.get("ends")).longValue() >= now,
          "Current entitlement required");
      String returned = id(b, "asset_id");
      var install = definition(db, "installation-account", returned);
      require(
          entitled.get("account_id").equals(install.get("account"))
              && entitled
                  .get("user_id")
                  .equals(AssetCustody.latest(db, returned).get("assignee_id")),
          "Choose equipment installed at this customer");
      var allocated = new ArrayList<>();
      for (var transfer : list(db, "depot-transfer")) {
        var part = object(transfer.get("definition"));
        if (task.equals(part.get("task"))
            && Set.of("reserved", "picked_up").contains(part.get("state")))
          allocated.add(part.get("asset"));
      }
      String state = Objects.toString(job.get("state"), "new");
      require(
          Set.of("new", "offered", "accepted", "assigned").contains(state),
          "Repair already submitted");
      var companies = new TreeSet<String>();
      for (Object entry : batch(b.get("entries"), 20)) {
        String group = EnterpriseData.id(entry);
        require(
            Boolean.TRUE.equals(definition(db, "contract-company", group).get("approved")),
            "Unapproved company");
        companies.add(group);
      }
      long expires = ChangeScheduling.instant(b.get("expires"));
      require(
          !companies.isEmpty() && expires > System.currentTimeMillis(),
          "Choose companies and a future deadline");
      Object parentRevision = Service.readRecord(db, "customer_case", parent).get("revision");
      if (job.isEmpty()) {
        WorkTasks.command(
            db,
            "customer_case",
            parent,
            map(
                "operation",
                "claim",
                "task_id",
                task,
                "record_revision",
                parentRevision,
                "revision",
                nativeTask.get("revision")));
        nativeTask = WorkTasks.task(db, parent, task);
      } else {
        ProblemInvestigations.revision(job.get("task_revision"), nativeTask.get("revision"));
      }
      var history = new ArrayList<>(objects(job.getOrDefault("offers", List.of())));
      history.add(
          map(
              "companies",
              companies,
              "at",
              now(),
              "expires",
              expires,
              "superseded_company",
              job.get("company"),
              "superseded_tech",
              job.get("tech")));
      job =
          map(
              "offers",
              history,
              "companies",
              companies,
              "account",
              entitled.get("account_id"),
              "customer",
              entitled.get("user_id"),
              "entitlement",
              entitled.get("entitlement_id"),
              "task_revision",
              nativeTask.get("revision"),
              "case",
              parent,
              "returned_asset",
              returned,
              "parts",
              allocated,
              "instructions",
              text(b, "note", 2000),
              "expires",
              expires,
              "state",
              "offered",
              "offered_by",
              db.viewerId);
    } else {
      require(!job.isEmpty(), "Unknown repair");
      boolean
          customer =
              db.viewerId.equals(job.get("customer"))
                  && db.count(
                          "SELECT COUNT(*) FROM os_customer_contact WHERE user_id=? AND"
                              + " account_id=? AND active=1",
                          db.viewerId,
                          job.get("account"))
                      == 1,
          admin = db.viewerRole.equals("admin");
      if (op.equals("accept_repair")) {
        String group = id(b, "group_id");
        var company = definition(db, "contract-company", group);
        require(
            Boolean.TRUE.equals(company.get("approved"))
                && ((List<?>) job.get("companies")).contains(group)
                && db.viewerId.equals(company.get("dispatcher"))
                && member(db, group, db.viewerId),
            "Assigned company dispatcher required");
        require(
            "offered".equals(job.get("state"))
                && System.currentTimeMillis() < ((Number) job.get("expires")).longValue(),
            "Offer closed");
        job.putAll(map("company", group, "state", "accepted"));
      } else {
        String group = Objects.toString(job.get("company"), "");
        var company = object(get(db, "contract-company", group).get("definition"));
        boolean enrolled =
            Boolean.TRUE.equals(company.get("approved")) && member(db, group, db.viewerId);
        boolean dispatcher = enrolled && db.viewerId.equals(company.get("dispatcher"));
        boolean tech = enrolled && db.viewerId.equals(job.get("tech"));
        if (op.equals("repair_packet")) {
          require(admin || tech || dispatcher || customer, "Repair unavailable");
          return projection(row, job);
        }
        if (op.equals("assign_repair")) {
          String technician = id(b, "technician_id");
          require(
              dispatcher && member(db, group, technician) && "accepted".equals(job.get("state")),
              "Assign an enrolled technician");
          job.putAll(map("state", "assigned", "tech", technician));
        } else if (op.equals("repair_sync")) {
          require(
              tech && job.get("returned_asset").equals(b.get("asset_id")),
              "Assigned technician and expected serial required");
          String packet = id(b, "new_id");
          var work =
              map(
                  "packet",
                  packet,
                  "labor",
                  money(b.get("amount")).toPlainString(),
                  "returned_asset",
                  id(b, "asset_id"),
                  "report",
                  text(b, "note", 2000));
          if (job.containsKey("work")) {
            require(work.equals(job.get("work")), "Conflicting offline report");
            return projection(row, job);
          }
          require("assigned".equals(job.get("state")), "Repair is not assigned");
          job.putAll(map("work", work, "state", "awaiting_signature"));
        } else if (op.equals("sign_repair")) {
          require(
              customer && "awaiting_signature".equals(job.get("state")),
              "Customer signature required");
          job.putAll(
              map(
                  "signed_by",
                  db.viewerId,
                  "signature",
                  text(b, "reference", 400),
                  "state",
                  "signed"));
        } else {
          var work = object(job.getOrDefault("work", map()));
          if ("settled".equals(job.get("state"))) {
            require(
                job.get("invoice").equals(b.get("reference")), "Different invoice already matched");
            return projection(row, job);
          }
          require(admin && "signed".equals(job.get("state")), "Signed repair required");
          var movement = AssetCustody.latest(db, work.get("returned_asset").toString());
          require(
              "in_stock".equals(movement.get("state"))
                  && !movement.get("stockroom_id").toString().isBlank(),
              "Receive the returned serial at the depot");
          var returned =
              definition(db, "installation-account", work.get("returned_asset").toString());
          require(
              task.equals(returned.get("returned_for")) && "returned".equals(returned.get("state")),
              "Verify receipt against this repair");
          String invoice = text(b, "reference", 160);
          String parent = job.get("case").toString();
          WorkTasks.command(
              db,
              "customer_case",
              parent,
              map(
                  "operation",
                  "complete",
                  "task_id",
                  task,
                  "record_revision",
                  Service.readRecord(db, "customer_case", parent).get("revision"),
                  "revision",
                  job.get("task_revision"),
                  "outcome",
                  work.get("report")));
          save(
              db,
              "contract-invoice",
              key(group, invoice),
              0,
              map(
                  "task",
                  task,
                  "amount",
                  work.get("labor"),
                  "return_revision",
                  movement.get("revision")));
          job.putAll(map("invoice", invoice, "state", "settled"));
        }
      }
    }
    var saved = put(db, "contract-repair", task, b.get("revision"), job);
    return projection(saved, job);
  }

  static boolean member(Store.Db db, String group, String person) throws Exception {
    return db.count(
            "SELECT COUNT(*) FROM os_group_member WHERE group_id=? AND user_id=?", group, person)
        > 0;
  }

  static Object projection(Map<String, Object> row, Map<String, Object> job) {
    var result = new LinkedHashMap<>(job);
    result.remove("companies");
    result.remove("offers");
    return map("revision", row.get("revision"), "job", result);
  }
}
