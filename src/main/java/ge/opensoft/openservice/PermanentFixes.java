package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

final class PermanentFixes {
  private PermanentFixes() {}

  static List<String> incidents(Store.Db db, String problem) throws Exception {
    return db
        .all(
            "SELECT incident_id FROM os_problem_link WHERE problem_id=? ORDER BY incident_id",
            problem)
        .stream()
        .map(r -> r.get("incident_id").toString())
        .toList();
  }

  static List<String> recurrence(
      Store.Db db, String problem, String ci, List<?> baseline, long deployed, long end)
      throws java.sql.SQLException {
    var repeated = new TreeSet<String>();
    var monitored = new HashSet<String>();
    for (var row : list(db, "service-episode")) {
      var event = object(row.get("definition"));
      if (!ci.equals(event.get("ci"))) continue;
      String incident = event.get("incident").toString();
      monitored.add(incident);
      long began = ((Number) event.get("began")).longValue();
      if (began >= deployed && began < end) repeated.add(incident);
    }
    // A monitored incident uses its observation time. A desk-reported incident uses its opening
    // time.
    for (var record :
        db.all(
            "SELECT r.sys_id,r.created FROM os_problem_link l JOIN os_record r"
                + " ON l.incident_id=r.sys_id WHERE l.problem_id=?",
            problem)) {
      String incident = record.get("sys_id").toString();
      if (baseline.contains(incident) || monitored.contains(incident)) continue;
      long opened = DeliveryPerformance.timestamp(record.get("created"));
      if (opened >= deployed && opened < end) repeated.add(incident);
    }
    return List.copyOf(repeated);
  }

  static boolean canClose(Store.Db db, String problem, Map<String, Object> fix)
      throws java.sql.SQLException {
    var decision =
        object(get(db, "fix-verification", fix.get("attempt").toString()).get("definition"));
    if (!"verified".equals(decision.get("state"))) return false;
    var release =
        object(get(db, "pipeline-release", fix.get("release").toString()).get("definition"));
    return recurrence(
            db,
            problem,
            object(release.get("policy")).get("ci").toString(),
            (List<?>) decision.get("baseline"),
            DeliveryPerformance.timestamp(release.get("reported_at")),
            ((Number) decision.get("window_end")).longValue())
        .isEmpty();
  }

  static Object run(Store.Db db, String op, Map<String, Object> b) throws Exception {
    String problem = id(b, "problem_id"),
        release = id(b, "release_id"),
        ref = key(problem, release);
    ProblemInvestigations.record(db, "problem", problem);
    var deployment = definition(db, "pipeline-release", release);
    String ci = object(deployment.get("policy")).get("ci").toString();
    var linked = incidents(db, problem);
    var episodes =
        list(db, "service-episode").stream()
            .map(e -> object(e.get("definition")))
            .filter(e -> ci.equals(e.get("ci")))
            .toList();
    if (op.equals("propose_fix")) {
      var analysis = ProblemInvestigations.analysis(db, problem);
      require(Integer.valueOf(1).equals(analysis.get("published")), "Publish the workaround first");
      require(
          linked.stream()
                  .filter(i -> episodes.stream().anyMatch(e -> i.equals(e.get("incident"))))
                  .count()
              >= 3,
          "Link three observed incidents on the affected service");
      require(
          Set.of("authorized", "awaiting_review").contains(deployment.get("state")),
          "Select a proposed release");
      String objective = id(b, "objective_id");
      require(
          ci.equals(definition(db, "service-objective", objective).get("ci")),
          "Select an objective on the affected component");
      var record =
          map(
              "problem",
              problem,
              "release",
              release,
              "change",
              deployment.get("change_id"),
              "objective",
              objective,
              "minutes",
              integer(b.get("minutes"), 1, 43200),
              "baseline",
              linked,
              "workaround",
              analysis.get("workaround"),
              "workaround_revision",
              analysis.get("revision"));
      var previous = get(db, "problem-fix", ref);
      var saved = ServiceAssurance.once(db, "problem-fix", ref, record);
      if (previous.get("revision").equals(0)) {
        require(
            !"closed"
                .equals(
                    object(ProblemInvestigations.record(db, "problem", problem).get("data"))
                        .get("state")),
            "Choose an open problem");
        var current = get(db, "problem-fix-current", problem);
        put(
            db,
            "problem-fix-current",
            problem,
            current.get("revision"),
            map("attempt", ref, "release", release, "change", deployment.get("change_id")));
      }
      return saved;
    }
    var spec = definition(db, "problem-fix", ref);
    String objective = spec.get("objective").toString();
    var decision = get(db, "fix-verification", ref);
    if ("recurrence".equals(object(decision.get("definition")).get("state"))) return decision;
    var board = object(get(db, "cab-window", release).get("definition"));
    if (!"deployed".equals(deployment.get("state"))
        || !"approved".equals(board.get("state"))
        || !Objects.equals(deployment.get("artifact"), board.get("artifact")))
      return map(
          "state", "awaiting_successful_board_release", "release_state", deployment.get("state"));
    long deployed = DeliveryPerformance.timestamp(deployment.get("reported_at")),
        interval =
            ((Number) definition(db, "service-objective", objective).get("interval")).longValue();
    long start = (deployed / interval + 1) * interval;
    long buckets = (60000L * (Integer) spec.get("minutes") + interval - 1) / interval;
    long end = start + interval * buckets;
    var repeated = recurrence(db, problem, ci, (List<?>) spec.get("baseline"), deployed, end);
    if (!decision.get("revision").equals(0) && repeated.isEmpty()) return decision;
    long healthy =
        db.count(
            "SELECT COUNT(*) FROM (SELECT bucket FROM os_synthetic_sample WHERE objective=? AND"
                + " bucket>=? AND bucket<? GROUP BY bucket HAVING COUNT(*)=2 AND MIN(result)='up'"
                + " AND MAX(result)='up') healthy",
            objective,
            start,
            end);
    var result =
        map(
            "state",
            "observing",
            "problem",
            problem,
            "deployment",
            deployment.get("deployment"),
            "change",
            spec.get("change"),
            "cab",
            board,
            "window_start",
            start,
            "window_end",
            end,
            "required_intervals",
            buckets,
            "healthy_intervals",
            healthy,
            "recurrence",
            List.copyOf(repeated),
            "baseline",
            spec.get("baseline"));
    if (System.currentTimeMillis() < end) return result;
    result.put(
        "state",
        !repeated.isEmpty()
            ? "recurrence"
            : healthy != buckets ? "insufficient_health_evidence" : "verified");
    if ("insufficient_health_evidence".equals(result.get("state"))) return result;
    return put(db, "fix-verification", ref, decision.get("revision"), result);
  }
}
