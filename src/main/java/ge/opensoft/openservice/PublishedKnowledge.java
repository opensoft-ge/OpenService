package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Explicit immutable publication snapshots. Draft edits never change requester-visible content. */
final class PublishedKnowledge {
    private PublishedKnowledge() {}

    static Map<String, Object> publish(Store.Db db, String actor, String id) throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        var item = Service.record(db, "kb_knowledge", id);
        require(
                RecordAccess.status(db, id).get("visibility").equals("workspace"),
                "Private or group articles cannot be published to all requesters");
        var data = object(FieldAccess.serialize(db, item));
        String title = WorkJobs.text(data.get("short_description"), 1000),
                content = WorkJobs.text(data.get("text"), 100000);
        int version = nextVersion(db, id);
        db.update(
                "INSERT INTO os_publication(article_id,version,title,content,published_at)"
                    + " VALUES(?,?,?,?,?)",
                id,
                version,
                title,
                content,
                now());
        Service.audit(
                db,
                item,
                db.one("SELECT name FROM os_user WHERE user_id=?", actor).get("name").toString(),
                "knowledge.published",
                map("version", version));
        return map("article_id", id, "version", version);
    }

    static int nextVersion(Store.Db db, String id) throws SQLException {
        long latest = db.count("SELECT COALESCE(MAX(version),0) FROM os_publication WHERE article_id=?", id);
        require(latest < Integer.MAX_VALUE, "Publication version limit reached");
        return (int)latest + 1;
    }

    static Map<String,Object> restore(Store.Db db, String actor, String id, Object input) throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        var item = Service.record(db,"kb_knowledge",id);
        require(RecordAccess.status(db,id).get("visibility").equals("workspace"),
                "Private or group articles cannot be published to all requesters");
        var body = object(input);
        require(body.keySet().equals(Set.of("operation","version")),"Provide operation and version");
        require(body.get("version") instanceof Integer,"Version must be an integer");
        int source = (Integer)body.get("version");
        require(source>0,"Version must be positive");
        var previous = db.one("SELECT title,content FROM os_publication WHERE article_id=? AND version=?",id,source);
        if(previous==null) throw new Failure(404,"Publication version not found");
        int version=nextVersion(db,id);
        db.update("INSERT INTO os_publication(article_id,version,title,content,published_at) VALUES(?,?,?,?,?)",
                id,version,previous.get("title"),previous.get("content"),now());
        Service.audit(db,item,Service.account(db,actor).get("name").toString(),"knowledge.restored",
                map("source_version",source,"version",version));
        return map("article_id",id,"version",version,"source_version",source);
    }

    static void retire(Store.Db db, String actor, String id) throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        if (db.update("UPDATE os_publication SET retired=1 WHERE article_id=?", id) == 0)
            throw new Failure(404, "Published article not found");
        SecurityEvents.record(db, actor, actor, "knowledge.retired", map("article_id", id));
    }

    static Map<String, Object> history(Store.Db db, String actor, String id, Object input) throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        Service.record(db, "kb_knowledge", id);
        var args = object(input);
        require(Set.of("before").containsAll(args.keySet()), "Only before is supported");
        long before = args.containsKey("before")
                ? Service.number(String.valueOf(args.get("before")), 1, Integer.MAX_VALUE)
                : Long.MAX_VALUE;
        var rows = db.all("SELECT version,title,content,published_at,retired FROM os_publication WHERE article_id=? AND version<? ORDER BY version DESC LIMIT 101", id, before);
        boolean more = rows.size() > 100;
        if (more) rows.removeLast();
        return map("article_id", id, "versions", rows, "next_before", more ? rows.getLast().get("version").toString() : "");
    }

    static List<Map<String, Object>> list(Store.Db db) throws SQLException {
        return objects(KnowledgeSearch.page(db,Map.of()).get("result"));
    }
}
