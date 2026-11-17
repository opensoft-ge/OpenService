package ge.opensoft.openservice;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
import java.util.*;

/** Personal history always binds the authenticated account before counting or paging. */
final class NotificationSearch {
    private NotificationSearch() {}
    static Map<String,Object> page(Store.Db db,String actor,Map<String,String> args) throws SQLException {
        require(Set.of("q","state","offset","limit").containsAll(args.keySet()),"Use q, state, offset and limit");
        String q=args.getOrDefault("q","").strip(),state=args.getOrDefault("state","all");
        require(q.length()<=200 && q.indexOf('\0')<0,"Search must be at most 200 characters without NUL");
        require(Set.of("all","unread","acknowledged").contains(state),"Unsupported notification state");
        int offset=(int)Service.number(args.getOrDefault("offset","0"),0,1000000),limit=(int)Service.number(args.getOrDefault("limit","100"),1,100);
        String literal="%"+q.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        String selection=" FROM os_notice WHERE user_id=? AND (?='all' OR (?='unread' AND acknowledged=0) OR (?='acknowledged' AND acknowledged=1)) AND LOWER(message) LIKE ? ESCAPE '!'";
        long total=db.count("SELECT COUNT(*)"+selection,actor,state,state,state,literal);
        var rows=db.all("SELECT notice_id,message,created_at,acknowledged,(SELECT COUNT(*) FROM os_notice_target target WHERE target.notice_id=os_notice.notice_id) AS has_target"+selection+" ORDER BY created_at DESC,notice_id LIMIT ? OFFSET ?",actor,state,state,state,literal,limit,offset);
        return map("result",rows,"query",q,"state",state,"offset",offset,"limit",limit,"total",total,
                "previous_offset",Math.max(0,offset-limit),"next_offset",offset+rows.size()<total?offset+limit:-1);
    }
}
