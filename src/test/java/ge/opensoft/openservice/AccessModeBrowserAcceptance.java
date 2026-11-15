package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class AccessModeBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "ApiBrowser" + id().substring(0, 8), target;
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
            assertEquals("false", operator.getByLabel("Allowed access").inputValue());
            operator.getByLabel("Allowed access").selectOption("true");
            operator.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Save access mode"))
                    .click();
            assertEquals("true", operator.getByLabel("Allowed access").inputValue());
            ReleaseBrowserAcceptance.accessible(operator);
            operator.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/access-mode-desktop.png"))
                            .setFullPage(true));
            operator.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(operator);
            operator.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            operator.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/access-mode-mobile-dark.png"))
                            .setFullPage(true));
            page.navigate(h.base() + "/account");
            assertThat(page).hasURL(h.base() + "/login");
            PasswordRequirementBrowserAcceptance.signIn(h, page, name, WorkflowSupport.PASSWORD);
            assertThat(page.getByText("Incorrect username or password")).isVisible();
            operator.getByLabel("Allowed access").selectOption("false");
            var save =
                    operator.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Save access mode"));
            save.focus();
            save.press("Enter");
            assertEquals("false", operator.getByLabel("Allowed access").inputValue());
            h.login(page, name);
        }
    }
}
