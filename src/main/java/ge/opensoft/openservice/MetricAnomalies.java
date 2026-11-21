package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

final class MetricAnomalies {
  private MetricAnomalies() {}

  static final List<String> METRICS = List.of("latency_ms", "saturation_pct", "error_pct");

  static Map<String, Object> readings(Map<String, Object> input, boolean training) {
    require(
        METRICS.containsAll(input.keySet()) && (!training || input.size() == METRICS.size()),
        "Use the configured service metrics");
    var result = map();
    for (String name : input.keySet())
      result.put(name, integer(input.get(name), 0, name.equals("latency_ms") ? 600000 : 100));
    return result;
  }

  record Classification(boolean expectedDown, boolean observedDown) {}

  record EpisodeScore(int count, int unmatched) {}

  static EpisodeScore episodes(
      SortedMap<Long, Classification> observations, long interval, boolean expected) {
    long previous = -1;
    boolean open = false, matched = false;
    int count = 0, missed = 0;
    for (var entry : observations.entrySet()) {
      var c = entry.getValue();
      boolean down = expected ? c.expectedDown : c.observedDown;
      if (open && (!down || entry.getKey() - previous > interval)) {
        if (!matched) missed++;
        open = false;
      }
      if (down) {
        if (!open) {
          count++;
          open = true;
          matched = false;
        }
        matched |= expected ? c.observedDown : c.expectedDown;
      }
      previous = entry.getKey();
    }
    return new EpisodeScore(count, missed + (open && !matched ? 1 : 0));
  }

