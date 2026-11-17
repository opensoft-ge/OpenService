package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
final class KnowledgeFeedbackAcceptance {
 static Map<String,Object> response(WorkflowSupport h,String path,String user) throws Exception {var r=h.get(path,auth(user));assertEquals(200,r.statusCode(),html(r));return object(parsed(r).get("result"));}
 static void run(WorkflowSupport h) throws Exception {
  h.account("FeedbackRequester","requester");h.account("FeedbackReader","reader");h.account("FeedbackAgent","agent");String id,other,draft;
  try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);id=KnowledgeSearchAcceptance.article(db,actor,"Published instructions","A safe published answer");other=KnowledgeSearchAcceptance.article(db,actor,"Another answer","Public answer");draft=Service.create(db,"kb_knowledge",map("short_description","Private draft","text","HIDDEN DRAFT"),"admin").get("sys_id").toString();db.connection.commit();}
  String api="/api/openservice/knowledge/"+id,form="/knowledge/"+id,review="/api/openservice/knowledge-feedback/"+id,reviewForm="/admin/knowledge/"+id+"/feedback";var page=response(h,api,"FeedbackRequester");assertNull(page.get("feedback"));assertEquals("A safe published answer",object(page.get("article")).get("content"));
  var body=map("version",1,"helpful",false,"details","Missing a step <script>");var saved=h.json("POST",api+"/feedback",body,auth("FeedbackRequester"));assertEquals(200,saved.statusCode(),html(saved));var feedback=object(object(parsed(saved).get("result")).get("feedback"));String fid=feedback.get("feedback_id").toString();assertEquals(1,feedback.get("revision"));assertEquals(0,feedback.get("reviewed"));
  assertEquals(200,h.json("POST",api+"/feedback",body,auth("FeedbackRequester")).statusCode());assertEquals(1,object(response(h,api,"FeedbackRequester").get("feedback")).get("revision"));assertNull(response(h,api,"FeedbackReader").get("feedback"));assertFalse(html(h.get(form,auth("FeedbackReader"))).contains("Missing a step"));
  var all=response(h,review,"admin");assertEquals(1,objects(all.get("rows")).size());assertEquals(1,((Number)all.get("pending")).intValue());
  var decision=map("feedback_id",fid,"revision",1,"note","Internal editorial follow-up");assertEquals(200,h.json("POST",review,decision,auth("admin")).statusCode());assertEquals(409,h.json("POST",review,decision,auth("admin")).statusCode());page=response(h,api,"FeedbackRequester");assertEquals(1,object(page.get("feedback")).get("reviewed"));assertFalse(page.toString().contains("Internal editorial"));
  // A changed response reopens review; identical retries preserve the reviewed decision.
  assertEquals(200,h.json("POST",api+"/feedback",body,auth("FeedbackRequester")).statusCode());assertEquals(1,object(response(h,api,"FeedbackRequester").get("feedback")).get("reviewed"));body.put("helpful",true);assertEquals(200,h.json("POST",api+"/feedback",body,auth("FeedbackRequester")).statusCode());assertEquals(0,object(response(h,api,"FeedbackRequester").get("feedback")).get("reviewed"));assertEquals(409,h.json("POST",review,decision,auth("admin")).statusCode());body.put("details","Now correct");assertEquals(200,h.json("POST",api+"/feedback",body,auth("FeedbackRequester")).statusCode());assertEquals(3,object(response(h,api,"FeedbackRequester").get("feedback")).get("revision"));
  assertEquals(404,h.json("POST",review.replace(id,other),map("feedback_id",fid,"revision",3,"note","Wrong article"),auth("admin")).statusCode());
  for(String actor:List.of("FeedbackReader","FeedbackAgent","FeedbackRequester")){assertEquals(403,h.get(review,auth(actor)).statusCode());assertEquals(403,h.json("POST",review,decision,auth(actor)).statusCode());}
  assertEquals(401,h.get(api,null).statusCode());assertEquals(404,h.get(api.replace(id,draft),auth("FeedbackRequester")).statusCode());assertEquals(400,h.get(api+"?x=y",auth("admin")).statusCode());assertEquals(405,h.json("PUT",api+"/feedback",body,auth("admin")).statusCode());assertEquals(405,h.get(api+"/feedback",auth("admin")).statusCode());
  for(var bad:List.of(map(),map("version","1","helpful",true,"details",""),map("version",0,"helpful",true,"details",""),map("version",1,"helpful","true","details",""),map("version",1,"helpful",true,"details",3),map("version",1,"helpful",true,"details","x".repeat(2001)),map("version",1,"helpful",true,"details","\0")))assertEquals(400,h.json("POST",api+"/feedback",bad,auth("FeedbackRequester")).statusCode());
  assertEquals(403,h.form(form+"/feedback",map("version","1","helpful","false","details",""),auth("FeedbackRequester")).statusCode());assertEquals(400,h.protectedForm(form+"/feedback",map("version","1","helpful","maybe","details",""),auth("FeedbackRequester")).statusCode());assertEquals(302,h.protectedForm(form+"/feedback",map("version","1","helpful","false","details","Reader feedback"),auth("FeedbackReader")).statusCode());
  for(var bad:List.of(map(),map("feedback_id",fid,"revision","3","note","r"),map("feedback_id",fid,"revision",0,"note","r"),map("feedback_id",fid,"revision",3,"note","")))assertEquals(400,h.json("POST",review,bad,auth("admin")).statusCode());
  assertEquals(403,h.form(reviewForm,map("feedback_id",fid,"revision","3","note","r"),auth("admin")).statusCode());assertEquals(302,h.protectedForm(reviewForm,map("feedback_id",fid,"revision","3","note","Reviewed latest response"),auth("admin")).statusCode());
  new Store(h.environment);assertEquals(1,object(response(h,api,"FeedbackRequester").get("feedback")).get("reviewed"));
  try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);var item=Service.record(db,"kb_knowledge",id);Service.update(db,"kb_knowledge",item,map("text","UNPUBLISHED NEXT DRAFT"),"admin","admin",null);db.connection.commit();}
  assertEquals("A safe published answer",object(response(h,api,"FeedbackRequester").get("article")).get("content"));
  try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);PublishedKnowledge.publish(db,actor,id);db.connection.commit();}
  assertEquals(409,h.json("POST",api+"/feedback",body,auth("FeedbackRequester")).statusCode());assertNull(response(h,api,"FeedbackRequester").get("feedback"));
  try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);for(int i=0;i<101;i++)db.update("INSERT INTO os_knowledge_feedback(feedback_id,article_id,version,user_id,helpful,details,updated_at) VALUES(?,?,1,?,0,'Synthetic feedback',?)",id(),id,id(),i);db.connection.commit();}
  all=response(h,review,"admin");assertEquals(100,objects(all.get("rows")).size());assertEquals(true,all.get("truncated"));
  try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);PublishedKnowledge.retire(db,actor,id);db.connection.commit();}
  assertEquals(404,h.get(api,auth("FeedbackRequester")).statusCode());assertEquals(404,h.json("POST",api+"/feedback",body,auth("FeedbackRequester")).statusCode());assertEquals(200,h.get(review,auth("admin")).statusCode());
  try(var db=h.store.open()){String actor=PlatformFoundationTest.bind(db);RecordAccess.change(db,Service.record(db,"kb_knowledge",other),actor,map("visibility","private","owner_id",actor));db.connection.commit();}
  assertEquals(404,h.get(api.replace(id,other),auth("admin")).statusCode());
 }
}
