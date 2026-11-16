package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
import java.util.*;

/** Per-viewer execution metadata; report results and other users' activity are never shared. */
final class ReportRuns {
    private ReportRuns() {}

    static void record(Store.Db db,String actor,Map<String,Object> report,long duration) throws SQLException {
        String reportId=report.get("report_id").toString();
        String hash=org.apache.commons.codec.digest.DigestUtils.sha256Hex(JSON.writeValueAsString(
                map("table",report.get("table_name"),"query",new TreeMap<>(object(report.get("definition"))))));
        db.update("INSERT INTO os_report_run(run_id,report_id,viewer_id,executed_at,duration_ms,definition_hash) VALUES(?,?,?,?,?,?)",
                id(),reportId,actor,System.currentTimeMillis(),duration,hash);
        db.update("DELETE FROM os_report_run WHERE report_id=? AND viewer_id=? AND run_id NOT IN "
                + "(SELECT run_id FROM os_report_run WHERE report_id=? AND viewer_id=? ORDER BY executed_at DESC,run_id DESC LIMIT 100)",
                reportId,actor,reportId,actor);
    }

    static List<Map<String,Object>> history(Store.Db db,String actor,String id) throws SQLException {
        SavedReports.find(db,actor,id);
        return db.all("SELECT run_id,executed_at,duration_ms,definition_hash FROM os_report_run WHERE report_id=? AND viewer_id=? ORDER BY executed_at DESC,run_id DESC LIMIT 100",id,actor);
    }
}
