package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import com.microsoft.playwright.*;import com.microsoft.playwright.options.*;
import java.nio.file.Path;
final class AttachmentScanningBrowserAcceptance {
    static void run(BrowserTest h)throws Exception{
        String record;try(var db=h.store.open()){PlatformFoundationTest.bind(db);record=PlatformFoundationTest.record(db,"incident","Scan supporting evidence");db.connection.commit();}
        try(var server=new ClamScannerTest.Server(ClamScannerTest.VERSION+"\0","stream: OK\0");var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
            AttachmentScanningAcceptance.enabled(h.store.environment,true,server.port());var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/record/incident/"+record);page.locator("input[type=file]").setInputFiles(new FilePayload("scan-browser.txt","text/plain","Browser synthetic content".getBytes()));page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Upload").setExact(true)).press("Enter");assertThat(page.locator("main")).containsText("Held for review");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review attachment access")).press("Enter");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review malware scan")).press("Enter");assertThat(page.locator("main")).containsText("pending");String id;try(var db=h.store.open()){id=db.one("SELECT sys_id FROM os_attachment WHERE record_id=?",record).get("sys_id").toString();}assertEquals(423,context.request().get(h.base()+"/attachment/"+id).status());
            // Rescanning already-held content preserves its hold even after a clean result.
            page.getByLabel("Reason for scanning").fill("Review before releasing this file");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Queue attachment scan")).press("Enter");assertThat(page.locator("main")).containsText("pending");
            var environment=new org.springframework.mock.env.MockEnvironment();environment.merge((org.springframework.core.env.ConfigurableEnvironment)h.store.environment);environment.setProperty("WORK_ENABLED","true");new WorkWorker(new Store(environment)).tick();page.reload();assertThat(page.locator("main")).containsText("clean");assertThat(page.locator("main")).containsText(ClamScannerTest.VERSION);assertEquals(423,context.request().get(h.base()+"/attachment/"+id).status());
            for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.checkAccessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/attachment-scan-"+theme+".png")).setFullPage(true));}
            page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review download access")).press("Enter");page.getByLabel("Download access").selectOption("false");page.getByLabel("Review reason").fill("Current clean receipt reviewed");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save attachment access")).press("Enter");assertThat(page.locator("main")).containsText("Available");assertEquals("Browser synthetic content",context.request().get(h.base()+"/attachment/"+id).text());
        }finally{AttachmentScanningAcceptance.reset(h.store.environment);}
    }
}
