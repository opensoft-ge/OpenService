package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseAcceptance.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static ge.opensoft.openservice.TenJourneysAcceptance.post;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.*;

final class TenRecoveryAcceptance {
    static Map<String,Object> copy(Map<String,Object> value){return object(JSON.readValue(JSON.writeValueAsString(value),Object.class));}
    static void store(WorkflowSupport h,String area,String key,Map<String,Object> data)throws Exception{
        try(var db=h.store.open()){
            PlatformFoundationTest.bind(db);EnterpriseData.save(db,area,key,EnterpriseData.get(db,area,key).get("revision"),data);
            db.connection.commit();
        }
    }
    static void recovery(WorkflowSupport h)throws Exception{
        h.account("RecoveryAgent","agent");h.account("RecoveryManager","agent");
        String agent=uid(h,"RecoveryAgent"),manager=uid(h,"RecoveryManager"),user=uid(h,"admin"),template=id(),run=id();
        String group=h.add("sys_user_group").get("sys_id").toString();RecordScopesAcceptance.member(h,group,manager,true);
        try(var db=h.store.open()){db.update("UPDATE os_user SET api_only=1 WHERE user_id=?",agent);db.connection.commit();}
        var registration=map("operation","identity","user_id",user,"manager_id",manager,"directory_id",UUID.randomUUID().toString(),"revision",0);
        post(h,"studio",registration);
        try(var provider=new RecordQuestionsAcceptance.Provider(h.store,h.environment)){
            var readCode=new AtomicInteger(200);var writeCode=new AtomicInteger(429);var present=new AtomicBoolean(true);
            provider.server.createContext("/rest/api/3/issue/OPS-2",e->{
                int status=e.getRequestMethod().equals("PUT")?writeCode.get():readCode.get();
                if(status==429)e.getResponseHeaders().set("Retry-After","later");
                byte[] bytes=JSON.writeValueAsBytes(map("key","OPS-2","fields",map("labels",present.get()?List.of("ready"):List.of())));
                e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();
            });
            var approve=map("stage",1,"kind","approval","title","Authorize");
            var label=map("stage",2,"kind","jira_label","title","Mark ready","alias",provider.alias,"oauth","alias","issue","OPS-2","label","ready");
            post(h,"studio",map("operation","publish","title","Reviewed dispatch","days",2,"steps",List.of(approve,label),"revision",0,"template_id",template));
            post(h,"studio",map("operation","request","new_id",run,"template_id",template,"revision",1));
            TenPolicyAcceptance.as(h,"RecoveryManager","studio",map("operation","advance","run_id",run,"step",1,"note","Approved","revision",1),200);
            var baseline=object(doc(h,"workflow-run",run).get("definition"));
            var admin=baseline.get("publisher_revision");
            baseline.putAll(map("delegate",agent,"approver",user,"approver_revision",admin,"proposed",List.of(2),"queued",true));
            try(var db=h.store.open()){baseline.put("delegate_revision",AgentPlans.account(db,agent).get("auth_revision"));}
            assertFalse(WorkflowStudio.runOne(h.store));
            provider.settings.put("AI_AGENT_EXECUTION_ENABLED","true");assertFalse(WorkflowStudio.runOne(h.store));
            provider.settings.put("CONNECTOR_WRITES_ENABLED","true");
            try(var db=h.store.open()){
                PlatformFoundationTest.bind(db);
                var step=objects(baseline.get("steps")).getLast();
                var groupApproval=map("stage",1,"kind","team_approval","group",group,"state","pending","by",manager,"signer_revision",((Number)AgentPlans.account(db,manager).get("auth_revision")).intValue());
                var augmented=copy(baseline);var checks=objects(augmented.get("steps"));checks.add(groupApproval);
                VerifiedActions.validateApprovals(db,augmented);
                groupApproval.put("state","done");VerifiedActions.validateApprovals(db,augmented);
                groupApproval.put("group",id());assertThrows(Failure.class,()->VerifiedActions.validateApprovals(db,augmented));
                var action=map("run_id",run,"step",2,"revision",2,"confirm",true);
                db.viewerId=manager;db.viewerRole="agent";
                assertThrows(Failure.class,()->VerifiedActions.authority(h.store,db,baseline,step));
                db.viewerId=agent;provider.settings.put("AI_AGENT_EXECUTION_ENABLED","false");
                assertThrows(Failure.class,()->VerifiedActions.authority(h.store,db,baseline,step));
                provider.settings.put("AI_AGENT_EXECUTION_ENABLED","true");PlatformFoundationTest.bind(db);
                assertThrows(Failure.class,()->VerifiedActions.apply(h.store,db,baseline,objects(baseline.get("steps")).getFirst(),"act",action));
                var done=copy(step);done.put("state","done");
                assertThrows(Failure.class,()->VerifiedActions.apply(h.store,db,baseline,done,"act",action));
                assertThrows(Failure.class,()->VerifiedActions.apply(h.store,db,baseline,step,"rearm",action));
                action.put("confirm",false);assertThrows(Failure.class,()->VerifiedActions.apply(h.store,db,baseline,step,"rearm",action));
                var interrupted=copy(step);
                action.put("confirm",true);
                try{
                    Thread.currentThread().interrupt();
                    VerifiedActions.apply(h.store,db,baseline,interrupted,"recheck",action);
                    assertTrue(Thread.currentThread().isInterrupted());
                    assertEquals("unverified",interrupted.get("state"));
                }finally{Thread.interrupted();}
                String bench=id();
                var args=map("new_id",bench);var consent=object(WorkflowBenchmark.run(h.store,db,"preview_benchmark",args));
                args.putAll(map("preview_token",consent.get("preview_token"),"confirm",true));
                provider.settings.put("AI_ENABLED","false");assertThrows(Failure.class,()->WorkflowBenchmark.run(h.store,db,"run_benchmark",args));
                provider.settings.put("AI_ENABLED","true");
                try{
                    Thread.currentThread().interrupt();
                    assertThrows(Failure.class,()->WorkflowBenchmark.run(h.store,db,"run_benchmark",args));
                    assertTrue(Thread.currentThread().isInterrupted());
                }finally{Thread.interrupted();}
                var existing=copy(step);existing.put("effect_origin","acknowledged_write");
                VerifiedActions.apply(h.store,db,baseline,existing,"recheck",action);
                assertEquals("acknowledged_write",existing.get("effect_origin"));
                var expired=copy(baseline);expired.put("deadline",0L);
                assertThrows(Failure.class,()->WorkflowStudio.current(db,run,expired));
            }
            assertFalse(WorkflowStudio.runOne(h.store));
            if(!h.store.postgres){
                String changed=id();store(h,"workflow-run",changed,baseline);
                try(var db=h.store.open()){
                    db.update("CREATE TRIGGER ten_racing_revision AFTER INSERT ON os_enterprise_document WHEN NEW.area='workflow-attempt' BEGIN UPDATE os_enterprise_document SET revision=revision+1 WHERE area='workflow-run' AND subject='"+changed+"'; END");
                    db.connection.commit();
                }
                assertFalse(WorkflowStudio.runOne(h.store));assertEquals(2,doc(h,"workflow-run",changed).get("revision"));
                try(var db=h.store.open()){db.update("DROP TRIGGER ten_racing_revision");db.connection.commit();}
                var stopped=copy(baseline);stopped.put("state","stopped");store(h,"workflow-run",changed,stopped);
            }
            var unready=copy(baseline);objects(unready.get("steps")).getFirst().put("state","pending");
            store(h,"workflow-run",run,unready);assertFalse(WorkflowStudio.runOne(h.store));
            var paused=copy(baseline);objects(paused.get("steps")).getLast().put("state","different");
            store(h,"workflow-run",run,paused);assertFalse(WorkflowStudio.runOne(h.store));
            var revoked=copy(baseline);revoked.put("delegate_revision",-1);
            store(h,"workflow-run",run,revoked);assertTrue(WorkflowStudio.runOne(h.store));
            assertEquals("requires_review",object(doc(h,"workflow-run",run).get("definition")).get("queue_status"));
            store(h,"workflow-run",run,baseline);assertTrue(WorkflowStudio.runOne(h.store));
            assertEquals("preexisting",objects(object(doc(h,"workflow-run",run).get("definition")).get("steps")).getLast().get("effect_origin"));
            for(String scenario:List.of("observe","replay","unavailable","discrepancy","throttled")){
                String key=id();var working=copy(baseline);store(h,"workflow-run",key,working);
                boolean reconcile=scenario.equals("observe");
                if(scenario.equals("replay")){
                    store(h,"workflow-attempt",ConnectorSynchronization.hash(List.of(key,2,0)),map("run",key,"step",2));
                }
                readCode.set(Set.of("unavailable","discrepancy").contains(scenario)?503:200);present.set(!scenario.equals("throttled"));
                post(h,"studio",TenConnectorAcceptance.call(reconcile?"recheck":"act",key,2,1));
                var result=objects(object(doc(h,"workflow-run",key).get("definition")).get("steps")).getLast();
                assertEquals(switch(scenario){case "unavailable","discrepancy"->"unverified";case "throttled"->"throttled";default->"done";},result.get("state"));
                if(scenario.equals("discrepancy")){
                    readCode.set(200);present.set(false);
                    post(h,"studio",TenConnectorAcceptance.call("recheck",key,2,2));
                    var ticket=result.get("investigation").toString();
                    post(h,"studio",TenConnectorAcceptance.call("rearm",key,2,3));
                    assertEquals("retry_after_discrepancy",object(doc(h,"agent-investigation",ticket).get("definition")).get("authorization"));
                    assertEquals("pending",objects(object(doc(h,"workflow-run",key).get("definition")).get("steps")).getLast().get("state"));
                }
                if(scenario.equals("unavailable")){
                    readCode.set(200);present.set(true);
                    EnterpriseAcceptance.bad(h,"studio","global",TenConnectorAcceptance.call("act",key,2,2),400);
                    post(h,"studio",TenConnectorAcceptance.call("recheck",key,2,2));
                    var checked=objects(object(doc(h,"workflow-run",key).get("definition")).get("steps")).getLast();
                    assertEquals("awaiting_acceptance",checked.get("state"));
                    var ticket=checked.get("investigation").toString();
                    post(h,"desk",map("operation","handoff_review","investigation_id",ticket,"note","Confirmed group membership with directory owner","revision",1));
                    post(h,"studio",TenConnectorAcceptance.call("rearm",key,2,3));
                    var investigated=object(doc(h,"agent-investigation",ticket).get("definition"));
                    assertEquals("accepted_observed_result",investigated.get("authorization"));
                    assertEquals("Confirmed group membership with directory owner",investigated.get("note"));
                    assertEquals("complete",object(doc(h,"workflow-run",key).get("definition")).get("state"));
                }
            }
        }
    }
}
