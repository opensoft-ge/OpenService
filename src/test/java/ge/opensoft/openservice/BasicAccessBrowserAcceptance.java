package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class BasicAccessBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "BasicPolicy" + id().substring(0, 8), target;
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
            var page = admin.newPage();
            var person = member.newPage();
            h.login(page, "admin");
            h.login(person, name);
            page.navigate(h.base() + "/admin/accounts/" + target);
            page.getByLabel("Basic authentication access").selectOption("false");
            var save =
                    page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Save Basic authentication"));
            save.focus();
            save.press("Enter");
            assertEquals("false", page.getByLabel("Basic authentication access").inputValue());
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/basic-policy-desktop.png"))
                            .setFullPage(true));
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/basic-policy-mobile-dark.png"))
                            .setFullPage(true));
            person.navigate(h.base() + "/account");
            assertThat(person).hasURL(h.base() + "/login");
            h.login(person, name);
            person.navigate(h.base() + "/account");
            assertThat(
                            person.getByRole(
                                    AriaRole.HEADING,
                                    new Page.GetByRoleOptions().setName("Account security")))
                    .isVisible();
            page.getByLabel("Basic authentication access").selectOption("true");
            save.click();
            assertEquals("true", page.getByLabel("Basic authentication access").inputValue());
        }
    }
}
