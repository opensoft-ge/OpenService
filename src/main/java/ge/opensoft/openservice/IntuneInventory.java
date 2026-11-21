package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

final class IntuneInventory {
  private IntuneInventory() {}

  static final String ORIGIN = "https://graph.microsoft.com",
      PATH = "/v1.0/deviceManagement/managedDevices";

  static String source(String alias) {
    return "intune-" + key(alias).substring(0, 24);
  }

  static String continuation(Object value) {
    if (value == null) return "";
    java.net.URI uri;
    try {
      uri = new java.net.URI(WorkJobs.text(value, 2048));
    } catch (java.net.URISyntaxException malformed) {
      throw new Failure(400, "Invalid Graph continuation");
    }
    require(
        ORIGIN.equals(uri.getScheme() + "://" + uri.getRawAuthority())
            && PATH.equals(uri.getRawPath())
            && uri.getRawFragment() == null
            && uri.getRawQuery() != null,
        "Invalid Graph continuation");
    return Connectors.path(PATH + "?" + uri.getRawQuery());
  }

  static Object run(Store store, Store.Db db, String op, Map<String, Object> b) throws Exception {
    String alias = text(b, "alias", 80), source = source(alias);
    if (op.equals("intune_authority")) {
      String cloud = text(b, "source", 80);
      require(!cloud.equals(source), "Choose an independent cloud inventory");
      definition(db, "service-map", cloud);
      return put(db, "intune-authority", source, b.get("revision"), map("source", cloud));
    }
    if (op.equals("intune_import")) {
      String run = id(b, "new_id");
      var row = get(db, "intune-run", run);
      var state = object(row.get("definition"));
      var connection = ConnectorConsole.page(db, alias);
      require(ORIGIN.equals(connection.get("origin")), "Bind a Microsoft Graph connection");
      if (!state.isEmpty()) {
        require(alias.equals(state.get("alias")), "Import belongs to a different connection");
        if (Boolean.TRUE.equals(state.get("complete"))) return row;
        require(
            connection.get("revision").equals(state.get("connection_revision")),
            "Connection changed during import. Start a new import.");
      }
      String path =
          state
              .getOrDefault(
                  "next",
                  PATH
                      + "?$select=id,deviceName,azureADDeviceId,operatingSystem,serialNumber&$top=50")
              .toString();
      var response = Connectors.fetch(store, db, map("alias", alias, "path", path));
      var page = object(JSON.readValue((byte[]) response.get("content"), Object.class));
      return accept(db, run, alias, connection.get("revision"), page);
    }
    String device = text(b, "reference", 160), binding = ServiceTopology.identity(source, device);
    var inventory = definition(db, "service-map", source);
    var facts = object(definition(db, "intune-facts", source).get("devices"));
    require(facts.containsKey(device), "Device is absent from last complete inventory");
    if (op.equals("intune_device"))
      return map(
          "facts",
          facts.get(device),
          "source",
          source,
          "observation",
          object(inventory.get("resources")).get(device),
          "correction",
          get(db, "intune-binding", binding));
    String other = text(b, "source", 80), resource = text(b, "resource_id", 200);
    require(!other.equals(source), "Select an independent source");
    var peer =
        object(
            object(definition(db, "service-map", other).get("resources"))
                .getOrDefault(resource, map()));
    require(peer.containsKey("ci_id"), "Choose a current authoritative observation");
    String ci = peer.get("ci_id").toString();
    var row = get(db, "discovered-ci", binding);
    var previousAuthority = object(get(db, "intune-binding", binding).get("definition"));
    var evidence =
        put(
            db,
            "intune-binding",
            binding,
            b.get("revision"),
            map("source", other, "resource", resource, "ci", ci, "reviewer", db.viewerId));
    var canonical = ServiceTopology.identity(other, resource);
    var aliasRow = get(db, "component-alias", binding);
    var parent = object(get(db, "component-alias", canonical).get("definition"));
    save(
        db,
        "component-alias",
        binding,
        aliasRow.get("revision"),
        map("component", parent.getOrDefault("component", canonical)));
    save(db, "discovered-ci", binding, row.get("revision"), map("ci_id", ci));
    object(object(inventory.get("resources")).get(device)).put("ci_id", ci);
    save(db, "service-map", source, get(db, "service-map", source).get("revision"), inventory);
    EndpointCollectors.reconsiderAuthority(db, previousAuthority);
    return evidence;
  }

