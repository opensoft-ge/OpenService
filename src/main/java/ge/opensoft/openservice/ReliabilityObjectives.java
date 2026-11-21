package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.Instant;
import java.util.*;

final class ReliabilityObjectives {
  private ReliabilityObjectives() {}

  static void authorizeProbe(Store.Db db, String id, String location) throws Exception {
    var permission = object(get(db, "probe-identity", key(id, location)).get("definition"));
    if (!(permission.isEmpty()
        ? "admin".equals(db.viewerRole)
        : db.viewerId.equals(permission.get("user"))))
      throw new Failure(403, "Assigned location identity required");
  }

  static Object run(Store store, Store.Db db, String op, Map<String, Object> b) throws Exception {
    String id = id(b, "objective_id");
    if (op.equals("define_objective")) {
      String source = text(b, "source", 80), resource = text(b, "resource_id", 200);
      var component =
          object(object(definition(db, "service-map", source).get("resources")).get(resource));
      String first = text(b, "location", 80), second = text(b, "backup_location", 80);
      require(!first.equals(second), "Use two distinct monitoring locations");
      definition(db, "duty-roster", id(b, "roster_id"));
      return put(
          db,
          "service-objective",
          id,
          0,
          map(
              "source",
              source,
              "resource",
              resource,
              "ci",
              component.get("ci_id"),
              "owner",
              db.viewerId,
              "owner_revision",
              AgentPlans.account(db, db.viewerId).get("auth_revision"),
              "locations",
              List.of(first, second),
              "interval",
              integer(b.get("minutes"), 1, 60) * 60000L,
              "target",
              integer(b.get("target_bps"), 1, 9999),
              "roster",
              b.get("roster_id")));
    }
    var spec = definition(db, "service-objective", id);
    if (op.equals("objective_report")) return report(db, id, spec, System.currentTimeMillis());
    String location = text(b, "location", 80);
    require(((List<?>) spec.get("locations")).contains(location), "Unregistered probe location");
    if (op.equals("probe_identity")) {
      String probe = DutyRosters.responder(db, b.get("user_id"));
      for (Object site : (List<?>) spec.get("locations"))
        if (!site.equals(location))
          require(
              !probe.equals(
                  object(get(db, "probe-identity", key(id, site)).get("definition")).get("user")),
              "Assign independent probe identities");
      return put(db, "probe-identity", key(id, location), b.get("revision"), map("user", probe));
    }
    authorizeProbe(db, id, location);
    if(op.equals("probe_sample")) require(get(db,"transaction-rule",id).get("revision").equals(0),"Report the configured transaction measurements");
    String status = choice(b, "result", "up", "down", "probe_error");
    long now = System.currentTimeMillis(),
        at = ServiceOutages.time(b.get("observed_at"), now),
        interval = ((Number) spec.get("interval")).longValue(),
        bucket = at - at % interval;
    require(now - at < 2 * interval, "Observation outside the collection window");
    var prior =
        db.one(
            "SELECT result FROM os_synthetic_sample WHERE objective=? AND bucket=? AND location=?",
            id,
            bucket,
            location);
    if (prior != null) {
      require(status.equals(prior.get("result")), "Conflicting sample in this interval");
      return map("bucket", bucket, "replayed", true);
    }
    db.update(
        "INSERT INTO os_synthetic_sample(objective,bucket,location,result) VALUES(?,?,?,?)",
        id,
        bucket,
        location,
        status);
    var samples =
        db.all("SELECT result FROM os_synthetic_sample WHERE objective=? AND bucket=?", id, bucket);
    if (samples.size() < 2) return map("bucket", bucket, "state", "waiting");
    boolean down = samples.stream().allMatch(row -> "down".equals(row.get("result")));
    boolean up = samples.stream().anyMatch(row -> "up".equals(row.get("result")));
    String state = down ? "down" : up ? "up" : "unknown";
    if (!state.equals("unknown")) {
      String reporter = db.viewerId, role = db.viewerRole, owner = spec.get("owner").toString();
      var authority = AgentPlans.account(db, owner);
      require("admin".equals(authority.get("role")), "Objective owner lost operational authority");
      ProblemInvestigations.revision(spec.get("owner_revision"), authority.get("auth_revision"));
      try {
        db.viewerId = owner;
        db.viewerRole = "admin";
        signal(store, db, id, spec, bucket, state);
      } finally {
        db.viewerId = reporter;
        db.viewerRole = role;
      }
    }
    return map("bucket", bucket, "state", state);
  }

