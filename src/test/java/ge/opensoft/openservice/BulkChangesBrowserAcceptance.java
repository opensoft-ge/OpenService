package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
import java.util.*;
final class BulkChangesBrowserAcceptance {
 static void click(Page page,String name){ServiceOutagesBrowserAcceptance.click(page,name);}
 static void run(BrowserTest h)throws Exception{
  var ids=new ArrayList<String>();String suffix=id().substring(0,8);try(var db=h.store.open()){PlatformFoundationTest.bind(db);for(int n=0;n<2;n++)ids.add(Service.create(db,"problem",map("short_description","Bulk browser "+suffix+" "+n),"admin").get("sys_id").toString());db.connection.commit();}
  try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
   var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/workspace/problem");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review bulk record changes")).press("Enter");for(String id:ids)page.locator("input[name=selected_"+id+"]").check();page.getByLabel("Field changes (JSON)").fill("{\"description\":\"Reviewed together <script>plain text</script>\"}");click(page,"Review selected changes");assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Review exact record changes"))).isVisible();assertThat(page.locator("main script")).hasCount(0);for(String id:ids)assertThat(page.locator("input[name=selected_"+id+"]")).isChecked();try(var db=h.store.open()){for(String id:ids)assertEquals(0,((Number)Service.readRecord(db,"problem",id).get("revision")).intValue());}ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/bulk-changes-review-light.png")).setFullPage(true));click(page,"Dark mode");page.setViewportSize(390,844);ReleaseBrowserAcceptance.accessible(page);assertEquals(false,page.evaluate("document.documentElement.scrollWidth>window.innerWidth"));assertEquals(true,page.evaluate("Array.from(document.querySelectorAll('main .panel')).every(e=>e.scrollWidth<=e.clientWidth)"));page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/bulk-changes-review-dark.png")).setFullPage(true));page.getByLabel("Apply these exact changes to all selected records").check();click(page,"Apply reviewed changes");assertThat(page.locator("[role=status]")).containsText("2 records updated together");assertThat(page.locator("main script")).hasCount(0);ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/bulk-changes-receipt-dark.png")).setFullPage(true));page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review receipt").setExact(false)).first().press("Enter");assertThat(page.locator("[role=status]")).containsText("2 records updated together");
   page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Open current record")).first().press("Enter");assertThat(page.locator("main")).containsText("Reviewed together <script>plain text</script>");try(var db=h.store.open()){for(String id:ids)assertEquals(1,((Number)Service.readRecord(db,"problem",id).get("revision")).intValue());}
  }
 }
}
