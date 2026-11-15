package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class BrowserCsrfBrowserAcceptance {
    static void run(BrowserTest h) throws Exception {
        String name = "CsrfTarget" + id().substring(0, 8), target;
        try (var db = h.store.open()) {
            Service.createAccount(
                    db, map("name", name, "password", WorkflowSupport.PASSWORD, "role", "reader"));
            target = RecordAccess.owner(db, name);
            db.connection.commit();
        }
        String form = "/admin/accounts/" + target + "/basic-auth",
                api = "/api/openservice/users/" + target + "/basic-auth";
        var attacker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        attacker.createContext(
                "/",
                exchange -> {
                    byte[] body =
                            ("<!doctype html><html lang='en'><title>Cross-origin test</title><form"
                                 + " method='post' action='"
                                            + h.base()
                                            + form
                                            + "'><input name='allow_basic'"
                                            + " value='false'><button>Send test"
                                            + " form</button></form></html>")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        attacker.start();
        try (var playwright = Playwright.create();
                var browser = playwright.chromium().launch();
                var context =
                        browser.newContext(
                                new Browser.NewContextOptions()
                                        .setExtraHTTPHeaders(
                                                Map.of(
                                                        "Authorization",
                                                        WorkflowSupport.auth("admin"))))) {
            var page = context.newPage();
            assertEquals(200, page.navigate(h.base() + "/account").status());
            page.navigate("http://127.0.0.1:" + attacker.getAddress().getPort() + "/");
            var response =
                    page.waitForResponse(
                            r ->
                                    r.url().equals(h.base() + form)
                                            && r.request().method().equals("POST"),
                            () ->
                                    page.getByRole(
                                                    AriaRole.BUTTON,
                                                    new Page.GetByRoleOptions()
                                                            .setName("Send test form"))
                                            .click());
            assertTrue(
                    response.request().allHeaders().containsKey("authorization"),
                    "The fixture supplied ambient Basic credentials to the protected form");
            assertEquals(403, response.status());
            try (var db = h.store.open()) {
                assertEquals(true, BasicAccess.state(db, target).get("allow_basic"));
            }
            // The authenticated bootstrap is readable only at the application origin.
            assertEquals(200, page.navigate(h.base() + "/api/openservice/me/csrf").status());
            var result =
                    (Map<?, ?>)
                            page.evaluate(
                                    "async (api) => { const token=await (await"
                                        + " fetch('/api/openservice/me/csrf')).json(); const"
                                        + " options={method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({allow_basic:false})};"
                                        + " const blocked=await fetch(api,options);"
                                        + " options.headers['X-CSRF-Token']=token.result.csrf;"
                                        + " const allowed=await fetch(api,options); return"
                                        + " {blocked:blocked.status,allowed:allowed.status}; }",
                                    api);
            assertEquals(403, result.get("blocked"));
            assertEquals(200, result.get("allowed"));
            try (var db = h.store.open()) {
                assertEquals(false, BasicAccess.state(db, target).get("allow_basic"));
            }
        } finally {
            attacker.stop(0);
        }
    }
}
