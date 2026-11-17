package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class MigrationFetchBrowserAcceptance {
    static void run(BrowserTest h)throws Exception{
        MigrationFetchAcceptance.source(2);String migration,job;try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);migration=TransferJobs.create(db,TransferValidationAcceptance.basic(),actor,h.store.migrationOrigins).get("job_id").toString();db.connection.commit();}
        try(var p=Playwright.create();var browser=p.chromium().launch()){
            try(var context=browser.newContext()){
                var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/admin/migrations");page.locator("#migration-jobs").selectOption(migration);assertThat(page.locator("#migration-reconciliation")).hasAttribute("href","/admin/migrations/"+migration+"/reconciliation");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review reconciliation")).press("Enter");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Fetch remaining data in the background")).press("Enter");assertThat(page.locator("main")).containsText("background worker is disabled");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Back to migration controls")).press("Enter");assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Review migration cancellation"))).isVisible();page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Fetch remaining data in the background")).press("Enter");
                for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.checkAccessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/migration-fetch-"+theme+".png")).setFullPage(true));}
                page.getByLabel("Source OAuth bearer token").fill(MigrationSource.TOKEN);page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Queue remaining pages")).press("Enter");assertThat(page.locator("main")).containsText("queued");assertThat(page.locator("main")).not().containsText(MigrationSource.TOKEN);job=page.url().substring(page.url().lastIndexOf('/')+1);
            }
            try(var db=h.store.open()){db.update("UPDATE os_work SET created_at=? WHERE job_id=?",Long.MIN_VALUE,job);db.connection.commit();}
            long now=System.currentTimeMillis()+100;assertTrue(new WorkWorker(h.store).runOne(now));assertTrue(new WorkWorker(new Store(h.store.environment)).runOne(now+10000));
            try(var context=browser.newContext()){
                var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/admin/jobs/"+job);assertThat(page.locator("main")).containsText("succeeded");assertThat(page.locator("main")).containsText("review");assertThat(page.locator("main")).not().containsText("Staged private data");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("View attempt history")).press("Enter");assertThat(page.locator("tbody")).containsText("continued");assertThat(page.locator("tbody")).containsText("succeeded");ReleaseBrowserAcceptance.checkAccessible(page);
            }
        }finally{MigrationSource.reset();}
    }
}
