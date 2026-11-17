package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;
/** Failure events and their notification targets commit with the terminal attempt. */
final class WorkFailureAlerts {
    private WorkFailureAlerts() {}
    static void send(Store.Db db,String id,String state,long now)throws SQLException{
        if(!Set.of("failed","cancelled").contains(state))return;
        var row=db.one("SELECT u.*,w.auth_revision AS job_revision FROM os_work w JOIN os_user u ON u.user_id=w.owner_id WHERE w.job_id=? AND u.active=1 AND u.role='admin'",id);
        if(row==null || PasswordReset.required(row) || ((Number)row.get("auth_revision")).longValue()!=((Number)row.get("job_revision")).longValue())return;
        long ordinal=db.count("SELECT COALESCE(MAX(ordinal),0) FROM os_work_attempt WHERE job_id=?",id);if(ordinal==0)return;
        String notice=DigestUtils.sha256Hex("work-failure:"+id+":"+ordinal).substring(0,32);var result=Notifications.deliver(db,notice,map("username",row.get("name"),"message","Background job "+id+" needs review after processing stopped ("+state+"). Review its current state and available recovery actions."),now);
        if(Boolean.TRUE.equals(result.get("delivered")))NoticeTargets.attach(db,notice,"work",id);
    }
}
