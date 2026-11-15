package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class AccountSessionLimitBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "AccountCap" + id().substring(0, 8), target;
        try (var db = h.store.open()) {
            Service.createAccount(
                    db, map("name", name, "password", WorkflowSupport.PASSWORD, "role", "reader"));
            target = RecordAccess.owner(db, name);
            db.connection.commit();
        }
        try (var p = Playwright.create();
                var browser = p.chromium().launch();
                var admin = browser.newContext();
                var member = browser.newContext();
                var second = browser.newContext()) {
            var page = admin.newPage();
            var person = member.newPage();
            var another = second.newPage();
            h.login(page, "admin");
            h.login(person, name);
            page.navigate(h.base() + "/admin/accounts/" + target + "/sessions");
            page.getByLabel("Maximum browser sessions").fill("1");
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/session-policy-desktop.png"))
                            .setFullPage(true));
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/session-policy-mobile-dark.png"))
                            .setFullPage(true));
            var button =
                    page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Save session limit"));
            button.focus();
            button.press("Enter");
            assertThat(
                            page.getByText(
                                    "Effective limit: 1.",
                                    new Page.GetByTextOptions().setExact(false)))
                    .isVisible();
            person.navigate(h.base() + "/account/sessions");
            assertThat(
                            person.getByText(
                                    "Browser session limit: 1.",
                                    new Page.GetByTextOptions().setExact(false)))
                    .isVisible();
            h.login(another, name);
            person.navigate(h.base() + "/account");
            assertThat(person).hasURL(h.base() + "/login");
            page.getByLabel("Maximum browser sessions").fill("0");
            button.click();
            assertThat(
                            page.getByText(
                                    "Effective limit: 10.",
                                    new Page.GetByTextOptions().setExact(false)))
                    .isVisible();
        }
    }
}
