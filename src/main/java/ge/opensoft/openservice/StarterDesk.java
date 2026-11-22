package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
import java.util.Map;

/** The singleton identifies operator-editable native records, never seeded credentials. */
final class StarterDesk {
    private StarterDesk() {}

    static Map<String,Object> settings(Store.Db db) throws SQLException {
        return db.one("SELECT group_id,offering_id FROM os_starter_desk WHERE singleton=1");
    }

    static void install(Store.Db db) throws SQLException {
        if (settings(db) != null) return;
        var group = Service.create(db, "sys_user_group", map("short_description","Service desk",
                "description","First-line incidents and service requests"), Service.account(db,db.viewerId).get("name").toString());
        var offering = Catalogue.save(db, db.viewerId, "", map("name","Request a work laptop",
                "description","Tell the service desk who needs the laptop and when.",
                "active",true,"revision",0));
        CatalogueRouting.save(db, db.viewerId, offering.get("offering_id").toString(),
                map("group_id",group.get("sys_id"),"revision",1));
        db.update("INSERT INTO os_starter_desk(singleton,group_id,offering_id) VALUES(1,?,?)",
                group.get("sys_id"), offering.get("offering_id"));
    }

    static void route(Store.Db db,String group) throws SQLException {
        CatalogueRouting.group(db,group);
        if (db.update("UPDATE os_starter_desk SET group_id=? WHERE singleton=1",group) != 1)
            throw new Failure(409,"Install the starter desk first");
    }
}
