package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;

/** Case-owned visit execution, required checks and retained evidence receipts. */
final class FieldVisits {
    private FieldVisits() {}

    static Map<String,Object> current(Store.Db db,String task)throws SQLException {
        var row=db.one("SELECT * FROM os_field_visit WHERE task_id=?",task);
        if(row==null)return map("revision",0,"phase","unplanned","checklist",new ArrayList<>(),"answers",new ArrayList<>(),"parts",new ArrayList<>(),"evidence",new ArrayList<>(),"notes","","travelled",0L,"arrived",0L,"ended",0L,"evidence_required",1);
        for(String field:List.of("checklist","answers","parts","evidence"))row.put(field,JSON.readValue(row.get(field).toString(),List.class));
        return row;
    }

    static boolean available(Store.Db db,String parent,Map<String,Object> receipt)throws SQLException {
        var file=db.one("SELECT content FROM os_attachment WHERE sys_id=? AND record_id=?",receipt.get("attachment_id"),parent);
        if(file==null || !receipt.get("sha256").equals(DigestUtils.sha256Hex((byte[])file.get("content"))))return false;
        try{AttachmentHolds.requireDownload(db,receipt.get("attachment_id").toString());AttachmentScanning.requireRelease(db,receipt.get("attachment_id").toString());return true;}catch(Failure held){return false;}
    }

    static List<Map<String,Object>> evidence(Store.Db db,String parent,Object input,boolean required)throws SQLException {
        require(input instanceof List<?> && ((List<?>)input).size()<=10,"Choose at most 10 case attachments");var result=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();
        for(Object value:(List<?>)input){String id=WorkJobs.text(value,32);require(id.matches("[0-9a-f]{32}") && seen.add(id),"Choose unique attachment identifiers");var file=db.one("SELECT name,content FROM os_attachment WHERE sys_id=? AND record_id=?",id,parent);if(file==null)throw new Failure(404,"Case evidence attachment not found");AttachmentHolds.requireDownload(db,id);AttachmentScanning.requireRelease(db,id);byte[] content=(byte[])file.get("content");result.add(map("attachment_id",id,"name",file.get("name"),"bytes",content.length,"sha256",DigestUtils.sha256Hex(content)));}
        require(!required || !result.isEmpty(),"Select at least one available evidence attachment");return result;
    }

    static Map<String,Object> page(Store.Db db,String table,String parent,String task,Map<String,String> args)throws SQLException {
        var dispatch=FieldDispatch.page(db,table,parent,task);var visit=current(db,task);var shown=new LinkedHashMap<>(visit);shown.remove("parts");shown.remove("evidence");var parts=new ArrayList<Map<String,Object>>();
        for(var part:objects(visit.get("parts"))){var item=new LinkedHashMap<>(part);try{var ci=FieldAccess.serialize(db,Service.readRecord(db,"cmdb_ci",part.get("ci_id").toString()));item.put("label",ci.getOrDefault("short_description","Configuration item"));item.put("accessible",true);}catch(Failure hidden){item.remove("ci_id");item.put("notes","");item.put("label","Restricted or unavailable configuration item");item.put("accessible",false);}parts.add(item);}
        var receipts=new ArrayList<Map<String,Object>>();for(var receipt:objects(visit.get("evidence"))){var item=new LinkedHashMap<>(receipt);item.put("available",available(db,parent,receipt));receipts.add(item);}
        Map<String,Object> search;try{search=ReferenceSearch.search(db,"cmdb_ci",args);}catch(Failure denied){if(denied.status!=403)throw denied;search=map("items",List.of(),"total",0,"limit",20,"offset",0,"next_offset",null);}
        for(String field:List.of("travelled","arrived","ended")){long time=((Number)visit.get(field)).longValue();shown.put(field+"_utc",time==0?"Not recorded":Instant.ofEpochMilli(time).toString());}
        return map("work",dispatch,"visit",shown,"parts",parts,"evidence",receipts,"search",search,"attachments",db.all("SELECT sys_id,name,quarantined FROM os_attachment WHERE record_id=? ORDER BY name,sys_id LIMIT 100",parent),"owned",object(dispatch.get("dispatch")).get("status").equals("accepted") && object(dispatch.get("task")).get("assignee_id").equals(db.viewerId));
    }

