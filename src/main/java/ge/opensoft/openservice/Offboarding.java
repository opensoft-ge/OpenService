package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;

/** Reviewed native work handoff; preserves business guards and historical viewer identities. */
final class Offboarding {
 private Offboarding() {}
 static Map<String,Object> account(Store.Db db,String id)throws SQLException {
  var user=db.one("SELECT user_id,name,role,active,api_only,password_reset_required,auth_revision FROM os_user WHERE user_id=?",id);if(user==null)throw new Failure(404,"Account not found");return user;
 }
 static void authority(Store.Db db,String source)throws SQLException {
  if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");Service.requireAccountAdministrator(db,db.viewerId);require(!source.equals(db.viewerId),"Choose another account for offboarding");
 }
 static Map<String,Object> reference(Store.Db db,String table,String id)throws SQLException {
  var item=Service.readRecord(db,table,id);var data=FieldAccess.serialize(db,item);return map("sys_id",id,"table",table,"number",data.get("number"),"summary",data.getOrDefault("short_description","Record"),"revision",item.get("revision"));
 }
 static List<Map<String,Object>> bounded(List<Map<String,Object>> rows)throws SQLException {require(rows.size()<=100,"Handoff supports at most 100 items per category; reduce the inventory through ordinary work controls first");return rows;}
 static Map<String,Object> inventory(Store.Db db,Map<String,Object> source,Map<String,Object> recipient)throws SQLException {
  String id=source.get("user_id").toString();var records=new ArrayList<Map<String,Object>>();String assignment=db.postgres?"data->>'assigned_to'":"json_extract(data,'$.assigned_to')";
  var candidates=db.all("SELECT sys_id,\"table\",data FROM os_record WHERE \"table\" IN ('incident','problem','change_request','sc_request') AND "+assignment+"=? ORDER BY sys_id LIMIT 1001",source.get("name"));require(candidates.size()<=1000,"At most 1000 matching core records can be reviewed at once");
  for(var candidate:candidates){String table=candidate.get("table").toString();var schema=Service.schema(db,table);String state=Objects.toString(object(candidate.get("data")).get("state"),"");if(((List<?>)object(schema.get("flow")).getOrDefault(state,List.of())).isEmpty())continue;records.add(reference(db,table,candidate.get("sys_id").toString()));}bounded(records);
  var tasks=bounded(db.all("SELECT t.task_id,t.parent_id,t.group_id,t.summary,t.revision,r.\"table\" AS table_name FROM os_case_task t JOIN os_record r ON r.sys_id=t.parent_id WHERE t.assignee_id=? AND t.state='working' ORDER BY t.task_id LIMIT 101",id));for(var task:tasks)task.put("parent",reference(db,task.get("table_name").toString(),task.get("parent_id").toString()));
  var fulfilment=bounded(db.all("SELECT request_id,group_id,revision FROM os_fulfilment WHERE assignee_id=? AND state='in_progress' ORDER BY request_id LIMIT 101",id));for(var work:fulfilment)work.put("parent",reference(db,"sc_request",work.get("request_id").toString()));
  var reports=bounded(db.all("SELECT report_id,owner_id,name,table_name,definition,shared FROM os_saved_report WHERE owner_id=? ORDER BY report_id LIMIT 101",id));
  var dispatches=bounded(db.all("SELECT d.task_id,d.revision,d.status,t.parent_id,r.\"table\" AS table_name FROM os_field_dispatch d JOIN os_case_task t ON t.task_id=d.task_id JOIN os_record r ON r.sys_id=t.parent_id WHERE d.technician_id=? AND d.status IN ('assigned','accepted') ORDER BY d.task_id LIMIT 101",id));for(var dispatch:dispatches)dispatch.put("parent",reference(db,dispatch.get("table_name").toString(),dispatch.get("parent_id").toString()));
  require(db.count("SELECT COUNT(*) FROM os_saved_report WHERE owner_id=?",recipient.get("user_id"))+reports.size()<=100,"The successor would exceed the 100-report ownership limit");
  String viewer=db.viewerId,role=db.viewerRole;try {
   db.viewerId=recipient.get("user_id").toString();db.viewerRole=recipient.get("role").toString();
   for(var row:records){var record=Service.readRecord(db,row.get("table").toString(),row.get("sys_id").toString());var schema=Service.schema(db,row.get("table").toString());for(String field:List.of("assigned_to","state"))if(!FieldAccess.allowed(db,schema,field))throw new Failure(403,"The successor must be able to read assignment and state fields");String group=Objects.toString(object(record.get("data")).get("assignment_group"),"");if(!group.isEmpty()){Service.readRecord(db,"sys_user_group",group);if(!db.viewerRole.equals("admin") && !Fulfilment.member(db,group))throw new Failure(403,"The successor must belong to each existing assignment group");}}
   for(var task:tasks){WorkTasks.record(db,task.get("table_name").toString(),task.get("parent_id").toString());Service.readRecord(db,"sys_user_group",task.get("group_id").toString());if(!db.viewerRole.equals("admin") && !Fulfilment.member(db,task.get("group_id").toString()))throw new Failure(403,"The successor must belong to each continuing task team");}
   for(var work:fulfilment){Service.readRecord(db,"sc_request",work.get("request_id").toString());Fulfilment.access(db);Service.readRecord(db,"sys_user_group",work.get("group_id").toString());if(!db.viewerRole.equals("admin") && !Fulfilment.member(db,work.get("group_id").toString()))throw new Failure(403,"The successor must belong to each continuing fulfilment team");}
   for(var report:reports){var args=new LinkedHashMap<String,String>();object(report.get("definition")).forEach((key,value)->args.put(key,value.toString()));Analytics.report(db,report.get("table_name").toString(),args);}
  }finally{db.viewerId=viewer;db.viewerRole=role;}
  return map("source",source,"recipient",recipient,"records",records,"tasks",tasks,"fulfilment",fulfilment,"reports",reports,"dispatches",dispatches);
 }
 static Map<String,Object> preview(Store.Db db,String sourceId,String recipientName)throws Exception {
  authority(db,sourceId);var source=account(db,sourceId);var target=db.one("SELECT user_id FROM os_user WHERE name=? AND active=1 AND role IN ('admin','agent') AND api_only=0 AND password_reset_required=0",recipientName);require(target!=null,"Choose an active browser staff successor without a required password change");var recipient=account(db,target.get("user_id").toString());require(!sourceId.equals(recipient.get("user_id")),"Choose a different successor");var result=inventory(db,source,recipient);result.put("review",Passwords.tokenHash(JSON.writeValueAsString(result)));return result;
 }
 static Map<String,Object> page(Store.Db db,String source,String recipient)throws Exception {
  authority(db,source);var user=account(db,source);return map("account",user,"recipient",recipient,"preview",recipient.isEmpty()?null:preview(db,source,recipient),"history",db.all("SELECT handoff_id,recipient_id,actor_id,created_at,detail FROM os_offboarding WHERE source_id=? ORDER BY created_at DESC,handoff_id DESC LIMIT 100",source));
 }
 static Map<String,Object> apply(Store.Db db,String source,Object input)throws Exception {
  var body=object(input);require(body.keySet().equals(Set.of("recipient","review","reason")),"Provide successor, reviewed inventory and reason");String recipient=WorkJobs.text(body.get("recipient"),80),review=WorkJobs.text(body.get("review"),64),reason=WorkJobs.text(body.get("reason"),1000);require(review.matches("[0-9a-f]{64}"),"Use the reviewed handoff fingerprint");var plan=preview(db,source,recipient);if(!review.equals(plan.get("review")))throw new Failure(409,"Account, successor or supported inventory changed; review the handoff again");
  if(!objects(plan.get("dispatches")).isEmpty())throw new Failure(409,"Release or close out active field dispatches before offboarding");if(AccessMode.lastBrowserAdministrator(db,source))throw new Failure(409,"Keep at least one active browser administrator");require(db.count("SELECT COUNT(*) FROM os_offboarding WHERE source_id=?",source)<100,"This account has reached its retained handoff limit");
  var records=objects(plan.get("records"));var tasks=objects(plan.get("tasks"));var fulfilment=objects(plan.get("fulfilment"));var reports=objects(plan.get("reports"));if(((Number)object(plan.get("source")).get("active")).intValue()==0 && records.isEmpty() && tasks.isEmpty() && fulfilment.isEmpty() && reports.isEmpty())throw new Failure(409,"This inactive account has no supported work left to hand off");String actor=Service.account(db,db.viewerId).get("name").toString();
  for(var row:records){String table=row.get("table").toString();Service.update(db,table,Service.record(db,table,row.get("sys_id").toString()),map("assigned_to",recipient),actor,db.viewerRole,row.get("revision").toString());}
  for(var task:tasks){String table=task.get("table_name").toString(),parent=task.get("parent_id").toString();WorkTasks.command(db,table,parent,map("operation","requeue","task_id",task.get("task_id"),"revision",task.get("revision"),"record_revision",Service.record(db,table,parent).get("revision"),"reason",reason));}
  for(var work:fulfilment){String parent=work.get("request_id").toString();Fulfilment.control(db,parent,map("operation","reassign","revision",work.get("revision"),"request_revision",Service.record(db,"sc_request",parent).get("revision"),"group_id",work.get("group_id")));}
  String target=object(plan.get("recipient")).get("user_id").toString();for(var report:reports)db.update("UPDATE os_saved_report SET owner_id=? WHERE report_id=?",target,report.get("report_id"));Service.changeActive(db,db.viewerId,source,map("active",false));
  var detail=map("source_name",object(plan.get("source")).get("name"),"recipient_name",recipient,"reason",reason,"review",review,"record_ids",records.stream().map(row->row.get("sys_id")).toList(),"task_ids",tasks.stream().map(row->row.get("task_id")).toList(),"request_ids",fulfilment.stream().map(row->row.get("request_id")).toList(),"report_ids",reports.stream().map(row->row.get("report_id")).toList());String handoff=Values.id();db.update("INSERT INTO os_offboarding(handoff_id,source_id,recipient_id,actor_id,created_at,detail) VALUES(?,?,?,?,?,?)",handoff,source,target,db.viewerId,now(),JSON.writeValueAsString(detail));SecurityEvents.record(db,db.viewerId,source,"account.offboarding_handoff",map("handoff_id",handoff,"recipient_id",target,"records",records.size(),"tasks",tasks.size(),"fulfilment",fulfilment.size(),"reports",reports.size()));return map("handoff_id",handoff,"detail",detail);
 }
}
