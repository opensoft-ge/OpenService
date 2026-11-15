package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.util.*;

/** Administrator investigation of events targeting one stable account identity. */
final class AccountSecurityEvents {
    private AccountSecurityEvents() {}

    static Map<String, Object> page(
            Store.Db db, String actor, String target, Map<String, String> args) throws Exception {
        Service.requireAccountAdministrator(db, actor);
        var account = Service.account(db, target);
        if (!Set.of("before", "download").containsAll(args.keySet())
                || !Set.of("true", "false").contains(args.getOrDefault("download", "false")))
            throw new Failure(400, "Unsupported account journal parameter");
        String before = args.get("before");
        var params = new ArrayList<Object>();
        params.add(target);
        if (before != null) {
            if (!before.matches("[0-9a-f]{32}"))
                throw new Failure(400, "Invalid account journal cursor");
            var cursor =
                    db.one(
                            "SELECT sequence FROM os_security_event WHERE target_id=? AND"
                                + " event_id=?",
                            target,
                            before);
            if (cursor == null)
                throw new Failure(
                        400, "Account journal cursor is unavailable; return to latest events");
            params.add(cursor.get("sequence"));
        }
        var rows =
                db.all(
                        "SELECT"
                            + " event_id,occurred_at,actor_id,actor_name,target_id,target_name,action,detail"
                            + " FROM os_security_event WHERE target_id=?"
                                + (before == null ? "" : " AND sequence<?")
                                + " ORDER BY sequence DESC LIMIT 101",
                        params.toArray());
        String next = "";
        if (rows.size() > 100) {
            rows.remove(100);
            next = rows.getLast().get("event_id").toString();
        }
        return map(
                "account",
                account,
                "result",
                rows,
                "next_before",
                next,
                "before",
                before == null ? "" : before,
                "download",
                Boolean.parseBoolean(args.getOrDefault("download", "false")));
    }
}
