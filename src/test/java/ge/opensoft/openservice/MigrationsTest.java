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
        try(var db=store.open()){PlatformFoundationTest.bind(db);parent=Service.create(db,"incident",Values.map("short_description","Retained case"),"admin").get("sys_id").toString();group=Service.create(db,"sys_user_group",Values.map("short_description","Retained team"),"admin").get("sys_id").toString();db.update("DROP TABLE os_field_visit");db.update("DROP TABLE os_field_dispatch");db.update("DROP TABLE os_case_task");db.update("DROP TABLE os_task_plan");db.update("DELETE FROM os_migration WHERE version>=55");db.connection.commit();Migrations.apply(db);assertEquals(0,db.count("SELECT COUNT(*) FROM os_case_task"));db.connection.rollback();assertEquals(54,db.count("SELECT MAX(version) FROM os_migration"));}
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
                    assertEquals(10, db.count("SELECT COUNT(*) FROM os_schema"));
                    assertNotNull(db.one("SELECT name FROM os_schema WHERE name='risk'"));
                    assertNotNull(db.one("SELECT name FROM os_schema WHERE name='hr_case'"));
                    assertNotNull(db.one("SELECT name FROM os_schema WHERE name='customer_case'"));
                }
            }
        }
    }
}
