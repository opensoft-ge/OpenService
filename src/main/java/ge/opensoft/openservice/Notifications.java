package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

final class Notifications {
    private Notifications() {}

    static Map<String, Object> deliver(Store.Db db, String id, Object input, long now)
            throws SQLException {
        var body = object(input);
        require(
                body.keySet().equals(Set.of("username", "message")),
                "Provide username and message");
        String username = WorkJobs.text(body.get("username"), 80),
                message = WorkJobs.text(body.get("message"), 2000);
        var user =
                db.one("SELECT user_id,active,notifications FROM os_user WHERE name=?", username);
        if (user == null) throw new Failure(404, "Notification recipient not found");
        boolean delivered =
                ((Number) user.get("active")).intValue() == 1
                        && ((Number) user.get("notifications")).intValue() == 1;
        if (delivered)
            db.update(
                    "INSERT INTO os_notice(notice_id,user_id,message,created_at) VALUES(?,?,?,?) ON"
                        + " CONFLICT(notice_id) DO NOTHING",
                    id,
                    user.get("user_id"),
                    message,
                    now);
        return map("delivered", delivered);
    }

    static List<Map<String, Object>> list(Store.Db db, String actor) throws SQLException {
        return objects(NotificationSearch.page(db,actor,Map.of()).get("result"));
    }

    static void acknowledgeMany(Store.Db db,String actor,Object value) throws SQLException {
        require(value instanceof List<?> rows && !rows.isEmpty() && rows.size()<=100,"Provide 1–100 notification IDs");
        var ids=new LinkedHashSet<String>();for(Object id:(List<?>)value){require(id instanceof String s && s.matches("[0-9a-f]{32}"),"Use 32-character notification IDs");require(ids.add((String)id),"Notification IDs must be unique");}
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");var parameters=new ArrayList<Object>();parameters.add(actor);parameters.addAll(ids);
        String selection="user_id=? AND notice_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")";
        if(db.count("SELECT COUNT(*) FROM os_notice WHERE "+selection,parameters.toArray())!=ids.size())throw new Failure(404,"One or more notifications are unavailable");
        db.update("UPDATE os_notice SET acknowledged=1 WHERE "+selection+" AND acknowledged=0",parameters.toArray());
    }

    static void update(Store.Db db, String actor, Object input) throws SQLException {
        var body = object(input);
        if (body.keySet().equals(Set.of("enabled"))) {
            require(body.get("enabled") instanceof Boolean, "Enabled must be boolean");
            db.update(
                    "UPDATE os_user SET notifications=? WHERE user_id=?",
                    body.get("enabled").equals(true) ? 1 : 0,
                    actor);
        } else if(body.keySet().equals(Set.of("acknowledge_many"))) {
            acknowledgeMany(db,actor,body.get("acknowledge_many"));
        } else {
            require(body.keySet().equals(Set.of("acknowledge")), "Provide enabled or acknowledge");
            String id = WorkJobs.text(body.get("acknowledge"), 32);
            if (db.update(
                            "UPDATE os_notice SET acknowledged=1 WHERE notice_id=? AND user_id=?",
                            id,
                            actor)
                    != 1) throw new Failure(404, "Notification not found");
        }
    }
}
