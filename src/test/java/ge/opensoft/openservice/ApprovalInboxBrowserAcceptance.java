package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class ApprovalInboxBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "Inbox" + id().substring(0, 8), record, approval;
        try (var db = h.store.open()) {
            Service.createAccount(
                    db, map("name", name, "role", "agent", "password", WorkflowSupport.PASSWORD));
            ApprovalTest.as(db, "admin");
            record =
                    Service.create(
                                    db,
                                    "incident",
                                    map("short_description", "Queue journey"),
                                    "admin")
                            .get("sys_id")
                            .toString();
            approval =
                    Approvals.request(
                                    db,
                                    "incident",
                                    record,
                                    ApprovalTest.as(db, "admin"),
                                    map(
                                            "expected_revision",
                                            0,
                                            "approver",
                                            name,
                                            "target_state",
                                            "2",
                                            "reason",
                                            "<script>Review safely</script>"))
                            .get("approval_id")
                            .toString();
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            h.login(page, name);
            page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Notifications").setExact(true)).click();
            assertThat(page.locator("article")).hasCount(1);
            assertThat(page.locator("article")).containsText(approval);
            assertThat(page.locator("article")).not().containsText("Review safely");
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/approval-assignment-notice-light.png")).setFullPage(true));
            page.setViewportSize(390,844);
            page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/approval-assignment-notice-dark.png")).setFullPage(true));
            page.setViewportSize(1280,720);
            page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();
            page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Acknowledge").setExact(true)).click();
            assertThat(page.getByText("Acknowledged",new Page.GetByTextOptions().setExact(true))).isVisible();

            page.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("My approvals").setExact(true))
                    .click();
            assertThat(
                            page.getByRole(
                                    AriaRole.HEADING,
                                    new Page.GetByRoleOptions().setName("My approvals")))
                    .isVisible();
            assertThat(page.getByText("<script>Review safely</script>")).isVisible();
            assertEquals(0, page.locator("main script").count());
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/approval-inbox-desktop.png"))
                            .setFullPage(true));
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/approval-inbox-mobile-dark.png"))
                            .setFullPage(true));
            var review = page.locator("article h3 a");
            review.focus();
            review.press("Enter");
            assertThat(page).hasURL(h.base() + "/record/incident/" + record + "/approvals");
            page.getByLabel("Decision", new Page.GetByLabelOptions().setExact(true))
                    .selectOption("rejected");
            page.getByLabel("Review comment").fill("Clarify the proposed impact.");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Record decision"))
                    .click();
            page.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("My approvals").setExact(true))
                    .click();
            assertThat(page.getByText("No accessible reviews match this filter.")).isVisible();
            page.getByLabel("Review status").selectOption("rejected");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Apply filter"))
                    .click();
            assertThat(page.getByText("Clarify the proposed impact.")).isVisible();
            ReleaseBrowserAcceptance.accessible(page);
            try (var db = h.store.open()) {
                for (int i = 1; i <= 100; i++) ApprovalInboxAcceptance.copy(db, approval, i);
                db.connection.commit();
            }
            page.reload();
            assertThat(page.locator("article")).hasCount(100);
            assertThat(
                            page.getByText(
                                            "Outdated proposal. A new review is needed for the"
                                                + " changed record or configuration.")
                                    .first())
                    .isVisible();
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Older reviews"))
                    .click();
            assertThat(page.locator("article")).hasCount(1);
            assertThat(page.getByLabel("Review status")).hasValue("rejected");
            assertEquals(
                    0,
                    page.getByRole(
                                    AriaRole.LINK,
                                    new Page.GetByRoleOptions().setName("Older reviews"))
                            .count());
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Latest reviews"))
                    .click();
            assertThat(page.locator("article")).hasCount(100);
        }
    }
}
