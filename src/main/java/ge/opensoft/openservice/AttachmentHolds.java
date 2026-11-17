package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;
/** A manual download hold, not an antivirus scan or a verdict that content is safe. */
final class AttachmentHolds {
 private AttachmentHolds() {}
 static Map<String,Object> inspect(Store.Db db,String id) throws SQLException {
  Service.requireAccountAdministrator(db,db.viewerId);var file=db.one("SELECT a.*,r.\"table\" AS table_name FROM os_attachment a JOIN os_record r ON r.sys_id=a.record_id WHERE a.sys_id=?",id);if(file==null)throw new Failure(404,"Attachment not found");
  String table=file.get("table_name").toString();RecordPolicy.require(Service.schema(db,table),"read",db.viewerRole);Service.record(db,table,file.get("record_id").toString());byte[] bytes=(byte[])file.get("content");
  var result=map("attachment_id",id,"record_id",file.get("record_id"),"table",table,"name",file.get("name"),"content_type",file.get("content_type"),"bytes",bytes.length,"sha256",DigestUtils.sha256Hex(bytes),"held",((Number)file.get("quarantined")).intValue()==1,"revision",file.get("hold_revision"));
  result.put("review",DigestUtils.sha256Hex(JSON.writeValueAsString(result)));return result;
 }
 static Map<String,Object> change(Store.Db db,String id,Object input) throws SQLException {
  Service.requireAccountAdministrator(db,db.viewerId);if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");var body=object(input);
  require(body.keySet().equals(Set.of("held","review","reason")),"Provide held, review and reason");require(body.get("held") instanceof Boolean,"Held must be boolean");String reason=WorkJobs.text(body.get("reason"),2000),review=WorkJobs.text(body.get("review"),64);var current=inspect(db,id);
  if(!review.equals(current.get("review")))throw new Failure(409,"Attachment or hold changed; review it again");
  require(!body.get("held").equals(current.get("held")),"Choose a different download-access state");
  if(Boolean.FALSE.equals(body.get("held")))AttachmentScanning.requireRelease(db,id);
  db.update("UPDATE os_attachment SET quarantined=?,hold_revision=hold_revision+1 WHERE sys_id=?",Boolean.TRUE.equals(body.get("held"))?1:0,id);
  SecurityEvents.record(db,db.viewerId,db.viewerId,"attachment.access_changed",map("attachment_id",id,"held",body.get("held"),"sha256",current.get("sha256"),"reason",reason));return inspect(db,id);
 }
 static void requireDownload(Store.Db db,String id) throws SQLException {
  if(db.count("SELECT quarantined FROM os_attachment WHERE sys_id=?",id)==1)throw new Failure(423,"Attachment is held for review and cannot be downloaded");
 }
}
