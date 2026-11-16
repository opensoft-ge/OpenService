package ge.opensoft.openservice;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
import java.util.*;

/** Exact native event filters; existing ingestion/correlation semantics remain unchanged. */
final class EventSearch {
    private EventSearch() {}
    static Map<String,Object> page(Store.Db db,String actor,Map<String,String> args) throws SQLException {
        Service.requireAccountAdministrator(db,actor);
        require(Set.of("q","severity","state","offset","limit").containsAll(args.keySet()),"Use q, severity, state, offset and limit");
        String q=args.getOrDefault("q","").strip(),severity=args.getOrDefault("severity","all"),state=args.getOrDefault("state","all");
        require(q.length()<=200 && q.indexOf('\0')<0,"Search must be at most 200 characters without NUL");
        require(Set.of("all","info","warning","critical").contains(severity),"Unsupported severity filter");
        require(Set.of("all","open","acknowledged","resolved").contains(state),"Unsupported event state filter");
        int offset=(int)Service.number(args.getOrDefault("offset","0"),0,1000000),limit=(int)Service.number(args.getOrDefault("limit","100"),1,100);
        String literal="%"+q.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        String selection=" FROM os_operational_event WHERE (?='all' OR severity=?) AND (?='all' OR state=?) AND (LOWER(event_key) LIKE ? ESCAPE '!' OR LOWER(summary) LIKE ? ESCAPE '!')";
        long total=db.count("SELECT COUNT(*)"+selection,severity,severity,state,state,literal,literal);
        var rows=db.all("SELECT *"+selection+" ORDER BY last_seen DESC,event_key LIMIT ? OFFSET ?",severity,severity,state,state,literal,literal,limit,offset);
        return map("result",rows,"query",q,"severity",severity,"state",state,"offset",offset,"limit",limit,"total",total,
                "previous_offset",Math.max(0,offset-limit),"next_offset",offset+rows.size()<total?offset+limit:-1);
    }
}
