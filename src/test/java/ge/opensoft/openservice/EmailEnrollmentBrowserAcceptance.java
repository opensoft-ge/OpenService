package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Path;

final class EmailEnrollmentBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "Enroll" + id().substring(0, 8),
                email = name.toLowerCase() + "@example.invalid";
        var properties = ((ConfigurableEnvironment) h.environment).getPropertySources();
        try (var db = h.store.open()) {
            db.update("DELETE FROM os_outbox");
            Service.createAccount(
                    db, map("name", name, "role", "reader", "password", WorkflowSupport.PASSWORD));
            db.connection.commit();
        }
        try (var sink = new MailSink();
                var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            properties.addFirst(
                    new MapPropertySource("enrollment-browser", ReleaseAcceptance.settings(sink)));
            var page = context.newPage();
            h.login(page, name);
            page.navigate(h.base() + "/account");
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Recovery email"))
                    .click();
            page.getByLabel("Recovery email address").fill(email);
            page.getByLabel("Current password").fill(WorkflowSupport.PASSWORD);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Send verification email"))
                    .click();
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("Verification message queued");
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Cancel email verification"))
                    .click();
            assertThat(
                            page.getByRole(
                                    AriaRole.BUTTON,
                                    new Page.GetByRoleOptions()
                                            .setName("Cancel email verification")))
                    .hasCount(0);
            try (var db = h.store.open()) {
                db.update(
                        "UPDATE os_email_enrollment SET created_at=0 WHERE user_id=?",
                        RecordAccess.owner(db, name));
                db.connection.commit();
            }
            page.getByLabel("Recovery email address").fill(email);
            page.getByLabel("Current password").fill(WorkflowSupport.PASSWORD);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Send verification email"))
                    .focus();
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Send verification email"))
                    .press("Enter");
            var worker = new DeliveryWorker(h.store, h.environment);
            assertTrue(worker.runOne(System.currentTimeMillis() + 100));
            assertTrue(worker.runOne(System.currentTimeMillis() + 100));
            String link = ReleaseAcceptance.link(sink.receive(), "verify-email");
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/enrollment-desktop.png"))
                            .setFullPage(true));
            page.navigate(h.base() + link);
            assertThat(page.getByText("Email: " + email)).isVisible();
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/enrollment-confirm-dark.png"))
                            .setFullPage(true));
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Verify email address"))
                    .click();
            assertThat(page).hasURL(h.base() + "/login");
            h.login(page, name);
            page.navigate(h.base() + "/account/email");
            assertThat(page.getByText("Registered address: " + email)).isVisible();
            ReleaseBrowserAcceptance.accessible(page);
        } finally {
            properties.remove("enrollment-browser");
            try (var db = h.store.open()) {
                db.update("DELETE FROM os_outbox");
                db.update(
                        "DELETE FROM os_email_enrollment WHERE user_id=?",
                        RecordAccess.owner(db, name));
                db.update("DELETE FROM os_user WHERE name=?", name);
                db.connection.commit();
            }
        }
    }
}
