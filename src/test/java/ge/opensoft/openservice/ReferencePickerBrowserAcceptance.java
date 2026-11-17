package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class ReferencePickerBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {
  var data=ReferencePickerAcceptance.seed(h.store);
  try(var p=Playwright.create();var b=p.chromium().launch();var c=b.newContext()){
   var page=c.newPage();h.login(page,"admin");page.navigate(h.base()+"/record/"+data.get("table")+"/"+data.get("source"));page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Choose Related case")).press("Enter");
   for(String theme:new String[]{"light","dark"}){
    if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}
    page.getByLabel("Search reference summaries").fill("100%");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Find references")).click();assertThat(page.locator("main")).containsText("1 matching records");
    ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/reference-picker-"+theme+".png")).setFullPage(true));
   }
   page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Select record: Target 100% <script>")).press("Enter");assertThat(page.locator("input[name=related]")).hasValue(data.get("target"));
   page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Choose Related case")).click();page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Clear reference")).click();assertThat(page.locator("input[name=related]")).hasValue("");
  }
 }
}
