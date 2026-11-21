package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static ge.opensoft.openservice.EnterpriseAcceptance.*;
import static ge.opensoft.openservice.TenJourneysAcceptance.post;
import static ge.opensoft.openservice.EmployeeCasesAcceptance.result;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.time.*;
import java.util.concurrent.atomic.*;

final class TenConnectorAcceptance {
    static Map<String,Object> call(String op,String run,int step,int revision){
        var b=map("operation",op,"run_id",run,"step",step,"revision",revision);
        if(Set.of("act","rearm").contains(op))b.put("confirm",true);
        return b;
    }
    static void directory(WorkflowSupport h)throws Exception{
        String user=uid(h,"admin"),template=id(),run=id(),member=UUID.randomUUID().toString(),group=UUID.randomUUID().toString();
        h.account("GraphManager","agent");String manager=uid(h,"GraphManager");
        post(h,"studio",map("operation","identity","user_id",user,"manager_id",manager,"directory_id",member,"revision",0));
        try(var provider=new RecordQuestionsAcceptance.Provider(h.store,h.environment)){
            provider.settings.put("CONNECTOR_WRITES_ENABLED","true");
            String credential=id();
            post(h,"integrations",map("operation","schedule","key",credential,"revision",0,"kind","inventory","target","Test inventory","alias",provider.alias,"path","/snapshot","minutes",60,"enabled",false));
            post(h,"integrations",map("operation","schedule_oauth","key",credential,"revision",0,"issuer_alias",provider.alias,"token_path","/renew","client_id","test","client_secret","fixture-secret","refresh_token","fixture-refresh"));
            var renewals=new AtomicInteger();
            provider.server.createContext("/renew",e->{renewals.incrementAndGet();byte[] bytes=JSON.writeValueAsBytes(map("access_token","fixture-access","token_type","Bearer","expires_in",600));e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});
            var mode=new AtomicInteger(429);var assigned=new AtomicBoolean();var writes=new AtomicInteger();
            provider.server.createContext("/v1.0/groups/",exchange->{
                var path=exchange.getRequestURI().getPath();
                if(!"Bearer fixture-access".equals(exchange.getRequestHeaders().getFirst("Authorization"))){exchange.sendResponseHeaders(401,-1);exchange.close();return;}
                String verb=exchange.getRequestMethod(),message="{}";int status=200;
                if(mode.get()==429){status=429;exchange.getResponseHeaders().set("Retry-After","1");}
                else if(path.equals("/v1.0/groups/"+group)){
                    if(mode.get()==403)status=403;
                    else message=JSON.writeValueAsString(map("id",mode.get()==422?"other":group));
                }
                else if(verb.equals("POST") || verb.equals("DELETE")){
                    UUID.fromString(exchange.getRequestHeaders().getFirst("client-request-id"));
                    writes.incrementAndGet();
                    if(mode.get()==503)status=503;
                    else{
                        assertTrue(path.endsWith("/$ref"),path);
                        if(verb.equals("POST"))assertTrue(new String(exchange.getRequestBody().readAllBytes()).contains(member));
                        assigned.set(verb.equals("POST"));status=204;
                    }
                }else if(mode.get()==500)status=500;
                else if(assigned.get())message=JSON.writeValueAsString(map("id",member));
                else status=404;
                if(status==204)exchange.sendResponseHeaders(status,-1);
                else{byte[] bytes=message.getBytes();exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}
                exchange.close();
            });
            var add=map("stage",2,"kind","entra_add","title","Join team","alias",provider.alias,"oauth",credential,"target",group);
            var remove=new LinkedHashMap<>(add);remove.putAll(map("stage",3,"kind","entra_remove","title","Leave team"));
            var publish=map("operation","publish","template_id",template,"days",1,"title","Temporary access","steps",List.of(map("kind","approval","title","Review access","stage",1),add,remove),"revision",0);
            post(h,"studio",publish);
            post(h,"studio",map("operation","request","new_id",run,"template_id",template,"revision",1));
            bad(h,"studio","global",call("act",run,2,1),400);
            result(h.json("POST",api("studio","global"),map("operation","advance","step",1,"run_id",run,"revision",1,"note","Approved"),auth("GraphManager")));
            bad(h,"studio","global",map("operation","advance","run_id",run,"step",2,"revision",2,"note","Manual completion forbidden"),400);
            post(h,"studio",call("act",run,2,2));
            bad(h,"studio","global",call("act",run,2,3),400);
            Thread.sleep(1050);
            mode.set(503);
            post(h,"studio",call("recheck",run,2,3));
            post(h,"studio",call("rearm",run,2,4));
            post(h,"studio",call("act",run,2,5));
            assertEquals(1,writes.get());
            mode.set(200);
            post(h,"studio",call("act",run,2,6));
            assertEquals(1,writes.get());
            post(h,"studio",call("rearm",run,2,7));
            post(h,"studio",call("act",run,2,8));
            assertTrue(assigned.get());
            post(h,"studio",call("act",run,3,9));
            assertFalse(assigned.get());
            assertEquals(3,writes.get());
            var receipt=post(h,"packages",map("operation","provider_attempt","run_id",run,"step",3,"sequence",0));
            assertNotNull(UUID.fromString(receipt.get("correlation_id").toString()));
            assertEquals(provider.alias,receipt.get("write_alias"));
            assertEquals(group,receipt.get("group"));assertEquals(member,receipt.get("member"));
            assertNotNull(UUID.fromString(receipt.get("process_id").toString()));
            assertTrue(receipt.get("credential_tag").toString().matches("[a-f0-9]{64}"));
            assertFalse(receipt.toString().contains("fixture-access"));
            assertNotNull(receipt.get("credential_revision"));
            assertEquals(1,renewals.get());
            for(int error:List.of(403,422,500)){
                mode.set(error);
                assertThrows(Failure.class,()->VerifiedActions.request(provider.origin(),"fixture-access",remove,member,false,""));
            }
            var saved=object(doc(h,"workflow-run",run).get("definition"));
            assertEquals("complete",saved.get("state"));
            assertTrue(objects(saved.get("steps")).subList(1,3).stream().allMatch(s->Boolean.TRUE.equals(s.get("changed"))));
            var benchmark=map("operation","preview_benchmark","new_id",id());
            var consent=post(h,"studio",benchmark);provider.answer.set("[]");
            benchmark.putAll(map("operation","run_benchmark","preview_token",consent.get("preview_token"),"confirm",true));
            var evaluation=object(post(h,"studio",benchmark).get("definition"));
            assertEquals(5,evaluation.get("total"));assertEquals(2,evaluation.get("passed"));
            assertEquals(5,provider.calls.get());
            post(h,"studio",benchmark);assertEquals(5,provider.calls.get());
            benchmark.putAll(map("new_id",id(),"operation","preview_benchmark"));benchmark.remove("confirm");benchmark.remove("preview_token");
            var next=post(h,"studio",benchmark);provider.status.set(502);
            benchmark.putAll(map("operation","run_benchmark","preview_token",next.get("preview_token"),"confirm",false));
            bad(h,"studio","global",benchmark,400);benchmark.put("confirm",true);
            assertEquals(0,object(post(h,"studio",benchmark).get("definition")).get("passed"));
        }
    }
}
