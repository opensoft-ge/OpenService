package ge.opensoft.openservice;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class SQLiteContentionBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {
  try(var p=Playwright.create();var b=p.chromium().launch();var c=b.newContext()){
   var page=c.newPage();h.login(page,"admin");
   for(String theme:new String[]{"light","dark"}){
    if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}
    try(var holder=h.store.open()){
     assertEquals(503,page.navigate(h.base()+"/account/profile").status());assertThat(page.locator("main")).containsText("This request did not start");
     assertTrue((Boolean)page.evaluate("document.styleSheets.length > 0 && getComputedStyle(document.querySelector('main')).display !== 'inline'"));
     ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/sqlite-busy-"+theme+".png")).setFullPage(true));
    }
    page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Return to workspace").setExact(false)).press("Enter");assertThat(page.locator("main")).not().containsText("Service Unavailable");
   }
  }
 }
}
