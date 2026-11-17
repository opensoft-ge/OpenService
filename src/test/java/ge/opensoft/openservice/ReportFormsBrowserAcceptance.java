package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class ReportFormsBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {
  String table=ReportFormsAcceptance.seed(h.store),name="ReportBrowser"+id().substring(0,8),viewer="ReportReader"+id().substring(0,8);
  try(var db=h.store.open()){Service.createAccount(db,map("name",viewer,"role","reader","password",WorkflowSupport.PASSWORD));db.connection.commit();}
  try(var p=Playwright.create();var b=p.chromium().launch();var c=b.newContext();var other=b.newContext()){
   var page=c.newPage();h.login(page,"admin");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Saved reports").setExact(true)).click();
   ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/report-library-light.png")).setFullPage(true));
   page.getByLabel("Report name").fill(name);page.getByLabel("Record type").fill(table);page.getByLabel("Numeric field").fill("amount");page.getByLabel("Report visibility").selectOption("true");
   page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save report").setExact(true)).press("Enter");String url=page.url();
   assertThat(page.locator("tbody tr td").nth(3)).hasText("100");
   for(String theme:new String[]{"light","dark"}){
    if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}
    if(theme.equals("dark")){page.getByRole(AriaRole.REGION,new Page.GetByRoleOptions().setName("Saved report results")).press("ArrowRight");page.waitForCondition(()->((Number)page.locator(".table-scroll").evaluate("el=>el.scrollLeft")).doubleValue()>0);}
    ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/saved-report-"+theme+".png")).setFullPage(true));
   }
   var reader=other.newPage();h.login(reader,viewer);reader.navigate(url);assertThat(reader.locator("tbody tr td").nth(3)).hasText("10");assertThat(reader.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save report"))).hasCount(0);
   page.getByLabel("Report name").fill(name+" revised");page.getByLabel("Maximum value").fill("50");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save report").setExact(true)).click();
   assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setLevel(1))).hasText(name+" revised");assertThat(page.locator("tbody tr td").nth(3)).hasText("10");page.reload();assertThat(page.getByLabel("Maximum value")).hasValue("50");
   try(var db=h.store.open()){var schema=Service.schema(db,table);object(object(schema.get("field_rules")).get("amount")).put("type","string");db.update("UPDATE os_schema SET definition="+db.jsonParameter()+" WHERE name=?",JSON.writeValueAsString(schema),table);db.connection.commit();}
   page.reload();assertThat(page.getByRole(AriaRole.ALERT)).containsText("Results could not be calculated");
   ReleaseBrowserAcceptance.accessible(page);
   page.getByLabel("Numeric field").fill("quantity");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save report").setExact(true)).click();assertThat(page.getByRole(AriaRole.ALERT)).hasCount(0);
   page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Delete report")).click();assertThat(page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName(name+" revised").setExact(true))).hasCount(0);ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/report-library-dark.png")).setFullPage(true));
  }
 }
}
