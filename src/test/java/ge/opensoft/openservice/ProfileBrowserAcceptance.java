package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class ProfileBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {
  String name="ProfileBrowser"+id().substring(0,8),user;
  try(var db=h.store.open()){Service.createAccount(db,map("name",name,"role","reader","password",WorkflowSupport.PASSWORD));user=RecordAccess.owner(db,name);db.connection.commit();}
  try(var p=Playwright.create();var b=p.chromium().launch();var c=b.newContext();var admin=b.newContext()){
   var page=c.newPage();h.login(page,name);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Your profile").setExact(true)).click();
   for(String theme:new String[]{"light","dark"}){
    if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}
    page.getByLabel("Display name").fill("José <script>");page.getByLabel("Job title").fill("Service specialist");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save profile")).press("Enter");
    assertThat(page.getByLabel("Display name")).hasValue("José <script>");page.reload();assertThat(page.getByLabel("Job title")).hasValue("Service specialist");
    ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/profile-"+theme+".png")).setFullPage(true));
   }
   var operator=admin.newPage();h.login(operator,"admin");operator.navigate(h.base()+"/admin/accounts/"+user);assertThat(operator.locator("main")).containsText("José <script>");assertThat(operator.locator("main")).containsText("Service specialist");
   page.getByLabel("Display name").fill("");page.getByLabel("Job title").fill("");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Save profile")).click();assertThat(page.getByLabel("Display name")).hasValue("");
   operator.reload();assertThat(operator.getByText("Not provided",new Page.GetByTextOptions().setExact(true))).hasCount(2);
  }
 }
}
