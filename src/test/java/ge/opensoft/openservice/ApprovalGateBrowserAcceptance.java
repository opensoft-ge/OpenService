package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class ApprovalGateBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "Gate" + id().substring(0, 8), table = "u_gate_" + id(), record;
        try (var db = h.store.open()) {
            Service.createAccount(
                    db, map("name", name, "role", "agent", "password", WorkflowSupport.PASSWORD));
            var admin = ApprovalTest.as(db, "admin");
            var definition = ApprovalGateAcceptance.definition(Service.schema(db, "incident"), 1);
            definition.put("label", "Peer-reviewed cases");
            Configuration.install(
                    db,
                    ConfigurationAcceptance.bundle(table, definition),
                    admin.get("user_id").toString(),
                    false);
            record =
                    Service.create(
                                    db,
                                    table,
                                    map("short_description", "Review before starting work"),
                                    "admin")
                            .get("sys_id")
                            .toString();
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
            String url = h.base() + "/record/" + table + "/" + record;
            page.navigate(url);
            assertThat(page.getByText("Minimum approvals for In progress: 1")).isVisible();
            page.locator("select[name=state]").selectOption("2");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save changes"))
                    .click();
            assertThat(
                            page.getByText(
                                    "This transition requires 1 current approvals from other"
                                            + " staff"))
                    .isVisible();
            page.navigate(url);
            assertThat(page.locator("select[name=state]")).hasValue("1");
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Record approvals"))
                    .click();
            assertThat(page.locator("[data-approval-target='2']"))
                    .containsText("0 qualifying · 1 required");
            assertThat(page.locator("[data-approval-target='2']"))
                    .containsText("More approvals needed");
            ReleaseBrowserAcceptance.accessible(page);
            page.getByLabel("Reviewer username").fill(name);
            page.getByLabel("Proposed state").selectOption("2");
            page.getByLabel("Reason for review")
                    .fill("Please review this change before execution.");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Request review"))
                    .click();
            assigned.navigate(url + "/approvals");
            assigned.getByLabel("Decision", new Page.GetByLabelOptions().setExact(true))
                    .selectOption("approved");
            assigned.getByLabel("Review comment").fill("The proposed transition is appropriate.");
            assigned.getByRole(
                            AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Record decision"))
                    .click();
            assertThat(assigned.locator("[data-approval-target='2']"))
                    .containsText("0 qualifying · 1 required");
            page.navigate(url + "/approvals");
            assertThat(page.locator("[data-approval-target='2']"))
                    .containsText("1 qualifying · 1 required");
            assertThat(page.locator("[data-approval-target='2']"))
                    .containsText("Approval requirement met");
            ReleaseBrowserAcceptance.accessible(page);
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/approval-requirements-mobile-dark.png"))
                            .setFullPage(true));
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            page.navigate(url);
            assertThat(page.locator("select[name=state]")).hasValue("1");
            page.locator("select[name=state]").selectOption("2");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save changes"))
                    .click();
            assertThat(page.locator("select[name=state]")).hasValue("2");
            ReleaseBrowserAcceptance.accessible(page);
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode"))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/approval-gated-transition-mobile-dark.png"))
                            .setFullPage(true));
        }
    }
}
