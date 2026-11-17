package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;
/** Reviewed event escalation creates one private incident; event and case states remain independent. */
final class EventCases {
 private EventCases() {}
 static Map<String,Object> page(Store.Db db,String key) throws SQLException {
  Service.requireAccountAdministrator(db,db.viewerId);key=WorkJobs.text(key,160);var event=db.one("SELECT * FROM os_operational_event WHERE event_key=?",key);if(event==null)throw new Failure(404,"Operational event not found");
  Service.record(db,"cmdb_ci",event.get("asset_id").toString());var link=db.one("SELECT record_id FROM os_event_case WHERE event_key=?",key);Map<String,Object> record=null;
  if(link!=null)record=FieldAccess.serialize(db,Service.record(db,"incident",link.get("record_id").toString()));
  return map("event",event,"case",record,"review",DigestUtils.sha256Hex(JSON.writeValueAsString(event)));
 }
 static Map<String,Object> create(Store.Db db,Object input) throws SQLException {
  Service.requireAccountAdministrator(db,db.viewerId);if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");var body=object(input);require(body.keySet().equals(Set.of("key","review")),"Provide key and review");String key=WorkJobs.text(body.get("key"),160),review=WorkJobs.text(body.get("review"),64);var current=page(db,key);
  if(current.get("case")!=null)return object(current.get("case"));
  if(!review.equals(current.get("review")))throw new Failure(409,"Event changed; review it before creating a case");var event=object(current.get("event"));if(event.get("state").equals("resolved"))throw new Failure(409,"Reopen the event before creating a case");
  String description="Event key: "+key+"\nConfiguration item: "+event.get("asset_id")+"\nSeverity: "+event.get("severity")+"\nOccurrences: "+event.get("occurrences")+"\n\n"+event.get("summary");
  String actor=Service.account(db,db.viewerId).get("name").toString();var item=Service.create(db,"incident",map("short_description","Operational event investigation","description",description,"priority",Map.of("critical","1","warning","2","info","3").get(event.get("severity"))),actor);
  RecordAccess.change(db,item,db.viewerId,map("visibility","private","owner_id",db.viewerId));db.update("INSERT INTO os_event_case(event_key,record_id,created_at,creator_id) VALUES(?,?,?,?)",key,item.get("sys_id"),System.currentTimeMillis(),db.viewerId);
  SecurityEvents.record(db,db.viewerId,db.viewerId,"event.case_created",map("key",key,"record_id",item.get("sys_id"),"source_review",review));return FieldAccess.serialize(db,item);
 }
}
