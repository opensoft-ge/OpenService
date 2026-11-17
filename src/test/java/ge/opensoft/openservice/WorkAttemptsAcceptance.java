package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

final class WorkAttemptsAcceptance {
    static String api(String id){return "/api/openservice/operations/jobs/"+id+"/attempts";}
    static Map<String,Object> history(WorkflowSupport h,String id,String query)throws Exception{var r=h.get(api(id)+query,auth("admin"));assertEquals(200,r.statusCode(),html(r));return parsed(r);}
    static void run(WorkflowSupport h)throws Exception {
        h.account("AttemptReader","reader");h.account("AttemptAgent","agent");String first=WorkSearchAcceptance.seed(h.store,"History notification");
        assertEquals(0,history(h,first,"").get("total"));assertTrue(html(h.get("/admin/jobs/"+first+"/attempts",auth("admin"))).contains("No retained attempts"));
        var worker=new WorkWorker(h.store);assertTrue(worker.runOne(System.currentTimeMillis()+100));var item=objects(history(h,first,"").get("result")).getFirst();assertEquals("succeeded",item.get("state"));assertEquals(1,((Number)item.get("ordinal")).intValue());assertTrue(((Number)item.get("duration_ms")).longValue()>=0);assertFalse(item.get("finished_at_utc").toString().isEmpty());
        for(String user:List.of("AttemptReader","AttemptAgent")){assertEquals(403,h.get(api(first),auth(user)).statusCode());assertEquals(403,h.get("/admin/jobs/"+first+"/attempts",auth(user)).statusCode());}
        assertEquals(401,h.get(api(first),null).statusCode());assertEquals(404,h.get(api(id()),auth("admin")).statusCode());assertEquals(405,h.json("POST",api(first),map(),auth("admin")).statusCode());
        for(String bad:List.of("limit=0","limit=101","offset=-1","offset=101","extra=x","limit=1&limit=2"))assertEquals(400,h.get(api(first)+"?"+bad,auth("admin")).statusCode());
        try(var provider=new RecordQuestionsAcceptance.Provider(h.store,h.environment)){
            provider.status.set(503);provider.server.createContext("/attempt",exchange->{provider.calls.incrementAndGet();byte[] bytes="PRIVATE PROVIDER BODY".getBytes();exchange.sendResponseHeaders(provider.status.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
            var queued=h.json("POST","/api/openservice/operations/jobs",map("kind","connector.read","key",id(),"payload",map("alias",provider.alias,"path","/attempt")),auth("admin"));assertEquals(200,queued.statusCode(),html(queued));String job=object(parsed(queued).get("result")).get("job_id").toString();
            long now=System.currentTimeMillis()+100;assertTrue(worker.runOne(now));var failed=objects(history(h,job,"").get("result")).getFirst();assertEquals("retry_scheduled",failed.get("state"));assertEquals("EXECUTION_FAILED",failed.get("error"));assertEquals(1,provider.calls.get());
            provider.status.set(200);assertTrue(worker.runOne(now+10000));var succeeded=history(h,job,"?limit=1");assertEquals(2,succeeded.get("total"));assertEquals(1,succeeded.get("next_offset"));assertEquals("succeeded",objects(succeeded.get("result")).getFirst().get("state"));var older=history(h,job,"?limit=1&offset=1");assertEquals(-1,older.get("next_offset"));assertEquals(0,older.get("previous_offset"));assertEquals("retry_scheduled",objects(older.get("result")).getFirst().get("state"));assertEquals(2,provider.calls.get());
            String serialized=JSON.writeValueAsString(history(h,job,""));for(String secret:List.of("PRIVATE PROVIDER BODY","synthetic-question-token","envelope","lease","/attempt"))assertFalse(serialized.contains(secret),serialized);
            assertTrue(html(h.get("/admin/jobs/"+job+"/attempts?limit=1",auth("admin"))).contains("Next attempts"));assertTrue(html(h.get("/admin/jobs/"+job+"/attempts?limit=1&offset=1",auth("admin"))).contains("Previous attempts"));new Store(h.environment);assertEquals(2,history(h,job,"").get("total"));
        }
    }
    static void lifecycle(WorkflowSupport h)throws Exception {
        String job=WorkSearchAcceptance.seed(h.store,"Lease history");long now=System.currentTimeMillis()+100;Map<String,Object> old,current;
        try(var db=h.store.open()){old=WorkJobs.claim(db,now);assertNotNull(old);db.connection.commit();}
        var running=objects(history(h,job,"").get("result")).getFirst();assertEquals("processing",running.get("state"));assertEquals(-1,((Number)running.get("duration_ms")).intValue());assertEquals("",running.get("finished_at_utc"));
        try(var db=h.store.open()){current=WorkJobs.claim(db,now+120001);assertNotNull(current);db.connection.commit();}
        assertTrue(new WorkWorker(h.store).runClaimed(old,now+120002));var reclaim=objects(history(h,job,"").get("result"));assertEquals("processing",reclaim.getFirst().get("state"));assertEquals("lease_expired",reclaim.getLast().get("state"));assertEquals("LEASE_EXPIRED",reclaim.getLast().get("error"));
        for(int i=2;i<5;i++)try(var db=h.store.open()){assertNotNull(WorkJobs.claim(db,now+120001L*i));db.connection.commit();}
        try(var db=h.store.open()){assertNull(WorkJobs.claim(db,now+120001L*5));db.connection.commit();}
        var exhausted=objects(history(h,job,"").get("result"));assertEquals(5,exhausted.size());assertEquals("failed",exhausted.getFirst().get("state"));assertEquals("LEASE_EXHAUSTED",exhausted.getFirst().get("error"));
        assertEquals(200,h.json("POST","/api/openservice/operations/jobs/"+job,map("operation","retry"),auth("admin")).statusCode());assertTrue(new WorkWorker(new Store(h.environment)).runOne(now+1000000));var retried=objects(history(h,job,"").get("result")).getFirst();assertEquals(6,((Number)retried.get("ordinal")).intValue());assertEquals(1,retried.get("attempt_number"));assertEquals("succeeded",retried.get("state"));
        String bounded=WorkSearchAcceptance.seed(h.store,"Bounded attempt history");Map<String,Object> latest=null;
        for(int i=0;i<105;i++)try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);latest=WorkJobs.claim(db,now+2000000L+200001L*i);if(latest==null){WorkJobs.control(db,actor,bounded,"retry");latest=WorkJobs.claim(db,now+2000000L+200001L*i);}assertNotNull(latest);db.connection.commit();}
        var retained=history(h,bounded,"?limit=60");assertEquals(100,retained.get("total"));assertEquals(60,retained.get("next_offset"));assertEquals(105,((Number)objects(retained.get("result")).getFirst().get("ordinal")).intValue());var last=history(h,bounded,"?limit=60&offset=60");assertEquals(40,objects(last.get("result")).size());assertEquals(6,((Number)objects(last.get("result")).getLast().get("ordinal")).intValue());assertEquals(-1,last.get("next_offset"));
        assertTrue(new WorkWorker(h.store).runClaimed(latest,now+30000000));
        String rollback=WorkSearchAcceptance.seed(h.store,"Rolled back attempt");try(var db=h.store.open()){assertNotNull(WorkJobs.claim(db,now+40000000));db.connection.rollback();assertEquals(0,db.count("SELECT COUNT(*) FROM os_work_attempt WHERE job_id=?",rollback));assertEquals("queued",db.one("SELECT state FROM os_work WHERE job_id=?",rollback).get("state"));String actor=PlatformFoundationTest.bind(db);WorkJobs.control(db,actor,rollback,"cancel");db.connection.commit();}
        try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);db.update("UPDATE os_work SET created_at=0");WorkJobs.control(db,actor,"all","cleanup");assertEquals(0,db.count("SELECT COUNT(*) FROM os_work_attempt"));db.connection.commit();}assertEquals(404,h.get(api(job),auth("admin")).statusCode());
        String revoked=WorkSearchAcceptance.seed(h.store,"Revoked attempt");try(var db=h.store.open()){db.update("UPDATE os_work SET auth_revision=-1 WHERE job_id=?",revoked);db.connection.commit();}assertTrue(new WorkWorker(h.store).runOne(System.currentTimeMillis()+100));var cancelled=objects(history(h,revoked,"").get("result")).getFirst();assertEquals("cancelled",cancelled.get("state"));assertEquals("AUTHORITY_OR_INPUT_UNAVAILABLE",cancelled.get("error"));
    }
}
