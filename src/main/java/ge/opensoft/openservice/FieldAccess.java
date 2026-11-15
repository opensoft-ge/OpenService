package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Field-read policy is applied before querying as well as before serialization. */
final class FieldAccess {
    private FieldAccess() {}

    static boolean allowed(Store.Db db, Map<String, Object> definition, String field) {
        if (db.viewerId == null || "admin".equals(db.viewerRole)) return true;
        var rule = object(TypedFields.rules(definition).getOrDefault(field, map()));
        return !rule.containsKey("read_roles")
                || ((List<?>) rule.get("read_roles")).contains(db.viewerRole);
    }

    static Map<String, Object> visible(
            Store.Db db, Map<String, Object> definition, Map<String, Object> data) {
        var result = new LinkedHashMap<>(data);
        result.keySet().removeIf(field -> !allowed(db, definition, field));
        return result;
    }

    static void writing(Store.Db db, Map<String, Object> definition, Map<String, Object> payload) {
        // Reject before comparing with stored values: a no-op must not reveal a hidden value.
        for (String field : payload.keySet())
            if (!allowed(db, definition, field))
                throw new Failure(403, "Field is not writable: " + field);
    }

    static Map<String, Object> serialize(Store.Db db, Map<String, Object> item)
            throws SQLException {
        return visible(
                db, Service.schema(db, item.get("table").toString()), Service.serialize(item));
    }

    static Map<String, Object> schema(Store.Db db, Map<String, Object> definition) {
        var result = new LinkedHashMap<>(definition);
        result.put("fields", visible(db, definition, object(definition.get("fields"))));
        result.put("field_rules", visible(db, definition, TypedFields.rules(definition)));
        return result;
    }

    static Map<String, Object> event(Store.Db db, Map<String, Object> event) throws SQLException {
        if ("admin".equals(db.viewerRole)) return event;
        var copy = new LinkedHashMap<>(event);
        var detail = object(event.get("detail"));
        var filtered = map();
        if (Set.of("note", "comment").contains(event.get("action")))
            filtered.put("body", detail.get("body"));
        else {
            var record =
                    db.one(
                            "SELECT \"table\" FROM os_record WHERE sys_id=?",
                            event.get("record_id"));
            if (record != null) {
                var definition = Service.schema(db, record.get("table").toString());
                for (String snapshot : List.of("before", "after")) {
                    if (detail.get(snapshot) instanceof Map<?, ?>) {
                        var values = visible(db, definition, object(detail.get(snapshot)));
                        values.keySet().retainAll(object(definition.get("fields")).keySet());
                        filtered.put(snapshot, values);
                    }
                }
            }
        }
        copy.put("detail", filtered);
        return copy;
    }
}
