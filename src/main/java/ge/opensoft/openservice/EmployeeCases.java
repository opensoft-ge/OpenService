package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;

/** Separate employee-visible case content from the HR team's internal workspace. */
final class EmployeeCases {
    private EmployeeCases() {}

    static void insertion(Store.Db db,Map<String,Object> item){if("hr_case".equals(item.get("table")) && !db.employeeIntake)throw new Failure(409,"Create HR cases through employee service intake");}

    static String filter(Store.Db db,String record,List<Object> parameters){
        String scope;
        if(db.employeePortal){parameters.add(db.viewerId);scope="h.user_id=? AND EXISTS(SELECT 1 FROM os_employee e WHERE e.user_id=h.user_id AND e.active=1)";}
        else if("agent".equals(db.viewerRole)){parameters.add(db.viewerId);scope="EXISTS(SELECT 1 FROM os_group_member m JOIN os_record g ON g.sys_id=m.group_id AND g.\"table\"='sys_user_group' WHERE m.user_id=? AND m.group_id=s.group_id)";}
        else scope="1=0";
        return "NOT EXISTS(SELECT 1 FROM os_record hr WHERE hr.sys_id="+record+" AND hr.\"table\"='hr_case' AND NOT EXISTS(SELECT 1 FROM os_hr_case h JOIN os_hr_service s ON s.service_id=h.service_id WHERE h.record_id=hr.sys_id AND "+scope+"))";
    }

    static Map<String,Object> record(Store.Db db,String id,boolean employee)throws SQLException {
        Map<String,Object> record;
        if(employee){
            EmployeeServices.currentEmployee(db);
            if(db.one("SELECT record_id FROM os_hr_case WHERE record_id=? AND user_id=?",id,db.viewerId)==null)throw new Failure(404,"Employee case not found");
            try{db.employeePortal=true;record=Service.readRecord(db,"hr_case",id);}finally{db.employeePortal=false;}
        }else record=WorkTasks.record(db,"hr_case",id);
        var schema=Service.schema(db,"hr_case");if(!FieldAccess.allowed(db,schema,"description") || !FieldAccess.allowed(db,schema,"state"))throw new Failure(403,"Employee case description and state access required");return record;
    }

    static Map<String,Object> publicFields(Map<String,Object> data){var result=new LinkedHashMap<>(data);result.keySet().retainAll(Set.of("sys_id","sys_mod_count","number","short_description","description","state"));return result;}

    static boolean writable(Store.Db db)throws SQLException {
        var rule=object(TypedFields.rules(Service.schema(db,"hr_case")).getOrDefault("description",map()));return !rule.containsKey("write_roles") || ((List<?>)rule.get("write_roles")).contains(db.viewerRole);
    }

    static Map<String,Object> portal(Store.Db db,Map<String,String> args)throws SQLException {
        var employee=EmployeeServices.currentEmployee(db);require(Set.of("limit","offset").containsAll(args.keySet()),"Use limit and offset only");int limit=Service.number(args.getOrDefault("limit","20"),1,100),offset=Service.number(args.getOrDefault("offset","0"),0,Integer.MAX_VALUE);
        var services=db.all("SELECT service_id,name,description FROM os_hr_service WHERE active=1 AND (department='' OR department=?) ORDER BY name,service_id",employee.get("department"));
        try{db.employeePortal=true;var page=Service.page(db,"hr_case",Map.of("sysparm_limit",Integer.toString(limit),"sysparm_offset",Integer.toString(offset)),"");var cases=new ArrayList<Map<String,Object>>();for(var item:page.rows())cases.add(publicFields(item));return map("services",services,"cases",cases,"total",page.total(),"limit",limit,"offset",offset,"next_offset",page.hasNext()?(long)offset+limit:null);}finally{db.employeePortal=false;}
    }

