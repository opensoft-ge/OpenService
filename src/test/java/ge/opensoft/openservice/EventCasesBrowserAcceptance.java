package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class EventCasesBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {String key="event-browser-"+id();try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db),asset=Service.create(db,"cmdb_ci",map("short_description","Event browser asset"),"admin").get("sys_id").toString();AssetOperations.event(db,actor,map("key",key,"asset_id",asset,"severity","warning","summary","Literal <script> "+"x".repeat(300)),1);db.connection.commit();}
  try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
   var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/admin/events?q="+key);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review event case")).click();
   for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/event-case-"+theme+".png")).setFullPage(true));}
   page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Create private incident")).press("Enter");assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Operational event investigation").setExact(true))).isVisible();assertThat(page.getByLabel("Visibility",new Page.GetByLabelOptions().setExact(true))).hasValue("private");String caseUrl=page.url();
   page.navigate(h.base()+"/admin/events?q="+key);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review event case")).click();assertThat(page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Create private incident"))).hasCount(0);ReleaseBrowserAcceptance.accessible(page);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Open linked case")).press("Enter");assertEquals(caseUrl,page.url());
  }
 }
}
