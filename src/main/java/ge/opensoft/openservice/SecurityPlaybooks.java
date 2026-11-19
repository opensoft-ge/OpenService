package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.EnterpriseData.*;
import java.util.*;

/** Reviewed response plans execute through ordinary team tasks and dependencies. */
final class SecurityPlaybooks {
    private SecurityPlaybooks() {}
    static Map<String,Object> page(Store.Db db,String id)throws Exception {
        var record=WorkTasks.record(db,"security_incident",id);var row=get(db,"playbook",id);var data=object(row.get("definition"));var progress=new ArrayList<Map<String,Object>>();for(Object task:(List<?>)data.getOrDefault("tasks",List.of()))progress.add(WorkTasks.task(db,id,task.toString()));
        return EnterpriseData.page("Security response playbook","Review ordered containment steps, launch ordinary team work and retain each outcome. Stopping prevents unfinished work; completed effects remain and existing closure review is required.",map("record",FieldAccess.serialize(db,record),"playbook",row,"controls",ControlAssurance.cases(db,id),"progress",progress,"threat_feed",get(db,"threat-feed",id),"shared_link",get(db,"feed-link",id),"progress_state",progress.isEmpty()?"draft":progress.stream().allMatch(t->t.get("state").equals("complete"))?"completed":data.get("state")),
            form("link_feed","Link shared threat feed",field("feed","Shared feed ID","text",""),field("revision","Current revision","number",0)),
            form("observe","Record file observed on this case",field("sha256","SHA-256","text",""),field("revision","Current revision","number",0)),
            form("shared_matches","Compare linked cases"),
            form("feed","Replace case threat indicator feed",field("revision","Feed revision","number",get(db,"threat-feed",id).get("revision")),field("source","Feed source","text",""),field("indicators","Complete snapshot of sha256 and label pairs","json","[]")),
            form("fetch_feed","Import feed from configured connector",field("revision","Feed revision","number",get(db,"threat-feed",id).get("revision")),field("alias","Feed connector alias","text",""),field("path","JSON indicator feed path","text","/indicators")),
            form("match","Check observed file digest",field("sha256","Observed SHA-256","text","")),
            form("plan","Save response plan",field("revision","Playbook revision","number",row.get("revision")),field("steps","Ordered steps: summary, details, group_id","json","[]")),
            form("launch","Launch reviewed plan",field("revision","Playbook revision","number",row.get("revision")),field("record_revision","Case revision","number",record.get("revision")),field("confirm","Confirm these response steps","boolean",false)),
            form("stop","Stop unfinished response work",field("revision","Playbook revision","number",row.get("revision")),field("reason","Stop reason","text","")));
    }
    static void command(Store store,Store.Db db,String id,Object input)throws Exception {
        lock(db);var record=WorkTasks.record(db,"security_incident",id);if(!SecurityIncidents.writable(db))throw new Failure(403,"Security response write access required");require(!object(record.get("data")).get("state").equals("closed"),"Reopen the security case before changing its playbook");var body=object(input);String op=WorkJobs.text(body.get("operation"),10);
        if (op.equals("fetch_feed")) {
            ThreatIndicators.fetch(store, db, id, body);
            return;
        }
        if (op.equals("feed")) {
            ThreatIndicators.ingest(db, id, body);
            return;
        }
        var row=get(db,"playbook",id);ProblemInvestigations.revision(body.get("revision"),row.get("revision"));var data=object(row.get("definition"));
        if(op.equals("plan")){
            keys(body,"operation","revision","steps");require(!data.containsKey("tasks"),"A launched response plan is immutable");require(body.get("steps") instanceof List<?> list && !list.isEmpty() && list.size()<=20,"Provide one to twenty ordered response steps");var steps=new ArrayList<Map<String,Object>>();for(var step:objects(body.get("steps"))){keys(step,"summary","details","group_id");String group=EnterpriseData.id(step.get("group_id"));Service.readRecord(db,"sys_user_group",group);steps.add(map("summary",WorkJobs.text(step.get("summary"),160),"details",WorkJobs.text(step.get("details"),4000),"group_id",group));}data.putAll(map("steps",steps,"state","draft"));
        }else if(op.equals("launch")){
            keys(body,"operation","revision","record_revision","confirm");require(Boolean.TRUE.equals(body.get("confirm")),"Confirm the reviewed response plan");require(data.getOrDefault("state","").equals("draft"),"Save a draft before launch");ProblemInvestigations.revision(body.get("record_revision"),record.get("revision"));var ids=new ArrayList<String>();
            for(var step:objects(data.get("steps"))){var current=WorkTasks.record(db,"security_incident",id);var before=WorkTasks.plan(db,id);var result=WorkTasks.command(db,"security_incident",id,map("operation","create","revision",before.get("revision"),"record_revision",current.get("revision"),"summary",step.get("summary"),"details",step.get("details"),"group_id",step.get("group_id"),"required",true,"predecessors",ids.isEmpty()?List.of():List.of(ids.getLast())));ids.add(objects(result.get("tasks")).getLast().get("task_id").toString());}
            data.putAll(map("tasks",ids,"state","running","launched_by",db.viewerId));
        }else{
            require(op.equals("stop"),"Choose plan, launch or stop");keys(body,"operation","revision","reason");require(data.getOrDefault("state","").equals("running"),"Only a running playbook can be stopped");String reason=WorkJobs.text(body.get("reason"),2000);
            for(Object value:(List<?>)data.get("tasks")){String taskId=value.toString();var task=WorkTasks.task(db,id,taskId);if(Set.of("complete","cancelled").contains(task.get("state")))continue;var current=WorkTasks.record(db,"security_incident",id);WorkTasks.command(db,"security_incident",id,map("operation","cancel","revision",task.get("revision"),"record_revision",current.get("revision"),"task_id",taskId,"reason",reason));}
            data.putAll(map("state","stopped","reason",reason,"stopped_by",db.viewerId));
        }save(db,"playbook",id,row.get("revision"),data);
    }
}
