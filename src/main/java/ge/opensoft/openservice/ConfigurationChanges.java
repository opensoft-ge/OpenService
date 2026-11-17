package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.util.*;

/** A structural comparison of validated native definitions, not a patch or an approval token. */
final class ConfigurationChanges {
    private ConfigurationChanges() {}

    static Map<String, Object> describe(
            String table, Map<String, Object> previous, Map<String, Object> proposed) {
        var before = new LinkedHashMap<>(previous == null ? Map.<String, Object>of() : previous);
        var after = new LinkedHashMap<>(proposed);
        before.remove("version");
        after.remove("version");
        var entries = new ArrayList<Map<String, Object>>();
        compare("", before, after, entries);
        return map(
                "table", table,
                "kind", previous == null ? "created" : entries.isEmpty() ? "unchanged" : "updated",
                "entries", entries);
    }

    private static void compare(
            String parent, Map<String, Object> before, Map<String, Object> after,
            List<Map<String, Object>> entries) {
        var keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            boolean had = before.containsKey(key), has = after.containsKey(key);
            Object oldValue = before.get(key), newValue = after.get(key);
            String path = parent + "/" + key.replace("~", "~0").replace("/", "~1");
            // State order selects the initial state; field order controls the rendered form.
            boolean reordered = Set.of("/fields", "/states").contains(path) && had && has
                    && !new ArrayList<>(object(oldValue).keySet()).equals(new ArrayList<>(object(newValue).keySet()));
            if (!reordered && had == has && Objects.equals(oldValue, newValue)) continue;
            if (!reordered && oldValue instanceof Map<?, ?> && newValue instanceof Map<?, ?>) {
                compare(path, object(oldValue), object(newValue), entries);
            } else {
                entries.add(map(
                        "path", path,
                        "kind", !had ? "added" : !has ? "removed" : "changed",
                        "before_present", had, "after_present", has,
                        "before", oldValue, "after", newValue,
                        "before_text", had ? JSON.writeValueAsString(oldValue) : "Not present",
                        "after_text", has ? JSON.writeValueAsString(newValue) : "Not present"));
            }
        }
    }
}
