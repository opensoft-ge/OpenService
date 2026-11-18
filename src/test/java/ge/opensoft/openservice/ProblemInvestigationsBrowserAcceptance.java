package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class ProblemInvestigationsBrowserAcceptance {
 static void run(BrowserTest h)throws Exception{String id;try(var db=h.store.open()){PlatformFoundationTest.bind(db);id=Service.create(db,"incident",map("short_description","Repeated outage"),"admin").get("sys_id").toString();db.connection.commit();}
  try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
   var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/record/incident/"+id);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Investigate related incidents")).press("Enter");page.getByLabel("Problem summary").fill("Find outage cause");page.getByLabel("Investigation details").fill("Repeated <script> evidence");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Create linked problem")).press("Enter");page.reload();assertThat(page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Open problem investigation"))).hasCount(1);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Open problem investigation")).press("Enter");
   page.getByLabel("Root cause").fill("Literal <script> cause");page.getByLabel("Workaround",new Page.GetByLabelOptions().setExact(true)).fill("Restart after reviewing active work");page.getByLabel("Sharing state").selectOption("true");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save investigation")).press("Enter");page.reload();assertThat(page.getByLabel("Root cause")).hasValue("Literal <script> cause");assertThat(page.locator("main script")).hasCount(0);
   for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/problem-investigation-"+theme+".png")).setFullPage(true));}
   page.locator("main li a").press("Enter");assertThat(page.locator("main")).containsText("Restart after reviewing active work");ReleaseBrowserAcceptance.accessible(page);page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Unlink problem")).press("Enter");assertThat(page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Create linked problem"))).isVisible();page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Find an existing problem")).press("Enter");page.getByLabel("Search problem summaries").fill("Find outage cause");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Find problems")).press("Enter");ReleaseBrowserAcceptance.accessible(page);page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Link problem: Find outage cause")).press("Enter");assertThat(page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Open problem investigation"))).isVisible();
  }
 }
}
