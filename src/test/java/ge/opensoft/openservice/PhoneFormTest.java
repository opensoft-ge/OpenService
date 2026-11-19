package ge.opensoft.openservice;

import com.google.gson.*;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.Base64;
import org.apache.commons.codec.digest.DigestUtils;

class PhoneFormTest {
    @Test void disconnectedVisit(@TempDir Path folder)throws Exception{
        Path resources=Path.of("src/main/resources");
        String script=Files.readString(resources.resolve("static/field-phone.js"));
        String template=Files.readString(resources.resolve("field-phone.html"));
        Path local=folder.resolve("visit.html");
        Files.writeString(local,template.replace("VISIT_DETAILS","Site A").replace("SIGNED_PACKET","ticket,signed")
                .replace("PHONE_DIGEST",Base64.getEncoder().encodeToString(DigestUtils.sha256(script))).replace("PHONE_SCRIPT",script));
        try(var playwright=Playwright.create();var browser=playwright.chromium().launch();var context=browser.newContext(new Browser.NewContextOptions().setViewportSize(390,844))){
            var page=context.newPage();
            var cdp=ThemeBrowserAcceptance.instrument(context,page);
            context.setOffline(true);
            page.navigate(local.toUri().toString());
            page.getByLabel("Minutes worked").fill("37");
            page.getByLabel("Evidence note").fill("Replaced, tested");
            page.getByText("Add a used part").click();
            page.getByText("Remove part").click();
            page.getByText("Add a used part").click();
            page.getByLabel("Configuration item ID").fill("0".repeat(32));
            page.getByLabel("Part notes").fill("Used one cable");
            page.evaluate("() => { const release=URL.revokeObjectURL; URL.revokeObjectURL=u=>{release(u);document.body.dataset.released='yes';}; }");
            var saved=page.waitForDownload(()->page.getByText("Save completed worksheet").click());
            assertTrue(Files.readString(saved.path()).contains("\"evidence_note\",\"Replaced, tested\""));
            assertTrue(Files.readString(saved.path()).contains("\"part\",\""+ "0".repeat(32)+"\",\"1\",\"Used one cable\""));
            page.locator("body[data-released=yes]").waitFor();
            var reports=new JsonArray();
            for(var value:cdp.send("Profiler.takePreciseCoverage").getAsJsonArray("result")){
                var entry=value.getAsJsonObject();
                if(!entry.get("url").getAsString().equals(local.toUri().toString()))continue;
                var params=new JsonObject();params.add("scriptId",entry.get("scriptId"));
                var source=cdp.send("Debugger.getScriptSource",params).get("scriptSource");
                if(!source.getAsString().equals(script))continue;
                var raw=new JsonObject();raw.add("functions",entry.get("functions"));raw.add("source",source);reports.add(raw);
            }
            assertEquals(1,reports.size());
            Files.writeString(Path.of("target/field-phone-browser-v8.json"),reports.toString());
        }
    }
}
