package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;

import java.nio.file.*;
import java.util.*;

final class ThemeBrowserAcceptance {
    static Locator toggle(Page page) {
        return page.getByRole(
                AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Dark mode").setExact(true));
    }

    static void theme(Page page, String expected) {
        assertThat(page.locator("html")).hasAttribute("data-theme", expected);
        assertThat(toggle(page))
                .hasAttribute("aria-pressed", String.valueOf(expected.equals("dark")));
        assertEquals(
                expected, page.evaluate("getComputedStyle(document.documentElement).colorScheme"));
    }

    static CDPSession instrument(BrowserContext context, Page page) {
        var cdp = context.newCDPSession(page);
        cdp.send("Debugger.enable");
        cdp.send("Profiler.enable");
        var options = new JsonObject();
        options.addProperty("callCount", true);
        options.addProperty("detailed", true);
        cdp.send("Profiler.startPreciseCoverage", options);
        return cdp;
    }

    static void collect(CDPSession cdp, JsonArray reports) {
        for (var entry : cdp.send("Profiler.takePreciseCoverage").getAsJsonArray("result")) {
            var script = entry.getAsJsonObject();
            if (!script.get("url").getAsString().endsWith("/static/theme.js")) continue;
            var params = new JsonObject();
            params.add("scriptId", script.get("scriptId"));
            var report = new JsonObject();
            report.add("source", cdp.send("Debugger.getScriptSource", params).get("scriptSource"));
            report.add("functions", script.get("functions"));
            reports.add(report);
        }
    }

    static void run(BrowserTest h) throws Exception {
        var reports = new JsonArray();
        Files.createDirectories(Path.of("target/browser-artifacts"));
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch()) {
            try (var context =
                    browser.newContext(
                            new Browser.NewContextOptions().setColorScheme(ColorScheme.DARK))) {
                var page = context.newPage();
                var errors = new ArrayList<String>();
                page.onPageError(errors::add);
                var cdp = instrument(context, page);
                var script = context.request().get(h.base() + "/static/theme.js");
                assertEquals(200, script.status());
                assertTrue(script.headers().get("content-type").startsWith("text/javascript"));
                assertEquals(
                        Files.readString(Path.of("src/main/resources/static/theme.js")),
                        script.text());
                assertEquals(405, context.request().post(h.base() + "/static/theme.js").status());
                page.navigate(h.base() + "/login");
                theme(page, "dark");
                page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.LIGHT));
                theme(page, "light");
                ReleaseBrowserAcceptance.checkAccessible(page);
                page.screenshot(
                        new Page.ScreenshotOptions()
                                .setPath(Path.of("target/browser-artifacts/login-light.png"))
                                .setFullPage(true));
                toggle(page).focus();
                assertEquals(
                        "solid", toggle(page).evaluate("e => getComputedStyle(e).outlineStyle"));
                toggle(page).press("Enter");
                theme(page, "dark");
                assertEquals("dark", page.evaluate("localStorage.getItem('openservice.theme')"));
                page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.DARK));
                page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.LIGHT));
                theme(page, "dark");
                ReleaseBrowserAcceptance.checkAccessible(page);
                page.screenshot(
                        new Page.ScreenshotOptions()
                                .setPath(Path.of("target/browser-artifacts/login-dark.png"))
                                .setFullPage(true));
                collect(cdp, reports);
                page.reload();
                theme(page, "dark");
                toggle(page).press("Space");
                theme(page, "light");
                collect(cdp, reports);
                page.reload();
                theme(page, "light");
                collect(cdp, reports);
                h.login(page, "admin");
                theme(page, "light");
                for (String path :
                        List.of(
                                "/",
                                "/workspace/incident",
                                "/record/incident/new",
                                "/dictionary",
                                "/admin/accounts",
                                "/admin/invitations",
                                "/admin/delivery",
                                "/admin/migrations",
                                "/migration",
                                "/account")) {
                    collect(cdp, reports);
                    assertEquals(200, page.navigate(h.base() + path).status(), path);
                    ReleaseBrowserAcceptance.accessible(page);
                    if (path.equals("/admin/delivery")) {
                        assertThat(page.locator("[aria-label='Complete queue diagnostics']"))
                                .containsText("Oldest pending message age (seconds)");
                    }
                    if (path.equals("/")) {
                        page.screenshot(
                                new Page.ScreenshotOptions()
                                        .setPath(
                                                Path.of(
                                                        "target/browser-artifacts/workspace-light.png"))
                                        .setFullPage(true));
                        toggle(page).click();
                        page.screenshot(
                                new Page.ScreenshotOptions()
                                        .setPath(
                                                Path.of(
                                                        "target/browser-artifacts/workspace-dark.png"))
                                        .setFullPage(true));
                        toggle(page).click();
                    }
                }
                page.setViewportSize(390, 844);
                toggle(page).click();
                theme(page, "dark");
                ReleaseBrowserAcceptance.accessible(page);
                collect(cdp, reports);
                page.getByTitle("Sign out").click();
                theme(page, "dark");
                ReleaseBrowserAcceptance.accessible(page);
                collect(cdp, reports);
                page.navigate(h.base() + "/forgot");
                theme(page, "dark");
                ReleaseBrowserAcceptance.accessible(page);
                collect(cdp, reports);
                // A public form failure must remain readable and have the same preference control.
                page.navigate(h.base() + "/join/" + "x".repeat(32));
                theme(page, "dark");
                ReleaseBrowserAcceptance.accessible(page);
                collect(cdp, reports);
                assertEquals(List.of(), errors);
            }
            for (String preference : List.of("invalid", "blocked")) {
                try (var context =
                        browser.newContext(
                                new Browser.NewContextOptions()
                                        .setColorScheme(ColorScheme.LIGHT))) {
                    if (preference.equals("blocked")) {
                        context.addInitScript(
                                "Object.defineProperty(window, 'localStorage', {get() {throw new"
                                        + " DOMException('Unavailable', 'SecurityError');}})");
                    } else {
                        context.addInitScript(
                                "localStorage.setItem('openservice.theme', 'invalid')");
                    }
                    var page = context.newPage();
                    var cdp = instrument(context, page);
                    page.navigate(h.base() + "/login");
                    theme(page, "light");
                    page.emulateMedia(
                            new Page.EmulateMediaOptions().setColorScheme(ColorScheme.DARK));
                    theme(page, "dark");
                    toggle(page).click();
                    theme(page, "light");
                    toggle(page).click();
                    theme(page, "dark");
                    collect(cdp, reports);
                }
            }
            try (var context =
                    browser.newContext(
                            new Browser.NewContextOptions().setJavaScriptEnabled(false))) {
                var page = context.newPage();
                page.navigate(h.base() + "/login");
                assertThat(page.locator("[data-theme-toggle]")).isHidden();
                assertThat(page.getByLabel("Username", new Page.GetByLabelOptions().setExact(true)))
                        .isVisible();
                assertEquals(
                        "light",
                        page.evaluate("getComputedStyle(document.documentElement).colorScheme"));
            }
        }
        assertFalse(reports.isEmpty());
        Files.writeString(Path.of("target/theme-browser-v8.json"), reports.toString());
    }
}
