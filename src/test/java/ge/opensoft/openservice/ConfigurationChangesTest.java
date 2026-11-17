package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.ConfigurationChangesAcceptance.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ConfigurationChangesTest {
    @Test void reportsFieldAndStateOrderChangesEvenWhenValuesMatch() {
        var before = map("fields", map("short_description", "Summary", "state", "State"),
                "states", map("new", "New", "closed", "Closed"));
        var after = map("fields", map("state", "State", "short_description", "Summary"),
                "states", map("closed", "Closed", "new", "New"));
        var change = ConfigurationChanges.describe("u_ordered", before, after);
        assertEquals("updated", change.get("kind"), "Changing the initial state must be visible");
        assertEquals("{\"new\":\"New\",\"closed\":\"Closed\"}", difference(change, "/states").get("before_text"));
        assertEquals("{\"closed\":\"Closed\",\"new\":\"New\"}", difference(change, "/states").get("after_text"));
        assertEquals("changed", difference(change, "/fields").get("kind"));
        var removed = ConfigurationChanges.describe("u_ordered", before, map());
        assertEquals("removed", difference(removed, "/fields").get("kind"));
        var added = ConfigurationChanges.describe("u_ordered", null, after);
        assertEquals("added", difference(added, "/states").get("kind"));
    }

    @Test void comparesNestedValuesMissingNullsAndOrderedArraysWithoutMutatingInputs() {
        var before = map("version", 7, "unchanged", "same", "null", null,
                "rules", map("roles", List.of("admin", "agent"), "remove", true, "version", 1),
                "to_scalar", map("x", 1), "to_object", "value", "removed_null", null,
                "a/b~c", "old");
        var after = map("version", 8, "unchanged", "same", "null", null,
                "rules", map("roles", List.of("agent", "admin"), "add", false, "version", 2),
                "to_scalar", "value", "to_object", map("x", 1), "added_null", null,
                "a/b~c", "new");
        String beforeJson = JSON.writeValueAsString(before), afterJson = JSON.writeValueAsString(after);
        var change = ConfigurationChanges.describe("u_example", before, after);
        assertEquals("updated", change.get("kind"));
        var entries = ((List<?>) change.get("entries")).stream().map(Values::object).toList();
        assertEquals(List.of("/a~1b~0c", "/added_null", "/removed_null", "/rules/add", "/rules/remove", "/rules/roles", "/rules/version", "/to_object", "/to_scalar"),
                entries.stream().map(row -> row.get("path")).toList());
        assertEquals("old", difference(change, "/a~1b~0c").get("before"));
        var added = difference(change, "/added_null");
        assertEquals(false, added.get("before_present"));
        assertEquals(true, added.get("after_present"));
        assertNull(added.get("after"));
        assertEquals("null", added.get("after_text"));
        assertEquals("Not present", added.get("before_text"));
        var removed = difference(change, "/removed_null");
        assertEquals(true, removed.get("before_present"));
        assertEquals(false, removed.get("after_present"));
        assertEquals("null", removed.get("before_text"));
        assertEquals("Not present", removed.get("after_text"));
        assertEquals(List.of("admin", "agent"), difference(change, "/rules/roles").get("before"));
        assertEquals(List.of("agent", "admin"), difference(change, "/rules/roles").get("after"));
        assertEquals(map("x", 1), difference(change, "/to_object").get("after"));
        assertEquals(map("x", 1), difference(change, "/to_scalar").get("before"));
        assertEquals(beforeJson, JSON.writeValueAsString(before));
        assertEquals(afterJson, JSON.writeValueAsString(after));
    }

    @Test void distinguishesNewAndUnchangedTablesAndIgnoresOnlyRootVersion() {
        assertEquals("created", ConfigurationChanges.describe("u_new", null, map("label", "New")).get("kind"));
        var unchanged = ConfigurationChanges.describe("u_old", map("version", 0, "fields", map()), map("version", 1, "fields", map()));
        assertEquals("unchanged", unchanged.get("kind"));
        assertEquals(List.of(), unchanged.get("entries"));
    }
}
