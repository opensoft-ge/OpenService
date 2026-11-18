package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;

/** Reviewed department handoffs, separate from private HR case content. */
final class EmployeeOnboarding {
    private EmployeeOnboarding() {}

    static Map<String,Object> plan(Store.Db db,String id)throws SQLException {
        var row=db.one("SELECT revision,launched,steps FROM os_hr_onboarding WHERE parent_id=?",id);if(row==null)return map("revision",0,"launched",0,"steps",new ArrayList<Map<String,Object>>());row.put("steps",JSON.readValue(row.get("steps").toString(),List.class));return row;
    }

    static Map<String,Object> binding(Store.Db db,String child)throws SQLException {return db.one("SELECT * FROM os_hr_onboarding_work WHERE child_id=?",child);}

    static String filter(Store.Db db,String id,List<Object> parameters){parameters.add(db.viewerId);return "NOT EXISTS(SELECT 1 FROM os_hr_onboarding_work w WHERE w.child_id="+id+" AND NOT EXISTS(SELECT 1 FROM os_group_member m JOIN os_record g ON g.sys_id=m.group_id AND g.\"table\"='sys_user_group' WHERE m.group_id=w.group_id AND m.user_id=?))";}

    static List<Map<String,Object>> progress(Store.Db db,String id)throws SQLException {return db.all("SELECT display_title,status FROM os_hr_onboarding_work WHERE parent_id=? ORDER BY ordinal",id);}

    static boolean open(Map<String,Object> parent){return !Set.of("closed","cancelled").contains(object(parent.get("data")).get("state"));}

    static Map<String,Object> page(Store.Db db,String id)throws SQLException {
        var parent=EmployeeCases.record(db,id,false);var plan=plan(db,id);var groups=new LinkedHashMap<String,String>();if(RecordPolicy.allows(Service.schema(db,"sys_user_group"),"read",db.viewerRole))for(var g:Service.page(db,"sys_user_group",Map.of("sysparm_limit","100"),"").rows())groups.put(g.get("sys_id").toString(),Objects.toString(g.get("short_description"),"Team"));
        var work=db.all("SELECT ordinal,status,reason,updated FROM os_hr_onboarding_work WHERE parent_id=? ORDER BY ordinal",id);return map("parent",FieldAccess.serialize(db,parent),"plan",plan,"work",work,"groups",groups,"can_manage",open(parent) && EmployeeCases.writable(db) && RecordPolicy.allows(Service.schema(db,"hr_case"),"update",db.viewerRole));
    }

    static Map<String,Object> command(Store.Db db,String id,Object input)throws SQLException {
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");var parent=EmployeeCases.record(db,id,false);RecordPolicy.require(Service.schema(db,"hr_case"),"update",db.viewerRole);if(!EmployeeCases.writable(db))throw new Failure(403,"HR description write access required");if(!open(parent))throw new Failure(409,"Reopen the HR case before changing onboarding");var body=object(input);String operation=WorkJobs.text(body.get("operation"),10);require(Set.of("add","remove","launch","waive").contains(operation),"Choose an onboarding operation");var keys=new HashSet<>(Set.of("operation","revision","record_revision"));if(operation.equals("add"))keys.addAll(Set.of("display_title","summary","details","group_id"));if(operation.equals("remove"))keys.add("step_id");if(operation.equals("waive"))keys.addAll(Set.of("ordinal","reason"));require(body.keySet().equals(keys),"Provide only the selected onboarding fields");var plan=plan(db,id);ProblemInvestigations.revision(body.get("revision"),plan.get("revision"));ProblemInvestigations.revision(body.get("record_revision"),parent.get("revision"));var steps=objects(plan.get("steps"));boolean launched=((Number)plan.get("launched")).intValue()==1;String actor=Service.account(db,db.viewerId).get("name").toString();
        if(operation.equals("waive")){
            Service.requireAccountAdministrator(db,db.viewerId);require(body.get("ordinal") instanceof Integer n && n>0 && n<=20,"Choose an onboarding step");String reason=WorkJobs.text(body.get("reason"),4000);var work=db.one("SELECT child_id,task_id,status FROM os_hr_onboarding_work WHERE parent_id=? AND ordinal=?",id,body.get("ordinal"));if(work==null)throw new Failure(404,"Onboarding step not found");if(Set.of("complete","waived").contains(work.get("status")))throw new Failure(409,"Only unfinished onboarding work can be waived");db.update("UPDATE os_case_task SET state='cancelled',required=0,outcome='',note=?,revision=revision+1 WHERE task_id=?",reason,work.get("task_id"));db.update("UPDATE os_task_plan SET revision=revision+1 WHERE parent_id=?",work.get("child_id"));db.update("UPDATE os_hr_onboarding_work SET status='waived',reason=?,updated=? WHERE child_id=?",reason,now(),work.get("child_id"));
        }else{
            if(launched)throw new Failure(409,"A launched onboarding plan is immutable");
            if(operation.equals("add")){require(steps.size()<20,"An onboarding plan supports at most 20 steps");String group=WorkJobs.text(body.get("group_id"),32);Service.readRecord(db,"sys_user_group",group);steps.add(map("step_id",Values.id(),"display_title",WorkJobs.text(body.get("display_title"),160),"summary",WorkJobs.text(body.get("summary"),160),"details",WorkJobs.text(body.get("details"),4000),"group_id",group));}
            else if(operation.equals("remove")){String step=WorkJobs.text(body.get("step_id"),32);boolean removed=steps.removeIf(value->value.get("step_id").equals(step));if(!removed)throw new Failure(404,"Draft step not found");}
            else{require(!steps.isEmpty(),"Add department work before launching");CatalogueWorkflows.lifecycle(db);RecordPolicy.require(Service.schema(db,"sc_request"),"create",db.viewerRole);for(var step:steps){Service.readRecord(db,"sys_user_group",step.get("group_id").toString());}int ordinal=0;
                for(var step:steps){var child=Service.create(db,"sc_request",map("short_description",step.get("summary"),"description",step.get("details")),actor);require(object(child.get("data")).get("state").equals("open"),"Onboarding requests must start open");String childId=child.get("sys_id").toString(),task=Values.id();db.update("INSERT INTO os_task_plan(parent_id,revision,close_at) VALUES(?,1,'complete')",childId);db.update("INSERT INTO os_case_task(task_id,parent_id,ordinal,summary,details,group_id,assignee_id,state,required,predecessors,outcome,note,revision) VALUES(?,?,1,?,?,?,'','queued',1,'[]','','',0)",task,childId,step.get("summary"),step.get("details"),step.get("group_id"));db.update("INSERT INTO os_hr_onboarding_work(child_id,parent_id,task_id,ordinal,group_id,display_title,status,reason,updated) VALUES(?,?,?,?,?,?,'pending','',?)",childId,id,task,++ordinal,step.get("group_id"),step.get("display_title"),now());}plan.put("launched",1);
            }
        }
        db.update("INSERT INTO os_hr_onboarding(parent_id,revision,launched,steps) VALUES(?,?,?,?) ON CONFLICT(parent_id) DO UPDATE SET revision=excluded.revision,launched=excluded.launched,steps=excluded.steps",id,((Number)plan.get("revision")).intValue()+1,plan.get("launched"),JSON.writeValueAsString(steps));Service.update(db,"hr_case",parent,map(),actor,db.viewerRole,parent.get("revision").toString());Service.audit(db,parent,actor,"onboarding."+operation,map("plan_revision",((Number)plan.get("revision")).intValue()+1));return page(db,id);
    }

