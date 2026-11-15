package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.*;

final class PersonalActivityBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "Activity" + id().substring(0, 8);
        try (var db = h.store.open()) {
            Service.createAccount(
                    db, map("name", name, "role", "reader", "password", WorkflowSupport.PASSWORD));
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            h.login(page, name);
            try (var db = h.store.open()) {
                db.update("DELETE FROM os_auth_event WHERE user_name=?", name);
                for (int i = 0; i < 101; i++)
                    AuthenticationEvents.record(
                            db, name, Passwords.tokenHash(name), "browser", "succeeded");
                AuthenticationEvents.record(
                        db, "admin", Passwords.tokenHash("admin"), "basic", "limited");
                db.connection.commit();
            }
            page.navigate(h.base() + "/account");
            page.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("My sign-in activity"))
                    .click();
            assertThat(page.locator("tbody tr")).hasCount(100);
            assertFalse(page.locator("tbody").innerText().contains("limited"));
            ReleaseBrowserAcceptance.accessible(page);
            var older =
                    page.getByRole(
                            AriaRole.LINK, new Page.GetByRoleOptions().setName("Older events"));
            older.focus();
            older.press("Enter");
            assertThat(page.locator("tbody tr")).hasCount(1);
            assertThat(older).hasCount(0);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/personal-activity-desktop.png"))
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
            assertEquals(1, objects(data.get("result")).size());
            assertFalse(data.toString().contains("identity_hash"));
            page.setViewportSize(390, 844);
            assertEquals(
                    true,
                    page.locator(".personal-activity .table-scroll")
                            .evaluate("el => el.scrollWidth <= el.clientWidth"));
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/personal-activity-mobile-dark.png"))
                            .setFullPage(true));
            try (var db = h.store.open()) {
                db.update("DELETE FROM os_auth_event WHERE user_name=?", name);
                db.connection.commit();
            }
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Latest events"))
                    .click();
            assertThat(page.getByText("No retained verification events.")).isVisible();
            ReleaseBrowserAcceptance.accessible(page);
        }
    }
}
