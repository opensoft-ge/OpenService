package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

final class DeliveryOperationsBrowserAcceptance {
    static void cancellation(BrowserTest h) throws Exception {
        String queued;
        try (var db = h.store.open()) {
            db.update("DELETE FROM os_outbox");
            queued = DeliveryOperationsAcceptance.seed(db, "queued", 1, 0, Long.MAX_VALUE);
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            h.login(page, "admin");
            page.navigate(h.base() + "/admin/delivery");
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Cancel delivery"))
                    .click();
            assertThat(page.getByRole(AriaRole.TABLE)).containsText("OPERATOR_CANCELLED");
            assertThat(
                            page.getByRole(
                                    AriaRole.BUTTON,
                                    new Page.GetByRoleOptions().setName("Cancel delivery")))
                    .hasCount(0);
            page.reload();
            assertThat(page.getByRole(AriaRole.TABLE)).containsText(queued);
            page.setViewportSize(390, 844);
            ReleaseBrowserAcceptance.accessible(page);
        } finally {
            try (var db = h.store.open()) {
                db.update("DELETE FROM os_outbox");
                db.connection.commit();
            }
        }
    }

    static void directory(BrowserTest h) throws Exception {
        String failed;
        try (var db = h.store.open()) {
            db.update("DELETE FROM os_outbox");
            for (int i = 0; i < 101; i++)
                DeliveryOperationsAcceptance.seed(db, "delivered", 100, 0, Long.MAX_VALUE);
            failed = DeliveryOperationsAcceptance.seed(db, "failed", 1, 0, Long.MAX_VALUE);
            db.update("UPDATE os_outbox SET kind='email.recovery' WHERE job_id=?", failed);
            db.connection.commit();
        }
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            h.login(page, "admin");
            page.navigate(h.base() + "/admin/delivery");
            page.getByLabel("Delivery state").selectOption("failed");
            page.getByLabel("Message kind").selectOption("recovery");
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Filter deliveries"))
                    .click();
            assertThat(page.getByRole(AriaRole.TABLE)).containsText(failed);
            assertThat(page.locator("[aria-label='Delivery pages']")).containsText("1 messages");
            ReleaseBrowserAcceptance.accessible(page);
            page.getByLabel("Delivery state").selectOption("");
            page.getByLabel("Message kind").selectOption("invitation");
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Filter deliveries"))
                    .click();
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Next")).click();
            assertThat(page.locator("[aria-label='Delivery pages']")).containsText("Page 2 of 2");
            assertThat(page.getByRole(AriaRole.TABLE).locator("tbody tr")).hasCount(1);
            assertThat(page.getByLabel("Message kind")).hasValue("invitation");
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Previous")).click();
            assertThat(page.getByRole(AriaRole.TABLE).locator("tbody tr")).hasCount(100);
            page.setViewportSize(390, 844);
            page.getByLabel("Delivery state").selectOption("queued");
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Filter deliveries"))
                    .click();
            assertThat(page.getByText("No deliveries match these filters.")).isVisible();
            ReleaseBrowserAcceptance.accessible(page);
            var scroller = page.locator("[aria-label='Delivery message list']");
            var clear =
                    page.getByRole(
                            AriaRole.LINK, new Page.GetByRoleOptions().setName("Clear filters"));
            clear.focus();
            clear.press("Tab");
            assertThat(scroller).isFocused();
            assertEquals("solid", scroller.evaluate("e => getComputedStyle(e).outlineStyle"));
            scroller.press("ArrowRight");
            page.waitForCondition(
                    () -> ((Number) scroller.evaluate("e => e.scrollLeft")).doubleValue() > 0);
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Clear filters"))
                    .click();
            assertThat(page.locator("[aria-label='Delivery pages']")).containsText("102 messages");
        } finally {
            try (var db = h.store.open()) {
                db.update("DELETE FROM os_outbox");
                db.connection.commit();
            }
        }
    }
}
