package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
import java.util.*;

/** One durable group task per routed order; ordinary request policies still govern every change. */
final class Fulfilment {
    private Fulfilment() {}

    static boolean readable(Store.Db db) throws SQLException {
        var definition=Service.schema(db,"sc_request");
        return FieldAccess.allowed(db,definition,"state") && FieldAccess.allowed(db,definition,"description");
    }

    static void access(Store.Db db) throws SQLException {
        if(!readable(db))throw new Failure(403,"Request state and description read access are required for fulfilment");
    }

    static boolean member(Store.Db db,String group) throws SQLException {
        return db.count("SELECT COUNT(*) FROM os_group_member m JOIN os_record g ON g.sys_id=m.group_id AND g.\"table\"='sys_user_group' WHERE m.group_id=? AND m.user_id=?",group,db.viewerId)>0;
    }

    static Map<String,Object> row(Store.Db db,String id) throws SQLException {
        var row=db.one("SELECT * FROM os_fulfilment WHERE request_id=?",id);
        if(row==null)throw new Failure(404,"Managed fulfilment not found");return row;
    }

    static Map<String,Object> detail(Store.Db db,String id) throws SQLException {
        var request=Service.record(db,"sc_request",id);access(db);var row=row(db,id);
        var result=map("request_id",id,"state",row.get("state"),"outcome",row.get("outcome"),"revision",row.get("revision"),"updated_at",row.get("updated_at"),"request_revision",request.get("revision"));
        boolean staff=Set.of("admin","agent").contains(db.viewerRole),admin="admin".equals(db.viewerRole);
        boolean working=staff && (admin || member(db,row.get("group_id").toString()));
        result.put("can_claim",working && row.get("state").equals("queued"));
        result.put("can_complete",working && row.get("state").equals("in_progress") && (admin || row.get("assignee_id").equals(db.viewerId)));
        result.put("can_reassign",admin && !row.get("state").equals("complete"));
        if(staff){result.put("group_id",row.get("group_id"));result.put("group_name",CatalogueRouting.name(db,row.get("group_id").toString()));result.put("assignee_id",row.get("assignee_id"));result.put("assignee_name",db.one("SELECT COALESCE((SELECT name FROM os_user WHERE user_id=?),'Unassigned') AS name",row.get("assignee_id")).get("name"));}
        return result;
    }

    static Map<String,Object> page(Store.Db db,Map<String,String> args) throws SQLException {
        if(!Set.of("admin","agent").contains(db.viewerRole))throw new Failure(403,"Staff access required");
        access(db);
        require(Set.of("state","limit","offset").containsAll(args.keySet()),"Use state, limit and offset");
        String state=args.getOrDefault("state","open");require(Set.of("open","queued","in_progress","complete","all").contains(state),"Choose a supported task state");
        int limit=Service.number(args.getOrDefault("limit","50"),1,100),offset=Service.number(args.getOrDefault("offset","0"),0,1000000);
        var parameters=new ArrayList<Object>();String access=RecordAccess.filter(db,"f.request_id",parameters);
        String selection=" FROM os_fulfilment f JOIN os_record r ON r.sys_id=f.request_id WHERE "+access+" AND (?='all' OR (?='open' AND f.state<>'complete') OR f.state=?)";parameters.add(state);parameters.add(state);parameters.add(state);
        if(!"admin".equals(db.viewerRole)){selection+=" AND EXISTS (SELECT 1 FROM os_group_member m JOIN os_record g ON g.sys_id=m.group_id AND g.\"table\"='sys_user_group' WHERE m.group_id=f.group_id AND m.user_id=?)";parameters.add(db.viewerId);}
        long total=db.count("SELECT COUNT(*)"+selection,parameters.toArray());parameters.add(limit);parameters.add(offset);
        var rows=new ArrayList<Map<String,Object>>();
        for(var item:db.all("SELECT f.request_id"+selection+" ORDER BY f.updated_at,f.request_id LIMIT ? OFFSET ?",parameters.toArray())){String id=item.get("request_id").toString();var task=detail(db,id);task.put("request",FieldAccess.serialize(db,Service.record(db,"sc_request",id)));rows.add(task);}
        return map("result",rows,"total",total,"limit",limit,"offset",offset,"state",state);
    }

    static Map<String,Object> control(Store.Db db,String id,Object input) throws SQLException {
        if(!Set.of("admin","agent").contains(db.viewerRole))throw new Failure(403,"Staff access required");
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        var request=Service.record(db,"sc_request",id);access(db);var task=row(db,id);var body=object(input);
        String operation=WorkJobs.text(body.get("operation"),20);
        require(Set.of("claim","complete","reassign").contains(operation),"Choose claim, complete or reassign");
        var expected=new HashSet<>(Set.of("operation","revision","request_revision"));if(!operation.equals("claim"))expected.add(operation.equals("complete")?"outcome":"group_id");require(body.keySet().equals(expected),"Provide the operation and reviewed revisions");
        require(body.get("revision") instanceof Integer n && n>0,"Use a positive task revision");
        require(body.get("request_revision") instanceof Integer n && n>=0,"Use a nonnegative request revision");
        if(!task.get("revision").equals(body.get("revision")) || !request.get("revision").equals(body.get("request_revision")))throw new Failure(409,"Request or fulfilment changed; reload before acting");
        String actor=Service.account(db,db.viewerId).get("name").toString();boolean admin=db.viewerRole.equals("admin");
        if(operation.equals("reassign")){
            Service.requireAccountAdministrator(db,db.viewerId);require(!task.get("state").equals("complete"),"Completed fulfilment cannot be reassigned");
            String group=WorkJobs.text(body.get("group_id"),32);CatalogueRouting.group(db,group);
            RecordPolicy.require(Service.schema(db,"sc_request"),"update",db.viewerRole);
            db.update("UPDATE os_fulfilment SET group_id=?,assignee_id='',state='queued',revision=revision+1,updated_at=? WHERE request_id=?",group,now(),id);
        }else{
            if(!admin && !member(db,task.get("group_id").toString()))throw new Failure(403,"Current fulfilment group membership required");
            String next;
            if(operation.equals("claim")){
                if(!task.get("state").equals("queued"))throw new Failure(409,"Only queued fulfilment can be claimed");
                db.update("UPDATE os_fulfilment SET assignee_id=?,state='in_progress',revision=revision+1,updated_at=? WHERE request_id=?",db.viewerId,now(),id);next="fulfilling";
            }else{
                if(!task.get("state").equals("in_progress"))throw new Failure(409,"Claim fulfilment before completing it");
                if(!admin && !task.get("assignee_id").equals(db.viewerId))throw new Failure(403,"Only the current assignee or an administrator can complete fulfilment");
                String outcome=WorkJobs.text(body.get("outcome"),2000);
                db.update("UPDATE os_fulfilment SET state='complete',outcome=?,revision=revision+1,updated_at=? WHERE request_id=?",outcome,now(),id);next="complete";
            }
            Service.update(db,"sc_request",request,map("state",next),actor,db.viewerRole,body.get("request_revision").toString());
        }
        Service.audit(db,request,actor,"fulfilment."+operation,map("request_id",id));
        return detail(db,id);
    }

    static void guard(Store.Db db,Map<String,Object> item) throws SQLException {
        if(!item.get("table").equals("sc_request"))return;
        var task=db.one("SELECT state FROM os_fulfilment WHERE request_id=?",item.get("sys_id"));
        if(task!=null && task.get("state").equals("complete")!=Objects.toString(object(item.get("data")).get("state"),"").equals("complete"))throw new Failure(409,"Complete managed fulfilment through its task; completed tasks cannot be reopened");
    }
}
