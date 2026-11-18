package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;

/** Reviewed, version-bound catalogue plans instantiated as dependent team work. */
final class CatalogueWorkflows {
    private CatalogueWorkflows() {}

    static Map<String,Object> configuration(Store.Db db,String id) throws SQLException {
        var row=db.one("SELECT enabled,steps FROM os_catalog_workflow WHERE offering_id=?",id);
        if(row==null)return map("enabled",0,"steps",new ArrayList<Map<String,Object>>());
        row.put("steps",JSON.readValue(row.get("steps").toString(),List.class));return row;
    }

    static Map<String,Object> settings(Store.Db db,String id) throws SQLException {
        Service.requireAccountAdministrator(db,db.viewerId);return map("offering",Catalogue.find(db,id,true),"workflow",configuration(db,id));
    }

    static Map<String,Object> save(Store.Db db,String id,Object input) throws SQLException {
        Service.requireAccountAdministrator(db,db.viewerId);if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");var offering=Catalogue.find(db,id,true);var body=object(input);String operation=WorkJobs.text(body.get("operation"),10);
        require(Set.of("add","edit","remove","up","down","enable","disable").contains(operation),"Choose a workflow operation");var keys=new HashSet<>(Set.of("operation","revision"));boolean content=Set.of("add","edit").contains(operation);
        if(content)keys.addAll(Set.of("summary","details","group_id"));if(Set.of("edit","remove","up","down").contains(operation))keys.add("step_id");require(body.keySet().equals(keys),"Provide only the selected workflow fields");ProblemInvestigations.revision(body.get("revision"),offering.get("revision"));var config=configuration(db,id);var steps=objects(config.get("steps"));
        if(operation.equals("enable")){require(!steps.isEmpty(),"Add a team step before enabling this workflow");for(var step:steps)CatalogueRouting.group(db,step.get("group_id").toString());config.put("enabled",1);}
        else if(operation.equals("disable"))config.put("enabled",0);
        else{
            int index=-1;String stepId=operation.equals("add")?Values.id():WorkJobs.text(body.get("step_id"),32);
            if(!operation.equals("add")){for(int n=0;n<steps.size();n++)if(steps.get(n).get("step_id").equals(stepId)){index=n;break;}if(index<0)throw new Failure(404,"Workflow step not found");}
            if(content){String group=WorkJobs.text(body.get("group_id"),32);Service.readRecord(db,"sys_user_group",group);var step=map("step_id",stepId,"summary",WorkJobs.text(body.get("summary"),160),"details",WorkJobs.text(body.get("details"),4000),"group_id",group);
                if(operation.equals("add")){require(steps.size()<20,"A catalogue workflow supports at most 20 steps");steps.add(step);}else steps.set(index,step);
            }else if(operation.equals("remove")){require(((Number)config.get("enabled")).intValue()==0 || steps.size()>1,"Disable the workflow before removing its final step");steps.remove(index);}
            else{int target=index+(operation.equals("up")?-1:1);require(target>=0 && target<steps.size(),"This step is already at that end of the workflow");Collections.swap(steps,index,target);}
        }
        db.update("INSERT INTO os_catalog_workflow(offering_id,enabled,steps) VALUES(?,?,?) ON CONFLICT(offering_id) DO UPDATE SET enabled=excluded.enabled,steps=excluded.steps",id,config.get("enabled"),JSON.writeValueAsString(steps));db.update("UPDATE os_offering SET revision=revision+1 WHERE offering_id=?",id);SecurityEvents.record(db,db.viewerId,db.viewerId,"catalogue.workflow_"+operation,map("offering_id",id,"revision",offering.get("revision")));return settings(db,id);
    }

    static void lifecycle(Store.Db db) throws SQLException {
        var flow=object(Service.schema(db,"sc_request").get("flow"));require(((List<?>)flow.getOrDefault("open",List.of())).contains("fulfilling") && ((List<?>)flow.getOrDefault("fulfilling",List.of())).contains("complete") && ((List<?>)flow.getOrDefault("complete",List.of())).isEmpty(),"Catalogue team workflows require open, fulfilling and terminal complete request states");
    }

