package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class CreationPreviewBrowserAcceptance {
    static void run(BrowserTest h)throws Exception{
        String target,name="PreviewUser"+id().substring(0,8);try(var db=h.store.open()){Service.createAccount(db,map("name",name,"password",WorkflowSupport.PASSWORD,"role","requester"));target=RecordAccess.owner(db,name);db.connection.commit();}
        var before=CreationPreviewAcceptance.retained(h.store);
        try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
            var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/admin/accounts/"+target+"/permissions");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Check proposed creation")).press("Enter");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Check creation").setExact(true)).press("Enter");assertThat(page.locator("main")).containsText("Blocked by current checks");
            page.getByLabel("Creation journey").selectOption("request");page.getByLabel("Table name").fill("sc_request");page.getByLabel("Proposed field values (JSON)").fill("{\"short_description\":\"Review <script>work</script>\"}");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Check creation").setExact(true)).press("Enter");assertThat(page.locator("main")).containsText("Allowed by current checks");assertThat(page.locator("main script")).hasCount(0);
            for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.checkAccessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/creation-preview-"+theme+".png")).setFullPage(true));}
            page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Back to permission diagnosis")).press("Enter");assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Account permission diagnosis"))).isVisible();
        }
        // Browser login may add authentication evidence; business data and numbering must not move.
        var after=CreationPreviewAcceptance.retained(h.store);before.remove("os_security_event");after.remove("os_security_event");assertEquals(before,after);
    }
}
