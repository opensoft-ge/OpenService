package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** Manual, capacity-safe dispatch of existing case-owned team work. */
final class FieldDispatch {
    private FieldDispatch() {}

    static Map<String,Object> current(Store.Db db,String task) throws SQLException {
        var row=db.one("SELECT * FROM os_field_dispatch WHERE task_id=?",task);
        return row==null?map("revision",0,"technician_id","","starts",0L,"ends",0L,"location","","status","unassigned","reason",""):row;
    }

    static void team(Store.Db db,Map<String,Object> task) throws SQLException {
        if(!"admin".equals(db.viewerRole) && !Fulfilment.member(db,task.get("group_id").toString()))throw new Failure(403,"Current field task-team membership required");
    }

    static Map<String,Object> page(Store.Db db,String table,String id,String taskId) throws SQLException {
        var parent=WorkTasks.record(db,table,id);var task=WorkTasks.task(db,id,taskId);team(db,task);var dispatch=current(db,taskId);
        var choices=new ArrayList<Map<String,Object>>();
        for(var user:db.all("SELECT u.user_id,u.name,a.calendar_id FROM os_user u JOIN os_field_availability a ON a.user_id=u.user_id JOIN os_group_member m ON m.user_id=u.user_id WHERE m.group_id=? AND u.active=1 AND u.role='agent' AND u.password_reset_required=0 AND a.active=1 ORDER BY u.name",task.get("group_id")))choices.add(user);
        var shown=new LinkedHashMap<>(dispatch);shown.put("technician",db.one("SELECT COALESCE((SELECT name FROM os_user WHERE user_id=?),'Unassigned or removed') AS name",dispatch.get("technician_id")).get("name"));
        boolean booked=Set.of("assigned","accepted").contains(dispatch.get("status")),valid=true;
        if(booked)try{FieldAvailability.eligible(db,table,id,task,dispatch.get("technician_id").toString(),((Number)dispatch.get("starts")).longValue(),((Number)dispatch.get("ends")).longValue());}catch(Failure unavailable){valid=false;}
        return map("parent",FieldAccess.serialize(db,parent),"task",task,"dispatch",shown,"technicians",choices,"start",((Number)dispatch.get("revision")).intValue()==0?"":Instant.ofEpochMilli(((Number)dispatch.get("starts")).longValue()).toString(),"end",((Number)dispatch.get("revision")).intValue()==0?"":Instant.ofEpochMilli(((Number)dispatch.get("ends")).longValue()).toString(),"eligible",valid,"owned",dispatch.get("technician_id").equals(db.viewerId),"booked",booked,"mutable",!WorkTasks.closed(db,parent,WorkTasks.plan(db,id)),"can_manage",RecordPolicy.allows(Service.schema(db,table),"update",db.viewerRole));
    }

    static Map<String,Object> command(Store.Db db,String table,String id,String taskId,Object input) throws SQLException {
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        var parent=WorkTasks.record(db,table,id);var task=WorkTasks.task(db,id,taskId);team(db,task);RecordPolicy.require(Service.schema(db,table),"update",db.viewerRole);
        var current=current(db,taskId);var body=object(input);String operation=WorkJobs.text(body.get("operation"),7);boolean assign=operation.equals("assign"),accept=operation.equals("accept");
        require(Set.of("assign","accept","reject","release").contains(operation),"Choose a field dispatch action");
        var keys=new HashSet<>(Set.of("operation","revision","task_revision","record_revision"));keys.addAll(assign?Set.of("technician_id","start","end","location"):Set.of("reason"));
        require(body.keySet().equals(keys),"Provide only the selected dispatch fields");
        ProblemInvestigations.revision(body.get("revision"),current.get("revision"));ProblemInvestigations.revision(body.get("task_revision"),task.get("revision"));ProblemInvestigations.revision(body.get("record_revision"),parent.get("revision"));
        if(WorkTasks.closed(db,parent,WorkTasks.plan(db,id)))throw new Failure(409,"Reopen the parent before changing field work");
        String actor=Service.account(db,db.viewerId).get("name").toString(),status=current.get("status").toString(),reason=assign?"":WorkJobs.text(body.get("reason"),4000);
        if(assign){
            if(!task.get("state").equals("queued") || !WorkTasks.ready(db,task) || Set.of("assigned","accepted").contains(status))throw new Failure(409,"Dispatch ready queued work after releasing any prior assignment");
            String technician=WorkJobs.text(body.get("technician_id"),32),location=WorkJobs.text(body.get("location"),240);long start=ChangeScheduling.instant(body.get("start")),end=ChangeScheduling.instant(body.get("end"));
            require(end>start && end-start<=86400000 && end>System.currentTimeMillis(),"Choose a visit interval of at most 24 hours ending in the future");
            FieldAvailability.eligible(db,table,id,task,technician,start,end);
            db.update("INSERT INTO os_field_dispatch(task_id,technician_id,starts,ends,location,status,reason,revision) VALUES(?,?,?,?,?,'assigned','',1) ON CONFLICT(task_id) DO UPDATE SET technician_id=excluded.technician_id,starts=excluded.starts,ends=excluded.ends,location=excluded.location,status='assigned',reason='',revision=os_field_dispatch.revision+1",taskId,technician,start,end,location);
        }else{
            if(!Set.of("assigned","accepted").contains(status))throw new Failure(409,"This task has no active dispatch");
            if(!operation.equals("release")){
                if(!current.get("technician_id").equals(db.viewerId))throw new Failure(403,"Only the assigned technician can accept or reject");
                if(!status.equals("assigned"))throw new Failure(409,"Only an awaiting assignment can be accepted or rejected");
            }
            if(accept){FieldAvailability.eligible(db,table,id,task,db.viewerId,((Number)current.get("starts")).longValue(),((Number)current.get("ends")).longValue());if(((Number)current.get("ends")).longValue()<=System.currentTimeMillis())throw new Failure(409,"The visit interval has ended; request a new dispatch");}
            db.update("UPDATE os_field_dispatch SET status=?,reason=?,revision=revision+1 WHERE task_id=?",accept?"accepted":operation.equals("reject")?"rejected":"released",reason,taskId);
        }
        if(accept)try{
            db.fieldDispatchCommand=true;
            WorkTasks.command(db,table,id,map("operation","claim","revision",task.get("revision"),"record_revision",parent.get("revision"),"task_id",taskId));
        }finally{db.fieldDispatchCommand=false;}
        else{
            db.update("UPDATE os_case_task SET state='queued',assignee_id='',revision=revision+1 WHERE task_id=?",taskId);db.update("UPDATE os_task_plan SET revision=revision+1 WHERE parent_id=?",id);
            Service.update(db,table,parent,map(),actor,db.viewerRole,body.get("record_revision").toString());
        }
        Service.audit(db,parent,actor,"field.dispatch_"+operation,map("task_id",taskId,"dispatch",current(db,taskId)));
        return page(db,table,id,taskId);
    }

