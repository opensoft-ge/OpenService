package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class AuditGrantBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {
  String name="AuditBrowser"+id().substring(0,8),user;
  try(var db=h.store.open()){Service.createAccount(db,map("name",name,"role","reader","password",WorkflowSupport.PASSWORD));user=RecordAccess.owner(db,name);db.connection.commit();}
  try(var p=Playwright.create();var b=p.chromium().launch();var admin=b.newContext();var reader=b.newContext()){
   var page=admin.newPage();var journal=reader.newPage();h.login(page,"admin");h.login(journal,name);
   page.navigate(h.base()+"/admin/accounts/"+user);
   for(String theme:new String[]{"light","dark"}){
    if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}
    assertThat(page.getByLabel("Audit journal access")).hasValue("false");ReleaseBrowserAcceptance.accessible(page);
    page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/audit-grant-"+theme+".png")).setFullPage(true));
   }
   page.getByLabel("Audit journal access").selectOption("true");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save audit access")).press("Enter");
   assertThat(page.getByLabel("Audit journal access")).hasValue("true");assertThat(page.getByLabel("Account role",new Page.GetByLabelOptions().setExact(true))).hasValue("reader");
   journal.navigate(h.base()+"/admin/security-events");assertTrue(journal.url().contains("/login"));h.login(journal,name);
   journal.navigate(h.base()+"/admin/security-events");assertThat(journal.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Security events").setExact(true))).isVisible();
   page.getByLabel("Audit journal access").selectOption("false");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save audit access")).click();
   journal.reload();assertTrue(journal.url().contains("/login"));h.login(journal,name);
   journal.navigate(h.base()+"/admin/security-events");assertThat(journal.locator("main")).containsText("Audit viewer permission required");
  }
 }
}
