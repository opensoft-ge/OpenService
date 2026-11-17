package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

final class ScheduledApprovalRemindersBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name="Scheduled"+id().substring(0,8),record;
        try(var db=h.store.open()){
            String actor=PlatformFoundationTest.bind(db);Service.createAccount(db,map("name",name,"password",WorkflowSupport.PASSWORD,"role","agent"));
            record=Service.create(db,"incident",map("short_description","Schedule this review"),"admin").get("sys_id").toString();
            Approvals.request(db,"incident",record,db.one("SELECT * FROM os_user WHERE user_id=?",actor),ApprovalAcceptance.proposal(name));db.connection.commit();
        }
        try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
            var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/record/incident/"+record+"/approvals");
            page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Inspect review readiness")).press("Enter");
            String due=Instant.ofEpochMilli((System.currentTimeMillis()/1000+3600)*1000).toString();
            page.getByLabel("Reminder time (UTC)").fill(due);
            for(String theme:List.of("light","dark")){
                if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}
                assertThat(page.locator("main")).containsText("Background processing is disabled");
                ReleaseBrowserAcceptance.checkAccessible(page);
                page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/approval-schedule-"+theme+".png")).setFullPage(true));
            }
            page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Schedule reminder")).press("Enter");
            assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Background work details"))).isVisible();
            assertThat(page.locator("main")).containsText("queued");assertThat(page.locator("main")).containsText(due);
            String job=page.url().substring(page.url().lastIndexOf('/')+1);
            page.navigate(h.base()+"/admin/jobs?q="+job+"&kind=approval.reminder");
            assertThat(page.locator("tbody tr")).hasCount(1);
            page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Cancel").setExact(true)).press("Enter");
            assertThat(page.locator("tbody")).containsText("cancelled");
            page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName(job).setExact(true)).press("Enter");
            assertThat(page.locator("main")).containsText("cancelled");
        }
    }
}
