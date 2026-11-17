package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class ClockPurposesBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {String record,calendar="Purpose"+id();try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);record=PlatformFoundationTest.record(db,"incident","Separate response and resolution");ServiceClocks.calendar(db,actor,map("name",calendar,"calendar",PlatformFoundationTest.calendar()));db.connection.commit();}
  try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
   var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/record/incident/"+record+"/clocks");for(String purpose:new String[]{"response","resolution"}){page.getByLabel("Business calendar",new Page.GetByLabelOptions().setExact(true)).selectOption(calendar);page.getByLabel("Clock purpose").selectOption(purpose);page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Start service clock")).press("Enter");}
   var response=page.locator("tbody tr").filter(new Locator.FilterOptions().setHasText("response"));response.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Complete").setExact(true)).press("Enter");assertThat(response).containsText("completed");assertThat(page.locator("tbody tr").filter(new Locator.FilterOptions().setHasText("resolution"))).containsText("running");
   for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/clock-purposes-"+theme+".png")).setFullPage(true));}page.reload();assertThat(response).containsText("completed");
  }
 }
}