    static void guard(Store.Db db,String task,String operation) throws SQLException {
        String status=current(db,task).get("status").toString();
        if(!db.fieldDispatchCommand && Set.of("assigned","accepted").contains(status) && !operation.equals("waive") && !(status.equals("accepted") && Set.of("complete","cancel").contains(operation)))throw new Failure(409,"Use the field dispatch workbench before changing this assigned task");
    }

    static void finished(Store.Db db,String task,String state) throws SQLException {
        if(Set.of("complete","cancelled").contains(state))db.update("UPDATE os_field_dispatch SET status=?,revision=revision+1 WHERE task_id=? AND status='accepted'",state,task);
    }

    static void parentGuard(Store.Db db,Map<String,Object> parent) throws SQLException {
        if(WorkTasks.closed(db,parent,WorkTasks.plan(db,parent.get("sys_id").toString())) && db.count("SELECT COUNT(*) FROM os_field_dispatch d JOIN os_case_task t ON t.task_id=d.task_id WHERE t.parent_id=? AND d.status IN ('assigned','accepted')",parent.get("sys_id"))>0)throw new Failure(409,"Finish or release active field visits before closing their parent");
    }

    static Map<String,Object> queue(Store.Db db,Map<String,String> args) throws SQLException {
        if(!Set.of("admin","agent").contains(db.viewerRole))throw new Failure(403,"Field staff access required");
        require(Set.of("limit","offset").containsAll(args.keySet()),"Use limit and offset only");int limit=Service.number(args.getOrDefault("limit","20"),1,100),offset=Service.number(args.getOrDefault("offset","0"),0,Integer.MAX_VALUE);
        var parameters=new ArrayList<Object>();String filter=RecordAccess.filter(db,"r.sys_id",parameters);var readable=new ArrayList<String>();
        for(var row:db.all("SELECT name,definition FROM os_schema")){var definition=object(row.get("definition"));if(RecordPolicy.allows(definition,"read",db.viewerRole) && FieldAccess.allowed(db,definition,"description") && FieldAccess.allowed(db,definition,"state"))readable.add(row.get("name").toString());}
        parameters.addAll(readable);String from=" FROM os_field_dispatch d JOIN os_case_task t ON t.task_id=d.task_id JOIN os_record r ON r.sys_id=t.parent_id WHERE "+filter+" AND r.\"table\" IN (''"+String.join("",Collections.nCopies(readable.size(),",?"))+")";
        if(!"admin".equals(db.viewerRole)){from+=" AND EXISTS (SELECT 1 FROM os_group_member m JOIN os_record g ON g.sys_id=m.group_id AND g.\"table\"='sys_user_group' WHERE m.group_id=t.group_id AND m.user_id=?)";parameters.add(db.viewerId);}
        long total=db.count("SELECT COUNT(*)"+from,parameters.toArray());parameters.add(limit);parameters.add(offset);
        var rows=db.all("SELECT d.task_id,d.status,d.location,t.summary,r.sys_id AS parent_id,r.\"table\" AS parent_table"+from+" ORDER BY r.seq,t.ordinal LIMIT ? OFFSET ?",parameters.toArray());
        return map("items",rows,"total",total,"limit",limit,"offset",offset,"next_offset",(long)offset+limit<total?(long)offset+limit:null);
    }
}
