package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;

final class AuditGrantAcceptance {
 static void run(WorkflowSupport h) throws Exception {
  h.account("AuditReader","reader");String user;
  try(var db=h.store.open()){user=RecordAccess.owner(db,"AuditReader");}
  String page="/admin/accounts/"+user;
  assertEquals(302,h.protectedForm(page,map("operation","audit","enabled","true"),auth("admin")).statusCode());
  assertTrue(html(h.get(page,auth("admin"))).contains("Audit journal access"));
  for(String path:new String[]{"/admin/security-events","/admin/authentication-events","/api/openservice/security-events","/api/openservice/authentication-events"})assertEquals(200,h.get(path,auth("AuditReader")).statusCode(),path);
  assertEquals(200,h.get("/admin/security-events?download=true",auth("AuditReader")).statusCode());
  assertEquals(403,h.get("/admin/accounts",auth("AuditReader")).statusCode());
  assertEquals(403,h.protectedForm(page,map("operation","audit","enabled","true"),auth("AuditReader")).statusCode());
  assertEquals(403,h.form(page,map("operation","audit","enabled","true"),auth("admin")).statusCode());
  for(String value:new String[]{"","yes","TRUE"})assertEquals(400,h.protectedForm(page,map("operation","audit","enabled",value),auth("admin")).statusCode());
  h.login("AuditReader");assertEquals(200,h.get("/admin/security-events",null).statusCode());
  // Basic administrator credentials take precedence over the reader session.
  assertEquals(302,h.protectedForm(page,map("operation","audit","enabled","false"),auth("admin")).statusCode());
  assertEquals(302,h.get("/admin/security-events",null).statusCode());
  assertEquals(403,h.get("/admin/security-events",auth("AuditReader")).statusCode());
  try(var db=h.store.open()){
   assertEquals("reader",Service.account(db,user).get("role"));
   assertEquals(0,db.count("SELECT audit_viewer FROM os_user WHERE user_id=?",user));
   assertEquals(2,db.count("SELECT COUNT(*) FROM os_security_event WHERE target_id=? AND action='account.audit_access_changed'",user));
  }
  new Store(h.environment);assertEquals(403,h.get("/admin/authentication-events",auth("AuditReader")).statusCode());
 }
}
