package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
final class ProfileAcceptance {
 static final String API="/api/openservice/me/profile";
 static void run(WorkflowSupport h) throws Exception {
  h.account("ProfileOwner","requester");h.account("ProfileOther","reader");String actor;
  try(var db=h.store.open()){actor=RecordAccess.owner(db,"ProfileOwner");}
  var empty=map("display_name","","job_title","");
  var response=h.get(API,auth("ProfileOwner"));assertEquals(200,response.statusCode());assertEquals(empty,parsed(response).get("result"));
  var value=map("display_name","  Jose\u0301 <script>  ","job_title","Customer success");
  assertEquals(200,h.json("POST",API,value,auth("ProfileOwner")).statusCode());
  var expected=map("display_name","José <script>","job_title","Customer success");
  assertEquals(expected,parsed(h.get(API,auth("ProfileOwner"))).get("result"));assertEquals(empty,parsed(h.get(API,auth("ProfileOther"))).get("result"));
  assertEquals(200,h.json("POST",API,expected,auth("ProfileOwner")).statusCode());
  try(var db=h.store.open()){
   var events=db.all("SELECT detail FROM os_security_event WHERE target_id=? AND action='account.profile_changed'",actor);assertEquals(1,events.size());String details=events.toString();assertTrue(details.contains("display_name"));assertFalse(details.contains("José"));assertFalse(details.contains("Customer success"));
   assertEquals("requester",Service.account(db,actor).get("role"));assertEquals("ProfileOwner",Service.account(db,actor).get("name"));
  }
  new Store(h.environment);assertEquals(expected,parsed(h.get(API,auth("ProfileOwner"))).get("result"));
  for(Object invalid:Arrays.asList(null,12,"x".repeat(81),"x".repeat(1025),"bad\ntext","bad\u0000text"))assertEquals(400,h.json("POST",API,map("display_name",invalid,"job_title",""),auth("ProfileOwner")).statusCode());
  assertEquals(200,h.json("POST",API,map("display_name","😀".repeat(80),"job_title",""),auth("ProfileOwner")).statusCode());
  assertEquals(400,h.json("POST",API,map("display_name","😀".repeat(81),"job_title",""),auth("ProfileOwner")).statusCode());
  for(String key:List.of("user_id","role","email")){var bad=new LinkedHashMap<>(empty);bad.put(key,"forged");assertEquals(400,h.json("POST",API,bad,auth("ProfileOwner")).statusCode());}
  assertEquals(400,h.json("POST",API,map(),auth("ProfileOwner")).statusCode());assertEquals(400,h.get(API+"?user_id="+actor,auth("ProfileOther")).statusCode());assertEquals(401,h.get(API,null).statusCode());
  assertEquals(403,h.form("/account/profile",empty,auth("ProfileOwner")).statusCode());
  assertEquals(302,h.protectedForm("/account/profile",map("display_name","A".repeat(80),"job_title",""),auth("ProfileOwner")).statusCode());
  assertEquals(200,h.json("POST",API,empty,auth("ProfileOwner")).statusCode());assertEquals(empty,parsed(h.get(API,auth("ProfileOwner"))).get("result"));
  assertEquals(200,h.json("POST",API,map("display_name","","job_title","Only title changed"),auth("ProfileOwner")).statusCode());
  assertEquals(200,h.json("PATCH","/api/openservice/users/"+actor+"/status",map("active",false),auth("admin")).statusCode());
  assertEquals(401,h.get(API,auth("ProfileOwner")).statusCode());
 }
}
