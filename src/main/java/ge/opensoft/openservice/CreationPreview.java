package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
import java.util.*;

/** Current native creation checks without writes, numbering, credentials or impersonated sessions. */
final class CreationPreview {
    private CreationPreview() {}

    static Object document(Object text) {
        String source=WorkJobs.text(text,65536);
        try{return JSON.readValue(source,Object.class);}catch(tools.jackson.core.JacksonException invalid){throw new Failure(400,"Use a JSON object of proposed field values");}
    }

    static Map<String,Object> inspect(Store.Db db,String actor,String target,Object input) throws SQLException {
        Service.requireAccountAdministrator(db,actor);var body=object(input);
        require(body.keySet().equals(Set.of("kind","table","values")),"Provide kind, table and values");
        String kind=WorkJobs.text(body.get("kind"),10),table=WorkJobs.text(body.get("table"),64);
        require(Set.of("record","request").contains(kind),"Choose record or request");require(table.matches("[a-z][a-z0-9_]{0,63}"),"Use a table name");
        require(!kind.equals("request") || table.equals("sc_request"),"Owned requests use sc_request");var proposed=object(body.get("values"));require(JSON.writeValueAsString(proposed).length()<=65536,"Proposed values exceed 65536 characters");
        var account=db.one("SELECT user_id,name,role,active,password_reset_required FROM os_user WHERE user_id=?",target);if(account==null)throw new Failure(404,"User not found");
        String previousId=db.viewerId,previousRole=db.viewerRole;
        var result=map("account_id",target,"table",table,"kind",kind,"allowed",false,"status",403,"message","");
        try{
            if(((Number)account.get("active")).intValue()!=1)throw new Failure(403,"Account is inactive");
            if(PasswordReset.required(account))throw new Failure(403,"Password replacement is required");
            String role=account.get("role").toString();if(!Set.of("admin","agent").contains(role) && !(role.equals("requester") && kind.equals("request")))throw new Failure(403,"Account role cannot create through this journey");
            db.viewerId=target;db.viewerRole=role;
            var values=kind.equals("request")?Requests.input(proposed):proposed;
            Service.creationData(db,Service.schema(db,table),values);
            result.put("allowed",true);result.put("status",201);result.put("message","Current creation checks passed");
        }catch(Failure denied){result.put("status",denied.status);result.put("message",denied.getMessage());}
        finally{db.viewerId=previousId;db.viewerRole=previousRole;}
        return result;
    }
}
