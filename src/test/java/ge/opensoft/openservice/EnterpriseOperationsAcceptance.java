package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseAcceptance.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;

final class EnterpriseOperationsAcceptance {
    static Map<String,Object> outcome(WorkflowSupport h,String area,String subject,Map<String,Object> command) throws Exception {
        return object(send(h,area,subject,command).get("outcome"));
    }

    static void imports(WorkflowSupport h) throws Exception {
        String device=AssetCustodyAcceptance.setup(h)[0],user=uid(h,"AssetUser");
        var security=SecurityIncidentsAcceptance.setup(h); String caseId=security[0];
        String other=EmployeeCasesAcceptance.result(h.json("POST","/api/openservice/security/intake",
                SecurityIncidentsAcceptance.intake(security[1]),auth("admin"))).get("sys_id").toString();
        try(var provider=new RecordQuestionsAcceptance.Provider(h.store,h.environment)) {
            var source=new AtomicReference<Object>();
            provider.server.createContext("/snapshot",e->{byte[] bytes=JSON.writeValueAsBytes(source.get());e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});
            String key=id(),feed=id();
            send(h,"assets","global",map("operation","alias","alias","design pro","product","Design","revision",0));
            var schedule=map("operation","schedule","key",key,"revision",0,"alias",provider.alias,"path","/snapshot","kind","inventory","target","Design","minutes",5,"enabled",true);
            assertFalse(RecurringImports.runOne(h.store,100));
            send(h,"integrations","global",schedule);
            source.set(map("product","DESIGN PRO","installations",List.of(map("asset_id",device,"user_id",user))));
            assertTrue(RecurringImports.runOne(h.store,100));
            var state=object(doc(h,"import-schedule",key).get("definition"));
            assertEquals("succeeded",state.get("status"));
            assertFalse(RecurringImports.runOne(h.store,101));
            assertTrue(RecurringImports.runOne(new Store(h.environment),300101));
            var inventory=doc(h,"installations",ConnectorSynchronization.hash("Design"));
            assertEquals(1,inventory.get("revision"));
            source.set(map("product","wrong","installations",List.of()));
            send(h,"integrations","global",map("operation","replay","key",key,"revision",5));
            assertTrue(RecurringImports.runOne(h.store,400000));
            assertEquals("failed",object(doc(h,"import-schedule",key).get("definition")).get("status"));
            assertEquals(inventory,doc(h,"installations",ConnectorSynchronization.hash("Design")));
            assertFalse(RecurringImports.finish(h.store,key,1,400000,true,map(),false,""));
            schedule.putAll(map("enabled",false,"revision",8));send(h,"integrations","global",schedule);
            assertFalse(RecurringImports.runOne(h.store,900000));
            bad(h,"integrations","global",map("operation","replay","key",key,"revision",9),400);
            String scheduleId=id(),hash="ab".repeat(32),expired="cd".repeat(32);
            schedule.putAll(map("revision",0,"enabled",true,"key",scheduleId,"target",feed,"kind","threat"));
            send(h,"integrations","global",schedule);
            source.set(List.of(map("sha256",hash,"label","Observation","expires_on","2099-01-01"),
                    map("sha256",expired,"label","Historical","expires_on","2000-01-01")));
            assertTrue(RecurringImports.runOne(h.store,1000));
            for(String incident:List.of(caseId,other)) {
                send(h,"security",incident,map("operation","link_feed","feed",feed,"revision",0));
                send(h,"security",incident,map("operation","observe","sha256",hash,"revision",1));
            }
            var observed=outcome(h,"security",caseId,map("operation","observe","sha256",expired,"revision",2));
            assertEquals(3,objects(observed.get("matches")).size());
            assertTrue(objects(observed.get("matches")).stream().anyMatch(m->m.get("status").equals("expired")));
            source.set(List.of());assertTrue(RecurringImports.runOne(h.store,400000));
            var matches=object(send(h,"security",other,map("operation","shared_matches")).get("outcome"));
            assertTrue(objects(matches.get("matches")).stream().allMatch(m->m.get("status").equals("withdrawn")));
            send(h,"security",caseId,map("operation","observe","sha256","ef".repeat(32),"revision",3));
            source.set(List.of(map("sha256","oops")));assertTrue(RecurringImports.runOne(h.store,800000));
            assertEquals(2,doc(h,"shared-feed",feed).get("revision"));
            EnterpriseImportEdges.run(h,caseId,feed,scheduleId,provider.alias);
            provider.settings.put("CONNECTOR_ALLOWED_ORIGINS","");
            assertTrue(RecurringImports.runOne(h.store,1200000));
            assertEquals("failed",object(doc(h,"import-schedule",scheduleId).get("definition")).get("status"));
            // Two due schedules compete after a restart. The earlier checkpoint wins even if its ID sorts last.
            String later="0".repeat(31)+"1",earlier="f".repeat(32);
            for(String candidate:List.of(later,earlier)) {
                schedule.putAll(map("revision",0,"key",candidate));
                send(h,"integrations","global",schedule);
            }
            try(var db=h.store.open()) {
                PlatformFoundationTest.bind(db);
                var item=object(EnterpriseData.get(db,"import-schedule",later).get("definition"));
                item.put("next",25L);
                EnterpriseData.save(db,"import-schedule",later,1,item);
                assertEquals(earlier,RecurringImports.claim(db,50L).key());
            }
        }
    }

