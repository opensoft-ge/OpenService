package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class RecordQuestionsBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {
  String id;try(var db=h.store.open()){PlatformFoundationTest.bind(db);id=Service.create(db,"incident",map("short_description","Question source","description","Synthetic approved evidence"),"admin").get("sys_id").toString();db.connection.commit();}
  try(var provider=new RecordQuestionsAcceptance.Provider(h.store,h.environment);var p=Playwright.create();var b=p.chromium().launch();var c=b.newContext()){
   var page=c.newPage();h.login(page,"admin");page.navigate(h.base()+"/record/incident/"+id);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Ask about this record")).press("Enter");page.getByLabel("Your question").fill("What does the source say?");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Preview question")).click();assertEquals(0,provider.calls.get());assertThat(page.locator("main")).containsText("Synthetic approved evidence");org.junit.jupiter.api.Assertions.assertTrue(page.getByLabel("I approve sending this question and the selected fields").boundingBox().width<40);ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/record-assistant-light.png")).setFullPage(true));
   page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Cancel and start again")).click();assertEquals(0,provider.calls.get());page.getByLabel("Your question").fill("What does the source say?");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Preview question")).click();page.getByLabel("I approve sending this question and the selected fields").check();page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Send question")).press("Enter");assertEquals(1,provider.calls.get());assertThat(page.locator("main")).containsText("Evidence <script>not executed</script>");assertThat(page.locator("main script")).hasCount(0);page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/record-assistant-dark.png")).setFullPage(true));
  }
 }
}
