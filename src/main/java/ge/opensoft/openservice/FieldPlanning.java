package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.EnterpriseData.*;
import java.util.*;
import java.time.Instant;

/** Skill requirements are enforced by ordinary dispatch as well as suggestions. */
final class FieldPlanning {
    private FieldPlanning() {}
    static List<String> skills(Object input){require(input instanceof List<?> list && list.size()<=20,"Use at most twenty skill names");var result=new TreeSet<String>();for(Object value:(List<?>)input){String skill=WorkJobs.text(value,60).toLowerCase(Locale.ROOT);require(result.add(skill),"Skills must be unique");}return new ArrayList<>(result);}
    static void eligible(Store.Db db,String task,String user)throws java.sql.SQLException {
        var needed=object(get(db,"field-task",task).get("definition"));var held=object(get(db,"field-user",user).get("definition"));
        require(((List<?>)held.getOrDefault("skills",List.of())).containsAll((List<?>)needed.getOrDefault("skills",List.of())),"Technician lacks required task skills");
    }
    static Map<String,Object> task(Store.Db db,String id)throws Exception {
        var task=db.one("SELECT t.*,r.\"table\" AS parent_table FROM os_case_task t JOIN os_record r ON r.sys_id=t.parent_id WHERE t.task_id=?",id);if(task==null)throw new Failure(404,"Field task not found");
        FieldDispatch.page(db,task.get("parent_table").toString(),task.get("parent_id").toString(),id);return task;
    }
    static Map<String,Object> page(Store.Db db,String id)throws Exception {
        if(id.equals("technicians")){Service.requireAccountAdministrator(db,db.viewerId);return EnterpriseData.page("Technician skills","Maintain current qualifications used by dispatch.",list(db,"field-user"),form("skills","Set technician skills",field("user_id","Technician account ID","text",""),field("revision","Skill revision","number",0),field("skills","Skills","json","[]")));}
        var task=task(db,id);var row=get(db,"field-task",id);return EnterpriseData.page("Find a field appointment","Suggestions honor skills, team membership, record access, calendars and existing reservations. Earliest slots rank first; workload breaks ties.",map("task",task,"requirements",row,"offline_path","/field-worksheet/"+id,"latest_sync",get(db,"offline-task",id),"worksheet_conflicts",db.all("SELECT definition FROM os_enterprise_document WHERE area='offline-conflict' AND subject LIKE ?",id+"%")),
            form("requirements","Set task requirements",field("revision","Requirement revision","number",row.get("revision")),field("skills","Required skills","json","[]")),
            form("suggest","Find available appointments",field("start","Search start (UTC)","text",Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()),field("minutes","Visit duration in minutes","number",60),field("days","Search days","number",3)));
    }
    static Object command(Store.Db db,String id,Object input)throws Exception {
        lock(db);var body=object(input);String operation=WorkJobs.text(body.get("operation"),12);
        if(id.equals("technicians")){Service.requireAccountAdministrator(db,db.viewerId);keys(body,"operation","user_id","revision","skills");require(operation.equals("skills"),"Choose technician skills");String user=EnterpriseData.id(body.get("user_id"));require(db.count("SELECT COUNT(*) FROM os_user WHERE user_id=? AND active=1 AND role='agent'",user)==1,"Choose an active technician");save(db,"field-user",user,body.get("revision"),map("skills",skills(body.get("skills"))));return map("saved",true);}
        var task=task(db,id);String table=task.get("parent_table").toString(),parent=task.get("parent_id").toString();RecordPolicy.require(Service.schema(db,table),"update",db.viewerRole);
        if(operation.equals("requirements")){keys(body,"operation","revision","skills");require(task.get("state").equals("queued") && !Set.of("assigned","accepted").contains(FieldDispatch.current(db,id).get("status")),"Set requirements before dispatch");save(db,"field-task",id,body.get("revision"),map("skills",skills(body.get("skills"))));return map("saved",true);}
        require(operation.equals("suggest"),"Choose requirements or suggestions");keys(body,"operation","start","minutes","days");long start=ChangeScheduling.instant(body.get("start")),duration=integer(body.get("minutes"),15,480)*60000L;int days=integer(body.get("days"),1,14);require(start>=System.currentTimeMillis() && start<=System.currentTimeMillis()+30L*86400000,"Search within the next thirty days");
        var candidates=objects(FieldDispatch.page(db,table,parent,id).get("technicians"));var results=new ArrayList<Map<String,Object>>();
        int examined=0;for(var user:candidates){String uid=user.get("user_id").toString();try{eligible(db,id,uid);}catch(Failure missingSkills){continue;}for(long slot=start;slot+duration<=start+days*86400000L && examined<2000;slot+=1800000L){examined++;try{FieldAvailability.eligible(db,table,parent,WorkTasks.task(db,parent,id),uid,slot,slot+duration);}catch(Failure unavailable){continue;}long load=db.count("SELECT COUNT(*) FROM os_field_dispatch WHERE technician_id=? AND status IN ('assigned','accepted')",uid);results.add(map("technician_id",uid,"technician",user.get("name"),"start",Instant.ofEpochMilli(slot).toString(),"end",Instant.ofEpochMilli(slot+duration).toString(),"reserved_visits",load));break;}}
        results.sort(Comparator.<Map<String,Object>,String>comparing(row->row.get("start").toString()).thenComparingLong(row->((Number)row.get("reserved_visits")).longValue()).thenComparing(row->row.get("technician").toString()));return map("appointments",results,"examined_slots",examined,"search_limit_reached",examined==2000,"dispatch_url","/record/"+table+"/"+parent+"/field-dispatch/"+id);
    }
}
