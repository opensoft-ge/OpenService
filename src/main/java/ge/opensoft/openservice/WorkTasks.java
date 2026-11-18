package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;

/** Durable case-owned tasks with finish-to-start dependencies and live team authority. */
final class WorkTasks {
    private WorkTasks() {}

    static Map<String,Object> record(Store.Db db,String table,String id) throws SQLException {
        var item=ProblemInvestigations.record(db,table,id);
        if(!FieldAccess.allowed(db,Service.schema(db,table),"state"))throw new Failure(403,"Parent state access required");return item;
    }

    static Map<String,Object> plan(Store.Db db,String id) throws SQLException {
        var plan=db.one("SELECT revision,close_at FROM os_task_plan WHERE parent_id=?",id);
        return plan==null?map("revision",0,"close_at",""):plan;
    }

    static boolean closed(Store.Db db,Map<String,Object> item,Map<String,Object> plan) throws SQLException {
        var definition=Service.schema(db,item.get("table").toString());Object state=object(item.get("data")).getOrDefault("state","");
        return state.equals(plan.get("close_at")) || ((List<?>)object(definition.get("flow")).getOrDefault(state,List.of())).isEmpty();
    }

    static Map<String,Object> task(Store.Db db,String parent,String id) throws SQLException {
        var row=db.one("SELECT * FROM os_case_task WHERE parent_id=? AND task_id=?",parent,id);if(row==null)throw new Failure(404,"Work task not found");
        row.put("predecessors",JSON.readValue(row.get("predecessors").toString(),List.class));return row;
    }

    static boolean ready(Store.Db db,Map<String,Object> task) throws SQLException {
        for(Object id:(List<?>)task.get("predecessors"))if(!task(db,task.get("parent_id").toString(),id.toString()).get("state").equals("complete"))return false;return true;
    }

    static List<String> predecessors(Store.Db db,String parent,Object input,int ordinal) throws SQLException {
        require(input instanceof List<?> && ((List<?>)input).size()<=20,"Choose at most 20 predecessor tasks");var result=new ArrayList<String>();
        for(Object value:(List<?>)input){String id=WorkJobs.text(value,32);require(!result.contains(id),"Predecessors must be unique");var before=task(db,parent,id);require(((Number)before.get("ordinal")).intValue()<ordinal,"Choose an earlier task from this case");require(!before.get("state").equals("cancelled"),"A cancelled task cannot be a new predecessor");result.add(id);}return result;
    }

    static Map<String,Object> page(Store.Db db,String table,String id) throws SQLException {
        var parent=record(db,table,id);var plan=plan(db,id);var tasks=new ArrayList<Map<String,Object>>();var groupNames=new LinkedHashMap<String,String>();
        if(RecordPolicy.allows(Service.schema(db,"sys_user_group"),"read",db.viewerRole))for(var group:Service.page(db,"sys_user_group",Map.of("sysparm_limit","100"),"").rows())groupNames.put(group.get("sys_id").toString(),Objects.toString(group.get("short_description"),"Team"));
        for(var row:db.all("SELECT task_id FROM os_case_task WHERE parent_id=? ORDER BY ordinal",id)){
            var task=task(db,id,row.get("task_id").toString());task.put("ready",ready(db,task));task.put("field_status",FieldDispatch.current(db,task.get("task_id").toString()).get("status"));task.put("visit_phase",FieldVisits.current(db,task.get("task_id").toString()).get("phase"));task.put("owned",task.get("assignee_id").equals(db.viewerId));task.put("group_name",groupNames.getOrDefault(task.get("group_id"),"Restricted or unavailable team"));task.put("can_work","admin".equals(db.viewerRole) || Fulfilment.member(db,task.get("group_id").toString()));task.put("assignee_name",db.one("SELECT COALESCE((SELECT name FROM os_user WHERE user_id=?),'Unassigned') AS name",task.get("assignee_id")).get("name"));tasks.add(task);
        }
        return map("parent",FieldAccess.serialize(db,parent),"plan",plan,"states",Service.schema(db,table).get("states"),"tasks",tasks,"groups",groupNames,"mutable",!closed(db,parent,plan),"can_manage",RecordPolicy.allows(Service.schema(db,table),"update",db.viewerRole));
    }

