package ge.opensoft.openservice;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;

final class CalendarFormsBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name="BrowserCalendar"+id().substring(0,8);
        try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()) {
            var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/admin/operations");
            var create=page.locator("form[data-calendar='new']");
            create.getByLabel("Calendar name").fill(name);
            create.getByLabel("Time zone").fill("Europe/London");
            create.getByLabel("Work starts").fill("09:00");create.getByLabel("Work ends").fill("17:00");
            create.getByLabel("Working weekdays").fill("1,2,3,4,5");create.getByLabel("Excluded holiday dates").fill("2026-12-25");
            create.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Create calendar")).press("Enter");
            var edit=page.locator("form[data-calendar='"+name+"']");
            assertThat(edit.getByLabel("Excluded holiday dates")).hasValue("2026-12-25");
            edit.getByLabel("Work starts").fill("08:30");
            edit.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Save calendar")).click();
            assertThat(edit.getByLabel("Work starts")).hasValue("08:30");
            for(String theme:new String[]{"light","dark"}) {
                if(theme.equals("dark")) {page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}
                assertThat(create.getByText("Use comma-separated numbers: Monday is 1, Sunday is 7. Working hours must finish on the same day.")).isVisible();
                ReleaseBrowserAcceptance.accessible(page);
                page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/calendar-management-"+theme+".png")).setFullPage(true));
            }
            edit.getByLabel("Time zone").fill("Invalid/Zone");
            edit.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Save calendar")).click();
            assertThat(page.locator("main")).containsText("Invalid calendar date, time or timezone");
        }
    }
}
