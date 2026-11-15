package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class ProvisioningPasswordBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "FirstLogin" + id().substring(0, 8);
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext();
                var member = browser.newContext()) {
            var admin = context.newPage();
            h.login(admin, "admin");
            admin.navigate(h.base() + "/admin/accounts");
            var required = admin.getByLabel("Password change at first sign-in");
            assertEquals("true", required.inputValue());
            admin.getByLabel("Username", new Page.GetByLabelOptions().setExact(true)).fill(name);
            admin.getByLabel("Password", new Page.GetByLabelOptions().setExact(true))
                    .fill("🌟".repeat(14));
            admin.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create account"))
                    .click();
            assertThat(admin.getByRole(AriaRole.ALERT)).containsText("15–1024");
            assertEquals("true", required.inputValue());
            required.selectOption("false");
            admin.getByLabel("Password", new Page.GetByLabelOptions().setExact(true))
                    .fill("🌟".repeat(14));
            admin.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create account"))
                    .click();
            assertEquals("false", required.inputValue());
            required.selectOption("true");
            ReleaseBrowserAcceptance.accessible(admin);
            admin.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/provisioning-desktop.png"))
                            .setFullPage(true));
            admin.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(admin);
            admin.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            admin.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/provisioning-mobile-dark.png"))
                            .setFullPage(true));
            admin.getByLabel("Password", new Page.GetByLabelOptions().setExact(true))
                    .fill(WorkflowSupport.PASSWORD);
            var create =
                    admin.getByRole(
                            AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create account"));
            create.focus();
            create.press("Enter");
            assertThat(admin.getByRole(AriaRole.ALERT)).hasCount(0);
            var page = member.newPage();
            PasswordRequirementBrowserAcceptance.signIn(h, page, name, WorkflowSupport.PASSWORD);
            assertThat(page).hasURL(h.base() + "/password-required");
            page.navigate(h.base() + "/account");
            assertThat(page).hasURL(h.base() + "/password-required");
            page.getByLabel("Current password").fill(WorkflowSupport.PASSWORD);
            page.getByLabel("New password").fill("first-login-new-password");
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Change password").setExact(true))
                    .click();
            assertThat(page).hasURL(h.base() + "/login");
            PasswordRequirementBrowserAcceptance.signIn(h, page, name, "first-login-new-password");
            assertThat(
                            page.getByRole(
                                    AriaRole.HEADING,
                                    new Page.GetByRoleOptions()
                                            .setName("Make room for better work.")))
                    .isVisible();
        }
    }
}
