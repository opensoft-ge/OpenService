package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class ConfigurationChangesAcceptance {
    static void initialStateOrder(WorkflowSupport h) throws Exception {
        var fixture = new ConfigurationAcceptance(h);
        try {
            var definition = ConfigurationAcceptance.definition();
            object(definition.get("fields")).put("state", "State");
            definition.put("states", map("new", "New", "closed", "Closed"));
            definition.put("flow", map("new", List.of("closed"), "closed", List.of("new")));
            fixture.result(fixture.install(ConfigurationAcceptance.bundle(fixture.table, definition), false), 200);
            assertEquals("new", fixture.create(fixture.table, map("short_description", "Before promotion"), "admin").get("state"));
            var next = current(h, fixture.table);
            next.put("states", map("closed", "Closed", "new", "New"));
            var update = ConfigurationAcceptance.bundle(fixture.table, next);
            var preview = first(fixture.result(fixture.install(update, true), 200));
            assertEquals("updated", preview.get("kind"), "Changing the initial state must be visible");
            assertEquals("{\"new\":\"New\",\"closed\":\"Closed\"}", difference(preview, "/states").get("before_text"));
            assertEquals("{\"closed\":\"Closed\",\"new\":\"New\"}", difference(preview, "/states").get("after_text"));
            assertEquals("new", fixture.create(fixture.table, map("short_description", "After preview"), "admin").get("state"));
            assertEquals(preview, first(fixture.result(fixture.install(update, false), 200)));
            assertEquals("closed", fixture.create(fixture.table, map("short_description", "After promotion"), "admin").get("state"));
        } finally {
            fixture.cleanup();
        }
    }

    static void run(WorkflowSupport h) throws Exception {
        var fixture = new ConfigurationAcceptance(h);
        try {
            var definition = ConfigurationAcceptance.definition();
            var document = ConfigurationAcceptance.bundle(fixture.table, definition);
            var preview = fixture.result(fixture.install(document, true), 200);
            assertTrue(preview.containsKey("changes"), "Preview must explain configuration changes");
            var created = first(preview);
            assertEquals(fixture.table, created.get("table"));
            assertEquals("created", created.get("kind"));
            assertEquals(false, difference(created, "/label").get("before_present"));
            assertEquals("Reusable service cases", difference(created, "/label").get("after"));
            assertUnchanged(h, fixture.table, null, 0);

            var installed = fixture.result(fixture.install(document, false), 200);
            assertEquals(preview.get("changes"), installed.get("changes"));
            var existing = current(h, fixture.table);
            var unchanged = first(fixture.result(fixture.install(
                    ConfigurationAcceptance.bundle(fixture.table, existing), true), 200));
            assertEquals("unchanged", unchanged.get("kind"));
            assertEquals(List.of(), unchanged.get("entries"));
            assertUnchanged(h, fixture.table, existing, 1);

            var next = object(JSON.readValue(JSON.writeValueAsString(existing), Object.class));
            next.put("label", "Reviewed <script> & service");
            object(next.get("fields")).put("notes", "Review notes");
            object(object(next.get("field_rules")).get("quantity")).put("read_roles", List.of("admin"));
            object(object(next.get("field_rules")).get("short_description")).remove("max_length");
            var update = ConfigurationAcceptance.bundle(fixture.table, next);
            var changed = first(fixture.result(fixture.install(update, true), 200));
            assertEquals("updated", changed.get("kind"));
            assertEquals("changed", difference(changed, "/label").get("kind"));
            assertEquals("Reusable service cases", difference(changed, "/label").get("before"));
            assertEquals("Reviewed <script> & service", difference(changed, "/label").get("after"));
            assertEquals("changed", difference(changed, "/fields").get("kind"));
            assertEquals("Review notes", object(difference(changed, "/fields").get("after")).get("notes"));
            assertEquals(List.of("admin"), difference(changed, "/field_rules/quantity/read_roles").get("after"));
            var removed = difference(changed, "/field_rules/short_description/max_length");
            assertEquals("removed", removed.get("kind"));
            assertEquals(50, removed.get("before"));
            assertEquals(false, removed.get("after_present"));
            assertUnchanged(h, fixture.table, existing, 1);

            for (String role : List.of("agent", "reader", "requester")) {
                String name = "Diff" + role;
                h.account(name, role);
                assertEquals(403, h.json("POST", ConfigurationAcceptance.API, update, auth(name)).statusCode());
                assertEquals(403, h.get("/admin/configuration", auth(name)).statusCode());
            }
            assertEquals(401, h.json("POST", ConfigurationAcceptance.API, update, null).statusCode());
            h.login("admin");
            assertEquals(403, h.form("/admin/configuration", map("operation", "preview", "document", JSON.writeValueAsString(update)), null).statusCode());
            var page = h.get("/admin/configuration", null);
            var rendered = h.form("/admin/configuration", map("csrf", csrf(page), "operation", "preview", "document", JSON.writeValueAsString(update)), null);
            assertEquals(200, rendered.statusCode(), html(rendered));
            assertTrue(html(rendered).contains("Configuration changes"));
            assertTrue(html(rendered).contains("/field_rules/quantity/read_roles"));
            assertFalse(html(rendered).contains("Reviewed <script>"));
            assertUnchanged(h, fixture.table, existing, 1);

            var bad = ConfigurationAcceptance.definition();
            object(object(bad.get("field_rules")).get("quantity")).put("type", "reference");
            object(object(bad.get("field_rules")).get("quantity")).put("target", "missing_dependency");
            var mixed = map("format", Configuration.FORMAT, "tables", map(fixture.table, next, fixture.table + "bad", bad));
            assertEquals(400, fixture.install(mixed, true).statusCode());
            assertEquals(400, fixture.install(mixed, false).statusCode());
            assertUnchanged(h, fixture.table, existing, 1);
            assertUnchanged(h, fixture.table + "bad", null, 1);

            assertEquals(changed, first(fixture.result(fixture.install(update, false), 200)));
            assertEquals("Reviewed <script> & service", current(h, fixture.table).get("label"));
            assertEquals(409, fixture.install(update, true).statusCode());
            assertEquals(409, fixture.install(update, false).statusCode());
            assertUnchanged(h, fixture.table, current(h, fixture.table), 2);
        } finally {
            fixture.cleanup();
        }
    }

    static Map<String, Object> first(Map<String, Object> report) {
        return object(((List<?>) report.get("changes")).getFirst());
    }

    static Map<String, Object> difference(Map<String, Object> table, String path) {
        return ((List<?>) table.get("entries")).stream().map(Values::object)
                .filter(row -> path.equals(row.get("path"))).findFirst().orElseThrow();
    }

    static Map<String, Object> current(WorkflowSupport h, String table) throws Exception {
        try (var db = h.store.open()) {
            return object(db.one("SELECT definition FROM os_schema WHERE name=?", table).get("definition"));
        }
    }

    static void assertUnchanged(WorkflowSupport h, String table, Map<String, Object> expected, long events) throws Exception {
        try (var db = h.store.open()) {
            var row = db.one("SELECT definition FROM os_schema WHERE name=?", table);
            assertEquals(expected, row == null ? null : object(row.get("definition")));
            assertEquals(events, db.count("SELECT COUNT(*) FROM os_security_event WHERE action='configuration.installed'"));
        }
    }
}
