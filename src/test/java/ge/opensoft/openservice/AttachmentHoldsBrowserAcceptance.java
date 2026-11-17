package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class AttachmentHoldsBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {
  String record,id=id();try(var db=h.store.open()){PlatformFoundationTest.bind(db);record=PlatformFoundationTest.record(db,"incident","Review a supporting file");db.update("INSERT INTO os_attachment(sys_id,record_id,name,content,content_type) VALUES(?,?,?,?,?)",id,record,"review.txt","synthetic-file".getBytes(),"text/plain");db.connection.commit();}
  try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
   var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/record/incident/"+record);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review attachment access")).press("Enter");page.getByLabel("Download access").selectOption("true");page.getByLabel("Review reason").fill("Hold during investigation");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save attachment access")).press("Enter");assertThat(page.locator("main")).containsText("On hold");
   for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/attachment-hold-"+theme+".png")).setFullPage(true));}
   assertEquals(423,context.request().get(h.base()+"/attachment/"+id).status());page.getByLabel("Download access").selectOption("false");page.getByLabel("Review reason").fill("Review completed");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save attachment access")).press("Enter");assertThat(page.locator("main")).containsText("Available");assertEquals("synthetic-file",context.request().get(h.base()+"/attachment/"+id).text());
  }
 }
}
