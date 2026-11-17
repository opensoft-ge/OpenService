package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
final class ReportFormsAcceptance {
 static String seed(Store store) throws Exception {
  String table="u_reports_"+id();
  try(var db=store.open()){
   String actor=PlatformFoundationTest.bind(db);
   Configuration.install(db,ConfigurationAcceptance.bundle(table,ConfigurationAcceptance.definition()),actor,false);
   Service.create(db,table,map("short_description","Visible","amount","10"),"admin");
   var hidden=Service.create(db,table,map("short_description","Private","amount","90"),"admin");
   RecordAccess.change(db,hidden,actor,map("visibility","private","owner_id",actor));db.connection.commit();
  }return table;
 }
 static Map<String,Object> form(String table,boolean shared){return map("operation","save","name","Measured work","table",table,"field","amount","group_by","","q","","minimum","","maximum","","shared",Boolean.toString(shared));}
 static void run(WorkflowSupport h) throws Exception {
  h.account("ReportEditor","agent");h.account("ReportViewer","reader");h.account("ReportOther","agent");
  String table=seed(h.store);var body=form(table,false);
  var created=h.protectedForm("/reports",body,auth("admin"));assertEquals(302,created.statusCode(),html(created));
  String page=created.headers().firstValue("location").orElseThrow(),id=page.substring(page.lastIndexOf('/')+1);
  assertTrue(html(h.get("/reports",auth("admin"))).contains("Measured work"));assertTrue(html(h.get(page,auth("admin"))).contains("100"));
  assertEquals(404,h.get(page,auth("ReportViewer")).statusCode());
  body.put("shared","true");body.put("name","Shared metrics");assertEquals(302,h.protectedForm(page,body,auth("admin")).statusCode());
  var view=html(h.get(page,auth("ReportViewer")));assertTrue(view.contains("Shared metrics"));assertTrue(view.contains(">10<"));assertFalse(view.contains(">100<"));assertFalse(view.contains("Edit your report"));
  assertEquals(403,h.protectedForm(page,body,auth("ReportViewer")).statusCode());
  assertEquals(404,h.protectedForm(page,body,auth("ReportOther")).statusCode());
  assertEquals(404,h.protectedForm(page,map("operation","delete"),auth("ReportOther")).statusCode());
  assertEquals(403,h.form(page,body,auth("admin")).statusCode());
  assertEquals(400,h.protectedForm(page,map("operation","invalid"),auth("admin")).statusCode());
  var invalid=new LinkedHashMap<>(body);invalid.put("shared","maybe");assertEquals(400,h.protectedForm(page,invalid,auth("admin")).statusCode());
  invalid=new LinkedHashMap<>(body);invalid.put("unexpected","x");assertEquals(400,h.protectedForm("/reports",invalid,auth("admin")).statusCode());
  invalid=new LinkedHashMap<>(body);invalid.put("field","missing");assertEquals(400,h.protectedForm(page,invalid,auth("admin")).statusCode());
  assertEquals(400,h.get(page+"?extra=x",auth("admin")).statusCode());assertEquals(403,h.json("PUT",page,map(),auth("admin")).statusCode());
  assertEquals(302,h.get("/reports",null).statusCode());
  // A shared definition never restores revoked numeric-field access.
  try(var db=h.store.open()){
   var schema=Service.schema(db,table);object(object(schema.get("field_rules")).get("amount")).put("read_roles",List.of("admin"));
   db.update("UPDATE os_schema SET definition="+db.jsonParameter()+" WHERE name=?",JSON.writeValueAsString(schema),table);db.connection.commit();
  }
  assertEquals(400,h.get(page,auth("ReportViewer")).statusCode());
  new Store(h.environment);assertEquals(200,h.get(page,auth("admin")).statusCode());
  assertEquals(302,h.protectedForm(page,map("operation","delete"),auth("admin")).statusCode());assertEquals(404,h.get(page,auth("admin")).statusCode());
  try(var db=h.store.open()){assertEquals(0,db.count("SELECT COUNT(*) FROM os_report_run WHERE report_id=?",id));}
  // Demoting an owner leaves a readable definition without owner mutation controls.
  String editor;
  try(var db=h.store.open()){editor=RecordAccess.owner(db,"ReportEditor");PlatformFoundationTest.bind(db);var schema=Service.schema(db,table);object(object(schema.get("field_rules")).get("amount")).remove("read_roles");db.update("UPDATE os_schema SET definition="+db.jsonParameter()+" WHERE name=?",JSON.writeValueAsString(schema),table);db.connection.commit();}
  var own=h.protectedForm("/reports",body,auth("ReportEditor"));assertEquals(302,own.statusCode());String owned=own.headers().firstValue("location").orElseThrow();
  try(var db=h.store.open()){var schema=Service.schema(db,table);object(object(schema.get("field_rules")).get("amount")).put("read_roles",List.of("admin"));db.update("UPDATE os_schema SET definition="+db.jsonParameter()+" WHERE name=?",JSON.writeValueAsString(schema),table);db.connection.commit();}
  var repair=h.get(owned,auth("ReportEditor"));assertEquals(200,repair.statusCode(),"An owner must be able to repair a no-longer-runnable report");assertTrue(html(repair).contains("Results could not be calculated"));assertTrue(html(repair).contains("Edit your report"));
  body.put("field","quantity");assertEquals(302,h.protectedForm(owned,body,auth("ReportEditor")).statusCode());assertFalse(html(h.get(owned,auth("ReportEditor"))).contains("Results could not be calculated"));

  assertEquals(200,h.json("PATCH","/api/openservice/users/"+editor+"/role",map("role","reader"),auth("admin")).statusCode());
  assertFalse(html(h.get(owned,auth("ReportEditor"))).contains("Edit your report"));
 }
}
