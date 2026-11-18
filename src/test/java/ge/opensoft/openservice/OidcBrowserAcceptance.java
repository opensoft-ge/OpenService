package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;

final class OidcBrowserAcceptance {
 static void run(BrowserTest h)throws Exception {
  String username="FederatedBrowser"+id(),subject="browser-"+id();
  try(var db=h.store.open()){PlatformFoundationTest.bind(db);Service.createAccount(db,map("name",username,"password",WorkflowSupport.PASSWORD,"role","agent"));db.connection.commit();}
  try(var provider=new OidcAcceptance.Provider(h.store,h.base());var p=Playwright.create();var browser=p.chromium().launch();var admin=browser.newContext();var employee=browser.newContext()){
   provider.subject=subject;var page=admin.newPage();h.login(page,"admin");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Organizational sign-in").setExact(true)).press("Enter");page.getByLabel("Local username").fill(username);page.getByLabel("Provider subject").fill(subject);ServiceOutagesBrowserAcceptance.click(page,"Link organizational identity");assertThat(page.locator("main")).containsText(username);assertThat(page.locator("main")).not().containsText("synthetic-secret");ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/federation-link-light.png")).setFullPage(true));
   ServiceOutagesBrowserAcceptance.click(page,"Dark mode");page.setViewportSize(390,844);ReleaseBrowserAcceptance.accessible(page);assertEquals(false,page.evaluate("document.documentElement.scrollWidth>window.innerWidth"));assertEquals(false,page.evaluate("Array.from(document.querySelectorAll('main .panel')).some(panel=>panel.scrollWidth>panel.clientWidth+1)"));page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/federation-link-dark.png")).setFullPage(true));
   var worker=employee.newPage();var blocked=new java.util.concurrent.atomic.AtomicBoolean();worker.onConsoleMessage(message->{if(message.text().contains("form-action"))blocked.set(true);});worker.navigate(h.base()+"/login");worker.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Sign in with your organization")).press("Enter");assertThat(worker.locator("main")).containsText("Continue to your organization");ReleaseBrowserAcceptance.accessible(worker);worker.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Continue to identity provider")).press("Enter");worker.waitForCondition(()->blocked.get() || worker.url().equals(h.base()+"/"));assertFalse(blocked.get(),"The browser blocked organizational sign-in under form-action CSP");worker.waitForURL(h.base()+"/");worker.navigate(h.base()+"/account");assertThat(worker.locator("body")).containsText(username);assertEquals(1,provider.tokenCalls);ServiceOutagesBrowserAcceptance.click(worker,"Dark mode");worker.setViewportSize(390,844);ReleaseBrowserAcceptance.accessible(worker);assertEquals(false,worker.evaluate("document.documentElement.scrollWidth>window.innerWidth"));worker.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/federation-session-dark.png")).setFullPage(true));
   page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Remove link and end sessions for "+username)).press("Enter");assertThat(page.locator("main")).not().containsText(username);worker.navigate(h.base()+"/account");worker.waitForURL(h.base()+"/login");provider.settings.put("OIDC_ENABLED","false");worker.reload();assertThat(worker.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Sign in with your organization"))).hasCount(0);h.login(worker,username);worker.navigate(h.base()+"/account");assertThat(worker.locator("body")).containsText(username);
  }
 }
}
