package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class MigrationStopsBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {String id;try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);id=TransferJobs.create(db,TransferValidationAcceptance.basic(),actor,h.store.migrationOrigins).get("job_id").toString();db.connection.commit();}
  try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
   var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/admin/migrations");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review and cancel unfinished migrations")).click();page.locator("article").filter(new Locator.FilterOptions().setHasText(id)).getByRole(AriaRole.LINK,new Locator.GetByRoleOptions().setName("Review cancellation")).click();
   for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/migration-stop-"+theme+".png")).setFullPage(true));}
   page.getByLabel("Cancellation reason").fill("Evaluation paused <script>");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Cancel this migration")).press("Enter");assertThat(page.locator("main")).containsText("Migration state: cancelled");assertThat(page.locator("main")).containsText("Evaluation paused <script>");assertThat(page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Cancel this migration"))).hasCount(0);ReleaseBrowserAcceptance.accessible(page);page.reload();assertThat(page.locator("main")).containsText("Cancellation recorded");
  }
 }
}
