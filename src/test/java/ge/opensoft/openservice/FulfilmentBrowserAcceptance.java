package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
import java.util.*;

final class FulfilmentBrowserAcceptance {
    static void login(BrowserTest h,Page page,String name){page.navigate(h.base()+"/login");page.getByLabel("Username",new Page.GetByLabelOptions().setExact(true)).fill(name);page.getByLabel("Password",new Page.GetByLabelOptions().setExact(true)).fill(WorkflowSupport.PASSWORD);page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Enter your workspace")).click();}
    static void run(BrowserTest h)throws Exception {
        String suffix=id().substring(0,8),buyer="Buyer"+suffix,agent="Fulfiller"+suffix,title="Deliver laptop "+suffix,group,other;
        try(var db=h.store.open()){
            String actor=PlatformFoundationTest.bind(db);Service.createAccount(db,map("name",buyer,"password",WorkflowSupport.PASSWORD,"role","requester"));Service.createAccount(db,map("name",agent,"password",WorkflowSupport.PASSWORD,"role","agent"));
            group=Service.create(db,"sys_user_group",map("short_description","Laptop team "+suffix),"admin").get("sys_id").toString();other=Service.create(db,"sys_user_group",map("short_description","Recovery team "+suffix),"admin").get("sys_id").toString();
            String user=RecordAccess.owner(db,agent);GroupMembers.change(db,group,actor,map("user_id",user),true);GroupMembers.change(db,other,actor,map("user_id",user),true);db.connection.commit();
        }
        try(var p=Playwright.create();var browser=p.chromium().launch();var admin=browser.newContext();var requester=browser.newContext();var staff=browser.newContext()){
            var page=admin.newPage();h.login(page,"admin");page.navigate(h.base()+"/admin/catalogue");page.getByLabel("Offering name").fill(title);page.getByLabel("Offering description").fill("A managed laptop delivery");page.getByLabel("Availability").selectOption("true");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save offering")).press("Enter");
            page.getByLabel("Fulfilment group").selectOption(group);page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save routing")).press("Enter");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("View offering")).press("Enter");String offeringUrl=page.url();
            var owner=requester.newPage();login(h,owner,buyer);owner.navigate(offeringUrl);owner.getByLabel("Request details").fill("Set up a laptop for my project.");owner.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Submit service request")).press("Enter");owner.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Follow fulfilment")).press("Enter");assertThat(owner.locator("main")).containsText("Waiting for a team member");String taskUrl=owner.url();assertThat(owner.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Claim fulfilment"))).hasCount(0);
            page.navigate(taskUrl);page.getByLabel("Handoff group").selectOption(other);page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Handoff fulfilment")).press("Enter");assertThat(page.locator("main")).containsText("Recovery team "+suffix);
            var fulfiller=staff.newPage();h.login(fulfiller,agent);fulfiller.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Fulfilment queue").setExact(true)).press("Enter");fulfiller.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName(title).setExact(true)).press("Enter");fulfiller.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Claim fulfilment")).press("Enter");
            assertThat(fulfiller.locator("main")).containsText("In progress");
            for(String theme:List.of("light","dark")){
                if(theme.equals("dark")){fulfiller.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();fulfiller.setViewportSize(390,844);}
                ReleaseBrowserAcceptance.checkAccessible(fulfiller);fulfiller.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/fulfilment-"+theme+".png")).setFullPage(true));
            }
            fulfiller.getByLabel("Shared outcome").fill("Laptop delivered and checked <script>alert(1)</script>");fulfiller.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Complete fulfilment")).press("Enter");assertThat(fulfiller.locator("main")).containsText("Complete");
            owner.reload();assertThat(owner.locator("main")).containsText("Laptop delivered and checked <script>alert(1)</script>");assertThat(owner.locator("main script")).hasCount(0);ReleaseBrowserAcceptance.checkAccessible(owner);
            owner.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Back to request")).press("Enter");assertThat(owner.locator("main > .page-heading .pill")).hasText("Complete");
        }
    }
}
