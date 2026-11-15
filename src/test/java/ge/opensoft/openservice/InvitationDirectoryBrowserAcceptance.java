package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.nio.file.Path;

final class InvitationDirectoryBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String prefix = "Invite" + id().substring(0, 6);
        try (var db = h.store.open()) {
            String admin = RecordAccess.owner(db, "admin");
            for (int i = 0; i < 27; i++)
                Invitations.issue(db, admin, map("name", prefix + i, "role", "reader"));
            db.connection.commit();
        }
        try (var p = Playwright.create();
                var browser = p.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            h.login(page, "admin");
            page.navigate(h.base() + "/admin/invitations");
            page.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("Search all invitations"))
                    .click();
            page.getByLabel("Find invited username").fill(prefix);
            page.getByLabel("Filter invited role").selectOption("reader");
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Find invitations").setExact(true))
                    .press("Enter");
            assertThat(page.locator("tbody tr")).hasCount(25);
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Next invitations"))
                    .press("Enter");
            assertThat(page.locator("tbody tr")).hasCount(2);
            assertThat(page.getByLabel("Find invited username")).hasValue(prefix);
            assertThat(page.getByLabel("Filter invited role")).hasValue("reader");
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/invitation-directory-desktop.png"))
                            .setFullPage(true));
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Dark mode").setExact(true))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/invitation-directory-mobile-dark.png"))
                            .setFullPage(true));
            page.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("Previous invitations"))
                    .press("Enter");
            assertThat(page.locator("tbody tr")).hasCount(25);
            page.getByLabel("Find invited username").fill(prefix + "0");
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Find invitations").setExact(true))
                    .press("Enter");
            assertThat(page.locator("tbody tr")).hasCount(1);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions()
                                    .setName("Revoke invitation " + prefix + "0")
                                    .setExact(true))
                    .press("Enter");
            assertThat(
                            page.getByRole(
                                    AriaRole.HEADING,
                                    new Page.GetByRoleOptions()
                                            .setName("Invitations")
                                            .setExact(true)))
                    .isVisible();
            page.navigate(h.base() + "/admin/security-events");
            assertThat(page.locator("tbody tr").first()).containsText("invitation.revoked");
            assertThat(page.locator("tbody tr").first()).containsText(prefix + "0");
            ReleaseBrowserAcceptance.accessible(page);
            page.setViewportSize(1280, 900);
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Dark mode").setExact(true))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/invitation-audit-desktop.png"))
                            .setFullPage(true));
            page.setViewportSize(390, 844);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Dark mode").setExact(true))
                    .click();
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/invitation-audit-mobile-dark.png"))
                            .setFullPage(true));
        }
    }
}
