package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class NetworkCircuits {
  private NetworkCircuits() {}

  static Object run(Store.Db db, String op, Map<String, Object> b) throws Exception {
    if (op.equals("network_port")) {
      String port = id(b, "port_id"), ci = id(b, "service_ci");
      Service.readRecord(db, "cmdb_ci", ci);
      return put(
          db,
          "network-port",
          port,
          0,
          map(
              "ci",
              ci,
              "capacity",
              integer(b.get("capacity"), 1, 1000000),
              "name",
              text(b, "title", 160),
              "state",
              "up",
              "circuits",
              map()));
    }
    if (op.equals("port_condition")) {
      String port = id(b, "port_id");
      var data = definition(db, "network-port", port);
      String status = choice(b, "state", "up", "down");
      var impacted = new ArrayList<>();
      for (var circuit : object(data.get("circuits")).keySet()) {
        var line = definition(db, "network-circuit", circuit);
        var sale = definition(db, "opportunity", line.get("opportunity").toString());
        impacted.add(
            map(
                "circuit",
                circuit,
                "activation",
                line.get("state"),
                "customer",
                sale.get("account"),
                "site",
                line.get("site"),
                "entitlement",
                sale.get("entitlement"),
                "case",
                sale.get("case_id")));
      }
      if (!status.equals(data.get("state"))) {
        String ci = data.get("ci").toString();
        var instant = java.time.Instant.now();
        String stamp = java.time.Instant.ofEpochMilli(instant.toEpochMilli()).toString();
        if (status.equals("down")) {
          var outage =
              ServiceOutages.save(
                  db,
                  ci,
                  map(
                      "operation",
                      "create",
                      "record_revision",
                      Service.readRecord(db, "cmdb_ci", ci).get("revision"),
                      "kind",
                      "outage",
                      "begin",
                      stamp,
                      "end",
                      "",
                      "summary",
                      "Circuit interruption at " + data.get("name"),
                      "evidence",
                      "Port " + port,
                      "request_id",
                      Values.id()),
                  System.currentTimeMillis());
          data.put("outage", outage.get("outage_id"));
        } else {
          String outage = data.get("outage").toString();
          var record =
              db.one("SELECT revision,begin_ms FROM os_ci_outage WHERE outage_id=?", outage);
          long began = ((Number) record.get("begin_ms")).longValue();
          require(instant.toEpochMilli() >= began, "Recovery must follow the outage");
          // A transition serialized in the same millisecond still follows the opening event.
          long ended = Math.max(began + 1, instant.toEpochMilli());
          ServiceOutages.save(
              db,
              ci,
              map(
                  "operation",
                  "close",
                  "record_revision",
                  Service.readRecord(db, "cmdb_ci", ci).get("revision"),
                  "outage_id",
                  outage,
                  "revision",
                  record.get("revision"),
                  "end",
                  java.time.Instant.ofEpochMilli(ended).toString(),
                  "evidence",
                  "Restored network port " + port),
              Math.max(ended, System.currentTimeMillis()));
        }
        data.put("state", status);
        data.put("changed_at",instant.toEpochMilli());
        var saved = put(db, "network-port", port, b.get("revision"), data);
        save(
            db,
            "port-event",
            key(port, saved.get("revision")),
            0,
            map(
                "outage",
                data.get("outage"),
                "state",
                status,
                "sites",
                impacted,
                "at",
                now(),
                "by",
                db.viewerId));
      }
      return map(
          "port",
          get(db, "network-port", port),
          "circuits_on_port",
          impacted,
          "affected_circuits",
          impacted.stream()
              .map(Values::object)
              .filter(c -> "active".equals(c.get("activation")))
              .toList());
    }
    String bundle = id(b, "bundle_id"),
        site = text(b, "location", 120),
        circuit = key(bundle, site);
    var order = definition(db, "site-bundle", bundle);
    SalesOrders.account(db, order.get("account").toString());
    require("ordered".equals(order.get("state")), "An accepted order is required");
    var item = object(object(order.get("sites")).getOrDefault(site, map()));
    require(!item.isEmpty(), "Select an ordered site");
    var current = get(db, "network-circuit", circuit);
    var data = object(current.get("definition"));
    if (op.equals("reserve_circuit")) {
      int units = integer(b.get("quantity"), 1, 1000000);
      var path = new ArrayList<String>();
      for (var input : batch(b.get("entries"), 32)) {
        String port = EnterpriseData.id(input);
        require(!path.contains(port), "Repeated port");
        path.add(port);
      }
      require(!path.isEmpty(), "Choose a path");
      if (!data.isEmpty()) {
        require(
            path.equals(data.get("path")) && Objects.equals(units, data.get("units")),
            "Site has another reservation");
        return current;
      }
      for (String port : path) {
        var row = get(db, "network-port", port);
        var link = definition(db, "network-port", port);
        var assigned = object(link.get("circuits"));
        long used = assigned.values().stream().mapToLong(v -> ((Number) v).longValue()).sum();
        require(
            link.get("state").equals("up")
                && used + units <= ((Number) link.get("capacity")).longValue(),
            "Insufficient healthy path capacity");
        assigned.put(circuit, units);
        save(db, "network-port", port, row.get("revision"), link);
      }
      return put(
          db,
          "network-circuit",
          circuit,
          0,
          map(
              "bundle",
              bundle,
              "site",
              site,
              "opportunity",
              item.get("opportunity"),
              "units",
              units,
              "path",
              path,
              "state",
              "reserved"));
    }
    require(!data.isEmpty(), "Reserve a path first");
    var conditions = new LinkedHashMap<String, Object>();
    for (Object port : (List<?>) data.get("path"))
      conditions.put(port.toString(), definition(db, "network-port", port.toString()).get("state"));
    if (op.equals("activate_circuit") && !"active".equals(data.get("state"))) {
      require(!conditions.containsValue("down"), "Restore the path before activation");
      var sale = definition(db, "opportunity", data.get("opportunity").toString());
      require(sale.containsKey("order_id"), "Fulfillment order required");
      data.put("state", "active");
      data.put("activated", now());
      put(db, "network-circuit", circuit, b.get("revision"), data);
    }
    return map(
        "circuit",
        get(db, "network-circuit", circuit),
        "path_conditions",
        conditions,
        "available",
        List.of("primary","backup").contains(CircuitAssurance.condition(db,data)));
  }
}