    static Map<String,Object> command(Store.Db db,String table,String parent,String taskId,Object input)throws SQLException {
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        var record=WorkTasks.record(db,table,parent);EmployeeOnboarding.taskGuard(db,parent,"dispatch");var task=WorkTasks.task(db,parent,taskId);FieldDispatch.team(db,task);RecordPolicy.require(Service.schema(db,table),"update",db.viewerRole);var visit=current(db,taskId);var body=object(input);String operation=WorkJobs.text(body.get("operation"),10),phase=visit.get("phase").toString();
        require(Set.of("configure","travel","checkin","addpart","removepart","complete","incomplete","abort").contains(operation),"Choose a field visit operation");
        var keys=new HashSet<>(Set.of("operation","revision","task_revision","record_revision"));switch(operation){case "configure"->keys.addAll(Set.of("checklist","evidence_required"));case "addpart"->keys.addAll(Set.of("ci_id","quantity","direction","notes"));case "removepart"->keys.add("part_id");case "complete"->keys.addAll(Set.of("notes","answers","evidence","parts_reviewed"));case "incomplete","abort"->keys.add("notes");default->{ /* travel/checkin */ }}
        require(body.keySet().equals(keys),"Provide only the selected visit fields");ProblemInvestigations.revision(body.get("revision"),visit.get("revision"));ProblemInvestigations.revision(body.get("task_revision"),task.get("revision"));ProblemInvestigations.revision(body.get("record_revision"),record.get("revision"));
        if(WorkTasks.closed(db,record,WorkTasks.plan(db,parent)) || Set.of("complete","cancelled").contains(task.get("state")))throw new Failure(409,"Only unfinished work on an open case can change its visit");
        boolean configure=operation.equals("configure"),abort=operation.equals("abort"),finish=Set.of("complete","incomplete","abort").contains(operation);long time=System.currentTimeMillis();
        if(abort)Service.requireAccountAdministrator(db,db.viewerId);
        else if(!configure){var dispatch=FieldDispatch.current(db,taskId);if(!dispatch.get("status").equals("accepted") || !dispatch.get("technician_id").equals(db.viewerId) || !task.get("assignee_id").equals(db.viewerId))throw new Failure(403,"Only the current accepted technician can execute this visit");}
        if(configure){
            require(Set.of("unplanned","planned").contains(phase),"The checklist is fixed once the visit starts");require(body.get("checklist") instanceof List<?> && !((List<?>)body.get("checklist")).isEmpty() && ((List<?>)body.get("checklist")).size()<=20,"Provide 1 to 20 required checks");var checks=new ArrayList<String>();for(Object value:(List<?>)body.get("checklist")){String text=WorkJobs.text(value,160);require(!checks.contains(text),"Required checks must be unique");checks.add(text);}require(body.get("evidence_required") instanceof Boolean,"Choose whether evidence is required");visit.putAll(map("phase","planned","checklist",checks,"evidence_required",(Boolean)body.get("evidence_required")?1:0));
        }else if(operation.equals("travel")){require(phase.equals("planned"),"Start travel from a planned visit");visit.putAll(map("phase","travelling","travelled",time));}
        else if(operation.equals("checkin")){require(Set.of("planned","travelling").contains(phase),"Check in from a planned or travelling visit");visit.putAll(map("phase","onsite","arrived",Math.max(time,((Number)visit.get("travelled")).longValue())));}
        else if(operation.equals("addpart")){
            require(phase.equals("onsite"),"Record part usage while onsite");var parts=objects(visit.get("parts"));require(parts.size()<20,"At most 20 part-usage entries belong to a visit");String ci=WorkJobs.text(body.get("ci_id"),32),direction=WorkJobs.text(body.get("direction"),7),notes=WorkJobs.text(body.get("notes"),1000);Service.readRecord(db,"cmdb_ci",ci);require(Set.of("used","removed").contains(direction),"Choose used or removed");require(body.get("quantity") instanceof Integer count && count>=1 && count<=10000,"Use an integer quantity from 1 to 10000");for(var part:parts)require(!(part.get("ci_id").equals(ci) && part.get("direction").equals(direction)),"This item and usage direction are already recorded");parts.add(map("part_id",Values.id(),"ci_id",ci,"direction",direction,"quantity",body.get("quantity"),"notes",notes));visit.put("parts",parts);
        }else if(operation.equals("removepart")){require(phase.equals("onsite"),"Revise part usage while onsite");String id=WorkJobs.text(body.get("part_id"),32);var parts=objects(visit.get("parts"));boolean removed=parts.removeIf(part->part.get("part_id").equals(id));if(!removed)throw new Failure(404,"Part usage entry not found");visit.put("parts",parts);}
        else{
            require(Set.of("planned","travelling","onsite").contains(phase),"A planned or active visit is required");visit.put("notes",WorkJobs.text(body.get("notes"),4000));
            if(operation.equals("complete")){require(phase.equals("onsite"),"Check in before completing a visit");require(Boolean.TRUE.equals(body.get("parts_reviewed")),"Review reported part usage before completing");int count=((List<?>)visit.get("checklist")).size();require(body.get("answers") instanceof List<?> && ((List<?>)body.get("answers")).size()==count,"Complete every required check");var answers=new HashSet<Integer>();for(Object answer:(List<?>)body.get("answers"))require(answer instanceof Integer n && n>=1 && n<=count && answers.add(n),"Answer each required check exactly once");visit.put("answers",new ArrayList<>(new TreeSet<>(answers)));visit.put("evidence",evidence(db,parent,body.get("evidence"),((Number)visit.get("evidence_required")).intValue()==1));}
            visit.putAll(map("phase",operation.equals("complete")?"complete":"incomplete","ended",Math.max(time,Math.max(((Number)visit.get("travelled")).longValue(),((Number)visit.get("arrived")).longValue()))));
        }
        db.update("INSERT INTO os_field_visit(task_id,revision,phase,checklist,answers,parts,evidence,notes,travelled,arrived,ended,evidence_required) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(task_id) DO UPDATE SET revision=excluded.revision,phase=excluded.phase,checklist=excluded.checklist,answers=excluded.answers,parts=excluded.parts,evidence=excluded.evidence,notes=excluded.notes,travelled=excluded.travelled,arrived=excluded.arrived,ended=excluded.ended,evidence_required=excluded.evidence_required",taskId,((Number)visit.get("revision")).intValue()+1,visit.get("phase"),JSON.writeValueAsString(visit.get("checklist")),JSON.writeValueAsString(visit.get("answers")),JSON.writeValueAsString(visit.get("parts")),JSON.writeValueAsString(visit.get("evidence")),visit.get("notes"),visit.get("travelled"),visit.get("arrived"),visit.get("ended"),visit.get("evidence_required"));
        String actor=Service.account(db,db.viewerId).get("name").toString();Service.update(db,table,record,map(),actor,db.viewerRole,body.get("record_revision").toString());
        if(finish)try{db.fieldVisitCommand=true;String action=operation.equals("complete")?"complete":"cancel";var command=map("operation",action,"revision",task.get("revision"),"record_revision",record.get("revision"),"task_id",taskId);command.put(action.equals("complete")?"outcome":"reason",visit.get("notes"));WorkTasks.command(db,table,parent,command);}finally{db.fieldVisitCommand=false;}
        Service.audit(db,record,actor,"field.visit_"+operation,map("task_id",taskId,"revision",((Number)visit.get("revision")).intValue()+1,"phase",visit.get("phase")));return page(db,table,parent,taskId,Map.of());
    }

    static void guardTask(Store.Db db,String task,String operation)throws SQLException {
        if(!db.fieldVisitCommand && Set.of("complete","cancel").contains(operation) && ((Number)current(db,task).get("revision")).intValue()>0)throw new Failure(409,"Complete or report an incomplete visit in its field report");
    }

    static void guardDispatch(Store.Db db,String task)throws SQLException {
        if(Set.of("travelling","onsite").contains(current(db,task).get("phase")))throw new Failure(409,"Record an incomplete visit or administrator abort before releasing active field work");
    }
}
