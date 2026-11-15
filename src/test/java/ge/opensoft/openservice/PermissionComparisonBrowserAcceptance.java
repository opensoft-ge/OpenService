package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class PermissionComparisonBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String target, record, name = "Compare" + id().substring(0, 6);
        try (var db = h.store.open()) {
            Service.createAccount(
                    db, map("name", name, "role", "reader", "password", WorkflowSupport.PASSWORD));
            target = RecordAccess.owner(db, name);
            record =
                    Service.create(
                                    db,
                                    "incident",
                                    map("short_description", "Hidden business contents"),
                                    "admin")
                            .get("sys_id")
                            .toString();
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
                            new Page.GetByRoleOptions().setName("Compare account permissions"))
                    .press("Enter");
            page.getByLabel("Compare with username").fill("admin");
            page.getByLabel("Table name").fill("incident");
            page.getByLabel("Record ID").fill(record);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions()
                                    .setName("Compare permissions")
                                    .setExact(true))
                    .press("Enter");
            assertThat(
                            page.getByRole(
                                    AriaRole.HEADING,
                                    new Page.GetByRoleOptions().setName(name).setExact(true)))
                    .isVisible();
            assertThat(
                            page.getByRole(
                                    AriaRole.HEADING,
                                    new Page.GetByRoleOptions().setName("admin").setExact(true)))
                    .isVisible();
            assertThat(
                            page.getByRole(
                                            AriaRole.TABLE,
                                            new Page.GetByRoleOptions()
                                                    .setName(name + " field permissions"))
                                    .locator("tbody tr")
                                    .first())
                    .containsText("Blocked");
            assertThat(
                            page.getByRole(
                                            AriaRole.TABLE,
                                            new Page.GetByRoleOptions()
                                                    .setName("admin field permissions"))
                                    .locator("tbody tr")
                                    .first())
                    .containsText("Allowed");
            assertThat(page.getByText("Hidden business contents")).hasCount(0);
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/account-permissions-comparison-desktop.png"))
                            .setFullPage(true));
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Dark mode").setExact(true))
                    .click();
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(
                                    Path.of(
                                            "target/account-permissions-comparison-mobile-dark.png"))
                            .setFullPage(true));
        }
    }
}
