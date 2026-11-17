package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

final class CatalogueEligibilityAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("EligibleBuyer","requester");h.account("ExcludedBuyer","requester");h.account("EligibleReader","reader");
        String api="/api/openservice/catalogue";
        String id=object(parsed(h.json("POST",api,CatalogueAcceptance.offering("Group equipment",0,true),auth("admin"))).get("result")).get("offering_id").toString();
        String item=api+"/"+id,eligibility=item+"/eligibility",group=h.add("sys_user_group").get("sys_id").toString();
        String member,reader;
        try(var db=h.store.open()){member=RecordAccess.owner(db,"EligibleBuyer");reader=RecordAccess.owner(db,"EligibleReader");}
        var saved=h.json("PUT",eligibility,map("group_id",group,"revision",1),auth("admin"));
        assertEquals(200,saved.statusCode(),"Offering eligibility must be editable: "+html(saved));
        assertEquals(group,object(parsed(saved).get("result")).get("group_id"));
        assertTrue(objects(parsed(h.get(api,auth("EligibleBuyer"))).get("result")).isEmpty());
        assertEquals(404,h.get(item,auth("EligibleBuyer")).statusCode());
        assertEquals(200,h.get(item,auth("admin")).statusCode());
        assertFalse(html(h.get("/catalogue/"+id,auth("admin"))).contains("Submit service request"),"Inspection authority must not offer an ineligible order form");
        for(String user:List.of(member,reader))assertEquals(200,h.json("PUT","/api/openservice/groups/"+group+"/members",map("user_id",user),auth("admin")).statusCode());
        assertEquals(1,objects(parsed(h.get(api,auth("EligibleBuyer"))).get("result")).size());
        assertEquals(200,h.get("/catalogue/"+id,auth("EligibleReader")).statusCode());
        assertFalse(html(h.get("/catalogue",auth("ExcludedBuyer"))).contains("Group equipment"));
        var order=map("revision",2,"details","Eligible request","key",id());
        assertEquals(403,h.json("POST",item+"/order",order,auth("EligibleReader")).statusCode());
        assertEquals(404,h.json("POST",item+"/order",order,auth("ExcludedBuyer")).statusCode());
        var created=h.json("POST",item+"/order",order,auth("EligibleBuyer"));assertEquals(201,created.statusCode(),html(created));
        String request=object(parsed(created).get("result")).get("sys_id").toString();
        assertEquals(409,h.json("PUT",eligibility,map("group_id","","revision",1),auth("admin")).statusCode());
        for(Object bad:List.of(map(),map("group_id",42,"revision",2),map("group_id","invalid","revision",2),map("group_id",id(),"revision",2),map("group_id",group,"revision","2"),map("group_id",group,"revision",0)))assertEquals(400,h.json("PUT",eligibility,bad,auth("admin")).statusCode());
        assertEquals(401,h.json("PUT",eligibility,map("group_id","","revision",2),null).statusCode());
        assertEquals(403,h.json("PUT",eligibility,map("group_id","","revision",2),auth("ExcludedBuyer")).statusCode());
        assertEquals(405,h.get(eligibility,auth("admin")).statusCode());
        assertEquals(404,h.json("PUT",api+"/"+id()+"/eligibility",map("group_id","","revision",1),auth("admin")).statusCode());
        assertEquals(400,h.json("PUT",eligibility+"?extra=x",map("group_id","","revision",2),auth("admin")).statusCode());
        assertEquals(200,h.json("DELETE","/api/openservice/groups/"+group+"/members/"+member,map(),auth("admin")).statusCode());
        assertEquals(404,h.get(item,auth("EligibleBuyer")).statusCode());
        assertEquals(404,h.json("POST",item+"/order",map("revision",2,"details","New request","key",id()),auth("EligibleBuyer")).statusCode());
        assertEquals(request,object(parsed(h.json("POST",item+"/order",order,auth("EligibleBuyer"))).get("result")).get("sys_id"));
        // Content updates retain the criterion, and deletion never opens the offering to everyone.
        assertEquals(200,h.json("PUT",item,CatalogueAcceptance.offering("Still restricted",2,true),auth("admin")).statusCode());
        assertEquals(group,object(parsed(h.get(item,auth("admin"))).get("result")).get("group_id"));
        assertEquals(204,h.json("DELETE","/api/now/table/sys_user_group/"+group,map(),auth("admin")).statusCode());
        assertEquals(404,h.get(item,auth("EligibleReader")).statusCode());
        assertTrue(html(h.get("/admin/catalogue/"+id,auth("admin"))).contains("Removed group"));
        new Store(h.environment);
        assertEquals(404,h.get(item,auth("ExcludedBuyer")).statusCode());
        try(var db=h.store.open()){
            String actor=PlatformFoundationTest.bind(db);CatalogueEligibility.save(db,actor,id,map("group_id","","revision",3));db.connection.rollback();
            assertEquals(group,Catalogue.find(db,id,true).get("group_id"));
        }
        assertEquals(403,h.form("/admin/catalogue/"+id+"/eligibility",map("group_id","","revision","3"),auth("admin")).statusCode());
        assertEquals(302,h.protectedForm("/admin/catalogue/"+id+"/eligibility",map("group_id","","revision","3"),auth("admin")).statusCode());
        assertEquals(200,h.get(item,auth("ExcludedBuyer")).statusCode());
        assertEquals(201,h.json("POST",item+"/order",map("revision",4,"details","Now available","key",id()),auth("ExcludedBuyer")).statusCode());
        assertEquals(409,h.json("POST",item+"/order",map("revision",3,"details","Stale","key",id()),auth("ExcludedBuyer")).statusCode());
        try(var db=h.store.open()){assertEquals(2,db.count("SELECT COUNT(*) FROM os_catalog_order"));}
    }
}
