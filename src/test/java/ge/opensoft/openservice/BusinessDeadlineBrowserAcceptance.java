package ge.opensoft.openservice;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
import java.util.List;
final class BusinessDeadlineBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {
  String name="preview"+Values.id();BusinessDeadlineAcceptance.seed(h.store,name,BusinessDeadlineAcceptance.calendar("UTC","09:00","17:00",List.of(1,2,3,4,5),List.of("2026-11-23")));
  try(var p=Playwright.create();var b=p.chromium().launch();var c=b.newContext()){
   var page=c.newPage();h.login(page,"admin");page.navigate(h.base()+"/admin/operations");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Preview a service deadline")).press("Enter");
   for(String theme:List.of("light","dark")){
    if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}
    page.getByLabel("Business calendar").selectOption(name);page.getByLabel("Start instant").fill("2026-11-20T16:30:00Z");page.getByLabel("Business minutes",new Page.GetByLabelOptions().setExact(true)).fill("120");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Calculate deadline")).press("Enter");
    assertThat(page.locator("main")).containsText("2026-11-24T10:30:00Z");ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/deadline-preview-"+theme+".png")).setFullPage(true));
   }
  }
 }
}
