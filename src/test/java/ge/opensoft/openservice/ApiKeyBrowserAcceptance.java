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
                    db, map("name", name, "password", WorkflowSupport.PASSWORD, "role", "reader"));
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
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Revoke key Nightly report"))
                    .click();
            assertThat(page.getByText("No API keys.")).isVisible();
            assertThat(page.getByLabel("New API key")).hasCount(0);
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
        }
    }
}
