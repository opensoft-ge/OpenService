package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class RecordScopesBrowserAcceptance {
 static void run(BrowserTest h)throws Exception{
  String child,parent;try(var db=h.store.open()){String admin=PlatformFoundationTest.bind(db);Service.createAccount(db,map("name","ScopeBrowserAgent","role","agent","password",WorkflowSupport.PASSWORD));parent=Service.create(db,"problem",map("short_description","Private review <script> case"),"admin").get("sys_id").toString();child=Service.create(db,"incident",map("short_description","Protected follow-up"),"admin").get("sys_id").toString();RecordAccess.change(db,Service.record(db,"problem",parent),admin,map("visibility","private","owner_id",admin));db.connection.commit();}
  try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext();var staffContext=browser.newContext()){
   var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/record/incident/"+child);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Manage parent access boundary")).press("Enter");page.getByLabel("Parent table").selectOption("problem");page.getByLabel("Search parent summaries").fill("Private review");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Find parents")).press("Enter");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Use parent: Private review <script> case")).press("Enter");page.reload();assertThat(page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Private review <script> case").setExact(true))).isVisible();assertThat(page.locator("main script")).hasCount(0);
   var staff=staffContext.newPage();h.login(staff,"ScopeBrowserAgent");assertEquals(404,staff.navigate(h.base()+"/record/incident/"+child).status());for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/record-scope-"+theme+".png")).setFullPage(true));}
   page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Detach parent boundary")).press("Enter");page.reload();assertThat(page.locator("main")).containsText("no additional parent boundary");assertEquals(200,staff.navigate(h.base()+"/record/incident/"+child).status());ReleaseBrowserAcceptance.accessible(page);
  }
 }
}
