package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;

/** Administrator-owned technician calendars, checked again when work is accepted. */
final class FieldAvailability {
    private FieldAvailability() {}

    static Map<String,Object> page(Store.Db db) throws SQLException {
        Service.requireAccountAdministrator(db,db.viewerId);
        return map("entries",db.all("SELECT a.*,u.name FROM os_field_availability a JOIN os_user u ON u.user_id=a.user_id ORDER BY u.name"),
                "technicians",db.all("SELECT user_id,name FROM os_user WHERE active=1 AND role='agent' AND password_reset_required=0 ORDER BY name LIMIT 200"),
                "calendars",db.all("SELECT calendar_id FROM os_calendar ORDER BY calendar_id"));
    }

    static Map<String,Object> save(Store.Db db,Object input) throws SQLException {
        Service.requireAccountAdministrator(db,db.viewerId);
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        var body=object(input);
        require(body.keySet().equals(Set.of("username","calendar_id","active","revision")),"Provide technician username, calendar, active and revision");
        String username=WorkJobs.text(body.get("username"),80),calendar=WorkJobs.text(body.get("calendar_id"),80);
        var user=db.one("SELECT user_id FROM os_user WHERE name=? AND active=1 AND role='agent' AND password_reset_required=0",username);
        require(user!=null,"Choose an active agent with a usable account");
        require(body.get("active") instanceof Boolean,"Choose active or inactive");
        require(db.one("SELECT calendar_id FROM os_calendar WHERE calendar_id=?",calendar)!=null,"Choose an existing calendar");
        String id=user.get("user_id").toString();var current=db.one("SELECT revision FROM os_field_availability WHERE user_id=?",id);
        ProblemInvestigations.revision(body.get("revision"),current==null?0:current.get("revision"));
        require(current!=null || db.count("SELECT COUNT(*) FROM os_field_availability")<200,"At most 200 technicians can have field availability");
        db.update("INSERT INTO os_field_availability(user_id,calendar_id,active,revision) VALUES(?,?,?,1) ON CONFLICT(user_id) DO UPDATE SET calendar_id=excluded.calendar_id,active=excluded.active,revision=os_field_availability.revision+1",id,calendar,(Boolean)body.get("active")?1:0);
        SecurityEvents.record(db,db.viewerId,id,"field.availability_saved",map("calendar_id",calendar,"active",body.get("active")));
        return page(db);
    }

    static void eligible(Store.Db db,String table,String parent,Map<String,Object> task,String technician,long start,long end) throws SQLException {
        CustomerRepairs.availability(db,task.get("task_id").toString(),technician,start,end);
        FieldPlanning.eligible(db,task.get("task_id").toString(),technician);
        var row=db.one("SELECT c.definition AS calendar_definition FROM os_field_availability a JOIN os_user u ON u.user_id=a.user_id JOIN os_calendar c ON c.calendar_id=a.calendar_id JOIN os_group_member m ON m.user_id=a.user_id WHERE a.user_id=? AND a.active=1 AND u.active=1 AND u.role='agent' AND u.password_reset_required=0 AND m.group_id=?",technician,task.get("group_id"));
        if(row==null)throw new Failure(409,"Technician is not currently available for this team");
        var calendar=object(JSON.readValue(row.get("calendar_definition").toString(),Map.class));
        if(BusinessCalendar.elapsed(calendar,start,end)!=end-start)throw new Failure(409,"The whole visit must fit the technician calendar");
        String viewer=db.viewerId,role=db.viewerRole;
        try{db.viewerId=technician;db.viewerRole="agent";WorkTasks.record(db,table,parent);}
        finally{db.viewerId=viewer;db.viewerRole=role;}
        if(db.count("SELECT COUNT(*) FROM os_field_dispatch WHERE technician_id=? AND task_id<>? AND status IN ('assigned','accepted') AND starts<? AND ends>?",technician,task.get("task_id"),end,start)>0)throw new Failure(409,"Technician has another reserved visit in this interval");
    }
}
