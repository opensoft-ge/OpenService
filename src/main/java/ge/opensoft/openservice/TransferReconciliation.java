package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
import java.util.*;

/** Bounded diagnostic output for local staging/link integrity, never a claim of source completeness. */
final class TransferReconciliation {
    private TransferReconciliation() {}
    static Map<String,Object> inspect(Store.Db db,String actor,String id) throws SQLException {
        Service.requireAccountAdministrator(db,actor);
        var job=TransferJobs.job(db,id);
        var missing=TransferApply.missing(db,job);
        var links=db.all("SELECT source_table,source_id,detail FROM os_transfer_link WHERE source_url=? ORDER BY source_table,source_id",job.get("source_url"));
        var conflicts=new ArrayList<Map<String,Object>>();
        long changed=0;
        for(var link:links) {
            var detail=object(link.get("detail"));
            if(!Objects.equals(detail.get("destination"),TransferApply.snapshot(db,detail.get("kind").toString(),detail.get("target").toString()))) {
                changed++;
                if(conflicts.size()<100) conflicts.add(map("source_table",link.get("source_table"),"source_id",link.get("source_id")));
            }
        }
        String state=object(job.get("detail")).get("status").toString();
        return map("job_id",id,"state",state,"can_resolve_records",state.equals("review") && object(job.get("detail")).containsKey("previous_job"),"compared_links",links.size(),
                "missing_source_items",missing.size(),"missing",missing.stream().limit(100).toList(),
                "changed_destinations",changed,"conflicts",conflicts,
                "truncated",missing.size()>100 || changed>100,
                "local_reconciliation_passed",state.equals("applied") && missing.isEmpty() && changed==0,
                "source_completeness_verified",false,
                "limitations","Checks retained local links and snapshots only. Review source permissions/deletions, unmapped data and live cutover confirmations separately.");
    }
}
