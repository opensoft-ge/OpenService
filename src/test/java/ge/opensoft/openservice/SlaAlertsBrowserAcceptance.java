package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class SlaAlertsBrowserAcceptance {
    static void run(BrowserTest h)throws Exception{
        String name="AlertBrowser"+id().substring(0,8),record,clock;try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);Service.createAccount(db,map("name",name,"role","agent","password",WorkflowSupport.PASSWORD));record=Service.create(db,"incident",map("short_description","Review this overdue commitment","assigned_to",name),"admin").get("sys_id").toString();String calendar="AlertBrowser"+id();ServiceClocks.calendar(db,actor,map("name",calendar,"calendar",PlatformFoundationTest.calendar()));clock=ServiceClocks.start(db,actor,map("table","incident","record_id",record,"calendar",calendar,"minutes",1,"purpose","resolution"),SlaAlertsAcceptance.START).get("sla_id").toString();db.connection.commit();}SlaAlertsAcceptance.tick(h.store,SlaAlertsAcceptance.START+60000);
        try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
            var page=context.newPage();h.login(page,name);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Notifications").setExact(true)).click();assertThat(page.locator("main")).containsText(clock);assertThat(page.locator("main")).containsText("has breached its business-time budget");
            for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.checkAccessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/sla-alert-"+theme+".png")).setFullPage(true));}
            page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review related work")).press("Enter");assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Service clocks").setExact(true))).isVisible();assertThat(page.locator("main")).containsText("breached");assertThat(page.locator("main")).containsText("resolution");ReleaseBrowserAcceptance.checkAccessible(page);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Back to record")).press("Enter");assertThat(page.locator("main")).containsText("Review this overdue commitment");page.navigate(h.base()+"/account/notifications");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Acknowledge").setExact(true)).press("Enter");assertThat(page.locator("main")).containsText("Acknowledged");
        }
    }
}
