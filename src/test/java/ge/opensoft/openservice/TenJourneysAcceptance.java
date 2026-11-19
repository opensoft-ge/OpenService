package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static ge.opensoft.openservice.EnterpriseAcceptance.*;
import static ge.opensoft.openservice.EmployeeCasesAcceptance.result;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

final class TenJourneysAcceptance {
    static Map<String,Object> post(WorkflowSupport h,String area,Map<String,Object> body)throws Exception{
        return object(send(h,area,"global",body).get("outcome"));
    }
    static Map<String,Object> signal(String monitor,String event,long seconds,String status){
        return map("monitor",monitor,"id",event,"observed_at",Instant.ofEpochSecond(seconds).toString(),"state",status);
    }
    static void topology(WorkflowSupport h)throws Exception{
        var dbci=map("id","db","title","Database","type","Database","depends_on",List.of());
        var api=map("id","api","title","API","type","Service","depends_on",List.of("db"));
        var body=map("operation","inventory","source","cluster","sequence",1,"resources",List.of(api,dbci),"services",List.of(map("id","checkout","title","Checkout","roots",List.of("api"))));
        assertEquals(1,post(h,"topology",body).get("revision"));
        assertEquals(1,post(h,"topology",body).get("revision"));
        var dupe=new LinkedHashMap<>(body);dupe.put("resources",List.of(api,api));bad(h,"topology","global",dupe,400);
        var target=map("source","cluster","resource_id","db","operation","impact");
        assertEquals(List.of("checkout"),post(h,"topology",target).get("services"));
        var overlay=new LinkedHashMap<>(target);overlay.putAll(map("operation","overlay","title","Primary database","revision",0));
        post(h,"topology",overlay);
        var original=object(post(h,"topology",target).get("resource")).get("ci_id");
        body.put("sequence",2);post(h,"topology",body);
        assertEquals(original,object(post(h,"topology",target).get("resource")).get("ci_id"));
        body.put("sequence",1);bad(h,"topology","global",body,400);
        var signals=new LinkedHashMap<>(target);
        long t=Instant.now().minusSeconds(300).getEpochSecond();
        var down=signal("a","1",t,"down");
        signals.putAll(map("operation","signals","alerts",List.of(down,signal("b","1",t+1,"down"))));
        var state=object(post(h,"topology",signals).get("definition"));String episode=state.get("episode").toString();
        var repeated=object(post(h,"topology",signals).get("definition"));
        assertEquals(state,repeated);
        signals.put("alerts",List.of(signal("a","out-of-order",t-1,"up")));bad(h,"topology","global",signals,400);
        var declaration=new LinkedHashMap<>(target);
        declaration.putAll(map("operation","declare","commander","admin","note","Payment interruption","revision",1));
        post(h,"topology",declaration);
        signals.put("alerts",List.of(down,signal("a","2",t+10,"up")));
        assertEquals(false,object(post(h,"topology",signals).get("definition")).get("all_up"));
        var recovery=new LinkedHashMap<>(target);
        recovery.putAll(map("operation","recover","note","Restored and reviewed","change_id",h.add("change_request").get("sys_id"),"revision",2));
        bad(h,"topology","global",recovery,400);
        body.putAll(map("sequence",3,"resources",List.of(),"services",List.of()));post(h,"topology",body);
        bad(h,"topology","global",target,400);
        signals.put("alerts",List.of(signal("b","2",t+20,"up")));
        assertEquals(true,object(post(h,"topology",signals).get("definition")).get("all_up"));
        recovery.put("revision",3);
        bad(h,"topology","global",recovery,400);
        for(String stage:List.of("review","approved")) ChangePlanningAcceptance.patch(h,recovery.get("change_id").toString(),map("state",stage),200);
        assertEquals("recovered",object(post(h,"topology",recovery).get("definition")).get("state"));
        bad(h,"topology","global",recovery,400);
        bad(h,"topology","global",signals,400);
        body.putAll(map("sequence",4,"resources",List.of(api,dbci),"services",List.of()));post(h,"topology",body);
        signals.put("alerts",List.of(signal("b","3",t+30,"down"),signal("b","4",t+40,"up")));post(h,"topology",signals);
        recovery.put("revision",5);post(h,"topology",recovery);
        var persisted=object(doc(h,"service-episode",episode).get("definition"));
        assertEquals("db",persisted.get("resource"));
        assertNotNull(EmployeeCasesAcceptance.get(h,"/api/now/table/incident/"+persisted.get("incident"),"admin"));
    }
    static void finance(WorkflowSupport h)throws Exception{
        var assets=AssetCustodyAcceptance.setup(h);
        String order=id(),product="Enterprise productivity",license=id(),user=uid(h,"AssetUser");
        post(h,"assets",order(order));
        post(h,"assets",map("operation","approve","order_id",order,"revision",1));
        post(h,"assets",map("operation","invoice","order",order,"invoice","A-100","currency","USD","lines",List.of(map("model","Field laptop","quantity",1,"unit_cost","1200.00"))));
        h.account("FinanceReviewer","admin");
        var approvePrice=map("operation","accept_price","invoice_id",ConnectorSynchronization.hash(List.of("Local supplier <script>","A-100")),"model","Field laptop","unit_cost","1200","note","Exact price already matches");
        TenPolicyAcceptance.as(h,"FinanceReviewer","assets",approvePrice,400);
        var settlement=map("operation","settle","invoice_id",ConnectorSynchronization.hash(List.of("Local supplier <script>","A-100")),"revision",0);
        assertTrue(objects(object(post(h,"assets",settlement).get("definition")).get("exports")).isEmpty());
        post(h,"assets",map("operation","receive","order_id",order,"revision",2,"asset_id",assets[0],"stockroom_id",assets[1],"received_on",LocalDate.now().toString()));
        settlement.put("revision",1);
        assertEquals(1,objects(object(post(h,"assets",settlement).get("definition")).get("exports")).size());
        var exported=post(h,"assets",settlement);
        assertEquals(2,exported.get("revision"));
        var csv=h.get("/finance-export/"+settlement.get("invoice_id")+"/"+objects(object(exported.get("definition")).get("exports")).getFirst().get("export_id"),auth("admin"));
        assertEquals(200,csv.statusCode());assertTrue(html(csv).contains("1200.00"));
        assertEquals(404,h.get("/finance-export/"+settlement.get("invoice_id")+"/"+id(),auth("admin")).statusCode());
        post(h,"assets",map("operation","license","key",license,"product",product,"seats",3,"expires_on",LocalDate.now().plusYears(1).toString()));
        post(h,"assets",map("operation","allocate","license_id",license,"revision",1,"user_id",user));
        post(h,"assets",map("operation","seat_usage","as_of",LocalDate.now().toString(),"source","Publisher report","product",product,"revision",0,"observations",List.of(map("user_id",user,"last_active",LocalDate.now().minusDays(120).toString()))));
        assertEquals(1,objects(post(h,"assets",map("operation","reclaim","product",product)).get("candidates")).size());
        String different=id();
        post(h,"assets",map("operation","license","key",different,"product","Other product","seats",3,"expires_on",LocalDate.now().plusDays(7).toString()));
        post(h,"assets",map("operation","allocate","license_id",license,"revision",2,"user_id",uid(h,"admin")));
        var more=map("operation","seat_usage","as_of",LocalDate.now().toString(),"source","Publisher report","product",product,"revision",1,"observations",List.of(
                map("user_id",user,"last_active",LocalDate.now().minusDays(120).toString()),map("user_id",uid(h,"admin"),"last_active",LocalDate.now().toString())));
        assertEquals(1,objects(post(h,"assets",map("operation","reclaim","product",product)).get("candidates")).size());
        post(h,"assets",more);
        var reclaim=map("operation","release_unused","license_id",license,"user_id",user,"revision",3,"product",product,"usage_revision",2,"confirm",true);
        for(var change:List.of(map("confirm",false),map("license_id",different),map("user_id",uid(h,"admin")))){
            var denied=new LinkedHashMap<>(reclaim);denied.putAll(change);bad(h,"assets","global",denied,400);
        }
        assertEquals(List.of(uid(h,"admin")),object(post(h,"assets",reclaim).get("definition")).get("users"));
        bad(h,"assets","global",reclaim,400);
        try(var db=h.store.open()){
            PlatformFoundationTest.bind(db);
            var rights=EnterpriseData.get(db,"entitlement",license);var expired=object(rights.get("definition"));
            expired.put("expires_on",LocalDate.now().minusDays(1).toString());EnterpriseData.save(db,"entitlement",license,rights.get("revision"),expired);
            assertTrue(objects(object(InvoiceSettlement.command(db,"reclaim",map("product",product))).get("candidates")).isEmpty());
            String usageKey=ConnectorSynchronization.hash(product);
            var snapshot=EnterpriseData.get(db,"seat-usage",usageKey);var stale=object(snapshot.get("definition"));
            stale.put("as_of",LocalDate.now().minusDays(8).toString());EnterpriseData.save(db,"seat-usage",usageKey,snapshot.get("revision"),stale);
            assertThrows(Failure.class,()->InvoiceSettlement.command(db,"reclaim",map("product",product)));
        }
    }
    static void studio(WorkflowSupport h)throws Exception{
        h.account("JourneyManager","agent");h.account("JourneyAgent","agent");
        String manager=uid(h,"JourneyManager"),agent=uid(h,"JourneyAgent"),template=id(),run=id();
        try(var db=h.store.open()){db.update("UPDATE os_user SET api_only=1 WHERE user_id=?",agent);db.connection.commit();}
        try(var provider=new RecordQuestionsAcceptance.Provider(h.store,h.environment)){
            provider.settings.put("CONNECTOR_WRITES_ENABLED","true");
            var labels=new AtomicBoolean();var writes=new AtomicInteger();
            provider.server.createContext("/rest/api/3/issue/OPS-1",exchange->{
                if(exchange.getRequestMethod().equals("PUT")){writes.incrementAndGet();labels.set(true);exchange.sendResponseHeaders(204,-1);}
                else{byte[] bytes=JSON.writeValueAsBytes(map("key","OPS-1","fields",map("labels",labels.get()?List.of("ready"):List.of())));
                    exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}
                exchange.close();
            });
            post(h,"studio",map("operation","identity","user_id",uid(h,"admin"),"manager_id",manager,"directory_id",UUID.randomUUID().toString(),"revision",0));
            var approval=map("kind","approval","title","Manager review","stage",1);
            var action=map("kind","jira_label","title","Mark issue ready","stage",2,"issue","OPS-1","label","ready","alias",provider.alias,"oauth","alias");
            var spec=map("operation","publish","template_id",template,"title","Enable operator","days",3,"steps",List.of(approval,action),"revision",0);
            bad(h,"studio","global",map("operation","publish","template_id",template,"title","Empty","days",2,"steps",List.of(),"revision",0),400);
            var early=new LinkedHashMap<>(action);early.put("stage",1);
            for(var illegal:List.of(List.of(early),List.of(approval,early),List.of(approval,action,approval))){
                var invalid=new LinkedHashMap<>(spec);invalid.put("steps",illegal);bad(h,"studio","global",invalid,400);
            }
            post(h,"studio",spec);
            post(h,"studio",map("operation","request","template_id",template,"new_id",run,"revision",1));
            spec.putAll(map("days",2,"revision",1));post(h,"studio",spec);
            var inspect=map("operation","inspect","run_id",run);
            assertEquals(1,object(post(h,"studio",inspect).get("definition")).get("version"));
            var progress=map("operation","advance","run_id",run,"step",1,"revision",1,"note","Manager approved");
            bad(h,"studio","global",progress,400);
            result(h.json("POST",api("studio","global"),progress,auth("JourneyManager")));
            var preview=map("operation","preview_actions","run_id",run,"revision",2,"goal","Mark the reviewed issue ready");
            var draft=new LinkedHashMap<>(preview);
            draft.putAll(map("operation","draft_actions","confirm",true,"preview_token",post(h,"studio",preview).get("preview_token")));
            provider.answer.set("[]");bad(h,"studio","global",draft,400);
            provider.answer.set("invalid");bad(h,"studio","global",draft,502);
            provider.answer.set("[2,2]");bad(h,"studio","global",draft,400);
            provider.answer.set("[1]");bad(h,"studio","global",draft,400);
            provider.answer.set("[2]");post(h,"studio",draft);
            post(h,"studio",map("operation","delegate_run","run_id",run,"revision",3,"delegate_id",agent,"confirm",true));
            preview.put("revision",4);bad(h,"studio","global",preview,400);
            var queue=map("operation","queue_run","run_id",run,"revision",4,"confirm",false);
            bad(h,"studio","global",queue,400);queue.put("confirm",true);
            h.account("OtherScheduler","admin");TenPolicyAcceptance.as(h,"OtherScheduler","studio",queue,400);
            assertEquals(3,objects(post(h,"studio",map("operation","evaluate_run","run_id",run)).get("trials")).size());
            var execute=map("operation","act","step",2,"confirm",true,"run_id",run,"revision",4);
            provider.settings.put("AI_AGENT_EXECUTION_ENABLED","true");
            post(h,"studio",map("operation","queue_run","run_id",run,"revision",4,"confirm",true));
            assertTrue(new WorkWorker(h.store).runOne(System.currentTimeMillis()));
            assertEquals("complete",object(post(h,"studio",inspect).get("definition")).get("state"));
            bad(h,"studio","global",execute,409);
            assertEquals(1,writes.get());
            assertFalse(WorkflowStudio.runOne(h.store));
            preview.put("revision",6);bad(h,"studio","global",preview,400);
            assertTrue(WorkflowPlanReview.choices(object(post(h,"studio",inspect).get("definition"))).isEmpty());
        }
    }
}
