package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class AccountSecurityEventsBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String target;
        try (var db = h.store.open()) {
            String name = "Trail" + id().substring(0, 6);
            Service.createAccount(
                    db, map("name", name, "password", WorkflowSupport.PASSWORD, "role", "reader"));
            target = RecordAccess.owner(db, name);
            String admin = RecordAccess.owner(db, "admin");
            for (int i = 0; i < 101; i++)
                SecurityEvents.record(db, admin, target, "account.test_change", map("ordinal", i));
            Service.changeRole(db, admin, target, map("role", "agent"));
            db.connection.commit();
        }
        try (var p = Playwright.create();
                var browser = p.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            h.login(page, "admin");
            page.navigate(h.base() + "/admin/accounts/" + target);
            page.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("View account security changes"))
                    .press("Enter");
            assertThat(page.locator("tbody tr").first()).containsText("account.role_changed");
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Older changes"))
                    .press("Enter");
            assertThat(page.locator("tbody tr")).hasCount(2);
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/account-security-desktop.png"))
                            .setFullPage(true));
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Dark mode").setExact(true))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/account-security-mobile-dark.png"))
                            .setFullPage(true));
            var download =
                    page.waitForDownload(
                            () ->
                                    page.getByRole(
                                                    AriaRole.LINK,
                                                    new Page.GetByRoleOptions()
                                                            .setName("Download this page"))
                                            .press("Enter"));
            assertEquals("account-security-events.json", download.suggestedFilename());
            assertTrue(java.nio.file.Files.readString(download.path()).contains(target));
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Latest changes"))
                    .press("Enter");
            assertThat(page.locator("tbody tr")).hasCount(100);
        }
    }
}
