package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
final class KnowledgeComparisonAcceptance {
 static void run(WorkflowSupport h) throws Exception {
  h.account("VersionReader","reader");String id;
  try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);id=KnowledgeSearchAcceptance.article(db,actor,"First <script>","Original instructions");db.connection.commit();}
  String api="/api/openservice/knowledge/"+id+"/compare",page="/admin/knowledge/"+id+"/versions";
  Map<String,Object> original;try(var db=h.store.open()){original=Service.schema(db,"kb_knowledge");}var denied=object(JSON.readValue(JSON.writeValueAsString(original),Object.class));denied.put("record_roles",map("read",List.of("agent")));ReferencePickerAcceptance.writeSchema(h,"kb_knowledge",denied);try{assertEquals(403,h.get(api,auth("admin")).statusCode());assertEquals(403,h.get(page,auth("admin")).statusCode());}finally{ReferencePickerAcceptance.writeSchema(h,"kb_knowledge",original);}
  var first=h.get(api,auth("admin"));assertEquals(200,first.statusCode(),html(first));var result=object(parsed(first).get("result"));assertEquals(false,result.get("title_changed"));assertEquals(false,result.get("content_changed"));
  assertEquals(403,h.get(api,auth("VersionReader")).statusCode());assertEquals(401,h.get(api,null).statusCode());assertEquals(403,h.get(page,auth("VersionReader")).statusCode());
  try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);Service.update(db,"kb_knowledge",Service.record(db,"kb_knowledge",id),map("short_description","Second title","text","Replacement body"),"admin","admin",null);PublishedKnowledge.publish(db,actor,id);db.connection.commit();}
  result=object(parsed(h.get(api,auth("admin"))).get("result"));assertEquals(true,result.get("title_changed"));assertEquals(true,result.get("content_changed"));assertEquals("Original instructions",object(result.get("left")).get("content"));assertEquals("Replacement body",object(result.get("right")).get("content"));
  var html=html(h.get(page+"?left=2&right=1",auth("admin")));assertTrue(html.contains("First &lt;script&gt;"));assertTrue(html.contains("Title changed"));assertTrue(html.contains("Body changed"));
  try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);Service.update(db,"kb_knowledge",Service.record(db,"kb_knowledge",id),map("text","UNPUBLISHED DRAFT"),"admin","admin",null);PublishedKnowledge.retire(db,actor,id);db.connection.commit();}
  assertFalse(html(h.get(page,auth("admin"))).contains("UNPUBLISHED DRAFT"));assertTrue(html(h.get(page,auth("admin"))).contains("Retired"));
  for(String query:List.of("left=0","left=x","right=2147483648","right=0","left=1&left=2","unknown=x"))assertEquals(400,h.get(api+"?"+query,auth("admin")).statusCode());
  assertEquals(404,h.get(api+"?left=99",auth("admin")).statusCode());assertEquals(404,h.get(api+"?right=99",auth("admin")).statusCode());
  assertEquals(405,h.json("POST",api,map(),auth("admin")).statusCode());
  String draft;try(var db=h.store.open()){PlatformFoundationTest.bind(db);draft=Service.create(db,"kb_knowledge",map("short_description","Unpublished","text","Body"),"admin").get("sys_id").toString();db.connection.commit();}
  assertEquals(404,h.get("/api/openservice/knowledge/"+draft+"/compare",auth("admin")).statusCode());
  try(var db=h.store.open()){for(int version=3;version<=103;version++)db.update("INSERT INTO os_publication(article_id,version,title,content,published_at) VALUES(?,?,?,?,?)",id,version,"Later version "+version,"Retained content",now());db.connection.commit();}
  var distant=object(parsed(h.get(api+"?left=1&right=103",auth("admin"))).get("result"));assertEquals("Original instructions",object(distant.get("left")).get("content"));assertEquals(103,object(distant.get("right")).get("version"));
  try(var db=h.store.open()){assertEquals(103,db.count("SELECT COUNT(*) FROM os_publication WHERE article_id=?",id));db.update("DELETE FROM os_record WHERE sys_id=?",id);db.connection.commit();}assertEquals(404,h.get(api,auth("admin")).statusCode());
 }
}
