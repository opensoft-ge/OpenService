package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
final class AuditCheckpointBrowserAcceptance {
 static String query(Map<String,Object> checkpoint){return checkpoint.entrySet().stream().map(e->e.getKey()+"="+URLEncoder.encode(e.getValue().toString(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));}
 static void run(WorkflowSupport h) throws Exception {
  h.account("CheckpointViewer","reader");String user;
  try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);user=RecordAccess.owner(db,"CheckpointViewer");AuditGovernance.grant(db,actor,map("user_id",user,"enabled",true));db.connection.commit();}
  String path="/audit/checkpoint";assertEquals(200,h.get(path,auth("CheckpointViewer")).statusCode());assertEquals(302,h.get(path,null).statusCode());
  var download=h.get(path+"?download=true",auth("CheckpointViewer"));assertEquals(200,download.statusCode());assertTrue(download.headers().firstValue("Content-Disposition").orElseThrow().contains("openservice-audit-checkpoint.json"));var checkpoint=object(parsed(download).get("result"));assertEquals(Set.of("algorithm","count","last_sequence","hash"),checkpoint.keySet());
  var verify=path+"?"+query(checkpoint);assertTrue(html(h.get(verify,auth("CheckpointViewer"))).contains("Retained prefix matches"));assertEquals(200,h.get(path+"?download=false",auth("admin")).statusCode());
  assertEquals(400,h.get(verify+"&download=true",auth("admin")).statusCode());
  for(String bad:List.of("download=yes","download=true&download=false","extra=x","hash=bad"))assertEquals(400,h.get(path+"?"+bad,auth("admin")).statusCode());assertEquals(405,h.protectedForm(path,map(),auth("admin")).statusCode());
  try(var db=h.store.open()){db.update("UPDATE os_security_event SET action='changed' WHERE sequence=?",checkpoint.get("last_sequence"));db.connection.commit();}
  assertTrue(html(h.get(verify,auth("CheckpointViewer"))).contains("Retained prefix does not match"));
  try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);AuditGovernance.grant(db,actor,map("user_id",user,"enabled",false));db.connection.commit();}
  assertEquals(403,h.get(path,auth("CheckpointViewer")).statusCode());assertEquals(403,h.get(path+"?download=true",auth("CheckpointViewer")).statusCode());
 }
 static void browser(BrowserTest h) throws Exception {
  try(var p=Playwright.create();var b=p.chromium().launch();var c=b.newContext()){
   var page=c.newPage();h.login(page,"admin");page.navigate(h.base()+"/admin/security-events");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Retain or verify an audit checkpoint")).press("Enter");
   var download=page.waitForDownload(()->page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Download current checkpoint")).click());assertEquals("openservice-audit-checkpoint.json",download.suggestedFilename());
   var checkpoint=object(object(JSON.readValue(Files.readString(download.path()),Object.class)).get("result"));
   page.getByLabel("Retained event count").fill(checkpoint.get("count").toString());page.getByLabel("Retained last sequence").fill(checkpoint.get("last_sequence").toString());page.getByLabel("Retained SHA-256 hash").fill(checkpoint.get("hash").toString());page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Verify checkpoint")).press("Enter");
   for(String theme:List.of("light","dark")){
    if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}
    assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Retained prefix matches").setExact(true))).isVisible();ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/audit-checkpoint-"+theme+".png")).setFullPage(true));
   }
   page.getByLabel("Retained SHA-256 hash").fill("f".repeat(64));page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Verify checkpoint")).click();assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Retained prefix does not match"))).isVisible();
  }
 }
}
