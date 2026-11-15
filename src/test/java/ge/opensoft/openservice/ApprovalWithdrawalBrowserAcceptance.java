package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class ApprovalWithdrawalBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "Withdraw" + id().substring(0, 8), record;
        try (var db = h.store.open()) {
            Service.createAccount(
                    db, map("name", name, "role", "agent", "password", WorkflowSupport.PASSWORD));
            var admin = ApprovalTest.as(db, "admin");
            record =
                    Service.create(
                                    db,
                                    "incident",
                                    map("short_description", "Withdraw obsolete review"),
                                    "admin")
                            .get("sys_id")
                            .toString();
            Approvals.request(db, "incident", record, admin, ApprovalAcceptance.proposal(name));
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var admin = browser.newContext();
                var reviewer = browser.newContext()) {
            var page = admin.newPage();
            var assigned = reviewer.newPage();
            h.login(page, "admin");
            h.login(assigned, name);
            String url = h.base() + "/record/incident/" + record + "/approvals";
            assigned.navigate(url);
            assertEquals(
                    0,
                    assigned.getByRole(
                                    AriaRole.BUTTON,
                                    new Page.GetByRoleOptions().setName("Withdraw review"))
                            .count());
            page.navigate(url);
            ReleaseBrowserAcceptance.accessible(page);
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            page.getByLabel("Withdrawal reason").fill("<script>No longer needed</script>");
            var withdraw =
                    page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Withdraw review"));
            withdraw.focus();
            withdraw.press("Enter");
            assertThat(page.getByText(name + " · withdrawn")).isVisible();
            assertThat(page.getByText("<script>No longer needed</script>")).isVisible();
            assertEquals(0, page.locator("main script").count());
            assertEquals(
                    0,
                    page.getByRole(
                                    AriaRole.BUTTON,
                                    new Page.GetByRoleOptions().setName("Withdraw review"))
                            .count());
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/approval-withdrawal-mobile-dark.png"))
                            .setFullPage(true));
            assigned.navigate(h.base() + "/account/approvals");
            assertThat(assigned.getByText("No accessible reviews match this filter.")).isVisible();
            assigned.getByLabel("Review status").selectOption("withdrawn");
            assigned.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Apply filter"))
                    .click();
            assertThat(assigned.getByText("<script>No longer needed</script>")).isVisible();
            ReleaseBrowserAcceptance.accessible(assigned);
        }
    }
}
