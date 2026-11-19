package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseAcceptance.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.*;

final class ScheduledIssueAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        for(String adapter:List.of("github","gitlab")) exercise(h,adapter);
    }
    static void exercise(WorkflowSupport h,String adapter) throws Exception {
        String id=h.add("incident").get("sys_id").toString(), key=id(), api="/api/now/table/incident/"+id;
        boolean github=adapter.equals("github");
        String path=github?"/repos/acme/help/issues/1":"/api/v4/projects/13/issues/1";
        var data=map("number",1,"iid",1,"project_id",13,"id",124,"updated_at","v1","title","From issue",
                github?"body":"description","Issue details");
        var writes=new AtomicInteger();var refreshes=new AtomicInteger();var drop=new AtomicBoolean();var misreport=new AtomicBoolean();
        try(var provider=new RecordQuestionsAcceptance.Provider(h.store,h.environment)) {
            provider.settings.put("CONNECTOR_WRITES_ENABLED","true");
            provider.server.createContext("/rotate",e->{
                String form=new String(e.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                int n=refreshes.incrementAndGet();
                assertTrue(form.contains("refresh_token="+(n==1?"initial":"rotated")));
                byte[] bytes=JSON.writeValueAsBytes(map("access_token","scheduled","token_type","Bearer","expires_in",60,"refresh_token","rotated"));
                e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
            });
            provider.server.createContext(path,e->{
                if(!e.getRequestMethod().equals("GET")) {
                    assertEquals(github?"PATCH":"PUT",e.getRequestMethod());
                    if(github) assertEquals("\"v1\"",e.getRequestHeaders().getFirst("If-Match"));
                    assertEquals("Bearer scheduled",e.getRequestHeaders().getFirst("Authorization"));
                    data.putAll(object(JSON.readValue(e.getRequestBody().readAllBytes(),Object.class)));
                    writes.incrementAndGet();
                    if(drop.getAndSet(false)) {e.sendResponseHeaders(503,-1);e.close();return;}
                }
                if(github) e.getResponseHeaders().set("ETag","\"v1\"");
                var shown=new LinkedHashMap<>(data);
                if(!e.getRequestMethod().equals("GET") && misreport.getAndSet(false)) shown.put("id",999);
                byte[] bytes=JSON.writeValueAsBytes(shown);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
            });
            var schedule=map("operation","schedule","key",key,"revision",0,"alias",provider.alias,"path",path,"kind",adapter,"target",id,"minutes",1,"enabled",true);
            send(h,"integrations","global",schedule);
            send(h,"integrations","global",map("operation","schedule_oauth","key",key,"revision",0,"issuer_alias",provider.alias,
                    "token_path","/rotate","client_id","client","client_secret","secret","refresh_token","initial"));
            long now=100;
            assertTrue(RecurringImports.runOne(h.store,now));
            assertEquals("From issue",EmployeeCasesAcceptance.get(h,api,"admin").get("short_description"));
            var revision=EmployeeCasesAcceptance.get(h,api,"admin").get("sys_mod_count");
            assertTrue(RecurringImports.runOne(new Store(h.environment),now+=60001));
            assertEquals(revision,EmployeeCasesAcceptance.get(h,api,"admin").get("sys_mod_count"));
            assertEquals(200,h.json("PATCH",api,map("short_description","From desk"),auth("admin")).statusCode());
            assertTrue(RecurringImports.runOne(h.store,now+=60001));
            assertEquals("outbound_pending",state(h,key).get("status"));
            drop.set(true);
            assertTrue(RecurringImports.runOne(new Store(h.environment),now));
            assertEquals("failed",state(h,key).get("status"));
            assertTrue(RecurringImports.runOne(h.store,now+=60001));
            assertEquals("succeeded",state(h,key).get("status"));assertEquals(1,writes.get());
            data.put("title","Remote change");
            assertEquals(200,h.json("PATCH",api,map("short_description","Desk change"),auth("admin")).statusCode());
            assertTrue(RecurringImports.runOne(h.store,now+=60001));
            assertEquals("conflict",state(h,key).get("status"));
            var resolve=map("operation","resolve","key",key,"revision",doc(h,"import-schedule",key).get("revision"),
                    "proof",object(state(h,key).get("conflict")).get("proof"),"choice",github?"local":"remote");
            send(h,"integrations","global",resolve);
            assertTrue(RecurringImports.runOne(h.store,now));
            if(github) {
                assertTrue(RecurringImports.runOne(h.store,now));
                assertEquals("Desk change",data.get("title"));assertEquals(2,writes.get());
            } else assertEquals("Remote change",EmployeeCasesAcceptance.get(h,api,"admin").get("short_description"));
            assertEquals("succeeded",state(h,key).get("status"));
            assertEquals(1,refreshes.get());
            try(var db=h.store.open()) {
                PlatformFoundationTest.bind(db);
                var row=EnterpriseData.get(db,"schedule-token",key);
                var profile=object(row.get("definition"));
                var conn=db.one("SELECT * FROM os_connector WHERE alias=?",provider.alias);
                var wrong=map("owner","wrong");
                assertThrows(Failure.class,()->IssueTokens.access(h.store,db,key,wrong,conn,true));
                profile.put("expires",0);
                EnterpriseData.save(db,"schedule-token",key,row.get("revision"),profile);
                db.connection.commit();
            }
            assertTrue(RecurringImports.runOne(h.store,now+=60001));
            assertEquals(2,refreshes.get());
            bad(h,"integrations","global",map("operation","schedule_oauth","key",key,"revision",0,"issuer_alias","missing",
                    "token_path","/rotate","client_id","c","client_secret","s","refresh_token","r"),400);
            data.put("id",901);
            assertTrue(RecurringImports.runOne(h.store,now+=60001));assertEquals("conflict",state(h,key).get("status"));
            resolve.putAll(map("choice","remote","revision",doc(h,"import-schedule",key).get("revision"),"proof","stale"));
            bad(h,"integrations","global",resolve,400);
            data.put("id",124);
            assertTrue(RecurringImports.runOne(h.store,now+=60001));
            assertEquals(200,h.json("PATCH",api,map("short_description","After rotation"),auth("admin")).statusCode());
            assertTrue(RecurringImports.runOne(h.store,now+=60001));
            data.put("title","Changed during outbound preparation");
            assertTrue(RecurringImports.runOne(h.store,now));
            assertEquals("conflict",state(h,key).get("status"));
            resolve.putAll(map("choice","local","revision",doc(h,"import-schedule",key).get("revision"),"proof",object(state(h,key).get("conflict")).get("proof")));
            send(h,"integrations","global",resolve);assertTrue(RecurringImports.runOne(h.store,now));
            provider.settings.put("CONNECTOR_WRITES_ENABLED","false");
            assertTrue(RecurringImports.runOne(h.store,now));assertEquals("failed",state(h,key).get("status"));
            assertTrue(RecurringImports.runOne(h.store,now+=60001));assertEquals("conflict",state(h,key).get("status"));
            provider.settings.put("CONNECTOR_WRITES_ENABLED","true");
            resolve.putAll(map("revision",doc(h,"import-schedule",key).get("revision"),"proof",object(state(h,key).get("conflict")).get("proof")));
            send(h,"integrations","global",resolve);assertTrue(RecurringImports.runOne(h.store,now));
            misreport.set(true);assertTrue(RecurringImports.runOne(h.store,now));assertEquals("failed",state(h,key).get("status"));
            assertTrue(RecurringImports.runOne(h.store,now+=60001));assertEquals("succeeded",state(h,key).get("status"));
            Object lastGood=state(h,key).get("last_success");
            assertEquals(200,h.json("PATCH",api,map("short_description","Desk discrepancy"),auth("admin")).statusCode());
            data.put(github?"body":"description","x".repeat(65000));
            assertTrue(RecurringImports.runOne(h.store,now+=60001));
            assertEquals("failed",state(h,key).get("status"));
            assertEquals(lastGood,state(h,key).get("last_success"));
            data.put(github?"body":"description","Issue details");
            send(h,"integrations","global",map("operation","replay","key",key,"revision",doc(h,"import-schedule",key).get("revision")));
            assertTrue(RecurringImports.runOne(h.store,now));
            assertEquals("outbound_pending",state(h,key).get("status"));
            schedule.putAll(map("enabled",false,"revision",doc(h,"import-schedule",key).get("revision")));
            send(h,"integrations","global",schedule);
        }
    }
    static Map<String,Object> state(WorkflowSupport h,String key) throws Exception {
        return object(doc(h,"import-schedule",key).get("definition"));
    }
}
