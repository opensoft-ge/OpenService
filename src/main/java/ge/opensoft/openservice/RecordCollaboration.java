package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;

/** Record activity subscriptions and explicit, access-checked journal mentions. */
final class RecordCollaboration {
 private RecordCollaboration() {}
 static boolean allowed(Store.Db db,String table,String kind)throws SQLException {
  var schema=Service.schema(db,table);
  return kind.equals("changes") || (kind.equals("comments")?FieldAccess.allowed(db,schema,"comments"):Set.of("admin","agent").contains(db.viewerRole) && FieldAccess.allowed(db,schema,"work_notes"));
 }
 static boolean recipient(Store.Db db,Map<String,Object> user,String table,String record,String kind)throws SQLException {
  if(user==null || !user.get("active").equals(1))return false;
  String actor=db.viewerId,role=db.viewerRole;boolean portal=db.employeePortal;try{db.employeePortal=false;db.viewerId=user.get("user_id").toString();db.viewerRole=user.get("role").toString();Service.readRecord(db,table,record);return allowed(db,table,kind);}catch(Failure denied){return false;}finally{db.viewerId=actor;db.viewerRole=role;db.employeePortal=portal;}
 }
 static void deliver(Store.Db db,String table,String record,String kind,long sequence,Map<String,Object> user,boolean mention)throws SQLException {
  if(!recipient(db,user,table,record,kind))return;String notice=DigestUtils.sha256Hex("record-activity:"+sequence+":"+user.get("user_id")).substring(0,32);String message=mention?"You were mentioned in permitted record activity.":"New activity is available on a followed record.";
  var delivered=Notifications.deliver(db,notice,map("username",user.get("name"),"message",message),System.currentTimeMillis());if(Boolean.TRUE.equals(delivered.get("delivered"))){if(mention)db.update("UPDATE os_notice SET message=? WHERE notice_id=?",message,notice);NoticeTargets.attach(db,notice,"record_"+kind,record);}
 }
 static void changed(Store.Db db,Map<String,Object> item,String actor,String action,long sequence)throws SQLException {
  String kind=action.equals("comment")?"comments":action.equals("note")?"notes":"changes";String record=item.get("sys_id").toString(),table=item.get("table").toString();
  for(var row:db.all("SELECT u.user_id,u.name,u.role,u.active,f.definition FROM os_record_follow f JOIN os_user u ON u.user_id=f.user_id WHERE f.record_id=?",record))if(!row.get("name").equals(actor) && ((List<?>)object(row.get("definition")).get("categories")).contains(kind))deliver(db,table,record,kind,sequence,row,false);
 }
 static Map<String,Object> page(Store.Db db,String table,String id)throws SQLException {
  var item=Service.readRecord(db,table,id);var schema=Service.schema(db,table);boolean comments=allowed(db,table,"comments"),notes=allowed(db,table,"notes");var subscription=db.one("SELECT revision,definition FROM os_record_follow WHERE record_id=? AND user_id=?",id,db.viewerId);var kinds=new ArrayList<String>();if(comments)kinds.add("comment");if(notes)kinds.add("note");var args=new ArrayList<Object>();args.add(id);args.addAll(kinds);
  var entries=db.all("SELECT seq,actor,action,created,detail FROM os_event WHERE record_id=? AND action IN (''"+String.join("",Collections.nCopies(kinds.size(),",?"))+") ORDER BY seq DESC LIMIT 100",args.toArray());
  return map("record",FieldAccess.serialize(db,item),"subscription",subscription==null?map("revision",0,"definition",map("categories",List.of())):subscription,"entries",entries,"can_comment",comments,"can_note",notes && RecordPolicy.allows(schema,"update",db.viewerRole),"receipt",null);
 }
 static Map<String,Object> subscribe(Store.Db db,String table,String id,Object input)throws SQLException {
  Service.readRecord(db,table,id);var body=object(input);require(body.keySet().equals(Set.of("operation","revision","categories")),"Provide subscription revision and categories");var previous=db.one("SELECT revision FROM os_record_follow WHERE record_id=? AND user_id=?",id,db.viewerId);ProblemInvestigations.revision(body.get("revision"),previous==null?0:previous.get("revision"));require(body.get("categories") instanceof List<?> values && !values.isEmpty() && values.size()<=3,"Choose one to three activity categories");var categories=new LinkedHashSet<String>();for(Object value:(List<?>)body.get("categories")){require(value instanceof String kind && Set.of("changes","comments","notes").contains(kind) && categories.add(kind),"Choose unique supported activity categories");if(!allowed(db,table,value.toString()))throw new Failure(403,"This activity category is unavailable");}
  if(previous==null)require(db.count("SELECT COUNT(*) FROM os_record_follow WHERE user_id=?",db.viewerId)<100 && db.count("SELECT COUNT(*) FROM os_record_follow WHERE record_id=?",id)<100 && db.count("SELECT COUNT(*) FROM os_record_follow")<50000,"Record subscription capacity reached");
  db.update("INSERT INTO os_record_follow(record_id,user_id,revision,definition) VALUES(?,?,1,?) ON CONFLICT(record_id,user_id) DO UPDATE SET revision=os_record_follow.revision+1,definition=excluded.definition",id,db.viewerId,JSON.writeValueAsString(map("categories",categories)));return page(db,table,id);
 }
 static Map<String,Object> post(Store.Db db,String table,String id,Object input)throws SQLException {
  var item=Service.readRecord(db,table,id);var body=object(input);require(body.keySet().equals(Set.of("operation","kind","body","mentions","key")),"Provide journal kind, body, explicit mentions and request key");String kind=WorkJobs.text(body.get("kind"),10);require(Set.of("comment","note").contains(kind),"Choose comment or note");String category=kind.equals("note")?"notes":"comments";if(!allowed(db,table,category))throw new Failure(403,"This journal is unavailable");if(kind.equals("note"))RecordPolicy.require(Service.schema(db,table),"update",db.viewerRole);String text=WorkJobs.text(body.get("body"),4000),key=WorkJobs.text(body.get("key"),32);require(key.matches("[0-9a-f]{32}"),"Use a 32-character request key");require(body.get("mentions") instanceof List<?> values && values.size()<=10,"Mention at most ten users");var usernames=new LinkedHashSet<String>();for(Object value:(List<?>)body.get("mentions")){String username=WorkJobs.text(value,80);require(usernames.add(username),"Mention each username once");}
  String fingerprint=DigestUtils.sha256Hex(JSON.writeValueAsString(map("kind",kind,"body",text,"mentions",usernames)));var previous=db.one("SELECT event_sequence,fingerprint FROM os_collaboration_post WHERE record_id=? AND actor_id=? AND request_key=?",id,db.viewerId,key);if(previous!=null){if(!previous.get("fingerprint").equals(fingerprint))throw new Failure(409,"Request key identifies another journal post");var result=page(db,table,id);result.put("receipt",map("event_sequence",previous.get("event_sequence")));return result;}
  var recipients=new ArrayList<Map<String,Object>>();for(String username:usernames){var user=db.one("SELECT user_id,name,role,active FROM os_user WHERE name=?",username);require(recipient(db,user,table,id,category),"One or more mentioned users is unavailable");recipients.add(user);}
  require(db.count("SELECT COUNT(*) FROM os_collaboration_post WHERE record_id=?",id)<1000 && db.count("SELECT COUNT(*) FROM os_collaboration_post")<50000,"Collaboration post capacity reached");String actor=Service.account(db,db.viewerId).get("name").toString();long sequence=Service.audit(db,item,actor,kind,map("body",text));for(var user:recipients)deliver(db,table,id,category,sequence,user,true);db.update("INSERT INTO os_collaboration_post(record_id,actor_id,request_key,event_sequence,fingerprint,created) VALUES(?,?,?,?,?,?)",id,db.viewerId,key,sequence,fingerprint,now());var result=page(db,table,id);result.put("receipt",map("event_sequence",sequence));return result;
 }
 static Map<String,Object> command(Store.Db db,String table,String id,Object input)throws SQLException {
  var body=object(input);String operation=WorkJobs.text(body.get("operation"),20);require(Set.of("subscribe","post").contains(operation),"Choose subscribe or post");return operation.equals("subscribe")?subscribe(db,table,id,body):post(db,table,id,body);
 }
 static Map<String,Object> directory(Store.Db db)throws SQLException {
  var results=new ArrayList<Map<String,Object>>();var user=db.one("SELECT user_id,name,role,active FROM os_user WHERE user_id=?",db.viewerId);
  for(var row:db.all("SELECT f.record_id,f.revision,f.definition,r.\"table\" AS table_name FROM os_record_follow f JOIN os_record r ON r.sys_id=f.record_id WHERE f.user_id=? ORDER BY f.record_id",db.viewerId)){boolean accessible=recipient(db,user,row.get("table_name").toString(),row.get("record_id").toString(),"changes");row.put("available",accessible);row.put("label",accessible?FieldAccess.serialize(db,Service.readRecord(db,row.get("table_name").toString(),row.get("record_id").toString())).getOrDefault("short_description","Record activity"):"Unavailable record");results.add(row);}return map("subscriptions",results);
 }
 static void remove(Store.Db db,Object input)throws SQLException {
  var body=object(input);require(body.keySet().equals(Set.of("record_id")),"Provide your subscribed record ID");String id=WorkJobs.text(body.get("record_id"),32);require(id.matches("[0-9a-f]{32}"),"Use a valid record ID");db.update("DELETE FROM os_record_follow WHERE record_id=? AND user_id=?",id,db.viewerId);
 }
 static String open(Store.Db db,String record,String kind)throws SQLException {
  var row=db.one("SELECT \"table\" AS table_name FROM os_record WHERE sys_id=?",record);if(row==null)throw new Failure(404,"Record no longer exists");String table=row.get("table_name").toString();Service.readRecord(db,table,record);if(!allowed(db,table,kind))throw new Failure(404,"Record activity is unavailable");return "/record/"+table+"/"+record+"/collaboration";
 }
}
