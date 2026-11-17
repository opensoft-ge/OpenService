package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static ge.opensoft.openservice.FulfilmentAcceptance.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;

final class FulfilmentBoundariesAcceptance {
    static void install(WorkflowSupport h,Map<String,Object> definition)throws Exception {result(h.json("POST","/api/openservice/configuration?dry_run=false",ConfigurationAcceptance.bundle("sc_request",definition),auth("admin")),200);}
    static void run(WorkflowSupport h)throws Exception {
        h.account("OrderOwner","requester");h.account("BoundaryAgent","agent");h.account("BoundaryOther","agent");
        String group=h.add("sys_user_group").get("sys_id").toString();member(h,group,"BoundaryAgent",true);member(h,group,"BoundaryOther",true);
        String offering=result(h.json("POST",CATALOG,CatalogueAcceptance.offering("Boundary service",0,true),auth("admin")),201).get("offering_id").toString();
        result(h.json("PUT",CATALOG+"/"+offering+"/routing",map("group_id",group,"revision",1),auth("admin")),200);
        String request=order(h,offering,2);Map<String,Object> original;try(var db=h.store.open()){original=Service.schema(db,"sc_request");}
        try {
            for(String hidden:List.of("state","description")){
                Map<String,Object> restricted;try(var db=h.store.open()){restricted=Service.schema(db,"sc_request");}restricted.putIfAbsent("version",0);restricted.put("field_rules",map(hidden,map("type","string","read_roles",List.of("admin"))));install(h,restricted);
                assertEquals(403,h.get(path(request),auth("BoundaryAgent")).statusCode(),"Task progress and outcome must not bypass request field-read policy");assertEquals(403,h.get(QUEUE,auth("BoundaryAgent")).statusCode());assertEquals(403,h.json("POST",path(request),map("operation","claim","revision",1,"request_revision",0),auth("BoundaryAgent")).statusCode());
                assertFalse(html(h.get("/record/sc_request/"+request,auth("BoundaryAgent"))).contains("Follow fulfilment"));
            }
            Map<String,Object> restored;try(var db=h.store.open()){restored=Service.schema(db,"sc_request");}restored.remove("field_rules");install(h,restored);
            var locked=new LinkedHashMap<>(original);try(var db=h.store.open()){locked.put("version",Service.schema(db,"sc_request").get("version"));}locked.putIfAbsent("version",0);locked.put("field_rules",map("state",map("type","string","write_roles",List.of("admin"))));install(h,locked);
            var task=get(h,request,"BoundaryAgent");assertEquals(403,h.json("POST",path(request),command(task,"claim"),auth("BoundaryAgent")).statusCode());assertEquals("queued",get(h,request,"admin").get("state"));assertEquals("",get(h,request,"admin").get("assignee_id"));
            Map<String,Object> current;try(var db=h.store.open()){current=Service.schema(db,"sc_request");}current.remove("field_rules");current.put("transition_rules",map("complete",map("min_approvals",1)));install(h,current);
            var claimed=result(h.json("POST",path(request),command(get(h,request,"BoundaryAgent"),"claim"),auth("BoundaryAgent")),200);var finish=command(claimed,"complete");finish.put("outcome","Must wait for approval");assertEquals(409,h.json("POST",path(request),finish,auth("BoundaryAgent")).statusCode());assertEquals("in_progress",get(h,request,"admin").get("state"));assertEquals("",get(h,request,"admin").get("outcome"));
            try(var db=h.store.open()){current=Service.schema(db,"sc_request");}current.remove("transition_rules");install(h,current);
            var handoff=command(get(h,request,"admin"),"reassign");handoff.put("group_id",group);result(h.json("POST",path(request),handoff,auth("admin")),200);
            // Two staff act on the same reviewed revisions: exactly one claim survives.
            var claim=command(get(h,request,"admin"),"claim");try(var executor=Executors.newFixedThreadPool(2)){
                var start=new CountDownLatch(1);var a=executor.submit(()->{start.await();return h.json("POST",path(request),claim,auth("BoundaryAgent")).statusCode();});var b=executor.submit(()->{start.await();return h.json("POST",path(request),claim,auth("BoundaryOther")).statusCode();});start.countDown();assertEquals(Set.of(200,409),Set.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS)));
            }
            var active=get(h,request,"admin");var bad=command(active,"complete");bad.put("outcome","");assertEquals(400,h.json("POST",path(request),bad,auth("admin")).statusCode());
            try(var db=h.store.open()){
                String actor=PlatformFoundationTest.bind(db);var rollback=command(Fulfilment.detail(db,request),"complete");rollback.put("outcome","Roll back this result");Fulfilment.control(db,request,rollback);db.connection.rollback();PlatformFoundationTest.bind(db);assertEquals("in_progress",Fulfilment.detail(db,request).get("state"));assertEquals("fulfilling",object(Service.record(db,"sc_request",request).get("data")).get("state"));
                db.viewerRole="reader";assertEquals(403,assertThrows(Failure.class,()->Fulfilment.control(db,request,map())).status);PlatformFoundationTest.bind(db);
                var invalid=Service.record(db,"sc_request",request);db.update("UPDATE os_fulfilment SET state='complete' WHERE request_id=?",request);assertEquals(409,assertThrows(Failure.class,()->Service.save(db,invalid)).status);db.connection.rollback();
                PlatformFoundationTest.bind(db);var routed=Catalogue.find(db,offering,true);CatalogueRouting.save(db,actor,offering,map("group_id","","revision",2));db.connection.rollback();assertEquals(group,Catalogue.find(db,offering,true).get("fulfilment_group_id"));
                long count=db.count("SELECT COUNT(*) FROM os_fulfilment");Catalogue.order(db,offering,map("revision",2,"details","Rolled back request","key",id()));db.connection.rollback();assertEquals(count,db.count("SELECT COUNT(*) FROM os_fulfilment"));
            }
            // A group removed after routing cannot produce an orphaned new order.
            assertEquals(204,h.json("DELETE","/api/now/table/sys_user_group/"+group,map(),auth("admin")).statusCode());
            long before;try(var db=h.store.open()){before=db.count("SELECT COUNT(*) FROM os_record WHERE \"table\"='sc_request'");}
            assertEquals(400,h.json("POST",CATALOG+"/"+offering+"/order",map("revision",2,"details","","key",id()),auth("OrderOwner")).statusCode());
            assertTrue(html(h.get("/admin/catalogue/"+offering,auth("admin"))).contains("Removed group"));try(var db=h.store.open()){assertEquals(before,db.count("SELECT COUNT(*) FROM os_record WHERE \"table\"='sc_request'"));}
        } finally {
            try(var db=h.store.open()){db.update("UPDATE os_schema SET definition="+db.jsonParameter()+" WHERE name='sc_request'",JSON.writeValueAsString(original));db.connection.commit();}
        }
    }
}
