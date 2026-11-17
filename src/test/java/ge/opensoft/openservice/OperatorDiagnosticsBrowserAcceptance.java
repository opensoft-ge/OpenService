package ge.opensoft.openservice;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import com.microsoft.playwright.*;import com.microsoft.playwright.options.AriaRole;import java.nio.file.Path;import java.nio.file.Files;
final class OperatorDiagnosticsBrowserAcceptance {
 static void run(BrowserTest h)throws Exception{
  try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
   var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/admin/operations");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review diagnostic snapshot")).press("Enter");assertThat(page.locator("main")).containsText("Current diagnostic snapshot");assertThat(page.locator("main")).containsText("Installed / expected schema");
   for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.checkAccessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/operator-diagnostics-"+theme+".png")).setFullPage(true));}
   var download=page.waitForDownload(()->page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Download diagnostic JSON")).press("Enter"));assertEquals("openservice-diagnostics.json",download.suggestedFilename());assertTrue(Files.readString(download.path()).contains("openservice-diagnostics-v1"));assertFalse(Files.readString(download.path()).contains("envelope"));page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review retained jobs and recovery")).press("Enter");assertThat(page.locator("main")).containsText("Find background work");page.goBack();page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review saved migrations")).press("Enter");assertThat(page.locator("main")).containsText("Saved migrations");
  }
 }
}
