package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;
class SharedSessionsTest {
 @TempDir Path root;
 ConfigurableApplicationContext start(MockEnvironment env){return new SpringApplicationBuilder(Application.class).run("--server.port=0","--DATABASE_URL="+env.getProperty("DATABASE_URL"),"--SECRET_KEY="+env.getProperty("SECRET_KEY"),"--ADMIN_PASSWORD="+WorkflowSupport.PASSWORD,"--COOKIE_SECURE=false","--SHARED_BROWSER_SESSIONS=true","--spring.main.banner-mode=off");}
 static String cookie(WorkflowSupport h){return ((CookieManager)h.client.cookieHandler().orElseThrow()).getCookieStore().getCookies().stream().filter(c->c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();}
 @ParameterizedTest @ValueSource(booleans={false,true}) void browserMovesBetweenInstancesAndRestart(boolean postgres)throws Exception{
  var migration=new MigrationsTest();migration.root=root;var env=migration.configuration(postgres).withProperty("ADMIN_PASSWORD",WorkflowSupport.PASSWORD);var h=new WorkflowSupport(){};h.client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
  try(var a=start(env);var b=start(env);var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
   h.environment=a.getEnvironment();h.store=a.getBean(Store.class);String csrf=h.login("admin"),token=cookie(h);assertEquals(36,token.length());String first=h.base();assertTrue(WorkflowSupport.html(h.get("/admin/operations",null)).contains("shared browser sessions"));var record=h.add("incident");String path="/api/now/table/incident/"+record.get("sys_id");h.environment=b.getEnvironment();String second=h.base();assertEquals(200,h.get("/account",null).statusCode());assertEquals(csrf,WorkflowSupport.csrf(h.get("/account",null)));assertEquals(403,h.json("PATCH",path,map("description","No CSRF"),null).statusCode());var write=HttpRequest.newBuilder(URI.create(second+path)).header("Content-Type","application/json").header("X-CSRF-Token",csrf).method("PATCH",HttpRequest.BodyPublishers.ofString("{\"description\":\"Updated on second instance\"}")).build();assertEquals(200,h.client.send(write,HttpResponse.BodyHandlers.ofByteArray()).statusCode());assertEquals(1,objects(WorkflowSupport.parsed(h.get("/api/openservice/me/sessions",null)).get("result")).size());
   context.addCookies(List.of(new Cookie("JSESSIONID",token).setDomain("localhost").setPath("/")));var page=context.newPage();page.navigate(first+"/account");assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Account security"))).isVisible();ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/shared-session-node-a-light.png")).setFullPage(true));page.navigate(second+"/account");ServiceOutagesBrowserAcceptance.click(page,"Dark mode");page.setViewportSize(390,844);ReleaseBrowserAcceptance.accessible(page);assertEquals(false,page.evaluate("document.documentElement.scrollWidth>window.innerWidth"));page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/shared-session-node-b-dark.png")).setFullPage(true));b.close();
   try(var c=start(env)){
    h.environment=c.getEnvironment();assertEquals(200,h.get("/account",null).statusCode());assertEquals(token,cookie(h));assertEquals("Updated on second instance",object(WorkflowSupport.parsed(h.get(path,null)).get("result")).get("description"));page.navigate(h.base()+"/account");ServiceOutagesBrowserAcceptance.click(page,"Dark mode");ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/shared-session-restarted-dark.png")).setFullPage(true));String cookieHeader=h.get("/login",null).headers().allValues("Set-Cookie").toString();assertFalse(cookieHeader.contains(token),"Reading an existing session does not replace its token");assertEquals(302,h.form("/logout",map("csrf",csrf),null).statusCode());h.environment=a.getEnvironment();assertEquals(401,h.get(path,null).statusCode());assertEquals(401,context.request().get(first+path).status());assertNull(a.getBean(SqlSessions.class).findById(token));
    h.login("admin");String current=cookie(h);try(var db=h.store.open()){db.update("DELETE FROM os_browser_session WHERE session_hash=?",Passwords.tokenHash(current));db.connection.commit();}h.environment=c.getEnvironment();assertEquals(401,h.get(path,null).statusCode());h.login("admin");try(var db=h.store.open()){db.update("UPDATE os_user SET auth_revision=auth_revision+1 WHERE name='admin'");db.connection.commit();}h.environment=a.getEnvironment();assertEquals(401,h.get(path,null).statusCode());h.login("admin");context.addCookies(List.of(new Cookie("JSESSIONID",cookie(h)).setDomain("localhost").setPath("/")));String third="http://localhost:"+c.getEnvironment().getProperty("local.server.port");assertEquals(200,context.request().get(third+path).status());assertEquals(302,h.form("/account/password",map("csrf",WorkflowSupport.csrf(h.get("/account",null)),"current_password",WorkflowSupport.PASSWORD,"new_password","shared-node-replacement-password-2026"),null).statusCode());assertEquals(401,context.request().get(third+path).status());
   }
  }finally{h.client.close();}
 }
}
