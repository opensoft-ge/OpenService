package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Path;
import java.util.*;

final class PlatformBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "Platform" + id().substring(0, 6), actor, clock;
        try (var db = h.store.open()) {
            actor = PlatformFoundationTest.bind(db);
            db.update("DELETE FROM os_work");
            Service.createAccount(
                    db, map("name", name, "role", "reader", "password", WorkflowSupport.PASSWORD));
            String record = PlatformFoundationTest.record(db, "incident", "Response clock");
            ServiceClocks.calendar(
                    db,
                    actor,
                    map("name", "Browser office", "calendar", PlatformFoundationTest.calendar()));
            clock =
                    ServiceClocks.start(
                                    db,
                                    actor,
                                    map(
                                            "table",
                                            "incident",
                                            "record_id",
                                            record,
                                            "calendar",
                                            "Browser office",
                                            "minutes",
                                            120),
                                    System.currentTimeMillis())
                            .get("sla_id")
                            .toString();
            var knowledge =
                    Service.create(
                            db,
                            "kb_knowledge",
                            map(
                                    "short_description",
                                    "How to request help",
                                    "text",
                                    "Use My requests to submit an issue."),
                            "admin");
            PublishedKnowledge.publish(db, actor, knowledge.get("sys_id").toString());
            db.connection.commit();
        }
        try (var p = Playwright.create();
                var browser = p.chromium().launch();
                var context = browser.newContext()) {
            var page = context.newPage();
            h.login(page, "admin");
            page.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("Operations").setExact(true))
                    .click();
            page.getByLabel("Recipient username").fill(name);
            page.getByLabel("Message", new Page.GetByLabelOptions().setExact(true))
                    .fill("Your request is ready for review.");
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Queue notification"))
                    .press("Enter");
            assertThat(page.locator("tbody").first()).containsText("queued");
            assertTrue(new WorkWorker(h.store).runOne(System.currentTimeMillis() + 100));
            page.reload();
            assertThat(page.locator("tbody").first()).containsText("succeeded");
            var row =
                    page.locator("tr")
                            .filter(
                                    new Locator.FilterOptions()
                                            .setHas(
                                                    page.locator(
                                                            "input[name='sla_id'][value='"
                                                                    + clock
                                                                    + "']")));
            row.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Pause")).click();
            assertThat(page.locator("tbody").last()).containsText("paused");
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/platform-operations-desktop.png"))
                            .setFullPage(true));
            page.setViewportSize(390, 844);
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Dark mode").setExact(true))
                    .click();
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/platform-operations-mobile-dark.png"))
                            .setFullPage(true));
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign out"))
                    .click();
            h.login(page, name);
            page.navigate(h.base() + "/account/notifications");
            assertThat(page.getByText("Your request is ready for review.")).isVisible();
            page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Acknowledge").setExact(true))
                    .click();
            assertThat(page.getByText("Acknowledged", new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            page.getByLabel("Business notifications").selectOption("false");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save preference"))
                    .click();
            ReleaseBrowserAcceptance.accessible(page);
            page.getByRole(
                            AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("Published knowledge"))
                    .click();
            assertThat(
                            page.getByRole(
                                    AriaRole.HEADING,
                                    new Page.GetByRoleOptions().setName("How to request help")))
                    .isVisible();
            ReleaseBrowserAcceptance.accessible(page);
            page.screenshot(
                    new Page.ScreenshotOptions()
                            .setPath(Path.of("target/platform-knowledge-mobile-dark.png"))
                            .setFullPage(true));
        }
    }

    static void queueHealth(BrowserTest h) throws Exception {
        for(String theme:List.of("light","dark")) {
            String job;
            try(var db=h.store.open()) {
                String actor=PlatformFoundationTest.bind(db);
                db.update("DELETE FROM os_work");
                job=WorkJobs.enqueue(h.store,db,actor,map("kind","notification","key",id(),"payload",map("username","admin","message","Queued health check"))).get("job_id").toString();
                db.update("UPDATE os_work SET available_at=? WHERE job_id=?",System.currentTimeMillis()-600000,job);
                db.connection.commit();
            }
            try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()) {
                var page=context.newPage();
                page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(theme.equals("dark")?com.microsoft.playwright.options.ColorScheme.DARK:com.microsoft.playwright.options.ColorScheme.LIGHT));
                page.setViewportSize(theme.equals("dark")?390:1280,844);
                h.login(page,"admin");page.navigate(h.base()+"/admin/operations");
                var health=page.getByRole(AriaRole.REGION,new Page.GetByRoleOptions().setName("Queue health"));
                assertThat(health).containsText("Enable the business worker");
                assertThat(health).containsText("at least five minutes");
                ReleaseBrowserAcceptance.accessible(page);
                page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/queue-health-"+theme+".png")).setFullPage(true));
                var row=page.locator("tr").filter(new Locator.FilterOptions().setHasText(job));
                row.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Cancel").setExact(true)).press("Enter");
                assertThat(health).containsText("No queue alerts.");
                page.reload();assertThat(health).containsText("No queue alerts.");
                try(var db=h.store.open()) { db.update("UPDATE os_work SET created_at=0 WHERE job_id=?",job);db.connection.commit(); }
                health.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Clean up completed work older than 30 days")).press("Enter");
                assertThat(page.locator("tr").filter(new Locator.FilterOptions().setHasText(job))).hasCount(0);
                assertThat(health).containsText("5000 of 5000");
                page.reload();assertThat(health).containsText("5000 of 5000");
                ReleaseBrowserAcceptance.accessible(page);
            }
        }
    }

    static void numericRanges(BrowserTest h) throws Exception {
        String table="u_browser_range_"+id();
        try(var db=h.store.open()) {
            String actor=PlatformFoundationTest.bind(db);
            Configuration.install(db,ConfigurationAcceptance.bundle(table,ConfigurationAcceptance.definition()),actor,false);
            for(String amount:List.of("2","10","20"))
                Service.create(db,table,map("short_description","Amount "+amount,"amount",amount),"admin");
            db.connection.commit();
        }
        for(String theme:List.of("light","dark")) {
            try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()) {
                var page=context.newPage();
                page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(theme.equals("dark")?com.microsoft.playwright.options.ColorScheme.DARK:com.microsoft.playwright.options.ColorScheme.LIGHT));
                page.setViewportSize(theme.equals("dark")?390:1280,900);
                h.login(page,"admin");
                page.navigate(h.base()+"/analytics/"+table+"?field=amount");
                assertThat(page.locator("html")).hasAttribute("data-theme",theme);
                page.getByLabel("Minimum value",new Page.GetByLabelOptions().setExact(false)).fill("2");
                page.getByLabel("Maximum value",new Page.GetByLabelOptions().setExact(false)).fill("10");
                page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Calculate metrics")).press("Enter");
                var row=page.locator("tbody tr").first();
                assertThat(row.locator("td").nth(3)).hasText("12");
                page.reload();
                assertThat(page.locator("input[name=minimum]")).hasValue("2");
                assertThat(page.locator("input[name=maximum]")).hasValue("10");
                if(theme.equals("dark")) {
                    page.getByRole(AriaRole.REGION,new Page.GetByRoleOptions().setName("Numeric metrics")).press("ArrowRight");
                    page.waitForCondition(() -> ((Number)page.locator(".table-scroll").evaluate("element => element.scrollLeft")).doubleValue() > 0);
                }
                ReleaseBrowserAcceptance.accessible(page);
                page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/numeric-range-"+theme+".png")).setFullPage(true));
                page.locator("input[name=minimum]").fill("");
                page.locator("input[name=maximum]").fill("");
                page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Calculate metrics")).press("Enter");
                assertThat(row.locator("td").nth(3)).hasText("32");
            }
        }
    }

    static void cancelClocks(BrowserTest h) throws Exception {
        for (String theme : List.of("light", "dark")) {
            String record;
            try (var db = h.store.open()) {
                String actor = PlatformFoundationTest.bind(db);
                record = PlatformFoundationTest.record(db, "incident", "Cancel clock " + theme);
                ServiceClocks.calendar(db, actor, map("name", "cancel-browser", "calendar", PlatformFoundationTest.calendar()));
                ServiceClocks.start(db, actor, map("table", "incident", "record_id", record, "calendar", "cancel-browser", "minutes", 120), System.currentTimeMillis());
                db.connection.commit();
            }
            try (var p = Playwright.create(); var browser = p.chromium().launch(); var context = browser.newContext()) {
                var page = context.newPage();
                page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(theme.equals("dark") ? com.microsoft.playwright.options.ColorScheme.DARK : com.microsoft.playwright.options.ColorScheme.LIGHT));
                page.setViewportSize(theme.equals("dark") ? 390 : 1280, 900);
                h.login(page, "admin");
                page.navigate(h.base()+"/admin/operations");
                assertThat(page.locator("html")).hasAttribute("data-theme", theme);
                var row = page.locator("tr").filter(new Locator.FilterOptions().setHas(page.locator("a[href='/record/incident/"+record+"']")));
                ReleaseBrowserAcceptance.accessible(page);
                row.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Cancel clock").setExact(true)).press("Enter");
                assertThat(row).containsText("cancelled");
                page.reload();
                assertThat(row).containsText("cancelled");
                assertThat(row.locator("button")).hasCount(0);
                ReleaseBrowserAcceptance.accessible(page);
                page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/sla-cancel-"+theme+".png")).setFullPage(true));
            }
        }
    }

    static void replacement(BrowserTest h) throws Exception {
        String name = "ChangeMail" + id().substring(0, 6),
                oldEmail = name.toLowerCase() + "@old.invalid",
                newEmail = name.toLowerCase() + "@new.invalid";
        var properties = ((ConfigurableEnvironment) h.environment).getPropertySources();
        properties.addFirst(
                new MapPropertySource(
                        "replacement-proof",
                        map(
                                "DELIVERY_ENABLED",
                                true,
                                "PUBLIC_BASE_URL",
                                "https://example.invalid")));
        try {
            try (var db = h.store.open()) {
                Service.createAccount(
                        db,
                        map("name", name, "role", "reader", "password", WorkflowSupport.PASSWORD));
                db.update(
                        "UPDATE os_user SET email=?,email_verified=1 WHERE name=?", oldEmail, name);
                db.connection.commit();
            }
            try (var p = Playwright.create();
                    var browser = p.chromium().launch();
                    var context = browser.newContext()) {
                var page = context.newPage();
                h.login(page, name);
                page.navigate(h.base() + "/account/email");
                page.getByLabel("Recovery email address").fill(newEmail);
                page.getByLabel("Current password").fill(WorkflowSupport.PASSWORD);
                page.getByRole(
                                AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("Send verification email"))
                        .click();
                assertThat(page.getByRole(AriaRole.STATUS))
                        .containsText("Verification message queued");
                var links = new LinkedHashMap<String, String>();
                try (var db = h.store.open()) {
                    for (var row :
                            db.all(
                                    "SELECT envelope FROM os_outbox WHERE kind='email.enrollment'"
                                        + " AND envelope<>''")) {
                        var payload =
                                Outbox.decrypt(h.store.secret, row.get("envelope").toString());
                        if (Set.of(oldEmail, newEmail).contains(payload.get("email")))
                            links.put(
                                    payload.get("email").toString(),
                                    payload.get("path").toString());
                    }
                }
                assertEquals(2, links.size());
                page.navigate(h.base() + links.get(newEmail));
                page.getByRole(
                                AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("Verify email address"))
                        .click();
                h.login(page, name);
                page.navigate(h.base() + "/account/email");
                assertThat(page.getByText("Registered address: " + oldEmail)).isVisible();
                page.navigate(h.base() + links.get(oldEmail));
                page.setViewportSize(390, 844);
                page.getByRole(
                                AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("Dark mode").setExact(true))
                        .click();
                ReleaseBrowserAcceptance.accessible(page);
                page.getByRole(
                                AriaRole.BUTTON,
                                new Page.GetByRoleOptions().setName("Verify email address"))
                        .click();
                h.login(page, name);
                page.navigate(h.base() + "/account/email");
                assertThat(page.getByText("Registered address: " + newEmail)).isVisible();
                ReleaseBrowserAcceptance.accessible(page);
            }
        } finally {
            properties.remove("replacement-proof");
        }
    }
}
