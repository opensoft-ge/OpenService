package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

final class DeliveryPerformance {
  private DeliveryPerformance() {}

  static long timestamp(Object t) {
    return LocalDateTime.parse(t.toString().replace(' ', 'T'))
        .toInstant(ZoneOffset.UTC)
        .toEpochMilli();
  }

  static Object run(Store.Db db, String op, Map<String, Object> b) throws Exception {
    String team = id(b, "group_id");
    CatalogueRouting.group(db, team);
    if (op.equals("track_delivery")) {
      String release = id(b, "release_id");
      var pipeline = definition(db, "pipeline-release", release);
      Service.readRecord(db, "change_request", pipeline.get("change_id").toString());
      return ServiceAssurance.once(
          db, "delivery-team", release, map("team", team, "release", release));
    }
    if (op.equals("delivery_event")) {
      String release = id(b, "release_id");
      require(
          team.equals(definition(db, "delivery-team", release).get("team")), "Wrong delivery team");
      String kind =
          choice(b, "kind", "commit", "pull_request", "build", "incident", "revert", "restored");
      String ref = text(b, "reference", 160);
      long at = ServiceOutages.time(b.get("observed_at"), System.currentTimeMillis());
      if (kind.equals("incident")) Service.readRecord(db, "incident", EnterpriseData.id(ref));
      return ServiceAssurance.once(
          db,
          "delivery-event",
          key(release, kind, ref),
          map("release", release, "type", kind, "reference", ref, "at", at));
    }
    long end = ServiceOutages.time(b.get("end"), System.currentTimeMillis()),
        start = ServiceOutages.time(b.get("start"), end);
    require(start < end, "Use a positive reporting window");
    int deployments = 0, failed = 0;
    var leads = new LongSummaryStatistics();
    var recoveries = new LongSummaryStatistics();
    var evidence = new ArrayList<>();
    var events = new HashMap<String, List<Map<String, Object>>>();
    for (var row : list(db, "delivery-event")) {
      var e = object(row.get("definition"));
      events.computeIfAbsent(e.get("release").toString(), ignored -> new ArrayList<>()).add(e);
    }
    for (var binding : list(db, "delivery-team")) {
      String release = binding.get("subject").toString();
      if (!team.equals(object(binding.get("definition")).get("team"))) continue;
      var data = definition(db, "pipeline-release", release);
      if (!data.containsKey("reported_at")) continue;
      long deployed = timestamp(data.get("reported_at"));
      if (deployed < start || deployed >= end) continue;
      var items = events.getOrDefault(release, List.of());
      long failure =
          Set.of("failed", "recovered").contains(data.get("state")) ? deployed : Long.MAX_VALUE;
      long first = Long.MAX_VALUE;
      for (var e : items) {
        long at = ((Number) e.get("at")).longValue();
        String kind = e.get("type").toString();
        if (kind.equals("commit") && at <= deployed) first = Math.min(first, at);
        if (Set.of("incident", "revert").contains(kind) && at >= deployed)
          failure = Math.min(failure, at);
      }
      long restored =
          data.containsKey("recovered_at") ? timestamp(data.get("recovered_at")) : Long.MAX_VALUE;
      if (restored < failure) restored = Long.MAX_VALUE;
      for (var e : items)
        if (e.get("type").equals("restored") && ((Number) e.get("at")).longValue() >= failure)
          restored = Math.min(restored, ((Number) e.get("at")).longValue());
      deployments++;
      if (failure != Long.MAX_VALUE) failed++;
      if (first != Long.MAX_VALUE) leads.accept(deployed - first);
      if (restored != Long.MAX_VALUE) recoveries.accept(restored - failure);
      var missing = new ArrayList<String>();
      for (String type : List.of("commit", "pull_request", "build"))
        if (items.stream()
            .noneMatch(
                e -> type.equals(e.get("type")) && ((Number) e.get("at")).longValue() <= deployed))
          missing.add(type);
      evidence.add(
          map(
              "release",
              release,
              "change",
              data.get("change_id"),
              "deployment",
              data.get("deployment"),
              "events",
              items,
              "missing",
              missing,
              "failed",
              failure != Long.MAX_VALUE));
    }
    return map(
        "deployments",
        deployments,
        "deployments_per_day",
        deployments * 86400000.0 / (end - start),
        "failed",
        failed,
        "failure_rate",
        deployments == 0 ? null : (double) failed / deployments,
        "lead_samples",
        leads.getCount(),
        "mean_lead_ms",
        leads.getCount() == 0 ? null : leads.getAverage(),
        "recovery_samples",
        recoveries.getCount(),
        "mean_recovery_ms",
        recoveries.getCount() == 0 ? null : recoveries.getAverage(),
        "releases",
        evidence);
  }
}
