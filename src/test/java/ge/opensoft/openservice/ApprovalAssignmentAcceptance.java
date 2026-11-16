package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

final class ApprovalAssignmentAcceptance {
    static List<Map<String,Object>> notices(WorkflowSupport h,String name) throws Exception {
        var response=h.get("/api/openservice/me/notifications",auth(name));assertEquals(200,response.statusCode());return objects(parsed(response).get("result"));
    }
    static void run(WorkflowSupport h) throws Exception {
        for(String user:List.of("AssignedA","AssignedB","AssignedMuted"))h.account(user,"agent");
        h.account("AssignedReader","reader");
        String record=h.add("incident").get("sys_id").toString(),path="/api/openservice/records/incident/"+record+"/approvals";
        var proposal=ApprovalAcceptance.proposal("AssignedA");proposal.put("reason","PRIVATE REVIEW REASON");
        var response=h.json("POST",path,proposal,auth("admin"));assertEquals(201,response.statusCode());
        String approval=object(parsed(response).get("result")).get("approval_id").toString();
        var first=notices(h,"AssignedA");assertEquals(1,first.size());assertEquals(0,first.getFirst().get("acknowledged"));
        assertTrue(first.getFirst().get("message").toString().contains(approval));assertFalse(JSON.writeValueAsString(first).contains("PRIVATE REVIEW REASON"));
        assertEquals(409,h.json("POST",path,proposal,auth("admin")).statusCode());assertEquals(1,notices(h,"AssignedA").size());
        String delegate=path+"/"+approval+"/delegate";
        assertEquals(403,h.json("POST",delegate,map("approver","AssignedB"),auth("AssignedReader")).statusCode());assertTrue(notices(h,"AssignedB").isEmpty());
        assertEquals(200,h.json("POST",delegate,map("approver","AssignedB"),auth("AssignedA")).statusCode());assertEquals(1,notices(h,"AssignedB").size());
        assertEquals(1,notices(h,"AssignedA").size());
        assertEquals(200,h.json("POST",delegate,map("approver","AssignedA"),auth("AssignedB")).statusCode());
        assertEquals(200,h.json("POST",delegate,map("approver","AssignedB"),auth("AssignedA")).statusCode());assertEquals(2,notices(h,"AssignedB").size());
        assertEquals(200,h.json("POST","/api/openservice/me/notifications",map("enabled",false),auth("AssignedMuted")).statusCode());
        ApprovalAcceptance.create(h,path,"AssignedMuted","admin");assertTrue(notices(h,"AssignedMuted").isEmpty());
        try(var db=h.store.open()) {
            var actor=ApprovalTest.as(db,"admin");long before=db.count("SELECT COUNT(*) FROM os_notice");
            String transientRecord=Service.create(db,"incident",map("short_description","Uncommitted record"),"admin").get("sys_id").toString();
            Approvals.request(db,"incident",transientRecord,actor,ApprovalAcceptance.proposal("AssignedA"));
            assertEquals(before+1,db.count("SELECT COUNT(*) FROM os_notice"));db.connection.rollback();
            assertEquals(before,db.count("SELECT COUNT(*) FROM os_notice"));
        }
        new Store(h.environment);assertEquals(2,notices(h,"AssignedB").size());
        assertTrue(notices(h,"admin").isEmpty());
    }
}
