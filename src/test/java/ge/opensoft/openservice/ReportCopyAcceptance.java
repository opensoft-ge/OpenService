package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
final class ReportCopyAcceptance {
 static void run(WorkflowSupport h) throws Exception {
  h.account("CopyAgent","agent");h.account("CopyReader","reader");String table=ReportFormsAcceptance.seed(h.store),source;
  try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);source=SavedReports.save(db,actor,map("name","Shared source","table",table,"query",map("field","amount"),"shared",true)).get("report_id").toString();SavedReports.run(db,actor,source);db.connection.commit();}
  try(var db=h.store.open()){db.viewerId=RecordAccess.owner(db,"CopyReader");db.viewerRole="reader";assertEquals(403,assertThrows(Failure.class,()->SavedReports.copy(db,db.viewerId,source,map("name","Denied direct caller"))).status);}
  String api="/api/openservice/reports/"+source+"/copy",form="/reports/"+source+"/copy";
  var response=h.json("POST",api,map("name","Private analysis"),auth("CopyAgent"));assertEquals(201,response.statusCode(),html(response));String copy=object(parsed(response).get("result")).get("report_id").toString();assertNotEquals(source,copy);
  try(var db=h.store.open()){String actor=RecordAccess.owner(db,"CopyAgent");var row=SavedReports.find(db,actor,copy);assertEquals(actor,row.get("owner_id"));assertEquals(0,row.get("shared"));assertEquals(0,db.count("SELECT COUNT(*) FROM os_report_run WHERE report_id=?",copy));assertEquals(1,db.count("SELECT COUNT(*) FROM os_report_run WHERE report_id=?",source));assertEquals(1,db.one("SELECT shared FROM os_saved_report WHERE report_id=?",source).get("shared"));}
  assertEquals(404,h.get("/reports/"+copy,auth("admin")).statusCode());assertTrue(html(h.get("/reports/"+copy,auth("CopyAgent"))).contains(">10<"));assertEquals(403,h.json("POST",api,map("name","Denied"),auth("CopyReader")).statusCode());assertEquals(401,h.json("POST",api,map("name","Denied"),null).statusCode());assertEquals(405,h.get(api,auth("admin")).statusCode());assertEquals(400,h.json("POST",api+"?extra=x",map("name","Denied"),auth("admin")).statusCode());assertEquals(403,h.form(form,map("name","Denied"),auth("admin")).statusCode());
  for(var bad:List.of(map("name",""),map("name","x".repeat(161)),map("name","Valid","shared",true)))assertEquals(400,h.json("POST",api,bad,auth("CopyAgent")).statusCode());
  assertEquals(302,h.protectedForm(form,map("name","My source copy"),auth("admin")).statusCode());
  Map<String,Object> original;try(var db=h.store.open()){original=Service.schema(db,table);}var denied=object(JSON.readValue(JSON.writeValueAsString(original),Object.class));object(object(denied.get("field_rules")).get("amount")).put("read_roles",List.of("admin"));ReferencePickerAcceptance.writeSchema(h,table,denied);try{assertEquals(400,h.json("POST",api,map("name","Hidden field"),auth("CopyAgent")).statusCode());}finally{ReferencePickerAcceptance.writeSchema(h,table,original);}
  DiverseBatchAcceptance.fixtureUpdate(h,"UPDATE os_saved_report SET shared=0 WHERE report_id=?",source);assertEquals(404,h.json("POST",api,map("name","No longer shared"),auth("CopyAgent")).statusCode());DiverseBatchAcceptance.fixtureUpdate(h,"DELETE FROM os_saved_report WHERE report_id=?",source);assertEquals(404,h.json("POST",api,map("name","Deleted"),auth("admin")).statusCode());assertEquals(200,h.get("/reports/"+copy,auth("CopyAgent")).statusCode());
  try(var db=h.store.open()){String actor=RecordAccess.owner(db,"CopyAgent");for(int i=1;i<100;i++)db.update("INSERT INTO os_saved_report(report_id,owner_id,name,table_name,definition,shared) VALUES(?,?,?,?,?,0)",id(),actor,"Capacity "+i,table,"{}");db.connection.commit();}
  assertEquals(400,h.json("POST","/api/openservice/reports/"+copy+"/copy",map("name","Over capacity"),auth("CopyAgent")).statusCode());
 }
}
