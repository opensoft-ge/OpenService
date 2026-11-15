package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class ApiKeyBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String target;
        try (var db = h.store.open()) {
            String name = "Machine" + id().substring(0, 8);
            Service.createAccount(
                    db, map("name", name, "password", WorkflowSupport.PASSWORD, "role", "agent"));
            target = RecordAccess.owner(db, name);
            db.update("UPDATE os_user SET api_only=1 WHERE user_id=?", target);
            db.connection.commit();
        }
        try (var p = Playwright.create();
                var browser = p.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            h.login(page, "admin");
            page.navigate(h.base() + "/admin/accounts/" + target);
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Manage API keys"))
                    .click();
            assertThat(page.getByText("No API keys.")).isVisible();
            ReleaseBrowserAcceptance.accessible(page);
            page.getByLabel("Key label").fill("Nightly report");
            page.getByLabel("Table name").fill("incident");
            var issue =
                    page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Issue read-only key"));
            issue.focus();
            issue.press("Enter");
            assertTrue(page.getByLabel("New API key").inputValue().startsWith("osk_"));
            assertThat(page.locator("tbody tr")).hasCount(1);
            ReleaseBrowserAcceptance.accessible(page);
            String original = page.getByLabel("New API key").inputValue();
            assertThat(page.getByText("Never", new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            var call =
                    java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create(h.base() + "/api/now/table/incident"))
                            .header("Authorization", "Bearer " + original)
                            .GET()
                            .build();
            try (var client = java.net.http.HttpClient.newHttpClient()) {
                assertEquals(
                        200,
                        client.send(call, java.net.http.HttpResponse.BodyHandlers.ofString())
                                .statusCode());
            }
            page.navigate(h.base() + "/admin/accounts/" + target + "/api-keys");
            assertThat(page.getByText("Never", new Page.GetByTextOptions().setExact(true)))
                    .hasCount(0);
            assertThat(page.locator("tbody tr")).containsText("1");
            var rotate =
                    page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Rotate key Nightly report"));
            rotate.focus();
            rotate.press("Enter");
            assertNotEquals(original, page.getByLabel("New API key").inputValue());
            assertThat(page.locator("tbody tr")).hasCount(1);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Suspend key Nightly report"))
                    .press("Enter");
            assertThat(page.getByText("Suspended", new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            assertThat(page.getByText("Effective: Suspended", new Page.GetByTextOptions().setExact(true))).isVisible();
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Resume key Nightly report"))
                    .press("Enter");
            assertThat(page.getByText("Enabled", new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            ReleaseBrowserAcceptance.accessible(page);
            try (var db = h.store.open()) {
                String admin = RecordAccess.owner(db, "admin");
                var expired = ApiKeys.issue(db, admin, target, map("label", "Expired demo", "table", "incident", "days", 1));
                db.update("UPDATE os_api_key SET expires_at=0 WHERE key_id=?", expired.get("key_id"));
                db.connection.commit();
            }
            page.navigate(h.base() + "/admin/accounts/" + target + "/api-keys");
            assertThat(page.locator("tbody tr")).hasCount(2);
            assertThat(page.getByText("Effective: Expired", new Page.GetByTextOptions().setExact(true))).isVisible();
            page.getByLabel("Reason for removing expired keys").fill("Remove obsolete credential");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Remove expired API keys")).press("Enter");
            assertThat(page.locator("tbody tr")).hasCount(1);
            assertThat(page.getByText("Expired demo", new Page.GetByTextOptions().setExact(true))).hasCount(0);
            assertThat(page.getByRole(AriaRole.CELL, new Page.GetByRoleOptions().setName("Nightly report").setExact(true))).isVisible();
            ReleaseBrowserAcceptance.accessible(page);
            String selectedKey = page.getByLabel("Key to shorten").locator("option").first().getAttribute("value");
            assertThat(page.getByLabel("Key to shorten").locator("option").first()).hasText("Nightly report — " + selectedKey);
            page.getByLabel("Key to shorten").selectOption(selectedKey);
            page.getByLabel("Remaining hours").fill("1");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Shorten key lifetime").setExact(true)).press("Enter");
            assertThat(page.locator("tbody tr")).hasCount(1);
            try (var db = h.store.open()) {
                long expiry = ((Number)db.one("SELECT expires_at FROM os_api_key WHERE user_id=?", target).get("expires_at")).longValue();
                assertTrue(expiry > System.currentTimeMillis());
                assertTrue(expiry <= System.currentTimeMillis() + 3600000L);
            }
            ReleaseBrowserAcceptance.accessible(page);
            assertThat(page.getByText("Effective: Eligible", new Page.GetByTextOptions().setExact(true))).isVisible();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/api-keys-desktop.png"))
                            .setFullPage(true));
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Dark mode").setExact(true))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/api-keys-mobile-dark.png"))
                            .setFullPage(true));
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Revoke key Nightly report"))
                    .click();
            assertThat(page.getByText("No API keys.")).isVisible();
            assertThat(page.getByLabel("New API key")).hasCount(0);
            page.getByLabel("Key label").fill("Replacement");
            page.getByLabel("Table name").fill("incident");
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Issue read-only key"))
                    .click();
            page.getByLabel("Reason for revoking all keys").fill("Integration retired");
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Revoke all API keys"))
                    .press("Enter");
            assertThat(page.getByText("No API keys.")).isVisible();
            assertThat(page.getByLabel("New API key")).hasCount(0);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByLabel("Key label").fill("Writer");
            page.getByLabel("Table name").fill("incident");
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Issue create/update key"))
                    .press("Enter");
            assertThat(
                            page.getByText(
                                    "Read, create and update",
                                    new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            ReleaseBrowserAcceptance.accessible(page);
            String writer = page.getByLabel("Key to make read-only").locator("option").first().getAttribute("value");
            page.getByLabel("Key to make read-only").selectOption(writer);
            page.getByLabel("Reason for reducing access").fill("Writer only needs to read now");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Make key read-only")).press("Enter");
            assertThat(page.getByRole(AriaRole.CELL, new Page.GetByRoleOptions().setName("Read only").setExact(true))).isVisible();
            assertThat(page.getByLabel("New API key")).hasCount(0);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByLabel("Key to rename").selectOption(writer);
            page.getByLabel("Updated label").fill("Reviewed integration");
            page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Rename key").setExact(true)).press("Enter");
            assertThat(page.getByRole(AriaRole.CELL,new Page.GetByRoleOptions().setName("Reviewed integration").setExact(true))).isVisible();
            assertThat(page.getByLabel("New API key")).hasCount(0);ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/api-keys-renamed-mobile-dark.png")).setFullPage(true));
            page.setViewportSize(1280,900);page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/api-keys-renamed-desktop.png")).setFullPage(true));
            page.getByLabel("Key label").fill("Key only");
            page.getByLabel("Table name").fill("incident");
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions()
                                    .setName("Replace keys and block password access"))
                    .press("Enter");
            assertTrue(page.getByLabel("New API key").inputValue().startsWith("osk_"));
            assertThat(page.locator("tbody tr")).hasCount(1);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Account details"))
                    .click();
            assertThat(page.getByLabel("Basic authentication access")).hasValue("false");
            ReleaseBrowserAcceptance.accessible(page);
        }
    }
}