    static Map<String,Object> command(Store.Db db,String table,String id,Object input) throws SQLException {
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");var parent=record(db,table,id);var plan=plan(db,id);var body=object(input);String operation=WorkJobs.text(body.get("operation"),12);
        require(Set.of("configure","create","revise","claim","complete","cancel","requeue","waive").contains(operation),"Choose a supported task operation");
        var keys=new HashSet<>(Set.of("operation","revision","record_revision"));boolean configure=operation.equals("configure"),create=operation.equals("create");
        if(configure)keys.add("close_at");else if(create)keys.addAll(Set.of("summary","details","group_id","required","predecessors"));else{keys.add("task_id");switch(operation){case "revise"->keys.addAll(Set.of("summary","details","group_id","predecessors"));case "complete"->keys.add("outcome");case "cancel","requeue","waive"->keys.add("reason");default->{ /* claim */ }}}
        require(body.keySet().equals(keys),"Provide only the selected task operation fields");ProblemInvestigations.revision(body.get("record_revision"),parent.get("revision"));
        Map<String,Object> task=null;if(!configure && !create)task=task(db,id,WorkJobs.text(body.get("task_id"),32));ProblemInvestigations.revision(body.get("revision"),task==null?plan.get("revision"):task.get("revision"));
        boolean structural=Set.of("configure","create","revise","waive").contains(operation);if(structural)RecordPolicy.require(Service.schema(db,table),"update",db.viewerRole);
        if(!configure){if(((Number)plan.get("revision")).intValue()==0)throw new Failure(409,"Configure the parent completion gate first");if(closed(db,parent,plan))throw new Failure(409,"Reopen the parent before changing its tasks");}
        String taskId="";
        if(configure){
            String state=WorkJobs.text(body.get("close_at"),80);require(object(Service.schema(db,table).get("states")).containsKey(state),"Choose a state from the parent schema");db.update("INSERT INTO os_task_plan(parent_id,revision,close_at) VALUES(?,?,?) ON CONFLICT(parent_id) DO UPDATE SET close_at=excluded.close_at",id,plan.get("revision"),state);
        }else if(create){
            require(db.count("SELECT COUNT(*) FROM os_case_task WHERE parent_id=?",id)<100,"A case supports at most 100 work tasks");require(body.get("required") instanceof Boolean,"Choose whether the task is required");int ordinal=(int)db.count("SELECT COALESCE(MAX(ordinal),0)+1 FROM os_case_task WHERE parent_id=?",id);String summary=WorkJobs.text(body.get("summary"),160),details=WorkJobs.text(body.get("details"),4000),group=WorkJobs.text(body.get("group_id"),32);Service.readRecord(db,"sys_user_group",group);var dependencies=predecessors(db,id,body.get("predecessors"),ordinal);taskId=Values.id();
            db.update("INSERT INTO os_case_task(task_id,parent_id,ordinal,summary,details,group_id,assignee_id,state,required,predecessors,outcome,note,revision) VALUES(?,?,?,?,?,?,'','queued',?,?,'','',0)",taskId,id,ordinal,summary,details,group,(Boolean)body.get("required")?1:0,JSON.writeValueAsString(dependencies));
        }else{
            taskId=task.get("task_id").toString();FieldDispatch.guard(db,taskId,operation);FieldVisits.guardTask(db,taskId,operation);String state=task.get("state").toString();boolean admin="admin".equals(db.viewerRole);
            if(operation.equals("revise")){
                if(!state.equals("queued"))throw new Failure(409,"Revise only queued tasks");String group=WorkJobs.text(body.get("group_id"),32);Service.readRecord(db,"sys_user_group",group);task.putAll(map("summary",WorkJobs.text(body.get("summary"),160),"details",WorkJobs.text(body.get("details"),4000),"group_id",group,"predecessors",predecessors(db,id,body.get("predecessors"),((Number)task.get("ordinal")).intValue())));
            }else if(operation.equals("waive") || operation.equals("requeue")){
                Service.requireAccountAdministrator(db,db.viewerId);task.put("note",WorkJobs.text(body.get("reason"),4000));
                if(operation.equals("waive")){if(((Number)task.get("required")).intValue()==0 || state.equals("complete"))throw new Failure(409,"Only unfinished required work can be waived");task.put("required",0);}
                else{if(!state.equals("working"))throw new Failure(409,"Requeue only work in progress");task.putAll(map("state","queued","assignee_id",""));}
            }else{
                if(!admin && !Fulfilment.member(db,task.get("group_id").toString()))throw new Failure(403,"Current task-team membership required");
                if(operation.equals("claim")){if(!state.equals("queued") || !ready(db,task))throw new Failure(409,"Claim queued work after its predecessors complete");task.putAll(map("state","working","assignee_id",db.viewerId));}
                else{
                    if(!Set.of("queued","working").contains(state))throw new Failure(409,"This work task is already terminal");
                    if(state.equals("working") && !admin && !task.get("assignee_id").equals(db.viewerId))throw new Failure(403,"Only the current assignee can finish this task");
                    if(operation.equals("complete")){if(!state.equals("working"))throw new Failure(409,"Claim the task before completing it");task.putAll(map("state","complete","outcome",WorkJobs.text(body.get("outcome"),4000)));}
                    else task.putAll(map("state","cancelled","outcome",WorkJobs.text(body.get("reason"),4000)));
                }
            }
            db.update("UPDATE os_case_task SET summary=?,details=?,group_id=?,assignee_id=?,state=?,required=?,predecessors=?,outcome=?,note=?,revision=revision+1 WHERE task_id=?",task.get("summary"),task.get("details"),task.get("group_id"),task.get("assignee_id"),task.get("state"),task.get("required"),JSON.writeValueAsString(task.get("predecessors")),task.get("outcome"),task.get("note"),taskId);FieldDispatch.finished(db,taskId,task.get("state").toString());
        }
        db.update("UPDATE os_task_plan SET revision=revision+1 WHERE parent_id=?",id);String actor=Service.account(db,db.viewerId).get("name").toString();if(structural)Service.update(db,table,parent,map(),actor,db.viewerRole,body.get("record_revision").toString());Service.audit(db,parent,actor,"worktask."+operation,map("task_id",taskId));if(Set.of("claim","complete").contains(operation))CatalogueWorkflows.advance(db,parent);return page(db,table,id);
    }

