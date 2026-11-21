package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class TransactionChecks {
  private TransactionChecks() {}

  static Object run(Store store, Store.Db db, String op, Map<String, Object> b) throws Exception {
    String id = id(b, "objective_id");
    var spec = definition(db, "service-objective", id);
    if (op.equals("transaction_rule")) {
      require(db.viewerId.equals(spec.get("owner")), "Objective owner required");
      return put(
          db,
          "transaction-rule",
          id,
          b.get("revision"),
          map(
              "expected",
              text(b, "expected", 240),
              "maximum_ms",
              integer(b.get("limit"), 1, 120000)));
    }
    if (op.equals("transaction_evidence")) {
      return db.all(
          "SELECT bucket,location,detail FROM os_transaction_sample WHERE objective=? ORDER BY"
              + " bucket DESC,location LIMIT 500",
          id);
    }
    var rule = get(db, "transaction-rule", id);
    var settings = definition(db, "transaction-rule", id);
    String location = text(b, "location", 80);
    var raw = object(b.get("manifest"));
    keys(raw, "latency_ms", "status", "matched", "expected", "healthy");
    ReliabilityObjectives.authorizeProbe(db, id, location);
    long observed = ServiceOutages.time(b.get("observed_at"), System.currentTimeMillis());
    long interval = ((Number) spec.get("interval")).longValue();
    long bucket = observed - observed % interval;
    var saved =
        db.one(
            "SELECT detail FROM os_transaction_sample WHERE objective=? AND location=? AND"
                + " bucket=?",
            id,
            location,
            bucket);
    if (saved != null) {
      var evidence = new LinkedHashMap<>(object(saved.get("detail")));
      evidence.keySet().removeAll(Set.of("rule_revision", "maximum_ms", "result"));
      require(evidence.equals(raw), "Conflicting observation for the interval");
      return map("bucket", bucket, "replayed", true);
    }
    require(
        raw.get("matched") instanceof Boolean
            && raw.get("healthy") instanceof Boolean
            && settings.get("expected").equals(raw.get("expected")),
        "Probe contract changed");
    int ms = integer(raw.get("latency_ms"), 0, 600000), status = integer(raw.get("status"), 0, 599);
    String state =
        !Boolean.TRUE.equals(raw.get("healthy"))
            ? "probe_error"
            : (status >= 200
                    && status < 400
                    && (boolean) raw.get("matched")
                    && ms <= ((Number) settings.get("maximum_ms")).intValue())
                ? "up"
                : "down";
    var result =
        object(
            ReliabilityObjectives.run(
                store,
                db,
                "classified_sample",
                map(
                    "objective_id",
                    id,
                    "location",
                    location,
                    "observed_at",
                    b.get("observed_at"),
                    "result",
                    state)));
    var evidence = new LinkedHashMap<>(raw);
    evidence.putAll(
        map(
            "rule_revision",
            rule.get("revision"),
            "maximum_ms",
            settings.get("maximum_ms"),
            "result",
            state));
    db.update(
        "INSERT INTO os_transaction_sample(objective,location,bucket,detail) VALUES(?,?,?,?)",
        id,
        location,
        bucket,
        JSON.writeValueAsString(evidence));
    return result;
  }
}
