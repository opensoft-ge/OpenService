package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

final class CatalogueAcceptance {
    static Map<String,Object> offering(String name,int revision,boolean active) {
        return map("name",name,"description","Managed service <script> description","active",active,"revision",revision);
    }
    static void run(WorkflowSupport h) throws Exception {
        h.account("CatalogOwner","requester");h.account("CatalogOther","requester");h.account("CatalogReader","reader");
        String api="/api/openservice/catalogue";
        var made=h.json("POST",api,offering("Equipment setup",0,true),auth("admin"));assertEquals(201,made.statusCode(),html(made));
        String id=object(parsed(made).get("result")).get("offering_id").toString(),item=api+"/"+id;
        assertEquals(1,objects(parsed(h.get(api,auth("CatalogOwner"))).get("result")).size());
        assertEquals(200,h.get("/catalogue",auth("CatalogOwner")).statusCode());assertEquals(200,h.get("/catalogue/"+id,auth("CatalogReader")).statusCode());
        assertEquals(401,h.get(api,null).statusCode());assertEquals(403,h.json("POST",api,offering("Denied",0,true),auth("CatalogOwner")).statusCode());
        assertEquals(403,h.get("/admin/catalogue",auth("CatalogReader")).statusCode());assertEquals(400,h.get(api+"?x=y",auth("admin")).statusCode());
        assertEquals(405,h.json("DELETE",item,map(),auth("admin")).statusCode());assertEquals(404,h.get(api+"/"+id(),auth("admin")).statusCode());
        for(var bad:List.of(map(),offering("",0,true),offering("x".repeat(161),0,true),map("name","Valid","description","d","active","true","revision",0),offering("Valid",1,true)))assertEquals(400,h.json("POST",api,bad,auth("admin")).statusCode());
        for(Object revision:List.of("0",-1)){var bad=offering("Valid",0,true);bad.put("revision",revision);assertEquals(400,h.json("POST",api,bad,auth("admin")).statusCode());}
        assertEquals(400,h.protectedForm("/admin/catalogue",map("name","Bad","description","d","active","other","revision","0"),auth("admin")).statusCode());
        assertEquals(400,h.get("/catalogue/"+id+"/order",auth("admin")).statusCode());assertEquals(405,h.get(item+"/order",auth("admin")).statusCode());
        var order=map("revision",1,"details","Please prepare this week","key",id());
        var created=h.json("POST",item+"/order",order,auth("CatalogOwner"));assertEquals(201,created.statusCode(),html(created));
        var result=object(parsed(created).get("result"));String request=result.get("sys_id").toString();assertEquals("Equipment setup",result.get("short_description"));assertTrue(result.get("description").toString().contains("Please prepare this week"));
        assertEquals(request,object(parsed(h.json("POST",item+"/order",order,auth("CatalogOwner"))).get("result")).get("sys_id"));
        var changed=new LinkedHashMap<>(order);changed.put("details","Changed");assertEquals(409,h.json("POST",item+"/order",changed,auth("CatalogOwner")).statusCode());
        assertEquals(404,h.get("/record/sc_request/"+request,auth("CatalogOther")).statusCode());
        assertEquals(403,h.json("POST",item+"/order",order,auth("CatalogReader")).statusCode());assertEquals(403,h.form("/catalogue/"+id,order,auth("CatalogOwner")).statusCode());
        assertEquals(200,h.json("PUT",item,offering("Revised service",1,false),auth("admin")).statusCode());
        assertEquals(409,h.json("PUT",item,offering("Stale update",1,true),auth("admin")).statusCode());
        assertEquals(404,h.get(item,auth("CatalogOwner")).statusCode());assertEquals(0,objects(parsed(h.get(api,auth("CatalogOwner"))).get("result")).size());
        assertEquals(404,h.json("POST",item+"/order",map("revision",2,"details","","key",id()),auth("CatalogOwner")).statusCode());
        assertEquals(200,h.get(item,auth("admin")).statusCode());assertEquals(200,h.json("PUT",item,offering("Revised service",2,true),auth("admin")).statusCode());
        assertEquals(409,h.json("POST",item+"/order",map("revision",1,"details","","key",id()),auth("CatalogOwner")).statusCode());
        for(var bad:List.of(map(),map("revision",3,"details",3,"key",id()),map("revision",3,"details","x".repeat(2001),"key",id()),map("revision",3,"details","\0","key",id()),map("revision",3,"details","","key","bad")))assertEquals(400,h.json("POST",item+"/order",bad,auth("CatalogOwner")).statusCode());
        for(Object revision:List.of("3",0))assertEquals(400,h.json("POST",item+"/order",map("revision",revision,"details","","key",id()),auth("CatalogOwner")).statusCode());
        assertEquals(302,h.protectedForm("/catalogue/"+id,map("revision","3","details","Second order","key",id()),auth("CatalogOwner")).statusCode());
        try(var db=h.store.open()){assertEquals(2,db.count("SELECT COUNT(*) FROM os_catalog_order"));assertEquals("Equipment setup",object(db.one("SELECT data FROM os_record WHERE sys_id=?",request).get("data")).get("short_description"));}
        // Reopen persisted storage; the offering and idempotency key remain available.
        new Store(h.environment);
        assertEquals(request,object(parsed(h.json("POST",item+"/order",order,auth("CatalogOwner"))).get("result")).get("sys_id"));
        try(var db=h.store.open()){
            db.viewerId=RecordAccess.owner(db,"CatalogReader");db.viewerRole="reader";assertEquals(403,assertThrows(Failure.class,()->Catalogue.order(db,id,order)).status);
            PlatformFoundationTest.bind(db);Catalogue.order(db,id,map("revision",3,"details","Rolled back","key",id()));db.connection.rollback();assertEquals(2,db.count("SELECT COUNT(*) FROM os_catalog_order"));
        }
        var another=h.json("POST",item+"/order",map("revision",3,"details","","key",order.get("key")),auth("CatalogOther"));assertEquals(201,another.statusCode());assertNotEquals(request,object(parsed(another).get("result")).get("sys_id"));
        try(var db=h.store.open()){for(int i=1;i<200;i++)db.update("INSERT INTO os_offering(offering_id,name,description,active,revision) VALUES(?,?,?,0,1)",id(),"Inactive "+i,"d");db.connection.commit();}
        try { assertEquals(400,h.json("POST",api,offering("Over capacity",0,true),auth("admin")).statusCode()); }
        finally { try(var db=h.store.open()){db.update("DELETE FROM os_offering WHERE offering_id<>?",id);db.connection.commit();} }
    }
}