  static Object accept(
      Store.Db db, String run, String alias, Object revision, Map<String, Object> page)
      throws Exception {
    var row = get(db, "intune-run", run);
    var state = object(row.get("definition"));
    String source = source(alias);
    var current = get(db, "intune-facts", source);
    Object base = state.getOrDefault("base", current.get("revision"));
    require(base.equals(current.get("revision")), "A more recent inventory was published");
    var devices = object(state.getOrDefault("devices", map()));
    for (Object raw : batch(page.get("value"), 200)) {
      var device = object(raw);
      String id = text(device, "id", 160);
      var fact =
          map(
              "id",
              id,
              "hostname",
              text(device, "deviceName", 160),
              "platform",
              text(device, "operatingSystem", 80));
      for (String name : List.of("serialNumber", "azureADDeviceId"))
        if (device.get(name) != null) fact.put(name, device.get(name).toString());
      require(
          devices.putIfAbsent(id, fact) == null || devices.get(id).equals(fact),
          "Conflicting observations for device");
    }
    require(devices.size() <= 200, "Designated fleet exceeds 200 devices");
    String next = continuation(page.get("@odata.nextLink"));
    var cursors = new ArrayList<>((List<?>) state.getOrDefault("cursors", List.of()));
    if (!next.isEmpty()) {
      String digest = key(next);
      require(
          !cursors.contains(digest) && cursors.size() < 200, "Repeated or excessive Graph pages");
    }
    if (next.isEmpty()) {
      matchCloud(db, source, devices);
      var resources =
          devices.values().stream()
              .map(Values::object)
              .map(
                  f ->
                      map(
                          "id",
                          f.get("id"),
                          "title",
                          f.get("hostname"),
                          "type",
                          "managed endpoint",
                          "depends_on",
                          List.of()))
              .toList();
      var topology = object(get(db, "service-map", source).get("definition"));
      ServiceTopology.inventory(
          db,
          source,
          map(
              "sequence",
              ((Number) topology.getOrDefault("sequence", 0)).intValue() + 1,
              "resources",
              resources,
              "services",
              List.of()));
      put(
          db,
          "intune-facts",
          source,
          base,
          map("devices", devices, "run", run, "connection_revision", revision));
      return put(
          db,
          "intune-run",
          run,
          row.get("revision"),
          map("alias", alias, "complete", true, "count", devices.size()));
    }
    var visited = new ArrayList<Object>(cursors);
    visited.add(key(next));
    return put(
        db,
        "intune-run",
        run,
        row.get("revision"),
        map(
            "alias",
            alias,
            "connection_revision",
            revision,
            "base",
            base,
            "complete",
            false,
            "devices",
            devices,
            "next",
            next,
            "cursors",
            visited));
  }

  static void matchCloud(Store.Db db, String source, Map<String, Object> devices) throws Exception {
    var rule = object(get(db, "intune-authority", source).get("definition"));
    if (rule.isEmpty()) return;
    String cloud = rule.get("source").toString();
    var cloudResources = object(definition(db, "service-map", cloud).get("resources"));
    var previouslyBound = new HashSet<String>();
    for (var row : list(db, "intune-binding"))
      previouslyBound.add(object(row.get("definition")).get("ci").toString());
    var counts = new HashMap<Object, Integer>();
    for (Object raw : devices.values())
      counts.merge(object(raw).getOrDefault("azureADDeviceId", ""), 1, Integer::sum);
    for (var entry : devices.entrySet()) {
      var fact = object(entry.getValue());
      String directory = Objects.toString(fact.get("azureADDeviceId"), "");
      String binding = ServiceTopology.identity(source, entry.getKey());
      if (!get(db, "discovered-ci", binding).get("revision").equals(0)) continue;
      if (!directory.matches("(?i)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
          || !cloudResources.containsKey(directory)
          || counts.get(directory) != 1) continue;
      String ci = object(cloudResources.get(directory)).get("ci_id").toString();
      if (!previouslyBound.add(ci)) continue;
      var canonical = ServiceTopology.identity(cloud, directory);
      var alias = object(get(db, "component-alias", canonical).get("definition"));
      save(
          db,
          "component-alias",
          binding,
          0,
          map("component", alias.getOrDefault("component", canonical)));
      save(db, "discovered-ci", binding, 0, map("ci_id", ci));
      save(
          db,
          "intune-binding",
          binding,
          0,
          map("ci", ci, "source", cloud, "resource", directory, "basis", "azureADDeviceId"));
    }
  }

  static boolean observes(Store.Db db, Object ci) throws Exception {
    for (var binding : list(db, "intune-binding"))
      if (ci.equals(object(binding.get("definition")).get("ci"))) {
        var observed =
            object(get(db, "discovered-ci", binding.get("subject").toString()).get("definition"));
        if (!observed.isEmpty() && !observed.containsKey("retirement")) return true;
      }
    return false;
  }
}
