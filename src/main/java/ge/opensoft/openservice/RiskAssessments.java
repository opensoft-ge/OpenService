package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Native risk assessment history with current owner review and treatment gates. */
final class RiskAssessments {
    private RiskAssessments() {}

    static Map<String,Object> record(Store.Db db,String id)throws SQLException {
        var item=WorkTasks.record(db,"risk",id);for(String field:List.of("risk_score","residual_score"))if(!FieldAccess.allowed(db,Service.schema(db,"risk"),field))throw new Failure(403,"Risk score access required");return item;
    }
    static Map<String,Object> latest(Store.Db db,String id)throws SQLException {
        var row=db.one("SELECT revision,detail FROM os_risk_assessment WHERE record_id=? ORDER BY revision DESC LIMIT 1",id);return row==null?map("revision",0,"detail",map()):row;
    }
    static Map<String,Object> snapshot(Store.Db db,Map<String,Object> item)throws SQLException {
        var data=new TreeMap<>(object(item.get("data")));data.remove("state");return map("data",data,"schema_version",Approvals.version(Service.schema(db,"risk")));
    }
    static boolean current(Store.Db db,Map<String,Object> item,Map<String,Object> assessment)throws SQLException {return ((Number)assessment.get("revision")).intValue()>0 && object(object(assessment.get("detail")).get("snapshot")).equals(snapshot(db,item));}
    static boolean writable(Store.Db db)throws SQLException {
        var schema=Service.schema(db,"risk");if(!RecordPolicy.allows(schema,"update",db.viewerRole))return false;for(String field:List.of("description","risk_score","residual_score")){var rule=object(TypedFields.rules(schema).getOrDefault(field,map()));if(rule.containsKey("write_roles") && !((List<?>)rule.get("write_roles")).contains(db.viewerRole))return false;}return true;
    }
    static String today(){return LocalDate.now(ZoneOffset.UTC).toString();}
    static String reviewDate(Object input){String date=WorkJobs.text(input,10);require(date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"),"Use a review date in YYYY-MM-DD form");try{LocalDate.parse(date);}catch(DateTimeParseException invalid){throw new Failure(400,"Choose a valid review date");}require(date.compareTo(today())>=0,"Choose a review date today or later");return date;}

    static Map<String,Object> page(Store.Db db,String id)throws SQLException {
        var item=record(db,id);var latest=latest(db,id);var history=new ArrayList<Map<String,Object>>();for(var row:db.all("SELECT revision,detail FROM os_risk_assessment WHERE record_id=? ORDER BY revision DESC",id)){var detail=new LinkedHashMap<>(object(row.get("detail")));detail.remove("snapshot");detail.put("owner",db.one("SELECT COALESCE((SELECT name FROM os_user WHERE user_id=?),'Removed owner') AS name",detail.get("owner_id")).get("name"));row.put("detail",detail);history.add(row);}var detail=history.isEmpty()?map():object(history.getFirst().get("detail"));return map("responses",List.of("accept","mitigate","avoid","transfer"),"record",FieldAccess.serialize(db,item),"revision",latest.get("revision"),"assessment",detail,"history",history,"current",current(db,item,latest),"overdue",!detail.isEmpty() && detail.get("review_on").toString().compareTo(today())<0,"can_edit",Set.of("draft","assess").contains(object(item.get("data")).get("state")) && writable(db));
    }

    static Map<String,Object> owner(Store.Db db,String id,String name)throws SQLException {
        var owner=db.one("SELECT user_id,role FROM os_user WHERE name=? AND active=1 AND password_reset_required=0 AND role IN ('admin','agent')",name);require(owner!=null,"Choose a current usable staff risk owner");require(!owner.get("user_id").equals(db.viewerId),"Choose a risk owner different from the assessor");String originalId=db.viewerId,originalRole=db.viewerRole;
        try{db.viewerId=owner.get("user_id").toString();db.viewerRole=owner.get("role").toString();record(db,id);var schema=Service.schema(db,"risk");RecordPolicy.require(schema,"update",db.viewerRole);FieldAccess.writing(db,schema,map("state","monitor"));TypedFields.writing(schema,map(),map("state","monitor"),db.viewerRole);TypedFields.transition(schema,"monitor",db.viewerRole);}catch(Failure denied){throw new Failure(400,"The owner must currently read and authorize this risk");}finally{db.viewerId=originalId;db.viewerRole=originalRole;}return owner;
    }

    static Map<String,Object> save(Store.Db db,String id,Object input)throws SQLException {
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");var item=record(db,id);if(!writable(db))throw new Failure(403,"Risk assessment write access required");if(!Set.of("draft","assess").contains(object(item.get("data")).get("state")))throw new Failure(409,"Return the risk to assessment before revising it");var body=object(input);require(body.keySet().equals(Set.of("revision","record_revision","likelihood","impact","residual_likelihood","residual_impact","response","rationale","treatment","owner","review_on")),"Provide the reviewed assessment fields only");var latest=latest(db,id);ProblemInvestigations.revision(body.get("revision"),latest.get("revision"));ProblemInvestigations.revision(body.get("record_revision"),item.get("revision"));int revision=((Number)latest.get("revision")).intValue()+1;require(revision<=100,"A risk supports at most 100 retained assessments");var detail=new LinkedHashMap<String,Object>();for(String field:List.of("likelihood","impact","residual_likelihood","residual_impact")){Object value=body.get(field);require(value instanceof Integer n && n>=1 && n<=5,"Risk inputs must be integers from 1 to 5");detail.put(field,value);}String response=WorkJobs.text(body.get("response"),10);require(Set.of("accept","mitigate","avoid","transfer").contains(response),"Choose a supported risk response");var owner=owner(db,id,WorkJobs.text(body.get("owner"),80));int score=(Integer)detail.get("likelihood")*(Integer)detail.get("impact"),residual=(Integer)detail.get("residual_likelihood")*(Integer)detail.get("residual_impact");detail.putAll(map("response",response,"rationale",WorkJobs.text(body.get("rationale"),4000),"treatment",WorkJobs.text(body.get("treatment"),4000),"review_on",reviewDate(body.get("review_on")),"owner_id",owner.get("user_id"),"assessor_id",db.viewerId,"score",score,"residual_score",residual,"created",now()));String actor=Service.account(db,db.viewerId).get("name").toString();Service.update(db,"risk",item,map("risk_score",Integer.toString(score),"residual_score",Integer.toString(residual)),actor,db.viewerRole,item.get("revision").toString());detail.put("snapshot",snapshot(db,item));db.update("INSERT INTO os_risk_assessment(record_id,revision,detail) VALUES(?,?,?)",id,revision,JSON.writeValueAsString(detail));db.update("INSERT INTO os_task_plan(parent_id,revision,close_at) VALUES(?,1,'monitor') ON CONFLICT(parent_id) DO NOTHING",id);Service.audit(db,item,actor,"risk.assessed",map("assessment_revision",revision,"response",response,"score",score,"residual_score",residual));return page(db,id);
    }

    static Map<String,Object> ready(Store.Db db,Map<String,Object> item,boolean monitoring)throws SQLException {
        String id=item.get("sys_id").toString();var assessment=latest(db,id);if(!current(db,item,assessment))throw new Failure(409,"Record a complete current risk assessment before advancing");var detail=object(assessment.get("detail"));if(db.count("SELECT COUNT(*) FROM os_user WHERE user_id=? AND active=1 AND password_reset_required=0 AND role IN ('admin','agent')",detail.get("owner_id"))!=1)throw new Failure(409,"Reassess with a current usable risk owner");if(monitoring){if(detail.get("review_on").toString().compareTo(today())<0)throw new Failure(409,"Reassess the overdue risk before monitoring");if(db.count("SELECT COUNT(*) FROM os_case_task WHERE parent_id=? AND required=1 AND state<>'complete'",id)>0)throw new Failure(409,"Complete required risk treatment before review");if(!detail.get("response").equals("accept") && db.count("SELECT COUNT(*) FROM os_case_task WHERE parent_id=? AND required=1 AND state='complete'",id)==0)throw new Failure(409,"Non-accept responses require completed required treatment work");}return detail;
    }
    static void reviewable(Store.Db db,String table,Map<String,Object> item,String target)throws SQLException {if(!table.equals("risk") || !target.equals("monitor"))return;var detail=ready(db,item,true);if(!detail.get("owner_id").equals(db.viewerId))throw new Failure(403,"Only the designated risk owner may review monitoring");}
    static void guard(Store.Db db,Map<String,Object> item)throws SQLException {
        if(!"risk".equals(item.get("table")) || Set.of("draft","assess").contains(object(item.get("data")).get("state")))return;String id=item.get("sys_id").toString();var previous=db.one("SELECT * FROM os_record WHERE sys_id=?",id);boolean entering=object(item.get("data")).get("state").equals("monitor") && !object(previous.get("data")).get("state").equals("monitor");ready(db,item,entering);if(entering && ApprovalGate.qualifying(db,previous,Service.schema(db,"risk"),"monitor").isEmpty())throw new Failure(409,"Current designated owner approval is required for monitoring");
    }
    static void insertion(Map<String,Object> item){if("risk".equals(item.get("table")) && !"draft".equals(object(item.get("data")).get("state")))throw new Failure(409,"Import risks in draft, then assess and review them");}
}
