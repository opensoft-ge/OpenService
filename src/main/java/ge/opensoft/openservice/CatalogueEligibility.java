package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
import java.util.*;

/** One live group criterion for catalogue discovery and new orders, separate from record access. */
final class CatalogueEligibility {
    private CatalogueEligibility() {}

    static String filter() {
        return "(group_id='' OR EXISTS (SELECT 1 FROM os_group_member m JOIN os_record g ON g.sys_id=m.group_id AND g.\"table\"='sys_user_group' WHERE m.group_id=os_offering.group_id AND m.user_id=?))";
    }

    static boolean eligible(Store.Db db,String id) throws SQLException {
        return db.count("SELECT COUNT(*) FROM os_offering WHERE offering_id=? AND active=1 AND "+filter(),id,db.viewerId)>0;
    }

    static String groupName(Store.Db db,String group) throws SQLException {
        if(group.isEmpty())return "All signed-in users";
        var row=db.one("SELECT data FROM os_record WHERE sys_id=? AND \"table\"='sys_user_group'",group);
        return row==null?"Removed group — new requests blocked":object(row.get("data")).get("short_description").toString();
    }

    static Map<String,Object> save(Store.Db db,String actor,String id,Object input) throws SQLException {
        Service.requireAccountAdministrator(db,actor);
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        var before=Catalogue.find(db,id,true);
        var body=object(input);
        require(body.keySet().equals(Set.of("group_id","revision")),"Provide group_id and revision");
        require(body.get("group_id") instanceof String,"Group must be text");
        String group=body.get("group_id").toString();
        require(group.matches("(?:|[0-9a-f]{32})"),"Use an existing group ID or empty text for all users");
        require(body.get("revision") instanceof Integer revision && revision>0,"Use a positive integer revision");
        require(group.isEmpty() || db.one("SELECT sys_id FROM os_record WHERE sys_id=? AND \"table\"='sys_user_group'",group)!=null,"Choose an existing group");
        if(db.update("UPDATE os_offering SET group_id=?,revision=revision+1 WHERE offering_id=? AND revision=?",group,id,body.get("revision"))!=1)throw new Failure(409,"Offering changed; reload before saving eligibility");
        SecurityEvents.record(db,actor,actor,"catalogue.eligibility_changed",map("offering_id",id,"before_group_id",before.get("group_id"),"after_group_id",group));
        return Catalogue.find(db,id,true);
    }
}
