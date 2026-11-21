package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

final class PublisherPosition {
  private PublisherPosition() {}

  static Object run(Store.Db db, String op, Map<String, Object> b) throws Exception {
    String product = text(b, "product", 160);
    if (op.equals("license_fact")) {
      String ref = text(b, "reference", 160), day = ProjectDelivery.date(b.get("effective"));
      var data = object(b.get("manifest"));
      var model = get(db, "publisher-model", product);
      String metric =
          object(model.get("definition")).getOrDefault("metric", "installation").toString();
      if (data.containsKey("metric")) {
        String requested = choice(data, "metric", "installation", "named_user");
        if (model.get("revision").equals(0)) {
          if (requested.equals("named_user"))
            require(
                list(db, "publisher-fact").stream()
                    .noneMatch(row -> product.equals(object(row.get("definition")).get("product"))),
                "Existing inventory uses per-installation rights");
          put(db, "publisher-model", product, 0, map("metric", requested));
          metric = requested;
        } else
          require(requested.equals(metric), "License metric differs from recorded product metric");
      }
      String kind = choice(data, "kind", "rights", "installation", "removal");
      var fact = map("product", product, "reference", ref, "day", day, "kind", kind);
      if (kind.equals("rights")) {
        String contract = text(data, "contract", 120);
        var editions = new TreeSet<String>();
        for (Object value : batch(data.get("covers"), 32)) editions.add(WorkJobs.text(value, 120));
        int qty = integer(data.get("quantity"), -10000, 10000);
        require(qty != 0 && !editions.isEmpty(), "Provide signed quantity and licensed editions");
        if (data.containsKey("through")) {
          String through = ProjectDelivery.date(data.get("through"));
          require(day.compareTo(through) <= 0, "License expiry precedes its effective date");
          fact.put("through", through);
        }
        fact.putAll(map("contract", contract, "covers", List.copyOf(editions), "quantity", qty));
      } else {
        String ci = id(data, "ci");
        Service.readRecord(db, "cmdb_ci", ci);
        fact.put("ci", ci);
        if (data.containsKey("sequence"))
          fact.put("sequence", integer(data.get("sequence"), 1, Integer.MAX_VALUE));
        if (kind.equals("installation")) {
          if (metric.equals("named_user")) {
            String user = id(data, "user_id");
            Service.account(db, user);
            fact.put("user_id", user);
          }
          fact.putAll(
              map(
                  "edition",
                  text(data, "edition", 120),
                  "last_used",
                  ProjectDelivery.date(data.get("last_used"))));
        }
      }
      if (fact.containsKey("last_used"))
        require(
            day.compareTo(fact.get("last_used").toString()) >= 0,
            "Usage cannot follow inventory observation");
      return ServiceAssurance.once(db, "publisher-fact", key(product, ref), fact);
    }
    String day = ProjectDelivery.date(b.get("effective")), snapshot = id(b, "new_id");
    var old = get(db, "publisher-position", snapshot);
    if (!object(old.get("definition")).isEmpty()) {
      var prior = object(old.get("definition"));
      require(
          product.equals(prior.get("product")) && day.equals(prior.get("day")),
          "Snapshot identity conflict");
      return old;
    }
    var facts =
        list(db, "publisher-fact").stream()
            .map(x -> object(x.get("definition")))
            .filter(
                x ->
                    product.equals(x.get("product")) && day.compareTo(x.get("day").toString()) >= 0)
            .sorted(
                Comparator.comparing((Map<String, Object> x) -> x.get("day").toString())
                    .thenComparingInt(x -> (Integer) x.getOrDefault("sequence", 0))
                    .thenComparing(x -> Objects.toString(x.get("last_used"), ""))
                    .thenComparing(x -> x.get("reference").toString()))
            .toList();
    var pools = new TreeMap<String, Integer>();
    var covers = new TreeMap<String, List<?>>();
    var installed = new TreeMap<String, Map<String, Object>>();
    var ambiguous = new TreeSet<String>();
    for (var f : facts) {
      if (f.get("kind").equals("rights")) {
        if (f.containsKey("through") && day.compareTo(f.get("through").toString()) > 0) continue;
        String pool = f.get("contract").toString();
        var allowed = (List<?>) f.get("covers");
        require(
            Objects.equals(covers.getOrDefault(pool, allowed), allowed),
            "Separate changed rights into a new contract");
        covers.put(pool, allowed);
        pools.merge(pool, ((Number) f.get("quantity")).intValue(), Integer::sum);
      } else {
        String ci = f.get("ci").toString();
        var previous = installed.put(ci, f);
        if (previous == null
            || !f.get("day").equals(previous.get("day"))
            || !Objects.equals(f.get("sequence"), previous.get("sequence"))) {
          ambiguous.remove(ci);
        } else if (!Objects.equals(f.get("edition"), previous.get("edition"))
            || !Objects.equals(f.get("user_id"), previous.get("user_id"))) {
          ambiguous.add(ci);
        }
      }
    }
    require(ambiguous.isEmpty(), "Provide an ordered inventory observation for " + ambiguous);
    installed.values().removeIf(f -> f.get("kind").equals("removal"));
    require(pools.values().stream().allMatch(v -> v >= 0), "Credit exceeds rights");
    var recovery = new ArrayList<>();
    for (var row : list(db, "seat-receipt")) {
      var receipt = object(row.get("definition"));
      String recovered = Objects.toString(receipt.get("at"), "");
      if (recovered.length() < 10 || day.compareTo(recovered.substring(0, 10)) < 0) continue;
      var entitled =
          object(get(db, "entitlement", receipt.get("license").toString()).get("definition"));
      if (product.equalsIgnoreCase(
          Objects.toString(receipt.getOrDefault("product", entitled.get("product")), "")))
        recovery.add(
            map(
                "receipt",
                row.get("subject"),
                "entitlement",
                receipt.get("license"),
                "verified_at",
                recovered,
                "released_by_user",
                receipt.get("user"),
                "reallocated_to",
                receipt.get("replacement")));
    }
    var matches = new TreeMap<String, String>();
    var missing = new ArrayList<String>();
    var demand = new TreeMap<String, Set<String>>();
    installed.forEach(
        (ci, item) ->
            demand
                .computeIfAbsent(item.getOrDefault("user_id", ci).toString(), k -> new TreeSet<>())
                .add(item.get("edition").toString()));
    for (String consumer : demand.keySet())
      if (!assign(consumer, demand, pools, covers, matches, new HashSet<>())) missing.add(consumer);
    return put(
        db,
        "publisher-position",
        snapshot,
        0,
        map(
            "product",
            product,
            "day",
            day,
            "metric",
            object(get(db, "publisher-model", product).get("definition"))
                .getOrDefault("metric", "installation"),
            "consumption_by_edition",
            demand,
            "assignments",
            matches,
            "installations",
            installed,
            "rights",
            pools,
            "rights_by_edition",
            covers,
            "recovered_seats",
            recovery,
            "shortfall",
            missing,
            "surplus",
            pools.values().stream().mapToInt(Integer::intValue).sum() - matches.size(),
            "evidence",
            facts.stream().map(f -> f.get("reference")).toList()));
  }

  static boolean assign(
      String ci,
      Map<String, Set<String>> demand,
      Map<String, Integer> pools,
      Map<String, List<?>> covers,
      Map<String, String> assigned,
      Set<String> visited) {
    for (String pool : pools.keySet()) {
      if (!covers.get(pool).containsAll(demand.get(ci)) || !visited.add(pool)) continue;
      var occupants =
          assigned.entrySet().stream()
              .filter(e -> e.getValue().equals(pool))
              .map(Map.Entry::getKey)
              .toList();
      if (occupants.size() < pools.get(pool)
          || occupants.stream()
              .anyMatch(other -> assign(other, demand, pools, covers, assigned, visited))) {
        assigned.put(ci, pool);
        return true;
      }
    }
    return false;
  }
}
