package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
import java.util.*;

final class CatalogueQuestionsBrowserAcceptance {
    static void question(Page page,String key,String label,String type,boolean required,String choices){page.getByLabel("Question key",new Page.GetByLabelOptions().setExact(true)).fill(key);page.getByLabel("Question label").fill(label);page.getByLabel("Answer type").selectOption(type);page.getByLabel("Answer required").selectOption(Boolean.toString(required));page.getByLabel("Choices",new Page.GetByLabelOptions().setExact(true)).fill(choices);page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save question")).press("Enter");}
    static void run(BrowserTest h)throws Exception {
        String title="Configured service "+id().substring(0,8),buyer="QuestionBuyer"+id().substring(0,8);
        try(var db=h.store.open()){Service.createAccount(db,map("name",buyer,"password",WorkflowSupport.PASSWORD,"role","requester"));db.connection.commit();}
        try(var p=Playwright.create();var browser=p.chromium().launch();var admin=browser.newContext();var requester=browser.newContext()){
            var page=admin.newPage();h.login(page,"admin");page.navigate(h.base()+"/admin/catalogue");page.getByLabel("Offering name").fill(title);page.getByLabel("Offering description").fill("Configured request details");page.getByLabel("Availability").selectOption("true");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save offering")).press("Enter");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Manage questions")).press("Enter");
            question(page,"reason","Purpose <script>","string",true,"");question(page,"size","Equipment size","choice",true,"Small\nLarge");question(page,"needed","Delivery needed","boolean",true,"");question(page,"start","Start date","date",true,"");question(page,"count","Quantity","integer",false,"");question(page,"remove_me","Temporary question","string",false,"");
            page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Remove question Temporary question")).press("Enter");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Edit Equipment size")).press("Enter");assertThat(page.getByLabel("Choices",new Page.GetByLabelOptions().setExact(true))).hasValue("Small\nLarge");page.getByLabel("Choices",new Page.GetByLabelOptions().setExact(true)).fill("Small\nLarge\nMedium");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save question")).press("Enter");
            ReleaseBrowserAcceptance.checkAccessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/catalogue-questions-admin.png")).setFullPage(true));page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Preview offering")).press("Enter");String offering=page.url();
            var owner=requester.newPage();FulfilmentBrowserAcceptance.login(h,owner,buyer);owner.navigate(offering);
            for(String theme:List.of("light","dark")){
                if(theme.equals("dark")){owner.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();owner.setViewportSize(390,844);}
                ReleaseBrowserAcceptance.checkAccessible(owner);owner.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/catalogue-questions-"+theme+".png")).setFullPage(true));
            }
            owner.getByLabel("Purpose <script> (required)").fill("Project <script>alert(1)</script>");owner.getByLabel("Equipment size (required)").selectOption("Medium");owner.getByLabel("Delivery needed (required)").selectOption("false");owner.getByLabel("Start date (required)").fill("2026-11-18");owner.getByLabel("Quantity (optional)").fill("2");owner.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Submit service request")).press("Enter");
            assertThat(owner.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Submitted answers").setExact(true))).isVisible();assertThat(owner.locator("main")).containsText("Project <script>alert(1)</script>");assertThat(owner.locator("main script")).hasCount(0);ReleaseBrowserAcceptance.checkAccessible(owner);
        }
    }
}
