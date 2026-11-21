package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

final class BusinessTolerance {
  private BusinessTolerance() {}

  static Object run(Store.Db db, String op, Map<String, Object> b) throws Exception {
    if (op.equals("business_tolerance")) {
      String plan = id(b, "plan_id");
      var spec = definition(db, "continuity-plan", plan);
      var config = object(b.get("manifest"));
      keys(
          config,
          "applications",
          "suppliers",
          "people",
          "sites",
          "fallback",
          "maximum_minutes",
          "maximum_backlog");
      for (Object app : batch(config.get("applications"), 32))
        definition(db, "business-app", EnterpriseData.id(app));
      for (Object supplier : batch(config.get("suppliers"), 32))
        definition(db, "managed-supplier", SupplierGovernance.identity(supplier));
      for (Object person : batch(config.get("people"), 32)) DutyRosters.responder(db, person);
      for (Object site : batch(config.get("sites"), 32))
        Service.readRecord(db, "cmdb_ci", EnterpriseData.id(site));
      text(config, "fallback", 1000);
      integer(config.get("maximum_minutes"), 1, 43200);
      integer(config.get("maximum_backlog"), 0, 1000000);
      spec.put("business", config);
      return put(db, "continuity-plan", plan, b.get("revision"), spec);
    }
    String exercise = id(b, "exercise_id");
    var data = definition(db, "recovery-exercise", exercise);
    var rules = object(object(data.get("spec")).getOrDefault("business", map()));
    require(
        "running".equals(data.get("state")) && !rules.isEmpty(),
        "Select a running business exercise");
    long restored = ServiceOutages.time(b.get("restored_at"), System.currentTimeMillis()),
        elapsed = restored - ((Number) data.get("start")).longValue();
    require(elapsed >= 0, "Customer observation precedes disruption");
    int backlog = integer(b.get("quantity"), 0, 1000000);
    data.put(
        "business_result",
        map(
            "backlog",
            backlog,
            "elapsed_ms",
            elapsed,
            "observer",
            db.viewerId,
            "evidence",
            text(b, "evidence", 2000),
            "within_tolerance",
            backlog <= (Integer) rules.get("maximum_backlog")
                && elapsed <= ((Number) rules.get("maximum_minutes")).longValue() * 60000));
    return put(db, "recovery-exercise", exercise, b.get("revision"), data);
  }

  static boolean acceptable(Map<String, Object> spec, Map<String, Object> data) {
    if (!spec.containsKey("business")) return true;
    require(
        data.containsKey("business_result"), "Record customer impact before measuring recovery");
    return Boolean.TRUE.equals(object(data.get("business_result")).get("within_tolerance"));
  }
}
