package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;

/** Immutable per-clock warnings use original business budgets and current recipient authority. */
final class SlaEscalations {
 private SlaEscalations() {}
 static void remember(Store.Db db,String clock,long budget)throws SQLException {
  db.update("INSERT INTO os_sla_budget(sla_id,budget_ms) VALUES(?,?)",clock,budget);
 }
 static Map<String,Object> page(Store.Db db,String id)throws SQLException {
  Service.requireAccountAdministrator(db,db.viewerId);
  var clock=db.one("SELECT s.*,COALESCE(b.budget_ms,0) AS budget_ms FROM os_sla s LEFT JOIN os_sla_budget b ON b.sla_id=s.sla_id WHERE s.sla_id=?",id);
  if(clock==null)throw new Failure(404,"Service clock not found");
  Service.readRecord(db,clock.get("table_name").toString(),clock.get("record_id").toString());
  var plan=db.one("SELECT definition,created_at,owner_id FROM os_sla_escalation WHERE sla_id=?",id);
  return map("clock",clock,"configured",plan!=null,"steps",plan==null?List.of():object(plan.get("definition")).get("steps"),"receipts",db.all("SELECT percent,detail FROM os_sla_threshold WHERE sla_id=? ORDER BY percent",id),"can_configure",plan==null && ((Number)clock.get("budget_ms")).longValue()>0 && Set.of("running","paused").contains(clock.get("state")));
 }
 static Map<String,Object> form(Map<String,String> input){
  var form=new LinkedHashMap<>(input);form.remove("csrf");require(form.keySet().equals(Set.of("percent1","recipient1","percent2","recipient2","percent3","recipient3")),"Provide the three warning rows");
  var steps=new ArrayList<Map<String,Object>>();for(int n=1;n<=3;n++){String percent=form.get("percent"+n),recipient=form.get("recipient"+n);if(percent.isEmpty()){require(recipient.isEmpty(),"Choose a percentage for each recipient");continue;}steps.add(map("percent",Service.number(percent,1,99),"recipient",recipient));}return map("steps",steps);
 }
 static Map<String,Object> save(Store.Db db,String id,Object input,long now)throws SQLException {
  if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
  var page=page(db,id);var clock=object(page.get("clock"));if(Boolean.TRUE.equals(page.get("configured")))throw new Failure(409,"This clock already has an immutable warning plan");
  require(Boolean.TRUE.equals(page.get("can_configure")),"Use a running or paused clock with a known original budget");
  ServiceClocks.advance(db,clock,now);long remaining=((Number)db.one("SELECT remaining_ms FROM os_sla WHERE sla_id=?",id).get("remaining_ms")).longValue(),budget=((Number)clock.get("budget_ms")).longValue();
  var body=object(input);require(body.keySet().equals(Set.of("steps")),"Provide warning steps only");require(body.get("steps") instanceof List<?> list && !list.isEmpty() && list.size()<=3,"Provide one to three warning steps");var steps=new ArrayList<Map<String,Object>>();var seen=new HashSet<Integer>();
  for(Object value:(List<?>)body.get("steps")){var step=object(value);require(step.keySet().equals(Set.of("percent","recipient")),"Provide a percentage and recipient");require(step.get("percent") instanceof Integer p && p>0 && p<100,"Warning percentage must be an integer from1 to99");int percent=(Integer)step.get("percent");require(seen.add(percent),"Warning percentages must be unique");String recipient=WorkJobs.text(step.get("recipient"),20);require(Set.of("owner","assignee","group_manager","responsible").contains(recipient),"Choose a supported current recipient");require((budget-remaining)*100<budget*percent,"Every warning must be ahead of current business elapsed time");steps.add(map("percent",percent,"recipient",recipient));}
  steps.sort(Comparator.comparingInt(s->(Integer)s.get("percent")));db.update("INSERT INTO os_sla_escalation(sla_id,definition,owner_id,created_at) VALUES(?,?,?,?)",id,JSON.writeValueAsString(map("steps",steps)),db.viewerId,now);SecurityEvents.record(db,db.viewerId,db.viewerId,"sla.escalation_configured",map("sla_id",id,"steps",steps));return page(db,id);
 }
 static void advance(Store.Db db,Map<String,Object> clock,long remaining,long now)throws SQLException {
  String id=clock.get("sla_id").toString();var plan=db.one("SELECT e.definition,b.budget_ms FROM os_sla_escalation e JOIN os_sla_budget b ON b.sla_id=e.sla_id WHERE e.sla_id=?",id);if(plan==null)return;
  long budget=((Number)plan.get("budget_ms")).longValue();for(var step:objects(object(plan.get("definition")).get("steps"))){int percent=((Number)step.get("percent")).intValue();if((budget-remaining)*100<budget*percent || db.count("SELECT COUNT(*) FROM os_sla_threshold WHERE sla_id=? AND percent=?",id,percent)>0)continue;
   var item=db.one("SELECT * FROM os_record WHERE sys_id=? AND \"table\"=?",clock.get("record_id"),clock.get("table_name"));var outcomes=new ArrayList<Map<String,Object>>();
   if(item!=null){var recipients=new LinkedHashSet<String>();String selected=step.get("recipient").toString();var data=object(item.get("data"));if(Set.of("owner","responsible").contains(selected))recipients.add(clock.get("owner_id").toString());if(Set.of("assignee","responsible").contains(selected))SlaAlerts.username(db,recipients,data.get("assigned_to"));if(Set.of("group_manager","responsible").contains(selected)){var group=db.one("SELECT data FROM os_record WHERE sys_id=? AND \"table\"='sys_user_group'",Objects.toString(data.get("assignment_group"),""));if(group!=null)SlaAlerts.username(db,recipients,object(group.get("data")).get("manager"));}
    for(String recipient:recipients){var user=db.one("SELECT * FROM os_user WHERE user_id=? AND active=1 AND role IN ('admin','agent')",recipient);String outcome="unavailable";if(user!=null && !PasswordReset.required(user) && SlaAlerts.visible(db,user,item)){String notice=DigestUtils.sha256Hex("sla-warning:"+id+":"+percent+":"+recipient).substring(0,32);var delivered=Notifications.deliver(db,notice,map("username",user.get("name"),"message","Service clock "+id+" has reached its "+percent+"% business-time warning. Review the related service clocks."),now);if(Boolean.TRUE.equals(delivered.get("delivered"))){NoticeTargets.attach(db,notice,"clock",id);outcome="delivered";}else outcome="disabled";}outcomes.add(map("user_id",recipient,"name",user==null?"Unavailable account":user.get("name"),"outcome",outcome));}
   }
   db.update("INSERT INTO os_sla_threshold(sla_id,percent,detail) VALUES(?,?,?)",id,percent,JSON.writeValueAsString(map("recipient",step.get("recipient"),"processed_at",ServiceOutages.stamp(now),"budget_ms",budget,"consumed_ms",budget-remaining,"record_available",item!=null,"outcomes",outcomes)));
  }
 }
}
