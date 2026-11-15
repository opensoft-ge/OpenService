package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.*;
import java.util.*;

final class ReleaseBrowserAcceptance {
    static void accessible(Page page) throws Exception {
        var toggle =
                page.getByRole(
                        AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Dark mode").setExact(true));
        checkAccessible(page);
        toggle.click();
        checkAccessible(page);
        toggle.click();
    }

    static void checkAccessible(Page page) throws Exception {
        page.evaluate(
                Files.readString(
                        Path.of("tools/browser-coverage/node_modules/axe-core/axe.min.js")));
        Object violations =
                page.evaluate(
                        "async () => (await axe.run(document, {runOnly: {type: 'tag', values:"
                            + " ['wcag2a','wcag2aa','wcag21a','wcag21aa']}})).violations.map(v =>"
                            + " ({id:v.id, impact:v.impact,"
                            + " nodes:v.nodes.map(n=>({target:n.target,detail:n.failureSummary}))}))");
        assertEquals(List.of(), violations, page.url());
        assertEquals(
                true,
                page.evaluate("document.documentElement.scrollWidth <= innerWidth"),
                page.url());
    }

    static void run(BrowserTest h) throws Exception {
        var properties = ((ConfigurableEnvironment) h.environment).getPropertySources();
        String name = "Email" + id().substring(0, 8),
                email = name.toLowerCase(Locale.ROOT) + "@example.invalid";
        try (var sink = new MailSink();
                var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var adminContext = browser.newContext();
                var recipientContext =
                        browser.newContext(
                                new Browser.NewContextOptions().setViewportSize(390, 844))) {
            properties.addFirst(
                    new MapPropertySource(
                            "release-browser-mail", ReleaseAcceptance.settings(sink)));
            // Earlier browser cases may leave pending manual invitations, but never task-owned
            // mail.
            try (var db = h.store.open()) {
                db.update("DELETE FROM os_outbox");
                db.update("DELETE FROM os_recovery_budget");
                db.connection.commit();
            }
            Page admin = adminContext.newPage(), recipient = recipientContext.newPage();
            recipient.navigate(h.base() + "/login");
            accessible(recipient);
            h.login(admin, "admin");
            admin.navigate(h.base() + "/admin/invitations");
            admin.getByLabel("Invited username").fill(name);
            admin.getByLabel("Recipient email").fill(email);
            admin.getByLabel("Invited role").selectOption("requester");
            admin.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Create invitation"))
                    .click();
            assertThat(admin.getByRole(AriaRole.STATUS)).containsText("queued for email");
            accessible(admin);
            var worker = new DeliveryWorker(h.store, h.environment);
            assertTrue(worker.runOne(System.currentTimeMillis() + 100));
            recipient.navigate(h.base() + ReleaseAcceptance.link(sink.receive(), "join"));
            accessible(recipient);
            recipient.getByLabel("Choose your password").fill(WorkflowSupport.PASSWORD);
            recipient
                    .getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Accept invitation"))
                    .press("Enter");
            recipient
                    .getByLabel("Username", new Page.GetByLabelOptions().setExact(true))
                    .fill(name);
            recipient
                    .getByLabel("Password", new Page.GetByLabelOptions().setExact(true))
                    .fill(WorkflowSupport.PASSWORD);
            recipient
                    .getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Enter your workspace"))
                    .click();
            assertThat(
                            recipient.getByRole(
                                    AriaRole.HEADING,
                                    new Page.GetByRoleOptions().setName("My requests")))
                    .isVisible();
            accessible(recipient);
            recipient
                    .getByLabel("Summary", new Page.GetByLabelOptions().setExact(true))
                    .fill("Synthetic evaluation request");
            recipient
                    .getByLabel("Description", new Page.GetByLabelOptions().setExact(true))
                    .fill("Please review this synthetic request.");
            recipient
                    .getByRole(
                            AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Submit request"))
                    .click();
            assertThat(
                            recipient.getByRole(
                                    AriaRole.HEADING,
                                    new Page.GetByRoleOptions()
                                            .setName("Synthetic evaluation request")))
                    .isVisible();
            recipient.getByLabel("Add a comment").fill("Shared reply from requester");
            recipient
                    .getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Post comment"))
                    .click();
            assertThat(
                            recipient.getByText(
                                    "Shared reply from requester",
                                    new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            assertThat(recipient.getByLabel("Add an internal note")).hasCount(0);
            accessible(recipient);
            Files.createDirectories(Path.of("target/browser-artifacts"));
            recipient.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/browser-artifacts/requester-mobile.png"))
                            .setFullPage(true));
            recipient.navigate(h.base() + "/forgot");
            recipient.getByLabel("Verified email address").fill(email);
            recipient
                    .getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Request recovery email"))
                    .click();
            accessible(recipient);
            assertTrue(worker.runOne(System.currentTimeMillis() + 100));
            recipient.navigate(h.base() + ReleaseAcceptance.link(sink.receive(), "recover"));
            accessible(recipient);
            recipient.getByLabel("New password").fill(WorkflowSupport.PASSWORD + "new");
            recipient
                    .getByRole(
                            AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Reset password"))
                    .click();
            recipient
                    .getByLabel("Username", new Page.GetByLabelOptions().setExact(true))
                    .fill(name);
            recipient
                    .getByLabel("Password", new Page.GetByLabelOptions().setExact(true))
                    .fill(WorkflowSupport.PASSWORD + "new");
            recipient
                    .getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Enter your workspace"))
                    .click();
            assertThat(
                            recipient.getByRole(
                                    AriaRole.HEADING,
                                    new Page.GetByRoleOptions().setName("My requests")))
                    .isVisible();
            String user;
            try (var db = h.store.open()) {
                user = RecordAccess.owner(db, name);
            }
            admin.navigate(h.base() + "/admin/accounts/" + user);
            admin.getByLabel("Account status").selectOption("false");
            admin.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Save account status"))
                    .click();
            recipient.navigate(h.base() + "/requests");
            assertThat(
                            recipient.getByRole(
                                    AriaRole.HEADING,
                                    new Page.GetByRoleOptions().setName("Welcome back.")))
                    .isVisible();
            admin.navigate(h.base() + "/admin/delivery");
            accessible(admin);
            assertThat(admin.getByRole(AriaRole.TABLE)).containsText("delivered");
        } finally {
            properties.remove("release-browser-mail");
        }
    }
}
