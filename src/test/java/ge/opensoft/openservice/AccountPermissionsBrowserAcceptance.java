package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class AccountPermissionsBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String target, record, admin;
        try (var db = h.store.open()) {
            String name = "Inspector" + id().substring(0, 8);
            Service.createAccount(
                    db, map("name", name, "password", WorkflowSupport.PASSWORD, "role", "reader"));
            target = RecordAccess.owner(db, name);
            admin = RecordAccess.owner(db, "admin");
            record =
                    Service.create(
                                    db,
                                    "incident",
                                    map("short_description", "Sensitive example"),
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
                            new Page.GetByRoleOptions().setName("Inspect account permissions"))
                    .click();
            assertThat(
                            page.getByRole(
                                    AriaRole.HEADING,
                                    new Page.GetByRoleOptions()
                                            .setName("Account permission diagnosis")))
                    .isVisible();
            page.getByLabel("Table name").fill("incident");
            page.getByLabel("Record ID").fill(record);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Inspect permissions"))
                    .press("Enter");
            assertThat(
                            page.getByText(
                                    "Can read this record",
                                    new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            assertThat(
                            page.getByText(
                                    "Sensitive example",
                                    new Page.GetByTextOptions().setExact(true)))
                    .hasCount(0);
            assertThat(page.locator("tbody tr").first()).containsText("Blocked");
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/account-permissions-desktop.png"))
                            .setFullPage(true));
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Dark mode").setExact(true))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/account-permissions-mobile-dark.png"))
                            .setFullPage(true));
            try (var db = h.store.open()) {
                RecordAccess.change(
                        db,
                        Service.record(db, "incident", record),
                        admin,
                        map("visibility", "private", "owner_id", admin));
                db.connection.commit();
            }
            page.reload();
            assertThat(
                            page.getByText(
                                    "Record visibility blocks access",
                                    new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            assertThat(page.locator("table")).hasCount(0);
            ReleaseBrowserAcceptance.accessible(page);
        }
    }
}
