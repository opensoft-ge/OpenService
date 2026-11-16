package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Explicit audited operator disposition; no incident creation or external remediation. */
final class EventActions {
    private EventActions() {}

    static Map<String,Object> apply(Store.Db db,String actor,Object input) throws SQLException {
        Service.requireAccountAdministrator(db,actor);
        var body=object(input);
        require(body.keySet().equals(Set.of("key","operation","reason")),"Provide key, operation and reason");
        String key=WorkJobs.text(body.get("key"),160), reason=WorkJobs.text(body.get("reason"),2000);
        String operation=WorkJobs.text(body.get("operation"),20);
        var targets=Map.of("acknowledge","acknowledged","resolve","resolved","reopen","open");
        require(targets.containsKey(operation),"Choose acknowledge, resolve or reopen");
        var row=db.one("SELECT * FROM os_operational_event WHERE event_key=?",key);
        if(row==null) throw new Failure(404,"Operational event not found");
        Service.record(db,"cmdb_ci",row.get("asset_id").toString());
        String target=targets.get(operation), before=row.get("state").toString();
        if(before.equals(target)) return row;
        if(before.equals("resolved") && !operation.equals("reopen"))
            throw new Failure(409,"Reopen a resolved event before acknowledging it");
        db.update("UPDATE os_operational_event SET state=? WHERE event_key=?",target,key);
        SecurityEvents.record(db,actor,actor,"event."+operation,
                map("key",key,"before",before,"after",target,"reason",reason));
        row.put("state",target);
        return row;
    }
}
