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
 static void fill(Locator form,Map<String,Object> fields){
  fields.forEach((name,value)->form.locator("[name="+name+"]").fill(value.toString()));submit(form);
 }
 static void run(BrowserTest h)throws Exception{
  String project,hr,security,task,group,mined;
  try(var db=h.store.open()){
   PlatformFoundationTest.bind(db);group=Service.create(db,"sys_user_group",map("short_description","Enterprise browser team"),"admin").get("sys_id").toString();
   mined=Service.create(db,"incident",map("short_description","Browser cohort incident"),"admin").get("sys_id").toString();
   db.update("UPDATE os_event SET created='2024-01-01 00:00:00' WHERE record_id=? AND action='created'",mined);
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
   try(var db=h.store.open()){
       PlatformFoundationTest.bind(db);
       EmployeeAgreements.template(db,map("location","Browser office","body","{employee} transfers to {location}","days",30,"revision",0));
       EmployeeAgreements.refresh(db,hr,map("space","Browser office","effective",java.time.LocalDate.now().plusDays(3).toString(),"state","scheduled"));db.connection.commit();
   }
   try(var employeeContext=browser.newContext()){
       var employee=employeeContext.newPage();h.login(employee,"EnterpriseBrowserEmployee");employee.navigate(h.base()+"/employee-service/cases/"+hr);
       employee.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Review my employee agreement")).click();
       assertThat(employee.locator("main")).containsText("EnterpriseBrowserEmployee transfers to Browser office");
       employee.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Sign this agreement")).click();
       assertThat(employee.locator("main pre").first()).containsText("signed_by");
   }
   try(var provider=new RecordQuestionsAcceptance.Provider(h.store,h.store.environment)){
    String action=id(),policy=id();page.navigate(h.base()+"/enterprise/access/global");
    fill(form(page,"action"),map("action_id",action,"environment","test","kind","entra_add","alias",provider.alias,"oauth","alias","target",UUID.randomUUID().toString(),"argument",UUID.randomUUID().toString(),"expected","membership","expires","2099-02-01T00:00:00Z","revision",0));
    fill(form(page,"design"),map("policy_id",policy,"title","Production access","group_id",group,"action_id",action,"environment","test","days",7,"revision",0));
    fill(form(page,"simulate"),map("policy_id",policy,"revision",1));assertThat(page.locator("main")).containsText("Privileged access approval");
    fill(form(page,"activate"),map("policy_id",policy,"revision",1));assertThat(page.locator("main")).containsText("access-release");
   }
   String procedure=id(), design=id(), app=id();
   page.navigate(h.base()+"/enterprise/access/global");
   fill(form(page,"procedure"),map("procedure_id",procedure,"title","Legal approval","kind","team_approval","group_id",group,"revision",0));
   page.navigate(h.base()+"/enterprise/designer/global");
   fill(form(page,"path"),map("design_id",design,"title","Legal intake routing","row_id","general","condition","default","operand","any","days",3,
       "item_1",procedure,"stage_1",1,"revision",0));
   fill(form(page,"test"),map("design_id",design,"row_id","general","amount","10","risk","low","location","HQ","revision",1));
   fill(form(page,"publish"),map("design_id",design,"revision",2));
   page.navigate(h.base()+"/enterprise/apps/global");
   form(page,"build").locator("[name=visibility_1]").selectOption("confidential");
   fill(form(page,"build"),map("app_id",app,"title","Legal intake","group_id",group,"name_1","brief","label_1","Legal brief","design_id",design,"revision",0));
   try(var db=h.store.open()){
       PlatformFoundationTest.bind(db);
       Service.createAccount(db,map("name","BrowserIntakeRequester","role","requester","password",WorkflowSupport.PASSWORD));
       Service.createAccount(db,map("name","BrowserPromoter","role","admin","password",WorkflowSupport.PASSWORD));
       db.connection.commit();
   }
   try(var other=browser.newContext()){
       var promoter=other.newPage();h.login(promoter,"BrowserPromoter");
       promoter.navigate(h.base()+"/enterprise/apps/global");fill(form(promoter,"promote"),map("app_id",app,"revision",1,"release_revision",0));
   }
   try(var requesterContext=browser.newContext()){
       var requester=requesterContext.newPage();
       requester.navigate(h.base()+"/login");
       requester.getByLabel("Username",new Page.GetByLabelOptions().setExact(true)).fill("BrowserIntakeRequester");
       requester.getByLabel("Password",new Page.GetByLabelOptions().setExact(true)).fill(WorkflowSupport.PASSWORD);
       requester.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Enter your workspace")).click();
       requester.waitForURL("**/requests");
       requester.navigate(h.base()+"/enterprise/apps/global");
       requester.locator("a[href='/intake/"+app+"']").first().press("Enter");
       ReleaseBrowserAcceptance.accessible(requester);
       requester.getByLabel("Legal brief").fill("Review supplier terms");requester.getByLabel("Location").fill("HQ");
       requester.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Submit request")).press("Enter");
       assertThat(requester.locator("main")).containsText("Request submitted");assertThat(requester.locator("main")).not().containsText("Review supplier terms");
   }
   page.navigate(h.base()+"/enterprise/mining/global");
   fill(form(page,"compare"),map("table","incident","query","short_description=Browser cohort incident","before_start","2024-01-01","after_start","2024-02-01","days",1,"hours",24,"completed_state","6"));
   assertThat(page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Case cohorts"))).isVisible();
   page.getByText("before case histories",new Page.GetByTextOptions().setExact(true)).press("Enter");
   var example=page.locator("a[href='/record/incident/"+mined+"']");assertThat(example).isVisible();
   example.press("Enter");assertThat(page.locator("main")).containsText("Browser cohort incident");
   page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Dark mode").setExact(true)).press("Enter");page.setViewportSize(390,844);
   for(String route:List.of("designer/global","apps/global","packages/global","suppliers/global","continuity/global","releases/global","mining/global","access/global","duty/global","mapping/global","sales/global","routing/global","finance/global","workplace/global","audits/global","desk/global","studio/global","topology/global","architecture/global","assets/global","portfolio/"+project,"hr/"+hr,"security/"+security,"field/"+task,"field/technicians","integrations/global","agents/global")){assertEquals(200,page.navigate(h.base()+"/enterprise/"+route).status(),route);assertThat(page.locator("main h1")).hasCount(1);ReleaseBrowserAcceptance.accessible(page);assertEquals(false,page.evaluate("document.documentElement.scrollWidth>window.innerWidth"),route);assertThat(page.locator("main script")).hasCount(0);page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/enterprise-"+route.split("/")[0]+"-dark.png")).setFullPage(true));}
  }
 }
}
