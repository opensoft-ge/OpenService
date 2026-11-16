package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
import java.util.*;

/** Aggregate operator signals; no recipient, payload, credential or result content is exposed. */
final class QueueHealth {
    private QueueHealth() {}
    static Map<String,Object> inspect(Store store,Store.Db db,long now) throws SQLException {
        long due=db.count("SELECT COUNT(*) FROM os_work WHERE state='queued' AND available_at<=?",now);
        long scheduled=db.count("SELECT COUNT(*) FROM os_work WHERE state='queued' AND available_at>?",now);
        long processing=db.count("SELECT COUNT(*) FROM os_work WHERE state='processing'");
        long failed=db.count("SELECT COUNT(*) FROM os_work WHERE state='failed'");
        long expired=db.count("SELECT COUNT(*) FROM os_work WHERE state='processing' AND available_at<=?",now);
        Object oldest=db.one("SELECT MIN(available_at) AS oldest FROM os_work WHERE state='queued' AND available_at<=?",now).get("oldest");
        long age=oldest==null?0:Math.max(0,now-((Number)oldest).longValue());
        boolean enabled=store.environment.getProperty("WORK_ENABLED",Boolean.class,false);
        long retained=db.count("SELECT COUNT(*) FROM os_work");
        var alerts=new ArrayList<Map<String,Object>>();
        alert(alerts,retained>=5000,"retention_full","Clean up completed or cancelled work older than 30 days to make room for new jobs.");
        alert(alerts,!enabled && due+scheduled+processing>0,"worker_disabled","Enable the business worker to process pending work.");
        alert(alerts,failed>0,"failed_work","Investigate failed jobs, then retry or cancel them.");
        alert(alerts,expired>0,"expired_leases","Check worker recovery of expired processing leases.");
        alert(alerts,age>=300000,"overdue_work","Work has waited at least five minutes past its due time. Check workers and dependencies.");
        return map("worker_enabled",enabled,"due",due,"scheduled",scheduled,"processing",processing,
                "failed",failed,"expired_leases",expired,"oldest_due_ms",age,
                "retained",retained,"capacity",5000,"remaining_capacity",Math.max(0,5000-retained),
                "counts",db.all("SELECT kind,state,COUNT(*) AS count FROM os_work GROUP BY kind,state ORDER BY kind,state"),
                "alerts",alerts);
    }
    static void alert(List<Map<String,Object>> alerts,boolean condition,String id,String action) {
        if(condition) alerts.add(map("id",id,"action",action));
    }
}