    static void purchasing(WorkflowSupport h) throws Exception {
        String[] fixture=AssetCustodyAcceptance.setup(h);
        String orderId=id();
        var order=order(orderId);order.put("quantity",3);
        send(h,"assets","global",order);
        send(h,"assets","global",map("operation","approve","order_id",orderId,"revision",1));
        send(h,"assets","global",map("operation","receive","order_id",orderId,"revision",2,"asset_id",fixture[0],"stockroom_id",fixture[1],"received_on",LocalDate.now().toString()));
        var bill=map("operation","invoice","order",orderId,"currency","USD","invoice","INV-1","lines",List.of(map("model","Field laptop","quantity",2,"unit_cost","1210.00")));
        var line=object(object(outcome(h,"assets","global",bill).get("lines")).get("Field laptop"));
        assertEquals(-1,line.get("received_minus_billed"));assertEquals("20.00",line.get("price_variance"));
        bad(h,"assets","global",bill,409);bill.put("invoice","INV-2");
        assertEquals(4,object(object(outcome(h,"assets","global",bill).get("lines")).get("Field laptop")).get("cumulative_invoiced"));
        EnterpriseOperationsEdges.invoice(h);
    }

    static void hr(WorkflowSupport h) throws Exception {
        var ids=EmployeeOnboardingAcceptance.setup(h);
        String caseId=ids[0],now=LocalDate.now().toString(),later=LocalDate.now().plusDays(5).toString();
        var step=map("phase","effective","summary","Suspend building access","details","Retain account","group_id",ids[1]);
        var returning=map("phase","return","summary","Restore access","details","Confirm return with HR","group_id",ids[2]);
        var record=object(data(h,"hr",caseId).get("record"));
        var change=map("operation","schedule_change","type","leave","effective",now,"return_on",later,"steps",List.of(step,returning),"revision",0,"record_revision",Integer.valueOf(record.get("sys_mod_count").toString()));
        rejectVariants(h,"hr",caseId,change,List.of(map("type","unknown"),map("return_on",now),map("steps",List.of(returning,step)),map("steps",List.of(step)),map("steps",List.of())));
        send(h,"hr",caseId,change);
        var work=EmployeeOnboardingAcceptance.bindings(h,caseId);
        String first=work.getFirst().get("child_id").toString(),second=work.getLast().get("child_id").toString();
        EmployeeOnboardingAcceptance.task(h,first,"claim","OnboardIT");EmployeeOnboardingAcceptance.task(h,first,"complete","OnboardIT");
        assertEquals(false,objects(EmployeeCasesAcceptance.get(h,EmployeeOnboardingAcceptance.tasks(second),"OnboardFacilities").get("tasks")).getFirst().get("ready"));
        assertEquals(409,h.json("POST",EmployeeOnboardingAcceptance.tasks(second),EmployeeOnboardingAcceptance.taskBody(h,second,"claim"),auth("OnboardFacilities")).statusCode());
        var move=map("operation","move_change","revision",1,"effective",now,"return_on",LocalDate.now().plusDays(9).toString(),"reason","Return postponed");
        send(h,"hr",caseId,move);
        var cancel=map("operation","cancel","confirm",true,"revision",0,"record_revision",Integer.valueOf(object(data(h,"hr",caseId).get("record")).get("sys_mod_count").toString()),"reason","Leave rescinded","completed_actions",map("1","Review access restoration"));
        send(h,"hr",caseId,cancel);
        assertEquals("complete",EmployeeOnboardingAcceptance.bindings(h,caseId).getFirst().get("status"));
        assertEquals("waived",EmployeeOnboardingAcceptance.bindings(h,caseId).getLast().get("status"));
        EnterpriseOperationsEdges.hr(h,caseId,ids[1],ids[2]);
    }

    static void portfolio(WorkflowSupport h) throws Exception {
        String p=project(h),key=id(),user=uid(h,"admin");
        send(h,"portfolio",p,map("operation","budget","revision",0,"budget","400","remaining","100","currency","USD"));
        send(h,"portfolio",p,map("operation","reserve","revision",1,"key",id(),"user_id",user,"start_on","2027-01-01","finish_on","2027-01-10","percent",100));
        var cal=map("operation","calendar","user_id",user,"revision",0,"hourly_rate","10","currency","USD","week",List.of(480,480,480,480,480,0,0),"exceptions",map("2027-01-05",60));
        send(h,"portfolio",p,cal);
        var scenario=map("operation","save_scenario","key",key,"title","January options","currency","USD","ceiling","400","option_a",List.of(p),"option_b",List.of(),"start_on","2027-01-04","finish_on","2027-01-06");
        var saved=outcome(h,"portfolio",p,scenario);var option=object(object(saved.get("comparison")).get("option_a"));
        assertEquals("240.00",option.get("labor"));assertEquals("60.00",option.get("funding_after_labor"));
        assertEquals("2027-01-05",objects(option.get("overbooked")).getFirst().get("day"));
        cal.putAll(map("revision",1,"hourly_rate","100"));send(h,"portfolio",p,cal);
        assertEquals(saved,outcome(h,"portfolio",p,map("operation","open_scenario","key",key)));
        bad(h,"portfolio",p,scenario,409);
        EnterpriseOperationsEdges.portfolio(h,p,scenario,cal);
    }
}
