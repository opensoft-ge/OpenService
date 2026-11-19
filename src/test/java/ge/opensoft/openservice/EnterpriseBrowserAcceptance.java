package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.Path;
import java.util.*;

final class EnterpriseBrowserAcceptance {
 static Locator form(Page page,String operation){return page.locator("form").filter(new Locator.FilterOptions().setHas(page.locator("input[name=operation][value="+operation+"]")));}
 static void submit(Locator form){form.getByRole(AriaRole.BUTTON).press("Enter");}
 static void run(BrowserTest h)throws Exception{
  String project,hr,security,task,group;
  try(var db=h.store.open()){
   PlatformFoundationTest.bind(db);group=Service.create(db,"sys_user_group",map("short_description","Enterprise browser team"),"admin").get("sys_id").toString();
   project=Service.create(db,"project",map("short_description","Enterprise browser delivery","start_on","2027-01-01","finish_on","2027-12-31"),"admin").get("sys_id").toString();
   Service.createAccount(db,map("name","EnterpriseBrowserEmployee","role","agent","password",WorkflowSupport.PASSWORD));EmployeeServices.save(db,map("operation","employee","username","EnterpriseBrowserEmployee","department","Enterprise","active",true,"revision",0));var services=EmployeeServices.save(db,EmployeeCasesAcceptance.service("",0,group,"Enterprise"));String service=objects(services.get("services")).stream().filter(r->r.get("group_id").equals(group)).findFirst().orElseThrow().get("service_id").toString();String admin=db.viewerId;db.viewerId=RecordAccess.owner(db,"EnterpriseBrowserEmployee");db.viewerRole="agent";hr=EmployeeCases.create(db,service,map("employee_revision",1,"service_revision",1,"summary","Lifecycle browser case","description","Employee-visible request","key",id())).get("sys_id").toString();
   db.viewerId=admin;db.viewerRole="admin";db.update("INSERT INTO os_security_team(group_id,revision,active) VALUES(?,1,1)",group);security=SecurityIncidents.intake(db,SecurityIncidentsAcceptance.intake(group)).get("sys_id").toString();
   String parent=Service.create(db,"incident",map("short_description","Field browser scheduling"),"admin").get("sys_id").toString();WorkTasks.command(db,"incident",parent,map("operation","configure","revision",0,"record_revision",0,"close_at","6"));task=objects(WorkTasks.command(db,"incident",parent,map("operation","create","revision",1,"record_revision",1,"summary","Appointment","details","Technician inspection","group_id",group,"required",true,"predecessors",List.of())).get("tasks")).getFirst().get("task_id").toString();db.connection.commit();
  }
  try(var p=Playwright.create();var browser=p.chromium().launch();var context=browser.newContext()){
   var page=context.newPage();h.login(page,"admin");page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Enterprise workspaces").setExact(true)).press("Enter");ReleaseBrowserAcceptance.accessible(page);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Purchasing and software entitlements").setExact(true)).press("Enter");var order=form(page,"order");order.getByLabel("Supplier",new Locator.GetByLabelOptions().setExact(true)).fill("Supplier <script>alert(1)</script>");order.getByLabel("Asset model").fill("Laptop");order.getByLabel("Unit cost").fill("1000.00");submit(order);assertThat(page.locator("main")).containsText("Supplier <script>alert(1)</script>");assertThat(page.locator("main script")).hasCount(0);ReleaseBrowserAcceptance.accessible(page);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/enterprise-assets-light.png")).setFullPage(true));
   page.navigate(h.base()+"/record/project/"+project);page.locator("a[href='/enterprise/portfolio/"+project+"']").press("Enter");var budget=form(page,"budget");budget.getByLabel("Approved budget").fill("1000");budget.getByLabel("Estimated remaining cost").fill("250");submit(budget);assertThat(page.locator("main")).containsText("750.00");
   page.navigate(h.base()+"/record/hr_case/"+hr);page.locator("a[href='/enterprise/hr/"+hr+"']").press("Enter");submit(form(page,"documents"));assertThat(page.locator("main")).containsText("documents");try(var employeeContext=browser.newContext()){var employee=employeeContext.newPage();h.login(employee,"EnterpriseBrowserEmployee");employee.navigate(h.base()+"/employee-service/cases/"+hr+"/documents");assertThat(employee.locator("main")).containsText("No current documents");ReleaseBrowserAcceptance.accessible(employee);}
   page.navigate(h.base()+"/enterprise/security/"+security);form(page,"plan").getByLabel("Ordered steps: summary, details, group_id").fill(JSON.writeValueAsString(List.of(map("summary","Review browser evidence","details","Review manually","group_id",group))));submit(form(page,"plan"));form(page,"launch").getByLabel("Confirm these response steps").selectOption("true");submit(form(page,"launch"));assertThat(page.locator("main")).containsText("running");form(page,"stop").getByLabel("Stop reason").fill("Operator review changed");submit(form(page,"stop"));assertThat(page.locator("main")).containsText("stopped");
   page.navigate(h.base()+"/enterprise/field/"+task);form(page,"requirements").getByLabel("Required skills").fill("[\"network\"]");submit(form(page,"requirements"));assertThat(page.locator("main")).containsText("network");
   page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).press("Enter");page.setViewportSize(390,844);
   for(String route:List.of("studio/global","topology/global","architecture/global","assets/global","portfolio/"+project,"hr/"+hr,"security/"+security,"field/"+task,"field/technicians","integrations/global","agents/global")){page.navigate(h.base()+"/enterprise/"+route);assertThat(page.locator("main h1")).hasCount(1);ReleaseBrowserAcceptance.accessible(page);assertEquals(false,page.evaluate("document.documentElement.scrollWidth>window.innerWidth"),route);assertThat(page.locator("main script")).hasCount(0);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/enterprise-"+route.split("/")[0]+"-dark.png")).setFullPage(true));}
  }
 }
}
