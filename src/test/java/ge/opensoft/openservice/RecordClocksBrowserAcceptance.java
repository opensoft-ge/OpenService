package ge.opensoft.openservice;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static ge.opensoft.openservice.Values.*;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class RecordClocksBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {
  String record,calendar="Clock"+id();try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);record=PlatformFoundationTest.record(db,"incident","Track response work");ServiceClocks.calendar(db,actor,map("name",calendar,"calendar",PlatformFoundationTest.calendar()));db.connection.commit();}
  try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
   var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/record/incident/"+record);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("View service clocks")).press("Enter");
   page.getByLabel("Business calendar",new Page.GetByLabelOptions().setExact(true)).selectOption(calendar);page.getByLabel("Business-minute budget").fill("120");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Start service clock")).press("Enter");assertThat(page.locator("tbody")).containsText("running");
   for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/record-clocks-"+theme+".png")).setFullPage(true));}
   page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Pause").setExact(true)).press("Enter");assertThat(page.locator("tbody")).containsText("paused");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Resume").setExact(true)).press("Enter");assertThat(page.locator("tbody")).containsText("running");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Complete").setExact(true)).press("Enter");assertThat(page.locator("tbody")).containsText("completed");
  }
 }
}
