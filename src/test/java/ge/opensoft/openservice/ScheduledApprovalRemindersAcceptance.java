package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;

final class ScheduledApprovalRemindersAcceptance {
    static final String JOBS="/api/openservice/operations/jobs";
    static String path(String approval) { return "/api/openservice/approvals/"+approval+"/reminder-jobs"; }
    static Map<String,Object> request(long due,String key) { return map("run_at",Instant.ofEpochMilli(due).toString(),"key",key); }
    static Map<String,Object> detail(WorkflowSupport h,String id) throws Exception {var r=h.get(JOBS+"/"+id,auth("admin"));assertEquals(200,r.statusCode());return object(parsed(r).get("result"));}
    static String approval(WorkflowSupport h,String record) throws Exception {return ApprovalAcceptance.create(h,"/api/openservice/records/incident/"+record+"/approvals","ScheduledReviewer","admin");}
    static String queue(WorkflowSupport h,String approval,long due,String key,String owner) throws Exception {
        var r=h.json("POST",path(approval),request(due,key),auth(owner));assertEquals(201,r.statusCode(),html(r));return object(parsed(r).get("result")).get("job_id").toString();
    }
    static void run(WorkflowSupport h) throws Exception {
        h.account("ScheduledReviewer","agent");h.account("ScheduleReader","reader");h.account("ScheduleOwner","admin");
        String record=h.add("incident").get("sys_id").toString(),approval=approval(h,record),key=id();
        long due=System.currentTimeMillis()+3600000;
        String job=queue(h,approval,due,key,"admin");
        assertEquals("queued",detail(h,job).get("state"));
        assertEquals(due,((Number)detail(h,job).get("available_at")).longValue());
        assertEquals(1,ApprovalAssignmentAcceptance.notices(h,"ScheduledReviewer").size());
        assertEquals(job,queue(h,approval,due,key,"admin"));
        assertEquals(409,h.json("POST",path(approval),request(due+1,key),auth("admin")).statusCode());
        var worker=new WorkWorker(new Store(h.environment));
        assertFalse(worker.runOne(due-1));assertTrue(worker.runOne(due));
        assertEquals("succeeded",detail(h,job).get("state"));
        assertEquals(map("delivered",true,"duplicate",false),detail(h,job).get("receipt"));
        assertEquals(2,ApprovalAssignmentAcceptance.notices(h,"ScheduledReviewer").size());
        String repeated=queue(h,approval,due,id(),"admin");assertTrue(worker.runOne(due));
        assertEquals(map("delivered",true,"duplicate",true),detail(h,repeated).get("receipt"));
        assertEquals(2,ApprovalAssignmentAcceptance.notices(h,"ScheduledReviewer").size());
        assertEquals(2,WorkSearchAcceptance.result(h,"?kind=approval.reminder").get("total"));
        for(Object bad:List.of(map(),map("key",id(),"run_at","bad"),map("key",id(),"run_at",Instant.now().minusSeconds(60).toString()),map("key",id(),"run_at",Instant.now().plusSeconds(367L*86400).toString())))assertEquals(400,h.json("POST",path(approval),bad,auth("admin")).statusCode());
        assertEquals(401,h.json("POST",path(approval),request(due,id()),null).statusCode());
        assertEquals(403,h.json("POST",path(approval),request(due,id()),auth("ScheduleReader")).statusCode());
        assertEquals(405,h.get(path(approval),auth("admin")).statusCode());
        assertEquals(404,h.json("POST",path(id()),request(due,id()),auth("admin")).statusCode());
        assertEquals(400,h.json("POST",path(approval)+"?extra=x",request(due,id()),auth("admin")).statusCode());
        assertEquals(400,h.json("POST",JOBS,map("kind","approval.reminder","key",id(),"payload",map()),auth("admin")).statusCode());
        assertEquals(400,h.json("POST",JOBS,map("kind","approval.reminder","key",id(),"payload",map("approval_id",42)),auth("admin")).statusCode());
        String browser="/admin/approvals/"+approval+"/reminder-jobs";
        assertEquals(403,h.form(browser,request(due,id()),auth("admin")).statusCode());
        var browserResult=h.protectedForm(browser,request(due,id()),auth("admin"));assertEquals(302,browserResult.statusCode());
        String cancelled=browserResult.headers().firstValue("Location").orElseThrow().replace("/admin/jobs/","");
        assertEquals(200,h.json("POST",JOBS+"/"+cancelled,map("operation","cancel"),auth("admin")).statusCode());
        assertFalse(worker.runOne(due));assertEquals("cancelled",detail(h,cancelled).get("state"));
        assertEquals(200,h.json("POST","/api/openservice/records/incident/"+record+"/approvals/"+approval+"/decision",map("decision","approved","comment","Reviewed"),auth("ScheduledReviewer")).statusCode());
        assertEquals(job,queue(h,approval,due,key,"admin"));
        assertEquals(409,h.json("POST",path(approval),request(due,id()),auth("admin")).statusCode());
        String changed=h.add("incident").get("sys_id").toString(),staleApproval=approval(h,changed),stale=queue(h,staleApproval,due,id(),"admin");
        assertEquals(200,h.json("PATCH","/api/now/table/incident/"+changed,map("description","Changed after scheduling"),auth("admin")).statusCode());
        assertTrue(worker.runOne(due));assertEquals("cancelled",detail(h,stale).get("state"));
        String privateRecord=h.add("incident").get("sys_id").toString(),privateApproval=approval(h,privateRecord),revoked=queue(h,privateApproval,due,id(),"admin");
        try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);RecordAccess.change(db,Service.record(db,"incident",privateRecord),actor,map("visibility","private","owner_id",actor));db.connection.commit();}
        assertTrue(worker.runOne(due));assertEquals("cancelled",detail(h,revoked).get("state"));
        String optedRecord=h.add("incident").get("sys_id").toString(),optedApproval=approval(h,optedRecord),opted=queue(h,optedApproval,due,id(),"admin");
        assertEquals(200,h.json("POST","/api/openservice/me/notifications",map("enabled",false),auth("ScheduledReviewer")).statusCode());
        assertTrue(worker.runOne(due));assertEquals(map("delivered",false,"duplicate",false),detail(h,opted).get("receipt"));
        assertEquals(200,h.json("POST","/api/openservice/me/notifications",map("enabled",true),auth("ScheduledReviewer")).statusCode());
        String lost=queue(h,optedApproval,due,id(),"ScheduleOwner");
        DiverseBatchAcceptance.fixtureUpdate(h,"UPDATE os_user SET active=0 WHERE name='ScheduleOwner'");
        assertTrue(worker.runOne(due));assertEquals("cancelled",detail(h,lost).get("state"));
        try(var db=h.store.open()){
            String actor=PlatformFoundationTest.bind(db);long before=db.count("SELECT COUNT(*) FROM os_work");
            WorkJobs.enqueue(h.store,db,actor,map("kind","approval.reminder","key",id(),"run_at",Instant.ofEpochMilli(due).toString(),"payload",map("approval_id",optedApproval)));db.connection.rollback();
            assertEquals(before,db.count("SELECT COUNT(*) FROM os_work"));
        }
    }
}
