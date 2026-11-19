package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseAcceptance.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static ge.opensoft.openservice.EmployeeCasesAcceptance.result;
import static ge.opensoft.openservice.TenJourneysAcceptance.post;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.time.LocalDate;

/** Permissions, replay boundaries and dated operational decisions through the enterprise API. */
final class TenPolicyAcceptance {
    static Map<String,Object> as(WorkflowSupport h,String who,String area,Map<String,Object> b,int status)throws Exception{
        var response=h.json("POST",api(area,"global"),b,auth(who));
        assertEquals(status,response.statusCode(),html(response));
        return status==200?object(result(response).get("outcome")):map();
    }
    static void teams(WorkflowSupport h)throws Exception{
        for(String person:List.of("EmployeeA","ManagerA","ReviewerA","ReviewerB","Stranger"))h.account(person,"agent");
        String group=h.add("sys_user_group").get("sys_id").toString();
        for(String person:List.of("EmployeeA","ReviewerA","ReviewerB"))RecordScopesAcceptance.member(h,group,uid(h,person),true);
        String user=uid(h,"EmployeeA"),manager=uid(h,"ManagerA"),template=id(),run=id();
        var registration=map("operation","identity","user_id",user,"manager_id",user,"directory_id",UUID.randomUUID().toString(),"revision",0);
        bad(h,"studio","global",registration,400);registration.put("manager_id",manager);post(h,"studio",registration);
        var approval=map("kind","approval","stage",1,"title","Approve transfer");
        var team=map("kind","team_approval","group",group,"stage",2,"title","Confirm second team");
        var work=map("kind","work","group",group,"stage",3,"title","Confirm receipt");
        var policy=map("operation","publish","template_id",template,"days",3,"steps",List.of(work),"revision",0,"title","Transfer with team signoff");
        bad(h,"studio","global",policy,400);
        policy.put("steps",List.of(approval,team,team,work,map("kind","team_approval","group",group,"stage",4,"title","Sign handoff")));post(h,"studio",policy);
        var request=map("operation","request","new_id",run,"template_id",template,"revision",1);
        as(h,"EmployeeA","studio",request,200);
        bad(h,"studio","global",map("operation","preview_actions","run_id",run,"revision",1,"goal","Only manual tasks"),400);
        var inspect=map("operation","inspect","run_id",run);
        assertEquals(5,objects(as(h,"ReviewerA","studio",inspect,200).get("steps")).size());
        as(h,"Stranger","studio",inspect,400);
        var stop=map("operation","stop_run","run_id",run,"note","Withdrawn","revision",1);
        as(h,"ReviewerA","studio",stop,400);
        var step=map("operation","advance","step",1,"run_id",run,"revision",1,"note","Reviewed");
        as(h,"EmployeeA","studio",step,400);
        registration.putAll(map("manager_id",uid(h,"ReviewerB"),"revision",1));post(h,"studio",registration);
        // A replacement manager cannot approve the pinned original grant.
        as(h,"ReviewerB","studio",step,400);
        registration.putAll(map("manager_id",manager,"revision",2));post(h,"studio",registration);
        as(h,"ManagerA","studio",step,200);
        step.putAll(map("step",2,"revision",2));
        as(h,"EmployeeA","studio",step,400);as(h,"ManagerA","studio",step,400);
        as(h,"ReviewerA","studio",step,200);
        step.put("revision",3);as(h,"ReviewerA","studio",step,400);
        step.put("step",3);as(h,"ReviewerA","studio",step,400);as(h,"ReviewerB","studio",step,200);
        step.putAll(map("revision",4,"step",4));as(h,"ReviewerA","studio",step,200);
        step.putAll(map("revision",5,"step",5));as(h,"ReviewerA","studio",step,200);
        step.put("revision",6);bad(h,"studio","global",step,400);
        assertEquals(5,objects(as(h,"ManagerA","studio",inspect,200).get("steps")).size());
        String second=id();request.put("new_id",second);as(h,"EmployeeA","studio",request,200);
        stop.put("run_id",second);as(h,"EmployeeA","studio",stop,200);
        String third=id();request.put("new_id",third);as(h,"EmployeeA","studio",request,200);
        stop.put("run_id",third);post(h,"studio",stop);
        assertEquals("stopped",object(doc(h,"workflow-run",third).get("definition")).get("state"));
    }

