package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class AdminSessionRevocationBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "EndSession" + id().substring(0, 8), target;
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
            page.navigate(h.base() + "/admin/accounts/" + target + "/sessions");
            page.getByLabel("Reason for ending this session")
                    .fill("Requested by the account holder");
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/admin-revoke-desktop.png"))
                            .setFullPage(true));
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/admin-revoke-mobile-dark.png"))
                            .setFullPage(true));
            var button =
                    page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("End selected session"));
            button.focus();
            button.press("Enter");
            assertThat(page.getByText("No recent valid browser sessions.")).isVisible();
            person.navigate(h.base() + "/account");
            assertThat(person).hasURL(h.base() + "/login");
            h.login(person, name);
        }
    }
}
