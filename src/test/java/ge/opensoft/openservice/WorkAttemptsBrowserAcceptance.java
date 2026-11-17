package ge.opensoft.openservice;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class WorkAttemptsBrowserAcceptance {
    static void run(BrowserTest h)throws Exception {
        String job=WorkSearchAcceptance.seed(h.store,"Browser history "+Values.id());try(var db=h.store.open()){db.update("UPDATE os_work SET created_at=? WHERE job_id=?",Long.MIN_VALUE,job);db.connection.commit();}
        assertTrue(new WorkWorker(h.store).runOne(System.currentTimeMillis()+100));
        try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
            var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/admin/jobs/"+job);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("View attempt history")).press("Enter");
            assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Job attempt history"))).isVisible();assertThat(page.locator("tbody")).containsText("succeeded");assertThat(page.locator("main")).not().containsText("PAYLOAD SECRET");
            for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.checkAccessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/work-attempts-"+theme+".png")).setFullPage(true));}
            page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Back to job details")).press("Enter");assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Background work details"))).isVisible();
        }
    }
}
