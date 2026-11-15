package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class PasswordExclusionBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String suffix = id().substring(0, 8),
                label = "Organization example " + suffix,
                password = "browser-excluded-org-password";
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            h.login(page, "admin");
            page.navigate(h.base() + "/admin/accounts");
            page.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("Password exclusions"))
                    .click();
            ReleaseBrowserAcceptance.accessible(page);
            page.getByLabel("Administrative label").fill(label);
            page.getByLabel("Value to prohibit").fill(password);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add exclusion"))
                    .click();
            assertThat(
                            page.getByRole(
                                    AriaRole.CELL,
                                    new Page.GetByRoleOptions().setName(label).setExact(true)))
                    .isVisible();
            assertEquals("", page.getByLabel("Value to prohibit").inputValue());
            assertFalse(page.content().contains(password));
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/password-policy-desktop.png"))
                            .setFullPage(true));
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            assertEquals(
                    true,
                    page.locator(".password-exclusions .table-scroll")
                            .evaluate("el=>el.scrollWidth<=el.clientWidth"));
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/password-policy-mobile-dark.png"))
                            .setFullPage(true));
            page.navigate(h.base() + "/admin/accounts");
            page.getByLabel("Username", new Page.GetByLabelOptions().setExact(true))
                    .fill("Blocked" + suffix);
            page.getByLabel("Password", new Page.GetByLabelOptions().setExact(true)).fill(password);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create account"))
                    .click();
            assertThat(page.getByRole(AriaRole.ALERT)).containsText("password is excluded");
            assertEquals(
                    "",
                    page.getByLabel("Password", new Page.GetByLabelOptions().setExact(true))
                            .inputValue());
            page.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("Password exclusions"))
                    .click();
            var remove =
                    page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Remove " + label));
            remove.focus();
            remove.press("Enter");
            assertThat(page.getByText("No custom exclusions configured.")).isVisible();
            ReleaseBrowserAcceptance.accessible(page);
            try (var db = h.store.open()) {
                for (int i = 0; i < 101; i++)
                    db.update(
                            "INSERT INTO"
                                    + " os_password_exclusion(exclusion_id,label,digest,created_at)"
                                    + " VALUES(?,?,?,?)",
                            id(),
                            "BrowserPaging" + i,
                            Passwords.tokenHash("browser-paging-" + i),
                            now());
                db.connection.commit();
            }
            page.reload();
            assertThat(page.locator("tbody tr")).hasCount(100);
            page.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("Next").setExact(true))
                    .click();
            assertThat(page.locator("tbody tr")).hasCount(1);
            page.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("Previous").setExact(true))
                    .click();
            assertThat(page.locator("tbody tr")).hasCount(100);
        } finally {
            try (var db = h.store.open()) {
                db.update(
                        "DELETE FROM os_password_exclusion WHERE label=? OR label LIKE"
                                + " 'BrowserPaging%'",
                        label);
                db.connection.commit();
            }
        }
    }
}
