package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class AdminSessionsBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "Inspect" + id().substring(0, 8), target;
        try (var db = h.store.open()) {
            Service.createAccount(
                    db, map("name", name, "password", WorkflowSupport.PASSWORD, "role", "reader"));
            target = RecordAccess.owner(db, name);
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var admin = browser.newContext();
                var member = browser.newContext()) {
            var page = admin.newPage();
            var person = member.newPage();
            h.login(page, "admin");
            h.login(person, name);
            page.navigate(h.base() + "/admin/accounts/" + target);
            var link =
                    page.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("View browser sessions"));
            link.focus();
            link.press("Enter");
            assertThat(page.locator("tbody tr")).hasCount(1);
            assertThat(page.getByText(name, new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/admin-sessions-desktop.png"))
                            .setFullPage(true));
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/admin-sessions-mobile-dark.png"))
                            .setFullPage(true));
            person.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Account security"))
                    .first()
                    .click();
            person.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Sign out all browsers"))
                    .click();
            page.reload();
            assertThat(page.getByText("No recent valid browser sessions.")).isVisible();
        }
    }
}
