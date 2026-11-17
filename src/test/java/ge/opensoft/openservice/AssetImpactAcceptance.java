package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
final class AssetImpactAcceptance {
 static String asset(Store.Db db,String title) throws Exception {return Service.create(db,"cmdb_ci",map("short_description",title),"admin").get("sys_id").toString();}
 static Map<String,String> seed(Store store) throws Exception {try(var db=store.open()){String actor=PlatformFoundationTest.bind(db),source=asset(db,"Impact source"),child=asset(db,"Visible child <script>"),hidden=asset(db,"Hidden intermediate"),behind=asset(db,"Behind hidden path");for(var pair:List.of(List.of(child,source),List.of(source,child),List.of(hidden,source),List.of(behind,hidden)))AssetOperations.link(db,actor,map("dependent",pair.get(0),"dependency",pair.get(1),"remove",false));RecordAccess.change(db,Service.record(db,"cmdb_ci",hidden),actor,map("visibility","private","owner_id",actor));db.connection.commit();return Map.of("source",source,"child",child,"hidden",hidden,"behind",behind,"path","/record/cmdb_ci/"+source+"/impact");}}
 static void run(WorkflowSupport h) throws Exception {
  h.account("ImpactReader","reader");var data=seed(h.store);String path=data.get("path"),api="/api/openservice/operations/dependencies/"+data.get("source");
  Map<String,Object> original;try(var db=h.store.open()){original=Service.schema(db,"cmdb_ci");}var restricted=object(JSON.readValue(JSON.writeValueAsString(original),Object.class));restricted.put("record_roles",map("read",List.of("admin")));ReferencePickerAcceptance.writeSchema(h,"cmdb_ci",restricted);
  try{assertEquals(403,h.get(api,auth("ImpactReader")).statusCode());}finally{ReferencePickerAcceptance.writeSchema(h,"cmdb_ci",original);}
  var page=h.get(path,auth("ImpactReader"));assertEquals(200,page.statusCode(),html(page));assertTrue(html(page).contains("Visible child &lt;script&gt;"));assertFalse(html(page).contains("Hidden intermediate"));assertFalse(html(page).contains("Behind hidden path"));assertTrue(html(page).contains("1 visible dependent records"));
  restricted=object(JSON.readValue(JSON.writeValueAsString(original),Object.class));restricted.put("record_roles",map("read",List.of("admin")));ReferencePickerAcceptance.writeSchema(h,"cmdb_ci",restricted);try{assertEquals(403,h.get(path,auth("ImpactReader")).statusCode());}finally{ReferencePickerAcceptance.writeSchema(h,"cmdb_ci",original);}
  restricted=object(JSON.readValue(JSON.writeValueAsString(original),Object.class));var rules=TypedFields.rules(restricted);rules.put("short_description",map("type","string","read_roles",List.of("admin")));restricted.put("field_rules",rules);ReferencePickerAcceptance.writeSchema(h,"cmdb_ci",restricted);try{String redacted=html(h.get(path,auth("ImpactReader")));assertFalse(redacted.contains("Impact source"));assertFalse(redacted.contains("Visible child"));assertTrue(redacted.contains(data.get("child")));}finally{ReferencePickerAcceptance.writeSchema(h,"cmdb_ci",original);}
  assertEquals(302,h.get(path,null).statusCode());assertEquals(400,h.get(path+"?extra=x",auth("admin")).statusCode());assertEquals(405,h.protectedForm(path,map(),auth("admin")).statusCode());
  try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);RecordAccess.change(db,Service.record(db,"cmdb_ci",data.get("source")),actor,map("visibility","private","owner_id",actor));for(int i=0;i<101;i++){String target=asset(db,"Extra dependent "+i);AssetOperations.link(db,actor,map("dependent",target,"dependency",data.get("source"),"remove",false));}db.connection.commit();}
  assertEquals(404,h.get(path,auth("ImpactReader")).statusCode());assertTrue(html(h.get(path,auth("admin"))).contains("Showing the first 100 dependents"));assertTrue(html(h.get("/record/cmdb_ci/"+data.get("behind")+"/impact",auth("admin"))).contains("No visible dependent records"));
  try(var db=h.store.open()){db.update("DELETE FROM os_record WHERE sys_id=?",data.get("source"));db.connection.commit();}assertEquals(404,h.get(path,auth("admin")).statusCode());
 }
}
