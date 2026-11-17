package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.net.http.HttpResponse;
import java.util.*;

final class FulfilmentAcceptance {
    static final String CATALOG="/api/openservice/catalogue",QUEUE="/api/openservice/fulfilment";
    static String path(String id){return "/api/openservice/requests/"+id+"/fulfilment";}
    static Map<String,Object> result(HttpResponse<byte[]> r,int status){assertEquals(status,r.statusCode(),html(r));return object(parsed(r).get("result"));}
    static Map<String,Object> get(WorkflowSupport h,String id,String user)throws Exception{return result(h.get(path(id),auth(user)),200);}
    static Map<String,Object> command(Map<String,Object> task,String op){return map("operation",op,"revision",task.get("revision"),"request_revision",task.get("request_revision"));}
    static String order(WorkflowSupport h,String offering,int revision)throws Exception{return result(h.json("POST",CATALOG+"/"+offering+"/order",map("revision",revision,"details","Please deliver safely","key",id()),auth("OrderOwner")),201).get("sys_id").toString();}
    static void member(WorkflowSupport h,String group,String name,boolean add)throws Exception{
        String user;try(var db=h.store.open()){user=RecordAccess.owner(db,name);}
        assertEquals(200,h.json(add?"PUT":"DELETE","/api/openservice/groups/"+group+"/members"+(add?"":"/"+user),add?map("user_id",user):map(),auth("admin")).statusCode());
    }
    static void run(WorkflowSupport h)throws Exception {
        for(String name:List.of("OrderOwner","OrderOther"))h.account(name,"requester");
        for(String name:List.of("Fulfiller","Colleague","Outsider"))h.account(name,"agent");h.account("FulfilReader","reader");
        String group=h.add("sys_user_group").get("sys_id").toString(),replacement=h.add("sys_user_group").get("sys_id").toString();
        member(h,group,"Fulfiller",true);member(h,group,"Colleague",true);
        String offering=result(h.json("POST",CATALOG,CatalogueAcceptance.offering("Managed laptop <script>",0,true),auth("admin")),201).get("offering_id").toString(),routing=CATALOG+"/"+offering+"/routing";
        result(h.json("PUT",routing,map("group_id",group,"revision",1),auth("admin")),200);
        String request=order(h,offering,2);var queued=get(h,request,"OrderOwner");assertEquals("queued",queued.get("state"));assertFalse(queued.containsKey("assignee_id"));assertFalse(queued.containsKey("group_id"));
        assertEquals(false,queued.get("can_claim"));assertEquals(404,h.get(path(request),auth("OrderOther")).statusCode());assertEquals(403,h.get(QUEUE,auth("OrderOwner")).statusCode());
        assertEquals(1,parsed(h.get(QUEUE,auth("Fulfiller"))).get("total"));assertEquals(0,parsed(h.get(QUEUE,auth("Outsider"))).get("total"));
        assertEquals(403,h.json("POST",path(request),command(queued,"claim"),auth("Outsider")).statusCode());
        assertEquals(403,h.json("POST",path(request),command(queued,"claim"),auth("OrderOwner")).statusCode());
        var claimed=result(h.json("POST",path(request),command(queued,"claim"),auth("Fulfiller")),200);assertEquals("in_progress",claimed.get("state"));assertEquals("Fulfiller",claimed.get("assignee_name"));
        assertEquals("fulfilling",result(h.get("/api/now/table/sc_request/"+request,auth("OrderOwner")),200).get("state"));
        assertEquals(409,h.json("POST",path(request),command(queued,"claim"),auth("Colleague")).statusCode());
        assertEquals(409,h.json("POST",path(request),command(claimed,"claim"),auth("Colleague")).statusCode());
        var finish=command(claimed,"complete");finish.put("outcome","Laptop delivered <script>alert(1)</script>");
        assertEquals(403,h.json("POST",path(request),finish,auth("Colleague")).statusCode());
        assertEquals(false,get(h,request,"Colleague").get("can_complete"));
        assertEquals(409,h.json("PATCH","/api/now/table/sc_request/"+request,map("state","complete"),auth("admin")).statusCode());
        member(h,group,"Fulfiller",false);assertEquals(false,get(h,request,"Fulfiller").get("can_complete"));assertEquals(403,h.json("POST",path(request),finish,auth("Fulfiller")).statusCode());member(h,group,"Fulfiller",true);
        // A current record edit invalidates the reviewed completion even if its task is unchanged.
        assertEquals(200,h.json("PATCH","/api/now/table/sc_request/"+request,map("description","Updated context"),auth("admin")).statusCode());assertEquals(409,h.json("POST",path(request),finish,auth("Fulfiller")).statusCode());
        claimed=get(h,request,"Fulfiller");finish=command(claimed,"complete");finish.put("outcome","Laptop delivered <script>alert(1)</script>");
        var complete=result(h.json("POST",path(request),finish,auth("Fulfiller")),200);assertEquals("complete",complete.get("state"));assertEquals("complete",result(h.get("/api/now/table/sc_request/"+request,auth("OrderOwner")),200).get("state"));
        assertTrue(html(h.get("/requests/"+request+"/fulfilment",auth("OrderOwner"))).contains("&lt;script&gt;"));
        assertEquals(finish.get("outcome"),get(h,request,"OrderOwner").get("outcome"));assertEquals(0,parsed(h.get(QUEUE,auth("Fulfiller"))).get("total"));assertEquals(1,parsed(h.get(QUEUE+"?state=complete&limit=1",auth("Fulfiller"))).get("total"));
        assertEquals(409,h.json("POST",path(request),command(complete,"claim"),auth("admin")).statusCode());var again=command(complete,"complete");again.put("outcome","Again");assertEquals(409,h.json("POST",path(request),again,auth("admin")).statusCode());
        var handoff=command(complete,"reassign");handoff.put("group_id",replacement);assertEquals(400,h.json("POST",path(request),handoff,auth("admin")).statusCode());
        new Store(h.environment);assertEquals("complete",get(h,request,"OrderOwner").get("state"));
        // Future routing changes leave the completed and queued tasks with their snapshotted group.
        String recover=order(h,offering,2);result(h.json("PUT",routing,map("group_id",replacement,"revision",2),auth("admin")),200);
        assertEquals(group,get(h,recover,"admin").get("group_id"));assertEquals(204,h.json("DELETE","/api/now/table/sys_user_group/"+group,map(),auth("admin")).statusCode());
        assertTrue(get(h,recover,"admin").get("group_name").toString().contains("Removed group"));assertEquals(0,parsed(h.get(QUEUE+"?state=all",auth("Fulfiller"))).get("total"));
        var recovery=command(get(h,recover,"admin"),"reassign");recovery.put("group_id",replacement);
        assertEquals(403,h.json("POST",path(recover),recovery,auth("Fulfiller")).statusCode());
        assertEquals(403,h.form("/requests/"+recover+"/fulfilment",recovery,auth("admin")).statusCode());assertEquals(302,h.protectedForm("/requests/"+recover+"/fulfilment",recovery,auth("admin")).statusCode());
        var recovered=get(h,recover,"admin");assertEquals(replacement,recovered.get("group_id"));assertEquals("Unassigned",recovered.get("assignee_name"));
        var adminClaim=result(h.json("POST",path(recover),command(recovered,"claim"),auth("admin")),200);var adminFinish=command(adminClaim,"complete");adminFinish.put("outcome","Recovered and delivered");result(h.json("POST",path(recover),adminFinish,auth("admin")),200);
        // Record visibility still controls tasks and queue counts; routing alone grants no record access.
        String privateRequest=order(h,offering,3);member(h,replacement,"Fulfiller",true);
        try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);RecordAccess.change(db,Service.record(db,"sc_request",privateRequest),actor,map("visibility","private","owner_id",actor));db.connection.commit();}
        assertEquals(404,h.get(path(privateRequest),auth("Fulfiller")).statusCode());assertEquals(0,parsed(h.get(QUEUE,auth("Fulfiller"))).get("total"));assertEquals(404,h.get(path(privateRequest),auth("OrderOwner")).statusCode());
        // Invalid controls and revisions have no partial effects.
        var task=get(h,privateRequest,"admin");for(Object invalid:List.of(map(),map("operation","other"),map("operation","claim","revision","1","request_revision",0),map("operation","claim","revision",0,"request_revision",0),map("operation","claim","revision",1,"request_revision","0"),map("operation","claim","revision",1,"request_revision",-1),map("operation","claim","revision",1,"request_revision",0,"extra",true)))assertEquals(400,h.json("POST",path(privateRequest),invalid,auth("admin")).statusCode());
        var badGroup=command(task,"reassign");badGroup.put("group_id",id());assertEquals(400,h.json("POST",path(privateRequest),badGroup,auth("admin")).statusCode());
        assertEquals(401,h.get(path(privateRequest),null).statusCode());assertEquals(405,h.json("DELETE",path(privateRequest),map(),auth("admin")).statusCode());assertEquals(400,h.get(path(privateRequest)+"?x=y",auth("admin")).statusCode());
        assertEquals(404,h.get(path(h.add("sc_request").get("sys_id").toString()),auth("admin")).statusCode());
        for(String args:List.of("?state=bad","?extra=x","?limit=0","?offset=-1"))assertEquals(400,h.get(QUEUE+args,auth("admin")).statusCode());assertEquals(405,h.json("POST",QUEUE,map(),auth("admin")).statusCode());
        assertEquals(200,h.get(QUEUE+"?state=all&limit=1&offset=1",auth("admin")).statusCode());assertEquals(200,h.get("/fulfilment?state=all&limit=1&offset=1",auth("admin")).statusCode());
        for(Object invalid:List.of(map(),map("group_id",42,"revision",3),map("group_id",replacement,"revision","3"),map("group_id",replacement,"revision",0),map("group_id",id(),"revision",3)))assertEquals(400,h.json("PUT",routing,invalid,auth("admin")).statusCode());
        assertEquals(409,h.json("PUT",routing,map("group_id",replacement,"revision",2),auth("admin")).statusCode());assertEquals(403,h.json("PUT",routing,map("group_id",replacement,"revision",3),auth("Fulfiller")).statusCode());assertEquals(405,h.get(routing,auth("admin")).statusCode());assertEquals(400,h.json("PUT",routing+"?x=y",map("group_id",replacement,"revision",3),auth("admin")).statusCode());
        assertEquals(403,h.form("/admin/catalogue/"+offering+"/routing",map("group_id","","revision",3),auth("admin")).statusCode());assertEquals(302,h.protectedForm("/admin/catalogue/"+offering+"/routing",map("group_id","","revision",3),auth("admin")).statusCode());
        String manual=order(h,offering,4);assertEquals(404,h.get(path(manual),auth("OrderOwner")).statusCode());
        assertEquals(204,h.json("DELETE","/api/now/table/sc_request/"+privateRequest,map(),auth("admin")).statusCode());try(var db=h.store.open()){assertEquals(0,db.count("SELECT COUNT(*) FROM os_fulfilment WHERE request_id=?",privateRequest));}
    }
}
