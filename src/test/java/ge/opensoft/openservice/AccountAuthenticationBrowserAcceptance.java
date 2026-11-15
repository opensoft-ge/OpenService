package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.*;

final class AccountAuthenticationBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "History" + id().substring(0, 8), target;
        try (var db = h.store.open()) {
            Service.createAccount(
                    db, map("name", name, "password", WorkflowSupport.PASSWORD, "role", "reader"));
            target = RecordAccess.owner(db, name);
            for (int i = 0; i < 102; i++)
                AuthenticationEvents.record(
                        db,
                        name,
                        Passwords.tokenHash(name),
                        i == 0 ? "custom" : "browser",
                        "succeeded");
            AuthenticationEvents.record(
                    db, "admin", Passwords.tokenHash("admin"), "basic", "limited");
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
                            new Page.GetByRoleOptions().setName("View sign-in activity"))
                    .click();
            assertThat(
                            page.getByRole(
                                    AriaRole.HEADING,
                                    new Page.GetByRoleOptions()
                                            .setName("Account sign-in activity")))
                    .isVisible();
            assertThat(page.locator("tbody tr")).hasCount(100);
            assertFalse(page.locator("tbody").innerText().contains("limited"));
            ReleaseBrowserAcceptance.accessible(page);
            var older =
                    page.getByRole(
                            AriaRole.LINK, new Page.GetByRoleOptions().setName("Older events"));
            older.focus();
            older.press("Enter");
            assertThat(page.locator("tbody tr")).hasCount(2);
            assertThat(older).hasCount(0);
            assertThat(page.getByText("Other verification")).isVisible();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/account-history-desktop.png"))
                            .setFullPage(true));
            var download =
                    page.waitForDownload(
                            () ->
                                    page.getByRole(
                                                    AriaRole.LINK,
                                                    new Page.GetByRoleOptions()
                                                            .setName("Download this page"))
                                            .click());
            var data = object(JSON.readValue(Files.readString(download.path()), Object.class));
            assertEquals(target, object(data.get("account")).get("user_id"));
            assertEquals(2, objects(data.get("result")).size());
            assertFalse(data.toString().contains("identity_hash"));
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/account-history-mobile-dark.png"))
                            .setFullPage(true));
            try (var db = h.store.open()) {
                db.update("DELETE FROM os_auth_event WHERE user_id=?", target);
                db.connection.commit();
            }
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Latest events"))
                    .click();
            assertThat(page.getByText("No retained verification events for this account."))
                    .isVisible();
            ReleaseBrowserAcceptance.accessible(page);
        }
    }
}