    static boolean ready(Store.Db db,Map<String,Object> work)throws SQLException {return db.count("SELECT COUNT(*) FROM os_hr_onboarding_work WHERE parent_id=? AND ordinal<? AND status NOT IN ('complete','waived')",work.get("parent_id"),work.get("ordinal"))==0;}

    static void taskGuard(Store.Db db,String child,String operation)throws SQLException {
        var work=binding(db,child);if(work==null)return;if(!Set.of("claim","complete","requeue").contains(operation))throw new Failure(409,"Use the reviewed HR onboarding plan to manage this work");if(Set.of("complete","waived").contains(work.get("status")))throw new Failure(409,"This onboarding step is already settled");if(!ready(db,work))throw new Failure(409,"Complete or explicitly waive earlier onboarding steps first");
    }

    static void advance(Store.Db db,Map<String,Object> child)throws SQLException {
        String id=child.get("sys_id").toString();var work=binding(db,id);if(work==null)return;CatalogueWorkflows.lifecycle(db);var task=WorkTasks.task(db,id,work.get("task_id").toString());String state=task.get("state").toString(),actor=Service.account(db,db.viewerId).get("name").toString();if(state.equals("working")){if(object(child.get("data")).get("state").equals("open"))Service.update(db,"sc_request",child,map("state","fulfilling"),actor,db.viewerRole,child.get("revision").toString());}else if(state.equals("complete"))Service.update(db,"sc_request",child,map("state","complete"),actor,db.viewerRole,child.get("revision").toString());db.update("UPDATE os_hr_onboarding_work SET status=?,updated=? WHERE child_id=?",state.equals("queued")?"pending":state,now(),id);db.update("UPDATE os_hr_onboarding SET revision=revision+1 WHERE parent_id=?",work.get("parent_id"));
    }

    static void guard(Store.Db db,Map<String,Object> item)throws SQLException {if("hr_case".equals(item.get("table")) && !open(item))unfinished(db,item.get("sys_id").toString());}
    static void unfinished(Store.Db db,String id)throws SQLException {if(db.count("SELECT COUNT(*) FROM os_hr_onboarding_work WHERE parent_id=? AND status NOT IN ('complete','waived')",id)>0)throw new Failure(409,"Finish or explicitly waive onboarding work before closing or deleting HR");}
    static void deleting(Store.Db db,String id)throws SQLException {unfinished(db,id);db.update("DELETE FROM os_hr_onboarding WHERE parent_id=?",id);}
    static void insertion(Store.Db db,String id)throws SQLException {if(binding(db,id)!=null)throw new Failure(409,"A retained onboarding identity cannot be reused");}
}
