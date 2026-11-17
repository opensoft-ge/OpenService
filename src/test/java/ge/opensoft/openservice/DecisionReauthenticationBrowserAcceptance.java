package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class DecisionReauthenticationBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {String table="u_decision_"+id(),user="ConfirmBrowser"+id().substring(0,8),record;
  try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);Service.createAccount(db,map("name",user,"role","agent","password",WorkflowSupport.PASSWORD));Configuration.install(db,ContactFieldsAcceptance.bundle(table,DecisionReauthenticationAcceptance.definition()),actor,false);record=Service.create(db,table,map("short_description","Reviewed decision"),"admin").get("sys_id").toString();Approvals.request(db,table,record,db.one("SELECT * FROM os_user WHERE user_id=?",actor),DecisionReauthenticationAcceptance.proposal(user));db.connection.commit();}
  try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
   var page=context.newPage();h.login(page,user);String path=h.base()+"/record/"+table+"/"+record+"/approvals";page.navigate(path);assertThat(page.getByLabel("Confirm current password")).hasAttribute("type","password");
   for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/decision-confirmation-"+theme+".png")).setFullPage(true));}
   page.getByLabel("Confirm current password").fill("wrong verification password");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Record decision")).press("Enter");assertThat(page.locator("body")).containsText("Current password is incorrect");page.navigate(path);page.getByLabel("Confirm current password").fill(WorkflowSupport.PASSWORD);page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Record decision")).press("Enter");assertThat(page.locator("main")).containsText(user+" · approved");assertThat(page.getByLabel("Confirm current password")).hasCount(0);assertFalse(page.content().contains(WorkflowSupport.PASSWORD));ReleaseBrowserAcceptance.accessible(page);
  }finally{try(var db=h.store.open()){db.update("DELETE FROM os_record WHERE \"table\"=?",table);db.update("DELETE FROM os_schema WHERE name=?",table);db.connection.commit();}}
 }
}
