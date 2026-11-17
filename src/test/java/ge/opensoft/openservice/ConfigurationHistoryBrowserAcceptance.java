package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.*;
import java.util.*;

final class ConfigurationHistoryBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String table="u_history_"+id(),installation;
        try(var db=h.store.open()) {
            String actor=PlatformFoundationTest.bind(db);
            var definition=ConfigurationAcceptance.definition();
            definition.put("label","Historical <img src=x> configuration");
            installation=Configuration.install(db,ConfigurationAcceptance.bundle(table,definition),actor,false).get("installation_id").toString();
            var next=Service.schema(db,table);next.put("label","Current configuration");
            Configuration.install(db,ConfigurationAcceptance.bundle(table,next),actor,false);
            db.connection.commit();
        }
        try(var playwright=Playwright.create();var browser=playwright.chromium().launch();var context=browser.newContext()) {
            var page=context.newPage();h.login(page,"admin");
            page.navigate(h.base()+"/admin/configuration");
            page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Review installation history")).press("Enter");
            page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName(installation).setExact(true)).press("Enter");
            for(String theme:List.of("light","dark")) {
                if(theme.equals("dark")){page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).click();page.setViewportSize(390,844);}
                assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Installation receipt").setExact(true))).isVisible();
                assertThat(page.locator("main")).containsText("Historical <img src=x> configuration");
                assertEquals(0,page.locator("main img").count());
                var link=page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Download installation receipt"));link.focus();assertThat(link).isFocused();
                var download=page.waitForDownload(()->link.press("Enter"));
                assertEquals("configuration-installation-"+installation+".json",download.suggestedFilename());
                var saved=object(JSON.readValue(Files.readString(download.path()),Object.class));
                assertEquals(installation,object(saved.get("result")).get("install_id"));
                ReleaseBrowserAcceptance.checkAccessible(page);
                page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/configuration-history-"+theme+".png")).setFullPage(true));
            }
            page.navigate(h.base()+ConfigurationHistoryAcceptance.PAGE+"?limit=1");
            page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("More installations")).press("Enter");
            assertTrue(page.url().contains("offset=1"));
            page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Previous installations")).press("Enter");
            assertTrue(page.url().contains("offset=0"));
            var region=page.getByRole(AriaRole.REGION,new Page.GetByRoleOptions().setName("Configuration installation history"));region.focus();assertThat(region).isFocused();
            ReleaseBrowserAcceptance.checkAccessible(page);
        } finally {
            try(var db=h.store.open()){db.update("DELETE FROM os_schema WHERE name=?",table);db.connection.commit();}
        }
    }
}
