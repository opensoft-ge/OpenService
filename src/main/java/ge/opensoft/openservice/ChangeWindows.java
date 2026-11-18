package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;
/** Current CI maintenance and blackout rules, using the shared timezone-aware calendars. */
final class ChangeWindows {
 private ChangeWindows() {}
 static Map<String,Object> page(Store.Db db,String ci)throws SQLException{
  Service.requireAccountAdministrator(db,db.viewerId);return map("ci",FieldAccess.serialize(db,Service.readRecord(db,"cmdb_ci",ci)),"windows",db.all("SELECT * FROM os_change_window WHERE ci_id=? ORDER BY name,window_id",ci),"calendars",db.all("SELECT calendar_id FROM os_calendar ORDER BY calendar_id"));
 }
 static Map<String,Object> save(Store.Db db,String ci,Object input)throws SQLException{
  Service.requireAccountAdministrator(db,db.viewerId);if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");Service.readRecord(db,"cmdb_ci",ci);var body=object(input);String operation=WorkJobs.text(body.get("operation"),8);require(Set.of("create","save","remove").contains(operation),"Choose a window operation");boolean create=operation.equals("create"),remove=operation.equals("remove");var keys=new HashSet<>(Set.of("operation","revision"));if(!create)keys.add("window_id");if(!remove)keys.addAll(Set.of("name","kind","calendar_id","active"));require(body.keySet().equals(keys),"Provide only the selected window fields");String id=create?Values.id():WorkJobs.text(body.get("window_id"),32);
  Map<String,Object> current=create?map("revision",0):db.one("SELECT * FROM os_change_window WHERE window_id=? AND ci_id=?",id,ci);if(current==null)throw new Failure(404,"Change window not found");ProblemInvestigations.revision(body.get("revision"),current.get("revision"));
  if(remove)db.update("DELETE FROM os_change_window WHERE window_id=?",id);
  else{String name=WorkJobs.text(body.get("name"),120),kind=WorkJobs.text(body.get("kind"),12),calendar=WorkJobs.text(body.get("calendar_id"),80);require(Set.of("maintenance","blackout").contains(kind),"Choose maintenance or blackout");require(body.get("active") instanceof Boolean,"Choose active or inactive");require(db.one("SELECT calendar_id FROM os_calendar WHERE calendar_id=?",calendar)!=null,"Choose an existing calendar");if(create)require(db.count("SELECT COUNT(*) FROM os_change_window WHERE ci_id=?",ci)<20,"A CI supports at most 20 change windows");db.update("INSERT INTO os_change_window(window_id,ci_id,name,kind,calendar_id,active,revision) VALUES(?,?,?,?,?,?,?) ON CONFLICT(window_id) DO UPDATE SET name=excluded.name,kind=excluded.kind,calendar_id=excluded.calendar_id,active=excluded.active,revision=excluded.revision",id,ci,name,kind,calendar,(Boolean)body.get("active")?1:0,((Number)current.get("revision")).intValue()+1);}
  SecurityEvents.record(db,db.viewerId,db.viewerId,"change.window_"+operation,map("window_id",id,"ci_id",ci));return page(db,ci);
 }
 static List<String> conflicts(Store.Db db,String ci,long start,long end)throws SQLException{
  var result=new ArrayList<String>();boolean maintenance=false,covered=false;
  for(var row:db.all("SELECT w.name,w.kind,c.definition AS calendar_definition FROM os_change_window w LEFT JOIN os_calendar c ON c.calendar_id=w.calendar_id WHERE w.ci_id=? AND w.active=1 ORDER BY w.name,w.window_id",ci)){
   if(row.get("calendar_definition")==null){result.add("Unavailable calendar for "+row.get("name"));continue;}long duration=BusinessCalendar.elapsed(object(JSON.readValue(row.get("calendar_definition").toString(),Map.class)),start,end);
   if(row.get("kind").equals("maintenance")){maintenance=true;covered|=duration==end-start;}else if(duration>0)result.add("Blackout: "+row.get("name"));
  }
  if(maintenance && !covered)result.add("The full interval must fit within an active maintenance window");return result;
 }
 static void deleting(Store.Db db,String id)throws SQLException{
  if(db.count("SELECT COUNT(*) FROM os_change_schedule WHERE ci_id=?",id)>0)throw new Failure(409,"Clear retained change schedules before deleting their configuration item");
 }
}
