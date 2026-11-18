package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
/** Live CI reservation checks enforce reviewed scheduling through shared persistence. */
final class ChangeScheduling {
 private ChangeScheduling() {}
 static long instant(Object value){String text=WorkJobs.text(value,40);try{var instant=Instant.parse(text);require(instant.getNano()==0,"Use whole seconds for change schedules");long result=instant.toEpochMilli();require(result>=0 && result<=4102444800000L,"Schedule changes between 1970 and 2100");return result;}catch(DateTimeException | ArithmeticException invalid){throw new Failure(400,"Use an ISO UTC timestamp such as 2026-11-18T10:00:00Z");}}
 static Map<String,Object> schedule(Store.Db db,String id)throws SQLException{
  var row=db.one("SELECT ci_id,starts,ends,revision FROM os_change_schedule WHERE change_id=?",id);return row==null?map("ci_id","","starts",0L,"ends",0L,"revision",0):row;
 }
 static Map<String,Object> conflicts(Store.Db db,String id,Map<String,Object> schedule)throws SQLException{
  String ci=schedule.get("ci_id").toString();if(ci.isEmpty())return map("windows",List.of(),"changes",List.of(),"restricted",false,"more",false,"blocked",false);
  Service.readRecord(db,"cmdb_ci",ci);long start=((Number)schedule.get("starts")).longValue(),end=((Number)schedule.get("ends")).longValue();var windows=ChangeWindows.conflicts(db,ci,start,end);var changes=new ArrayList<Map<String,Object>>();boolean restricted=false;String state=db.postgres?"r.data->>'state'":"json_extract(r.data,'$.state')";String from=" FROM os_change_schedule s JOIN os_record r ON r.sys_id=s.change_id WHERE s.ci_id=? AND s.change_id<>? AND s.starts<? AND s.ends>? AND COALESCE("+state+",'') NOT IN ('closed','rejected')";long total=db.count("SELECT COUNT(*)"+from,ci,id,end,start);
  for(var peer:db.all("SELECT s.change_id,s.starts,s.ends"+from+" ORDER BY s.starts,s.change_id LIMIT 50",ci,id,end,start)){
   try{var item=ChangePlanning.record(db,peer.get("change_id").toString());var visible=FieldAccess.serialize(db,item);changes.add(map("id",peer.get("change_id"),"label",visible.getOrDefault("short_description","Reserved change"),"start",Instant.ofEpochMilli(((Number)peer.get("starts")).longValue()).toString(),"end",Instant.ofEpochMilli(((Number)peer.get("ends")).longValue()).toString()));}catch(Failure hidden){restricted=true;}
  }
  return map("windows",windows,"changes",changes,"restricted",restricted,"more",total>50,"blocked",!windows.isEmpty() || total>0);
 }
 static Map<String,Object> page(Store.Db db,String id,Map<String,String> args)throws SQLException{
  var parent=ChangePlanning.record(db,id);var schedule=schedule(db,id);String ci=schedule.get("ci_id").toString();Map<String,Object> selected=null;Map<String,Object> findings;
  try{findings=conflicts(db,id,schedule);if(!ci.isEmpty())selected=FieldAccess.serialize(db,Service.readRecord(db,"cmdb_ci",ci));}catch(Failure denied){findings=map("windows",List.of("The selected configuration item is no longer accessible; select an authorized replacement"),"changes",List.of(),"restricted",false,"more",false,"blocked",true);}
  Map<String,Object> search;try{search=ReferenceSearch.search(db,"cmdb_ci",args);}catch(Failure denied){if(denied.status!=403)throw denied;search=map("items",List.of(),"total",0,"limit",20,"offset",0,"next_offset",null);}var shown=new LinkedHashMap<>(schedule);if(selected==null)shown.put("ci_id","");return map("change",FieldAccess.serialize(db,parent),"schedule",shown,"selected",selected,"start",ci.isEmpty()?"":Instant.ofEpochMilli(((Number)schedule.get("starts")).longValue()).toString(),"end",ci.isEmpty()?"":Instant.ofEpochMilli(((Number)schedule.get("ends")).longValue()).toString(),"findings",findings,"search",search,"editable",Set.of("draft","rejected").contains(object(parent.get("data")).get("state")),"enrolled",((Number)ChangePlanning.plan(db,id).get("revision")).intValue()>0);
 }
 static Map<String,Object> save(Store.Db db,String id,Object input)throws SQLException{
  if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");var parent=ChangePlanning.record(db,id);var current=schedule(db,id);var body=object(input);String operation=WorkJobs.text(body.get("operation"),6);require(Set.of("save","clear").contains(operation),"Choose save or clear");var keys=new HashSet<>(Set.of("operation","revision","record_revision"));if(operation.equals("save"))keys.addAll(Set.of("ci_id","start","end"));require(body.keySet().equals(keys),"Provide only the selected schedule fields");ProblemInvestigations.revision(body.get("revision"),current.get("revision"));ProblemInvestigations.revision(body.get("record_revision"),parent.get("revision"));if(!Set.of("draft","rejected").contains(object(parent.get("data")).get("state")))throw new Failure(409,"Return the change to draft or rejected before rescheduling");if(((Number)ChangePlanning.plan(db,id).get("revision")).intValue()==0)throw new Failure(409,"Create the reviewed change plan before scheduling");
  if(operation.equals("clear"))db.update("DELETE FROM os_change_schedule WHERE change_id=?",id);
  else{String ci=WorkJobs.text(body.get("ci_id"),32);Service.readRecord(db,"cmdb_ci",ci);long start=instant(body.get("start")),end=instant(body.get("end"));require(end>start && end-start<=31L*86400000,"Use a positive schedule interval of at most 31 days");db.update("INSERT INTO os_change_schedule(change_id,ci_id,starts,ends,revision) VALUES(?,?,?,?,?) ON CONFLICT(change_id) DO UPDATE SET ci_id=excluded.ci_id,starts=excluded.starts,ends=excluded.ends,revision=excluded.revision",id,ci,start,end,((Number)current.get("revision")).intValue()+1);}
  String actor=Service.account(db,db.viewerId).get("name").toString();Service.update(db,"change_request",parent,map(),actor,db.viewerRole,body.get("record_revision").toString());Service.audit(db,parent,actor,"change.schedule_"+operation,map("revision",((Number)current.get("revision")).intValue()+1));return page(db,id,Map.of());
 }
 static void guard(Store.Db db,Map<String,Object> parent)throws SQLException{
  if(!"change_request".equals(parent.get("table")) || !Set.of("review","approved","implement").contains(Objects.toString(object(parent.get("data")).get("state"),"")))return;
  if(Boolean.TRUE.equals(conflicts(db,parent.get("sys_id").toString(),schedule(db,parent.get("sys_id").toString())).get("blocked")))throw new Failure(409,"Resolve current CI reservations and maintenance or blackout conflicts before advancing this change");
 }
}
