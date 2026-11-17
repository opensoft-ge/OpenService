package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
import java.util.*;

/** Optional one-team fulfilment routing, snapshotted when an offering is ordered. */
final class CatalogueRouting {
    private CatalogueRouting() {}

    static void group(Store.Db db,String id) throws SQLException {
        require(db.one("SELECT sys_id FROM os_record WHERE sys_id=? AND \"table\"='sys_user_group'",id)!=null,"Choose an existing fulfilment group");
    }

    static String name(Store.Db db,String id) throws SQLException {
        if(id.isEmpty())return "Manual fulfilment";
        var row=db.one("SELECT data FROM os_record WHERE sys_id=? AND \"table\"='sys_user_group'",id);
        return row==null?"Removed group — choose a replacement":object(row.get("data")).get("short_description").toString();
    }

    static Map<String,Object> save(Store.Db db,String actor,String id,Object input) throws SQLException {
        Service.requireAccountAdministrator(db,actor);
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        Catalogue.find(db,id,true);var body=object(input);
        require(body.keySet().equals(Set.of("group_id","revision")),"Provide group_id and revision");
        require(body.get("group_id") instanceof String,"Group must be text");String group=body.get("group_id").toString();
        require(body.get("revision") instanceof Integer n && n>0,"Use a positive offering revision");
        if(!group.isEmpty())group(db,group);
        if(db.update("UPDATE os_offering SET fulfilment_group_id=?,revision=revision+1 WHERE offering_id=? AND revision=?",group,id,body.get("revision"))!=1)throw new Failure(409,"Offering changed; reload before routing");
        SecurityEvents.record(db,actor,actor,"catalogue.routing_changed",map("offering_id",id,"group_id",group));
        return Catalogue.find(db,id,true);
    }

    static void create(Store.Db db,Map<String,Object> offering,Map<String,Object> request) throws SQLException {
        String group=offering.get("fulfilment_group_id").toString();
        if(group.isEmpty())return;
        group(db,group);
        db.update("INSERT INTO os_fulfilment(request_id,group_id,assignee_id,state,outcome,revision,updated_at) VALUES(?,?,'','queued','',1,?)",request.get("sys_id"),group,now());
    }
}
