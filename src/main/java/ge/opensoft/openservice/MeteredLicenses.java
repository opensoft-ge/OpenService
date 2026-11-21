package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

final class MeteredLicenses {
  private MeteredLicenses() {}

  static Map<String, Object> cores(
      Map<String, Map<String, Object>> inventory,
      Map<String, Integer> rights,
      Map<String, List<?>> covers,
      int minimumCores) {
    var quantities = new TreeMap<String, Integer>();
    var editions = new TreeMap<String, Set<String>>();
    inventory.forEach(
        (ci, row) -> {
          quantities.put(ci, Math.max(minimumCores, (Integer) row.get("cores")));
          editions.put(ci, Set.of(row.get("edition").toString()));
        });
    var result = allocate(quantities, editions, rights, covers);
    result.put("installations", inventory);
    result.put("minimum_cores", minimumCores);
    return result;
  }

  static Map<String, Object> peak(
      String day,
      List<Map<String, Object>> sessions,
      Map<String, Integer> rights,
      Map<String, List<?>> covers) {
    Instant first = LocalDate.parse(day).atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant limit = first.plus(Duration.ofDays(1));
    record Window(String user, String edition, Instant start, Instant end) {}
    var windows = new ArrayList<Window>();
    var boundaries = new TreeSet<Instant>();
    boundaries.add(first);
    for (var session : sessions) {
      Instant start = Instant.parse(session.get("start").toString());
      Instant end = Instant.parse(session.get("end").toString());
      if (!end.isAfter(first)) continue;
      windows.add(
          new Window(
              session.get("user_id").toString(), session.get("edition").toString(), start, end));
      if (start.isAfter(first)) boundaries.add(start);
      if (end.isBefore(limit)) boundaries.add(end);
    }
    int peak = 0, worstShortfall = -1, busiestAtWorst = -1;
    var result = map();
    for (Instant at : boundaries) {
      var active = new TreeMap<String, Set<String>>();
      for (var window : windows)
        if (!at.isBefore(window.start()) && at.isBefore(window.end()))
          active.computeIfAbsent(window.user(), k -> new TreeSet<>()).add(window.edition());
      var quantities = new TreeMap<String, Integer>();
      active.keySet().forEach(user -> quantities.put(user, 1));
      var position = allocate(quantities, active, rights, covers);
      int gap = (Integer) position.get("shortfall_units");
      peak = Math.max(peak, active.size());
      if (gap > worstShortfall || gap == worstShortfall && active.size() > busiestAtWorst) {
        worstShortfall = gap;
        busiestAtWorst = active.size();
        result = position;
        result.put("observed_at", at.toString());
        result.put("active_editions", active);
      }
    }
    result.put("peak_users", peak);
    return result;
  }

  // Residual edges allow a flexible contract to be freed for a more restricted consumer.
  // Each augmentation moves a block of units, keeping memory independent of core counts.
  static Map<String, Object> allocate(
      Map<String, Integer> quantities,
      Map<String, Set<String>> editions,
      Map<String, Integer> rights,
      Map<String, List<?>> covers) {
    String source = "source", sink = "sink";
    var graph = new TreeMap<String, Map<String, Integer>>();
    for (var item : quantities.entrySet()) {
      String consumer = "c/" + item.getKey();
      edge(graph, source, consumer, item.getValue());
      for (String pool : rights.keySet())
        if (covers.get(pool).containsAll(editions.get(item.getKey())))
          edge(graph, consumer, "p/" + pool, item.getValue());
    }
    for (var pool : rights.entrySet()) edge(graph, "p/" + pool.getKey(), sink, pool.getValue());
    int covered = 0;
    while (true) {
      var parent = new HashMap<String, String>();
      var queue = new ArrayDeque<String>();
      queue.add(source);
      parent.put(source, source);
      while (!queue.isEmpty() && !parent.containsKey(sink)) {
        String from = queue.remove();
        for (var next : graph.getOrDefault(from, Map.of()).entrySet()) {
          if (next.getValue() > 0 && parent.putIfAbsent(next.getKey(), from) == null)
            queue.add(next.getKey());
        }
      }
      if (!parent.containsKey(sink)) break;
      int units = Integer.MAX_VALUE;
      for (String to = sink; !to.equals(source); to = parent.get(to))
        units = Math.min(units, graph.get(parent.get(to)).get(to));
      for (String to = sink; !to.equals(source); to = parent.get(to)) {
        String from = parent.get(to);
        graph.get(from).merge(to, -units, Integer::sum);
        graph.get(to).merge(from, units, Integer::sum);
      }
      covered += units;
    }
    var assignments = new TreeMap<String, Map<String, Integer>>();
    var uncovered = new TreeMap<String, Integer>();
    for (String consumer : quantities.keySet()) {
      var allocation = new TreeMap<String, Integer>();
      for (String pool : rights.keySet()) {
        int used = graph.get("p/" + pool).getOrDefault("c/" + consumer, 0);
        if (used > 0) allocation.put(pool, used);
      }
      assignments.put(consumer, allocation);
      int missing = graph.get(source).get("c/" + consumer);
      if (missing > 0) uncovered.put(consumer, missing);
    }
    int required = quantities.values().stream().mapToInt(Integer::intValue).sum();
    return map(
        "required_units",
        required,
        "covered_units",
        covered,
        "shortfall_units",
        required - covered,
        "uncovered",
        uncovered,
        "allocations",
        assignments,
        "surplus_units",
        rights.values().stream().mapToInt(Integer::intValue).sum() - covered);
  }

  private static void edge(
      Map<String, Map<String, Integer>> graph, String from, String to, int capacity) {
    graph.computeIfAbsent(from, k -> new TreeMap<>()).put(to, capacity);
    graph.computeIfAbsent(to, k -> new TreeMap<>()).put(from, 0);
  }
}
