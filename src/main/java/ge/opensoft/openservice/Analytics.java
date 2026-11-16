package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.math.*;
import java.sql.SQLException;
import java.util.*;

/** Exact decimal accumulation over the complete authorized selection, independent of UI pages. */
final class Analytics {
    private Analytics() {}

    static Map<String, Object> fields(Map<String, Object> definition) {
        var fields = new LinkedHashMap<String, Object>();
        for (var field : TypedFields.rules(definition).entrySet())
            if (Set.of("integer", "decimal").contains(object(field.getValue()).get("type")))
                fields.put(field.getKey(), object(definition.get("fields")).get(field.getKey()));
        return fields;
    }

    static Map<String, Object> report(Store.Db db, String table, Map<String, String> args)
            throws SQLException {
        require(
                Set.of("field", "group_by", "q", "minimum", "maximum").containsAll(args.keySet()),
                "Unsupported analytics parameter");
        String field = args.getOrDefault("field", ""), group = args.getOrDefault("group_by", "");
        require(
                fields(FieldAccess.schema(db, Service.schema(db, table))).containsKey(field),
                "Choose a configured integer or decimal field");
        BigDecimal minimum = bound(args, "minimum"), maximum = bound(args, "maximum");
        require(minimum == null || maximum == null || minimum.compareTo(maximum) <= 0,
                "Minimum must not exceed maximum");
        var selection = Service.selection(db, table, args.getOrDefault("q", ""), "");
        require(
                group.isEmpty() || selection.columns().containsKey(group),
                "Unknown grouping field");
        String grouping =
                group.isEmpty() ? "''" : "COALESCE(" + selection.columns().get(group) + ",'')";
        var groups = new TreeMap<String, Totals>();
        if (group.isEmpty()) groups.put("", new Totals());
        try (var statement =
                        db.statement(
                                "SELECT COALESCE("
                                        + selection.columns().get(field)
                                        + ",''),"
                                        + grouping
                                        + " FROM os_record WHERE "
                                        + selection.where(),
                                selection.values().toArray());
                var rows = statement.executeQuery()) {
            while (rows.next()) {
                String value = rows.getString(1);
                if (included(value, minimum, maximum))
                    groups.computeIfAbsent(rows.getString(2), ignored -> new Totals()).add(value);
            }
        }
        var result = new ArrayList<Map<String, Object>>();
        groups.forEach((key, totals) -> result.add(totals.result(key)));
        return map("field", field, "group_by", group, "rows", result);
    }

    static BigDecimal bound(Map<String,String> args, String key) {
        if (args.getOrDefault(key, "").isBlank()) return null;
        String value = WorkJobs.text(args.get(key), 128);
        require(value.matches("-?[0-9]+(?:\\.[0-9]+)?"), "Use a plain decimal range bound");
        return new BigDecimal(value);
    }

    static boolean included(String text, BigDecimal minimum, BigDecimal maximum) {
        if (minimum == null && maximum == null) return true;
        if (text.isBlank()) return false;
        BigDecimal value = new BigDecimal(text);
        return (minimum == null || value.compareTo(minimum) >= 0)
                && (maximum == null || value.compareTo(maximum) <= 0);
    }

    static final class Totals {
        long count, values;
        BigDecimal sum = BigDecimal.ZERO, min, max;

        void add(String text) {
            count++;
            if (text.isBlank()) return;
            BigDecimal value = new BigDecimal(text);
            values++;
            sum = sum.add(value);
            if (min == null || value.compareTo(min) < 0) min = value;
            if (max == null || value.compareTo(max) > 0) max = value;
        }

        Map<String, Object> result(String group) {
            return map(
                    "group",
                    group,
                    "count",
                    Long.toString(count),
                    "value_count",
                    Long.toString(values),
                    "sum",
                    decimal(sum),
                    "min",
                    decimal(min),
                    "max",
                    decimal(max),
                    "average",
                    values == 0
                            ? null
                            : decimal(
                                    sum.divide(
                                            BigDecimal.valueOf(values), MathContext.DECIMAL128)));
        }

        private static String decimal(BigDecimal value) {
            return value == null ? null : value.stripTrailingZeros().toPlainString();
        }
    }
}