    static void studioDraft(WorkflowSupport h)throws Exception{
        bad(h,"studio","global",map("operation","unknown"),400);
        String key=id(),group=h.add("sys_user_group").get("sys_id").toString();
        try(var provider=new RecordQuestionsAcceptance.Provider(h.store,h.environment)){
            var approval=map("operation","append_approval","stage","1","title","Manager review","template_id",key,"draft_revision","0");
            assertEquals(200,h.protectedForm("/enterprise/studio/global",approval,auth("admin")).statusCode());
            post(h,"studio",map("operation","append_team","stage",2,"title","Review work","template_id",key,"draft_revision",1,"group",group,"kind","work"));
            post(h,"studio",map("operation","append_directory","stage",3,"title","Add group","template_id",key,"draft_revision",2,"target",UUID.randomUUID().toString(),"alias",provider.alias,"oauth","alias","kind","entra_add"));
            post(h,"studio",map("operation","append_issue","stage",3,"title","Record handoff","template_id",key,"draft_revision",3,"issue","OPS-1","label","ready","alias",provider.alias,"oauth","alias"));
            post(h,"studio",map("operation","remove_step","step",3,"template_id",key,"draft_revision",4));
            var publication=map("operation","publish_draft","days",7,"title","IT handoff","template_id",key,"draft_revision",5,"revision",0);
            var version=object(post(h,"studio",publication).get("definition"));
            assertEquals(3,objects(version.get("steps")).size());
            var draft=map("operation","append_approval","stage",3,"title","Approval","template_id",key);
            for(int revision=5;revision<26;revision++){draft.put("draft_revision",revision);post(h,"studio",draft);}
            draft.put("draft_revision",26);bad(h,"studio","global",draft,400);
        }
    }
    static void variance(WorkflowSupport h)throws Exception{
        h.account("FinanceApprover","admin");
        var assets=AssetCustodyAcceptance.setup(h);String order=id();
        post(h,"assets",order(order));post(h,"assets",map("operation","approve","order_id",order,"revision",1));
        post(h,"assets",map("operation","receive","order_id",order,"revision",2,"asset_id",assets[0],"stockroom_id",assets[1],"received_on",LocalDate.now().toString()));
        post(h,"assets",map("operation","invoice","order",order,"invoice","=X","currency","USD","lines",List.of(map("model","Field laptop","quantity",2,"unit_cost","1500"))));
        String key=ConnectorSynchronization.hash(List.of("Local supplier <script>","=X"));
        var settle=map("operation","settle","invoice_id",key,"revision",0);
        var first=object(post(h,"assets",settle).get("definition"));
        assertEquals("price_variance",object(object(first.get("holds")).get("Field laptop")).get("reason"));
        var accept=map("operation","accept_price","invoice_id",key,"model","Field laptop","unit_cost","1400","note","Approved by finance");
        bad(h,"assets","global",accept,400);as(h,"FinanceApprover","assets",accept,400);
        accept.put("unit_cost","1500");as(h,"FinanceApprover","assets",accept,200);
        settle.put("revision",1);var finalLedger=object(post(h,"assets",settle).get("definition"));
        assertEquals(1,object(object(finalLedger.get("holds")).get("Field laptop")).get("quantity"));
        var batch=objects(finalLedger.get("exports")).getFirst();
        var response=h.get("/finance-export/"+key+"/"+batch.get("export_id"),auth("admin"));
        assertEquals(200,response.statusCode());assertTrue(html(response).contains("'=X"));assertTrue(html(response).contains("1500.00"));
        var usage=map("operation","seat_usage","as_of",LocalDate.now().toString(),"product","Office","source","Publisher","revision",0,"observations",List.of(map("user_id",uid(h,"AssetUser"),"last_active",LocalDate.now().plusDays(1).toString())));
        bad(h,"assets","global",usage,400);
        var u=map("user_id",uid(h,"AssetUser"),"last_active",LocalDate.now().toString());
        usage.put("observations",List.of(u,u));bad(h,"assets","global",usage,400);
        usage.put("observations",List.of(u));
        for(var invalid:List.of(LocalDate.now().plusDays(1),LocalDate.now().minusDays(8))){usage.put("as_of",invalid.toString());bad(h,"assets","global",usage,400);}
        usage.put("as_of",LocalDate.now().toString());post(h,"assets",usage);
        usage.putAll(map("as_of",LocalDate.now().minusDays(1).toString(),"revision",1));bad(h,"assets","global",usage,400);
        assertTrue(objects(post(h,"assets",map("operation","reclaim","product","Office")).get("candidates")).isEmpty());
    }

    static void snapshots(WorkflowSupport h)throws Exception{
        try(var provider=new RecordQuestionsAcceptance.Provider(h.store,h.environment)){
            var source=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>();
            provider.server.createContext("/snapshot",e->{byte[] bytes=JSON.writeValueAsBytes(source.get());e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});
            String key=id(),origin="kubernetes/qa/ops";
            var node=map("id","a","title","Application","type","component","depends_on",List.of("b"));
            var back=map("id","b","title","Dependency","type","component","depends_on",List.of("a"));
            var isolated=map("id","x","title","Optional","type","component","depends_on",List.of());
            var packet=map("operation","inventory","sequence",1,"source",origin,"resources",List.of(node,back,isolated),"services",List.of(map("id","svc","title","Service","roots",List.of("a"))));
            source.set(packet);
            post(h,"integrations",map("operation","schedule","key",key,"revision",0,"kind","topology","target",origin,"alias",provider.alias,"path","/snapshot","minutes",1,"enabled",true));
            long now=System.currentTimeMillis();
            assertTrue(RecurringImports.runOne(h.store,now));
            assertEquals(1,doc(h,"service-map",origin).get("revision"));
            assertEquals(List.of(),post(h,"topology",map("operation","impact","source",origin,"resource_id","x")).get("services"));
            packet.putAll(map("sequence",2,"resources",List.of(node)));assertTrue(RecurringImports.runOne(h.store,now+120000));
            assertEquals(1,doc(h,"service-map",origin).get("revision"));
            packet.put("source","another-source");assertTrue(RecurringImports.runOne(h.store,now+300000));
            assertEquals(0,doc(h,"service-map","another-source").get("revision"));
            packet.putAll(map("operation","unexpected","source",origin));
            assertTrue(RecurringImports.runOne(h.store,now+900000));
            assertEquals(1,doc(h,"service-map",origin).get("revision"));
        }
    }
}