  static void signal(
      Store store, Store.Db db, String id, Map<String, Object> spec, long bucket, String state)
      throws Exception {
    var signal =
        map(
            "id",
            key(id, bucket),
            "monitor",
            id,
            "observed_at",
            Instant.ofEpochMilli(bucket).toString(),
            "state",
            state);
    var current =
        object(
            get(
                    db,
                    "monitor-state",
                    ServiceTopology.identity(
                        spec.get("source").toString(), spec.get("resource").toString()))
                .get("definition"));
    var previous = object(object(current.getOrDefault("monitors", map())).getOrDefault(id, map()));
    long newest =
        db.count(
            "SELECT COALESCE(MAX(bucket),0) FROM (SELECT bucket FROM os_synthetic_sample WHERE"
                + " objective=? GROUP BY bucket HAVING COUNT(*)=2) completed",
            id);
    if (bucket == newest
        && !state.equals(previous.get("state"))
        && (previous.isEmpty()
            || bucket > Instant.parse(previous.get("observed_at").toString()).toEpochMilli())) {
      var result =
          object(
              CustomerImpact.topology(
                  store,
                  db,
                  "signals",
                  map(
                      "source",
                      spec.get("source"),
                      "resource_id",
                      spec.get("resource"),
                      "alerts",
                      List.of(signal))));
      var monitor = object(result.get("definition"));
      if (state.equals("down")
          && !current.containsKey("episode")
          && monitor.containsKey("episode")) {
        var episode = definition(db, "service-episode", monitor.get("episode").toString());
        var incident = Service.readRecord(db, "incident", episode.get("incident").toString());
        Service.update(
            db,
            "incident",
            incident,
            map("priority", "1"),
            actor(db),
            db.viewerRole,
            incident.get("revision").toString());
        DutyRosters.run(
            store,
            db,
            "page",
            map("roster_id", spec.get("roster"), "incident_id", episode.get("incident")));
      }
    }
  }

  static Map<String, Object> report(Store.Db db, String id, Map<String, Object> spec, long at)
      throws Exception {
    long interval = ((Number) spec.get("interval")).longValue(),
        end = at - at % interval,
        start = end - 30L * 86400000;
    var groups =
        db.all(
            "SELECT bucket,COUNT(*) AS samples,SUM(CASE WHEN result='up' THEN 1 ELSE 0 END) AS"
                + " healthy,SUM(CASE WHEN result='down' THEN 1 ELSE 0 END) AS failed FROM"
                + " os_synthetic_sample WHERE objective=? AND bucket>=? AND bucket<? GROUP BY"
                + " bucket ORDER BY bucket",
            id,
            start,
            end);
    long down = groups.stream().filter(r -> ((Number) r.get("failed")).intValue() == 2).count();
    long up =
        groups.stream()
            .filter(
                r ->
                    ((Number) r.get("samples")).intValue() == 2
                        && ((Number) r.get("healthy")).intValue() > 0)
            .count();
    long allowed = (end - start) * (10000 - ((Number) spec.get("target")).longValue()) / 10000;
    long recentDown=0,recentKnown=0;
    for(var sample:groups){
      if(((Number)sample.get("bucket")).longValue()<end-3600000L || ((Number)sample.get("samples")).intValue()!=2)continue;
      if(((Number)sample.get("failed")).intValue()==2){recentDown++;recentKnown++;}
      else if(((Number)sample.get("healthy")).intValue()>0)recentKnown++;
    }
    return map(
        "one_hour_known_ms", recentKnown*interval,
        "one_hour_burn_rate", recentKnown==0?null:10000.0*recentDown/(recentKnown*(10000-((Number)spec.get("target")).longValue())),
        "window_start",
        start,
        "window_end",
        end,
        "unknown_ms",
        end - start - (up + down) * interval,
        "allowed_ms",
        allowed,
        "consumed_ms",
        down * interval,
        "budget_consumed_percent", 100.0 * down * interval / allowed,
        "remaining_ms",
        allowed - down * interval,
        "samples",
        groups);
  }

  static List<Object> review(Store.Db db, String ci) throws Exception {
    var result = new ArrayList<>();
    for (var row : list(db, "service-objective")) {
      var spec = object(row.get("definition"));
      if (ci.equals(spec.get("ci"))) {
        var summary = report(db, row.get("subject").toString(), spec, System.currentTimeMillis());
        summary.remove("samples");
        summary.put("objective_id", row.get("subject"));
        result.add(summary);
      }
    }
    return result;
  }
}
