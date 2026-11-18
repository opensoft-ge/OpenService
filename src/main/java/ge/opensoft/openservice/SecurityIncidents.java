package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;

/** Restricted native investigations, evidence receipts and human response coordination. */
final class SecurityIncidents {
    private SecurityIncidents() {}

    static void staff(Store.Db db) {if(!Set.of("admin","agent").contains(db.viewerRole))throw new Failure(403,"Security staff access required");}
    static void insertion(Store.Db db,Map<String,Object> item) {if("security_incident".equals(item.get("table")) && !db.securityIntake)throw new Failure(409,"Create security incidents through restricted intake");}
    static String filter(Store.Db db,String record,List<Object> parameters) {
        String membership="agent".equals(db.viewerRole)?"m.user_id=?":"1=0";if("agent".equals(db.viewerRole))parameters.add(db.viewerId);
        return "NOT EXISTS(SELECT 1 FROM os_record si WHERE si.sys_id="+record+" AND si.\"table\"='security_incident' AND NOT EXISTS(SELECT 1 FROM os_security_case s JOIN os_security_team t ON t.group_id=s.group_id JOIN os_group_member m ON m.group_id=t.group_id JOIN os_record g ON g.sys_id=t.group_id AND g.\"table\"='sys_user_group' WHERE s.record_id=si.sys_id AND t.active=1 AND "+membership+"))";
    }
    static Map<String,Object> team(Store.Db db,String group)throws SQLException {
        var row=db.one("SELECT group_id,active,revision FROM os_security_team WHERE group_id=?",group);return row==null?map("group_id",group,"active",0,"revision",0):row;
    }
    static void eligible(Store.Db db,String group)throws SQLException {
        staff(db);Service.readRecord(db,"sys_user_group",group);
        if(((Number)team(db,group).get("active")).intValue()!=1 || (!"admin".equals(db.viewerRole) && !Fulfilment.member(db,group)))throw new Failure(403,"Current enrolled security-team membership required");
    }
    static Map<String,Object> directory(Store.Db db)throws SQLException {
        staff(db);var teams=new ArrayList<Map<String,Object>>();
        for(var group:Service.page(db,"sys_user_group",Map.of("sysparm_limit","100"),"").rows()){
            String id=group.get("sys_id").toString();var team=team(db,id);
            if("admin".equals(db.viewerRole) || (((Number)team.get("active")).intValue()==1 && Fulfilment.member(db,id))){team.put("name",group.getOrDefault("short_description","Security team"));teams.add(team);}
        }
        var records=Service.page(db,"security_incident",Map.of("sysparm_limit","100"),"");return map("teams",teams,"records",records.rows(),"total",records.total(),"admin","admin".equals(db.viewerRole));
    }
    static Map<String,Object> enroll(Store.Db db,Object input)throws SQLException {
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        Service.requireAccountAdministrator(db,db.viewerId);var body=object(input);require(body.keySet().equals(Set.of("group_id","revision","active")),"Provide team, revision and active only");String group=WorkJobs.text(body.get("group_id"),32);Service.readRecord(db,"sys_user_group",group);var previous=team(db,group);ProblemInvestigations.revision(body.get("revision"),previous.get("revision"));require(body.get("active") instanceof Boolean,"Choose active or inactive");
        db.update("INSERT INTO os_security_team(group_id,active,revision) VALUES(?,?,?) ON CONFLICT(group_id) DO UPDATE SET active=excluded.active,revision=excluded.revision",group,(Boolean)body.get("active")?1:0,((Number)previous.get("revision")).intValue()+1);SecurityEvents.record(db,db.viewerId,db.viewerId,"security.team_saved",map("group_id",group,"active",body.get("active")));return directory(db);
    }
    static Map<String,Object> intake(Store.Db db,Object input)throws SQLException {
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        var body=object(input);require(body.keySet().equals(Set.of("group_id","summary","description","key")),"Provide team, summary, description and intake key only");String group=WorkJobs.text(body.get("group_id"),32);eligible(db,group);String summary=WorkJobs.text(body.get("summary"),160),description=WorkJobs.text(body.get("description"),4000),key=WorkJobs.text(body.get("key"),32);require(key.matches("[0-9a-f]{32}"),"Use a 32-character intake key");String fingerprint=DigestUtils.sha256Hex(JSON.writeValueAsString(new TreeMap<>(body)));var previous=db.one("SELECT record_id,fingerprint FROM os_security_case WHERE actor_id=? AND intake_key=?",db.viewerId,key);
        if(previous!=null){if(!fingerprint.equals(previous.get("fingerprint")))throw new Failure(409,"Intake key already identifies different work");return FieldAccess.serialize(db,WorkTasks.record(db,"security_incident",previous.get("record_id").toString()));}
        Map<String,Object> item;String actor=Service.account(db,db.viewerId).get("name").toString();
        try{db.securityIntake=true;item=Service.create(db,"security_incident",map("short_description",summary,"description",description),actor);}finally{db.securityIntake=false;}
        require(object(item.get("data")).get("state").equals("analysis"),"Security intake requires the native analysis initial state");String id=item.get("sys_id").toString();db.update("INSERT INTO os_security_case(record_id,group_id,actor_id,intake_key,fingerprint) VALUES(?,?,?,?,?)",id,group,db.viewerId,key,fingerprint);db.update("INSERT INTO os_task_plan(parent_id,revision,close_at) VALUES(?,1,'closed')",id);return FieldAccess.serialize(db,item);
    }
    static Map<String,Object> latest(Store.Db db,String id)throws SQLException {var row=db.one("SELECT revision,detail FROM os_security_document WHERE record_id=? ORDER BY revision DESC LIMIT 1",id);return row==null?map("revision",0,"detail",map()):row;}
    static Map<String,Object> basis(Store.Db db,Map<String,Object> item)throws SQLException {var data=new TreeMap<>(object(item.get("data")));data.remove("state");return map("data",data,"schema_version",Approvals.version(Service.schema(db,"security_incident")),"epoch",db.one("SELECT analysis_epoch FROM os_security_case WHERE record_id=?",item.get("sys_id")).get("analysis_epoch"));}
    static boolean current(Store.Db db,Map<String,Object> item,Map<String,Object> detail)throws SQLException {return detail.containsKey("basis") && detail.get("basis").equals(basis(db,item));}
    static boolean writable(Store.Db db)throws SQLException {var schema=Service.schema(db,"security_incident");var rule=object(TypedFields.rules(schema).getOrDefault("description",map()));return RecordPolicy.allows(schema,"update",db.viewerRole) && (!rule.containsKey("write_roles") || ((List<?>)rule.get("write_roles")).contains(db.viewerRole));}
    static Map<String,Object> projection(Store.Db db,String id,Map<String,Object> detail)throws SQLException {
        var shown=new LinkedHashMap<>(detail);shown.remove("basis");var assets=new ArrayList<Map<String,Object>>();
        for(Object ci:(List<?>)detail.getOrDefault("assets",List.of())){try{assets.add(FieldAccess.serialize(db,Service.readRecord(db,"cmdb_ci",ci.toString())));}catch(Failure hidden){assets.add(map("short_description","Restricted or unavailable configuration item"));}}
        shown.put("assets",assets);var evidence=new ArrayList<Map<String,Object>>();for(var receipt:objects(detail.getOrDefault("evidence",List.of()))){var copy=new LinkedHashMap<>(receipt);copy.put("available",FieldVisits.available(db,id,receipt));evidence.add(copy);}shown.put("evidence",evidence);return shown;
    }
    static Map<String,Object> page(Store.Db db,String id)throws SQLException {
        var item=WorkTasks.record(db,"security_incident",id);var latest=latest(db,id);var detail=object(latest.get("detail"));var history=new ArrayList<Map<String,Object>>();for(var row:db.all("SELECT revision,detail FROM os_security_document WHERE record_id=? ORDER BY revision DESC",id)){row.put("detail",projection(db,id,object(row.get("detail"))));history.add(row);}
        return map("asset_choices",RecordPolicy.allows(Service.schema(db,"cmdb_ci"),"read",db.viewerRole)?Service.page(db,"cmdb_ci",Map.of("sysparm_limit","100"),"").rows():List.of(),"record",FieldAccess.serialize(db,item),"revision",latest.get("revision"),"document",projection(db,id,detail),"current",current(db,item,detail),"history",history,"can_edit",writable(db),"attachments",db.all("SELECT sys_id,name FROM os_attachment WHERE record_id=? ORDER BY name,sys_id LIMIT 100",id));
    }
    static Map<String,Object> document(Store.Db db,String id,Object input)throws SQLException {
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        var item=WorkTasks.record(db,"security_incident",id);if(!writable(db))throw new Failure(403,"Security investigation write access required");var body=object(input);String operation=WorkJobs.text(body.get("operation"),8);boolean analyze=operation.equals("analyze");require(analyze || operation.equals("review"),"Choose analysis or closure review");var keys=new HashSet<>(Set.of("operation","revision","record_revision"));keys.addAll(analyze?Set.of("classification","findings","assets","evidence"):Set.of("code","notes"));require(body.keySet().equals(keys),"Provide only the selected security document fields");var previous=latest(db,id);ProblemInvestigations.revision(body.get("revision"),previous.get("revision"));ProblemInvestigations.revision(body.get("record_revision"),item.get("revision"));int revision=((Number)previous.get("revision")).intValue()+1;require(revision<=100,"At most 100 security document versions are supported");String state=object(item.get("data")).get("state").toString();if(!state.equals(analyze?"analysis":"review"))throw new Failure(409,"Document the corresponding analysis or review phase");Map<String,Object> detail;
        if(analyze){
            String classification=WorkJobs.text(body.get("classification"),14);require(Set.of("confirmed","false_positive").contains(classification),"Choose confirmed or false positive");require(body.get("assets") instanceof List<?> && ((List<?>)body.get("assets")).size()<=20,"Choose at most 20 affected configuration items");var assets=new ArrayList<String>();for(Object value:(List<?>)body.get("assets")){String ci=WorkJobs.text(value,32);require(!assets.contains(ci),"Affected configuration items must be unique");Service.readRecord(db,"cmdb_ci",ci);assets.add(ci);}detail=map("classification",classification,"findings",WorkJobs.text(body.get("findings"),4000),"assets",assets,"evidence",FieldVisits.evidence(db,id,body.get("evidence"),true),"basis",basis(db,item));
        }else{
            detail=new LinkedHashMap<>(ready(db,item,true));String code=WorkJobs.text(body.get("code"),14);require(code.equals(detail.get("classification").equals("confirmed")?"resolved":"false_positive"),"Closure code must match the investigation classification");detail.put("closure",map("code",code,"notes",WorkJobs.text(body.get("notes"),4000),"author_id",db.viewerId));
        }
        detail.put("author_id",db.viewerId);detail.put("created",now());db.update("INSERT INTO os_security_document(record_id,revision,detail) VALUES(?,?,?)",id,revision,JSON.writeValueAsString(detail));String actor=Service.account(db,db.viewerId).get("name").toString();Service.update(db,"security_incident",item,map(),actor,db.viewerRole,item.get("revision").toString());Service.audit(db,item,actor,"security."+operation,map("document_revision",revision));return page(db,id);
    }
    static Map<String,Object> ready(Store.Db db,Map<String,Object> item,boolean treatment)throws SQLException {
        String id=item.get("sys_id").toString();var detail=object(latest(db,id).get("detail"));if(!current(db,item,detail))throw new Failure(409,"Record a current security investigation before advancing");for(var receipt:objects(detail.get("evidence")))if(!FieldVisits.available(db,id,receipt))throw new Failure(409,"Investigation evidence must remain available and unchanged");
        if(treatment && detail.get("classification").equals("confirmed")){if(db.count("SELECT COUNT(*) FROM os_case_task WHERE parent_id=? AND required=1 AND state='complete'",id)==0 || db.count("SELECT COUNT(*) FROM os_case_task WHERE parent_id=? AND required=1 AND state<>'complete'",id)>0)throw new Failure(409,"Complete required containment work before review");}return detail;
    }
    static void guard(Store.Db db,Map<String,Object> item)throws SQLException {
        if(!"security_incident".equals(item.get("table")))return;String state=object(item.get("data")).get("state").toString();if(state.equals("analysis")){var prior=db.one("SELECT * FROM os_record WHERE sys_id=?",item.get("sys_id"));if(!object(prior.get("data")).get("state").equals("analysis"))db.update("UPDATE os_security_case SET analysis_epoch=analysis_epoch+1 WHERE record_id=?",item.get("sys_id"));return;}var detail=ready(db,item,!state.equals("contain"));if(state.equals("contain") && !detail.get("classification").equals("confirmed"))throw new Failure(409,"False positives proceed directly to review");
        if(state.equals("closed")){String id=item.get("sys_id").toString();if(!detail.containsKey("closure") || db.count("SELECT COUNT(*) FROM os_case_task WHERE parent_id=? AND state NOT IN ('complete','cancelled')",id)>0)throw new Failure(409,"Record closure review and finish or cancel all response tasks");var previous=db.one("SELECT * FROM os_record WHERE sys_id=?",id);if(!object(previous.get("data")).get("state").equals("closed") && ApprovalGate.qualifying(db,previous,Service.schema(db,"security_incident"),"closed").isEmpty())throw new Failure(409,"Current peer approval is required for security closure");}
    }
}
