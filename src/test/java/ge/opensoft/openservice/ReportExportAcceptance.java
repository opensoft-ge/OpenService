package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.apache.commons.csv.*;
final class ReportExportAcceptance {
 static List<CSVRecord> rows(String value) throws Exception {try(var p=CSVParser.parse(value,CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get())){return p.getRecords();}}
 static void run(WorkflowSupport h) throws Exception {
  h.account("ExportReader","reader");h.account("ExportAgent","agent");h.account("ExportRequester","requester");String table=ReportFormsAcceptance.seed(h.store),report;
  List<String> risky=List.of("=1+1","+SUM(1)","-1+2","@SUM(1)","\tvalue","\rvalue","\nvalue","  =2+3","＝1+1","＋1+2","－1+2","＠SUM(1)","\u200b=1+2");
  try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);for(String label:risky)Service.create(db,table,map("short_description",label,"amount","-2.5"),"admin");Service.create(db,table,map("short_description","Quoted, \"name\"\nქართული","amount",""),"admin");report=SavedReports.save(db,actor,map("name","Export test","table",table,"query",map("field","amount","group_by","short_description"),"shared",true)).get("report_id").toString();db.connection.commit();}
  String path="/reports/"+report+"/export.csv",api="/api/openservice/reports/"+report+"/export.csv";
  for(String actor:List.of("ExportReader","ExportAgent"))for(String endpoint:List.of(path,api)){
   var response=h.get(endpoint,auth(actor));assertEquals(200,response.statusCode(),html(response));assertTrue(response.headers().firstValue("content-type").orElseThrow().contains("text/csv"));assertTrue(response.headers().firstValue("content-disposition").orElseThrow().contains(report+".csv"));assertEquals("no-store",response.headers().firstValue("cache-control").orElseThrow());var result=rows(html(response));assertEquals(risky.size()+2,result.size());assertTrue(result.stream().noneMatch(x->x.get("group").equals("Private")));assertEquals("10",result.stream().filter(x->x.get("group").equals("Visible")).findFirst().orElseThrow().get("sum"));for(String label:risky){var row=result.stream().filter(x->x.get("group").equals("'"+label)).findFirst().orElseThrow();assertEquals("-2.5",row.get("sum"));assertEquals("1",row.get("count"));}var empty=result.stream().filter(x->x.get("group").equals("Quoted, \"name\"\nქართული")).findFirst().orElseThrow();assertEquals("",empty.get("average"));assertEquals("0",empty.get("value_count"));
  }
  assertEquals(risky.size()+3,rows(html(h.get(api,auth("admin")))).size());assertEquals(0,rows(html(h.get(api,auth("ExportRequester")))).size());
  assertEquals(401,h.get(api,null).statusCode());assertEquals(302,h.get(path,null).statusCode());assertEquals(405,h.json("POST",api,map(),auth("admin")).statusCode());assertEquals(400,h.get(api+"?field=amount",auth("admin")).statusCode());assertEquals(404,h.get(api.replace(report,id()),auth("admin")).statusCode());
  try(var db=h.store.open()){assertEquals(2,db.count("SELECT COUNT(*) FROM os_report_run WHERE report_id=? AND viewer_id=?",report,RecordAccess.owner(db,"ExportReader")));db.update("UPDATE os_saved_report SET shared=0 WHERE report_id=?",report);db.connection.commit();}
  assertEquals(404,h.get(api,auth("ExportReader")).statusCode());assertEquals(404,h.get(path,auth("ExportAgent")).statusCode());
  try(var db=h.store.open()){db.update("UPDATE os_saved_report SET shared=1 WHERE report_id=?",report);var schema=Service.schema(db,table);object(object(schema.get("field_rules")).get("amount")).put("read_roles",List.of("admin"));db.update("UPDATE os_schema SET definition="+db.jsonParameter()+" WHERE name=?",JSON.writeValueAsString(schema),table);db.connection.commit();}
  assertEquals(400,h.get(api,auth("ExportReader")).statusCode());assertEquals(400,h.get(path,auth("ExportReader")).statusCode());
  try(var db=h.store.open()){var schema=Service.schema(db,table);object(object(schema.get("field_rules")).get("amount")).remove("read_roles");object(object(schema.get("field_rules")).get("short_description")).put("read_roles",List.of("admin"));db.update("UPDATE os_schema SET definition="+db.jsonParameter()+" WHERE name=?",JSON.writeValueAsString(schema),table);db.connection.commit();}
  assertEquals(400,h.get(api,auth("ExportReader")).statusCode());
  try(var db=h.store.open()){assertEquals(2,db.count("SELECT COUNT(*) FROM os_report_run WHERE report_id=? AND viewer_id=?",report,RecordAccess.owner(db,"ExportReader")));}
  try(var db=h.store.open()){PlatformFoundationTest.bind(db);SavedReports.save(db,db.viewerId,report,map("name","Ungrouped","table",table,"query",map("field","amount","q","short_description=No such record"),"shared",true));db.connection.commit();}
  var none=rows(html(h.get(api,auth("admin"))));assertEquals(1,none.size());assertEquals("",none.getFirst().get("group"));assertEquals("0",none.getFirst().get("count"));assertEquals("",none.getFirst().get("min"));new Store(h.environment);assertEquals(200,h.get(path,auth("admin")).statusCode());
 }
}
