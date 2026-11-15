package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class ApprovalBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "Reviewer" + id().substring(0, 8), record;
        try (var db = h.store.open()) {
            Service.createAccount(
                    db, map("name", name, "role", "agent", "password", WorkflowSupport.PASSWORD));
            ApprovalTest.as(db, "admin");
            record =
                    Service.create(
                                    db,
                                    "incident",
                                    map("short_description", "Browser review"),
                                    "admin")
                            .get("sys_id")
                            .toString();
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var admin = browser.newContext();
                var reviewer = browser.newContext()) {
            var operator = admin.newPage();
            var page = reviewer.newPage();
            h.login(operator, "admin");
            h.login(page, name);
            String location = h.base() + "/record/incident/" + record + "/approvals";
            operator.navigate(h.base() + "/record/incident/" + record);
            operator.getByRole(
                            AriaRole.LINK, new Page.GetByRoleOptions().setName("Record approvals"))
                    .click();
            assertThat(operator.getByText("No reviews requested yet.")).isVisible();
            operator.getByLabel("Reviewer username").fill(name);
            operator.getByLabel("Proposed state").selectOption("2");
            operator.getByLabel("Reason for review").fill("<script>review content</script>");
            operator.getByRole(
                            AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Request review"))
                    .click();
            assertThat(operator.getByText("<script>review content</script>")).isVisible();
            assertEquals(
                    0,
                    operator.getByLabel("Decision", new Page.GetByLabelOptions().setExact(true))
                            .count());
            page.navigate(location);
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/approvals-desktop.png"))
                            .setFullPage(true));
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/approvals-mobile-dark.png"))
                            .setFullPage(true));
            page.getByLabel("Decision", new Page.GetByLabelOptions().setExact(true))
                    .selectOption("rejected");
            page.getByLabel("Review comment").fill("Please clarify the impact first.");
            var decide =
                    page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Record decision"));
            decide.focus();
            decide.press("Enter");
            assertThat(page.getByText(name + " · rejected")).isVisible();
            assertThat(page.getByText("Please clarify the impact first.")).isVisible();
            assertEquals(
                    0,
                    page.getByLabel("Decision", new Page.GetByLabelOptions().setExact(true))
                            .count());
            page.reload();
            assertThat(page.getByText(name + " · rejected")).isVisible();
            ReleaseBrowserAcceptance.accessible(page);
        }
    }
}
