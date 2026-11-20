package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class InventoryEvidence {
    private InventoryEvidence() {}

    static void snapshot(
            Store.Db db,
            String source,
            Map<String, Object> last,
            Map<String, Object> resources,
            int sequence)
            throws Exception {
        for (var entry : object(last.getOrDefault("resources", map())).entrySet()) {
            if (resources.containsKey(entry.getKey())) continue;
            String ref = ServiceTopology.identity(source, entry.getKey());
            var row = get(db, "discovered-ci", ref);
            var registration = object(row.get("definition"));
            registration.put(
                    "retirement",
                    map(
                            "source",
                            source,
                            "resource",
                            entry.getKey(),
                            "sequence",
                            sequence,
                            "at",
                            now()));
            save(db, "discovered-ci", ref, row.get("revision"), registration);
        }
    }

    static Map<String, Object> retirements(Store.Db db, String source) throws Exception {
        var result = map();
        for (var row : list(db, "discovered-ci")) {
            var registration = object(row.get("definition"));
            var retirement = object(registration.getOrDefault("retirement", map()));
            if (source.equals(retirement.get("source")))
                result.put(
                        retirement.get("resource").toString(),
                        map(
                                "ci_id",
                                registration.get("ci_id"),
                                "sequence",
                                retirement.get("sequence"),
                                "at",
                                retirement.get("at")));
        }
        return result;
    }

    static Object command(Store.Db db, String op, Map<String, Object> b) throws Exception {
        if (Set.of("collection", "failed_collect").contains(op)) {
            String source = text(b, "source", 80);
            var inventory = object(get(db, "service-map", source).get("definition"));
            var status = get(db, "collection-failure", source);
            if (op.equals("failed_collect")) {
                int seq = integer(b.get("sequence"), 1, Integer.MAX_VALUE);
                require(
                        seq > ((Number) inventory.getOrDefault("sequence", 0)).intValue()
                                && seq
                                        >= ((Number)
                                                        object(status.get("definition"))
                                                                .getOrDefault("sequence", 0))
                                                .intValue(),
                        "Report a current collection attempt");
                return put(
                        db,
                        "collection-failure",
                        source,
                        status.get("revision"),
                        map("sequence", seq, "at", now(), "note", text(b, "note", 1000)));
            }
            return map(
                    "schedule",
                    get(db, "inventory-schedule", source),
                    "refresh",
                    get(db, "inventory-progress", source),
                    "last_good_sequence",
                    inventory.get("sequence"),
                    "last_good_at",
                    inventory.get("observed"),
                    "retired",
                    retirements(db, source),
                    "last_failure",
                    status.get("definition"));
        }
        String component = text(b, "component", 100);
        var facts = FederatedMaps.graph(db).facts().get(component);
        require(facts != null, "Choose an observed component");
        var available = facts.stream().map(Values::object).toList();
        var row = get(db, "field-precedence", component);
        var preferences = object(row.get("definition"));
        if (op.equals("prefer")) {
            String field = choice(b, "field", "title", "type", "ci_id"),
                    source = text(b, "source", 80),
                    resource = text(b, "resource_id", 200);
            require(
                    available.stream()
                            .anyMatch(
                                    f ->
                                            source.equals(f.get("source"))
                                                    && resource.equals(f.get("resource_id"))),
                    "Choose a current observation of this component");
            preferences.put(field, map("source", source, "resource_id", resource));
            return put(db, "field-precedence", component, b.get("revision"), preferences);
        }
        var result = map();
        for (String field : List.of("title", "type", "ci_id")) {
            var selected = available.getFirst();
            boolean pinned = preferences.containsKey(field);
            if (pinned) {
                var preference = object(preferences.get(field));
                selected =
                        available.stream()
                                .filter(f -> f.entrySet().containsAll(preference.entrySet()))
                                .findFirst()
                                .orElse(null);
            }
            result.put(
                    field,
                    selected == null
                            ? map(
                                    "status",
                                    "source_unavailable",
                                    "preferred",
                                    preferences.get(field))
                            : map(
                                    "value",
                                    selected.get(field),
                                    "source",
                                    selected.get("source"),
                                    "resource_id",
                                    selected.get("resource_id"),
                                    "sequence",
                                    selected.get("sequence"),
                                    "pinned",
                                    pinned));
        }
        return map("component", component, "fields", result, "observations", available);
    }
}
