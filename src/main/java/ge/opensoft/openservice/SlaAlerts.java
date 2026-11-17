package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import java.util.*;
import java.sql.SQLException;
/** Breach recipients are current responsible staff, never a stale assignment snapshot. */
final class SlaAlerts {
    private SlaAlerts() {}
    static void username(Store.Db db,Set<String> recipients,Object name)throws SQLException{var user=db.one("SELECT user_id FROM os_user WHERE name=?",Objects.toString(name,""));if(user!=null)recipients.add(user.get("user_id").toString());}
    static boolean visible(Store.Db db,Map<String,Object> user,Map<String,Object> item)throws SQLException{
        String previousId=db.viewerId,previousRole=db.viewerRole;
        try{db.viewerId=user.get("user_id").toString();db.viewerRole=user.get("role").toString();if(!RecordPolicy.allows(Service.schema(db,item.get("table").toString()),"read",db.viewerRole))return false;var parameters=new ArrayList<Object>();parameters.add(item.get("sys_id"));String access=RecordAccess.filter(db,"os_record.sys_id",parameters);return db.count("SELECT COUNT(*) FROM os_record WHERE sys_id=? AND "+access,parameters.toArray())==1;}
        finally{db.viewerId=previousId;db.viewerRole=previousRole;}
    }
    static void send(Store.Db db,Map<String,Object> clock,long now)throws SQLException{
        var item=db.one("SELECT * FROM os_record WHERE sys_id=? AND \"table\"=?",clock.get("record_id"),clock.get("table_name"));if(item==null)return;var data=object(item.get("data"));var recipients=new LinkedHashSet<String>();recipients.add(clock.get("owner_id").toString());username(db,recipients,data.get("assigned_to"));
        var group=db.one("SELECT data FROM os_record WHERE sys_id=? AND \"table\"='sys_user_group'",Objects.toString(data.get("assignment_group"),""));if(group!=null)username(db,recipients,object(group.get("data")).get("manager"));
        for(String recipient:recipients){var user=db.one("SELECT * FROM os_user WHERE user_id=? AND active=1 AND role IN ('admin','agent')",recipient);if(user==null || PasswordReset.required(user) || !visible(db,user,item))continue;String notice=recipient.equals(clock.get("owner_id"))?clock.get("sla_id").toString():org.apache.commons.codec.digest.DigestUtils.sha256Hex("sla-breach:"+clock.get("sla_id")+":"+recipient).substring(0,32);var result=Notifications.deliver(db,notice,map("username",user.get("name"),"message","Service clock "+clock.get("sla_id")+" has breached its business-time budget. Review the related service clocks."),now);if(Boolean.TRUE.equals(result.get("delivered")))NoticeTargets.attach(db,notice,"clock",clock.get("sla_id").toString());}
    }
}
