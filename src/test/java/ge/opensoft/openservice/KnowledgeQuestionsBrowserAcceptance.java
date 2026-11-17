package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class KnowledgeQuestionsBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {String id,user="ArticleReader"+id().substring(0,8);try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);Service.createAccount(db,map("name",user,"password",WorkflowSupport.PASSWORD,"role","reader"));id=KnowledgeSearchAcceptance.article(db,actor,"Reviewed support article","The approved procedure is to open a service request.");db.connection.commit();}
  try(var provider=new RecordQuestionsAcceptance.Provider(h.store,h.environment);var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
   provider.settings.put("AI_KNOWLEDGE_ENABLED","true");var page=context.newPage();h.login(page,user);page.navigate(h.base()+"/knowledge/"+id);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Ask about this article")).click();page.getByLabel("Your question").fill("How do I ask for help?");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Preview question")).press("Enter");assertEquals(0,provider.calls.get());
   for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/knowledge-assistant-"+theme+".png")).setFullPage(true));}
   page.getByRole(AriaRole.CHECKBOX).check();page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Send question")).press("Enter");assertThat(page.locator("main")).containsText("Evidence <script>not executed</script>");assertThat(page.locator("main")).containsText("Published version 1 · No actions executed");assertThat(page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review published source"))).hasAttribute("href","/knowledge/"+id);assertEquals(1,provider.calls.get());ReleaseBrowserAcceptance.accessible(page);
   provider.status.set(503);page.getByLabel("Your question").fill("Try again");page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Preview question")).click();page.getByRole(AriaRole.CHECKBOX).check();page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Send question")).click();assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Answer unavailable"))).isVisible();assertThat(page.locator("main")).containsText("AI provider did not complete the request");assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Answer draft — verify before relying on it"))).hasCount(0);
  }
 }
}
