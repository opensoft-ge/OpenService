package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class AccountPolicyDirectoryBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String prefix = "Channel" + id().substring(0, 6);
        try (var db = h.store.open()) {
            String password =
                    db.one("SELECT password FROM os_user WHERE name='admin'")
                            .get("password")
                            .toString();
            for (int i = 0; i < 27; i++)
                db.update(
                        "INSERT INTO os_user(name,password,role,user_id,api_only,allow_basic)"
                            + " VALUES(?,?,?,?,1,0)",
                        prefix + i,
                        password,
                        "reader",
                        id());
            db.connection.commit();
        }
        try (var p = Playwright.create();
                var browser = p.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            h.login(page, "admin");
            page.navigate(h.base() + "/admin/accounts");
            page.getByLabel("Find username").fill(prefix);
            page.getByLabel("Filter sign-in channel").selectOption("api");
            page.getByLabel("Filter Basic authentication").selectOption("blocked");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Find accounts"))
                    .press("Enter");
            assertThat(page.locator("tbody tr")).hasCount(25);
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Next →"))
                    .press("Enter");
            assertThat(page.locator("tbody tr")).hasCount(2);
            assertThat(page.getByLabel("Filter sign-in channel")).hasValue("api");
            assertThat(page.getByLabel("Filter Basic authentication")).hasValue("blocked");
            assertThat(page.locator("tbody tr").first()).containsText("API only");
            assertThat(page.locator("tbody tr").first()).containsText("Basic blocked");
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/account-policy-directory-desktop.png"))
                            .setFullPage(true));
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Dark mode").setExact(true))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/account-policy-directory-mobile-dark.png"))
                            .setFullPage(true));
            page.getByLabel("Filter Basic authentication").selectOption("allowed");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Find accounts"))
                    .press("Enter");
            assertThat(page.getByText("No accounts match these filters.")).isVisible();
        }
    }
}
