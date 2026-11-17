package ge.opensoft.openservice;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
final class AssetImpactBrowserAcceptance {
 static void run(BrowserTest h) throws Exception {var data=AssetImpactAcceptance.seed(h.store);try(var p=Playwright.create();var b=p.chromium().launch();var c=b.newContext()){var page=c.newPage();h.login(page,"admin");page.navigate(h.base()+"/record/cmdb_ci/"+data.get("source"));page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Inspect dependent configuration items")).press("Enter");for(String theme:new String[]{"light","dark"}){if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}assertThat(page.locator("main")).containsText("Visible child <script>");ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/asset-impact-"+theme+".png")).setFullPage(true));}page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Visible child <script>")).press("Enter");assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Visible child <script>").setExact(true))).isVisible();}}
}
