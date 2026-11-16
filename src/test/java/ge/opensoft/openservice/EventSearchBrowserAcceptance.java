package ge.opensoft.openservice;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;

final class EventSearchBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String prefix="BrowserEvent"+id().substring(0,8);
        try(var db=h.store.open()) {
            String actor=PlatformFoundationTest.bind(db),asset=PlatformFoundationTest.record(db,"cmdb_ci","Event source");
            for(int i=0;i<3;i++)AssetOperations.event(db,actor,map("key",prefix+i,"asset_id",asset,"severity","warning","summary","Visible event <script>alert(1)</script>"),i);
            db.connection.commit();
        }
        try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()) {
            var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/admin/operations");
            page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Operational events")).click();
            for(String theme:new String[]{"light","dark"}) {
                if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}
                page.getByLabel("Search events").fill(prefix);page.getByLabel("Severity",new Page.GetByLabelOptions().setExact(true)).selectOption("warning");
                page.getByLabel("Event state").selectOption("open");page.getByLabel("Events per page").fill("1");
                page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Find events")).press("Enter");
                assertThat(page.locator("article")).hasCount(1);assertThat(page.locator("main")).containsText("3 matching events");
                String first=page.locator("article h3").innerText();
                page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Next events")).click();assertNotEquals(first,page.locator("article h3").innerText());
                page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Previous events")).press("Enter");assertEquals(first,page.locator("article h3").innerText());
                ReleaseBrowserAcceptance.accessible(page);
                page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/event-search-"+theme+".png")).setFullPage(true));
            }
            String key=page.locator("article h3").innerText();
            page.getByLabel("Response reason").fill("Investigating the event");
            page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Acknowledge event")).click();
            page.getByLabel("Search events").fill(key);page.getByLabel("Event state").selectOption("acknowledged");
            page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Find events")).click();assertThat(page.locator("article")).hasCount(1);
            page.getByLabel("Response reason").fill("Issue resolved");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Resolve event")).click();
            page.getByLabel("Search events").fill(key);page.getByLabel("Event state").selectOption("resolved");
            page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Find events")).click();assertThat(page.locator("article")).hasCount(1);
            page.getByLabel("Response reason").fill("Issue returned");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Reopen event")).click();
            page.getByLabel("Search events").fill(prefix+"missing");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Find events")).click();
            assertThat(page.getByText("No events match these filters.")).isVisible();
        }
    }
}