    static Map<String,Object> queue(Store.Db db,Map<String,String> args) throws SQLException {
        if(!Set.of("admin","agent").contains(db.viewerRole))throw new Failure(403,"Staff access required");require(Set.of("limit","offset").containsAll(args.keySet()),"Use limit and offset only");int limit=Service.number(args.getOrDefault("limit","20"),1,100),offset=Service.number(args.getOrDefault("offset","0"),0,Integer.MAX_VALUE);
        var parameters=new ArrayList<Object>();String filter=RecordAccess.filter(db,"r.sys_id",parameters);var readable=new ArrayList<String>();
        for(var row:db.all("SELECT name,definition FROM os_schema")){var definition=object(row.get("definition"));if(RecordPolicy.allows(definition,"read",db.viewerRole) && FieldAccess.allowed(db,definition,"description") && FieldAccess.allowed(db,definition,"state"))readable.add(row.get("name").toString());}
        parameters.addAll(readable);String sql=" FROM os_case_task t JOIN os_record r ON r.sys_id=t.parent_id WHERE t.state IN ('queued','working') AND "+filter+" AND r.\"table\" IN (''"+String.join("",Collections.nCopies(readable.size(),",?"))+")";
        if(!"admin".equals(db.viewerRole)){sql+=" AND EXISTS (SELECT 1 FROM os_group_member m JOIN os_record g ON g.sys_id=m.group_id AND g.\"table\"='sys_user_group' WHERE m.user_id=? AND m.group_id=t.group_id)";parameters.add(db.viewerId);}
        long total=db.count("SELECT COUNT(*)"+sql,parameters.toArray());parameters.add(limit);parameters.add(offset);var rows=db.all("SELECT t.task_id,t.summary,t.state,t.assignee_id,r.sys_id AS parent_id,r.\"table\" AS parent_table"+sql+" ORDER BY r.seq,t.ordinal LIMIT ? OFFSET ?",parameters.toArray());
        for(var row:rows)row.put("parent_label",FieldAccess.serialize(db,record(db,row.get("parent_table").toString(),row.get("parent_id").toString())).getOrDefault("short_description","Case"));
        return map("items",rows,"total",total,"limit",limit,"offset",offset,"next_offset",(long)offset+limit<total?(long)offset+limit:null);
    }

    static void guard(Store.Db db,Map<String,Object> item) throws SQLException {
        var plan=plan(db,item.get("sys_id").toString());if(((Number)plan.get("revision")).intValue()>0 && closed(db,item,plan) && db.count("SELECT COUNT(*) FROM os_case_task WHERE parent_id=? AND required=1 AND state<>'complete'",item.get("sys_id"))>0)throw new Failure(409,"Complete required work tasks or explicitly waive their requirements before closing this case");
    }

    static void deleting(Store.Db db,String id) throws SQLException {
        if(db.count("SELECT COUNT(*) FROM os_case_task WHERE parent_id=? AND state NOT IN ('complete','cancelled')",id)>0)throw new Failure(409,"Finish or cancel all work tasks before deleting their parent");
    }
}
