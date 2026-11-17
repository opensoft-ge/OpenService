package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** A requester owns only requests submitted through this bounded intake flow. */
final class Requests {
    private Requests() {}

    static Map<String,Object> input(Object body) {
        var input=object(body);
        if(!Set.of("short_description","description").containsAll(input.keySet()))throw new Failure(400,"Submit a summary and description only");
        return input;
    }

    static Map<String, Object> create(Store.Db db, String actor, Object body) throws SQLException {
        var item = Service.create(db, "sc_request", input(body), actor);
        db.update(
                "INSERT INTO os_requester_record(record_id,user_id) VALUES(?,?)",
                item.get("sys_id"),
                db.viewerId);
        return item;
    }
}
