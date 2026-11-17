package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
final class BusinessDeadlineAcceptance {
 static Map<String,Object> calendar(String zone,String start,String end,List<Integer> days,List<String> holidays){return map("zone",zone,"start",start,"end",end,"days",days,"holidays",holidays);}
 static void seed(Store store,String name,Object calendar) throws Exception {try(var db=store.open()){String actor=PlatformFoundationTest.bind(db);ServiceClocks.calendar(db,actor,map("name",name,"calendar",calendar));db.connection.commit();}}
 static String path(String name,String start,int minutes){return "/api/openservice/calendar-preview?calendar="+name+"&start="+java.net.URLEncoder.encode(start,java.nio.charset.StandardCharsets.UTF_8)+"&minutes="+minutes;}
 static Map<String,Object> result(WorkflowSupport h,String name,String start,int minutes) throws Exception {var response=h.get(path(name,start,minutes),auth("admin"));assertEquals(200,response.statusCode(),html(response));return object(parsed(response).get("result"));}
 static void run(WorkflowSupport h) throws Exception {
  h.account("DeadlineReader","reader");String name="deadline"+id();seed(h.store,name,calendar("UTC","09:00","17:00",List.of(1,2,3,4,5),List.of("2026-11-23")));
  var deadline=result(h,name,"2026-11-20T16:30:00Z",120);assertEquals("2026-11-24T10:30:00Z",deadline.get("due"));assertEquals(2,deadline.get("windows"));assertEquals(7200000,deadline.get("business_ms"));assertEquals(false,deadline.get("truncated"));
  assertEquals("2026-11-20T17:00:00Z",result(h,name,"2026-11-20T16:30:00Z",30).get("due"));assertEquals("2026-11-24T10:00:00Z",result(h,name,"2026-11-20T17:01:00Z",60).get("due"));assertEquals("2026-11-20T10:00:00Z",result(h,name,"2026-11-20T08:00:00Z",60).get("due"));
  seed(h.store,name,calendar("Europe/London","01:00","04:00",List.of(7),List.of()));assertEquals("2026-03-29T03:00:00Z",result(h,name,"2026-03-29T00:00:00Z",120).get("due"));assertEquals("2026-10-25T04:00:00Z",result(h,name,"2026-10-25T00:00:00Z",240).get("due"));
  seed(h.store,name,calendar("UTC","09:00","09:01",List.of(1,2,3,4,5,6,7),List.of()));var bounded=result(h,name,"2026-11-17T00:00:00Z",101);assertEquals(101,bounded.get("windows"));assertEquals(100,objects(bounded.get("segments")).size());assertEquals(true,bounded.get("truncated"));
  assertTrue(html(h.get(path(name,"2026-11-17T00:00:00Z",101).replace("/api/openservice/calendar-preview","/admin/calendars/preview"),auth("admin"))).contains("Only the first 100 contributing windows"));
  seed(h.store,name,calendar("UTC","09:00","09:01",List.of(1),List.of()));assertEquals(400,h.get(path(name,"2026-11-17T00:00:00Z",525600),auth("admin")).statusCode());
  for(String start:List.of("invalid","2026-11-17T00:00:00","2026-11-17T00:00:00.0001Z","+1000000000-12-31T23:59:59.999Z"))assertEquals(400,h.get(path(name,start,1),auth("admin")).statusCode());
  for(int minutes:List.of(0,525601))assertEquals(400,h.get(path(name,"2026-11-17T00:00:00Z",minutes),auth("admin")).statusCode());
  String valid=path(name,"2026-11-17T00:00:00Z",1);assertEquals(400,h.get(valid+"&extra=x",auth("admin")).statusCode());assertEquals(400,h.get(valid+"&minutes=2",auth("admin")).statusCode());assertEquals(404,h.get(path("absent","2026-11-17T00:00:00Z",1),auth("admin")).statusCode());assertEquals(403,h.get(valid,auth("DeadlineReader")).statusCode());assertEquals(401,h.get(valid,null).statusCode());assertEquals(405,h.json("POST","/api/openservice/calendar-preview",map(),auth("admin")).statusCode());assertEquals(200,h.get("/admin/calendars/preview",auth("admin")).statusCode());
  try(var db=h.store.open()){assertEquals(0,db.count("SELECT COUNT(*) FROM os_sla"));}
 }
}
