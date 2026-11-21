package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

final class CircuitAssurance {
  private CircuitAssurance() {}

  static boolean healthy(Store.Db db, List<?> path) throws Exception {
    if (path.isEmpty()) return false;
    for (Object port : path)
      if (!"up".equals(definition(db, "network-port", port.toString()).get("state"))) return false;
    return true;
  }

  static String condition(Store.Db db, Map<String, Object> circuit) throws Exception {
    if (!"active".equals(circuit.get("state"))) return "inactive";
    if (healthy(db, (List<?>) circuit.get("path"))) return "primary";
    return healthy(db, (List<?>) circuit.getOrDefault("backup", List.of()))
        ? "backup"
        : "interrupted";
  }

  static void notifyStandby(Store.Db db, String circuit, String status) throws Exception {
    var row = get(db, "standby-status", circuit);
    var before = object(row.get("definition"));
    if (status.equals(before.get("path")) || before.isEmpty() && status.equals("primary")) return;
    var site = object(get(db, "site-monitor", circuit).get("definition"));
    if (site.isEmpty()) return;
    var contact =
        db.one(
            "SELECT u.name FROM os_customer_contact c JOIN os_user u ON u.user_id=c.user_id JOIN"
                + " os_customer_account a ON a.account_id=c.account_id WHERE c.user_id=? AND"
                + " c.account_id=? AND c.active=1 AND a.active=1 AND u.active=1 AND"
                + " u.role='requester' AND u.password_reset_required=0",
            site.get("contact"),
            site.get("account"));
    if (contact == null) return;
    CustomerImpact.notify(
        db,
        site.get("contact").toString(),
        "Connectivity at "
            + site.get("site")
            + (status.equals("backup")
                ? " is running on the backup path."
                : " is back on the primary path."));
    put(
        db,
        "standby-status",
        circuit,
        row.get("revision"),
        map("path", status, "notified", site.get("contact"), "at", now()));
  }

