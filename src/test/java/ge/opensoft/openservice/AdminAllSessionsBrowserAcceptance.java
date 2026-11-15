package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class AdminAllSessionsBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "EndAll" + id().substring(0, 8), target, adminId;
        try (var db = h.store.open()) {
            Service.createAccount(
                    db, map("name", name, "password", WorkflowSupport.PASSWORD, "role", "reader"));
            target = RecordAccess.owner(db, name);
            adminId = RecordAccess.owner(db, "admin");
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var admin = browser.newContext();
                var member = browser.newContext();
                var second = browser.newContext()) {
            var page = admin.newPage();
            var person = member.newPage();
            var other = second.newPage();
            h.login(page, "admin");
            h.login(person, name);
            h.login(other, name);
            page.navigate(h.base() + "/admin/accounts/" + target + "/sessions");
            page.getByLabel("Reason for ending all sessions")
                    .fill("Requested by the account holder");
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/admin-all-sessions-desktop.png"))
                            .setFullPage(true));
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/admin-all-sessions-mobile-dark.png"))
                            .setFullPage(true));
            var button =
                    page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("End all browser sessions"));
            button.focus();
            button.press("Enter");
            assertThat(page.getByText("No recent valid browser sessions.")).isVisible();
            for (var client : new Page[] {person, other}) {
                client.navigate(h.base() + "/account");
                assertThat(client).hasURL(h.base() + "/login");
            }
            h.login(person, name);
            page.navigate(h.base() + "/admin/accounts/" + adminId + "/sessions");
            page.getByLabel("Reason for ending all sessions").fill("End my current browser");
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("End all browser sessions"))
                    .click();
            assertThat(page).hasURL(h.base() + "/login");
        }
    }
}