    static boolean create(Store.Db db,Map<String,Object> offering,Map<String,Object> request) throws SQLException {
        var config=configuration(db,offering.get("offering_id").toString());if(((Number)config.get("enabled")).intValue()==0)return false;lifecycle(db);require(object(request.get("data")).get("state").equals("open"),"Catalogue team workflows must start open");var steps=objects(config.get("steps"));String parent=request.get("sys_id").toString(),previous="";int ordinal=0;
        db.update("INSERT INTO os_task_plan(parent_id,revision,close_at) VALUES(?,1,'complete')",parent);
        for(var step:steps){CatalogueRouting.group(db,step.get("group_id").toString());String task=Values.id();db.update("INSERT INTO os_case_task(task_id,parent_id,ordinal,summary,details,group_id,assignee_id,state,required,predecessors,outcome,note,revision) VALUES(?,?,?,?,?,?,'','queued',1,?,'','',0)",task,parent,++ordinal,step.get("summary"),step.get("details"),step.get("group_id"),JSON.writeValueAsString(previous.isEmpty()?List.of():List.of(previous)));previous=task;}
        db.update("INSERT INTO os_catalog_workflow_order(request_id,offering_id,offering_revision,steps) VALUES(?,?,?,?)",parent,offering.get("offering_id"),offering.get("revision"),JSON.writeValueAsString(steps));return true;
    }

    static Map<String,Object> progress(Store.Db db,String id) throws SQLException {
        var parent=Service.readRecord(db,"sc_request",id);if(!FieldAccess.allowed(db,Service.schema(db,"sc_request"),"state"))throw new Failure(403,"Request state access required");var order=db.one("SELECT offering_revision FROM os_catalog_workflow_order WHERE request_id=?",id);if(order==null)return null;
        return map("offering_revision",order.get("offering_revision"),"total",db.count("SELECT COUNT(*) FROM os_case_task WHERE parent_id=?",id),"completed",db.count("SELECT COUNT(*) FROM os_case_task WHERE parent_id=? AND state='complete'",id),"waived",db.count("SELECT COUNT(*) FROM os_case_task WHERE parent_id=? AND required=0 AND state<>'complete'",id),"remaining",db.count("SELECT COUNT(*) FROM os_case_task WHERE parent_id=? AND required=1 AND state<>'complete'",id));
    }

    static void settle(Store.Db db,Map<String,Object> parent) throws SQLException {
        String id=parent.get("sys_id").toString();if(!"complete".equals(object(parent.get("data")).get("state")) || db.count("SELECT COUNT(*) FROM os_catalog_workflow_order WHERE request_id=?",id)==0)return;
        int settled=db.update("UPDATE os_case_task SET state='cancelled',outcome='Request completed; this work was optional or explicitly waived',revision=revision+1 WHERE parent_id=? AND required=0 AND state IN ('queued','working')",id);
        if(settled>0){db.update("UPDATE os_task_plan SET revision=revision+1 WHERE parent_id=?",id);Service.audit(db,parent,parent.get("updater").toString(),"catalogue.optional_work_settled",map("cancelled_tasks",settled));}
    }

    static Map<String,Object> reconcile(Store.Db db,String id,Object input) throws SQLException {
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");var parent=WorkTasks.record(db,"sc_request",id);var body=object(input);require(body.keySet().equals(Set.of("record_revision")),"Provide the current request revision");ProblemInvestigations.revision(body.get("record_revision"),parent.get("revision"));if(db.count("SELECT COUNT(*) FROM os_catalog_workflow_order WHERE request_id=?",id)==0)throw new Failure(404,"No catalogue workflow for this request");advance(db,parent);return progress(db,id);
    }

    static void advance(Store.Db db,Map<String,Object> parent) throws SQLException {
        String id=parent.get("sys_id").toString();if(db.count("SELECT COUNT(*) FROM os_catalog_workflow_order WHERE request_id=?",id)==0)return;lifecycle(db);boolean done=db.count("SELECT COUNT(*) FROM os_case_task WHERE parent_id=? AND required=1 AND state<>'complete'",id)==0;String state=object(parent.get("data")).get("state").toString(),actor=Service.account(db,db.viewerId).get("name").toString();
        if(state.equals("open") && (done || db.count("SELECT COUNT(*) FROM os_case_task WHERE parent_id=? AND state IN ('working','complete')",id)>0)){Service.update(db,"sc_request",parent,map("state","fulfilling"),actor,db.viewerRole,parent.get("revision").toString());}
        else if(done && state.equals("fulfilling"))Service.update(db,"sc_request",parent,map("state","complete"),actor,db.viewerRole,parent.get("revision").toString());
    }
}
