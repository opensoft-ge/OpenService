package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class BrowserSessionsBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "Sessions" + id().substring(0, 8);
        try (var db = h.store.open()) {
            Service.createAccount(
                    db, map("name", name, "role", "reader", "password", WorkflowSupport.PASSWORD));
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext();
                var other = browser.newContext()) {
            var page = context.newPage();
            h.login(page, name);
            h.login(other.newPage(), name);
            page.navigate(h.base() + "/account");
            var link =
                    page.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("My browser sessions"));
            link.focus();
            link.press("Enter");
            assertThat(page.locator("tbody tr")).hasCount(2);
            assertThat(page.getByText("This deployment allows up to 10 browser sessions per account.", new Page.GetByTextOptions().setExact(false))).isVisible();
            assertThat(page.getByText("This browser", new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            assertThat(page.getByText("Other browser", new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/sessions-desktop.png"))
                            .setFullPage(true));
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/sessions-mobile-dark.png"))
                            .setFullPage(true));
        }
    }
}
