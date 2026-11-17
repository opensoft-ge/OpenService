package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class NotificationBatchBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {
  String user="BatchBrowser"+id().substring(0,8);try(var db=h.store.open()){Service.createAccount(db,map("name",user,"role","reader","password",WorkflowSupport.PASSWORD));for(int i=0;i<3;i++)Notifications.deliver(db,id(),map("username",user,"message","Review visible message "+i),i);db.connection.commit();}
  try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
   var page=context.newPage();h.login(page,user);page.navigate(h.base()+"/account/notifications?state=unread&limit=2");
   for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}assertThat(page.locator("article")).hasCount(2);ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/notification-batch-"+theme+".png")).setFullPage(true));}
   try(var db=h.store.open()){Notifications.deliver(db,id(),map("username",user,"message","New arrival after review"),1000);db.connection.commit();}
   page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Acknowledge this page")).press("Enter");assertThat(page.getByLabel("Message state")).hasValue("unread");assertThat(page.getByLabel("Messages per page")).hasValue("2");assertThat(page.locator("main")).containsText("2 matching messages");assertThat(page.locator("main")).containsText("New arrival after review");assertThat(page.locator("main")).containsText("Review visible message 0");
   page.getByLabel("Message state").selectOption("acknowledged");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Find messages")).click();assertThat(page.locator("article")).hasCount(2);assertThat(page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Acknowledge this page"))).hasCount(0);ReleaseBrowserAcceptance.accessible(page);
  }
 }
}
