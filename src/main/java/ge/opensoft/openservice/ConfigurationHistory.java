package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;

/** Retained native promotion receipts, written in the same transaction as the definitions. */
final class ConfigurationHistory {
    private ConfigurationHistory() {}

    static String record(Store.Db db,String actor,Map<String,Object> before,
                         Map<String,Object> after,Map<String,Object> versions,
                         List<Map<String,Object>> changes) throws SQLException {
        String id=id();
        db.update("INSERT INTO os_configuration_install(install_id,actor_id,actor_name,installed_at,table_count,detail) VALUES(?,?,?,?,?,?)",
                id,actor,Service.account(db,actor).get("name"),now(),after.size(),
                JSON.writeValueAsString(map("format",Configuration.FORMAT,"before",before,"after",after,"versions",versions,"changes",changes)));
        return id;
    }

    static Map<String,Object> page(Store.Db db,Map<String,String> args) throws SQLException {
        Service.requireAccountAdministrator(db,db.viewerId);
        require(Set.of("offset","limit").containsAll(args.keySet()),"Use offset and limit only");
        int offset=Service.number(args.getOrDefault("offset","0"),0,1000000),limit=Service.number(args.getOrDefault("limit","100"),1,100);
        long total=db.count("SELECT COUNT(*) FROM os_configuration_install");
        var rows=db.all("SELECT install_id,actor_id,actor_name,installed_at,table_count FROM os_configuration_install ORDER BY installed_at DESC,install_id DESC LIMIT ? OFFSET ?",limit,offset);
        return map("result",rows,"total",total,"offset",offset,"limit",limit,"previous_offset",Math.max(0,offset-limit),"next_offset",nextOffset(offset,limit,total));
    }

    static int nextOffset(int offset,int limit,long total) {
        int next=offset+limit;
        return next<=1000000 && next<total?next:-1;
    }

    static Map<String,Object> detail(Store.Db db,String id) throws SQLException {
        Service.requireAccountAdministrator(db,db.viewerId);
        var row=db.one("SELECT * FROM os_configuration_install WHERE install_id=?",id);
        if(row==null)throw new Failure(404,"Configuration installation not found");
        return row;
    }
}
