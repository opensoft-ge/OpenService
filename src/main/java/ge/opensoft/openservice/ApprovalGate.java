package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Enforces locally configured minimum peer reviews across every record persistence path. */
final class ApprovalGate {
    private ApprovalGate() {}

    static int required(Map<String, Object> definition, Object target) {
        var rules = object(definition.getOrDefault("transition_rules", map()));
        return ((Number) object(rules.getOrDefault(target, map())).getOrDefault("min_approvals", 0))
                .intValue();
    }

    static void creation(Map<String, Object> definition, Map<String, Object> data) {
        if (required(definition, data.getOrDefault("state", "")) > 0)
            throw new Failure(
                    409,
                    "Import or create in an unprotected state, then request review before"
                        + " transitioning");
    }

    static List<String> qualifying(
            Store.Db db, Map<String, Object> item, Map<String, Object> definition, String target)
            throws SQLException {
        var approvals =
                db.all(
                        "SELECT a.approval_id,a.approver_id,u.role FROM os_approval a JOIN os_user"
                            + " u ON u.user_id=a.approver_id WHERE a.record_id=? AND"
                            + " a.record_revision=? AND a.schema_version=? AND a.target_state=? AND"
                            + " a.status='approved' AND u.active=1 AND u.password_reset_required=0"
                            + " AND u.role IN ('admin','agent') AND"
                            + " u.auth_revision=a.decision_revision AND a.approver_id<>? ORDER BY"
                            + " a.sequence",
                        item.get("sys_id"),
                        item.get("revision"),
                        Approvals.version(definition),
                        target,
                        Objects.toString(db.viewerId, ""));
        var qualified = new ArrayList<String>();
        String originalId = db.viewerId, originalRole = db.viewerRole;
        for (var approval : approvals) {
            try {
                db.viewerId = approval.get("approver_id").toString();
                db.viewerRole = approval.get("role").toString();
                var current =
                        Service.record(
                                db, item.get("table").toString(), item.get("sys_id").toString());
                Approvals.eligible(db, item.get("table").toString(), current, definition, target);
                qualified.add(approval.get("approval_id").toString());
            } catch (Failure inaccessible) {
                // A historical decision does not grant continuing access or authority.
            } finally {
                db.viewerId = originalId;
                db.viewerRole = originalRole;
            }
        }
        return qualified;
    }

    static List<Map<String, Object>> requirements(
            Store.Db db, Map<String, Object> item, Map<String, Object> definition)
            throws SQLException {
        var result = new ArrayList<Map<String, Object>>();
        for (var destination :
                (List<?>)
                        object(definition.get("flow"))
                                .getOrDefault(object(item.get("data")).get("state"), List.of())) {
            String target = destination.toString();
            int minimum = required(definition, target);
            int count = minimum == 0 ? 0 : qualifying(db, item, definition, target).size();
            result.add(
                    map(
                            "target_state",
                            target,
                            "label",
                            object(definition.get("states")).get(target),
                            "required",
                            minimum,
                            "qualifying",
                            count,
                            "approval_satisfied",
                            count >= minimum));
        }
        return result;
    }

    static void update(Store.Db db, Map<String, Object> item) throws SQLException {
        var definition = Service.schema(db, item.get("table").toString());
        var data = object(item.get("data"));
        String target = Objects.toString(data.get("state"), "");
        int minimum = required(definition, target);
        if (minimum == 0) return;
        var previous = db.one("SELECT * FROM os_record WHERE sys_id=?", item.get("sys_id"));
        if (previous == null) throw new Failure(404, "Record not found");
        var before = new LinkedHashMap<>(object(previous.get("data")));
        if (Objects.equals(before.get("state"), target)) return;
        var after = new LinkedHashMap<>(data);
        before.remove("state");
        after.remove("state");
        if (!before.equals(after))
            throw new Failure(
                    409, "Save other field changes before requesting review of this transition");
        if (qualifying(db, previous, definition, target).size() < minimum)
            throw new Failure(
                    409,
                    "This transition requires " + minimum + " current approvals from other staff");
    }
}
