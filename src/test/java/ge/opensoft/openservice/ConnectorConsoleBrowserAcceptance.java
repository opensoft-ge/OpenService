package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class ConnectorConsoleBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {
  try(var provider=new RecordQuestionsAcceptance.Provider(h.store,h.environment);var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
   provider.server.createContext("/probe",exchange->{provider.calls.incrementAndGet();byte[] bytes="private-result".getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
   String alias="console-"+id();var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/admin/operations");page.getByLabel("Alias",new Page.GetByLabelOptions().setExact(true)).fill(alias);page.getByLabel("Allowed origin").fill(provider.origin());page.getByLabel("Bearer token").fill("browser-probe-token");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save connector")).press("Enter");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName(alias).setExact(true)).press("Enter");
   for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/connector-console-"+theme+".png")).setFullPage(true));}
   page.getByLabel("Read-only endpoint path").fill("/probe");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Queue connection test")).press("Enter");assertThat(page.locator("main")).containsText("queued");String job=page.url().substring(page.url().lastIndexOf('/')+1);
   // Put only this synthetic job first; execute the real claim and worker path.
   try(var db=h.store.open()){db.update("UPDATE os_work SET created_at=? WHERE job_id=?",Long.MIN_VALUE,job);db.connection.commit();}assertTrue(new WorkWorker(h.store).runOne(System.currentTimeMillis()+100));page.reload();assertThat(page.locator("main")).containsText("succeeded");assertThat(page.locator("main")).containsText("sha256");assertThat(page.locator("main")).not().containsText("private-result");assertEquals(1,provider.calls.get());
   page.navigate(h.base()+"/admin/connectors/"+alias);page.getByLabel("Retirement reason").fill("Evaluation completed");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Retire connector")).press("Enter");assertThat(page.locator("main")).not().containsText(alias);
  }
 }
}
