package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

final class PermissionComparison {
    private PermissionComparison() {}

    static Map<String, Object> inspect(
            Store.Db db, String actor, String target, Map<String, String> args)
            throws SQLException {
        Service.requireAccountAdministrator(db, actor);
        if (!args.keySet().equals(Set.of("table", "record_id", "with"))
                || !args.get("with").matches("[A-Za-z0-9]{1,80}"))
            throw new Failure(400, "Provide table, record_id and an exact comparison username");
        var other = db.one("SELECT user_id FROM os_user WHERE name=?", args.get("with"));
        if (other == null) throw new Failure(404, "Comparison account not found");
        String right = other.get("user_id").toString();
        var policyArgs = Map.of("table", args.get("table"), "record_id", args.get("record_id"));
        var leftPolicy = AccountPermissions.inspect(db, actor, target, policyArgs);
        var rightPolicy = AccountPermissions.inspect(db, actor, right, policyArgs);
        return map(
                "subjects",
                List.of(
                        map("account", Service.account(db, target), "diagnosis", leftPolicy),
                        map("account", Service.account(db, right), "diagnosis", rightPolicy)));
    }
}
