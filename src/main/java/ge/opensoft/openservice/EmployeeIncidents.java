package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
import java.util.*;

/** A reporter receipt grants only a public incident view, never generic record access. */
final class EmployeeIncidents {
    private EmployeeIncidents() {}

    static Map<String,Object> create(Store.Db db,String actor,Map<String,Object> input) throws SQLException {
        TypedFields.require(input.keySet().equals(Set.of("short_description","description","key")),
                "Supply a summary, details and submission key");
        String summary=WorkJobs.text(input.get("short_description"),200);
        String details=Objects.toString(input.get("description"),"");
        TypedFields.require(input.get("description") instanceof String && details.length()<=8000,
                "Details must be text of at most 8000 characters");
        String key=JourneyForms.id(input,"key");
        var receipt=db.one("SELECT incident_id,summary,details FROM os_reported_incident WHERE reporter=? AND submission_key=?",db.viewerId,key);
        if(receipt!=null) {
            if(!summary.equals(receipt.get("summary")) || !details.equals(receipt.get("details")))
                throw new Failure(409,"This submission key already belongs to another report");
            return detail(db,receipt.get("incident_id").toString());
        }
        var desk=StarterDesk.settings(db);
        if(desk==null) throw new Failure(409,"An administrator must configure the service desk");
        String group=desk.get("group_id").toString();
        CatalogueRouting.group(db,group);
        var record=Service.create(db,"incident",map("short_description",summary,"description",details,
                "assignment_group",group),actor);
        String id=record.get("sys_id").toString();
        db.update("INSERT INTO os_reported_incident(incident_id,reporter,submission_key,summary,details) VALUES(?,?,?,?,?)",
                id,db.viewerId,key,summary,details);
        return detail(db,id);
    }

    static List<Map<String,Object>> mine(Store.Db db) throws SQLException {
        RecordPolicy.read(db,Service.schema(db,"incident"));
        var parameters=new ArrayList<Object>(List.of(db.viewerId));
        String permitted=RecordAccess.filter(db,"i.sys_id",parameters,true);
        var rows=db.all("SELECT r.incident_id,r.summary FROM os_reported_incident r JOIN os_record i ON i.sys_id=r.incident_id LEFT JOIN os_record_access a ON a.record_id=r.incident_id WHERE r.reporter=? AND COALESCE(a.visibility,'workspace')='workspace' AND "+permitted+" ORDER BY i.seq DESC LIMIT 100",parameters.toArray());
        if (!FieldAccess.allowed(db,Service.schema(db,"incident"),"short_description"))
            rows.forEach(row -> row.put("summary","Reported problem"));
        return rows;
    }

    static Map<String,Object> detail(Store.Db db,String id) throws SQLException {
        var definition=Service.schema(db,"incident");
        RecordPolicy.read(db,definition);
        var parameters=new ArrayList<Object>(List.of(id));
        String permitted=RecordAccess.filter(db,"i.sys_id",parameters,true);
        var receipt=db.one("SELECT r.reporter,r.summary,r.details,i.data FROM os_reported_incident r JOIN os_record i ON i.sys_id=r.incident_id WHERE r.incident_id=? AND "+permitted,parameters.toArray());
        if(receipt==null) throw new Failure(404,"Report not found");
        if("requester".equals(db.viewerRole)) {
            if(!"workspace".equals(RecordAccess.status(db,id).get("visibility")))
                throw new Failure(404,"Report not found");
        }
        var data=object(receipt.get("data"));
        var states=object(definition.get("states"));
        boolean comments=RecordCollaboration.allowed(db,"incident","comments");
        var replies=comments ? db.all("SELECT created,actor,detail FROM (SELECT seq,created,actor,detail FROM os_event WHERE record_id=? AND action='comment' ORDER BY seq DESC LIMIT 100) recent ORDER BY seq",id) : List.<Map<String,Object>>of();
        var publicReplies=new ArrayList<Map<String,Object>>();
        for(var reply:replies)
            publicReplies.add(map("at",reply.get("created"),"author",reply.get("actor"),"body",object(reply.get("detail")).get("body")));
        return map("incident_id",id,"number",data.get("number"),"summary",FieldAccess.allowed(db,definition,"short_description") ? receipt.get("summary") : "Reported problem",
                "details",FieldAccess.allowed(db,definition,"description") ? receipt.get("details") : "","status",FieldAccess.allowed(db,definition,"state")
                        ? states.getOrDefault(data.get("state"),"Unavailable") : "Unavailable",
                "replies",publicReplies,"comments_allowed",comments);
    }

    static void reply(Store.Db db,String id,String actor,Map<String,Object> input) throws SQLException {
        var page=detail(db,id);
        if(!Boolean.TRUE.equals(page.get("comments_allowed"))) throw new Failure(403,"Public comments are restricted");
        if(!"requester".equals(db.viewerRole)) RecordPolicy.require(Service.schema(db,"incident"),"update",db.viewerRole);
        TypedFields.require(input.keySet().equals(Set.of("body","key")),"Supply a public reply and submission key");
        String body=WorkJobs.text(input.get("body"),4000);
        String key=JourneyForms.id(input,"key");
        var previous=db.one("SELECT body FROM os_report_reply WHERE incident_id=? AND author=? AND submission_key=?",id,db.viewerId,key);
        if(previous!=null) {
            if(!body.equals(previous.get("body"))) throw new Failure(409,"This reply key was already used");
            return;
        }
        db.update("INSERT INTO os_report_reply(incident_id,author,submission_key,body) VALUES(?,?,?,?)",id,db.viewerId,key,body);
        Service.audit(db,map("sys_id",id,"table","incident"),actor,"comment",map("body",body));
    }
}
