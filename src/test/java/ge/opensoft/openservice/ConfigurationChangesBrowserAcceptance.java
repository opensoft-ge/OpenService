package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
import java.util.*;

final class ConfigurationChangesBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String table = "u_review_" + id();
        var definition = ConfigurationAcceptance.definition();
        object(definition.get("fields")).put("state", "State");
        definition.put("states", map("new", "New", "closed", "Closed"));
        definition.put("flow", map("new", List.of("closed"), "closed", List.of("new")));
        var document = ConfigurationAcceptance.bundle(table, definition);
        try (var p = Playwright.create(); var browser = p.chromium().launch(); var context = browser.newContext()) {
            var page = context.newPage();
            h.login(page, "admin");
            page.navigate(h.base() + "/admin/configuration");
            page.getByLabel("Configuration package JSON").fill(JSON.writeValueAsString(document));
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Preview package")).press("Enter");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Configuration changes").setExact(true))).isVisible();
            assertThat(page.locator("#configuration-changes")).containsText("created");
            try (var db = h.store.open()) {
                assertNull(db.one("SELECT name FROM os_schema WHERE name=?", table));
            }
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Install package")).press("Enter");
            assertThat(page.locator("main")).containsText("Configuration installed.");

            definition.put("version", 1);
            definition.put("label", "Reviewed <img src=x onerror=alert(1)> & workflow");
            object(object(definition.get("field_rules")).get("quantity")).put("read_roles", List.of("admin"));
            definition.put("states", map("closed", "Closed", "new", "New"));
            page.getByLabel("Configuration package JSON").fill(JSON.writeValueAsString(document));
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Preview package")).press("Enter");
            for (String theme : List.of("light", "dark")) {
                if (theme.equals("dark")) {
                    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();
                    page.setViewportSize(390, 844);
                }
                assertThat(page.locator("#configuration-changes")).containsText("/field_rules/quantity/read_roles");
                assertThat(page.locator("#configuration-changes")).containsText("Reusable service cases");
                assertThat(page.locator("#configuration-changes")).containsText("Reviewed <img src=x onerror=alert(1)> & workflow");
                var states = page.locator("#configuration-changes tbody tr").filter(
                        new Locator.FilterOptions().setHas(page.getByRole(AriaRole.ROWHEADER,
                                new Page.GetByRoleOptions().setName("/states").setExact(true))));
                assertThat(states).containsText("{\"new\":\"New\",\"closed\":\"Closed\"}");
                assertThat(states).containsText("{\"closed\":\"Closed\",\"new\":\"New\"}");
                assertEquals(0, page.locator("#configuration-changes img").count());
                page.getByRole(AriaRole.REGION, new Page.GetByRoleOptions().setName(table + " configuration changes")).focus();
                assertThat(page.getByRole(AriaRole.REGION, new Page.GetByRoleOptions().setName(table + " configuration changes"))).isFocused();
                ReleaseBrowserAcceptance.checkAccessible(page);
                page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/configuration-review-" + theme + ".png")).setFullPage(true));
            }
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Install package")).press("Enter");
            assertThat(page.locator("main")).containsText("Configuration installed.");
            assertThat(page.locator("#configuration-changes")).containsText("updated");
            try (var db = h.store.open()) {
                assertEquals(2, Service.schema(db, table).get("version"));
                assertEquals(definition.get("label"), Service.schema(db, table).get("label"));
            }
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Install package")).press("Enter");
            assertThat(page.locator("main")).containsText("Configuration version changed");
            page.navigate(h.base() + "/admin/configuration");
            definition.put("version", 2);
            page.getByLabel("Configuration package JSON").fill(JSON.writeValueAsString(document));
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Preview package")).press("Enter");
            assertThat(page.locator("#configuration-changes")).containsText("No definition changes. Installation still advances the version.");
            ReleaseBrowserAcceptance.checkAccessible(page);
        } finally {
            try (var db = h.store.open()) {
                db.update("DELETE FROM os_schema WHERE name=?", table);
                db.connection.commit();
            }
        }
    }
}
