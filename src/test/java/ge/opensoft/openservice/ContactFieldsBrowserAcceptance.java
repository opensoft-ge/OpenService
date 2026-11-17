package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class ContactFieldsBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {String table="u_contact_"+id();
  try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
   var page=context.newPage();h.login(page,"admin");page.navigate(h.base()+"/admin/configuration");page.getByLabel("Configuration package JSON").fill(JSON.writeValueAsString(ContactFieldsAcceptance.bundle(table,ContactFieldsAcceptance.definition())));page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Preview package")).click();assertThat(page.locator("main")).containsText("Preview passed");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Install package")).click();assertThat(page.locator("main")).containsText("Configuration installed");
   page.navigate(h.base()+"/record/"+table+"/new");page.locator("input[name=short_description]").fill("Reviewed contact details");page.locator("input[name=email]").fill("Support@Example.com");page.locator("input[name=url]").fill("https://example.org/help");assertThat(page.locator("input[name=email]")).hasAttribute("type","email");assertThat(page.locator("input[name=url]")).hasAttribute("type","url");
   for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/contact-fields-"+theme+".png")).setFullPage(true));}
   page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Create record →")).press("Enter");assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Reviewed contact details").setExact(true))).isVisible();assertThat(page.locator("input[name=email]")).hasValue("Support@Example.com");page.reload();assertThat(page.locator("input[name=url]")).hasValue("https://example.org/help");
  }finally{try(var db=h.store.open()){db.update("DELETE FROM os_record WHERE \"table\"=?",table);db.update("DELETE FROM os_schema WHERE name=?",table);db.connection.commit();}}
 }
}
