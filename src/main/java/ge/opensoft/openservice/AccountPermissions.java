package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Administrator policy diagnosis for one subject; never a login or permission grant. */
final class AccountPermissions {
    private AccountPermissions() {}

    static Map<String, Object> inspect(
            Store.Db db, String actor, String target, Map<String, String> args)
            throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        var account = Service.account(db, target);
        if (!args.keySet().equals(Set.of("table", "record_id"))
                || !args.get("record_id").matches("[0-9a-f]{32}")
                || !args.get("table").matches("[a-z][a-z0-9_]{0,63}"))
            throw new Failure(400, "Provide a table and a 32-character record ID");
        String table = args.get("table"), record = args.get("record_id");
        Service.schema(db, table);
        var item = db.one("SELECT * FROM os_record WHERE sys_id=?", record);
        if (item == null || !item.get("table").equals(table))
            throw new Failure(404, "Record not found");
        String previousId = db.viewerId, previousRole = db.viewerRole;
        try {
            var result =
                    map(
                            "account_id",
                            target,
                            "record_id",
                            record,
                            "table",
                            table,
                            "permissions",
                            map());
            if (!Boolean.TRUE.equals(account.get("active"))) {
                result.put("read", map("allowed", false, "reason", "account_inactive"));
                return result;
            }
            db.viewerId = target;
            db.viewerRole = account.get("role").toString();
            var parameters = new ArrayList<Object>(List.of(record));
            String filter = RecordAccess.filter(db, "os_record.sys_id", parameters);
            boolean visible =
                    db.count(
                                    "SELECT COUNT(*) FROM os_record WHERE sys_id=? AND " + filter,
                                    parameters.toArray())
                            == 1;
            result.put(
                    "read",
                    map("allowed", visible, "reason", visible ? "allowed" : "record_visibility"));
            if (visible) result.put("permissions", Permissions.describeVisible(db, table, record));
            return result;
        } finally {
            db.viewerId = previousId;
            db.viewerRole = previousRole;
        }
    }
}