    static Map<String,Object> create(Store.Db db,String serviceId,Object input)throws SQLException {
        EmployeeServices.currentEmployee(db);if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");var body=object(input);require(body.keySet().equals(Set.of("employee_revision","service_revision","summary","description","key")),"Provide reviewed employee/service revisions, summary, description and key");String summary=WorkJobs.text(body.get("summary"),160),description=WorkJobs.text(body.get("description"),4000),key=WorkJobs.text(body.get("key"),32);require(key.matches("[0-9a-f]{32}"),"Use a 32-character intake key");var normalized=new TreeMap<>(body);normalized.put("service_id",serviceId);String fingerprint=DigestUtils.sha256Hex(JSON.writeValueAsString(normalized));
        var previous=db.one("SELECT record_id,fingerprint FROM os_hr_case WHERE user_id=? AND intake_key=?",db.viewerId,key);if(previous!=null){if(!fingerprint.equals(previous.get("fingerprint")))throw new Failure(409,"Intake key already identifies different work");return publicFields(FieldAccess.serialize(db,record(db,previous.get("record_id").toString(),true)));}
        var offer=EmployeeServices.eligible(db,serviceId);var service=object(offer.get("service"));ProblemInvestigations.revision(body.get("employee_revision"),offer.get("employee_revision"));ProblemInvestigations.revision(body.get("service_revision"),service.get("revision"));String actor=Service.account(db,db.viewerId).get("name").toString();Map<String,Object> item;
        try{db.employeeIntake=true;item=Service.create(db,"hr_case",map("short_description",summary,"description",description),actor);}finally{db.employeeIntake=false;}
        db.update("INSERT INTO os_hr_case(record_id,service_id,user_id,employee_revision,service_revision,intake_key,fingerprint,created) VALUES(?,?,?,?,?,?,?,?)",item.get("sys_id"),serviceId,db.viewerId,body.get("employee_revision"),body.get("service_revision"),key,fingerprint,now());db.update("INSERT INTO os_requester_record(record_id,user_id) VALUES(?,?)",item.get("sys_id"),db.viewerId);Service.audit(db,item,actor,"employee.intake",map("service_id",serviceId));return publicFields(FieldAccess.serialize(db,item));
    }

    static Map<String,Object> page(Store.Db db,String id,boolean employee)throws SQLException {
        var item=record(db,id,employee);var data=publicFields(FieldAccess.serialize(db,item));var messages=db.all("SELECT actor_name,body,created FROM os_hr_reply WHERE record_id=? ORDER BY ordinal",id);String state=object(item.get("data")).get("state").toString();
        return map("onboarding",EmployeeOnboarding.progress(db,id),"record",data,"messages",messages,"revision",messages.size(),"states",Service.schema(db,"hr_case").get("states"),"can_publish",!Set.of("closed","cancelled").contains(state) && writable(db) && (employee || RecordPolicy.allows(Service.schema(db,"hr_case"),"update",db.viewerRole)),"employee",employee);
    }

    static Map<String,Object> publish(Store.Db db,String id,boolean employee,Object input)throws SQLException {
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");var item=record(db,id,employee);if(!employee)RecordPolicy.require(Service.schema(db,"hr_case"),"update",db.viewerRole);if(!writable(db))throw new Failure(403,"Employee case description write access required");var body=object(input);require(body.keySet().equals(Set.of("record_revision","revision","key","body")),"Provide reviewed case/message revisions, key and public message");String text=WorkJobs.text(body.get("body"),4000),key=WorkJobs.text(body.get("key"),32);require(key.matches("[0-9a-f]{32}"),"Use a 32-character message key");String fingerprint=DigestUtils.sha256Hex(JSON.writeValueAsString(new TreeMap<>(body)));
        var previous=db.one("SELECT fingerprint FROM os_hr_reply WHERE record_id=? AND actor_id=? AND submission_key=?",id,db.viewerId,key);if(previous!=null){if(!fingerprint.equals(previous.get("fingerprint")))throw new Failure(409,"Message key already identifies different content");return page(db,id,employee);}
        ProblemInvestigations.revision(body.get("record_revision"),item.get("revision"));long count=db.count("SELECT COUNT(*) FROM os_hr_reply WHERE record_id=?",id);ProblemInvestigations.revision(body.get("revision"),(int)count);require(count<200,"At most 200 public messages belong to one HR case");if(Set.of("closed","cancelled").contains(object(item.get("data")).get("state")))throw new Failure(409,"Reopen the case before adding a new public message");String actor=Service.account(db,db.viewerId).get("name").toString(),reply=Values.id();
        db.update("INSERT INTO os_hr_reply(reply_id,record_id,actor_id,actor_name,ordinal,body,created,submission_key,fingerprint) VALUES(?,?,?,?,?,?,?,?,?)",reply,id,db.viewerId,actor,count+1,text,now(),key,fingerprint);Service.audit(db,item,actor,"employee.public_reply",map("reply_id",reply));return page(db,id,employee);
    }
}
