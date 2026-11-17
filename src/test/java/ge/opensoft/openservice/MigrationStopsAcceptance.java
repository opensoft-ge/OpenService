package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
final class MigrationStopsAcceptance {
 static void concurrentStop(WorkflowSupport h) throws Exception {
  String job=create(h);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);MigrationSource.entered=entered;MigrationSource.release=release;
  try(var executor=Executors.newFixedThreadPool(2)){
   try{
    var step=executor.submit(()->{try(var db=h.store.open()){PlatformFoundationTest.bind(db);TransferJobs.step(db,job,MigrationSource.TOKEN,h.store.migrationOrigins);db.connection.commit();return true;}});
    assertTrue(entered.await(10,TimeUnit.SECONDS));var stopping=new CountDownLatch(1);
    var stop=executor.submit(()->{stopping.countDown();try(var db=h.store.open()){PlatformFoundationTest.bind(db);var result=MigrationStops.cancel(db,job,map("reason","Stop after in-flight page"));db.connection.commit();return result;}});
    assertTrue(stopping.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->stop.get(200,TimeUnit.MILLISECONDS));
    release.countDown();assertTrue(step.get(30,TimeUnit.SECONDS));assertEquals("cancelled",object(stop.get(30,TimeUnit.SECONDS).get("detail")).get("status"));
   }finally{release.countDown();MigrationSource.release=null;MigrationSource.entered=null;}
  }
  int calls=MigrationSource.queries.size();assertEquals(409,TransferAcceptance.step(h,job,MigrationSource.TOKEN).statusCode());assertEquals(calls,MigrationSource.queries.size());
 }

 static String create(WorkflowSupport h) throws Exception{return TransferAcceptance.result(h.json("POST",TransferAcceptance.API,TransferValidationAcceptance.basic(),auth("admin")),201).get("job_id").toString();}
 static void run(WorkflowSupport h) throws Exception {
  MigrationSource.reset();h.account("StopReader","reader");String directory="/admin/migration-stops";assertEquals(200,h.get(directory,auth("admin")).statusCode());assertTrue(html(h.get(directory,auth("admin"))).contains("No saved migrations"));
  var rows=new ArrayList<Map<String,Object>>();for(int i=1;i<=101;i++)rows.add(map("sys_id",MigrationSource.key(i),"short_description","Stop source "+i,"state","1","priority","3"));MigrationSource.tables.put("incident",rows);
  String id=create(h),api=TransferAcceptance.API+"/"+id+"/cancel",form="/admin/migrations/"+id+"/cancel";assertEquals(200,h.get(api,auth("admin")).statusCode());assertEquals(200,TransferAcceptance.step(h,id,MigrationSource.TOKEN).statusCode());Map<String,Object> claimed;
  try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);WorkJobs.enqueue(h.store,db,actor,map("kind","migration.step","key",id(),"payload",map("migration_id",id,"token",MigrationSource.TOKEN)));claimed=WorkJobs.claim(db,System.currentTimeMillis()+100);db.connection.commit();}
  int calls=MigrationSource.queries.size();var input=map("reason","Stop pending evaluation <script>");var stopped=TransferAcceptance.result(h.json("POST",api,input,auth("admin")),200);assertEquals("cancelled",object(stopped.get("detail")).get("status"));assertEquals(200,h.json("POST",api,map("reason","A later retry"),auth("admin")).statusCode());
  assertTrue(new WorkWorker(h.store).runClaimed(claimed,System.currentTimeMillis()+100));assertEquals(calls,MigrationSource.queries.size());assertEquals(409,TransferAcceptance.step(h,id,MigrationSource.TOKEN).statusCode());
  for(String action:List.of("preview","apply","sync","cutover"))assertEquals(409,h.json("POST",TransferAcceptance.API+"/"+id+"/"+action,map(),auth("admin")).statusCode(),action);
  assertEquals(calls,MigrationSource.queries.size());try(var db=h.store.open()){assertEquals(100,db.count("SELECT COUNT(*) FROM os_transfer_item WHERE job_id=?",id));assertEquals(0,db.count("SELECT COUNT(*) FROM os_record"));var work=db.one("SELECT state,envelope FROM os_work WHERE job_id=?",claimed.get("job_id"));assertEquals("cancelled",work.get("state"));assertEquals("",work.get("envelope"));assertEquals(1,db.count("SELECT COUNT(*) FROM os_security_event WHERE action='migration.cancelled'"));}
  new Store(h.environment);assertEquals(input.get("reason"),object(TransferAcceptance.result(h.get(api,auth("admin")),200).get("detail")).get("cancel_reason"));assertEquals(200,h.get(TransferAcceptance.API+"/"+id+"/archive?table=incident",auth("admin")).statusCode());
  assertEquals(401,h.get(api,null).statusCode());assertEquals(403,h.get(directory,auth("StopReader")).statusCode());assertEquals(403,h.json("POST",api,input,auth("StopReader")).statusCode());assertEquals(405,h.json("PUT",api,input,auth("admin")).statusCode());assertEquals(400,h.get(api+"?x=y",auth("admin")).statusCode());assertEquals(404,h.get(TransferAcceptance.API+"/"+id()+"/cancel",auth("admin")).statusCode());
  for(var bad:List.of(map(),map("reason",""),map("reason",3),map("reason","x".repeat(2001)),map("reason","x","extra",true)))assertEquals(400,h.json("POST",api,bad,auth("admin")).statusCode());assertEquals(403,h.form(form,input,auth("admin")).statusCode());assertEquals(302,h.protectedForm(form,input,auth("admin")).statusCode());
  MigrationSource.tables.put("incident",List.of(rows.getFirst()));String reviewed=create(h);TransferAcceptance.fetch(h,reviewed);assertEquals(200,h.json("POST",TransferAcceptance.API+"/"+reviewed+"/cancel",input,auth("admin")).statusCode());
  String applied=create(h);TransferAcceptance.fetch(h,applied);assertEquals(200,h.json("POST",TransferAcceptance.API+"/"+applied+"/apply",map(),auth("admin")).statusCode());assertEquals(409,h.json("POST",TransferAcceptance.API+"/"+applied+"/cancel",input,auth("admin")).statusCode());assertTrue(html(h.get("/admin/migrations/"+applied+"/cancel",auth("admin"))).contains("already been applied"));
  try(var db=h.store.open()){assertEquals(1,db.count("SELECT COUNT(*) FROM os_record"));var original=TransferJobs.job(db,id);for(int i=0;i<101;i++)db.update("INSERT INTO os_transfer_job(job_id,source_url,creator_id,definition,detail,created) VALUES(?,?,?,?,?,?)",id(),original.get("source_url"),original.get("creator_id"),JSON.writeValueAsString(original.get("definition")),JSON.writeValueAsString(original.get("detail")),now());db.connection.commit();}
  assertTrue(html(h.get(directory,auth("admin"))).contains("Showing the latest 100 saved migrations"));concurrentStop(h);
 }
}