  static Object run(Store store, Store.Db db, String op, Map<String, Object> b) throws Exception {
    String id = id(b, "objective_id");
    var objective = definition(db, "service-objective", id);
    require(db.viewerId.equals(objective.get("owner")), "Objective owner required");
    if (op.equals("metric_status")) {
      var latest =
          list(db, "metric-observation").stream()
              .map(row -> object(row.get("definition")))
              .filter(item -> id.equals(item.get("objective")))
              .max(Comparator.comparingLong(item -> ((Number) item.get("at")).longValue()))
              .orElse(map());
      boolean stale =
          latest.isEmpty()
              || System.currentTimeMillis() - ((Number) latest.get("at")).longValue()
                  > 2 * ((Number) objective.get("interval")).longValue();
      var episode = latest.get("episode");
      return map(
          "state",
          stale ? "unknown" : latest.get("state"),
          "stale",
          stale,
          "last_measurement",
          latest,
          "incident",
          episode == null
              ? null
              : definition(db, "service-episode", episode.toString()).get("incident"));
    }
    if (op.equals("metric_baseline")) {
      var groups = new TreeMap<String, List<Map<String, Object>>>();
      long latest = 0;
      var timestamps = new HashSet<Long>();
      for (Object raw : batch(b.get("samples"), 168)) {
        var row = object(raw);
        long stamp = ServiceOutages.time(row.get("observed_at"), System.currentTimeMillis());
        require(timestamps.add(stamp), "Training samples require distinct observation times");
        latest = Math.max(latest, stamp);
        String hour = String.valueOf(Instant.ofEpochMilli(stamp).atZone(ZoneOffset.UTC).getHour());
        groups
            .computeIfAbsent(hour, k -> new ArrayList<>())
            .add(readings(object(row.get("metrics")), true));
      }
      require(!groups.isEmpty(), "Supply historical measurements");
      var ranges = map();
      for (var entry : groups.entrySet()) {
        require(entry.getValue().size() >= 3, "Supply at least three observations per UTC hour");
        var band = map();
        for (String name : METRICS) {
          var values =
              entry.getValue().stream().mapToInt(m -> (Integer) m.get(name)).sorted().toArray();
          band.put(name, map("low", values[0], "high", values[values.length - 1]));
        }
        ranges.put(entry.getKey(), band);
      }
      return put(
          db, "metric-baseline", id, b.get("revision"), map("ranges", ranges, "through", latest));
    }
    if (op.equals("metric_score")) {
      int falseAlarms = 0, misses = 0, unknown = 0, correct = 0;
      var seen = new TreeMap<Long, Classification>();
      for (Object raw : batch(b.get("samples"), 512)) {
        var label = object(raw);
        long at = ServiceOutages.time(label.get("observed_at"), System.currentTimeMillis());
        require(!seen.containsKey(at), "Duplicate evaluation time");
        String truth = choice(label, "state", "up", "down", "unknown"),
            observed = definition(db, "metric-observation", key(id, at)).get("state").toString();
        seen.put(at, new Classification(truth.equals("down"), observed.equals("down")));
        if (observed.equals("unknown")) unknown++;
        if (truth.equals(observed)) correct++;
        else if (observed.equals("down")) falseAlarms++;
        else if (truth.equals("down")) misses++;
      }
      long cadence = ((Number) objective.get("interval")).longValue();
      var expectedEpisodes = episodes(seen, cadence, true);
      var detectedEpisodes = episodes(seen, cadence, false);
      return map(
          "expected_incidents",
          expectedEpisodes.count(),
          "missed_incidents",
          expectedEpisodes.unmatched(),
          "detected_episodes",
          detectedEpisodes.count(),
          "false_alarm_episodes",
          detectedEpisodes.unmatched(),
          "observations",
          seen.size(),
          "correct",
          correct,
          "unknown",
          unknown,
          "false_alarms",
          falseAlarms,
          "missed_degradations",
          misses);
    }
    long stamp = ServiceOutages.time(b.get("observed_at"), System.currentTimeMillis());
    String ref = key(id, stamp);
    var receipt = get(db, "metric-observation", ref);
    var raw = object(b.get("manifest"));
    if (!object(receipt.get("definition")).isEmpty()) {
      require(
          raw.equals(object(receipt.get("definition")).get("input")), "Conflicting measurement");
      return receipt;
    }
    var baseline = get(db, "metric-baseline", id);
    var profile = definition(db, "metric-baseline", id);
    require(stamp > ((Number) profile.get("through")).longValue(), "Measure after training");
    var expected =
        object(
            object(profile.get("ranges"))
                .getOrDefault(
                    String.valueOf(Instant.ofEpochMilli(stamp).atZone(ZoneOffset.UTC).getHour()),
                    map()));
    var breaches = new ArrayList<String>();
    var actual = readings(raw, false);
    String state = "unknown";
    if (!expected.isEmpty() && !actual.isEmpty()) {
      for (String name : actual.keySet())
        if ((Integer) actual.get(name)
            > ((Number) object(expected.get(name)).get("high")).intValue()) breaches.add(name);
      state = !breaches.isEmpty() ? "down" : actual.size() == METRICS.size() ? "up" : "unknown";
    }
    var stream = get(db, "metric-cursor", id);
    var cursor = object(stream.get("definition"));
    Object episode = null;
    if (!state.equals("unknown") && stamp > ((Number) cursor.getOrDefault("at", 0)).longValue()) {
      if (!state.equals(cursor.get("state"))) {
        var identity =
            ServiceTopology.identity(
                objective.get("source").toString(), objective.get("resource").toString());
        var before = object(get(db, "monitor-state", identity).get("definition"));
        var signal =
            map(
                "id",
                ref,
                "monitor",
                "metrics-" + id,
                "state",
                state,
                "observed_at",
                Instant.ofEpochMilli(stamp).toString());
        var next =
            object(
                object(
                        CustomerImpact.topology(
                            store,
                            db,
                            "signals",
                            map(
                                "source",
                                objective.get("source"),
                                "resource_id",
                                objective.get("resource"),
                                "alerts",
                                List.of(signal))))
                    .get("definition"));
        episode = next.get("episode");
        if (state.equals("down") && !Objects.equals(episode, before.get("episode"))) {
          String incident =
              definition(db, "service-episode", episode.toString()).get("incident").toString();
          var record = Service.readRecord(db, "incident", incident);
          Service.update(
              db,
              "incident",
              record,
              map("priority", "1"),
              actor(db),
              db.viewerRole,
              record.get("revision").toString());
          DutyRosters.run(
              store,
              db,
              "page",
              map("roster_id", objective.get("roster"), "incident_id", incident));
        }
      } else episode = cursor.get("episode");
      put(
          db,
          "metric-cursor",
          id,
          stream.get("revision"),
          map("state", state, "at", stamp, "episode", episode));
    }
    return put(
        db,
        "metric-observation",
        ref,
        0,
        map(
            "objective",
            id,
            "at",
            stamp,
            "input",
            raw,
            "range",
            expected,
            "baseline_revision",
            baseline.get("revision"),
            "breaches",
            breaches,
            "state",
            state,
            "episode",
            episode));
  }
}
