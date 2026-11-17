package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
import java.util.*;
final class MigrationConflictsBrowserAcceptance {
    static void fetch(BrowserTest h,Store.Db db,String job)throws Exception{int pages=0;while(object(TransferJobs.job(db,job).get("detail")).get("status").equals("fetching")){TransferJobs.step(db,job,MigrationSource.TOKEN,h.store.migrationOrigins);assertTrue(++pages<10);}}
    static void run(BrowserTest h)throws Exception{
        MigrationSource.reset();String sid=id(),job;var source=map("sys_id",sid,"short_description","Original source <script>","state","1","priority","3");MigrationSource.tables.put("incident",List.of(source));
        try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);String first=TransferJobs.create(db,TransferValidationAcceptance.basic(),actor,h.store.migrationOrigins).get("job_id").toString();fetch(h,db,first);TransferApply.apply(db,first,actor,false);Service.update(db,"incident",Service.record(db,"incident",sid),map("short_description","Local browser version"),"admin","admin",null);source.put("short_description","New source <script>");job=TransferJobs.synchronize(db,first,actor,h.store.migrationOrigins).get("job_id").toString();fetch(h,db,job);db.connection.commit();}
        try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
            var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/admin/migrations/"+job+"/reconciliation");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review record conflict decisions")).press("Enter");ReleaseBrowserAcceptance.checkAccessible(page);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review this record conflict")).press("Enter");assertThat(page.locator("tbody")).containsText("Original source <script>");assertThat(page.locator("tbody")).containsText("Local browser version");assertThat(page.locator("tbody")).containsText("New source <script>");assertEquals(0,page.locator("tbody script").count());
            for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.checkAccessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/migration-conflict-"+theme+".png")).setFullPage(true));}
            page.getByLabel("Version to retain").selectOption("use_source");page.getByLabel("Decision reason").fill("Source reviewed <script>");page.getByLabel("Confirm this reviewed conflict decision").check();page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save conflict decision")).press("Enter");assertThat(page.locator("main")).containsText("Saved decision: use_source");assertThat(page.locator("main")).containsText("Source reviewed <script>");assertEquals(0,page.locator("main script").count());ReleaseBrowserAcceptance.checkAccessible(page);
            page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Return to Migration Studio to preview and apply")).press("Enter");assertThat(page.locator("#migration-jobs")).isEnabled();page.locator("#migration-jobs").selectOption(job);assertThat(page.locator("#migration-jobs")).isEnabled();page.locator("#migration-preview").click();assertThat(page.locator("#migration-report")).containsText("\"preview\": true");try(var db=h.store.open()){PlatformFoundationTest.bind(db);assertEquals("Local browser version",object(Service.record(db,"incident",sid).get("data")).get("short_description"));}
            page.locator("#migration-apply").click();assertThat(page.locator("#migration-report")).containsText("\"preview\": false");try(var db=h.store.open()){PlatformFoundationTest.bind(db);assertEquals("New source <script>",object(Service.record(db,"incident",sid).get("data")).get("short_description"));}
            page.navigate(h.base()+"/admin/migrations/"+job+"/conflicts");assertThat(page.locator("main")).containsText("Retained decisions are read-only");assertThat(page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review this record conflict"))).hasCount(0);ReleaseBrowserAcceptance.checkAccessible(page);
        }finally{MigrationSource.reset();}
    }
}
