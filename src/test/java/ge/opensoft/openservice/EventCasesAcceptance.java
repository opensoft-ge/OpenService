package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
final class EventCasesAcceptance {
 static final String API="/api/openservice/event-cases";
 static String query(String key){return "?key="+URLEncoder.encode(key,StandardCharsets.UTF_8);}
 static Map<String,Object> page(WorkflowSupport h,String key) throws Exception {var response=h.get(API+query(key),auth("admin"));assertEquals(200,response.statusCode(),html(response));return object(parsed(response).get("result"));}
 static void run(WorkflowSupport h) throws Exception {
  h.account("CaseReader","reader");h.account("CaseAgent","agent");String asset=h.add("cmdb_ci").get("sys_id").toString(),key="correlation /?&+%漢";var event=map("key",key,"asset_id",asset,"severity","critical","summary","Private source <script> details");
  assertEquals(200,h.json("POST","/api/openservice/operations/events",event,auth("admin")).statusCode());var reviewed=page(h,key);assertNull(reviewed.get("case"));var input=map("key",key,"review",reviewed.get("review"));
  event.put("summary","Revised private source");assertEquals(200,h.json("POST","/api/openservice/operations/events",event,auth("admin")).statusCode());assertEquals(409,h.json("POST",API,input,auth("admin")).statusCode());
  input.put("review",page(h,key).get("review"));var created=h.json("POST",API,input,auth("admin"));assertEquals(201,created.statusCode(),html(created));var record=object(parsed(created).get("result"));String id=record.get("sys_id").toString();assertEquals("1",record.get("priority"));assertTrue(record.get("description").toString().contains("Revised private source"));assertFalse(record.get("description").toString().contains("Private source <script>"));
  try(var db=h.store.open()){assertEquals("private",RecordAccess.status(db,id).get("visibility"));assertEquals(1,db.count("SELECT COUNT(*) FROM os_event_case WHERE event_key=?",key));assertEquals("open",db.one("SELECT state FROM os_operational_event WHERE event_key=?",key).get("state"));}
  for(String user:List.of("CaseReader","CaseAgent")){assertEquals(404,h.get("/api/now/table/incident/"+id,auth(user)).statusCode());assertEquals(403,h.get(API+query(key),auth(user)).statusCode());assertEquals(403,h.json("POST",API,input,auth(user)).statusCode());assertFalse(html(h.get("/audit",auth(user))).contains("Revised private source"));}
  // Repeated/changed events retain the same case and never overwrite its content or state.
  event.put("severity","info");event.put("summary","Later event revision");assertEquals(200,h.json("POST","/api/openservice/operations/events",event,auth("admin")).statusCode());assertEquals(id,object(parsed(h.json("POST",API,input,auth("admin"))).get("result")).get("sys_id"));new Store(h.environment);assertEquals(id,object(page(h,key).get("case")).get("sys_id"));assertEquals(record.get("description"),object(page(h,key).get("case")).get("description"));
  assertEquals(401,h.get(API+query(key),null).statusCode());assertEquals(400,h.get(API,auth("admin")).statusCode());assertEquals(400,h.get(API+query(key)+"&key=other",auth("admin")).statusCode());assertEquals(404,h.get(API+query("missing"),auth("admin")).statusCode());assertEquals(405,h.json("PUT",API,input,auth("admin")).statusCode());assertEquals(400,h.json("POST",API+query(key),input,auth("admin")).statusCode());
  for(var bad:List.of(map(),map("key",key,"review",""),map("key","","review","r"),map("key",key,"review","x".repeat(65))))assertEquals(400,h.json("POST",API,bad,auth("admin")).statusCode());
  assertEquals(403,h.form("/admin/events/case",input,auth("admin")).statusCode());assertEquals(302,h.protectedForm("/admin/events/case",input,auth("admin")).statusCode());
  String stopped="resolved-event";event.put("key",stopped);assertEquals(200,h.json("POST","/api/openservice/operations/events",event,auth("admin")).statusCode());assertEquals(200,h.json("POST","/api/openservice/operations/event-actions",map("key",stopped,"operation","resolve","reason","Handled"),auth("admin")).statusCode());assertEquals(409,h.json("POST",API,map("key",stopped,"review",page(h,stopped).get("review")),auth("admin")).statusCode());assertTrue(html(h.get("/admin/events/case"+query(stopped),auth("admin"))).contains("Reopen the event"));
  assertEquals(200,h.json("POST","/api/openservice/operations/event-actions",map("key",stopped,"operation","reopen","reason","Investigate again"),auth("admin")).statusCode());var fresh=map("key",stopped,"review",page(h,stopped).get("review"));
  Map<String,Object> original;try(var db=h.store.open()){original=Service.schema(db,"incident");}var denied=object(JSON.readValue(JSON.writeValueAsString(original),Object.class));denied.put("record_roles",map("create",List.of("agent")));ReferencePickerAcceptance.writeSchema(h,"incident",denied);try{assertEquals(403,h.json("POST",API,fresh,auth("admin")).statusCode());try(var db=h.store.open()){assertEquals(0,db.count("SELECT COUNT(*) FROM os_event_case WHERE event_key=?",stopped));}}finally{ReferencePickerAcceptance.writeSchema(h,"incident",original);}
  assertEquals("3",object(parsed(h.json("POST",API,fresh,auth("admin"))).get("result")).get("priority"));
  assertEquals(204,h.json("DELETE","/api/now/table/incident/"+id,map(),auth("admin")).statusCode());assertEquals(404,h.get(API+query(key),auth("admin")).statusCode());assertEquals(404,h.json("POST",API,input,auth("admin")).statusCode());
  try(var db=h.store.open()){assertEquals(1,db.count("SELECT COUNT(*) FROM os_event_case WHERE event_key=?",key));}
 }
}
