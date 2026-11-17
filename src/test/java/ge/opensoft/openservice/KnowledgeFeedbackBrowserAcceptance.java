package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class KnowledgeFeedbackBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {String article,viewer="FeedbackBrowser"+id().substring(0,8);try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);Service.createAccount(db,map("name",viewer,"role","requester","password",WorkflowSupport.PASSWORD));article=KnowledgeSearchAcceptance.article(db,actor,"Guide"+"x".repeat(160),"Published browser instructions");db.connection.commit();}
 try(var p=Playwright.create();var b=p.chromium().launch();var c=b.newContext()){var page=c.newPage();page.navigate(h.base()+"/login");page.getByLabel("Username",new Page.GetByLabelOptions().setExact(true)).fill(viewer);page.getByLabel("Password",new Page.GetByLabelOptions().setExact(true)).fill(WorkflowSupport.PASSWORD);page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Enter your workspace")).click();page.navigate(h.base()+"/knowledge/"+article);
 page.getByLabel("Was this article helpful?").selectOption("false");page.getByLabel("Feedback details").fill("Please explain the next step <script>");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save feedback")).press("Enter");assertThat(page.locator("main")).containsText("Awaiting editorial review");for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/knowledge-feedback-"+theme+".png")).setFullPage(true));}
 try(var ac=b.newContext()){var admin=ac.newPage();h.login(admin,"admin");admin.navigate(h.base()+"/admin/knowledge/"+article+"/feedback");assertThat(admin.locator("main")).containsText("Please explain the next step <script>");admin.getByLabel("Editorial review note").fill("Follow-up recorded");admin.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Mark reviewed")).press("Enter");assertThat(admin.locator("main")).containsText("0 awaiting review");for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){admin.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();admin.setViewportSize(390,844);}ReleaseBrowserAcceptance.accessible(admin);admin.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/knowledge-review-"+theme+".png")).setFullPage(true));}}
 page.reload();assertThat(page.locator("main")).containsText("Reviewed by an administrator");assertThat(page.locator("main")).not().containsText("Follow-up recorded");}
 }
}
