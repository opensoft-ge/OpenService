package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class WorkflowInsightsBrowserAcceptance {
 static void run(BrowserTest h)throws Exception {
  var f=WorkflowInsightsAcceptance.seed(h.store,java.time.Instant.now().getEpochSecond());String old=f.get("old").toString(),query="sys_idIN"+String.join(",",f.values().stream().map(Object::toString).toList());
  try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
   var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/workspace/incident");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Investigate workflow delays").setExact(true)).press("Enter");page.getByLabel("Record query").fill(query);page.getByLabel("Age threshold in hours").fill("48");ServiceOutagesBrowserAcceptance.click(page,"Analyze current delays");assertThat(page.locator("[data-record]")).hasCount(3);double displayedAge=Double.parseDouble(page.locator("[data-record='"+old+"'] td").nth(3).textContent());assertTrue(displayedAge>=72 && displayedAge<72.1);assertThat(page.locator("[data-record='"+f.get("unknown")+"']")).containsText("Unknown");assertThat(page.locator("[data-record='"+f.get("terminal")+"']")).hasCount(0);assertEquals(0,page.locator("main script").count());ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/workflow-delays-light.png")).setFullPage(true));ServiceOutagesBrowserAcceptance.click(page,"Dark mode");page.setViewportSize(390,844);ReleaseBrowserAcceptance.accessible(page);assertEquals(false,page.evaluate("document.documentElement.scrollWidth>window.innerWidth"));page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/workflow-delays-dark.png")).setFullPage(true));
   page.locator("[data-record='"+old+"'] a").press("Enter");page.locator("select[name=state]").selectOption("2");ServiceOutagesBrowserAcceptance.click(page,"Save changes →");page.navigate(h.base()+"/workspace/incident/insights");page.getByLabel("Record query").fill("sys_id="+old);ServiceOutagesBrowserAcceptance.click(page,"Analyze current delays");assertThat(page.locator("[data-record]")).hasCount(1);assertThat(page.locator("[data-record]")).containsText("In progress");assertThat(page.locator("[data-record]")).containsText("Below");ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/workflow-delays-refreshed-dark.png")).setFullPage(true));page.getByLabel("Record query").fill("short_description=No matching incident");ServiceOutagesBrowserAcceptance.click(page,"Analyze current delays");assertThat(page.locator("main")).containsText("No active workflow records match.");assertThat(page.locator("[data-record]")).hasCount(0);
  }
 }
}
