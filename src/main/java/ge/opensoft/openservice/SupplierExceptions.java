package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

final class SupplierExceptions {
  private SupplierExceptions() {}

  static boolean resumeReady(Store store, Store.Db db) throws Exception {
    lock(db);
    String pending =
        db.postgres ? "definition::jsonb->>'state'" : "json_extract(definition,'$.state')";
    for (var row :
        db.all(
            "SELECT subject,definition,revision FROM os_enterprise_document WHERE"
                + " area='exception-run' AND "
                + pending
                + " IN ('review','waiting_for_contract') ORDER BY subject")) {
      var data = object(row.get("definition"));
      if (!data.containsKey("owner")) continue;
      String run = row.get("subject").toString();
      var review = definition(db, "workflow-run", run);
      if (!"complete".equals(review.get("state"))) continue;
      boolean signed =
          !SupplierContracts.terms(
                  db,
                  object(data.get("action")).get("supplier").toString(),
                  LocalDate.now(ZoneOffset.UTC).toString())
              .isEmpty();
      if (!signed && "waiting_for_contract".equals(data.get("state"))) continue;
      String previous = db.viewerId, role = db.viewerRole;
      try {
        if (!currentAdmin(db, data.get("owner"), data.get("owner_revision"))) {
          data.putAll(map("state", "authority_review", "authority_lost_at", now()));
          db.update(
              "UPDATE os_enterprise_document SET definition=?,revision=revision+1 WHERE"
                  + " area='exception-run' AND subject=? AND revision=?",
              JSON.writeValueAsString(data),
              run,
              row.get("revision"));
        } else {
          db.viewerId = data.get("owner").toString();
          db.viewerRole = "admin";
          run(store, db, "advance_exception", map("new_id", run));
        }
      } finally {
        db.viewerId = previous;
        db.viewerRole = role;
      }
      return true;
    }
    return false;
  }

