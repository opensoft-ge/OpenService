package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Each clock snapshots its calendar, so editing a schedule cannot rewrite elapsed service time. */
final class ServiceClocks {
    private ServiceClocks() {}

    static void calendar(Store.Db db, String actor, Object input) throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        var body = object(input);
        require(body.keySet().equals(Set.of("name", "calendar")), "Provide name and calendar");
        String name = WorkJobs.text(body.get("name"), 80);
        var definition = BusinessCalendar.validate(body.get("calendar"));
        db.update(
                "INSERT INTO os_calendar(calendar_id,definition) VALUES(?,?) ON"
                    + " CONFLICT(calendar_id) DO UPDATE SET definition=excluded.definition",
                name,
                JSON.writeValueAsString(definition));
        SecurityEvents.record(db, actor, actor, "sla.calendar_saved", map("calendar", name));
    }

    static Map<String, Object> start(Store.Db db, String actor, Object input, long now)
            throws SQLException {
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        Service.requireAccountAdministrator(db, actor);
        var body = object(input);
        require(body.keySet().containsAll(Set.of("table","record_id","calendar","minutes")) && Set.of("table","record_id","calendar","minutes","purpose").containsAll(body.keySet()),"Provide table, record_id, calendar, minutes and optional purpose");
        String purpose=WorkJobs.text(body.getOrDefault("purpose","general"),20);
        require(Set.of("general","response","resolution").contains(purpose),"Choose general, response or resolution");
        String table = WorkJobs.text(body.get("table"), 80),
                record = WorkJobs.text(body.get("record_id"), 32),
                calendar = WorkJobs.text(body.get("calendar"), 80);
        require(
                body.get("minutes") instanceof Integer n && n > 0 && n <= 525600,
                "Budget must be 1–525600 business minutes");
        Service.record(db, table, record);
        var schedule = db.one("SELECT definition FROM os_calendar WHERE calendar_id=?", calendar);
        if (schedule == null) throw new Failure(404, "Business calendar not found");
        if(!purpose.equals("general") && db.count("SELECT COUNT(*) FROM os_sla WHERE table_name=? AND record_id=? AND purpose=? AND state IN ('running','paused')",table,record,purpose)>0)throw new Failure(409,"An active clock already tracks this purpose; complete or cancel it before starting another");
        String id = id();
        db.update(
                "INSERT INTO"
                    + " os_sla(sla_id,table_name,record_id,definition,remaining_ms,last_tick,state,owner_id,purpose)"
                    + " VALUES(?,?,?,?,?,?,'running',?,?)",
                id,
                table,
                record,
                JSON.writeValueAsString(schedule.get("definition")),
                ((Number) body.get("minutes")).longValue() * 60000,
                now,
                actor,purpose);
        SecurityEvents.record(
                db, actor, actor, "sla.started", map("sla_id", id, "record_id", record,"purpose",purpose));
        return db.one("SELECT * FROM os_sla WHERE sla_id=?", id);
    }

    static void advance(Store.Db db, Map<String, Object> row, long now) throws SQLException {
        long previous = ((Number) row.get("last_tick")).longValue();
        if (!row.get("state").equals("running") || now <= previous) return;
        long remaining =
                Math.max(
                        0,
                        ((Number) row.get("remaining_ms")).longValue()
                                - BusinessCalendar.elapsed(
                                        object(row.get("definition")), previous, now));
        boolean breach = remaining == 0;
        db.update(
                "UPDATE os_sla SET remaining_ms=?,last_tick=?,state=?,breached_at=? WHERE sla_id=?",
                remaining,
                now,
                breach ? "breached" : "running",
                breach ? now : 0,
                row.get("sla_id"));
        if (breach) SlaAlerts.send(db,row,now);
    }

    static Map<String,Object> forRecord(Store.Db db,String id,Object input) throws SQLException {
        var args=object(input);
        require(args.keySet().equals(Set.of("table")),"Provide only the record table");
        String table=WorkJobs.text(args.get("table"),80);
        Service.record(db,table,id);
        var rows=db.all("SELECT sla_id,purpose,state,remaining_ms,last_tick,breached_at FROM os_sla WHERE table_name=? AND record_id=? ORDER BY last_tick DESC,sla_id LIMIT 101",table,id);
        boolean more=rows.size()>100;
        if(more) rows.removeLast();
        return map("table",table,"record_id",id,"clocks",rows,"truncated",more);
    }

    static void control(Store.Db db, String actor, String id, String operation, long now)
            throws SQLException {
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        Service.requireAccountAdministrator(db, actor);
        require(
                Set.of("pause", "resume", "complete", "cancel").contains(operation),
                "Choose pause, resume, complete or cancel");
        var row = db.one("SELECT * FROM os_sla WHERE sla_id=?", id);
        if (row == null) throw new Failure(404, "Service clock not found");
        require(
                Set.of("running", "paused").contains(row.get("state")),
                "This clock is already terminal");
        advance(db, row, now);
        if (db.one("SELECT state FROM os_sla WHERE sla_id=?", id).get("state").equals("breached"))
            return;
        String state =
                switch (operation) {
                    case "pause" -> "paused";
                    case "resume" -> "running";
                    case "complete" -> "completed";
                    default -> "cancelled";
                };
        db.update(
                "UPDATE os_sla SET state=?,last_tick=? WHERE sla_id=?",
                state,
                Math.max(now, ((Number) row.get("last_tick")).longValue()),
                id);
        SecurityEvents.record(db, actor, actor, "sla." + operation, map("sla_id", id));
    }

    static void tick(Store.Db db, long now) throws SQLException {
        if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439,4)");
        for (var row :
                db.all(
                        "SELECT * FROM os_sla WHERE state='running' ORDER BY last_tick,sla_id LIMIT"
                            + " 100"))
            advance(db, row, now);
    }
}
