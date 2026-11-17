package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;
/** Compare explicit immutable snapshots; draft text is never substituted for a missing version. */
final class KnowledgeComparison {
 private KnowledgeComparison() {}
 static Map<String,Object> compare(Store.Db db,String actor,String id,Map<String,String> args) throws SQLException {
  Service.requireAccountAdministrator(db,actor);RecordPolicy.require(Service.schema(db,"kb_knowledge"),"read",db.viewerRole);Service.record(db,"kb_knowledge",id);
  require(Set.of("left","right").containsAll(args.keySet()),"Use left and right versions only");
  long latest=db.count("SELECT COALESCE(MAX(version),0) FROM os_publication WHERE article_id=?",id);
  if(latest==0)throw new Failure(404,"No publication versions found");
  int left=Service.number(args.getOrDefault("left",Long.toString(Math.max(1,latest-1))),1,Integer.MAX_VALUE);
  int right=Service.number(args.getOrDefault("right",Long.toString(latest)),1,Integer.MAX_VALUE);
  var first=db.one("SELECT version,title,content,published_at,retired FROM os_publication WHERE article_id=? AND version=?",id,left);
  var second=db.one("SELECT version,title,content,published_at,retired FROM os_publication WHERE article_id=? AND version=?",id,right);
  if(first==null || second==null)throw new Failure(404,"Publication version not found");
  return map("article_id",id,"latest_version",latest,"left",first,"right",second,"title_changed",!first.get("title").equals(second.get("title")),"content_changed",!first.get("content").equals(second.get("content")));
 }
}