  static Object run(Store store, Store.Db db, String op, Map<String, Object> b) throws Exception {
    if (op.equals("exception_requisition")) {
      String request = id(b, "record_id");
      var parent = Service.readRecord(db, "sc_request", request);
      require(
          !Set.of("complete", "cancelled").contains(object(parent.get("data")).get("state")),
          "Choose an open purchase request");
      require(
          get(db, "exception-started", request).get("revision").equals(0),
          "This requisition has entered review");
      var lines = object(b.get("manifest"));
      require(!lines.isEmpty() && lines.size() <= 32, "Provide 1–32 requisition lines");
      for (var line : lines.entrySet()) {
        WorkJobs.text(line.getKey(), 120);
        object(line.getValue());
      }
      return put(
          db,
          "exception-requisition",
          request,
          b.get("revision"),
          map("supplier", text(b, "supplier", 160), "lines", lines));
    }
    if (op.equals("exception_design")) {
      var routes = object(b.get("bindings"));
      keys(routes, "low", "medium", "high");
      var frozen = map();
      for (var route : routes.entrySet()) {
        String design = EnterpriseData.id(route.getValue());
        var release = get(db, "decision-release", design);
        definition(db, "decision-release", design);
        frozen.put(
            route.getKey(),
            map(
                "design",
                design,
                "version",
                release.get("revision"),
                "release",
                release.get("definition")));
      }
      String action = id(b, "action_id");
      return put(
          db,
          "exception-design",
          id(b, "design_id"),
          b.get("revision"),
          map(
              "routes",
              frozen,
              "action_id",
              action,
              "action",
              definition(db, "purchase-action", action),
              "action_version",
              get(db, "purchase-action", action).get("revision")));
    }
    String id = id(b, "new_id");
    var row = get(db, "exception-run", id);
    var data = object(row.get("definition"));
    if (op.equals("start_exception")) {
      String design = id(b, "design_id"), employee = id(b, "user_id"), source = id(b, "record_id");
      var spec = definition(db, "exception-design", design);
      var route =
          object(object(spec.get("routes")).get(choice(b, "risk", "low", "medium", "high")));
      var inputs = map();
      var parent = Service.readRecord(db, "sc_request", source);
      require(
          !Set.of("complete", "cancelled").contains(object(parent.get("data")).get("state")),
          "Choose an open purchase request");
      var purchase = definition(db, "exception-requisition", source);
      save(db, "exception-started", source, 0, map("run", id));
      var total = java.math.BigDecimal.ZERO;
      var action = object(spec.get("action"));
      require(
          action.get("supplier").equals(purchase.get("supplier")),
          "Order and action require the same supplier");
      for (var line : ProjectSpend.lines(purchase).entrySet()) {
        var input = new LinkedHashMap<>(object(line.getValue()));
        input.putIfAbsent("model", line.getKey());
        var fields = object(action.get("bindings"));
        total =
            total.add(
                money(input.get(fields.get("unit_cost")))
                    .multiply(
                        java.math.BigDecimal.valueOf(
                            integer(input.get(fields.get("quantity")), 1, 10000))));
        inputs.put(line.getKey(), map("input", input, "state", "reserved"));
      }
      require(!inputs.isEmpty() && inputs.size() <= 32, "Select an order with 1–32 lines");
      require(
          total.compareTo(money(b.get("amount"))) == 0,
          "Reviewed amount must equal the requisition total");
      DecisionFlows.start(
          db,
          route.get("design").toString(),
          route.get("version"),
          object(route.get("release")),
          id,
          employee,
          b);
      return put(
          db,
          "exception-run",
          id,
          0,
          map(
              "design",
              design,
              "owner",
              db.viewerId,
              "owner_revision",
              AgentPlans.account(db, db.viewerId).get("auth_revision"),
              "design_version",
              get(db, "exception-design", design).get("revision"),
              "source",
              source,
              "lines",
              inputs,
              "action_id",
              spec.get("action_id"),
              "action_version",
              spec.get("action_version"),
              "action",
              action,
              "state",
              "review"));
    }
    require(!data.isEmpty(), "Unknown supplier exception");
    var lines = object(data.get("lines"));
    var workflow = get(db, "workflow-run", id);
    var review = object(workflow.get("definition"));
    if (op.equals("cancel_exception")) {
      if ("cancelled".equals(data.get("state"))) return row;
      String owner = DutyRosters.responder(db, b.get("owner_id")), reason = text(b, "note", 1000);
      if ("running".equals(review.get("state")))
        WorkflowStudio.command(
            store,
            db,
            "stop_run",
            map("run_id", id, "revision", workflow.get("revision"), "note", reason));
      for (Object raw : lines.values()) {
        var line = object(raw);
        if (line.containsKey("order")) {
          var task =
              Requests.create(
                  db,
                  actor(db),
                  map(
                      "short_description",
                      "Review cancellation of purchase order",
                      "description",
                      line.get("order") + " — " + reason));
          var assigned =
              Service.update(
                  db,
                  "sc_request",
                  task,
                  map("assigned_to", Service.account(db, owner).get("name")),
                  actor(db),
                  db.viewerRole,
                  task.get("revision").toString());
          line.putAll(map("state", "reversal_review", "task", assigned.get("sys_id")));
        } else line.put("state", "released");
      }
      data.putAll(map("state", "cancelled", "reason", reason));
    } else {
      if (Set.of("cancelled", "ordered").contains(data.get("state"))) return row;
      require("complete".equals(review.get("state")), "Complete the selected review subflow first");
      var terms =
          SupplierContracts.terms(
              db,
              object(data.get("action")).get("supplier").toString(),
              LocalDate.now(ZoneOffset.UTC).toString());
      if (terms.isEmpty()) {
        data.put("state", "waiting_for_contract");
      } else {
        data.put("state", "ordered");
        for (var entry : lines.entrySet()) {
          var line = object(entry.getValue());
          if (line.containsKey("order")) continue;
          var point = db.connection.setSavepoint();
          try {
            String order =
                PurchasingActions.order(
                    db,
                    id,
                    entry.getKey(),
                    map(
                        "action_id",
                        data.get("action_id"),
                        "action",
                        data.get("action"),
                        "input",
                        line.get("input")));
            var draft = definition(db, "purchase", order);
            SupplierContracts.validateOrder(db, draft, LocalDate.now(ZoneOffset.UTC).toString());
            put(db, "purchase", order, get(db, "purchase", order).get("revision"), draft);
            line.putAll(
                map("state", "ordered", "order", order, "terms_version", terms.get("version")));
            line.remove("error");
          } catch (Failure failure) {
            db.connection.rollback(point);
            data.put("state", "needs_retry");
            line.putAll(map("state", "failed", "error", failure.getMessage()));
            break;
          } finally {
            db.connection.releaseSavepoint(point);
          }
        }
      }
    }
    return put(db, "exception-run", id, row.get("revision"), data);
  }
}