  static Object run(Store store, Store.Db db, String op, Map<String, Object> b) throws Exception {
    if (op.equals("network_alarm")) {
      String port = id(b, "port_id"),
          ref = key(port, text(b, "reference", 160)),
          state = choice(b, "state", "up", "down");
      var prior = get(db, "network-alarm", ref);
      if (!prior.get("revision").equals(0)) {
        require(
            state.equals(object(prior.get("definition")).get("state")), "Alarm reference conflict");
        return prior;
      }
      var link = get(db, "network-port", port);
      ProblemInvestigations.revision(b.get("revision"), link.get("revision"));
      NetworkCircuits.run(db, "port_condition", b);
      var sites = map();
      for (String key : object(definition(db, "network-port", port).get("circuits")).keySet()) {
        var circuit = definition(db, "network-circuit", key);
        String status = condition(db, circuit);
        sites.put(key, status);
        if (status.equals("backup") || status.equals("primary")) notifyStandby(db, key, status);
        if (!status.equals("interrupted")) continue;
        var issue = get(db, "circuit-interruption", key);
        var incident = object(issue.get("definition"));
        if ("open".equals(incident.get("state"))) continue;
        var binding = object(get(db, "site-monitor", key).get("definition"));
        if (binding.isEmpty()) {
          sites.put(key, "interrupted_without_contract_binding");
          continue;
        }
        String outage = Values.id();
        var result =
            object(
                CustomerImpact.command(
                    store,
                    db,
                    "circuit_outage",
                    map(
                        "new_id",
                        outage,
                        "circuit",
                        key,
                        "component",
                        port,
                        "title",
                        "Network service interruption")));
        var cases = objects(object(result.get("definition")).get("sites"));
        if (cases.isEmpty()) {
          sites.put(key, "interrupted_without_eligible_contact");
          continue;
        }
        for (var site : cases) {
          String ticket = site.get("case_id").toString();
          var account =
              db.one(
                  "SELECT group_id FROM os_customer_account WHERE account_id=?",
                  site.get("account"));
          WorkTasks.command(
              db,
              "customer_case",
              ticket,
              map(
                  "operation",
                  "configure",
                  "revision",
                  0,
                  "record_revision",
                  Service.readRecord(db, "customer_case", ticket).get("revision"),
                  "close_at",
                  "resolved"));
          WorkTasks.command(
              db,
              "customer_case",
              ticket,
              map(
                  "operation",
                  "create",
                  "record_revision",
                  Service.readRecord(db, "customer_case", ticket).get("revision"),
                  "revision",
                  WorkTasks.plan(db, ticket).get("revision"),
                  "summary",
                  "Restore customer circuit",
                  "details",
                  "Investigate network port " + port,
                  "group_id",
                  account.get("group_id"),
                  "required",
                  true,
                  "predecessors",
                  List.of()));
        }
        put(
            db,
            "circuit-interruption",
            key,
            issue.get("revision"),
            map(
                "state",
                "open",
                "outage",
                outage,
                "opened",
                System.currentTimeMillis(),
                "service",
                binding.get("service")));
      }
      return put(db, "network-alarm", ref, 0, map("state", state, "port", port, "sites", sites));
    }
    String circuit = key(id(b, "bundle_id"), text(b, "location", 120));
    var row = get(db, "network-circuit", circuit);
    var data = definition(db, "network-circuit", circuit);
    if (op.equals("backup_path")) {
      var path = new ArrayList<String>();
      for (Object raw : batch(b.get("entries"), 32)) {
        String port = EnterpriseData.id(raw);
        require(
            !path.contains(port) && !((List<?>) data.get("path")).contains(port),
            "Choose independent ports");
        path.add(port);
      }
      require(!path.isEmpty(), "Select a standby path");
      if (path.equals(data.get("backup"))) return row;
      require(!data.containsKey("backup"), "A standby path is already reserved");
      int units = (Integer) data.get("units");
      for (String port : path) {
        var portRow = get(db, "network-port", port);
        var link = definition(db, "network-port", port);
        var assignments = object(link.get("circuits"));
        require(
            "up".equals(link.get("state"))
                && assignments.values().stream().mapToLong(v -> ((Number) v).longValue()).sum()
                        + units
                    <= (Integer) link.get("capacity"),
            "Insufficient standby capacity");
        assignments.put(circuit, units);
        put(db, "network-port", port, portRow.get("revision"), link);
      }
      data.put("backup", path);
      return put(db, "network-circuit", circuit, b.get("revision"), data);
    }
    String objective = id(b, "objective_id");
    var spec = definition(db, "service-objective", objective);
    var binding = definition(db, "site-monitor", circuit);
    if (op.equals("circuit_probe")) {
      require(
          get(db, "circuit-interruption", circuit).get("revision").equals(0)
              || !"open".equals(definition(db, "circuit-interruption", circuit).get("state")),
          "Keep the transaction binding fixed during an outage");
      var bound = get(db, "circuit-probe", objective);
      require(
          bound.get("revision").equals(0)
              || circuit.equals(object(bound.get("definition")).get("circuit")),
          "Each customer site requires its own transaction objective");
      var component =
          ServiceTopology.identity(spec.get("source").toString(), spec.get("resource").toString());
      var alias = object(get(db, "component-alias", component).get("definition"));
      require(
          FederatedMaps.graph(db)
              .roots()
              .getOrDefault(binding.get("service"), Set.of())
              .contains(alias.getOrDefault("component", component)),
          "Select an objective on the site service root");
      definition(db, "transaction-rule", objective);
      ServiceAssurance.once(db, "circuit-probe", objective, map("circuit", circuit));
      data.put("objective", objective);
      return put(db, "network-circuit", circuit, b.get("revision"), data);
    }
    require(
        objective.equals(data.get("objective")),
        "Use this site's registered transaction objective");
    var issue = get(db, "circuit-interruption", circuit);
    var incident = definition(db, "circuit-interruption", circuit);
    if ("restored".equals(incident.get("state"))) return issue;
    String path = condition(db, data);
    require(Set.of("primary", "backup").contains(path), "Restore a service path first");
    String component =
        ServiceTopology.identity(spec.get("source").toString(), spec.get("resource").toString());
    var alias = object(get(db, "component-alias", component).get("definition"));
    require(
        FederatedMaps.graph(db)
            .roots()
            .getOrDefault(incident.get("service"), Set.of())
            .contains(alias.getOrDefault("component", component)),
        "Choose an end-to-end objective on this service root");
    long available = ((Number) incident.get("opened")).longValue();
    for (Object port : (List<?>) data.get(path.equals("primary") ? "path" : "backup"))
      available =
          Math.max(
              available,
              ((Number)
                      definition(db, "network-port", port.toString()).getOrDefault("changed_at", 0))
                  .longValue());
    available =
        Math.max(
            available,
            System.currentTimeMillis() - 2 * ((Number) spec.get("interval")).longValue());
    var latest =
        db.all(
            "SELECT bucket,location,detail FROM os_transaction_sample WHERE objective=? AND"
                + " bucket>? ORDER BY bucket DESC,location LIMIT 2",
            objective,
            available);
    var rule = get(db, "transaction-rule", objective).get("revision");
    require(
        latest.size() == 2
            && latest.getFirst().get("bucket").equals(latest.getLast().get("bucket"))
            && latest.stream()
                .map(r -> object(r.get("detail")))
                .allMatch(
                    sample ->
                        "up".equals(sample.get("result"))
                            && rule.equals(sample.get("rule_revision"))),
        "Two healthy transaction probes are required after interruption");
    CustomerImpact.command(
        store,
        db,
        "restore_circuit",
        map(
            "new_id",
            incident.get("outage"),
            "note",
            "End-to-end service verified by " + objective));
    incident.putAll(map("state", "restored", "objective", objective, "observations", latest));
    return put(db, "circuit-interruption", circuit, issue.get("revision"), incident);
  }
}
