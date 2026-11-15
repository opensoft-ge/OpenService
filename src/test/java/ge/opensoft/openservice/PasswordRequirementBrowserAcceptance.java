package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class PasswordRequirementBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "RequireBrowser" + id().substring(0, 8), target;
        try (var db = h.store.open()) {
            Service.createAccount(
                    db, map("name", name, "role", "reader", "password", WorkflowSupport.PASSWORD));
            target = RecordAccess.owner(db, name);
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var admin = browser.newContext();
                var member = browser.newContext()) {
            var operator = admin.newPage();
            var page = member.newPage();
            h.login(operator, "admin");
            h.login(page, name);
            operator.navigate(h.base() + "/admin/accounts/" + target);
            operator.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Require password change"))
                    .click();
            assertThat(
                            operator.getByText(
                                    "Password change required. Workspace and API access remain"
                                        + " blocked until the account holder replaces the"
                                        + " password."))
                    .isVisible();
            ReleaseBrowserAcceptance.accessible(operator);
            page.navigate(h.base() + "/account");
            assertThat(page).hasURL(h.base() + "/login");
            signIn(h, page, name, WorkflowSupport.PASSWORD);
            assertThat(page).hasURL(h.base() + "/password-required");
            assertThat(
                            page.getByRole(
                                    AriaRole.HEADING,
                                    new Page.GetByRoleOptions()
                                            .setName("Change your password to continue")))
                    .isVisible();
            assertThat(
                            page.getByRole(
                                    AriaRole.LINK,
                                    new Page.GetByRoleOptions().setName("Overview").setExact(true)))
                    .hasCount(0);
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/required-password-desktop.png"))
                            .setFullPage(true));
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/required-password-mobile-dark.png"))
                            .setFullPage(true));
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Sign out").setExact(true))
                    .click();
            assertThat(page).hasURL(h.base() + "/login");
            signIn(h, page, name, WorkflowSupport.PASSWORD);
            page.getByLabel("Current password").fill("wrong");
            page.getByLabel("New password").fill("replacement-browser-secret");
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Change password").setExact(true))
                    .click();
            assertThat(page.getByRole(AriaRole.ALERT))
                    .containsText("Current password is incorrect");
            assertEquals("", page.getByLabel("Current password").inputValue());
            assertEquals("", page.getByLabel("New password").inputValue());
            ReleaseBrowserAcceptance.accessible(page);
            page.getByLabel("Current password").fill(WorkflowSupport.PASSWORD);
            page.getByLabel("New password").fill("replacement-browser-secret");
            var change =
                    page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Change password").setExact(true));
            change.focus();
            change.press("Enter");
            assertThat(page).hasURL(h.base() + "/login");
            signIn(h, page, name, "replacement-browser-secret");
            assertThat(
                            page.getByRole(
                                    AriaRole.HEADING,
                                    new Page.GetByRoleOptions()
                                            .setName("Make room for better work.")))
                    .isVisible();
            operator.reload();
            assertThat(operator.getByText("No password change is currently required.")).isVisible();
        }
    }

    static void signIn(BrowserTest h, Page page, String name, String password) {
        page.navigate(h.base() + "/login");
        page.getByLabel("Username", new Page.GetByLabelOptions().setExact(true)).fill(name);
        page.getByLabel("Password", new Page.GetByLabelOptions().setExact(true)).fill(password);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Enter your workspace"))
                .click();
    }
}
