package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
final class ReferencePickerAcceptance {
 static Map<String,String> seed(Store store) throws Exception {
  String table="u_picker_"+id(),targetTable=table+"_target";
  try(var db=store.open()){
   String actor=PlatformFoundationTest.bind(db);Configuration.install(db,ConfigurationAcceptance.bundle(targetTable,ConfigurationAcceptance.definition()),actor,false);var definition=ConfigurationAcceptance.definition();object(definition.get("fields")).put("related","Related case");object(definition.get("field_rules")).put("related",map("type","reference","target",targetTable));Configuration.install(db,ConfigurationAcceptance.bundle(table,definition),actor,false);
   String source=Service.create(db,table,map("short_description","Source"),"admin").get("sys_id").toString();
   String target=Service.create(db,targetTable,map("short_description","Target 100% <script>"),"admin").get("sys_id").toString();Service.create(db,targetTable,map("short_description","Second target"),"admin");
   String hidden=Service.create(db,targetTable,map("short_description","HIDDEN TARGET"),"admin").get("sys_id").toString();
   RecordAccess.change(db,Service.record(db,targetTable,hidden),actor,map("visibility","private","owner_id",actor));db.connection.commit();
   return Map.of("table",table,"target_table",targetTable,"source",source,"target",target,"hidden",hidden,"path","/record/"+table+"/"+source+"/reference/related");
  }
 }
 static void writeSchema(WorkflowSupport h,String table,Map<String,Object> definition) throws Exception {try(var db=h.store.open()){assertEquals(1,db.update("UPDATE os_schema SET definition="+db.jsonParameter()+" WHERE name=?",JSON.writeValueAsString(definition),table));db.connection.commit();}}
 static void run(WorkflowSupport h) throws Exception {
  h.account("PickerAgent","agent");h.account("PickerReader","reader");var data=seed(h.store);String path=data.get("path"),table=data.get("table"),source=data.get("source");
  var response=h.get(path+"?q=100%25",auth("PickerAgent"));assertEquals(200,response.statusCode(),html(response));assertTrue(html(response).contains("Target 100% &lt;script&gt;"));assertFalse(html(response).contains("HIDDEN TARGET"));
  var first=html(h.get(path+"?limit=1",auth("PickerAgent")));assertTrue(first.contains("2 matching records"));assertTrue(first.contains("Next references"));assertTrue(html(h.get(path+"?limit=1&offset=1",auth("PickerAgent"))).contains("Previous references"));assertTrue(html(h.get(path+"?q=absent",auth("PickerAgent"))).contains("No readable records match."));
  assertEquals(403,h.get(path,auth("PickerReader")).statusCode());assertEquals(302,h.get(path,null).statusCode());assertEquals(403,h.form(path,map("value",data.get("target"),"revision","0"),auth("PickerAgent")).statusCode());
  assertEquals(302,h.protectedForm(path,map("value",data.get("target"),"revision","0"),auth("PickerAgent")).statusCode());
  assertEquals(409,h.protectedForm(path,map("value","","revision","0"),auth("PickerAgent")).statusCode());
  assertEquals(302,h.protectedForm(path,map("value","","revision","1"),auth("PickerAgent")).statusCode());
  for(String target:List.of(data.get("hidden"),id()))assertEquals(404,h.protectedForm(path,map("value",target,"revision","2"),auth("PickerAgent")).statusCode());
  for(var invalid:List.of(map("value","not-an-id","revision","2"),map("value","","revision","bad"),map("value","","revision","2","role","admin")))assertEquals(400,h.protectedForm(path,invalid,auth("PickerAgent")).statusCode());
  assertEquals(400,h.get(path.replace("/related","/amount"),auth("PickerAgent")).statusCode());assertEquals(400,h.get(path+"?q=a&q=b",auth("PickerAgent")).statusCode());assertEquals(400,h.protectedForm(path+"?q=source",map("value","","revision","2"),auth("PickerAgent")).statusCode());
  Map<String,Object> original;try(var db=h.store.open()){original=Service.schema(db,table);}
  for(String kind:List.of("read_roles","write_roles")){
   var changed=object(JSON.readValue(JSON.writeValueAsString(original),Object.class));object(object(changed.get("field_rules")).get("related")).put(kind,List.of("admin"));writeSchema(h,table,changed);assertEquals(403,h.get(path,auth("PickerAgent")).statusCode());assertEquals(403,h.protectedForm(path,map("value",data.get("target"),"revision","2"),auth("PickerAgent")).statusCode());
  }
  var changed=object(JSON.readValue(JSON.writeValueAsString(original),Object.class));object(object(changed.get("field_rules")).get("related")).put("write_roles",List.of("admin","agent"));writeSchema(h,table,changed);assertEquals(200,h.get(path,auth("PickerAgent")).statusCode());
  Map<String,Object> targetOriginal;try(var db=h.store.open()){targetOriginal=Service.schema(db,data.get("target_table"));}
  var targetChanged=object(JSON.readValue(JSON.writeValueAsString(targetOriginal),Object.class));targetChanged.put("record_roles",map("read",List.of("admin")));writeSchema(h,data.get("target_table"),targetChanged);assertEquals(403,h.get(path,auth("PickerAgent")).statusCode());assertEquals(403,h.protectedForm(path,map("value",data.get("target"),"revision","2"),auth("PickerAgent")).statusCode());
  targetChanged=object(JSON.readValue(JSON.writeValueAsString(targetOriginal),Object.class));object(object(targetChanged.get("field_rules")).get("short_description")).put("read_roles",List.of("admin"));writeSchema(h,data.get("target_table"),targetChanged);assertEquals(403,h.get(path,auth("PickerAgent")).statusCode());
  writeSchema(h,data.get("target_table"),targetOriginal);writeSchema(h,table,original);try(var db=h.store.open()){assertEquals("",object(Service.record(db,table,source).get("data")).get("related"));}
 }
}
