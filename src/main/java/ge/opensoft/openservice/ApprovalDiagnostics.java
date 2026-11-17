package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
import java.util.*;

/** Explains current review eligibility without changing a proposal or executing its transition. */
final class ApprovalDiagnostics {
    private ApprovalDiagnostics() {}

    static Map<String,Object> inspect(Store.Db db,String actor,String id) throws SQLException {
        Service.requireAccountAdministrator(db,actor);
        var row=db.one("SELECT * FROM os_approval WHERE approval_id=?",id);
        if(row==null) throw new Failure(404,"Approval not found");
        var stored=db.one("SELECT \"table\" FROM os_record WHERE sys_id=?",row.get("record_id"));
        if(stored==null) throw new Failure(404,"Record not found");
        String table=stored.get("table").toString();
        var item=Service.record(db,table,row.get("record_id").toString());
        var definition=Service.schema(db,table);
        RecordPolicy.require(definition,"read",db.viewerRole);
        String reason=reason(db,row,item,definition);
        return map("approval_id",id,"record_id",item.get("sys_id"),"table_name",table,"status",row.get("status"),"reason",reason,
                "qualifies_for_actor",reason.equals("qualifying"),
                "record_revision",item.get("revision"),"schema_version",Approvals.version(definition));
    }

    static String reason(Store.Db db,Map<String,Object> row,Map<String,Object> item,Map<String,Object> definition) throws SQLException {
        if(!Approvals.current(row,item,definition)) return "proposal_changed";
        var user=db.one("SELECT * FROM os_user WHERE user_id=?",row.get("approver_id"));
        if(user==null || ((Number)user.get("active")).intValue()!=1
                || ((Number)user.get("password_reset_required")).intValue()!=0
                || !Set.of("admin","agent").contains(user.get("role"))) return "reviewer_unavailable";
        if(row.get("status").equals("approved")
                && ((Number)row.get("decision_revision")).longValue()!=((Number)user.get("auth_revision")).longValue())
            return "reviewer_credentials_changed";
        String originalId=db.viewerId,originalRole=db.viewerRole;
        try {
            db.viewerId=user.get("user_id").toString();db.viewerRole=user.get("role").toString();
            Service.record(db,item.get("table").toString(),item.get("sys_id").toString());
            Approvals.eligible(db,item.get("table").toString(),item,definition,row.get("target_state").toString());
        } catch(Failure denied) { return "reviewer_permission_changed"; }
        finally { db.viewerId=originalId;db.viewerRole=originalRole; }
        if(row.get("status").equals("requested")) return "awaiting_decision";
        if(!row.get("status").equals("approved")) return "review_closed";
        if(row.get("approver_id").equals(originalId)) return "same_as_actor";
        return "qualifying";
    }
}
