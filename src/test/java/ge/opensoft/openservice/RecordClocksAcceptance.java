package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
final class RecordClocksAcceptance {
 static void run(WorkflowSupport h) throws Exception {
  h.account("ClockReader","reader");h.account("ClockAgent","agent");String record,other,calendar="record-clock";
  try(var db=h.store.open()){PlatformFoundationTest.bind(db);record=PlatformFoundationTest.record(db,"incident","Clock intake");other=PlatformFoundationTest.record(db,"incident","Other record");db.connection.commit();}
  BusinessDeadlineAcceptance.seed(h.store,calendar,PlatformFoundationTest.calendar());String path="/record/incident/"+record+"/clocks";
  assertEquals(200,h.get(path,auth("admin")).statusCode());assertTrue(html(h.get(path,auth("admin"))).contains("Automatic clock processing is disabled"));assertEquals(200,h.get(path,auth("ClockReader")).statusCode());assertFalse(html(h.get(path,auth("ClockReader"))).contains("Start service clock"));
  var form=map("operation","start","calendar",calendar,"minutes","120");
  assertEquals(302,h.protectedForm(path,form,auth("admin")).statusCode());String clock;
  try(var db=h.store.open()){var row=db.one("SELECT * FROM os_sla WHERE record_id=?",record);clock=row.get("sla_id").toString();assertEquals(7200000,((Number)row.get("remaining_ms")).longValue());assertEquals("running",row.get("state"));}
  assertEquals(403,h.protectedForm(path,form,auth("ClockReader")).statusCode());assertEquals(403,h.protectedForm(path,form,auth("ClockAgent")).statusCode());assertEquals(403,h.form(path,form,auth("admin")).statusCode());
  for(String op:List.of("pause","resume","complete")){assertEquals(302,h.protectedForm(path,map("operation",op,"clock_id",clock),auth("admin")).statusCode());}
  assertEquals(400,h.protectedForm(path,map("operation","resume","clock_id",clock),auth("admin")).statusCode());
  assertEquals(404,h.protectedForm("/record/incident/"+other+"/clocks",map("operation","pause","clock_id",clock),auth("admin")).statusCode());
  assertEquals(404,h.protectedForm(path,map("operation","pause","clock_id",id()),auth("admin")).statusCode());
  assertEquals(400,h.protectedForm(path,map("operation","start","calendar",calendar,"minutes","0"),auth("admin")).statusCode());assertEquals(400,h.protectedForm(path,map("operation","start","minutes","120"),auth("admin")).statusCode());assertEquals(400,h.protectedForm(path,map("operation","other","clock_id",clock),auth("admin")).statusCode());assertEquals(400,h.protectedForm(path,map("operation","pause","clock_id",clock,"extra","x"),auth("admin")).statusCode());
  assertEquals(400,h.get(path+"?x=y",auth("admin")).statusCode());assertEquals(405,h.request("PUT",path,("csrf="+h.basicCsrf(auth("admin"))).getBytes(java.nio.charset.StandardCharsets.UTF_8),"application/x-www-form-urlencoded",auth("admin")).statusCode());assertEquals(302,h.get(path,null).statusCode());
  try(var db=h.store.open()){PlatformFoundationTest.bind(db);db.update("INSERT INTO os_record_access(record_id,visibility,owner_id,group_id) VALUES(?,'private',?,'')",record,db.viewerId);db.connection.commit();}
  assertEquals(404,h.get(path,auth("ClockReader")).statusCode());
  try(var db=h.store.open()){assertEquals(1,db.count("SELECT COUNT(*) FROM os_sla"));assertEquals("completed",db.one("SELECT state FROM os_sla").get("state"));}
 }
}
