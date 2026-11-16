package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Search immutable public snapshots; drafts and newly restricted articles never enter the query. */
final class KnowledgeSearch {
    private KnowledgeSearch() {}

    static Map<String,Object> page(Store.Db db, Map<String,String> args) throws SQLException {
        require(Set.of("q","offset","limit").containsAll(args.keySet()),"Use q, offset and limit");
        String query=args.getOrDefault("q","").strip();
        require(query.length()<=200 && query.indexOf('\0')<0,"Search must be at most 200 characters without NUL");
        int offset=(int)Service.number(args.getOrDefault("offset","0"),0,1000000);
        int limit=(int)Service.number(args.getOrDefault("limit","100"),1,100);
        String literal="%"+query.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        String selection=" FROM os_publication p WHERE p.retired=0 AND EXISTS (SELECT 1 FROM"
                +" os_record r WHERE r.sys_id=p.article_id AND r.\"table\"='kb_knowledge') AND"
                +" NOT EXISTS (SELECT 1 FROM os_record_access a WHERE a.record_id=p.article_id"
                +" AND a.visibility<>'workspace') AND p.version=(SELECT MAX(v.version) FROM"
                +" os_publication v WHERE v.article_id=p.article_id) AND"
                +" (LOWER(p.title) LIKE ? ESCAPE '!' OR LOWER(p.content) LIKE ? ESCAPE '!')";
        long total=db.count("SELECT COUNT(*)"+selection,literal,literal);
        var rows=db.all("SELECT p.*"+selection+" ORDER BY p.published_at DESC,p.article_id LIMIT ? OFFSET ?",literal,literal,limit,offset);
        return map("result",rows,"total",total,"query",query,"offset",offset,"limit",limit,"previous_offset",Math.max(0,offset-limit),
                "next_offset",offset+rows.size()<total?offset+limit:-1);
    }
}
