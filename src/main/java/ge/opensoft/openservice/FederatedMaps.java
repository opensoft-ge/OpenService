package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

final class FederatedMaps {
    private FederatedMaps() {}

    record Graph(
            Map<String, Set<String>> edges,
            Map<String, List<Object>> facts,
            Map<String, Set<String>> roots,
            Map<String, List<Object>> manualLinks) {}

    static String canonical(Map<String, String> aliases, String source, String resource) {
        String identity = ServiceTopology.identity(source, resource);
        return aliases.getOrDefault(identity, identity);
    }

    static Graph graph(Store.Db db) throws Exception {
        Map<String, String> aliases = new HashMap<>();
        for (var row : list(db, "component-alias"))
            aliases.put(
                    row.get("subject").toString(),
                    object(row.get("definition")).get("component").toString());
        var graph = new Graph(new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), new TreeMap<>());
        for (var row : list(db, "service-map")) {
            String source = row.get("subject").toString();
            var snapshot = object(row.get("definition"));
            for (var entry : object(snapshot.get("resources")).entrySet()) {
                String node = canonical(aliases, source, entry.getKey());
                var fact = object(entry.getValue());
                var dependencies = graph.edges.computeIfAbsent(node, k -> new TreeSet<>());
                for (Object dep : (List<?>) fact.get("depends_on"))
                    dependencies.add(canonical(aliases, source, dep.toString()));
                long age =
                        Duration.between(
                                        LocalDateTime.parse(
                                                        snapshot.get("observed")
                                                                .toString()
                                                                .replace(' ', 'T'))
                                                .toInstant(ZoneOffset.UTC),
                                        Instant.now())
                                .toSeconds();
                graph.facts
                        .computeIfAbsent(node, k -> new ArrayList<>())
                        .add(
                                map(
                                        "source",
                                        source,
                                        "resource_id",
                                        entry.getKey(),
                                        "title",
                                        fact.get("title"),
                                        "type",
                                        fact.get("type"),
                                        "source_dependencies",
                                        fact.get("depends_on"),
                                        "ci_id",
                                        fact.get("ci_id"),
                                        "observed",
                                        snapshot.get("observed"),
                                        "age_seconds",
                                        age,
                                        "sequence",
                                        snapshot.get("sequence")));
            }
            for (var entry : object(snapshot.get("services")).entrySet()) {
                var roots = new TreeSet<String>();
                for (Object root : (List<?>) object(entry.getValue()).get("roots"))
                    roots.add(canonical(aliases, source, root.toString()));
                graph.roots.put(source + " :: " + entry.getKey(), roots);
            }
        }
        for (var edge : list(db, "component-link")) {
            var d = object(edge.get("definition"));
            String from = d.get("component").toString(), to = d.get("dependency").toString();
            if (graph.edges.containsKey(from) && graph.edges.containsKey(to)) {
                graph.edges.get(from).add(to);
                graph.manualLinks
                        .computeIfAbsent(from, k -> new ArrayList<>())
                        .add(
                                map(
                                        "dependency",
                                        to,
                                        "operator",
                                        d.get("operator"),
                                        "revision",
                                        edge.get("revision")));
            }
        }
        return graph;
    }

    static Set<String> trace(Graph graph, Collection<String> roots) {
        var queue = new ArrayDeque<>(roots);
        var seen = new TreeSet<String>();
        while (!queue.isEmpty()) {
            String next = queue.remove();
            if (seen.add(next)) queue.addAll(graph.edges.get(next));
        }
        return seen;
    }

    static boolean matches(String resource, List<Object> kinds) {
        if (kinds.containsAll(List.of("PodAddress", "ip"))) {
            int slash = resource.indexOf('/');
            if (slash < 0 || !resource.substring(0, slash).matches("vpc-[0-9a-f]+")) return false;
            try {
                java.net.InetAddress.ofLiteral(resource.substring(slash + 1));
                return true;
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }
        return kinds.containsAll(List.of("DeclaredDatabase", "RDSInstance"))
                && resource.matches("arn:aws[a-z-]*:rds:[a-z0-9-]+:[0-9]{12}:db:[A-Za-z0-9-]+");
    }

    static Object run(Store store, Store.Db db, String op, Map<String, Object> b) throws Exception {
        if (op.equals("reconcile")) {
            String first = text(b, "source", 80), second = text(b, "peer", 80);
            require(!first.equals(second), "Choose two distinct sources");
            var a = object(definition(db, "service-map", first).get("resources"));
            var other = object(definition(db, "service-map", second).get("resources"));
            var shared = new TreeMap<String, Object>();
            for (String resource : a.keySet()) {
                if (!other.containsKey(resource)) continue;
                var kinds =
                        List.of(
                                object(a.get(resource)).get("type"),
                                object(other.get(resource)).get("type"));
                if (!matches(resource, kinds)) continue;
                String inventory =
                        Set.of("ip", "RDSInstance").contains(kinds.getFirst()) ? first : second;
                String component = key("observed-identity", inventory, resource);
                for (String source : List.of(first, second)) {
                    String ref = ServiceTopology.identity(source, resource);
                    var old = get(db, "component-alias", ref);
                    var current = object(old.get("definition"));
                    require(
                            current.isEmpty() || component.equals(current.get("component")),
                            "Resolve the previously reviewed component mapping first");
                    if (current.isEmpty())
                        save(
                                db,
                                "component-alias",
                                ref,
                                0,
                                map(
                                        "component",
                                        component,
                                        "source",
                                        source,
                                        "resource_id",
                                        resource));
                }
                shared.put(resource, component);
            }
            return shared;
        }
        if (op.equals("bind")) {
            String source = text(b, "source", 80), resource = text(b, "resource_id", 200);
            require(
                    object(definition(db, "service-map", source).get("resources"))
                            .containsKey(resource),
                    "Choose an observed resource");
            return put(
                    db,
                    "component-alias",
                    ServiceTopology.identity(source, resource),
                    b.get("revision"),
                    map(
                            "component",
                            text(b, "component", 100),
                            "source",
                            source,
                            "resource_id",
                            resource));
        }
        var g = graph(db);
        if (op.equals("map")) {
            String service = text(b, "source", 80) + " :: " + text(b, "service_id", 200);
            require(g.roots.containsKey(service), "Choose a discovered service");
            var components = new TreeMap<String, Object>();
            for (String id : trace(g, g.roots.get(service)))
                components.put(
                        id,
                        map(
                                "evidence",
                                g.facts.get(id),
                                "depends_on",
                                g.edges.get(id),
                                "manual_links",
                                g.manualLinks.getOrDefault(id, List.of())));
            return map("service", service, "components", components);
        }
        String component = text(b, "component", 100);
        require(g.edges.containsKey(component), "Unknown component");
        if (op.equals("link")) {
            String dep = text(b, "dependency", 100);
            require(
                    !component.equals(dep) && g.edges.containsKey(dep),
                    "Choose a distinct existing dependency");
            return put(
                    db,
                    "component-link",
                    key(component, dep),
                    b.get("revision"),
                    map("component", component, "dependency", dep, "operator", db.viewerId));
        }
        var impacted = new ArrayList<String>();
        var related = new HashSet<String>();
        related.add(component);
        for (var service : g.roots.entrySet()) {
            var nodes = trace(g, service.getValue());
            if (nodes.contains(component)) {
                impacted.add(service.getKey());
                related.addAll(nodes);
            }
        }
        var incidents = new TreeSet<String>();
        for (String node : related)
            for (Object item : g.facts.get(node)) {
                var fact = object(item);
                var monitor =
                        object(
                                get(
                                                db,
                                                "monitor-state",
                                                ServiceTopology.identity(
                                                        fact.get("source").toString(),
                                                        fact.get("resource_id").toString()))
                                        .get("definition"));
                if (monitor.containsKey("episode"))
                    incidents.add(
                            definition(db, "service-episode", monitor.get("episode").toString())
                                    .get("incident")
                                    .toString());
            }
        return map(
                "component",
                component,
                "affected_services",
                impacted,
                "related_open_incidents",
                incidents);
    }
}
