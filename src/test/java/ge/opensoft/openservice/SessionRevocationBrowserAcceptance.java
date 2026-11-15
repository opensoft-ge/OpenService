package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class SessionRevocationBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "Revoke" + id().substring(0, 8);
        try (var db = h.store.open()) {
            Service.createAccount(
                    db, map("name", name, "role", "reader", "password", WorkflowSupport.PASSWORD));
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var first = browser.newContext();
                var second = browser.newContext()) {
            var page = first.newPage();
            var other = second.newPage();
            h.login(page, name);
            h.login(other, name);
            page.navigate(h.base() + "/account/sessions");
            ReleaseBrowserAcceptance.accessible(page);
            var row =
                    page.locator("tbody tr")
                            .filter(new Locator.FilterOptions().setHasText("Other browser"));
            var button = row.getByRole(AriaRole.BUTTON);
            button.focus();
            button.press("Enter");
            assertThat(page.locator("tbody tr")).hasCount(1);
            other.navigate(h.base() + "/account");
            assertTrue(other.url().endsWith("/login"));
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/sessions-revoke-mobile-dark.png"))
                            .setFullPage(true));
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Sign out this browser"))
                    .click();
            assertTrue(page.url().endsWith("/login"));
            h.login(page, name);
            page.navigate(h.base() + "/account/sessions");
            assertThat(page.locator("tbody tr")).hasCount(1);
        }
    }
}
