package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class KnowledgeComparisonBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {
  String id,title="Version review "+id();
  try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);id=KnowledgeSearchAcceptance.article(db,actor,title,"First instructions <script>");Service.update(db,"kb_knowledge",Service.record(db,"kb_knowledge",id),map("text","Second instructions"),"admin","admin",null);PublishedKnowledge.publish(db,actor,id);db.connection.commit();}
  try(var p=Playwright.create();var b=p.chromium().launch();var c=b.newContext()){
   var page=c.newPage();h.login(page,"admin");page.navigate(h.base()+"/knowledge?q="+java.net.URLEncoder.encode(title,java.nio.charset.StandardCharsets.UTF_8));
   page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Compare publication versions")).press("Enter");
   for(String theme:new String[]{"light","dark"}){
    if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}
    assertThat(page.locator("main")).containsText("First instructions <script>");assertThat(page.locator("main")).containsText("Second instructions");assertThat(page.locator("main")).containsText("Title unchanged");assertThat(page.locator("main")).containsText("Body changed");
    ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/knowledge-comparison-"+theme+".png")).setFullPage(true));
   }
   page.getByLabel("Left version").fill("2");page.getByLabel("Right version").fill("2");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Compare versions")).click();assertThat(page.locator("main")).containsText("Body unchanged");
  }
 }
}
