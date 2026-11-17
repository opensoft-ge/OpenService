package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.*;
final class ReportExportBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {String table=ReportFormsAcceptance.seed(h.store),report,reader="ExportBrowser"+id().substring(0,8);try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);Service.createAccount(db,map("name",reader,"role","reader","password",WorkflowSupport.PASSWORD));report=SavedReports.save(db,actor,map("name","Download measured work","table",table,"query",map("field","amount"),"shared",true)).get("report_id").toString();db.connection.commit();}
 try(var p=Playwright.create();var b=p.chromium().launch();var c=b.newContext()){var page=c.newPage();h.login(page,reader);page.navigate(h.base()+"/reports/"+report);var link=page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Download CSV").setExact(true));assertThat(link).isVisible();var download=page.waitForDownload(()->link.press("Enter"));assertEquals("report-"+report+".csv",download.suggestedFilename());assertNull(download.failure());var rows=ReportExportAcceptance.rows(Files.readString(download.path()));assertEquals("10",rows.getFirst().get("sum"));for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/report-export-"+theme+".png")).setFullPage(true));}}
 }
}
