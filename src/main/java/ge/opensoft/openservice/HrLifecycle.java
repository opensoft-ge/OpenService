package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.EnterpriseData.*;
import java.util.*;

/** Explicit cancellation and an employee-visible, approved document manifest. */
final class HrLifecycle {
    private HrLifecycle() {}
    static Map<String,Object> page(Store.Db db,String id)throws Exception {
        var record=EmployeeCases.record(db,id,false);var row=get(db,"hr",id);
        var work=db.all("SELECT ordinal,display_title,status FROM os_hr_onboarding_work WHERE parent_id=? ORDER BY ordinal",id);
        return EnterpriseData.page("Employee lifecycle and documents","Cancellation stops unfinished onboarding. Completed actions require an explicit review; they are never silently undone.",map("record",FieldAccess.serialize(db,record),"lifecycle",row,"onboarding",work,"employment_change",get(db,"employment-change",id)),
            form("schedule_change","Schedule transfer or leave and return",field("type","Change type (transfer or leave)","text","transfer"),field("effective","Effective date","date",""),field("return_on","Return date or none","text","none"),field("steps","Ordered work (phase, group_id, summary, details)","json","[]"),field("revision","Current revision","number",0),field("record_revision","Case revision","number",record.get("revision"))),
            form("move_change","Reschedule remaining employee work",field("effective","Effective date","date",""),field("return_on","Return date or none","text","none"),field("revision","Current revision","number",0),field("reason","Reason for change","text","")),
            form("documents","Publish employee documents",field("revision","Current lifecycle revision","number",row.get("revision")),field("attachments","Selected case attachment IDs","json","[]")),
            form("cancel","Cancel employee lifecycle",field("revision","Current lifecycle revision","number",row.get("revision")),field("record_revision","Current case revision","number",record.get("revision")),field("reason","Cancellation reason","text",""),field("completed_actions","Completed step numbers and compensation review notes","json","{}"),field("confirm","Confirm cancellation","boolean",false)));
    }
    static void command(Store.Db db,String id,Object input)throws Exception {
        lock(db);var record=EmployeeCases.record(db,id,false);RecordPolicy.require(Service.schema(db,"hr_case"),"update",db.viewerRole);
        if(!EmployeeCases.writable(db))throw new Failure(403,"HR description write access required");
        var body=object(input);String op=WorkJobs.text(body.get("operation"),12);var row=get(db,"hr",id);var data=object(row.get("definition"));ProblemInvestigations.revision(body.get("revision"),row.get("revision"));
        if(op.equals("documents")){
            keys(body,"operation","revision","attachments");data.put("documents",FieldVisits.evidence(db,id,body.get("attachments"),false));
        }else{
            require(op.equals("cancel"),"Choose document publication or lifecycle cancellation");keys(body,"operation","revision","record_revision","reason","completed_actions","confirm");Service.requireAccountAdministrator(db,db.viewerId);
            require(Boolean.TRUE.equals(body.get("confirm")),"Confirm cancellation and the reviewed completed effects");ProblemInvestigations.revision(body.get("record_revision"),record.get("revision"));
            require(EmployeeOnboarding.open(record),"Only an open HR case can be cancelled");String reason=WorkJobs.text(body.get("reason"),2000);var completed=object(body.get("completed_actions"));var expected=new HashSet<String>();
            var work=db.all("SELECT * FROM os_hr_onboarding_work WHERE parent_id=? ORDER BY ordinal",id);
            for(var step:work)if(step.get("status").equals("complete")){String key=step.get("ordinal").toString();expected.add(key);WorkJobs.text(completed.get(key),2000);}
            require(completed.keySet().equals(expected),"Review compensation or no-reversal reasons for every completed step only");
            for(var step:work)if(!Set.of("complete","waived").contains(step.get("status"))){
                db.update("UPDATE os_case_task SET state='cancelled',required=0,note=?,revision=revision+1 WHERE task_id=?",reason,step.get("task_id"));
                db.update("UPDATE os_task_plan SET revision=revision+1 WHERE parent_id=?",step.get("child_id"));
                db.update("UPDATE os_hr_onboarding_work SET status='waived',reason=?,updated=? WHERE child_id=?",reason,now(),step.get("child_id"));
            }
            db.update("UPDATE os_hr_onboarding SET revision=revision+1 WHERE parent_id=?",id);
            Service.update(db,"hr_case",record,map("state","cancelled"),actor(db),db.viewerRole,record.get("revision").toString());
            data.put("cancellation",map("reason",reason,"completed_actions",completed,"actor",db.viewerId,"created",now()));
        }
        save(db,"hr",id,row.get("revision"),data);
    }
    static Map<String,Object> employee(Store.Db db,String id)throws Exception {
        EmployeeCases.record(db,id,true);var documents=new ArrayList<Map<String,Object>>();
        for(var receipt:objects(object(get(db,"hr",id).get("definition")).getOrDefault("documents",List.of())))if(FieldVisits.available(db,id,receipt))documents.add(receipt);
        return map("record_id",id,"documents",documents);
    }
    static Map<String,Object> download(Store.Db db,String id,String file)throws Exception {
        var documents=objects(employee(db,id).get("documents"));require(documents.stream().anyMatch(row->row.get("attachment_id").equals(file)),"Employee document is unavailable");
        return db.one("SELECT name,content,content_type FROM os_attachment WHERE sys_id=? AND record_id=?",file,id);
    }
}
