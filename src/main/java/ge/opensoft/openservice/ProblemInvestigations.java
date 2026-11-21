package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;

/** Staff problem analysis and live, permission-filtered incident associations. */
final class ProblemInvestigations {
    private ProblemInvestigations() {}

    static Map<String,Object> record(Store.Db db,String table,String id) throws SQLException {
        if(!Set.of("admin","agent").contains(db.viewerRole))throw new Failure(403,"Staff access required");
        var item=Service.readRecord(db,table,id);
        if(!FieldAccess.allowed(db,Service.schema(db,table),"description"))throw new Failure(403,"Description access is required for investigation");
        return item;
    }

    static Map<String,Object> analysis(Store.Db db,String id) throws SQLException {
        var row=db.one("SELECT revision,cause,workaround,published FROM os_problem_analysis WHERE problem_id=?",id);
        return row==null?map("revision",0,"cause","","workaround","","published",0):row;
    }

    static Map<String,Object> incident(Store.Db db,String id) throws SQLException {
        var source=record(db,"incident",id);var link=db.one("SELECT problem_id FROM os_problem_link WHERE incident_id=?",id);
        Map<String,Object> problem=null;String workaround="";
        if(link!=null){
            try{
                var related=record(db,"problem",link.get("problem_id").toString());
                problem=FieldAccess.serialize(db,related);var notes=analysis(db,related.get("sys_id").toString());
                if(((Number)notes.get("published")).intValue()==1)workaround=notes.get("workaround").toString();
            }catch(Failure inaccessible){ /* The linked problem's current authority may have changed. */ }
        }
        return map("incident",FieldAccess.serialize(db,source),"problem",problem,"workaround",workaround,"has_link",link!=null);
    }

    static Map<String,Object> problem(Store.Db db,String id) throws SQLException {
        var source=record(db,"problem",id);var incidents=new ArrayList<Map<String,Object>>();
        if(RecordPolicy.allows(Service.schema(db,"incident"),"read",db.viewerRole)){
            var parameters=new ArrayList<Object>();String filter=RecordAccess.filter(db,"l.incident_id",parameters);parameters.add(id);
            for(var row:db.all("SELECT l.incident_id FROM os_problem_link l JOIN os_record r ON r.sys_id=l.incident_id WHERE "+filter+" AND l.problem_id=? ORDER BY l.incident_id LIMIT 100",parameters.toArray()))
                incidents.add(FieldAccess.serialize(db,Service.readRecord(db,"incident",row.get("incident_id").toString())));
        }
        var view=map("problem",FieldAccess.serialize(db,source),"analysis",analysis(db,id),"incidents",incidents);
        var fix=object(EnterpriseData.get(db,"problem-fix-current",id).get("definition"));
        if(!fix.isEmpty() && "admin".equals(db.viewerRole)){view.put("permanent_fix",fix);view.put("verification",EnterpriseData.get(db,"fix-verification",fix.get("attempt").toString()));}
        return view;
    }

    static void revision(Object expected,Object current) {
        require(expected instanceof Integer && ((Integer)expected)>=0,"Use the nonnegative integer revision");
        if(((Number)expected).longValue()!=((Number)current).longValue())throw new Failure(409,"Investigation or record changed; reload before saving");
    }

    static Map<String,Object> link(Store.Db db,String id,Object input) throws SQLException {
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        var source=record(db,"incident",id);var body=object(input);String operation=WorkJobs.text(body.get("operation"),10);
        require(Set.of("create","link","unlink").contains(operation),"Choose create, link or unlink");
        var keys=new HashSet<>(Set.of("operation","revision"));
        if(operation.equals("create"))keys.addAll(Set.of("summary","details"));
        if(operation.equals("link"))keys.add("problem_id");
        require(body.keySet().equals(keys),"Provide only the selected operation fields");revision(body.get("revision"),source.get("revision"));
        RecordPolicy.require(Service.schema(db,"incident"),"update",db.viewerRole);
        String actor=Service.account(db,db.viewerId).get("name").toString();
        if(operation.equals("unlink"))db.update("DELETE FROM os_problem_link WHERE incident_id=?",id);
        else{
            Map<String,Object> target;
            if(operation.equals("create")){
                require(db.one("SELECT problem_id FROM os_problem_link WHERE incident_id=?",id)==null,"Unlink the current problem before creating another");
                target=Service.create(db,"problem",map("short_description",WorkJobs.text(body.get("summary"),160),"description",WorkJobs.text(body.get("details"),4000)),actor);
                var access=RecordAccess.status(db,id);
                db.update("INSERT INTO os_record_access(record_id,visibility,owner_id,group_id) VALUES(?,?,?,?)",target.get("sys_id"),access.get("visibility"),access.get("owner_id"),access.get("group_id"));
            }else target=record(db,"problem",WorkJobs.text(body.get("problem_id"),32));
            RecordPolicy.require(Service.schema(db,"problem"),"update",db.viewerRole);
            String problem=target.get("sys_id").toString();
            require(db.count("SELECT COUNT(*) FROM os_problem_link WHERE problem_id=? AND incident_id<>?",problem,id)<100,"A problem supports at most 100 linked incidents");
            db.update("INSERT INTO os_problem_link(incident_id,problem_id) VALUES(?,?) ON CONFLICT(incident_id) DO UPDATE SET problem_id=excluded.problem_id",id,problem);
        }
        Service.update(db,"incident",source,map(),actor,db.viewerRole,body.get("revision").toString());
        Service.audit(db,source,actor,"problem."+operation,map());return incident(db,id);
    }

    static Map<String,Object> save(Store.Db db,String id,Object input) throws SQLException {
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        var source=record(db,"problem",id);var current=analysis(db,id);var body=object(input);
        require(body.keySet().equals(Set.of("revision","record_revision","cause","workaround","publish")),"Provide the reviewed analysis fields");
        revision(body.get("revision"),current.get("revision"));revision(body.get("record_revision"),source.get("revision"));
        require(body.get("cause") instanceof String && ((String)body.get("cause")).length()<=4000,"Cause must be text of at most 4000 characters");
        require(body.get("workaround") instanceof String && ((String)body.get("workaround")).length()<=4000,"Workaround must be text of at most 4000 characters");
        require(body.get("publish") instanceof Boolean,"Choose draft or published");
        String cause=body.get("cause").toString().strip(),workaround=body.get("workaround").toString().strip();boolean publish=(Boolean)body.get("publish");
        require(!publish || (!cause.isEmpty() && !workaround.isEmpty()),"Publishing requires a cause and workaround");
        RecordPolicy.require(Service.schema(db,"problem"),"update",db.viewerRole);
        int next=((Number)current.get("revision")).intValue()+1;
        db.update("INSERT INTO os_problem_analysis(problem_id,revision,cause,workaround,published) VALUES(?,?,?,?,?) ON CONFLICT(problem_id) DO UPDATE SET revision=excluded.revision,cause=excluded.cause,workaround=excluded.workaround,published=excluded.published",id,next,cause,workaround,publish?1:0);
        Service.audit(db,source,Service.account(db,db.viewerId).get("name").toString(),"problem.analysis_saved",map("revision",next,"published",publish));return problem(db,id);
    }
}
