package ge.opensoft.openservice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

class MigrationsTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void demandUpgradeRetainsPlanning(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String retained,record,portfolio;
        try(var db=store.open()){PlatformFoundationTest.bind(db);retained=Service.create(db,"incident",Values.map("short_description","Retained before demand planning"),"admin").get("sys_id").toString();db.update("DROP TABLE os_demand_assessment");db.update("DELETE FROM os_schema WHERE name IN ('portfolio','demand')");db.update("DELETE FROM os_migration WHERE version>=69");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_demand_assessment"));db.connection.rollback();assertEquals(68,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);portfolio=Service.create(db,"portfolio",Values.map("short_description","Retained investment portfolio"),"admin").get("sys_id").toString();record=Service.create(db,"demand",Values.map("short_description","Retained assessed demand","description","Retained business case"),"admin").get("sys_id").toString();DemandPlanning.save(db,record,Values.map("operation","assess","revision",0,"record_revision",0,"portfolio_id",portfolio,"year",2027,"value",5,"alignment",4,"urgency",3,"effort",2,"rationale","Retained rationale"));Service.update(db,"demand",Service.record(db,"demand",record),Values.map("state","screen"),"admin","admin",null);Service.update(db,"demand",Service.record(db,"demand",record),Values.map("state","qualified"),"admin","admin",null);DemandPlanning.save(db,record,Values.map("operation","decide","revision",1,"record_revision",3,"decision","deferred","rationale","Retained deferral rationale"));db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);assertEquals("Retained before demand planning",Values.object(Service.readRecord(db,"incident",retained).get("data")).get("short_description"));var page=DemandPlanning.page(db,record);assertEquals("deferred",Values.object(page.get("record")).get("state"));assertEquals(2,Values.objects(page.get("history")).size());assertEquals(true,page.get("current"));assertEquals(84,Values.object(page.get("assessment")).get("score"));assertEquals(0,Values.objects(DemandPlanning.board(db,portfolio,2027).get("items")).getFirst().get("rank"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void vulnerabilityUpgradeRetainsVerification(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String retained,record,asset,group;
        try(var db=store.open()){PlatformFoundationTest.bind(db);retained=Service.create(db,"incident",Values.map("short_description","Retained before vulnerability upgrade"),"admin").get("sys_id").toString();for(String table:List.of("os_vulnerability_observation","os_vulnerability","os_vulnerability_scan"))db.update("DROP TABLE "+table);db.update("DELETE FROM os_schema WHERE name='vulnerability'");db.update("DELETE FROM os_migration WHERE version>=68");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_vulnerability"));db.connection.rollback();assertEquals(67,db.count("SELECT MAX(version) FROM os_migration"));}
        var upgraded=new Store(env);try(var endpoint=new VulnerabilityResponseAcceptance.Endpoint(upgraded,env)){
            try(var db=upgraded.open()){PlatformFoundationTest.bind(db);group=Service.create(db,"sys_user_group",Values.map("short_description","Retained vulnerability response team"),"admin").get("sys_id").toString();asset=Service.create(db,"cmdb_ci",Values.map("short_description","Retained affected server"),"admin").get("sys_id").toString();SecurityIncidents.enroll(db,Values.map("group_id",group,"revision",0,"active",true));endpoint.payload.set(Values.JSON.writeValueAsString(VulnerabilityResponseAcceptance.report(1,List.of(VulnerabilityResponseAcceptance.entry(asset,"UPGRADE-42","present")))));var source=Values.map("alias",endpoint.provider.alias,"path","/scan","review",ConnectorConsole.page(db,endpoint.provider.alias).get("review"),"group_id",group);VulnerabilityResponse.ingest(upgraded,db,source);record=db.one("SELECT record_id FROM os_vulnerability WHERE alias=?",endpoint.provider.alias).get("record_id").toString();Service.update(db,"vulnerability",Service.record(db,"vulnerability",record),Values.map("state","remediate"),"admin","admin",null);WorkTasks.command(db,"vulnerability",record,Values.map("operation","create","revision",1,"record_revision",1,"summary","Retained remediation","details","Apply the reviewed component update","group_id",group,"required",true,"predecessors",List.of()));String task=Values.objects(WorkTasks.page(db,"vulnerability",record).get("tasks")).getFirst().get("task_id").toString();WorkTasks.command(db,"vulnerability",record,Values.map("operation","claim","revision",0,"record_revision",2,"task_id",task));WorkTasks.command(db,"vulnerability",record,Values.map("operation","complete","revision",1,"record_revision",2,"task_id",task,"outcome","Retained remediation outcome"));Service.update(db,"vulnerability",Service.record(db,"vulnerability",record),Values.map("state","verify"),"admin","admin",null);endpoint.payload.set(Values.JSON.writeValueAsString(VulnerabilityResponseAcceptance.report(2,List.of(VulnerabilityResponseAcceptance.entry(asset,"UPGRADE-42","fixed")))));VulnerabilityResponse.ingest(upgraded,db,source);db.connection.commit();}
            for(int n=0;n<2;n++)try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);assertEquals("Retained before vulnerability upgrade",Values.object(Service.readRecord(db,"incident",retained).get("data")).get("short_description"));var page=VulnerabilityResponse.page(db,record);assertEquals("closed",Values.object(page.get("record")).get("state"));assertEquals(2,Values.objects(page.get("history")).size());assertEquals("fixed",Values.object(Values.object(page.get("latest")).get("detail")).get("status"));assertEquals(asset,RecordScopes.scope(db,record).get("parent_id"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void securityUpgradeRetainsRestrictedEvidence(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String retained,incident,group,file=Values.id();
        try(var db=store.open()){PlatformFoundationTest.bind(db);retained=Service.create(db,"incident",Values.map("short_description","Retained ordinary incident"),"admin").get("sys_id").toString();for(String table:List.of("os_security_document","os_security_case","os_security_team"))db.update("DROP TABLE "+table);db.update("DELETE FROM os_schema WHERE name='security_incident'");db.update("DELETE FROM os_migration WHERE version>=67");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_security_document"));db.connection.rollback();assertEquals(66,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);group=Service.create(db,"sys_user_group",Values.map("short_description","Upgrade security team"),"admin").get("sys_id").toString();SecurityIncidents.enroll(db,Values.map("group_id",group,"revision",0,"active",true));incident=SecurityIncidents.intake(db,Values.map("group_id",group,"summary","Retained security incident","description","Restricted retained details","key",Values.id())).get("sys_id").toString();db.update("INSERT INTO os_attachment(sys_id,record_id,name,content_type,content) VALUES(?,?,?,?,?)",file,incident,"security-proof.txt","text/plain","Retained evidence".getBytes());SecurityIncidents.document(db,incident,Values.map("operation","analyze","revision",0,"record_revision",0,"classification","false_positive","findings","Retained investigated evidence","assets",List.of(),"evidence",List.of(file)));Service.update(db,"security_incident",Service.record(db,"security_incident",incident),Values.map("state","review"),"admin","admin","1");SecurityIncidents.document(db,incident,Values.map("operation","review","revision",1,"record_revision",2,"code","false_positive","notes","Retained closure review"));db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);assertEquals("Retained ordinary incident",Values.object(Service.readRecord(db,"incident",retained).get("data")).get("short_description"));var page=SecurityIncidents.page(db,incident);assertEquals(2,Values.objects(page.get("history")).size());assertEquals(true,page.get("current"));assertEquals("Retained closure review",Values.object(Values.object(page.get("document")).get("closure")).get("notes"));assertEquals(true,Values.objects(Values.object(page.get("document")).get("evidence")).getFirst().get("available"));assertEquals(1,SecurityIncidents.team(db,group).get("active"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void riskAssessmentUpgradeRetainsHistory(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String retained,risk;
        try(var db=store.open()){PlatformFoundationTest.bind(db);Service.createAccount(db,Values.map("name","UpgradeRiskOwner","role","agent","password",WorkflowSupport.PASSWORD));retained=Service.create(db,"incident",Values.map("short_description","Retained incident before risk upgrade"),"admin").get("sys_id").toString();db.update("DROP TABLE os_risk_assessment");db.update("DELETE FROM os_schema WHERE name='risk'");db.update("DELETE FROM os_migration WHERE version>=66");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_risk_assessment"));db.connection.rollback();assertEquals(65,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);risk=Service.create(db,"risk",Values.map("short_description","Retained supply risk"),"admin").get("sys_id").toString();var body=Values.map("revision",0,"record_revision",0,"likelihood",5,"impact",5,"residual_likelihood",2,"residual_impact",2,"response","accept","rationale","Retained assessment rationale","treatment","Retained acceptance plan","owner","UpgradeRiskOwner","review_on","2030-12-31");RiskAssessments.save(db,risk,body);body.put("revision",1);body.put("record_revision",1);body.put("residual_impact",1);RiskAssessments.save(db,risk,body);db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);assertEquals("Retained incident before risk upgrade",Values.object(Service.readRecord(db,"incident",retained).get("data")).get("short_description"));var page=RiskAssessments.page(db,risk);assertEquals(2,Values.objects(page.get("history")).size());assertEquals(25,Values.object(page.get("assessment")).get("score"));assertEquals(2,Values.object(page.get("assessment")).get("residual_score"));assertEquals(4,Values.object(Values.objects(page.get("history")).getLast().get("detail")).get("residual_score"));assertEquals(true,page.get("current"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void onboardingUpgradeRetainsPrivateDepartmentProgress(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String parent,group,child,task;
        try(var db=store.open()){PlatformFoundationTest.bind(db);group=Service.create(db,"sys_user_group",Values.map("short_description","Upgrade onboarding department"),"admin").get("sys_id").toString();EmployeeServices.save(db,Values.map("operation","employee","username","admin","department","Engineering","active",true,"revision",0));String service=Values.objects(EmployeeServices.save(db,EmployeeCasesAcceptance.service("",0,group,"Engineering")).get("services")).getFirst().get("service_id").toString();parent=EmployeeCases.create(db,service,Values.map("employee_revision",1,"service_revision",1,"summary","Retained onboarding case","description","PRIVATE retained HR details","key",Values.id())).get("sys_id").toString();for(String table:List.of("os_hr_onboarding_work","os_hr_onboarding"))db.update("DROP TABLE "+table);db.update("DELETE FROM os_migration WHERE version>=65");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_hr_onboarding"));db.connection.rollback();assertEquals(64,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);EmployeeOnboarding.command(db,parent,Values.map("operation","add","revision",0,"record_revision",0,"display_title","Your equipment","summary","Prepare equipment","details","DEPARTMENT approved instructions","group_id",group));EmployeeOnboarding.command(db,parent,Values.map("operation","launch","revision",1,"record_revision",1));var work=db.one("SELECT child_id,task_id FROM os_hr_onboarding_work WHERE parent_id=?",parent);child=work.get("child_id").toString();task=work.get("task_id").toString();WorkTasks.command(db,"sc_request",child,Values.map("operation","claim","revision",0,"record_revision",0,"task_id",task));WorkTasks.command(db,"sc_request",child,Values.map("operation","complete","revision",1,"record_revision",1,"task_id",task,"outcome","DEPARTMENT completion detail"));db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);var progress=EmployeeOnboarding.progress(db,parent);assertEquals("complete",progress.getFirst().get("status"));assertEquals("Your equipment",progress.getFirst().get("display_title"));assertFalse(Values.JSON.writeValueAsString(EmployeeCases.page(db,parent,true)).contains("DEPARTMENT"));assertFalse(Values.JSON.writeValueAsString(Service.readRecord(db,"sc_request",child)).contains("PRIVATE"));assertEquals("complete",Values.object(Service.readRecord(db,"sc_request",child).get("data")).get("state"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void employeeServiceUpgradeRetainsPrivateConversation(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String user,group,id;
        try(var db=store.open()){PlatformFoundationTest.bind(db);Service.createAccount(db,Values.map("name","UpgradeEmployee","role","requester","password",WorkflowSupport.PASSWORD));user=RecordAccess.owner(db,"UpgradeEmployee");group=Service.create(db,"sys_user_group",Values.map("short_description","Retained HR team"),"admin").get("sys_id").toString();for(String table:List.of("os_hr_reply","os_hr_case","os_hr_service","os_employee"))db.update("DROP TABLE "+table);db.update("DELETE FROM os_schema WHERE name='hr_case'");db.update("DELETE FROM os_migration WHERE version>=64");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_hr_case"));db.connection.rollback();assertEquals(63,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);EmployeeServices.save(db,Values.map("operation","employee","username","UpgradeEmployee","department","Engineering","active",true,"revision",0));String service=Values.objects(EmployeeServices.save(db,EmployeeCasesAcceptance.service("",0,group,"Engineering")).get("services")).getFirst().get("service_id").toString();db.viewerId=user;db.viewerRole="requester";id=EmployeeCases.create(db,service,Values.map("employee_revision",1,"service_revision",1,"summary","Retained employee case","description","Retained private details","key",Values.id())).get("sys_id").toString();EmployeeCases.publish(db,id,true,Values.map("revision",0,"record_revision",0,"key",Values.id(),"body","Retained employee message"));PlatformFoundationTest.bind(db);Service.audit(db,Service.record(db,"hr_case",id),"admin","note",Values.map("body","Retained HR internal note"));db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){db.viewerId=user;db.viewerRole="requester";var page=EmployeeCases.page(db,id,true);assertEquals("Retained employee message",Values.objects(page.get("messages")).getFirst().get("body"));assertFalse(Values.JSON.writeValueAsString(page).contains("HR internal"));assertEquals(404,assertThrows(Values.Failure.class,()->Service.readRecord(db,"hr_case",id)).status);assertEquals(1,((Number)EmployeeCases.portal(db,Map.of()).get("total")).intValue());assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }



    @ParameterizedTest @ValueSource(booleans={false,true})
    void fieldVisitUpgradeRetainsCloseoutEvidence(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String parent,task,user,file;
        try(var db=store.open()){String admin=PlatformFoundationTest.bind(db);Service.createAccount(db,Values.map("name","UpgradeVisitAgent","role","agent","password",WorkflowSupport.PASSWORD));user=RecordAccess.owner(db,"UpgradeVisitAgent");String group=Service.create(db,"sys_user_group",Values.map("short_description","Visit upgrade team"),"admin").get("sys_id").toString();db.update("INSERT INTO os_group_member(group_id,user_id) VALUES(?,?)",group,user);parent=Service.create(db,"incident",Values.map("short_description","Retained visit case"),"admin").get("sys_id").toString();WorkTasks.command(db,"incident",parent,Values.map("operation","configure","revision",0,"record_revision",0,"close_at","6"));task=Values.objects(WorkTasks.command(db,"incident",parent,Values.map("operation","create","revision",1,"record_revision",1,"summary","Retained visit task","details","Visit and verify","group_id",group,"required",true,"predecessors",List.of())).get("tasks")).getFirst().get("task_id").toString();ServiceClocks.calendar(db,admin,Values.map("name","UpgradeVisitDay","calendar",Values.map("zone","UTC","start","08:00","end","18:00","days",List.of(1,2,3,4,5),"holidays",List.of())));FieldAvailability.save(db,Values.map("username","UpgradeVisitAgent","calendar_id","UpgradeVisitDay","active",true,"revision",0));FieldDispatch.command(db,"incident",parent,task,Values.map("operation","assign","revision",0,"task_revision",0,"record_revision",2,"technician_id",user,"start","2030-01-07T09:00:00Z","end","2030-01-07T10:00:00Z","location","Customer site"));db.viewerId=user;db.viewerRole="agent";FieldDispatch.command(db,"incident",parent,task,Values.map("operation","accept","revision",1,"task_revision",1,"record_revision",3,"reason","Ready for visit"));file=Values.id();db.update("INSERT INTO os_attachment(sys_id,record_id,name,content,content_type) VALUES(?,?,?,?,?)",file,parent,"upgrade-proof.txt","Retained evidence".getBytes(),"text/plain");db.update("DROP TABLE os_field_visit");db.update("DELETE FROM os_migration WHERE version>=63");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_field_visit"));db.connection.rollback();assertEquals(62,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);FieldVisits.command(db,"incident",parent,task,Values.map("operation","configure","revision",0,"task_revision",2,"record_revision",3,"checklist",List.of("Verify the result"),"evidence_required",true));db.viewerId=user;db.viewerRole="agent";FieldVisits.command(db,"incident",parent,task,Values.map("operation","checkin","revision",1,"task_revision",2,"record_revision",4));FieldVisits.command(db,"incident",parent,task,Values.map("operation","complete","revision",2,"task_revision",2,"record_revision",5,"notes","Verified after upgrade","answers",List.of(1),"evidence",List.of(file),"parts_reviewed",true));db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){db.viewerId=user;db.viewerRole="agent";var page=FieldVisits.page(db,"incident",parent,task,Map.of());assertEquals("complete",Values.object(page.get("visit")).get("phase"));assertEquals("Verified after upgrade",Values.object(page.get("visit")).get("notes"));assertEquals(true,Values.objects(page.get("evidence")).getFirst().get("available"));assertEquals("complete",Values.object(Values.object(page.get("work")).get("task")).get("state"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void fieldDispatchUpgradeRetainsReservations(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String parent,task,user;
        try(var db=store.open()){String admin=PlatformFoundationTest.bind(db);Service.createAccount(db,Values.map("name","UpgradeFieldAgent","role","agent","password",WorkflowSupport.PASSWORD));user=RecordAccess.owner(db,"UpgradeFieldAgent");String group=Service.create(db,"sys_user_group",Values.map("short_description","Field upgrade team"),"admin").get("sys_id").toString();db.update("INSERT INTO os_group_member(group_id,user_id) VALUES(?,?)",group,user);parent=Service.create(db,"incident",Values.map("short_description","Retained field case"),"admin").get("sys_id").toString();WorkTasks.command(db,"incident",parent,Values.map("operation","configure","revision",0,"record_revision",0,"close_at","6"));task=Values.objects(WorkTasks.command(db,"incident",parent,Values.map("operation","create","revision",1,"record_revision",1,"summary","Retained field task","details","Visit the customer","group_id",group,"required",true,"predecessors",List.of())).get("tasks")).getFirst().get("task_id").toString();ServiceClocks.calendar(db,admin,Values.map("name","UpgradeFieldDay","calendar",Values.map("zone","UTC","start","08:00","end","18:00","days",List.of(1,2,3,4,5),"holidays",List.of())));db.update("DROP TABLE os_field_dispatch");db.update("DROP TABLE os_field_availability");db.update("DELETE FROM os_migration WHERE version>=62");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_field_dispatch"));db.connection.rollback();assertEquals(61,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);FieldAvailability.save(db,Values.map("username","UpgradeFieldAgent","calendar_id","UpgradeFieldDay","active",true,"revision",0));FieldDispatch.command(db,"incident",parent,task,Values.map("operation","assign","revision",0,"task_revision",0,"record_revision",2,"technician_id",user,"start","2030-01-07T09:00:00Z","end","2030-01-07T10:00:00Z","location","Retained customer site"));db.viewerId=user;db.viewerRole="agent";FieldDispatch.command(db,"incident",parent,task,Values.map("operation","accept","revision",1,"task_revision",1,"record_revision",3,"reason","Ready for visit"));db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){db.viewerId=user;db.viewerRole="agent";var page=FieldDispatch.page(db,"incident",parent,task);assertEquals("accepted",Values.object(page.get("dispatch")).get("status"));assertEquals("working",Values.object(page.get("task")).get("state"));assertEquals("Ready for visit",Values.object(page.get("dispatch")).get("reason"));assertEquals(true,page.get("eligible"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }


    @ParameterizedTest @ValueSource(booleans={false,true})
    void customerResolutionUpgradeRetainsReviewedDecisions(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String record,user;
        try(var db=store.open()){PlatformFoundationTest.bind(db);Service.createAccount(db,Values.map("name","UpgradeSolutionCustomer","role","requester","password",WorkflowSupport.PASSWORD));user=RecordAccess.owner(db,"UpgradeSolutionCustomer");String group=Service.create(db,"sys_user_group",Values.map("short_description","Resolution upgrade support"),"admin").get("sys_id").toString();String account=CustomerAccounts.save(db,"",Values.map("name","Resolution upgrade customer","group_id",group,"active",true,"revision",0)).get("account_id").toString();CustomerAccounts.manage(db,account,Values.map("operation","contact","username","UpgradeSolutionCustomer","active",true,"revision",0));String entitlement=Values.objects(CustomerAccounts.manage(db,account,CustomerCasesAcceptance.entitlement("",0,0)).get("entitlements")).getFirst().get("entitlement_id").toString();db.viewerId=user;db.viewerRole="requester";var item=CustomerCases.create(db,entitlement,Values.map("account_revision",1,"contact_revision",1,"entitlement_revision",1,"summary","Retained resolution case","description","Retained problem","key",Values.id()));record=item.get("sys_id").toString();PlatformFoundationTest.bind(db);Service.update(db,"customer_case",item,Values.map("state","working"),"admin","admin",null);db.update("DROP TABLE os_customer_resolution");db.update("DELETE FROM os_migration WHERE version>=61");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_customer_resolution"));db.connection.rollback();assertEquals(60,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);CustomerResolutions.command(db,record,Values.map("operation","propose","revision",0,"record_revision",1,"code","solved","solution","Retained proposed solution"));db.viewerId=user;db.viewerRole="requester";CustomerResolutions.command(db,record,Values.map("operation","accept","revision",1,"record_revision",2,"reason","Customer verified the result"));db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){db.viewerId=user;db.viewerRole="requester";var page=CustomerResolutions.page(db,record);assertEquals("closed",Values.object(page.get("record")).get("state"));assertEquals(true,page.get("current"));assertEquals("accepted",Values.object(page.get("resolution")).get("status"));assertEquals(2,Values.object(page.get("resolution")).get("revision"));assertEquals("Customer verified the result",Values.objects(page.get("history")).getFirst().get("reason"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void customerReplyUpgradeRetainsPublicTextAndDelivery(boolean postgres)throws Exception{
        var env=configuration(postgres).withProperty("DELIVERY_ENABLED","true").withProperty("PUBLIC_BASE_URL","https://example.invalid");var store=new Store(env);String record,user;
        try(var db=store.open()){PlatformFoundationTest.bind(db);Service.createAccount(db,Values.map("name","UpgradeReplyCustomer","role","requester","password",WorkflowSupport.PASSWORD));user=RecordAccess.owner(db,"UpgradeReplyCustomer");db.update("UPDATE os_user SET email='upgrade@example.invalid',email_verified=1 WHERE user_id=?",user);String group=Service.create(db,"sys_user_group",Values.map("short_description","Upgrade support"),"admin").get("sys_id").toString();String account=CustomerAccounts.save(db,"",Values.map("name","Upgrade reply organization","group_id",group,"active",true,"revision",0)).get("account_id").toString();CustomerAccounts.manage(db,account,Values.map("operation","contact","username","UpgradeReplyCustomer","active",true,"revision",0));String entitlement=Values.objects(CustomerAccounts.manage(db,account,CustomerCasesAcceptance.entitlement("",0,0)).get("entitlements")).getFirst().get("entitlement_id").toString();db.viewerId=user;db.viewerRole="requester";record=CustomerCases.create(db,entitlement,Values.map("account_revision",1,"contact_revision",1,"entitlement_revision",1,"summary","Retained case","description","Retained problem","key",Values.id())).get("sys_id").toString();db.update("DROP TABLE os_customer_reply");db.update("DROP TABLE os_customer_delivery");db.update("DELETE FROM os_migration WHERE version>=60");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_customer_reply"));db.connection.rollback();assertEquals(59,db.count("SELECT MAX(version) FROM os_migration"));}
        var submission=Values.map("record_revision",0,"key",Values.id(),"body","Retained public response","email",true);
        store=new Store(env);try(var db=store.open()){db.viewerId=user;db.viewerRole="requester";CustomerReplies.preference(db,Values.map("revision",0,"enabled",true));PlatformFoundationTest.bind(db);CustomerReplies.publish(store,db,record,submission);db.connection.commit();}
        for(int n=0;n<2;n++){store=new Store(env);try(var db=store.open()){PlatformFoundationTest.bind(db);var replies=Values.objects(CustomerReplies.publish(store,db,record,submission).get("replies"));assertEquals(1,replies.size());assertEquals("Retained public response",replies.getFirst().get("body"));assertEquals("queued",replies.getFirst().get("delivery"));var job=db.one("SELECT envelope FROM os_outbox WHERE kind='email.customer_reply'");assertTrue(CustomerReplies.valid(db,Outbox.decrypt(store.secret,job.get("envelope").toString())));db.viewerId=user;db.viewerRole="requester";assertEquals(1,CustomerReplies.preference(db).get("enabled"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void customerIntakeUpgradePreservesOwnershipAndUnits(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String group,user;
        try(var db=store.open()){PlatformFoundationTest.bind(db);group=Service.create(db,"sys_user_group",Values.map("short_description","Retained support team"),"admin").get("sys_id").toString();Service.createAccount(db,Values.map("name","UpgradeCustomer","role","requester","password",WorkflowSupport.PASSWORD));user=RecordAccess.owner(db,"UpgradeCustomer");for(String table:List.of("os_customer_case","os_customer_entitlement","os_customer_contact","os_customer_account"))db.update("DROP TABLE "+table);db.update("DELETE FROM os_schema WHERE name='customer_case'");db.update("DELETE FROM os_migration WHERE version>=59");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_customer_account"));db.connection.rollback();assertEquals(58,db.count("SELECT MAX(version) FROM os_migration"));}
        String record,account,entitlement;Map<String,Object> body;
        try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);account=CustomerAccounts.save(db,"",Values.map("name","Retained customer","group_id",group,"active",true,"revision",0)).get("account_id").toString();CustomerAccounts.manage(db,account,Values.map("operation","contact","username","UpgradeCustomer","active",true,"revision",0));var p=CustomerAccounts.manage(db,account,CustomerCasesAcceptance.entitlement("",0,1));entitlement=Values.objects(p.get("entitlements")).getFirst().get("entitlement_id").toString();db.viewerId=user;db.viewerRole="requester";body=Values.map("account_revision",1,"contact_revision",1,"entitlement_revision",1,"summary","Retained support case","description","Original customer details","key",Values.id());record=CustomerCases.create(db,entitlement,body).get("sys_id").toString();db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){db.viewerId=user;db.viewerRole="requester";assertEquals(record,CustomerCases.create(db,entitlement,body).get("sys_id"));assertEquals("Retained customer",CustomerCases.snapshot(db,record).get("account_name"));assertEquals(1L,db.count("SELECT COUNT(*) FROM os_customer_case WHERE entitlement_id=?",entitlement));assertEquals(404,assertThrows(Values.Failure.class,()->CustomerEntitlements.eligible(db,entitlement,System.currentTimeMillis())).status);assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void majorIncidentUpgradeRetainsCommandAndChronology(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String id,child;
        try(var db=store.open()){PlatformFoundationTest.bind(db);id=Service.create(db,"incident",Values.map("short_description","Retained incident"),"admin").get("sys_id").toString();child=Service.create(db,"incident",Values.map("short_description","Retained related incident"),"admin").get("sys_id").toString();db.update("DROP TABLE os_major_link");db.update("DROP TABLE os_major_update");db.update("DROP TABLE os_major_incident");db.update("DELETE FROM os_migration WHERE version>=58");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_major_incident"));db.connection.rollback();assertEquals(57,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);MajorIncidents.command(db,id,Values.map("operation","propose","revision",0,"record_revision",0,"reason","Retained proposal","impact","Retained impact"));MajorIncidents.command(db,id,Values.map("operation","accept","revision",1,"record_revision",1,"reason","Confirmed","impact","Retained impact","commander","admin"));MajorIncidents.command(db,id,Values.map("operation","link","revision",2,"record_revision",2,"child_id",child,"child_revision",0));MajorIncidents.command(db,id,Values.map("operation","publish","revision",3,"record_revision",3,"message","Recovery underway"));db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);var page=MajorIncidents.page(db,id,Map.of());assertEquals("declared",Values.object(page.get("major")).get("status"));assertEquals("2",Values.object(page.get("incident")).get("state"));assertEquals(4,Values.object(page.get("major")).get("revision"));assertEquals(child,Values.objects(page.get("children")).getFirst().get("sys_id"));assertEquals("Recovery underway",Values.objects(page.get("updates")).getFirst().get("message"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void changeScheduleUpgradePreservesUnscheduledPlanReadiness(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String ci,change;
        try(var db=store.open()){PlatformFoundationTest.bind(db);ci=Service.create(db,"cmdb_ci",Values.map("short_description","Retained configuration item"),"admin").get("sys_id").toString();change=Service.create(db,"change_request",Values.map("short_description","Retained reviewed plan"),"admin").get("sys_id").toString();ChangePlanning.save(db,change,ChangeSchedulingBrowserAcceptance.plan());db.update("DROP TABLE os_change_schedule");db.update("DROP TABLE os_change_window");db.update("DELETE FROM os_migration WHERE version>=57");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_change_schedule"));db.connection.rollback();assertEquals(56,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);assertEquals(true,ChangePlanning.detail(db,change).get("current"));ChangeScheduling.save(db,change,Values.map("operation","save","revision",0,"record_revision",1,"ci_id",ci,"start","2026-11-18T09:00:00Z","end","2026-11-18T10:00:00Z"));assertEquals(false,ChangePlanning.detail(db,change).get("current"));var plan=ChangeSchedulingBrowserAcceptance.plan();plan.put("revision",1);plan.put("record_revision",2);ChangePlanning.save(db,change,plan);db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);assertEquals(true,ChangePlanning.detail(db,change).get("current"));assertEquals(ci,ChangeScheduling.schedule(db,change).get("ci_id"));assertEquals(false,Values.object(ChangeScheduling.page(db,change,Map.of()).get("findings")).get("blocked"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void catalogueWorkflowUpgradeRetainsReviewedPlans(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String offering,group;
        try(var db=store.open()){PlatformFoundationTest.bind(db);offering=Catalogue.save(db,db.viewerId,"",CatalogueAcceptance.offering("Retained offering",0,true)).get("offering_id").toString();group=Service.create(db,"sys_user_group",Values.map("short_description","Retained team"),"admin").get("sys_id").toString();db.update("DROP TABLE os_catalog_workflow_order");db.update("DROP TABLE os_catalog_workflow");db.update("DELETE FROM os_migration WHERE version>=56");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_catalog_workflow"));db.connection.rollback();assertEquals(55,db.count("SELECT MAX(version) FROM os_migration"));}
        String request;
        try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);CatalogueWorkflows.save(db,offering,Values.map("operation","add","revision",1,"summary","Retained step","details","Original instructions","group_id",group));CatalogueWorkflows.save(db,offering,Values.map("operation","enable","revision",2));request=Catalogue.order(db,offering,Values.map("revision",3,"details","Upgrade request","key",Values.id())).get("sys_id").toString();db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);assertEquals(1,Values.object(CatalogueWorkflows.settings(db,offering).get("workflow")).get("enabled"));assertEquals(1L,((Number)CatalogueWorkflows.progress(db,request).get("remaining")).longValue());assertTrue(db.one("SELECT steps FROM os_catalog_workflow_order WHERE request_id=?",request).get("steps").toString().contains("Original instructions"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void workTaskUpgradeRetainsAssignmentsAndDependencies(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String parent,group;
        try(var db=store.open()){PlatformFoundationTest.bind(db);parent=Service.create(db,"incident",Values.map("short_description","Retained case"),"admin").get("sys_id").toString();group=Service.create(db,"sys_user_group",Values.map("short_description","Retained team"),"admin").get("sys_id").toString();db.update("DROP TABLE os_field_visit");db.update("DROP TABLE os_field_dispatch");db.update("DROP TABLE os_project_schedule");db.update("DROP TABLE os_case_task");db.update("DROP TABLE os_task_plan");db.update("DELETE FROM os_migration WHERE version>=55");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_case_task"));db.connection.rollback();assertEquals(54,db.count("SELECT MAX(version) FROM os_migration"));}
        String first;
        try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);WorkTasks.command(db,"incident",parent,Values.map("operation","configure","revision",0,"record_revision",0,"close_at","6"));var result=WorkTasks.command(db,"incident",parent,Values.map("operation","create","revision",1,"record_revision",1,"summary","First task","details","Instructions","group_id",group,"required",true,"predecessors",List.of()));first=Values.objects(result.get("tasks")).getFirst().get("task_id").toString();WorkTasks.command(db,"incident",parent,Values.map("operation","create","revision",2,"record_revision",2,"summary","Successor","details","Verify","group_id",group,"required",true,"predecessors",List.of(first)));db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);var page=WorkTasks.page(db,"incident",parent);assertEquals(3,Values.object(page.get("plan")).get("revision"));var tasks=Values.objects(page.get("tasks"));assertEquals(2,tasks.size());assertEquals(List.of(first),tasks.getLast().get("predecessors"));assertEquals(false,tasks.getLast().get("ready"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void parentScopeUpgradePreservesRecordsAndBoundaries(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String parent,child;
        try(var db=store.open()){PlatformFoundationTest.bind(db);parent=Service.create(db,"problem",Values.map("short_description","Retained parent"),"admin").get("sys_id").toString();child=Service.create(db,"incident",Values.map("short_description","Retained child"),"admin").get("sys_id").toString();db.update("DROP TABLE os_record_scope");db.update("DELETE FROM os_migration WHERE version>=54");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_record_scope"));db.connection.rollback();assertEquals(53,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);RecordScopes.save(db,"incident",child,Values.map("operation","attach","revision",0,"record_revision",0,"parent_table","problem","parent_id",parent,"parent_revision",0));db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);var v=RecordScopes.view(db,"incident",child,Map.of());assertEquals(parent,Values.object(v.get("parent")).get("sys_id"));assertEquals(1,Values.object(v.get("scope")).get("revision"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void changePlanningUpgradePreservesRecordsAndRollsBack(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String id;
        try(var db=store.open()){PlatformFoundationTest.bind(db);id=Service.create(db,"change_request",Values.map("short_description","Retained change"),"admin").get("sys_id").toString();db.update("DROP TABLE os_change_plan");db.update("DELETE FROM os_migration WHERE version>=53");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_change_plan"));db.connection.rollback();assertEquals(52,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);ChangePlanning.save(db,id,Values.map("revision",0,"record_revision",0,"implementation","Deploy","impact","Restart","backout","Restore","test","Verify","impact_score",2,"likelihood",3,"ready",true));db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);var plan=ChangePlanning.detail(db,id);assertEquals(true,plan.get("current"));assertEquals("medium",Values.object(plan.get("plan")).get("risk"));assertEquals(1,plan.get("revision"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void problemInvestigationUpgradePreservesRecordsAndRollsBack(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String incident,problem;
        try(var db=store.open()){PlatformFoundationTest.bind(db);incident=Service.create(db,"incident",Values.map("short_description","Retained incident"),"admin").get("sys_id").toString();problem=Service.create(db,"problem",Values.map("short_description","Retained problem"),"admin").get("sys_id").toString();db.update("DROP TABLE os_problem_link");db.update("DROP TABLE os_problem_analysis");db.update("DELETE FROM os_migration WHERE version>=52");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_problem_link"));db.connection.rollback();assertEquals(51,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=new Store(env).open()){db.update("INSERT INTO os_problem_link(incident_id,problem_id) VALUES(?,?)",incident,problem);db.update("INSERT INTO os_problem_analysis(problem_id,revision,cause,workaround,published) VALUES(?,1,'Retained cause','Retained workaround',1)",problem);db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);assertEquals("Retained workaround",ProblemInvestigations.incident(db,incident).get("workaround"));assertEquals(1,Values.objects(ProblemInvestigations.problem(db,problem).get("incidents")).size());assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));assertTrue(Passwords.matches(db.one("SELECT password FROM os_user WHERE name='admin'").get("password").toString(),"migration-test-password"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void measurementUpgradePreservesReportDefinitions(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String table=ReportFormsAcceptance.seed(store),id=ReportMeasurementsAcceptance.save(store,table,Values.map("field","amount"),false);
        try(var db=store.open()){db.update("DROP TABLE os_report_measurement");db.update("DELETE FROM os_migration WHERE version>=51");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_report_measurement"));db.connection.rollback();assertEquals(50,db.count("SELECT MAX(version) FROM os_migration"));}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){String actor=PlatformFoundationTest.bind(db);assertEquals("Measured history",SavedReports.find(db,actor,id).get("name"));assertEquals(0,db.count("SELECT COUNT(*) FROM os_report_measurement"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void supportConversationUpgradePreservesPublishedKnowledge(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String article;
        try(var db=store.open()){String actor=PlatformFoundationTest.bind(db);article=KnowledgeSearchAcceptance.article(db,actor,"Retained article","Published source");db.update("DROP TABLE os_support_conversation");db.update("DELETE FROM os_migration WHERE version>=50");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_support_conversation"));db.connection.rollback();assertEquals(49,db.count("SELECT MAX(version) FROM os_migration"));}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);assertEquals("Published source",Values.object(KnowledgeQuestions.context(store,db,article).get("source")).get("content"));assertEquals(0,db.count("SELECT COUNT(*) FROM os_support_conversation"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void attachmentScanUpgradePreservesLegacyContentAndHolds(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String file=Values.id();
        try(var db=store.open()){PlatformFoundationTest.bind(db);String record=PlatformFoundationTest.record(db,"incident","Legacy attachment");db.update("INSERT INTO os_attachment(sys_id,record_id,name,content,quarantined,hold_revision) VALUES(?,?,?,?,1,2)",file,record,"retained.txt","legacy bytes".getBytes());db.update("DROP TABLE os_attachment_scan");db.update("DELETE FROM os_migration WHERE version>=49");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_attachment_scan"));db.connection.rollback();assertEquals(48,db.count("SELECT MAX(version) FROM os_migration"));}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){assertEquals(0,db.count("SELECT COUNT(*) FROM os_attachment_scan"));var row=db.one("SELECT * FROM os_attachment WHERE sys_id=?",file);assertArrayEquals("legacy bytes".getBytes(),(byte[])row.get("content"));assertEquals(1,row.get("quarantined"));assertEquals(2,((Number)row.get("hold_revision")).intValue());assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void notificationTargetsUpgradePreservesLegacyMessages(boolean postgres) throws Exception {
        var environment=configuration(postgres);var store=new Store(environment);String notice=Values.id();
        try(var db=store.open()){String actor=PlatformFoundationTest.bind(db);Notifications.deliver(db,notice,Values.map("username","admin","message","Retained legacy notice"),1);db.update("DROP TABLE os_notice_target");db.update("DELETE FROM os_migration WHERE version>=48");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_notice_target"));db.connection.rollback();assertEquals(47,db.count("SELECT MAX(version) FROM os_migration"));}
        for(int n=0;n<2;n++)try(var db=new Store(environment).open()){String actor=PlatformFoundationTest.bind(db);assertEquals("Retained legacy notice",db.one("SELECT message FROM os_notice WHERE notice_id=?",notice).get("message"));assertEquals(0,db.count("SELECT COUNT(*) FROM os_notice_target"));assertEquals(0L,((Number)Values.objects(NotificationSearch.page(db,actor,Map.of()).get("result")).getFirst().get("has_target")).longValue());assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void connectorActionUpgradePreservesExistingAliasesAndWork(boolean postgres) throws Exception {
        var environment=configuration(postgres).withProperty("CONNECTOR_ALLOWED_ORIGINS","https://integration.example.invalid");var store=new Store(environment);String job,envelope;
        try(var db=store.open()){
            String actor=PlatformFoundationTest.bind(db);Connectors.save(store,db,actor,Values.map("alias","retained","origin","https://integration.example.invalid","token","retained-synthetic-token"));envelope=db.one("SELECT envelope FROM os_connector WHERE alias='retained'").get("envelope").toString();job=WorkJobs.enqueue(store,db,actor,Values.map("kind","notification","key","legacy-connector-upgrade","payload",Values.map("username","admin","message","Retained pending work"))).get("job_id").toString();
            db.update("DROP TABLE os_connector_resolution");db.update("DROP TABLE os_connector_action");db.update("DELETE FROM os_migration WHERE version>=47");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_connector_action"));assertEquals(0,db.count("SELECT COUNT(*) FROM os_connector_resolution"));db.connection.rollback();assertEquals(46,db.count("SELECT MAX(version) FROM os_migration"));
        }
        for(int n=0;n<2;n++)try(var db=new Store(environment).open()){
            assertEquals(envelope,db.one("SELECT envelope FROM os_connector WHERE alias='retained'").get("envelope"));assertEquals("queued",db.one("SELECT state FROM os_work WHERE job_id=?",job).get("state"));assertEquals(0,db.count("SELECT COUNT(*) FROM os_connector_action"));assertEquals(0,db.count("SELECT COUNT(*) FROM os_connector_resolution"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));assertTrue(Passwords.matches(db.one("SELECT password FROM os_user WHERE name='admin'").get("password").toString(),"migration-test-password"));
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void attemptHistoryUpgradeDoesNotInventPastExecutions(boolean postgres) throws Exception {
        var environment=configuration(postgres);var store=new Store(environment);String job;
        try(var db=store.open()){
            String actor=PlatformFoundationTest.bind(db);job=WorkJobs.enqueue(store,db,actor,Values.map("kind","notification","key","legacy-attempt","payload",Values.map("username","admin","message","Retained work"))).get("job_id").toString();
            db.update("UPDATE os_work SET state='processing',attempts=2,lease=?,available_at=0 WHERE job_id=?",Values.id(),job);db.update("DROP TABLE os_work_attempt");db.update("DELETE FROM os_migration WHERE version>=46");db.connection.commit();
            Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_work_attempt"));db.connection.rollback();assertEquals(45,db.count("SELECT MAX(version) FROM os_migration"));
        }
        var upgraded=new Store(environment);try(var db=upgraded.open()){String actor=PlatformFoundationTest.bind(db);assertEquals(0L,WorkAttempts.page(db,actor,job,Map.of()).get("total"));}
        assertTrue(new WorkWorker(upgraded).runOne(System.currentTimeMillis()+100));
        try(var db=new Store(environment).open()){
            String actor=PlatformFoundationTest.bind(db);var attempts=Values.objects(WorkAttempts.page(db,actor,job,Map.of()).get("result"));assertEquals(1,attempts.size());assertEquals(1L,((Number)attempts.getFirst().get("ordinal")).longValue());assertEquals(3,attempts.getFirst().get("attempt_number"));assertEquals("succeeded",attempts.getFirst().get("state"));
            assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));assertTrue(Passwords.matches(db.one("SELECT password FROM os_user WHERE name='admin'").get("password").toString(),"migration-test-password"));
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void catalogueQuestionsUpgradePreservesLegacyOrdersAndNewAnswers(boolean postgres) throws Exception {
        var environment=configuration(postgres);var store=new Store(environment);String offering,legacy;
        try(var db=store.open()){
            String actor=PlatformFoundationTest.bind(db);offering=Catalogue.save(db,actor,"",CatalogueAcceptance.offering("Retained questions",0,true)).get("offering_id").toString();legacy=Catalogue.order(db,offering,Values.map("revision",1,"details","Legacy order","key",Values.id())).get("sys_id").toString();
            db.update("ALTER TABLE os_offering DROP COLUMN questions");db.update("ALTER TABLE os_catalog_order DROP COLUMN answers");db.update("DELETE FROM os_migration WHERE version>=45");db.connection.commit();Migrations.apply(db);assertEquals("[]",db.one("SELECT answers FROM os_catalog_order WHERE request_id=?",legacy).get("answers"));db.connection.rollback();assertEquals(44,db.count("SELECT MAX(version) FROM os_migration"));
        }
        String request;
        try(var db=new Store(environment).open()){
            String actor=PlatformFoundationTest.bind(db);assertTrue(CatalogueQuestions.answers(db,legacy).isEmpty());assertTrue(Values.objects(Catalogue.find(db,offering,true).get("questions")).isEmpty());
            CatalogueQuestions.save(db,actor,offering,CatalogueQuestionsAcceptance.schema(1,List.of(CatalogueQuestionsAcceptance.question("reason","string",true,List.of()))));request=Catalogue.order(db,offering,Values.map("revision",2,"details","","key",Values.id(),"answers",Values.map("reason","Retained answer"))).get("sys_id").toString();db.connection.commit();
        }
        try(var db=new Store(environment).open()){
            PlatformFoundationTest.bind(db);assertEquals("Retained answer",CatalogueQuestions.answers(db,request).getFirst().get("value"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));assertTrue(Passwords.matches(db.one("SELECT password FROM os_user WHERE name='admin'").get("password").toString(),"migration-test-password"));
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void routedFulfilmentUpgradeIsAtomicAndRetainsTasks(boolean postgres) throws Exception {
        var environment=configuration(postgres);var store=new Store(environment);
        try(var db=store.open()){
            db.update("DROP TABLE os_fulfilment");db.update("ALTER TABLE os_offering DROP COLUMN fulfilment_group_id");db.update("DELETE FROM os_migration WHERE version>=44");db.connection.commit();
            Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_fulfilment"));db.connection.rollback();assertEquals(43,db.count("SELECT MAX(version) FROM os_migration"));
        }
        String offering,request;
        try(var db=new Store(environment).open()){
            String actor=PlatformFoundationTest.bind(db),group=Service.create(db,"sys_user_group",Values.map("short_description","Retained fulfilment team"),"admin").get("sys_id").toString();
            offering=Catalogue.save(db,actor,"",CatalogueAcceptance.offering("Retained routed offering",0,true)).get("offering_id").toString();assertEquals("",Catalogue.find(db,offering,true).get("fulfilment_group_id"));
            CatalogueRouting.save(db,actor,offering,Values.map("group_id",group,"revision",1));request=Catalogue.order(db,offering,Values.map("revision",2,"key",Values.id(),"details","Retain delivery")).get("sys_id").toString();db.connection.commit();
        }
        try(var db=new Store(environment).open()){
            PlatformFoundationTest.bind(db);assertEquals("queued",Fulfilment.detail(db,request).get("state"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));assertTrue(Passwords.matches(db.one("SELECT password FROM os_user WHERE name='admin'").get("password").toString(),"migration-test-password"));
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void catalogueEligibilityUpgradePreservesExistingOfferings(boolean postgres) throws Exception {
        var environment=configuration(postgres);var store=new Store(environment);String offering,group;
        try(var db=store.open()) {
            String actor=PlatformFoundationTest.bind(db);
            offering=Catalogue.save(db,actor,"",CatalogueAcceptance.offering("Retained offering",0,true)).get("offering_id").toString();
            group=Service.create(db,"sys_user_group",Values.map("short_description","Required group"),"admin").get("sys_id").toString();
            db.update("ALTER TABLE os_offering DROP COLUMN group_id");db.update("DELETE FROM os_migration WHERE version>=43");db.connection.commit();
            Migrations.apply(db);assertEquals("",db.one("SELECT group_id FROM os_offering WHERE offering_id=?",offering).get("group_id"));
            db.connection.rollback();assertEquals(42,db.count("SELECT MAX(version) FROM os_migration"));
        }
        try(var db=new Store(environment).open()) {
            String actor=PlatformFoundationTest.bind(db);
            assertEquals("Retained offering",Catalogue.find(db,offering,true).get("name"));
            CatalogueEligibility.save(db,actor,offering,Values.map("group_id",group,"revision",1));db.connection.commit();
        }
        try(var db=new Store(environment).open()) {
            PlatformFoundationTest.bind(db);assertEquals(group,Catalogue.find(db,offering,true).get("group_id"));
            assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));
            assertTrue(Passwords.matches(db.one("SELECT password FROM os_user WHERE name='admin'").get("password").toString(),"migration-test-password"));
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void configurationHistoryUpgradeIsAtomicAndPreservesReceipts(boolean postgres) throws Exception {
        var environment=configuration(postgres);var store=new Store(environment);
        try(var db=store.open()) {
            db.update("DROP TABLE os_configuration_install");db.update("DELETE FROM os_migration WHERE version>=42");db.connection.commit();
            Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_configuration_install"));
            db.connection.rollback();assertEquals(41,db.count("SELECT MAX(version) FROM os_migration"));
        }
        String receipt;
        try(var db=new Store(environment).open()) {
            String actor=PlatformFoundationTest.bind(db);
            receipt=Configuration.install(db,ConfigurationAcceptance.bundle("u_retained_history",ConfigurationAcceptance.definition()),actor,false).get("installation_id").toString();
            db.connection.commit();
        }
        try(var db=new Store(environment).open()) {
            PlatformFoundationTest.bind(db);
            assertEquals(receipt,ConfigurationHistory.detail(db,receipt).get("install_id"));
            assertEquals(1,Service.schema(db,"u_retained_history").get("version"));
            assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));
            assertTrue(Passwords.matches(db.one("SELECT password FROM os_user WHERE name='admin'").get("password").toString(),"migration-test-password"));
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void clockPurposeUpgradeAndConcurrentStartsRemainSafe(boolean postgres) throws Exception {
        var env=configuration(postgres);var store=new Store(env);String record,calendar="purpose-upgrade";
        try(var db=store.open()){String actor=PlatformFoundationTest.bind(db);record=PlatformFoundationTest.record(db,"incident","Retained clock");ServiceClocks.calendar(db,actor,Values.map("name",calendar,"calendar",PlatformFoundationTest.calendar()));ServiceClocks.start(db,actor,Values.map("table","incident","record_id",record,"calendar",calendar,"minutes",120),System.currentTimeMillis());db.update("DROP INDEX ix_os_sla_active_purpose");db.update("ALTER TABLE os_sla DROP COLUMN purpose");db.update("DELETE FROM os_migration WHERE version>=41");db.connection.commit();Migrations.apply(db);assertEquals("general",db.one("SELECT purpose FROM os_sla").get("purpose"));db.connection.rollback();assertEquals(40,db.count("SELECT MAX(version) FROM os_migration"));}
        var upgraded=new Store(env);try(var executor=Executors.newFixedThreadPool(2)){var begin=new CountDownLatch(1);List<Future<Integer>> results=new ArrayList<>();for(int i=0;i<2;i++)results.add(executor.submit(()->{begin.await();try(var db=upgraded.open()){String actor=PlatformFoundationTest.bind(db);try{ServiceClocks.start(db,actor,Values.map("table","incident","record_id",record,"calendar",calendar,"minutes",120,"purpose","response"),System.currentTimeMillis());db.connection.commit();return 200;}catch(Values.Failure conflict){return conflict.status;}}}));begin.countDown();assertEquals(Set.of(200,409),Set.of(results.get(0).get(30,TimeUnit.SECONDS),results.get(1).get(30,TimeUnit.SECONDS)));}
        try(var db=new Store(env).open()){assertEquals(2,db.count("SELECT COUNT(*) FROM os_sla"));assertEquals(1,db.count("SELECT COUNT(*) FROM os_sla WHERE purpose='general'"));assertEquals(1,db.count("SELECT COUNT(*) FROM os_sla WHERE purpose='response'"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void knowledgeFeedbackUpgradeRollsBackAndRetainsReviews(boolean postgres) throws Exception {
        var env=configuration(postgres);var store=new Store(env);
        try(var db=store.open()){db.update("DROP TABLE os_knowledge_feedback");db.update("DELETE FROM os_migration WHERE version>=39");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_knowledge_feedback"));db.connection.rollback();assertEquals(38,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=new Store(env).open()){db.update("INSERT INTO os_knowledge_feedback(feedback_id,article_id,version,user_id,helpful,details,updated_at,reviewed,review_note) VALUES('feedback','article',1,'user',0,'Retained feedback',1,1,'Retained review')");db.connection.commit();}
        try(var db=new Store(env).open()){assertEquals("Retained review",db.one("SELECT review_note FROM os_knowledge_feedback WHERE feedback_id='feedback'").get("review_note"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void profileUpgradePreservesAccountsAndRepeatsSafely(boolean postgres) throws Exception {
        var env=configuration(postgres);var store=new Store(env);
        try(var db=store.open()){
            db.update("ALTER TABLE os_user DROP COLUMN display_name");db.update("ALTER TABLE os_user DROP COLUMN job_title");db.update("DELETE FROM os_migration WHERE version>=36");db.connection.commit();
        }
        new Store(env);
        try(var db=store.open()){
            assertEquals("",db.one("SELECT display_name FROM os_user WHERE name='admin'").get("display_name"));
            db.update("UPDATE os_user SET display_name='Retained profile',job_title='Operator' WHERE name='admin'");db.connection.commit();
        }
        new Store(env);
        try(var db=store.open()){assertEquals("Retained profile",db.one("SELECT display_name FROM os_user WHERE name='admin'").get("display_name"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void catalogueUpgradeAndConcurrentOrdersPreserveExactlyOneRequest(boolean postgres) throws Exception {
        var env=configuration(postgres);var store=new Store(env);
        try(var db=store.open()){
            db.update("DROP TABLE os_catalog_workflow_order");db.update("DROP TABLE os_catalog_workflow");db.update("DROP TABLE os_catalog_order");db.update("DROP TABLE os_offering");db.update("DELETE FROM os_migration WHERE version>=37");db.connection.commit();
            Migrations.apply(db);assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));db.connection.rollback();assertEquals(36,db.count("SELECT MAX(version) FROM os_migration"));
        }
        var upgraded=new Store(env);String offering,key=Values.id();
        try(var db=upgraded.open()){String actor=PlatformFoundationTest.bind(db);offering=Catalogue.save(db,actor,"",CatalogueAcceptance.offering("Retained offering",0,true)).get("offering_id").toString();db.connection.commit();}
        try(var executor=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);List<Future<String>> outcomes=new ArrayList<>();
            for(int i=0;i<2;i++)outcomes.add(executor.submit(()->{start.await();try(var db=upgraded.open()){PlatformFoundationTest.bind(db);String result=Catalogue.order(db,offering,Values.map("revision",1,"details","Concurrent order","key",key)).get("sys_id").toString();db.connection.commit();return result;}}));
            start.countDown();assertEquals(outcomes.get(0).get(30,TimeUnit.SECONDS),outcomes.get(1).get(30,TimeUnit.SECONDS));
        }
        try(var db=new Store(env).open()){assertEquals(1,db.count("SELECT COUNT(*) FROM os_catalog_order"));assertEquals(1,db.count("SELECT COUNT(*) FROM os_requester_record"));assertEquals("Retained offering",db.one("SELECT name FROM os_offering").get("name"));assertTrue(Passwords.matches(db.one("SELECT password FROM os_user WHERE name='admin'").get("password").toString(),"migration-test-password"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void attachmentHoldUpgradePreservesBytesAndRollsBack(boolean postgres) throws Exception {
        var env=configuration(postgres);var store=new Store(env);byte[] content={0,1,-1};
        try(var db=store.open()){db.update("INSERT INTO os_attachment(sys_id,record_id,name,content,content_type) VALUES('retained','parent','file.bin',?,'application/octet-stream')",content);db.update("ALTER TABLE os_attachment DROP COLUMN quarantined");db.update("ALTER TABLE os_attachment DROP COLUMN hold_revision");db.update("DELETE FROM os_migration WHERE version>=38");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT quarantined FROM os_attachment"));db.connection.rollback();assertEquals(37,db.count("SELECT MAX(version) FROM os_migration"));}
        for(int i=0;i<2;i++){try(var db=new Store(env).open()){var file=db.one("SELECT * FROM os_attachment WHERE sys_id='retained'");assertArrayEquals(content,(byte[])file.get("content"));assertEquals(0,file.get("quarantined"));assertEquals(0,file.get("hold_revision"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void eventCaseUpgradeAndConcurrentEscalationsKeepOneIncident(boolean postgres) throws Exception {
        var env=configuration(postgres);var store=new Store(env);
        try(var db=store.open()){db.update("DROP TABLE os_event_case");db.update("DELETE FROM os_migration WHERE version>=40");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_event_case"));db.connection.rollback();assertEquals(39,db.count("SELECT MAX(version) FROM os_migration"));}
        var upgraded=new Store(env);String review;
        try(var db=upgraded.open()){String actor=PlatformFoundationTest.bind(db),asset=Service.create(db,"cmdb_ci",Values.map("short_description","Concurrent event asset"),"admin").get("sys_id").toString();AssetOperations.event(db,actor,Values.map("key","concurrent-event","asset_id",asset,"severity","warning","summary","Review retained event"),1);review=EventCases.page(db,"concurrent-event").get("review").toString();db.connection.commit();}
        try(var executor=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);List<Future<String>> outcomes=new ArrayList<>();for(int i=0;i<2;i++)outcomes.add(executor.submit(()->{start.await();try(var db=upgraded.open()){PlatformFoundationTest.bind(db);String result=EventCases.create(db,Values.map("key","concurrent-event","review",review)).get("sys_id").toString();db.connection.commit();return result;}}));start.countDown();assertEquals(outcomes.get(0).get(30,TimeUnit.SECONDS),outcomes.get(1).get(30,TimeUnit.SECONDS));}
        try(var db=new Store(env).open()){assertEquals(1,db.count("SELECT COUNT(*) FROM os_event_case"));assertEquals(1,db.count("SELECT COUNT(*) FROM os_record WHERE \"table\"='incident'"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));assertTrue(Passwords.matches(db.one("SELECT password FROM os_user WHERE name='admin'").get("password").toString(),"migration-test-password"));}
    }

    @TempDir Path root;

    MockEnvironment configuration(boolean postgres) throws Exception {
        String url = "jdbc:sqlite:" + root.resolve("migration.db");
        if (postgres) {
            URI uri =
                    URI.create(DockerDatabase.url().replace("postgresql+psycopg:", "postgresql:"));
            String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
            url =
                    "jdbc:postgresql://"
                            + uri.getHost()
                            + ":"
                            + uri.getPort()
                            + uri.getPath()
                            + "?user=openservice&password=disposable-integration-password";
            try (var connection = DriverManager.getConnection(url);
                    var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
            }
            url += "&currentSchema=" + schema;
        }
        return new MockEnvironment()
                .withProperty("DATABASE_URL", url)
                .withProperty("SECRET_KEY", "migration-test-secret")
                .withProperty("ADMIN_PASSWORD", "migration-test-password");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void browserInventoryUpgradeIsTransactionalAndRepeatable(boolean postgres) throws Exception {
        var environment = configuration(postgres);
        var store = new Store(environment);
        try (var db = store.open()) {
            db.update("DROP TABLE os_browser_session");
            db.update("DELETE FROM os_migration WHERE version>=21");
            db.connection.commit();
            Migrations.apply(db);
            assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
            db.connection.rollback();
            assertEquals(20, db.count("SELECT COUNT(*) FROM os_migration"));
        }
        store = new Store(environment);
        try (var db = store.open()) {
            BrowserSessions.register(db, db.one("SELECT * FROM os_user WHERE name='admin'"),
                    "persisted-test-session", System.currentTimeMillis());
            db.connection.commit();
        }
        store = new Store(environment);
        try (var db = store.open()) {
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_browser_session"));
            assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void eventDispositionUpgradePreservesExistingOccurrences(boolean postgres) throws Exception {
        var env=configuration(postgres);
        var store=new Store(env);
        try(var db=store.open()) {
            db.update("ALTER TABLE os_operational_event DROP COLUMN state");
            db.update("DELETE FROM os_migration WHERE version>=34");
            db.update("INSERT INTO os_operational_event(event_key,asset_id,severity,summary,occurrences,first_seen,last_seen) VALUES('retained','asset','critical','Preserve this',7,100,200)");
            db.connection.commit();
            Migrations.apply(db);
            assertEquals("open",db.one("SELECT state FROM os_operational_event").get("state"));
            db.connection.rollback();
            assertEquals(33,db.count("SELECT MAX(version) FROM os_migration"));
        }
        for(int i=0;i<2;i++) {
            store=new Store(env);
            try(var db=store.open()) {
                var event=db.one("SELECT * FROM os_operational_event WHERE event_key='retained'");
                assertEquals("open",event.get("state"));
                assertEquals(7,((Number)event.get("occurrences")).intValue());
                assertEquals("Preserve this",event.get("summary"));
                assertEquals(200,((Number)event.get("last_seen")).intValue());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void passwordRecoveryIsAtomicAndSingleUseUnderConcurrency(boolean postgres) throws Exception {
        var store = new Store(configuration(postgres));
        String token, target;
        try (var db = store.open()) {
            target =
                    db.one("SELECT user_id FROM os_user WHERE name='admin'")
                            .get("user_id")
                            .toString();
            token =
                    RecoveryLinks.issue(
                                    db,
                                    db.one("SELECT * FROM os_user WHERE user_id=?", target),
                                    target,
                                    Values.map("current_password", "migration-test-password"))
                            .get("reset_path")
                            .toString()
                            .substring(9);
            db.connection.commit();
        }
        try (var db = store.open()) {
            RecoveryLinks.recover(db, token, "rolled-back-password");
            db.connection.rollback();
            assertTrue(
                    Passwords.matches(
                            db.one("SELECT password FROM os_user WHERE user_id=?", target)
                                    .get("password")
                                    .toString(),
                            "migration-test-password"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_recovery"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_security_event"));
        }
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int n = 0; n < 2; n++)
                results.add(
                        executor.submit(() -> {
                            start.await();
                            try (var db = store.open()) {
                                try {
                                    RecoveryLinks.recover(db, token, "accepted-reset-password");
                                    db.connection.commit();
                                    return 200;
                                } catch (Values.Failure failure) {
                                    db.connection.rollback();
                                    return failure.status;
                                }
                            }
                        }));
            start.countDown();
            var statuses =
                    List.of(
                            results.get(0).get(30, TimeUnit.SECONDS),
                            results.get(1).get(30, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(status -> status == 200).count());
            assertEquals(1, statuses.stream().filter(status -> status == 400).count());
        }
        try (var db = store.open()) {
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_recovery"));
            assertEquals(2, db.count("SELECT COUNT(*) FROM os_security_event"));
            var user =
                    db.one(
                            "SELECT password,auth_revision,active FROM os_user WHERE user_id=?",
                            target);
            assertTrue(
                    Passwords.matches(user.get("password").toString(), "accepted-reset-password"));
            assertEquals(1L, ((Number) user.get("auth_revision")).longValue());
            assertEquals(1, ((Number) user.get("active")).intValue());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyOneConcurrentInvitationAcceptanceCreatesAnAccount(boolean postgres) throws Exception {
        var store = new Store(configuration(postgres));
        String token;
        try (var db = store.open()) {
            String actor =
                    db.one("SELECT user_id FROM os_user WHERE name='admin'")
                            .get("user_id")
                            .toString();
            token =
                    Invitations.issue(
                                    db,
                                    actor,
                                    Values.map("name", "ConcurrentInvite", "role", "agent"))
                            .get("accept_path")
                            .toString()
                            .substring(6);
            db.connection.commit();
        }
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int n = 0; n < 2; n++)
                results.add(
                        executor.submit(() -> {
                            start.await();
                            try (var db = store.open()) {
                                try {
                                    Invitations.accept(db, token, "invitation-test-password");
                                    db.connection.commit();
                                    return 200;
                                } catch (Values.Failure failure) {
                                    db.connection.rollback();
                                    return failure.status;
                                }
                            }
                        }));
            start.countDown();
            var statuses =
                    List.of(
                            results.get(0).get(30, TimeUnit.SECONDS),
                            results.get(1).get(30, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(status -> status == 200).count());
            assertEquals(1, statuses.stream().filter(status -> status == 400).count());
        }
        try (var db = store.open()) {
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_user WHERE name='ConcurrentInvite'"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_invitation WHERE state='accepted'"));
            assertEquals(3, db.count("SELECT COUNT(*) FROM os_security_event"));
            for (String action : List.of("invitation.issued", "account.created", "account.invitation_accepted"))
                assertEquals(1, db.count("SELECT COUNT(*) FROM os_security_event WHERE action=?", action));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void passwordChangeRechecksCallerAndRollsBackBusinessMutation(boolean postgres)
            throws Exception {
        var store = new Store(configuration(postgres));
        try (var db = store.open()) {
            var caller = db.one("SELECT * FROM os_user WHERE name='admin'");
            var body =
                    Values.map(
                            "current_password",
                            "migration-test-password",
                            "new_password",
                            "next-private-password");
            String originalHash = caller.get("password").toString();
            db.update("UPDATE os_user SET active=0 WHERE name='admin'");
            db.connection.commit();
            assertEquals(
                    401,
                    assertThrows(
                                    Values.Failure.class,
                                    () -> Service.changePassword(db, caller, body))
                            .status);
            db.update("UPDATE os_user SET active=1 WHERE name='admin'");
            db.connection.commit();
            caller.put("auth_revision", -1L);
            assertEquals(
                    401,
                    assertThrows(
                                    Values.Failure.class,
                                    () -> Service.changePassword(db, caller, body))
                            .status);
            db.connection.rollback();
            assertEquals(
                    originalHash,
                    db.one("SELECT password FROM os_user WHERE name='admin'").get("password"));
            caller.put("auth_revision", 0L);
            Service.changePassword(db, caller, body);
            db.connection.rollback();
            assertEquals(
                    originalHash,
                    db.one("SELECT password FROM os_user WHERE name='admin'").get("password"));
            assertEquals(
                    0L,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                + " action='account.password_changed'"));
            assertEquals(
                    2L,
                    db.count(
                            "SELECT COUNT(*) FROM os_auth_event WHERE mechanism='password-change'"
                                + " AND outcome='succeeded'"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentPasswordChangesCannotReuseThePreviousSecret(boolean postgres) throws Exception {
        var store = new Store(configuration(postgres));
        String id;
        Map<String, Object> caller;
        try (var db = store.open()) {
            caller = db.one("SELECT * FROM os_user WHERE name='admin'");
            id = caller.get("user_id").toString();
        }
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (String next : List.of("first-new-password", "second-new-password"))
                results.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    try (var db = store.open()) {
                                        try {
                                            Service.changePassword(
                                                    db,
                                                    caller,
                                                    Values.map(
                                                            "current_password",
                                                            "migration-test-password",
                                                            "new_password",
                                                            next));
                                            db.connection.commit();
                                            return 200;
                                        } catch (Values.Failure failure) {
                                            db.connection.rollback();
                                            return failure.status;
                                        }
                                    }
                                }));
            start.countDown();
            var statuses =
                    List.of(
                            results.get(0).get(30, TimeUnit.SECONDS),
                            results.get(1).get(30, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(status -> status == 200).count());
            assertEquals(
                    1, statuses.stream().filter(status -> status == 400 || status == 401).count());
        }
        try (var db = store.open()) {
            var user = db.one("SELECT password,auth_revision FROM os_user WHERE user_id=?", id);
            assertFalse(
                    Passwords.matches(user.get("password").toString(), "migration-test-password"));
            assertEquals(1, ((Number) user.get("auth_revision")).longValue());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyUpgradeIsAtomicRepeatableAndPreservesData(boolean postgres) throws Exception {
        var config = configuration(postgres);
        var store = new Store(config);
        try (var db = store.open()) {
            db.update(
                    "INSERT INTO os_attachment(sys_id,record_id,name,content) VALUES(?,?,?,?)",
                    "legacy",
                    "parent",
                    "file.bin",
                    new byte[] {0, 1, -1});
            db.update("ALTER TABLE os_attachment DROP COLUMN content_type");
            db.update("DROP TABLE os_migration");
            db.connection.commit();
        }
        new Store(config);
        new Store(config.withProperty("ADMIN_PASSWORD", "changed-bootstrap-password"));
        try (var db = store.open()) {
            assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
            var file = db.one("SELECT * FROM os_attachment WHERE sys_id='legacy'");
            assertArrayEquals(new byte[] {0, 1, -1}, (byte[]) file.get("content"));
            assertEquals("application/octet-stream", file.get("content_type"));
            assertTrue(
                    Passwords.matches(
                            (String)
                                    db.one("SELECT password FROM os_user WHERE name='admin'")
                                            .get("password"),
                            "migration-test-password"));
            db.update("INSERT INTO os_migration(version,name) VALUES(99,'future')");
            db.connection.commit();
        }
        assertThrows(SQLException.class, () -> new Store(config));
        try (var db = store.open()) {
            db.update("DELETE FROM os_migration");
            db.update("INSERT INTO os_migration(version,name) VALUES(0,'invalid')");
            db.connection.commit();
        }
        assertThrows(SQLException.class, () -> new Store(config));
        try (var db = store.open()) {
            db.update("DELETE FROM os_migration");
            db.update("ALTER TABLE os_attachment DROP COLUMN content_type");
            db.connection.commit();
        }
        // Failure after DDL must roll back both the schema change and its journal entry.
        try (var db = store.open()) {
            Migrations.lock(db);
            Migrations.apply(db);
            assertThrows(
                    SQLException.class, () -> db.update("INSERT INTO missing_table VALUES(1)"));
            db.connection.rollback();
        }
        try (var db = store.open()) {
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_migration"));
            try (var columns =
                    db.connection
                            .getMetaData()
                            .getColumns(
                                    null,
                                    db.connection.getSchema(),
                                    "os_attachment",
                                    "content_type")) {
                assertFalse(columns.next());
            }
        }
        new Store(config);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyIdentitiesBackfillWithoutChangingCredentials(boolean postgres) throws Exception {
        var config = configuration(postgres);
        var store = new Store(config);
        String hash;
        try (var db = store.open()) {
            hash =
                    (String)
                            db.one("SELECT password FROM os_user WHERE name='admin'")
                                    .get("password");
            db.update("DROP TABLE os_support_conversation");
            db.update("DROP TABLE os_report_measurement");
            db.update("DROP TABLE os_customer_delivery");
            db.update("DROP TABLE os_field_availability");db.update("DROP TABLE os_customer_contact");
            db.update("DROP INDEX ix_os_user_identity");
            db.update("ALTER TABLE os_user DROP COLUMN user_id");
            db.update("DELETE FROM os_migration WHERE version>=2");
            db.update("INSERT INTO os_user(name,password,role) VALUES('legacy',?,'reader')", hash);
            db.connection.commit();
        }
        new Store(config);
        List<Map<String, Object>> before;
        try (var db = store.open()) {
            before = db.all("SELECT * FROM os_user ORDER BY name");
            assertEquals(2, before.size());
            for (var user : before) {
                assertTrue(user.get("user_id").toString().matches("[0-9a-f]{32}"));
                assertEquals(hash, user.get("password"));
            }
            assertNotEquals(before.get(0).get("user_id"), before.get(1).get("user_id"));
            assertThrows(
                    SQLException.class,
                    () ->
                            db.update(
                                    "UPDATE os_user SET user_id=? WHERE name='legacy'",
                                    before.get(0).get("user_id")));
            db.connection.rollback();
        }
        new Store(config);
        try (var db = store.open()) {
            assertEquals(before, db.all("SELECT * FROM os_user ORDER BY name"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void adoptsAlreadyUpgradedInstallationsWithoutReplacingMetadata(boolean postgres)
            throws Exception {
        var config = configuration(postgres);
        var store = new Store(config);
        try (var db = store.open()) {
            db.update(
                    "INSERT INTO os_attachment(sys_id,record_id,name,content,content_type)"
                            + " VALUES(?,?,?,?,?)",
                    "existing",
                    "parent",
                    "file.pdf",
                    new byte[] {7},
                    "application/pdf");
            db.update("DROP TABLE os_migration");
            db.connection.commit();
        }
        new Store(config);
        try (var db = store.open()) {
            assertEquals(
                    "application/pdf",
                    db.one("SELECT content_type FROM os_attachment WHERE sys_id='existing'")
                            .get("content_type"));
            assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentDeactivationKeepsAnActiveAdministrator(boolean postgres) throws Exception {
        var store = new Store(configuration(postgres));
        String admin, second;
        try (var db = store.open()) {
            Service.createAccount(
                    db,
                    Values.map(
                            "name",
                            "SecondAdmin",
                            "password",
                            "test-only-password-long",
                            "role",
                            "admin"));
            admin =
                    db.one("SELECT user_id FROM os_user WHERE name='admin'")
                            .get("user_id")
                            .toString();
            second =
                    db.one("SELECT user_id FROM os_user WHERE name='SecondAdmin'")
                            .get("user_id")
                            .toString();
            db.connection.commit();
            assertEquals(
                    403,
                    assertThrows(
                                    Values.Failure.class,
                                    () ->
                                            Service.changeActive(
                                                    db,
                                                    "missing",
                                                    second,
                                                    Values.map("active", false)))
                            .status);
        }
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (String target : List.of(admin, second))
                results.add(
                        executor.submit(() -> {
                            start.await();
                            try (var db = store.open()) {
                                try {
                                    Service.changeActive(
                                            db, admin, target, Values.map("active", false));
                                    db.connection.commit();
                                    return 200;
                                } catch (Values.Failure failure) {
                                    db.connection.rollback();
                                    return failure.status;
                                }
                            }
                        }));
            start.countDown();
            var statuses =
                    List.of(
                            results.get(0).get(30, TimeUnit.SECONDS),
                            results.get(1).get(30, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(status -> status == 200).count());
            assertTrue(
                    statuses.stream().allMatch(status -> Set.of(200, 403, 409).contains(status)));
        }
        try (var db = store.open()) {
            assertEquals(
                    1, db.count("SELECT COUNT(*) FROM os_user WHERE active=1 AND role='admin'"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentDemotionKeepsAnActiveAdministrator(boolean postgres) throws Exception {
        var store = new Store(configuration(postgres));
        String admin, second;
        try (var db = store.open()) {
            Service.createAccount(
                    db,
                    Values.map(
                            "name",
                            "SecondAdmin",
                            "password",
                            "test-only-password-long",
                            "role",
                            "admin"));
            admin =
                    db.one("SELECT user_id FROM os_user WHERE name='admin'")
                            .get("user_id")
                            .toString();
            second =
                    db.one("SELECT user_id FROM os_user WHERE name='SecondAdmin'")
                            .get("user_id")
                            .toString();
            db.connection.commit();
            assertEquals(
                    403,
                    assertThrows(
                                    Values.Failure.class,
                                    () ->
                                            Service.changeRole(
                                                    db,
                                                    "missing",
                                                    second,
                                                    Values.map("role", "reader")))
                            .status);
        }
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (String target : List.of(admin, second))
                results.add(
                        executor.submit(() -> {
                            start.await();
                            try (var db = store.open()) {
                                try {
                                    Service.changeRole(
                                            db, admin, target, Values.map("role", "reader"));
                                    db.connection.commit();
                                    return 200;
                                } catch (Values.Failure failure) {
                                    db.connection.rollback();
                                    return failure.status;
                                }
                            }
                        }));
            start.countDown();
            var statuses =
                    List.of(
                            results.get(0).get(30, TimeUnit.SECONDS),
                            results.get(1).get(30, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(status -> status == 200).count());
            assertTrue(
                    statuses.stream().allMatch(status -> Set.of(200, 403, 409).contains(status)));
        }
        try (var db = store.open()) {
            assertEquals(
                    1, db.count("SELECT COUNT(*) FROM os_user WHERE active=1 AND role='admin'"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentFreshStartupSerializesBootstrapAndMigration(boolean postgres) throws Exception {
        var config = configuration(postgres);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            List<Future<Store>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++)
                futures.add(
                        executor.submit(() -> {
                            start.await();
                            return new Store(config);
                        }));
            start.countDown();
            for (var future : futures) {
                try (var db = future.get(30, TimeUnit.SECONDS).open()) {
                    assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
                    assertEquals(1, db.count("SELECT COUNT(*) FROM os_user"));
                    assertEquals(17, db.count("SELECT COUNT(*) FROM os_schema"));
                    assertNotNull(db.one("SELECT name FROM os_schema WHERE name='risk'"));
                    assertNotNull(db.one("SELECT name FROM os_schema WHERE name='security_incident'"));
                    assertNotNull(db.one("SELECT name FROM os_schema WHERE name='vulnerability'"));
                    assertNotNull(db.one("SELECT name FROM os_schema WHERE name='portfolio'"));
                    assertNotNull(db.one("SELECT name FROM os_schema WHERE name='demand'"));
                    assertNotNull(db.one("SELECT name FROM os_schema WHERE name='project'"));
                    assertNotNull(db.one("SELECT name FROM os_schema WHERE name='alm_asset'"));
                    assertNotNull(db.one("SELECT name FROM os_schema WHERE name='stockroom'"));
                    assertNotNull(db.one("SELECT name FROM os_schema WHERE name='hr_case'"));
                    assertNotNull(db.one("SELECT name FROM os_schema WHERE name='customer_case'"));
                }
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void projectUpgradeRetainsDelivery(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String retained,project,demand,task;
        try(var db=store.open()){PlatformFoundationTest.bind(db);retained=Service.create(db,"incident",Values.map("short_description","Retained before project upgrade"),"admin").get("sys_id").toString();db.update("DROP TABLE os_project_schedule");db.update("DROP TABLE os_project_origin");db.update("DELETE FROM os_schema WHERE name='project'");db.update("DELETE FROM os_migration WHERE version>=70");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_project_origin"));db.connection.rollback();assertEquals(69,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);Service.createAccount(db,Values.map("name","UpgradeProjectReviewer","role","agent","password",WorkflowSupport.PASSWORD));String portfolio=Service.create(db,"portfolio",Values.map("short_description","Retained project portfolio"),"admin").get("sys_id").toString(),group=Service.create(db,"sys_user_group",Values.map("short_description","Retained project team"),"admin").get("sys_id").toString();demand=Service.create(db,"demand",Values.map("short_description","Retained delivery demand","description","Retained approved business case"),"admin").get("sys_id").toString();DemandPlanning.save(db,demand,Values.map("operation","assess","revision",0,"record_revision",0,"portfolio_id",portfolio,"year",2027,"value",5,"alignment",4,"urgency",3,"effort",2,"rationale","Retained investment basis"));Service.update(db,"demand",Service.record(db,"demand",demand),Values.map("state","screen"),"admin","admin",null);Service.update(db,"demand",Service.record(db,"demand",demand),Values.map("state","qualified"),"admin","admin",null);var admin=db.one("SELECT * FROM os_user WHERE name='admin'");String approval=Approvals.request(db,"demand",demand,admin,Values.map("expected_revision",3,"approver","UpgradeProjectReviewer","target_state","approved","reason","Retained investment review")).get("approval_id").toString();var reviewer=db.one("SELECT * FROM os_user WHERE name='UpgradeProjectReviewer'");db.viewerId=reviewer.get("user_id").toString();db.viewerRole="agent";Approvals.decide(db,"demand",demand,approval,reviewer,Values.map("decision","approved","comment","Current investment reviewed"));PlatformFoundationTest.bind(db);DemandPlanning.save(db,demand,Values.map("operation","decide","revision",1,"record_revision",3,"decision","approved","rationale","Retained approved investment"));project=ProjectDelivery.convert(db,demand,Values.map("record_revision",4)).get("sys_id").toString();Service.update(db,"project",Service.record(db,"project",project),Values.map("start_on","2030-01-01","finish_on","2030-01-31"),"admin","admin",null);WorkTasks.command(db,"project",project,Values.map("operation","create","revision",1,"record_revision",1,"summary","Retained delivery milestone","details","Verify the delivery decision","group_id",group,"required",true,"predecessors",List.of()));task=Values.objects(WorkTasks.page(db,"project",project).get("tasks")).getFirst().get("task_id").toString();ProjectDelivery.schedule(db,project,task,Values.map("revision",0,"record_revision",2,"task_revision",0,"start_on","2030-01-10","finish_on","2030-01-10","milestone",true));Service.update(db,"project",Service.record(db,"project",project),Values.map("state","active"),"admin","admin",null);WorkTasks.command(db,"project",project,Values.map("operation","claim","revision",0,"record_revision",4,"task_id",task));WorkTasks.command(db,"project",project,Values.map("operation","complete","revision",1,"record_revision",4,"task_id",task,"outcome","Retained verified delivery outcome"));Service.update(db,"project",Service.record(db,"project",project),Values.map("state","complete"),"admin","admin",null);db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);assertEquals("Retained before project upgrade",Values.object(Service.readRecord(db,"incident",retained).get("data")).get("short_description"));var page=ProjectDelivery.page(db,project);assertEquals("complete",Values.object(page.get("record")).get("state"));assertEquals(demand,Values.object(page.get("demand")).get("sys_id"));assertEquals(demand,RecordScopes.scope(db,project).get("parent_id"));var schedule=ProjectDelivery.schedulePage(db,project,task);assertEquals(true,schedule.get("current"));assertEquals(true,Values.object(Values.object(schedule.get("schedule")).get("detail")).get("milestone"));assertEquals("Retained verified delivery outcome",Values.object(schedule.get("task")).get("outcome"));assertEquals(1,Values.objects(schedule.get("history")).size());assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }


    @ParameterizedTest @ValueSource(booleans={false,true})
    void assetUpgradeRetainsCustody(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String retained,asset,stock,ci;
        try(var db=store.open()){PlatformFoundationTest.bind(db);retained=Service.create(db,"incident",Values.map("short_description","Retained before inventory upgrade"),"admin").get("sys_id").toString();db.update("DROP TABLE os_asset_movement");db.update("DROP TABLE os_asset_identity");db.update("DELETE FROM os_schema WHERE name IN ('alm_asset','stockroom')");db.update("DELETE FROM os_migration WHERE version>=71");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_asset_identity"));db.connection.rollback();assertEquals(70,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);Service.createAccount(db,Values.map("name","UpgradeCustodyRecipient","role","requester","password",WorkflowSupport.PASSWORD));stock=Service.create(db,"stockroom",Values.map("short_description","Retained stockroom"),"admin").get("sys_id").toString();ci=Service.create(db,"cmdb_ci",Values.map("short_description","Retained operational CI"),"admin").get("sys_id").toString();asset=Service.create(db,"alm_asset",Values.map("short_description","Retained inventory asset","asset_tag","UPGRADE-ASSET-001","model","Retained model","serial_number","Retained serial"),"admin").get("sys_id").toString();AssetCustody.save(db,asset,Values.map("operation","receive","revision",0,"record_revision",0,"reason","Retained receipt evidence","stockroom_id",stock,"ci_id",ci));AssetCustody.save(db,asset,Values.map("operation","issue","revision",1,"record_revision",1,"reason","Retained handover evidence","username","UpgradeCustodyRecipient"));AssetCustody.save(db,asset,Values.map("operation","return","revision",2,"record_revision",2,"reason","Retained return evidence","stockroom_id",stock));AssetCustody.save(db,asset,Values.map("operation","retire","revision",3,"record_revision",3,"reason","Retained retirement evidence","disposition","donated"));db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);assertEquals("Retained before inventory upgrade",Values.object(Service.readRecord(db,"incident",retained).get("data")).get("short_description"));var p=AssetCustody.page(db,asset);assertEquals("retired",Values.object(p.get("record")).get("state"));assertEquals(4,Values.objects(p.get("history")).size());assertEquals(ci,Values.object(p.get("ci")).get("sys_id"));assertEquals("Retained retirement evidence",Values.object(Values.object(p.get("movement")).get("detail")).get("reason"));assertEquals("UpgradeCustodyRecipient",Values.object(Values.objects(p.get("history")).get(2).get("assignee")).get("name"));assertEquals(stock,Values.object(Values.objects(p.get("history")).get(1).get("stockroom")).get("sys_id"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void inboundMailUpgradeRetainsRequestThreads(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String retained,mailbox,record,thread,gateway;
        try(var db=store.open()){PlatformFoundationTest.bind(db);retained=Service.create(db,"incident",Values.map("short_description","Retained before mail upgrade"),"admin").get("sys_id").toString();db.update("DROP TABLE os_inbound_mail_receipt");db.update("DROP TABLE os_inbound_mail_thread");db.update("DROP TABLE os_inbound_mailbox");db.update("DELETE FROM os_migration WHERE version>=72");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_inbound_mailbox"));db.connection.rollback();assertEquals(71,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);Service.createAccount(db,Values.map("name","UpgradeMailGateway","role","agent","password",WorkflowSupport.PASSWORD));Service.createAccount(db,Values.map("name","UpgradeMailSender","role","requester","password",WorkflowSupport.PASSWORD));gateway=RecordAccess.owner(db,"UpgradeMailGateway");db.update("UPDATE os_user SET api_only=1 WHERE user_id=?",gateway);db.update("UPDATE os_user SET email='upgrade@example.test',email_verified=1 WHERE name='UpgradeMailSender'");String token=ApiKeyWrites.issue(db,db.viewerId,gateway,Values.map("label","Upgrade mail gateway","table","sc_request","days",1)).get("token").toString();mailbox=Values.object(InboundMail.configure(db,"",Values.map("revision",0,"address","upgrade-help@opensoft.test","sender_domain","example.test","gateway_username","UpgradeMailGateway","active",true)).get("mailbox")).get("mailbox_id").toString();var caller=ApiKeys.authenticate(db,"Bearer "+token,InboundMailAcceptance.api(mailbox),"POST");db.viewerId=gateway;db.viewerRole="agent";var message=InboundMailAcceptance.message("upgrade-first","");message.put("recipient","upgrade-help@opensoft.test");message.put("sender","upgrade@example.test");var first=InboundMail.submit(db,caller,mailbox,message);record=Values.object(first.get("record")).get("sys_id").toString();thread=first.get("thread_id").toString();message.put("delivery_id","upgrade-reply");message.put("thread_id",thread);message.put("text","Retained follow-up from email");InboundMail.submit(db,caller,mailbox,message);assertEquals(gateway,db.viewerId);db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);assertEquals("Retained before mail upgrade",Values.object(Service.readRecord(db,"incident",retained).get("data")).get("short_description"));var p=InboundMail.adminPage(db,mailbox);assertEquals(2,Values.objects(p.get("receipts")).size());assertEquals(record,Values.object(Values.objects(p.get("receipts")).getFirst().get("record")).get("sys_id"));assertEquals(thread,db.one("SELECT thread_id FROM os_inbound_mail_thread WHERE record_id=?",record).get("thread_id"));assertEquals(gateway,Values.object(p.get("mailbox")).get("gateway_id"));assertEquals(1,db.count("SELECT COUNT(*) FROM os_event WHERE record_id=? AND action='comment'",record));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void triageUpgradeRetainsProposalsAndReceipts(boolean postgres)throws Exception{
        var env=configuration(postgres);var store=new Store(env);String retained,incident,group,actor,run;
        try(var db=store.open()){PlatformFoundationTest.bind(db);retained=Service.create(db,"incident",Values.map("short_description","Retained before triage upgrade"),"admin").get("sys_id").toString();db.update("DROP TABLE os_ai_triage");db.update("DELETE FROM os_migration WHERE version>=73");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_ai_triage"));db.connection.rollback();assertEquals(72,db.count("SELECT MAX(version) FROM os_migration"));}
        store=new Store(env);try(var db=store.open()){PlatformFoundationTest.bind(db);Service.createAccount(db,Values.map("name","UpgradeTriageAgent","role","agent","password",WorkflowSupport.PASSWORD));actor=RecordAccess.owner(db,"UpgradeTriageAgent");group=Service.create(db,"sys_user_group",Values.map("short_description","Retained triage group"),"admin").get("sys_id").toString();db.update("INSERT INTO os_group_member(group_id,user_id) VALUES(?,?)",group,actor);incident=Service.create(db,"incident",Values.map("short_description","Retained incident for triage","description","Current observed impact"),"admin").get("sys_id").toString();db.connection.commit();}
        try(var provider=new RecordQuestionsAcceptance.Provider(store,env)){provider.settings.put("AI_TRIAGE_ENABLED","true");provider.answer.set(Values.JSON.writeValueAsString(IncidentTriageAcceptance.draft(group)));try(var db=store.open()){db.viewerId=actor;db.viewerRole="agent";String token=IncidentTriage.generate(store,db,incident,Values.map("operation","preview")).get("preview_token").toString();run=Values.object(IncidentTriage.generate(store,db,incident,IncidentTriageAcceptance.generate(token)).get("run")).get("run_id").toString();db.connection.commit();}store=new Store(env);try(var db=store.open()){db.viewerId=actor;db.viewerRole="agent";assertEquals("proposed",Values.object(IncidentTriage.page(store,db,incident,run).get("run")).get("state"));var proposal=IncidentTriageAcceptance.draft(group);String approval=IncidentTriage.command(store,db,incident,run,Values.map("operation","review","proposal",proposal)).get("action_token").toString();IncidentTriage.command(store,db,incident,run,IncidentTriageAcceptance.execution(proposal,approval));db.connection.commit();}assertEquals(1,provider.calls.get());for(int n=0;n<2;n++){store=new Store(env);try(var db=store.open()){db.viewerId=actor;db.viewerRole="agent";assertEquals("Retained before triage upgrade",Values.object(Service.readRecord(db,"incident",retained).get("data")).get("short_description"));var page=IncidentTriage.page(store,db,incident,run);assertEquals("completed",Values.object(page.get("run")).get("state"));assertEquals(group,Values.object(page.get("receipt")).get("group_id"));assertEquals("2",Values.object(page.get("record")).get("priority"));assertEquals(1,db.count("SELECT COUNT(*) FROM os_event WHERE record_id=? AND action='ai.triage_applied'",incident));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}}}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void discoveryUpgradeRetainsReconciliation(boolean postgres)throws Exception {
        var env=configuration(postgres);var store=new Store(env);String retained,source,record,receipt;
        try(var db=store.open()){PlatformFoundationTest.bind(db);retained=Service.create(db,"incident",Values.map("short_description","Retained before discovery upgrade"),"admin").get("sys_id").toString();for(String table:java.util.List.of("os_discovery_field","os_discovery_report","os_discovery_identity","os_discovery_source"))db.update("DROP TABLE "+table);db.update("DELETE FROM os_migration WHERE version>=74");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_discovery_source"));db.connection.rollback();assertEquals(73,db.count("SELECT MAX(version) FROM os_migration"));}
        store=new Store(env);try(var e=new VulnerabilityResponseAcceptance.Endpoint(store,env)) {
            e.payload.set(Values.JSON.writeValueAsString(DiscoveryReconciliationAcceptance.report(1,java.util.List.of(DiscoveryReconciliationAcceptance.entry("UPGRADE-DISCOVERY","Retained observed value")))));
            try(var db=store.open()){PlatformFoundationTest.bind(db);source=Values.object(DiscoveryReconciliation.configure(db,"",DiscoveryReconciliationAcceptance.config(e.provider.alias,0,10)).get("source")).get("source_id").toString();var page=DiscoveryReconciliation.adminPage(db,source);var result=DiscoveryReconciliation.ingest(store,db,source,Values.map("revision",1,"connector_review",page.get("connector_review")));receipt=result.get("report_id").toString();record=DiscoveryReconciliationAcceptance.record(result);db.connection.commit();}
            for(int n=0;n<2;n++){store=new Store(env);try(var db=store.open()){PlatformFoundationTest.bind(db);assertEquals("Retained before discovery upgrade",Values.object(Service.readRecord(db,"incident",retained).get("data")).get("short_description"));assertEquals("UPGRADE-DISCOVERY",DiscoveryReconciliation.ciPage(db,record).get("serial"));assertEquals(4,Values.objects(DiscoveryReconciliation.ciPage(db,record).get("fields")).size());assertEquals("Retained observed value",Values.object(DiscoveryReconciliationAcceptance.outcome(DiscoveryReconciliation.receipt(db,receipt)).get("record")).get("description"));assertEquals(1,Values.objects(DiscoveryReconciliation.adminPage(db,source).get("reports")).size());assertEquals(1,db.count("SELECT COUNT(*) FROM os_event WHERE record_id=? AND action='discovery.observed'",record));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}}
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void typedRelationshipUpgradeRetainsMeaningAndHistory(boolean postgres)throws Exception {
        var env=configuration(postgres);var store=new Store(env);String source,target,kind,retained;
        try(var db=store.open()){PlatformFoundationTest.bind(db);retained=Service.create(db,"incident",Values.map("short_description","Retained before typed relationships"),"admin").get("sys_id").toString();source=Service.create(db,"cmdb_ci",Values.map("short_description","Retained source CI"),"admin").get("sys_id").toString();target=Service.create(db,"cmdb_ci",Values.map("short_description","Retained target CI"),"admin").get("sys_id").toString();AssetOperations.link(db,db.viewerId,Values.map("dependent",source,"dependency",target,"remove",false));for(String table:java.util.List.of("os_relation_history","os_typed_relation","os_relation_type"))db.update("DROP TABLE "+table);db.update("DELETE FROM os_migration WHERE version>=75");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_relation_type"));db.connection.rollback();assertEquals(74,db.count("SELECT MAX(version) FROM os_migration"));}
        store=new Store(env);try(var db=store.open()){PlatformFoundationTest.bind(db);kind=Values.object(TypedRelationships.configure(db,"",TypedRelationshipsAcceptance.config(0,true)).get("type")).get("type_id").toString();var review=TypedRelationships.review(db,"cmdb_ci",source,java.util.Map.of("type_id",kind,"direction","outbound","related_id",target));var command=Values.object(review.get("command"));command.put("reason","Retained upgraded relationship evidence");TypedRelationships.change(db,"cmdb_ci",source,command);db.connection.commit();}
        for(int n=0;n<2;n++){store=new Store(env);try(var db=store.open()){PlatformFoundationTest.bind(db);assertEquals("Retained before typed relationships",Values.object(Service.readRecord(db,"incident",retained).get("data")).get("short_description"));var edge=Values.objects(TypedRelationships.page(db,"cmdb_ci",source).get("links")).getFirst();assertEquals(1,edge.get("active"));assertEquals("Runs on <script>",edge.get("label"));assertEquals("Retained upgraded relationship evidence",Values.object(Values.objects(edge.get("history")).getFirst().get("detail")).get("reason"));assertEquals(1,Values.objects(AssetOperations.impact(db,target).get("impacted")).size());assertEquals(1,db.count("SELECT COUNT(*) FROM os_dependency WHERE dependent=? AND dependency=?",source,target));assertEquals(1,db.count("SELECT COUNT(*) FROM os_event WHERE record_id=? AND action='relationship.changed'",source));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void outageUpgradeRetainsRecoveryAndEvidence(boolean postgres)throws Exception {
        var env=configuration(postgres);var store=new Store(env);String ci,retained,outage;
        try(var db=store.open()){PlatformFoundationTest.bind(db);retained=Service.create(db,"incident",Values.map("short_description","Retained before outage upgrade"),"admin").get("sys_id").toString();ci=Service.create(db,"cmdb_ci",Values.map("short_description","Retained availability CI"),"admin").get("sys_id").toString();db.update("DROP TABLE os_outage_history");db.update("DROP TABLE os_ci_outage");db.update("DELETE FROM os_migration WHERE version>=76");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_ci_outage"));db.connection.rollback();assertEquals(75,db.count("SELECT MAX(version) FROM os_migration"));}
        store=new Store(env);try(var db=store.open()){PlatformFoundationTest.bind(db);var body=ServiceOutagesAcceptance.body("outage",ServiceOutagesAcceptance.at(0),"");outage=ServiceOutages.save(db,ci,body,System.currentTimeMillis()).get("outage_id").toString();db.connection.commit();}
        store=new Store(env);try(var db=store.open()){PlatformFoundationTest.bind(db);assertEquals("ongoing",ServiceOutagesAcceptance.entry(ServiceOutages.page(db,ci,java.util.Map.of(),System.currentTimeMillis()),outage).get("status"));ServiceOutages.save(db,ci,Values.map("operation","close","record_revision",0,"outage_id",outage,"revision",1,"end",ServiceOutagesAcceptance.at(50),"evidence","Retained recovery evidence"),System.currentTimeMillis());db.connection.commit();}
        for(int n=0;n<2;n++){store=new Store(env);try(var db=store.open()){PlatformFoundationTest.bind(db);assertEquals("Retained before outage upgrade",Values.object(Service.readRecord(db,"incident",retained).get("data")).get("short_description"));var page=ServiceOutages.page(db,ci,java.util.Map.of("start",ServiceOutagesAcceptance.at(0),"end",ServiceOutagesAcceptance.at(100)),System.currentTimeMillis());assertEquals("50.0000",Values.object(page.get("report")).get("recorded_availability"));var entry=ServiceOutagesAcceptance.entry(page,outage);assertEquals("ended",entry.get("status"));assertEquals(2,Values.objects(entry.get("history")).size());assertEquals("Retained recovery evidence",Values.object(Values.objects(entry.get("history")).getFirst().get("detail")).get("evidence"));assertEquals(2,db.count("SELECT COUNT(*) FROM os_event WHERE record_id=? AND action LIKE 'outage.%'",ci));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void slaEscalationUpgradeRetainsUnknownBudgetsAndReceipts(boolean postgres)throws Exception {
        var env=configuration(postgres);var store=new Store(env);String record,old,newClock;long start=SlaEscalationsAcceptance.START;
        try(var db=store.open()){String actor=PlatformFoundationTest.bind(db);record=Service.create(db,"incident",Values.map("short_description","Retained service commitment"),"admin").get("sys_id").toString();ServiceClocks.calendar(db,actor,Values.map("name","UpgradeWarnings","calendar",PlatformFoundationTest.calendar()));old=ServiceClocks.start(db,actor,Values.map("table","incident","record_id",record,"calendar","UpgradeWarnings","minutes",4),start).get("sla_id").toString();for(String table:java.util.List.of("os_sla_threshold","os_sla_escalation","os_sla_budget"))db.update("DROP TABLE "+table);db.update("DELETE FROM os_migration WHERE version>=77");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_sla_budget"));db.connection.rollback();assertEquals(76,db.count("SELECT MAX(version) FROM os_migration"));}
        store=new Store(env);try(var db=store.open()){String actor=PlatformFoundationTest.bind(db);assertEquals(0L,((Number)Values.object(SlaEscalations.page(db,old).get("clock")).get("budget_ms")).longValue());assertFalse((Boolean)SlaEscalations.page(db,old).get("can_configure"));newClock=ServiceClocks.start(db,actor,Values.map("table","incident","record_id",record,"calendar","UpgradeWarnings","minutes",4),start).get("sla_id").toString();SlaEscalations.save(db,newClock,SlaEscalationsAcceptance.plan(SlaEscalationsAcceptance.step(50,"owner"),SlaEscalationsAcceptance.step(75,"owner")),start);db.connection.commit();}
        SlaAlertsAcceptance.tick(store,start+120000);store=new Store(env);SlaAlertsAcceptance.tick(store,start+180000);
        for(int n=0;n<2;n++){store=new Store(env);SlaAlertsAcceptance.tick(store,start+180001);try(var db=store.open()){PlatformFoundationTest.bind(db);assertEquals("Retained service commitment",Values.object(Service.readRecord(db,"incident",record).get("data")).get("short_description"));assertEquals(2,Values.objects(SlaEscalations.page(db,newClock).get("receipts")).size());assertEquals(2,db.count("SELECT COUNT(*) FROM os_notice_target WHERE target_id=?",newClock));assertEquals(0,db.count("SELECT COUNT(*) FROM os_sla_budget WHERE sla_id=?",old));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}}
        SlaAlertsAcceptance.tick(store,start+240000);try(var db=store.open()){assertEquals("breached",db.one("SELECT state FROM os_sla WHERE sla_id=?",old).get("state"));assertEquals(1,db.count("SELECT COUNT(*) FROM os_notice_target WHERE target_id=?",old));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void conditionalClockUpgradeRetainsPolicyAndLifecycle(boolean postgres)throws Exception {
        var env=configuration(postgres);var store=new Store(env);String retained,record,policy,clock;long start=System.currentTimeMillis();
        try(var db=store.open()){String actor=PlatformFoundationTest.bind(db);retained=Service.create(db,"incident",Values.map("short_description","Retained before clock policies"),"admin").get("sys_id").toString();ServiceClocks.calendar(db,actor,Values.map("name","PolicyUpgrade","calendar",PlatformFoundationTest.calendar()));for(String table:java.util.List.of("os_clock_transition","os_clock_binding","os_clock_policy"))db.update("DROP TABLE "+table);db.update("DELETE FROM os_migration WHERE version>=78");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_clock_policy"));db.connection.rollback();assertEquals(77,db.count("SELECT MAX(version) FROM os_migration"));}
        store=new Store(env);try(var db=store.open()){PlatformFoundationTest.bind(db);policy=Values.object(ConditionalClocks.save(db,"",ConditionalClocksAcceptance.config("PolicyUpgrade",0)).get("policy")).get("policy_id").toString();record=Service.create(db,"incident",Values.map("short_description","Upgraded automatic clock","priority","1"),"admin").get("sys_id").toString();clock=db.one("SELECT sla_id FROM os_clock_binding WHERE record_id=?",record).get("sla_id").toString();Service.update(db,"incident",Service.record(db,"incident",record),Values.map("state","2"),"admin","admin",null);Service.update(db,"incident",Service.record(db,"incident",record),Values.map("state","3"),"admin","admin",null);db.connection.commit();}
        for(int n=0;n<2;n++){store=new Store(env);try(var db=store.open()){PlatformFoundationTest.bind(db);assertEquals("Retained before clock policies",Values.object(Service.readRecord(db,"incident",retained).get("data")).get("short_description"));assertEquals("paused",db.one("SELECT state FROM os_sla WHERE sla_id=?",clock).get("state"));assertEquals(2,Values.objects(ConditionalClocks.history(db,"incident",record).getFirst().get("history")).size());assertEquals(1,db.count("SELECT COUNT(*) FROM os_sla WHERE record_id=?",record));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}}
        try(var db=store.open()){PlatformFoundationTest.bind(db);Service.update(db,"incident",Service.record(db,"incident",record),Values.map("state","2"),"admin","admin",null);Service.update(db,"incident",Service.record(db,"incident",record),Values.map("state","6"),"admin","admin",null);db.connection.commit();assertEquals("completed",db.one("SELECT state FROM os_sla WHERE sla_id=?",clock).get("state"));assertEquals(4,Values.objects(ConditionalClocks.history(db,"incident",record).getFirst().get("history")).size());}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void eventWorkflowUpgradeRetainsPendingAndExecutedActions(boolean postgres)throws Exception {
        var env=configuration(postgres);var store=new Store(env);String retained,record,run;
        try(var db=store.open()){PlatformFoundationTest.bind(db);retained=Service.create(db,"incident",Values.map("short_description","Retained before event workflows"),"admin").get("sys_id").toString();for(String table:java.util.List.of("os_event_run","os_event_flow"))db.update("DROP TABLE "+table);db.update("DELETE FROM os_migration WHERE version>=79");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_event_flow"));db.connection.rollback();assertEquals(78,db.count("SELECT MAX(version) FROM os_migration"));}
        store=new Store(env);try(var db=store.open()){PlatformFoundationTest.bind(db);EventWorkflows.save(db,"",EventWorkflowsAcceptance.config(0,"created","1",java.util.List.of(EventWorkflowsAcceptance.note())));record=Service.create(db,"incident",Values.map("short_description","Upgraded event workflow"),"admin").get("sys_id").toString();run=db.one("SELECT run_id FROM os_event_run WHERE record_id=?",record).get("run_id").toString();db.connection.commit();}
        store=new Store(env);try(var db=store.open()){assertEquals("pending",db.one("SELECT state FROM os_event_run WHERE run_id=?",run).get("state"));}assertTrue(new WorkWorker(store).runOne(System.currentTimeMillis()+1000));
        for(int n=0;n<2;n++){store=new Store(env);assertFalse(new WorkWorker(store).runOne(System.currentTimeMillis()+1000));try(var db=store.open()){PlatformFoundationTest.bind(db);assertEquals("Retained before event workflows",Values.object(Service.readRecord(db,"incident",retained).get("data")).get("short_description"));assertEquals("succeeded",db.one("SELECT state FROM os_event_run WHERE run_id=?",run).get("state"));assertEquals(1,db.count("SELECT COUNT(*) FROM os_event WHERE record_id=? AND action='note'",record));assertEquals(1,Values.objects(EventWorkflows.history(db,"incident",record).get("runs")).size());assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void signedIntegrationUpgradeRetainsEncryptedKeyAndReceipts(boolean postgres)throws Exception {
        var env=configuration(postgres);var store=new Store(env);String retained,profile,secret,gateway,token;byte[] body=Values.JSON.writeValueAsBytes(SignedEventsAcceptance.payload());
        try(var db=store.open()){PlatformFoundationTest.bind(db);retained=Service.create(db,"incident",Values.map("short_description","Retained before signed intake"),"admin").get("sys_id").toString();for(String table:java.util.List.of("os_signed_receipt","os_signed_profile"))db.update("DROP TABLE "+table);db.update("DELETE FROM os_migration WHERE version>=80");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_signed_profile"));db.connection.rollback();assertEquals(79,db.count("SELECT MAX(version) FROM os_migration"));}
        store=new Store(env);try(var db=store.open()){PlatformFoundationTest.bind(db);Service.createAccount(db,Values.map("name","EventGateway","password",WorkflowSupport.PASSWORD,"role","agent"));gateway=RecordAccess.owner(db,"EventGateway");db.update("UPDATE os_user SET api_only=1 WHERE user_id=?",gateway);token=ApiKeyWrites.issue(db,db.viewerId,gateway,Values.map("label","Upgrade signed intake","table","incident","days",1)).get("token").toString();var result=SignedEvents.save(store,db,"",SignedEventsAcceptance.config(0));profile=Values.object(result.get("profile")).get("profile_id").toString();secret=result.get("issued_secret").toString();db.connection.commit();}
        String record="";for(int n=0;n<3;n++){store=new Store(env);try(var db=store.open()){var caller=ApiKeys.authenticate(db,"Bearer "+token,SignedEventsAcceptance.path(profile),"POST");db.viewerId=caller.get("user_id").toString();db.viewerRole="agent";String time=Long.toString(System.currentTimeMillis()/1000);var result=SignedEvents.receive(store,db,caller,"incident",profile,"application/json",java.util.List.of("upgrade-event"),java.util.List.of(time),java.util.List.of(SignedEventsAcceptance.signature(secret,time,profile,"upgrade-event",body)),body,System.currentTimeMillis());assertEquals(n>0,result.get("duplicate"));String current=Values.object(result.get("record")).get("sys_id").toString();if(n==0)record=current;else assertEquals(record,current);assertEquals(1,db.count("SELECT COUNT(*) FROM os_signed_receipt WHERE profile_id=?",profile));assertEquals("Retained before signed intake",Values.object(Service.readRecord(db,"incident",retained).get("data")).get("short_description"));assertFalse(db.one("SELECT envelope FROM os_signed_profile WHERE profile_id=?",profile).get("envelope").toString().contains(secret));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));db.connection.commit();}}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void federationUpgradeRetainsBindingsAndConsumesRestartedChallenge(boolean postgres)throws Exception {
        var env=configuration(postgres);var store=new Store(env);String retained;
        try(var db=store.open()){PlatformFoundationTest.bind(db);retained=Service.create(db,"incident",Values.map("short_description","Retained before federation"),"admin").get("sys_id").toString();for(String table:java.util.List.of("os_oidc_challenge","os_oidc_binding"))db.update("DROP TABLE "+table);db.update("DELETE FROM os_migration WHERE version>=81");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_oidc_binding"));db.connection.rollback();assertEquals(80,db.count("SELECT MAX(version) FROM os_migration"));}
        store=new Store(env);try(var provider=new OidcAcceptance.Provider(store,"http://127.0.0.1:9999")) {
            String start;try(var db=store.open()){PlatformFoundationTest.bind(db);Service.createAccount(db,Values.map("name","FederatedUser","password",WorkflowSupport.PASSWORD,"role","agent"));Oidc.bind(store,db,"",OidcAcceptance.binding(0,"FederatedUser","employee-123",false));start=Oidc.start(store,db,"retained-browser-session",System.currentTimeMillis());db.connection.commit();}
            java.util.Map<String,String> args;try(var client=java.net.http.HttpClient.newHttpClient()){var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(start)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofByteArray());args=OidcAcceptance.query(java.net.URI.create(response.headers().firstValue("location").orElseThrow()).getRawQuery());}
            store=new Store(env);try(var db=store.open()){var user=Oidc.complete(store,db,"retained-browser-session",args,System.currentTimeMillis());assertEquals("FederatedUser",user.get("name"));assertEquals(0,db.count("SELECT COUNT(*) FROM os_oidc_challenge"));db.connection.commit();}
            for(int n=0;n<2;n++){store=new Store(env);final var current=store;try(var db=store.open()){assertThrows(Values.Failure.class,()->Oidc.complete(current,db,"retained-browser-session",args,System.currentTimeMillis()));PlatformFoundationTest.bind(db);assertEquals(1,db.count("SELECT COUNT(*) FROM os_oidc_binding"));assertEquals("Retained before federation",Values.object(Service.readRecord(db,"incident",retained).get("data")).get("short_description"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}}
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void authenticatorUpgradeRetainsVerifiedFactorAndSingleUseRecovery(boolean postgres)throws Exception {
        var env=configuration(postgres);var store=new Store(env);String retained;
        try(var db=store.open()){PlatformFoundationTest.bind(db);retained=Service.create(db,"incident",Values.map("short_description","Retained before authenticator"),"admin").get("sys_id").toString();for(String table:List.of("os_totp_recovery","os_totp_setup","os_totp_challenge","os_totp_attempt","os_totp"))db.update("DROP TABLE "+table);db.update("DELETE FROM os_migration WHERE version>=82");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_totp"));db.connection.rollback();assertEquals(81,db.count("SELECT MAX(version) FROM os_migration"));}
        store=new Store(env);String code;long now=System.currentTimeMillis();try(var db=store.open()){PlatformFoundationTest.bind(db);Service.createAccount(db,Values.map("name","FactorUpgrade","password",WorkflowSupport.PASSWORD,"role","agent"));var user=TotpAcceptance.user(db,"FactorUpgrade");TotpAcceptance.install(store,db,"FactorUpgrade",TotpAcceptance.SEED,-1);code=Totp.recovery(db,user.get("user_id").toString()).getFirst();Totp.start(store,db,user,"retained-factor-browser",now);db.connection.commit();}
        store=new Store(env);try(var db=store.open()){assertEquals("FactorUpgrade",Totp.complete(store,db,"retained-factor-browser",Values.map("code",code),now).get("name"));db.connection.commit();}
        for(int n=0;n<2;n++){store=new Store(env);final var current=store;try(var db=store.open()){assertEquals(401,assertThrows(Values.Failure.class,()->Totp.complete(current,db,"retained-factor-browser",Values.map("code",code),now)).status);assertEquals(9,db.count("SELECT COUNT(*) FROM os_totp_recovery"));assertEquals(1,db.count("SELECT COUNT(*) FROM os_totp"));assertEquals(TotpAcceptance.SEED,Totp.seed(store,db.one("SELECT envelope FROM os_totp").get("envelope").toString()));PlatformFoundationTest.bind(db);assertEquals("Retained before authenticator",Values.object(Service.readRecord(db,"incident",retained).get("data")).get("short_description"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void offboardingUpgradeRetainsHandoffAndPriorViewerHistory(boolean postgres)throws Exception {
        var env=configuration(postgres);var store=new Store(env);String retained;
        try(var db=store.open()){PlatformFoundationTest.bind(db);retained=Service.create(db,"incident",Values.map("short_description","Retained before handoff"),"admin").get("sys_id").toString();db.update("DROP TABLE os_offboarding");db.update("DELETE FROM os_migration WHERE version>=83");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_offboarding"));db.connection.rollback();assertEquals(82,db.count("SELECT MAX(version) FROM os_migration"));}
        store=new Store(env);var fixture=OffboardingAcceptance.seed(store,"Upgrade");try(var db=store.open()){PlatformFoundationTest.bind(db);var preview=Offboarding.preview(db,fixture.get("source").toString(),fixture.get("to").toString());Offboarding.apply(db,fixture.get("source").toString(),OffboardingAcceptance.body(preview));db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);assertEquals(1,Values.objects(Offboarding.page(db,fixture.get("source").toString(),"").get("history")).size());assertEquals(0,((Number)Offboarding.account(db,fixture.get("source").toString()).get("active")).intValue());assertEquals(fixture.get("target"),db.one("SELECT owner_id FROM os_saved_report WHERE report_id=?",fixture.get("report")).get("owner_id"));assertEquals(fixture.get("source"),db.one("SELECT viewer_id FROM os_report_measurement WHERE report_id=?",fixture.get("report")).get("viewer_id"));assertEquals("Retained before handoff",Values.object(Service.readRecord(db,"incident",retained).get("data")).get("short_description"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void directoryUpgradeRetainsScopeAndRevocation(boolean postgres)throws Exception {
        var env=configuration(postgres);var store=new Store(env);String retained;
        try(var db=store.open()){PlatformFoundationTest.bind(db);retained=Service.create(db,"incident",Values.map("short_description","Retained before directory"),"admin").get("sys_id").toString();db.update("DROP TABLE os_directory");db.update("DELETE FROM os_migration WHERE version>=84");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_directory"));db.connection.rollback();assertEquals(83,db.count("SELECT MAX(version) FROM os_migration"));}
        store=new Store(env);java.util.Map<String,Object> issued;String target;
        try(var db=store.open()){PlatformFoundationTest.bind(db);issued=Scim.manage(db,"",Values.map("name","Upgrade directory","role","reader","groups",List.of()));var source=Scim.authenticate(db,issued.get("source_id").toString(),"Bearer "+issued.get("token"));target=Scim.write(db,source,"","POST",ScimAcceptance.user("UpgradeDirectory","preserved-external",true),"https://example.test").get("id").toString();Scim.write(db,source,target,"PATCH",ScimAcceptance.patch(false),"https://example.test");db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){var source=Scim.authenticate(db,issued.get("source_id").toString(),"Bearer "+issued.get("token"));assertEquals(false,Scim.resource(Scim.identity(db,issued.get("source_id").toString(),target),issued.get("source_id").toString(),"https://example.test").get("active"));assertEquals("preserved-external",Scim.identity(db,issued.get("source_id").toString(),target).get("external_id"));assertEquals("Retained before directory",Values.object(Service.readRecord(db,"incident",retained).get("data")).get("short_description"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=store.open()){PlatformFoundationTest.bind(db);Scim.manage(db,issued.get("source_id").toString(),Values.map("operation","disable","revision",1));db.connection.commit();}try(var db=new Store(env).open()){assertEquals(401,assertThrows(Values.Failure.class,()->Scim.authenticate(db,issued.get("source_id").toString(),"Bearer "+issued.get("token"))).status);}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void groupReviewUpgradeRetainsStagesAndCurrentAuthority(boolean postgres)throws Exception {
        var env=configuration(postgres);var store=new Store(env);String retained;
        try(var db=store.open()){PlatformFoundationTest.bind(db);retained=Service.create(db,"incident",Values.map("short_description","Retained before group reviews"),"admin").get("sys_id").toString();db.update("DROP TABLE os_approval_group");db.update("DELETE FROM os_migration WHERE version>=85");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_approval_group"));db.connection.rollback();assertEquals(84,db.count("SELECT MAX(version) FROM os_migration"));}
        store=new Store(env);var f=GroupApprovalsAcceptance.seed(store,"GroupUpgrade");String table=f.get("table").toString(),record=f.get("id").toString();
        try(var db=store.open()){for(String reviewer:List.of("a","c")){var actor=ApprovalTest.as(db,"admin");GroupApprovals.request(db,table,record,actor,Values.map("expected_revision",0,"target_state","2","reason","Upgrade group review"));String approval=db.one("SELECT approval_id FROM os_approval WHERE record_id=? AND approver_name=? AND status='requested'",record,f.get(reviewer)).get("approval_id").toString();Approvals.decide(db,table,record,approval,ApprovalTest.as(db,f.get(reviewer).toString()),Values.map("decision","approved","comment","Retained group decision"));}db.connection.commit();}
        for(int n=0;n<2;n++)try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);assertEquals(4,db.count("SELECT COUNT(*) FROM os_approval_group"));var requirements=ApprovalGate.requirements(db,Service.record(db,table,record),Service.schema(db,table));assertEquals(true,requirements.stream().filter(row->row.get("target_state").equals("2")).findFirst().orElseThrow().get("approval_satisfied"));assertEquals("Retained before group reviews",Values.object(Service.readRecord(db,"incident",retained).get("data")).get("short_description"));assertEquals(Migrations.VERSION,db.count("SELECT MAX(version) FROM os_migration"));}
        try(var db=store.open()){PlatformFoundationTest.bind(db);db.update("UPDATE os_user SET auth_revision=auth_revision+1 WHERE user_id=?",f.get("aId"));db.connection.commit();}try(var db=new Store(env).open()){PlatformFoundationTest.bind(db);var recordItem=Service.record(db,table,record);assertEquals(409,assertThrows(Values.Failure.class,()->Service.update(db,table,recordItem,Values.map("state","2"),"admin","admin","0")).status);}
    }

}
