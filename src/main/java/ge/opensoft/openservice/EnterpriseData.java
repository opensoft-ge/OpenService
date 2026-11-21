package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.util.*;

/** Bounded revisioned documents; each caller owns its business authorization. */
final class EnterpriseData {
    private EnterpriseData() {}
    static Map<String,Object> get(Store.Db db,String area,String subject)throws SQLException {
        var row=db.one("SELECT revision,definition FROM os_enterprise_document WHERE area=? AND subject=?",area,subject);
        return row==null?map("revision",0,"definition",map()):row;
    }
    static void save(Store.Db db,String area,String subject,Object revision,Object definition)throws SQLException {
        var current=get(db,area,subject);ProblemInvestigations.revision(revision,current.get("revision"));
        // Revocation must persist its audit handoff even when a workspace has filled its normal allocation.
        require("agent-investigation".equals(area) || ((Number)current.get("revision")).intValue()>0 || db.count("SELECT COUNT(*) FROM os_enterprise_document WHERE area=?",area)<1000,"Workspace document capacity reached");
        String text=JSON.writeValueAsString(definition);require(text.length()<=64000,"Workspace document exceeds 64000 characters");
        db.update("INSERT INTO os_enterprise_document(area,subject,revision,definition) VALUES(?,?,1,?) ON CONFLICT(area,subject) DO UPDATE SET revision=os_enterprise_document.revision+1,definition=excluded.definition",area,subject,text);
        SecurityEvents.record(db,db.viewerId,db.viewerId,"enterprise."+area,map("subject",subject));
    }
    static List<?> batch(Object value, int maximum) {
        require(value instanceof List<?>, "Provide a JSON array");
        var entries = (List<?>) value;
        require(entries.size() <= maximum, "Batch exceeds the supported size");
        return entries;
    }
    static void keys(Map<String,Object> body,String... names){require(body.keySet().equals(Set.of(names)),"Provide only the selected operation fields");}
    static String id(Object value){String id=WorkJobs.text(value,32);require(id.matches("[0-9a-f]{32}"),"Choose an exact record identifier");return id;}
    static int integer(Object value,int min,int max){require(value instanceof Integer n && n>=min && n<=max,"Use an integer within the allowed bounds");return (Integer)value;}
    static BigDecimal money(Object value){String text=WorkJobs.text(value,24);require(text.matches("[0-9]{1,12}(\\.[0-9]{1,2})?"),"Use a nonnegative amount with at most two decimal places");return new BigDecimal(text).setScale(2);}
    static String actor(Store.Db db)throws SQLException{return Service.account(db,db.viewerId).get("name").toString();}
    static boolean currentAdmin(Store.Db db, Object user, Object revision) throws SQLException {
        return db.count("SELECT COUNT(*) FROM os_user WHERE user_id=? AND auth_revision=?"
                + " AND active=1 AND password_reset_required=0 AND role='admin'", user, revision) == 1;
    }
    static void lock(Store.Db db)throws SQLException{if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");}
    static List<Map<String,Object>> list(Store.Db db,String area)throws SQLException{return db.all("SELECT subject,revision,definition FROM os_enterprise_document WHERE area=? ORDER BY subject",area);}
    static Map<String,Object> field(String name,String label,String type,Object value){return map("name",name,"label",label,"type",type,"value",value);}
    static Map<String,Object> form(String operation,String title,Object... fields){return map("operation",operation,"title",title,"fields",List.of(fields));}
    static Map<String,Object> page(String title,String description,Object data,Object... forms){return map("title",title,"description",description,"data",data,"forms",List.of(forms));}
}
