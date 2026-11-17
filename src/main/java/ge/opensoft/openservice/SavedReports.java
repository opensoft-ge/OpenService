package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/**
 * Share definitions, never cached author-visible results. Every execution uses the current viewer.
 */
final class SavedReports {
    private SavedReports() {}

    static Map<String, Object> save(Store.Db db, String actor, Object input) throws SQLException {
        return save(db, actor, "", input);
    }

    static Map<String, Object> save(Store.Db db, String actor, String id, Object input)
            throws SQLException {
        var body = object(input);
        require(
                body.keySet().equals(Set.of("name", "table", "query", "shared")),
                "Provide name, table, query and shared");
        require(body.get("shared") instanceof Boolean, "Shared must be boolean");
        String name = WorkJobs.text(body.get("name"), 160),
                table = WorkJobs.text(body.get("table"), 80);
        var definition = object(body.get("query"));
        var args = new LinkedHashMap<String, String>();
        for (var entry : definition.entrySet()) {
            require(entry.getValue() instanceof String, "Report parameters must be strings");
            args.put(entry.getKey(), (String) entry.getValue());
        }
        Analytics.report(db, table, args);
        if (id.isEmpty()) {
            require(
                    db.count("SELECT COUNT(*) FROM os_saved_report WHERE owner_id=?", actor) < 100,
                    "Saved report limit reached");
            id = id();
            db.update(
                    "INSERT INTO os_saved_report(report_id,owner_id,name,table_name,definition,shared)"
                            + " VALUES(?,?,?,?,?,?)",
                    id, actor, name, table, JSON.writeValueAsString(definition),
                    body.get("shared").equals(true) ? 1 : 0);
        } else if (db.update(
                        "UPDATE os_saved_report SET name=?,table_name=?,definition=?,shared=?"
                                + " WHERE report_id=? AND owner_id=?",
                        name, table, JSON.writeValueAsString(definition),
                        body.get("shared").equals(true) ? 1 : 0, id, actor)
                != 1) {
            throw new Failure(404, "Owned report not found");
        }
        return map("report_id", id, "name", name);
    }

    static List<Map<String, Object>> list(Store.Db db, String actor) throws SQLException {
        return db.all(
                "SELECT report_id,owner_id,name,table_name,shared FROM os_saved_report WHERE"
                    + " owner_id=? OR shared=1 ORDER BY name,report_id LIMIT 200",
                actor);
    }

    static Map<String, Object> find(Store.Db db, String actor, String id) throws SQLException {
        var report =
                db.one(
                        "SELECT * FROM os_saved_report WHERE report_id=? AND (owner_id=? OR"
                            + " shared=1)",
                        id,
                        actor);
        if (report == null) throw new Failure(404, "Report not found");
        return report;
    }

    static Map<String, Object> run(Store.Db db, String actor, String id) throws SQLException {
        long started=System.nanoTime();
        if(db.postgres) db.all("SELECT pg_advisory_xact_lock(18439,4)");
        var report=find(db,actor,id);
        var args = new LinkedHashMap<String, String>();
        object(report.get("definition")).forEach((key, value) -> args.put(key, value.toString()));
        var result=Analytics.report(db, report.get("table_name").toString(), args);
        ReportRuns.record(db,actor,report,Math.max(0,(System.nanoTime()-started)/1_000_000));
        return result;
    }

    static Map<String,Object> copy(Store.Db db,String actor,String id,Object input) throws SQLException {
        if(!Set.of("admin","agent").contains(db.viewerRole))throw new Failure(403,"Staff reporting permission required");
        var body=object(input);require(body.keySet().equals(Set.of("name")),"Provide the new report name");
        var source=find(db,actor,id);
        var result=save(db,actor,map("name",body.get("name"),"table",source.get("table_name"),"query",source.get("definition"),"shared",false));
        SecurityEvents.record(db,actor,actor,"report.copied",map("source_report_id",id,"report_id",result.get("report_id")));
        return result;
    }

    static void remove(Store.Db db, String actor, String id) throws SQLException {
        if (db.update("DELETE FROM os_saved_report WHERE report_id=? AND owner_id=?", id, actor)
                != 1) throw new Failure(404, "Owned report not found");
        db.update("DELETE FROM os_report_run WHERE report_id=?",id);
        db.update("DELETE FROM os_report_measurement WHERE report_id=?",id);
    }
}
