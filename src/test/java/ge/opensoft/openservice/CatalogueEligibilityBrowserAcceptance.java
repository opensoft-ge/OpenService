package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
import java.util.*;

final class CatalogueEligibilityBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String user="Criteria"+id(),group,offering,member;
        try(var db=h.store.open()){
            String actor=PlatformFoundationTest.bind(db);
            Service.createAccount(db,map("name",user,"role","requester","password",WorkflowSupport.PASSWORD));member=RecordAccess.owner(db,user);
            group=Service.create(db,"sys_user_group",map("short_description","Eligibility <script> group"),"admin").get("sys_id").toString();
            offering=Catalogue.save(db,actor,"",CatalogueAcceptance.offering("Eligibility browser service",0,true)).get("offering_id").toString();
            GroupMembers.change(db,group,actor,map("user_id",member),true);db.connection.commit();
        }
        try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
            var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/admin/catalogue/"+offering);
            page.getByLabel("Eligible group").selectOption(group);
            page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save eligibility")).press("Enter");
            page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("View offering")).press("Enter");
            assertThat(page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Submit service request"))).hasCount(0);
            assertThat(page.locator("main")).containsText("Group membership is required");
            page.navigate(h.base()+"/admin/catalogue/"+offering);
            for(String theme:List.of("light","dark")){
                if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}
                assertThat(page.getByLabel("Eligible group")).hasValue(group);
                assertThat(page.locator("main")).containsText("Eligibility <script> group");
                assertEquals(0,page.locator("main script").count());
                ReleaseBrowserAcceptance.checkAccessible(page);
                page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/catalogue-eligibility-"+theme+".png")).setFullPage(true));
            }
            try(var buyer=browser.newContext()){
                var request=buyer.newPage();request.navigate(h.base()+"/login");
                request.getByLabel("Username",new Page.GetByLabelOptions().setExact(true)).fill(user);
                request.getByLabel("Password",new Page.GetByLabelOptions().setExact(true)).fill(WorkflowSupport.PASSWORD);
                request.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Enter your workspace")).click();
                request.navigate(h.base()+"/catalogue");request.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Eligibility browser service")).press("Enter");
                request.getByLabel("Request details").fill("Please prepare this service.");
                request.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Submit service request")).press("Enter");
                assertThat(request.locator("main")).containsText("Please prepare this service.");
                try(var db=h.store.open()){GroupMembers.change(db,group,PlatformFoundationTest.bind(db),map("user_id",member),false);db.connection.commit();}
                request.navigate(h.base()+"/catalogue");assertThat(request.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Eligibility browser service"))).hasCount(0);
                assertEquals(404,request.navigate(h.base()+"/catalogue/"+offering).status());
                page.getByLabel("Eligible group").selectOption("");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save eligibility")).press("Enter");
                assertEquals(200,request.navigate(h.base()+"/catalogue/"+offering).status());
            }
        }
    }
}
