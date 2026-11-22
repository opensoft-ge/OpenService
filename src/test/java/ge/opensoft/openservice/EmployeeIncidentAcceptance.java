package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.net.http.HttpResponse;

final class EmployeeIncidentAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        String admin=auth("admin"), origin="/api/openservice/reported-incidents", setup="/admin/starter-desk";
        h.account("DeskReporter","requester"); h.account("OtherReporter","requester");
        h.account("DutyAgent","agent"); h.account("WatchingDesk","reader");
        String reporter=auth("DeskReporter"), agent=auth("DutyAgent"), other=auth("OtherReporter");
        Map<String,Object> report=map("short_description","VPN unavailable","description","Office laptop","key",id());
        assertEquals(409,h.json("POST",origin,report,reporter).statusCode());
        assertEquals(200,h.get(setup,admin).statusCode());
        assertEquals(403,h.get(setup,reporter).statusCode());
        assertEquals(400,h.protectedForm(setup,map("operation","unknown"),admin).statusCode());
        String group=h.add("sys_user_group").get("sys_id").toString();
        assertEquals(409,h.protectedForm(setup,map("operation","route","group_id",group),admin).statusCode());
        for(int i=0;i<2;i++) assertEquals(302,h.protectedForm(setup,map("operation","install"),admin).statusCode());
        assertEquals(302,h.protectedForm(setup,map("operation","route","group_id",group),admin).statusCode());
        assertEquals(200,h.get(setup,admin).statusCode());
        assertEquals(200,h.get("/reported-incidents",reporter).statusCode());
        assertEquals(403,h.get(origin,auth("WatchingDesk")).statusCode());
        assertEquals(404,h.get(origin+"/"+id(),reporter).statusCode());
        assertEquals(400,h.json("POST",origin,map(),reporter).statusCode());
        for(Object bad:List.of(1,"x".repeat(8001))) {
            var invalid=new LinkedHashMap<>(report);invalid.put("description",bad);
            assertEquals(400,h.json("POST",origin,invalid,reporter).statusCode());
        }
        var created=h.json("POST",origin,report,reporter);
        assertEquals(201,created.statusCode(),html(created));
        String id=object(parsed(created).get("result")).get("incident_id").toString(), url=origin+"/"+id;
        assertEquals(201,h.json("POST",origin,report,reporter).statusCode());
        for(String field:List.of("short_description","description")) {
            var changed=new LinkedHashMap<>(report); changed.put(field,"Different");
            assertEquals(409,h.json("POST",origin,changed,reporter).statusCode());
        }
        assertEquals(302,h.protectedForm("/reported-incidents",report,reporter).statusCode());
        assertEquals(200,h.get("/reported-incidents/"+id,reporter).statusCode());
        var incident="/api/now/table/incident/"+id;
        assertEquals(group,object(parsed(h.get(incident,agent)).get("result")).get("assignment_group"));
        assertTrue(html(h.get("/record/incident/"+id,agent)).contains("Reply to employee"));
        assertFalse(html(h.get("/record/incident/"+id,auth("WatchingDesk"))).contains("Reply to employee"));
        assertEquals(404,h.get(incident,reporter).statusCode());
        assertEquals(404,h.get(url,other).statusCode());
        assertEquals(403,h.json("POST","/api/now/table/incident",map("short_description","No generic write"),reporter).statusCode());
        var reply=map("body","Please reconnect","key",id());
        for(int i=0;i<2;i++) assertEquals(200,h.json("POST",url,reply,agent).statusCode());
        assertEquals(409,h.json("POST",url,map("body","Changed","key",reply.get("key")),agent).statusCode());
        assertEquals(400,h.json("POST",url,map(),agent).statusCode());
        assertEquals(302,h.protectedForm("/reported-incidents/"+id,map("body","Recovered","key",id()),reporter).statusCode());
        try(var db=h.store.open()) {
            PlatformFoundationTest.bind(db);
            Service.audit(db,Service.record(db,"incident",id),"admin","note",map("body","Internal journal canary"));
            db.connection.commit();
        }
        for(String state:List.of("2","6")) assertEquals(200,h.json("PATCH",incident,map("state",state),agent).statusCode());
        HttpResponse<byte[]> page=h.get(url,reporter);
        assertFalse(html(page).contains("Internal journal canary"));
        var detail=object(parsed(page).get("result"));
        assertEquals("Resolved",detail.get("status"));assertEquals(2,objects(detail.get("replies")).size());
        Map<String,Object> original; try(var db=h.store.open()){original=Service.schema(db,"incident");}var restricted=new LinkedHashMap<>(original);
        restricted.put("field_rules",map("comments",map("type","string","read_roles",List.of("admin")),
                "state",map("type","string","read_roles",List.of("admin")),
                "description",map("type","string","read_roles",List.of("admin")),
                "short_description",map("type","string","read_roles",List.of("admin"))));
        ReferencePickerAcceptance.writeSchema(h,"incident",restricted);
        try{
            assertEquals(403,h.json("POST",url,reply,reporter).statusCode());
            assertFalse(html(h.get(url,reporter)).contains("Office laptop"));
            assertFalse(html(h.get(origin,reporter)).contains("VPN unavailable"));
            assertEquals(200,h.get(origin,admin).statusCode());
        }finally{ReferencePickerAcceptance.writeSchema(h,"incident",original);}
        String parent=h.add("problem").get("sys_id").toString();
        try(var db=h.store.open()) {
            db.update("INSERT INTO os_record_scope(record_id,parent_id,revision) VALUES(?,?,1)",id,parent);db.connection.commit();
        }
        assertEquals(404,h.get(url,reporter).statusCode());
        assertEquals(0,objects(parsed(h.get(origin,reporter)).get("result")).size());
        try(var db=h.store.open()) {db.update("DELETE FROM os_record_scope WHERE record_id=?",id);db.connection.commit();}
        // Even a private record owned by this reporter must leave the published projection.
        ProblemInvestigationsAcceptance.access(h,"incident",id,"private",KnowledgeReviewsAcceptance.accountId(h.store,"DeskReporter"));
        assertEquals(404,h.get(url,reporter).statusCode());
        ProblemInvestigationsAcceptance.access(h,"incident",id,"private",KnowledgeReviewsAcceptance.accountId(h.store,"admin"));
        assertEquals(404,h.get(url,reporter).statusCode());
        assertEquals(0,objects(parsed(h.get(origin,reporter)).get("result")).size());
        assertEquals(204,h.json("DELETE",incident,map(),admin).statusCode());
        assertEquals(404,h.get(url,admin).statusCode());
    }
}
