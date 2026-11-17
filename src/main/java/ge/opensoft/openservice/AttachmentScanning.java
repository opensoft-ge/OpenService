package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;
final class AttachmentScanning {
    private AttachmentScanning() {}
    static void enqueue(Store.Db db,String id)throws SQLException{
        if(!db.attachmentScanning && db.count("SELECT COUNT(*) FROM os_attachment_scan WHERE attachment_id=?",id)==0)return;
        var file=db.one("SELECT quarantined,hold_revision FROM os_attachment WHERE sys_id=?",id);db.update("INSERT INTO os_attachment_scan(attachment_id,revision,status,sha256,engine,completed_at,hold_revision,release_on_clean) VALUES(?,1,'pending','','',0,?,?) ON CONFLICT(attachment_id) DO UPDATE SET revision=os_attachment_scan.revision+1,status='pending',sha256='',engine='',completed_at=0,hold_revision=excluded.hold_revision,release_on_clean=excluded.release_on_clean",id,((Number)file.get("hold_revision")).longValue()+1,((Number)file.get("quarantined")).intValue()==0?1:0);
        // A previous manual hold must not be released by an automatic clean result.
        db.update("UPDATE os_attachment SET quarantined=1,hold_revision=hold_revision+1 WHERE sys_id=?",id);
    }
    static Map<String,Object> page(Store.Db db,String id)throws SQLException{
        var result=AttachmentHolds.inspect(db,id);var scan=db.one("SELECT revision,status,sha256,engine,completed_at FROM os_attachment_scan WHERE attachment_id=?",id);result.put("scan",scan==null?map("status","unscanned"):scan);result.put("enabled",db.attachmentScanning);result.put("completed_at_utc",scan==null || ((Number)scan.get("completed_at")).longValue()==0?"Not completed":java.time.Instant.ofEpochMilli(((Number)scan.get("completed_at")).longValue()).toString());result.put("review",DigestUtils.sha256Hex(JSON.writeValueAsString(result)));
        return result;
    }
    static Map<String,Object> rescan(Store.Db db,String id,Object input)throws SQLException{
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");var current=page(db,id);require(db.attachmentScanning,"Attachment scanning is disabled");var body=object(input);require(body.keySet().equals(Set.of("review","reason")),"Provide review and reason");String reason=WorkJobs.text(body.get("reason"),2000);
        if(!current.get("review").equals(body.get("review")))throw new Failure(409,"Attachment or scan changed; review it again");enqueue(db,id);SecurityEvents.record(db,db.viewerId,db.viewerId,"attachment.scan_requested",map("attachment_id",id,"reason",reason,"sha256",current.get("sha256")));
        return page(db,id);
    }
    static void requireRelease(Store.Db db,String id)throws SQLException{
        var row=db.one("SELECT s.status,s.sha256,a.content FROM os_attachment_scan s JOIN os_attachment a ON a.sys_id=s.attachment_id WHERE s.attachment_id=?",id);
        if(row!=null && !(row.get("status").equals("clean") && row.get("sha256").equals(DigestUtils.sha256Hex((byte[])row.get("content")))))throw new Failure(423,"A clean scan of the current content is required before release");
    }
    static boolean runOne(Store store) throws Exception {
        Map<String,Object> job=null;
        try(var db=store.open()) {
            if(db.attachmentScanning) {
                db.update("DELETE FROM os_attachment_scan WHERE attachment_id NOT IN (SELECT sys_id FROM os_attachment)");
                job=db.one("SELECT s.*,a.content FROM os_attachment_scan s JOIN os_attachment a ON a.sys_id=s.attachment_id WHERE s.status='pending' ORDER BY s.attachment_id LIMIT 1");
                db.connection.commit();
            }
        }
        if(job==null)return false;
        var verdict=ClamScanner.scan(store.environment.getProperty("CLAMAV_HOST","127.0.0.1"),store.environment.getProperty("CLAMAV_PORT",Integer.class,3310),(byte[])job.get("content"));
        return finish(store,job,verdict);
    }
    static boolean finish(Store store,Map<String,Object> job,Map<String,Object> verdict) throws Exception {
        boolean completed=false;
        try(var db=store.open()) {
            if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
            var current=db.one("SELECT s.*,a.content,a.hold_revision AS current_hold FROM os_attachment_scan s JOIN os_attachment a ON a.sys_id=s.attachment_id WHERE s.attachment_id=?",job.get("attachment_id"));
            String digest=DigestUtils.sha256Hex((byte[])job.get("content"));
            if(db.attachmentScanning && current!=null && current.get("status").equals("pending") && ((Number)current.get("revision")).longValue()==((Number)job.get("revision")).longValue() && digest.equals(DigestUtils.sha256Hex((byte[])current.get("content")))) {
                db.update("UPDATE os_attachment_scan SET status=?,sha256=?,engine=?,completed_at=? WHERE attachment_id=?",verdict.get("status"),digest,verdict.get("engine"),System.currentTimeMillis(),job.get("attachment_id"));
                if(verdict.get("status").equals("clean") && ((Number)current.get("release_on_clean")).intValue()==1 && ((Number)current.get("current_hold")).longValue()==((Number)job.get("hold_revision")).longValue()) {
                    db.update("UPDATE os_attachment SET quarantined=0,hold_revision=hold_revision+1 WHERE sys_id=?",job.get("attachment_id"));
                }
                db.connection.commit();
                completed=true;
            }
        }
        return completed;
    }
}
