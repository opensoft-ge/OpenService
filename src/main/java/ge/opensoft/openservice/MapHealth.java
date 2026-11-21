package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class MapHealth {
    private MapHealth() {}

    static List<String> impact(FederatedMaps.Graph graph, String node) {
        return graph.roots().entrySet().stream()
                .filter(e -> FederatedMaps.trace(graph, e.getValue()).contains(node))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    static Object command(Store store, Store.Db db, String op, Map<String, Object> b)
            throws Exception {
        var graph = FederatedMaps.graph(db);
        if (op.equals("owner")) {
            String service = text(b, "service", 240);
            require(graph.roots().containsKey(service), "Choose a mapped service");
            return put(
                    db,
                    "service-owner",
                    key(service),
                    b.get("revision"),
                    map("service", service, "owner", DutyRosters.responder(db, b.get("owner_id"))));
        }
        var owned = new HashSet<String>();
        for (var row : list(db, "service-owner")) {
            var owner = object(row.get("definition"));
            if (db.viewerId.equals(owner.get("owner"))) owned.add(owner.get("service").toString());
        }
        boolean admin = "admin".equals(db.viewerRole);
        if (op.equals("health")) {
            long maxAge = integer(b.get("days"), 1, 365) * 86400L;
            var issues = new ArrayList<>();
            var names = new TreeMap<String, Set<String>>();
            for (var entry : graph.facts().entrySet())
                for (var raw : entry.getValue()) {
                    var affected = impact(graph, entry.getKey());
                    if (!admin && (affected.isEmpty() || !owned.containsAll(affected))) continue;
                    var fact = object(raw);
                    String label =
                            Objects.toString(fact.get("title"), "")
                                    .strip()
                                    .toLowerCase(Locale.ROOT);
                    if (!label.isEmpty())
                        names.computeIfAbsent(label, k -> new TreeSet<>()).add(entry.getKey());
                    if (((Number) fact.get("age_seconds")).longValue() > maxAge)
                        issues.add(
                                map(
                                        "kind",
                                        "stale_source",
                                        "component",
                                        entry.getKey(),
                                        "source",
                                        fact.get("source"),
                                        "age_seconds",
                                        fact.get("age_seconds"),
                                        "services",
                                        impact(graph, entry.getKey())));
                }
            var queue = new ArrayList<>();
            for (var pair : names.entrySet())
                if (pair.getValue().size() > 1)
                    queue.add(
                            map(
                                    "kind",
                                    "possible_duplicate",
                                    "title",
                                    pair.getKey(),
                                    "components",
                                    pair.getValue()));
            queue.addAll(issues);
            for (String service : graph.roots().keySet())
                if (admin && get(db, "service-owner", key(service)).get("revision").equals(0))
                    queue.add(map("kind", "missing_owner", "service", service));
            return map("queue", queue);
        }
        String from = text(b, "component", 200), to = text(b, "target", 200);
        require(
                !from.equals(to)
                        && graph.facts().containsKey(from)
                        && graph.facts().containsKey(to),
                "Choose two different observed components");
        var sourceImpact = impact(graph, from);
        var before = impact(graph, to);
        require(
                admin
                        || (!sourceImpact.isEmpty()
                                && !before.isEmpty()
                                && owned.containsAll(sourceImpact)
                                && owned.containsAll(before)),
                "Ownership of all affected services required");
        var reviewed = new ArrayList<Object>();
        for (Object raw : graph.facts().get(to)) {
            var targetFact = object(raw);
            reviewed.add(
                    List.of(
                            to,
                            targetFact.get("source"),
                            targetFact.get("resource_id"),
                            targetFact.get("sequence")));
        }
        var point = db.connection.setSavepoint();
        for (Object raw : graph.facts().get(from)) {
            var f = object(raw);
            String ref =
                    ServiceTopology.identity(
                            f.get("source").toString(), f.get("resource_id").toString());
            var old = get(db, "component-alias", ref);
            reviewed.add(List.of(ref, old.get("revision"), f.get("sequence")));
            save(
                    db,
                    "component-alias",
                    ref,
                    old.get("revision"),
                    map(
                            "source",
                            f.get("source"),
                            "resource_id",
                            f.get("resource_id"),
                            "component",
                            to,
                            "approved_by",
                            db.viewerId));
        }
        for (var row : list(db, "component-link")) {
            var data = object(row.get("definition"));
            if (!from.equals(data.get("component")) && !from.equals(data.get("dependency")))
                continue;
            reviewed.add(List.of(row.get("subject"), row.get("revision")));
            for (String prop : List.of("component", "dependency"))
                if (from.equals(data.get(prop))) data.put(prop, to);
            save(db, "component-link", row.get("subject").toString(), row.get("revision"), data);
        }
        var after = impact(FederatedMaps.graph(db), to);
        String signature = key(from, to, before, after, reviewed);
        boolean apply = op.equals("merge") && signature.equals(b.get("fingerprint"));
        if (!apply) db.connection.rollback(point);
        db.connection.releaseSavepoint(point);
        require(!op.equals("merge") || apply, "Map changed since the preview");
        return map(
                "before",
                before,
                "after",
                after,
                "fingerprint",
                signature,
                "merged",
                op.equals("merge"));
    }
}
