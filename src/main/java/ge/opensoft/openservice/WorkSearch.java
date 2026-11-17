package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;
/** Operator metadata search never decrypts or searches payloads or receipts. */
final class WorkSearch {
 private WorkSearch() {}
 static Map<String,Object> page(Store.Db db,String actor,Map<String,String> args) throws SQLException {
  Service.requireAccountAdministrator(db,actor);require(Set.of("q","kind","state","limit","offset").containsAll(args.keySet()),"Use q, kind, state, limit and offset");
  String q=args.getOrDefault("q","").strip(),kind=args.getOrDefault("kind","all"),state=args.getOrDefault("state","all");
  require(q.length()<=200 && q.indexOf('\0')<0,"Search must be at most 200 characters without NUL");
  require(Set.of("all","notification","connector.read","migration.step").contains(kind),"Unsupported work kind");require(Set.of("all","queued","processing","failed","succeeded","cancelled").contains(state),"Unsupported work state");
  int limit=Service.number(args.getOrDefault("limit","100"),1,100),offset=Service.number(args.getOrDefault("offset","0"),0,1000000);
  String literal="%"+q.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
  String selection=" FROM os_work WHERE (?='all' OR kind=?) AND (?='all' OR state=?) AND (LOWER(job_id) LIKE ? ESCAPE '!' OR LOWER(dedupe) LIKE ? ESCAPE '!')";
  long total=db.count("SELECT COUNT(*)"+selection,kind,kind,state,state,literal,literal);
  var rows=db.all("SELECT "+WorkJobs.COLUMNS+selection+" ORDER BY created_at DESC,job_id LIMIT ? OFFSET ?",kind,kind,state,state,literal,literal,limit,offset);
  return map("result",rows,"query",q,"kind",kind,"state",state,"limit",limit,"offset",offset,"total",total,"previous_offset",Math.max(0,offset-limit),"next_offset",offset+rows.size()<total?offset+limit:-1);
 }
}
