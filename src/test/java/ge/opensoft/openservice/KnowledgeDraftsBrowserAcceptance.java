package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class KnowledgeDraftsBrowserAcceptance {
    static void run(BrowserTest h)throws Exception{
        String id=KnowledgeDraftsAcceptance.seed(h.store);
        try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
            var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/admin/knowledge/"+id+"/versions");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review recovery into draft")).first().press("Enter");assertThat(page.locator("main")).containsText("Unpublished work");assertThat(page.locator("main")).containsText("Original instructions <script>");assertThat(page.locator("main")).containsText("Current instructions");assertEquals(0,page.locator("main script").count());
            for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.checkAccessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/knowledge-draft-"+theme+".png")).setFullPage(true));}
            page.getByLabel("Confirm replacing the current draft title and body with this source version").check();page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Recover into draft")).press("Enter");assertThat(page.locator("textarea[name=text]")).hasValue("Original instructions <script>");page.locator("textarea[name=text]").fill("Reviewed recovery for readers");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save changes →")).click();assertThat(page.locator("textarea[name=text]")).hasValue("Reviewed recovery for readers");
            try(var db=h.store.open()){assertEquals("Current instructions",db.one("SELECT content FROM os_publication WHERE article_id=? ORDER BY version DESC LIMIT 1",id).get("content"));}
            page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review draft for publication")).press("Enter");assertThat(page.locator("main")).containsText("Reviewed recovery for readers");assertThat(page.locator("main")).containsText("Current instructions");page.getByLabel("Confirm publishing this reviewed draft to workspace readers").check();page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Publish reviewed draft")).press("Enter");assertThat(page.locator("main")).containsText("Version 3");assertThat(page.locator("main")).containsText("Reviewed recovery for readers");ReleaseBrowserAcceptance.checkAccessible(page);
        }
    }
}
