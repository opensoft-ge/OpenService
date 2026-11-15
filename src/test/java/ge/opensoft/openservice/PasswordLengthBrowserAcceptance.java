package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

final class PasswordLengthBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "UnicodeLength" + id().substring(0, 8);
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            h.login(page, "admin");
            page.navigate(h.base() + "/admin/accounts");
            var password = page.getByLabel("Password", new Page.GetByLabelOptions().setExact(true));
            assertThat(password).hasAttribute("minlength", "15");
            assertThat(password).hasAttribute("maxlength", "2048");
            page.getByLabel("Username", new Page.GetByLabelOptions().setExact(true)).fill(name);
            password.fill(
                    "🌟".repeat(14)); // Browser UTF-16 length differs from server code points.
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create account"))
                    .click();
            assertThat(page.getByRole(AriaRole.ALERT)).containsText("15–1024");
            assertEquals("", password.inputValue());
            ReleaseBrowserAcceptance.accessible(page);
            password.fill("🌟".repeat(1024));
            assertEquals(2048, password.inputValue().length());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create account"))
                    .click();
            assertThat(page.getByRole(AriaRole.ALERT)).hasCount(0);
            try (var db = h.store.open()) {
                var user = db.one("SELECT password FROM os_user WHERE name=?", name);
                assertNotNull(user);
                assertTrue(Passwords.matches(user.get("password").toString(), "🌟".repeat(1024)));
            }
            page.navigate(h.base() + "/account");
            assertThat(page.getByLabel("New password", new Page.GetByLabelOptions().setExact(true)))
                    .hasAttribute("minlength", "15");
            assertThat(page.getByLabel("New password", new Page.GetByLabelOptions().setExact(true)))
                    .hasAttribute("maxlength", "2048");
            ReleaseBrowserAcceptance.accessible(page);
            page.navigate(h.base() + "/admin/password-exclusions");
            assertThat(page.getByLabel("Value to prohibit")).hasAttribute("maxlength", "2048");
            try (var userContext = browser.newContext()) {
                var own = userContext.newPage();
                own.navigate(h.base() + "/login");
                own.getByLabel("Username", new Page.GetByLabelOptions().setExact(true)).fill(name);
                own.getByLabel("Password", new Page.GetByLabelOptions().setExact(true))
                        .fill("🌟".repeat(1024));
                own.getByRole(
                                AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("Enter your workspace"))
                        .click();
                own.getByRole(
                                AriaRole.HEADING,
                                new Page.GetByRoleOptions().setName("Make room for better work."))
                        .waitFor();
                own.navigate(h.base() + "/account/email");
                assertThat(own.locator("input[name=current_password]"))
                        .hasAttribute("maxlength", "2048");
                own.navigate(h.base() + "/account");
                own.getByLabel("Current password", new Page.GetByLabelOptions().setExact(true))
                        .fill("🌟".repeat(1024));
                assertEquals(
                        2048,
                        own.getByLabel(
                                        "Current password",
                                        new Page.GetByLabelOptions().setExact(true))
                                .inputValue()
                                .length());
                own.getByLabel("New password", new Page.GetByLabelOptions().setExact(true))
                        .fill("changed-unicode-password");
                own.getByRole(
                                AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("Change password"))
                        .click();
                assertThat(own).hasURL(h.base() + "/login");
            }
        }
    }
}
