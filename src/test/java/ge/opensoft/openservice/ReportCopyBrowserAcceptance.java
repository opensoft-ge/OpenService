package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class ReportCopyBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {String table=ReportFormsAcceptance.seed(h.store),source;try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);source=SavedReports.save(db,actor,map("name","Copyable report","table",table,"query",map("field","amount"),"shared",true)).get("report_id").toString();db.connection.commit();}try(var p=Playwright.create();var b=p.chromium().launch();var c=b.newContext()){var page=c.newPage();h.login(page,"admin");page.navigate(h.base()+"/reports/"+source);page.getByLabel("Name for your copy").fill("My independent report");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Create private copy")).press("Enter");assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("My independent report").setExact(true))).isVisible();assertThat(page.locator("select[name=shared]")).hasValue("false");for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/report-copy-"+theme+".png")).setFullPage(true));}}}
}
