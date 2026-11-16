package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;

final class KnowledgeSearchBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String prefix="KnowledgeBrowser"+id().substring(0,8);
        String viewer=prefix+"Requester";
        try(var db=h.store.open()) {
            String actor=PlatformFoundationTest.bind(db);
            Service.createAccount(db,map("name",viewer,"role","requester","password",WorkflowSupport.PASSWORD));
            for(int i=0;i<3;i++) KnowledgeSearchAcceptance.article(db,actor,prefix+" "+i,"Safe answer <script>alert('x')</script>");
            Service.create(db,"kb_knowledge",map("short_description",prefix+" PRIVATE","text","SECRET DRAFT"),"admin");
            db.connection.commit();
        }
        try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()) {
            var page=context.newPage();
            page.navigate(h.base()+"/login");
            page.getByLabel("Username",new Page.GetByLabelOptions().setExact(true)).fill(viewer);
            page.getByLabel("Password",new Page.GetByLabelOptions().setExact(true)).fill(WorkflowSupport.PASSWORD);
            page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Enter your workspace")).click();
            page.navigate(h.base()+"/knowledge");
            for(String theme:new String[]{"light","dark"}) {
                if(theme.equals("dark")) {
                    page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();
                    page.setViewportSize(390,844);
                }
                page.getByLabel("Search published knowledge").fill(prefix);
                page.getByLabel("Articles per page").fill("1");
                page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Search articles")).press("Enter");
                assertThat(page.locator("article")).hasCount(1);
                assertThat(page.locator("main")).containsText("3 matching articles");
                assertThat(page.locator("main")).not().containsText("SECRET DRAFT");
                String first=page.locator("article h2").innerText();
                page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Next articles")).press("Enter");
                assertNotEquals(first,page.locator("article h2").innerText());
                page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Previous articles")).click();
                assertEquals(first,page.locator("article h2").innerText());
                ReleaseBrowserAcceptance.accessible(page);
                page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/knowledge-search-"+theme+".png")).setFullPage(true));
                page.getByLabel("Search published knowledge").fill(prefix+"-missing");
                page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Search articles")).click();
                assertThat(page.locator("article")).hasCount(0);
                assertThat(page.getByText("No published articles match this search.")).isVisible();
            }
            assertEquals(400,page.navigate(h.base()+"/knowledge?q=a&q=b").status());
        }
    }
}
