package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
import java.util.*;
final class TableReadBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {String viewer="ScopedBrowser"+id().substring(0,8),table="u_scope_"+id(),record;Map<String,Object> incident;
  try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);Service.createAccount(db,map("name",viewer,"role","reader","password",WorkflowSupport.PASSWORD));incident=Service.schema(db,"incident");db.connection.commit();}
  try(var p=Playwright.create();var b=p.chromium().launch();var adminContext=b.newContext();var readerContext=b.newContext()){
   var admin=adminContext.newPage();h.login(admin,"admin");var definition=ConfigurationAcceptance.definition();definition.put("label","Restricted browser cases");definition.put("record_roles",map("read",List.of("admin")));admin.navigate(h.base()+"/admin/configuration");admin.getByLabel("Configuration package JSON").fill(JSON.writeValueAsString(ConfigurationAcceptance.bundle(table,definition)));admin.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Preview package")).click();assertThat(admin.locator("main")).containsText("Preview passed");admin.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Install package")).click();assertThat(admin.locator("main")).containsText("Configuration installed");
   try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);record=Service.create(db,table,map("short_description","Authorized browser record","amount","7"),"admin").get("sys_id").toString();var limited=object(JSON.readValue(JSON.writeValueAsString(incident),Object.class));limited.put("record_roles",map("read",List.of("admin")));limited.putIfAbsent("version",0);Configuration.install(db,ConfigurationAcceptance.bundle("incident",limited),actor,false);db.connection.commit();}
   var page=readerContext.newPage();page.navigate(h.base()+"/login");page.getByLabel("Username",new Page.GetByLabelOptions().setExact(true)).fill(viewer);page.getByLabel("Password",new Page.GetByLabelOptions().setExact(true)).fill(WorkflowSupport.PASSWORD);page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Enter your workspace")).click();assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Your workspace").setExact(true))).isVisible();assertThat(page.locator("a[href='/workspace/"+table+"']")).hasCount(0);
   for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/table-read-"+theme+".png")).setFullPage(true));}
   assertEquals(403,page.navigate(h.base()+"/record/"+table+"/"+record).status());
   try(var db=h.store.open()){definition=Service.schema(db,table);}definition.put("record_roles",map("read",List.of("admin","reader")));admin.getByLabel("Configuration package JSON").fill(JSON.writeValueAsString(ConfigurationAcceptance.bundle(table,definition)));admin.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Install package")).click();assertThat(admin.locator("main")).containsText("Configuration installed");page.navigate(h.base()+"/workspace/"+table);assertThat(page.locator("main")).containsText("Authorized browser record");assertThat(page.locator("a[href='/workspace/"+table+"']")).hasCount(1);ReleaseBrowserAcceptance.accessible(page);
  }finally{try(var db=h.store.open()){db.update("UPDATE os_schema SET definition="+db.jsonParameter()+" WHERE name='incident'",JSON.writeValueAsString(incident));db.connection.commit();}}
 }
}
