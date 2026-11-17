package ge.opensoft.openservice;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class CatalogueBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name="Catalogue "+Values.id(),requester="Buyer"+Values.id();
        try(var db=h.store.open()){Service.createAccount(db,Values.map("name",requester,"password",WorkflowSupport.PASSWORD,"role","requester"));db.connection.commit();}
        try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
            var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/admin/catalogue");
            page.getByLabel("Offering name").fill(name);page.getByLabel("Offering description").fill("A reusable service with human fulfilment.");page.getByLabel("Availability").selectOption("true");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save offering")).press("Enter");
            page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("View offering")).press("Enter");
            String offeringUrl=page.url();
            try(var buyer=browser.newContext()){page=buyer.newPage();page.navigate(h.base()+"/login");page.getByLabel("Username",new Page.GetByLabelOptions().setExact(true)).fill(requester);page.getByLabel("Password",new Page.GetByLabelOptions().setExact(true)).fill(WorkflowSupport.PASSWORD);page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Enter your workspace")).click();page.navigate(offeringUrl);
            for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/catalogue-"+theme+".png")).setFullPage(true));}
            page.getByLabel("Request details").fill("Please arrange a time with me.");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Submit service request")).press("Enter");assertThat(page.locator("main")).containsText(name);assertThat(page.locator("main")).containsText("Please arrange a time with me.");
            }
        }
    }
}
