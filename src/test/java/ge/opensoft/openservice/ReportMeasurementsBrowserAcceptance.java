package ge.opensoft.openservice;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static ge.opensoft.openservice.Values.*;
import com.microsoft.playwright.*;import com.microsoft.playwright.options.AriaRole;import java.nio.file.Path;
final class ReportMeasurementsBrowserAcceptance {
 static void run(BrowserTest h)throws Exception{
  String table=ReportFormsAcceptance.seed(h.store),id=ReportMeasurementsAcceptance.save(h.store,table,map("field","amount"),false);
  try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
   var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/reports/"+id);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Measurements over time")).press("Enter");assertThat(page.locator("main")).containsText("No captures");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Capture current measurement")).press("Enter");page.reload();assertThat(page.locator("tbody tr")).hasCount(1);ReportMeasurementsAcceptance.change(h.store,table,"30");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Capture current measurement")).press("Enter");assertThat(page.locator("tbody tr")).hasCount(2);page.getByLabel("Metric",new Page.GetByLabelOptions().setExact(true)).selectOption("sum");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Compare metric")).press("Enter");assertThat(page.locator("thead")).containsText("sum");assertThat(page.locator("tbody tr").first()).containsText("100");
   for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.checkAccessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/report-measurements-"+theme+".png")).setFullPage(true));}
   page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Capture current measurement")).press("Enter");assertFalse(page.url().contains("?"));assertThat(page.locator("tbody tr")).hasCount(3);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Back to current report")).press("Enter");assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Results").setExact(true))).isVisible();
  }
 }
}
