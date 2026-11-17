package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;
/** Feedback is personal, version-specific and separately reviewed; it never publishes draft text. */
final class KnowledgeFeedback {
 private KnowledgeFeedback() {}
 static Map<String,Object> page(Store.Db db,String id) throws SQLException {
  var article=db.one("SELECT p.* FROM os_publication p WHERE p.article_id=? AND p.retired=0 AND p.version=(SELECT MAX(version) FROM os_publication WHERE article_id=p.article_id) AND EXISTS(SELECT 1 FROM os_record r WHERE r.sys_id=p.article_id AND r.\"table\"='kb_knowledge') AND NOT EXISTS(SELECT 1 FROM os_record_access a WHERE a.record_id=p.article_id AND a.visibility<>'workspace')",id);
  if(article==null)throw new Failure(404,"Published article not found");
  return map("article",article,"feedback",db.one("SELECT feedback_id,version,helpful,details,revision,reviewed FROM os_knowledge_feedback WHERE article_id=? AND version=? AND user_id=?",id,article.get("version"),db.viewerId));
 }
 static Map<String,Object> submit(Store.Db db,String id,Object input) throws SQLException {
  if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");var current=page(db,id);var body=object(input);
  require(body.keySet().equals(Set.of("version","helpful","details")),"Provide version, helpful and details");require(body.get("version") instanceof Integer v && v>0,"Use a positive integer version");require(body.get("helpful") instanceof Boolean,"Helpful must be boolean");require(body.get("details") instanceof String,"Feedback details must be text");String details=(String)body.get("details");require(details.length()<=2000 && details.indexOf('\0')<0,"Use at most 2000 feedback characters without NUL");
  if(!object(current.get("article")).get("version").equals(body.get("version")))throw new Failure(409,"Publication changed; read the current article before submitting feedback");
  int helpful=Boolean.TRUE.equals(body.get("helpful"))?1:0;var previous=current.get("feedback");
  if(previous!=null && object(previous).get("helpful").equals(helpful) && object(previous).get("details").equals(details))return current;
  String feedback=previous==null?id():object(previous).get("feedback_id").toString();
  db.update("INSERT INTO os_knowledge_feedback(feedback_id,article_id,version,user_id,helpful,details,updated_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(article_id,version,user_id) DO UPDATE SET helpful=excluded.helpful,details=excluded.details,updated_at=excluded.updated_at,revision=os_knowledge_feedback.revision+1,reviewed=0,review_note='',reviewer_id=''",feedback,id,body.get("version"),db.viewerId,helpful,details,System.currentTimeMillis());
  SecurityEvents.record(db,db.viewerId,db.viewerId,"knowledge.feedback_submitted",map("article_id",id,"version",body.get("version"),"feedback_id",feedback));return page(db,id);
 }
 static Map<String,Object> reviewPage(Store.Db db,String id) throws SQLException {
  Service.requireAccountAdministrator(db,db.viewerId);var item=Service.record(db,"kb_knowledge",id);
  var rows=db.all("SELECT f.*,COALESCE(u.name,'Unavailable account') AS username FROM os_knowledge_feedback f LEFT JOIN os_user u ON u.user_id=f.user_id WHERE article_id=? ORDER BY updated_at DESC,feedback_id LIMIT 101",id);boolean more=rows.size()>100;if(more)rows.removeLast();
  return map("article_id",id,"title",object(item.get("data")).get("short_description"),"rows",rows,"truncated",more,"pending",db.count("SELECT COUNT(*) FROM os_knowledge_feedback WHERE article_id=? AND reviewed=0",id));
 }
 static void review(Store.Db db,String id,Object input) throws SQLException {
  Service.requireAccountAdministrator(db,db.viewerId);if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");reviewPage(db,id);var body=object(input);require(body.keySet().equals(Set.of("feedback_id","revision","note")),"Provide feedback_id, revision and note");String feedback=WorkJobs.text(body.get("feedback_id"),32),note=WorkJobs.text(body.get("note"),2000);require(body.get("revision") instanceof Integer n && n>0,"Use a positive feedback revision");
  if(db.one("SELECT feedback_id FROM os_knowledge_feedback WHERE feedback_id=? AND article_id=?",feedback,id)==null)throw new Failure(404,"Feedback not found on this article");
  if(db.update("UPDATE os_knowledge_feedback SET reviewed=1,review_note=?,reviewer_id=? WHERE feedback_id=? AND article_id=? AND revision=? AND reviewed=0",note,db.viewerId,feedback,id,body.get("revision"))!=1)throw new Failure(409,"Feedback changed or was already reviewed; reload it");
  SecurityEvents.record(db,db.viewerId,db.viewerId,"knowledge.feedback_reviewed",map("article_id",id,"feedback_id",feedback,"revision",body.get("revision")));
 }
}
