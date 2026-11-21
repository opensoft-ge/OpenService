package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class StoreOpening {
  private StoreOpening() {}

  static Object run(Store.Db db, String op, Map<String, Object> b) throws Exception {
    String site = id(b, "site_id");
    if (op.equals("store_opening_plan")) {
      String storeTeam = id(b, "group_id"), safety = id(b, "safety_group_id");
      CatalogueRouting.group(db, storeTeam);
      CatalogueRouting.group(db, safety);
      var checks = new LinkedHashSet<String>();
      for (Object item : batch(b.get("entries"), 30))
        require(checks.add(WorkJobs.text(item, 100)), "Use distinct checklist items");
      require(!checks.isEmpty(), "Supply opening checks");
      return put(
          db,
          "store-opening-plan",
          site,
          b.get("revision"),
          map(
              "title",
              text(b, "title", 120),
              "store_team",
              storeTeam,
              "safety_team",
              safety,
              "checks",
              List.copyOf(checks)));
    }
    String day = ProjectDelivery.date(b.get("effective")), shift = key(site, day);
    var row = get(db, "store-opening", shift);
    var data = object(row.get("definition"));
    if (op.equals("start_store_opening") && data.isEmpty()) {
      var plan = get(db, "store-opening-plan", site);
      var rules = definition(db, "store-opening-plan", site);
      require(IndustryCases.member(db, rules.get("store_team")), "Store team required");
      var checks = new LinkedHashMap<String, Object>();
      for (Object check : (List<?>) rules.get("checks"))
        checks.put(check.toString(), map("state", "pending"));
      return put(
          db,
          "store-opening",
          shift,
          0,
          map(
              "site",
              site,
              "day",
              day,
              "store_team",
              rules.get("store_team"),
              "safety_team",
              rules.get("safety_team"),
              "plan_revision",
              plan.get("revision"),
              "checks",
              checks,
              "state",
              "pending",
              "history",
              List.of()));
    }
    boolean storeTeam = IndustryCases.member(db, data.get("store_team"));
    boolean safetyTeam = IndustryCases.member(db, data.get("safety_team"));
    if (!storeTeam && !safetyTeam) throw new Failure(404, "Store opening unavailable");
    if (op.equals("store_opening_status") || op.equals("start_store_opening")) return row;
    boolean verifying = op.equals("verify_store_hazard");
    require(verifying ? safetyTeam : storeTeam, "Assigned team required");
    String check = text(b, "check_name", 100),
        reference = id(b, "action_id"),
        evidence = text(b, "evidence", 800);
    var checks = object(data.get("checks"));
    require(checks.containsKey(check), "Choose a check on this opening");
    var item = object(checks.get(check));
    String decision =
        verifying
            ? choice(b, "decision", "release", "reject")
            : op.equals("record_store_check") ? choice(b, "decision", "pass", "hazard") : "correct";
    var action =
        map("check", check, "decision", decision, "actor", db.viewerId, "evidence", evidence);
    String receipt = key(shift, reference);
    var existing = object(get(db, "store-opening-action", receipt).get("definition"));
    if (!existing.isEmpty()) {
      require(existing.equals(action), "Action reference conflict");
      return row;
    }
    var state = item.get("state");
    if (op.equals("record_store_check")) {
      require("pending".equals(state), "This check was already recorded");
      item.put("state", decision.equals("pass") ? "passed" : "hazard");
    } else if (verifying) {
      require(
          "retest".equals(state) && !db.viewerId.equals(item.get("corrected_by")),
          "Independent safety retest required");
      item.put("state", decision.equals("release") ? "cleared" : "hazard");
    } else {
      require("hazard".equals(state), "A reported hazard is required");
      item.putAll(map("state", "retest", "corrected_by", db.viewerId));
    }
    checks.put(check, item);
    var states = checks.values().stream().map(x -> object(x).get("state").toString()).toList();
    var history = new ArrayList<>(objects(data.get("history")));
    history.add(map("action", reference, "at", now(), "record", action));
    String readiness =
        states.contains("hazard") || states.contains("retest")
            ? "blocked"
            : states.contains("pending") ? "pending" : "ready";
    data.putAll(map("checks", checks, "state", readiness, "history", history));
    save(db, "store-opening-action", receipt, 0, action);
    return put(db, "store-opening", shift, b.get("revision"), data);
  }
}
