package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
/** Internal targets are resolved against the current authenticated owner and resource policy. */
final class NoticeTargets {
    private NoticeTargets() {}
    static void attach(Store.Db db,String notice,String kind,String target)throws SQLException{
        db.update("INSERT INTO os_notice_target(notice_id,kind,target_id) VALUES(?,?,?) ON CONFLICT(notice_id) DO NOTHING",notice,kind,target);
    }
    static void cleanup(Store.Db db)throws SQLException{db.update("DELETE FROM os_notice_target WHERE notice_id NOT IN (SELECT notice_id FROM os_notice)");}
    static String open(Store.Db db,String id)throws SQLException{
        var target=db.one("SELECT t.kind,t.target_id FROM os_notice_target t JOIN os_notice n ON n.notice_id=t.notice_id WHERE n.notice_id=? AND n.user_id=?",id,db.viewerId);if(target==null)throw new Failure(404,"Notification context not found");
        if(!target.get("kind").equals("clock"))throw new Failure(404,"Notification context not supported");var clock=db.one("SELECT table_name,record_id FROM os_sla WHERE sla_id=?",target.get("target_id"));if(clock==null)throw new Failure(404,"Service clock no longer exists");
        RecordClocks.page(db,clock.get("table_name").toString(),clock.get("record_id").toString());return "/record/"+clock.get("table_name")+"/"+clock.get("record_id")+"/clocks";
    }
}
