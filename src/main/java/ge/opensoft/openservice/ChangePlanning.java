package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;

/** Reviewed planning for enrolled changes; ordinary approvals remain independent. */
final class ChangePlanning {
    private ChangePlanning() {}

    static Map<String,Object> record(Store.Db db,String id) throws SQLException {
        var item=ProblemInvestigations.record(db,"change_request",id);
        for(String field:List.of("state","risk","implementation_plan"))
            if(!FieldAccess.allowed(db,Service.schema(db,"change_request"),field))throw new Failure(403,"Change planning field access required");
        return item;
    }

    static Map<String,Object> plan(Store.Db db,String id) throws SQLException {
        var row=db.one("SELECT revision,detail FROM os_change_plan WHERE change_id=?",id);
        return row==null?map("revision",0,"detail",map("implementation","","impact","","backout","","test","","impact_score",1,"likelihood",1,"score",1,"risk","low","ready",false,"snapshot",map())):map("revision",row.get("revision"),"detail",object(row.get("detail")));
    }

    static Map<String,Object> snapshot(Store.Db db,Map<String,Object> item) throws SQLException {
        var data=new TreeMap<>(object(item.get("data")));data.remove("state");
        var snapshot=map("data",data,"schema_version",Approvals.version(Service.schema(db,"change_request")));var schedule=ChangeScheduling.schedule(db,item.get("sys_id").toString());if(((Number)schedule.get("revision")).intValue()>0)snapshot.put("schedule",map("ci_id",schedule.get("ci_id"),"starts",schedule.get("starts").toString(),"ends",schedule.get("ends").toString(),"revision",schedule.get("revision").toString()));return snapshot;
    }

    static Map<String,Object> detail(Store.Db db,String id) throws SQLException {
        var item=record(db,id);var plan=plan(db,id);var notes=object(plan.get("detail"));
        boolean current=Boolean.TRUE.equals(notes.get("ready")) && object(notes.get("snapshot")).equals(snapshot(db,item));
        var shown=new LinkedHashMap<>(notes);shown.remove("snapshot");
        return map("change",FieldAccess.serialize(db,item),"revision",plan.get("revision"),"plan",shown,"current",current,"editable",Set.of("draft","rejected").contains(object(item.get("data")).get("state")),"can_rework","admin".equals(db.viewerRole) && reworkAllowed(db,item,"rejected"));
    }

    static Map<String,Object> save(Store.Db db,String id,Object input) throws SQLException {
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        var item=record(db,id);var body=object(input);var existing=plan(db,id);
        require(body.keySet().equals(Set.of("revision","record_revision","implementation","impact","backout","test","impact_score","likelihood","ready")),"Provide only the reviewed change plan fields");
        ProblemInvestigations.revision(body.get("revision"),existing.get("revision"));ProblemInvestigations.revision(body.get("record_revision"),item.get("revision"));
        if(!Set.of("draft","rejected").contains(object(item.get("data")).get("state")))throw new Failure(409,"Revise plans only while the change is draft or rejected");
        require(body.get("ready") instanceof Boolean,"Choose draft or ready");boolean ready=(Boolean)body.get("ready");var notes=new LinkedHashMap<String,Object>();
        for(String key:List.of("implementation","impact","backout","test")){
            Object value=body.get(key);require(value instanceof String && ((String)value).length()<=4000,"Plan fields must be text of at most 4000 characters");String text=value.toString().strip();require(!ready || !text.isEmpty(),"Readiness requires implementation, impact, rollback and test plans");notes.put(key,text);
        }
        for(String key:List.of("impact_score","likelihood")){
            Object value=body.get(key);require(value instanceof Integer && (Integer)value>=1 && (Integer)value<=5,"Risk inputs must be integers from 1 to 5");notes.put(key,value);
        }
        int score=(Integer)notes.get("impact_score")*(Integer)notes.get("likelihood");String risk=score<6?"low":score<15?"medium":"high";notes.putAll(map("score",score,"risk",risk,"ready",ready));
        String actor=Service.account(db,db.viewerId).get("name").toString();Service.update(db,"change_request",item,map("implementation_plan",notes.get("implementation"),"risk",risk),actor,db.viewerRole,body.get("record_revision").toString());notes.put("snapshot",snapshot(db,item));
        int next=((Number)existing.get("revision")).intValue()+1;
        db.update("INSERT INTO os_change_plan(change_id,revision,detail) VALUES(?,?,?) ON CONFLICT(change_id) DO UPDATE SET revision=excluded.revision,detail=excluded.detail",id,next,JSON.writeValueAsString(notes));
        Service.audit(db,item,actor,"change.plan_saved",map("revision",next,"ready",ready,"risk",risk,"score",score));return detail(db,id);
    }

    static boolean reworkAllowed(Store.Db db,Map<String,Object> item,Object target) throws SQLException {
        return "change_request".equals(item.get("table")) && "rejected".equals(target)
                && Set.of("review","approved","implement").contains(object(item.get("data")).get("state"))
                && db.one("SELECT change_id FROM os_change_plan WHERE change_id=?",item.get("sys_id"))!=null;
    }

    static void guard(Store.Db db,Map<String,Object> item) throws SQLException {
        if(!"change_request".equals(item.get("table")))return;
        var saved=plan(db,item.get("sys_id").toString());
        if(((Number)saved.get("revision")).intValue()==0 || Set.of("draft","rejected").contains(object(item.get("data")).get("state")))return;
        var notes=object(saved.get("detail"));
        if(!Boolean.TRUE.equals(notes.get("ready")) || !object(notes.get("snapshot")).equals(snapshot(db,item)))throw new Failure(409,"Review a complete current change plan before advancing or editing this change");
    }
}
