package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class EndpointCollectors {
  private EndpointCollectors() {}

  static void sourceRetired(
      Store.Db db, String source, String resource, Map<String, Object> previous) throws Exception {
    String identity = ServiceTopology.identity(source, resource);
    retireEnrollment(db, identity, resource, previous);
    reconsiderAuthority(db, object(get(db, "intune-binding", identity).get("definition")));
  }

  static void reconsiderAuthority(Store.Db db, Map<String, Object> authority) throws Exception {
    if (authority.isEmpty()) return;
    String resource = authority.get("resource").toString();
    String identity = ServiceTopology.identity(authority.get("source").toString(), resource);
    var cloud = object(get(db, "discovered-ci", identity).get("definition"));
    if (cloud.containsKey("retirement")) retireEnrollment(db, identity, resource, null);
  }

  static void retireEnrollment(
      Store.Db db, String identity, String resource, Map<String, Object> previous)
      throws Exception {
    var binding = object(get(db, "endpoint-cloud-binding", identity).get("definition"));
    if (binding.isEmpty()) return;
    String id = binding.get("collector").toString();
    var row = get(db, "endpoint-enrollment", id);
    var enrollment = object(row.get("definition"));
    if ("retired".equals(enrollment.get("state"))) return;
    if (previous != null)
      enrollment.put("formerly_affected_services", ServiceTopology.affected(previous, resource));
    if (IntuneInventory.observes(db, enrollment.get("ci"))) {
      save(db, "endpoint-enrollment", id, row.get("revision"), enrollment);
      return;
    }
    var seat = object(enrollment.getOrDefault("seat", map()));
    var user =
        db.one(
            "SELECT active,role,auth_revision,password_reset_required FROM os_user WHERE user_id=?",
            enrollment.get("owner"));
    boolean authorized =
        user != null
            && user.get("active").equals(1)
            && "admin".equals(user.get("role"))
            && user.get("password_reset_required").equals(0)
            && ((Number) user.get("auth_revision")).longValue()
                == ((Number) enrollment.get("owner_revision")).longValue();
    if (!authorized
        || !seat.isEmpty()
            && !get(db, "entitlement", seat.get("license").toString())
                .get("revision")
                .equals(seat.get("revision"))) {
      enrollment.put("state", "retirement_review");
      save(db, "endpoint-enrollment", id, row.get("revision"), enrollment);
      return;
    }
    String actor = db.viewerId, role = db.viewerRole;
    try {
      db.viewerId = enrollment.get("owner").toString();
      db.viewerRole = "admin";
      save(db, "endpoint-enrollment", id, row.get("revision"), enrollment);
      run(
          db,
          "retire_endpoint",
          map(
              "collector_id",
              id,
              "note",
              "Authoritative source retired " + resource,
              "revision",
              get(db, "endpoint-enrollment", id).get("revision")));
    } finally {
      db.viewerId = actor;
      db.viewerRole = role;
    }
  }

  static Object run(Store.Db db, String op, Map<String, Object> b) throws Exception {
    String id = id(b, "collector_id"), source = "endpoint-" + id;
    if (op.equals("enroll_endpoint")) {
      String user = id(b, "user_id");
      DutyRosters.responder(db, user);
      var inventory = definition(db, "service-map", text(b, "source", 80));
      var resource =
          object(
              object(inventory.get("resources")).getOrDefault(text(b, "resource_id", 200), map()));
      require(resource.containsKey("ci_id"), "Choose a discovered cloud or asset component");
      String ci = resource.get("ci_id").toString();
      String cloudKey =
          ServiceTopology.identity(b.get("source").toString(), b.get("resource_id").toString());
      save(db, "endpoint-cloud-binding", cloudKey, 0, map("collector", id));
      var cloudAlias = object(get(db, "component-alias", cloudKey).get("definition"));
      save(
          db,
          "component-alias",
          ServiceTopology.identity(source, id),
          0,
          map("component", cloudAlias.getOrDefault("component", cloudKey)));
      save(db, "discovered-ci", ServiceTopology.identity(source, id), 0, map("ci_id", ci));
      return put(
          db,
          "endpoint-enrollment",
          id,
          0,
          map(
              "owner",
              db.viewerId,
              "owner_revision",
              AgentPlans.account(db, db.viewerId).get("auth_revision"),
              "ci",
              ci,
              "user",
              user,
              "source",
              b.get("source"),
              "resource",
              b.get("resource_id"),
              "interval",
              integer(b.get("minutes"), 1, 10080) * 60000L,
              "state",
              "enrolled",
              "platform",
              choice(b, "platform", "Linux", "Windows")));
    }
    var enrolled = get(db, "endpoint-enrollment", id);
    var data = definition(db, "endpoint-enrollment", id);
    var last = get(db, "endpoint-observation", id);
    var observed = object(last.get("definition"));
    if (op.equals("endpoint_status")) {
      long seen = ((Number) observed.getOrDefault("received", 0)).longValue();
      var cloud = definition(db, "service-map", data.get("source").toString());
      return map(
          "enrollment",
          enrolled,
          "endpoint_facts",
          last,
          "cloud_facts",
          object(cloud.get("resources")).get(data.get("resource")),
          "affected_services",
          ServiceTopology.affected(cloud, data.get("resource").toString()),
          "stale",
          System.currentTimeMillis() - seen > ((Number) data.get("interval")).longValue() * 2);
    }
    require(
        !"retired".equals(data.get("state"))
            && (!"retirement_review".equals(data.get("state")) || op.equals("retire_endpoint")),
        "Collector retired");
    if (op.equals("endpoint_seat")) {
      require(!data.containsKey("seat"), "Seat already bound");
      String license = id(b, "license_id"), user = id(b, "user_id");
      var rights = get(db, "entitlement", license);
      AssetPlanning.command(
          db,
          map(
              "operation",
              "allocate",
              "license_id",
              license,
              "revision",
              rights.get("revision"),
              "user_id",
              user));
      data.put(
          "seat",
          map(
              "license",
              license,
              "user",
              user,
              "revision",
              get(db, "entitlement", license).get("revision")));
      return put(db, "endpoint-enrollment", id, b.get("revision"), data);
    }
    if (op.equals("retire_endpoint")) {
      var seat = object(data.getOrDefault("seat", map()));
      if (!seat.isEmpty() && !"retirement_review".equals(data.get("state")))
        AssetPlanning.command(
            db,
            map(
                "operation",
                "release",
                "license_id",
                seat.get("license"),
                "revision",
                seat.get("revision"),
                "user_id",
                seat.get("user")));
      if (!seat.isEmpty() && "retirement_review".equals(data.get("state")))
        data.put("seat_requires_reconciliation", true);
      data.putAll(map("state", "retired", "reason", text(b, "note", 1200), "retired", now()));
      var topology = get(db, "service-map", source);
      int next =
          ((Number) object(topology.get("definition")).getOrDefault("sequence", 0)).intValue() + 1;
      ServiceTopology.inventory(
          db, source, map("sequence", next, "resources", List.of(), "services", List.of()));
      return put(db, "endpoint-enrollment", id, b.get("revision"), data);
    }
    require(db.viewerId.equals(data.get("user")), "Enrolled collector identity required");
    var input = object(b.get("manifest"));
    keys(input, "platform", "hostname", "machine", "software");
    require(data.get("platform").equals(input.get("platform")), "Unexpected platform");
    var normalized = new TreeSet<String>();
    for (Object app : batch(input.get("software"), 512)) normalized.add(WorkJobs.text(app, 160));
    var facts =
        map(
            "platform",
            data.get("platform"),
            "hostname",
            text(input, "hostname", 160),
            "machine",
            text(input, "machine", 120),
            "software",
            normalized.stream().toList());
    int sequence = integer(b.get("sequence"), 1, Integer.MAX_VALUE);
    int prior = ((Number) observed.getOrDefault("sequence", 0)).intValue();
    if (sequence == prior) {
      require(facts.equals(observed.get("facts")), "Sequence reused with different observations");
      return last;
    }
    require(sequence > prior, "Stale collector sequence");
    ServiceTopology.inventory(
        db,
        source,
        map(
            "sequence",
            sequence,
            "services",
            List.of(),
            "resources",
            List.of(
                map(
                    "id",
                    id,
                    "type",
                    "endpoint",
                    "title",
                    facts.get("hostname"),
                    "depends_on",
                    List.of()))));
    return put(
        db,
        "endpoint-observation",
        id,
        last.get("revision"),
        map(
            "facts",
            facts,
            "sequence",
            sequence,
            "received",
            System.currentTimeMillis(),
            "ci",
            data.get("ci"),
            "collector",
            id));
  }
}
