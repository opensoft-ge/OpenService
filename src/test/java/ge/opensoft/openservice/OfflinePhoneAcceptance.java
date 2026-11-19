package ge.opensoft.openservice;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

final class OfflinePhoneAcceptance {
    static void run(BrowserTest harness,Browser browser,String technician,String task) throws Exception {
        var options=new Browser.NewContextOptions().setViewportSize(390,844).setIsMobile(true).setHasTouch(true);
        try(var phone=browser.newContext(options)) {
            var page=phone.newPage();
            harness.login(page,technician);
            String url=harness.base()+"/enterprise/field/"+task;
            page.navigate(url);
            var download=page.waitForDownload(()->page.getByText("Download this visit worksheet").click());
            assertEquals("visit-"+task+".csv",download.suggestedFilename());
            phone.setOffline(true);
            String saved=Files.readString(download.path()).replace("minutes,0","minutes,35")
                    .replace("evidence_note,","evidence_note,Verified service on site");
            phone.setOffline(false);
            var worksheet=new FilePayload("visit.csv","text/csv",saved.getBytes(StandardCharsets.UTF_8));
            var photo=new FilePayload("site-evidence.jpg","image/jpeg",new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff,(byte)0xd9});
            for(int i=0;i<2;i++) {
                page.getByLabel("Completed worksheet").setInputFiles(worksheet);
                page.getByLabel("Optional evidence photo").setInputFiles(photo);
                page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Sync worksheet")).click();
                assertThat(page.locator("main")).containsText("Verified service on site");
            }
            try(var db=harness.store.open()) {
                assertEquals(1,db.count("SELECT COUNT(*) FROM os_attachment WHERE name=?","site-evidence.jpg"));
            }
        }
    }
}
