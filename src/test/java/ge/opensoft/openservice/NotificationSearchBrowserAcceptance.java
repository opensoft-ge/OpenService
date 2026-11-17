package ge.opensoft.openservice;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;

final class NotificationSearchBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String user="NoticeBrowser"+id().substring(0,8);
        try(var db=h.store.open()) {
            Service.createAccount(db,map("name",user,"role","agent","password",WorkflowSupport.PASSWORD));
            for(int i=0;i<3;i++)Notifications.deliver(db,id(),map("username",user,"message",user+" message "+i+" <script>alert(1)</script>"),i);
            db.connection.commit();
        }
        try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()) {
            var page=context.newPage();h.login(page,user);page.navigate(h.base()+"/account/notifications");
            for(String theme:new String[]{"light","dark"}) {
                if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}
                page.getByLabel("Search messages").fill(user);page.getByLabel("Message state").selectOption("unread");page.getByLabel("Messages per page").fill("1");
                page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Find messages")).press("Enter");
                assertThat(page.locator("article")).hasCount(1);assertThat(page.locator("main")).containsText("3 matching messages");
                String first=page.locator("article p").innerText();
                page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Next messages")).click();assertNotEquals(first,page.locator("article p").innerText());
                page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Previous messages")).press("Enter");assertEquals(first,page.locator("article p").innerText());
                ReleaseBrowserAcceptance.accessible(page);
                page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/notification-search-"+theme+".png")).setFullPage(true));
            }
            page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Acknowledge").setExact(true)).click();
            assertThat(page.getByLabel("Message state")).hasValue("unread");
            assertThat(page.locator("main")).containsText("2 matching messages");
            page.getByLabel("Search messages").fill(user);page.getByLabel("Message state").selectOption("acknowledged");
            page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Find messages")).click();assertThat(page.locator("article")).hasCount(1);
            page.getByLabel("Business notifications").selectOption("false");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save preference")).click();
            assertThat(page.getByLabel("Business notifications")).hasValue("false");
            page.getByLabel("Search messages").fill("missing");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Find messages")).click();
            assertThat(page.getByText("No messages match these filters.")).isVisible();
        }
    }
}
