package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Revision-bound review evidence. Recording a decision never executes the proposed action. */
final class Approvals {
    private Approvals() {}

    static Map<String, Object> context(Store.Db db, String table, String id) throws SQLException {
        if (!Set.of("admin", "agent").contains(db.viewerRole))
            throw new Failure(403, "Staff access is required for approvals");
        var item = Service.record(db, table, id);
        FieldAccess.writing(db, Service.schema(db, table), map("state", ""));
        return item;
    }

    static int version(Map<String, Object> definition) {
        return ((Number) definition.getOrDefault("version", 0)).intValue();
    }

    static boolean current(
            Map<String, Object> row, Map<String, Object> item, Map<String, Object> definition) {
        return ((Number) row.get("record_revision")).intValue()
                        == ((Number) item.get("revision")).intValue()
                && ((Number) row.get("schema_version")).intValue() == version(definition);
    }

    static Map<String, Object> publicRow(
            Map<String, Object> row, Map<String, Object> item, Map<String, Object> definition) {
        var result = new LinkedHashMap<>(row);
        result.remove("sequence");
        result.remove("decision_revision");
        result.put("current", current(row, item, definition));
        return result;
    }

    static Map<String, Object> page(Store.Db db, String table, String id, Map<String, String> args)
            throws SQLException {
        var item = context(db, table, id);
        var definition = Service.schema(db, table);
        if (!Set.of("before").containsAll(args.keySet()))
            throw new Failure(400, "Only before is supported");
        long before = Long.MAX_VALUE;
        if (args.containsKey("before")) {
            String cursor = args.get("before");
            if (!cursor.matches("[0-9a-f]{32}")) throw new Failure(400, "Invalid approval cursor");
            var row =
                    db.one(
                            "SELECT sequence FROM os_approval WHERE approval_id=? AND record_id=?",
                            cursor,
                            id);
            if (row == null)
                throw new Failure(400, "Approval cursor is unavailable for this record");
            before = ((Number) row.get("sequence")).longValue();
        }
        var rows =
                db.all(
                        "SELECT * FROM os_approval WHERE record_id=? AND sequence<? ORDER BY"
                                + " sequence DESC LIMIT 101",
                        id,
                        before);
        boolean more = rows.size() > 100;
        if (more) rows.removeLast();
        var safe = new ArrayList<Map<String, Object>>();
        for (var row : rows) safe.add(publicRow(row, item, definition));
        return map(
                "record_revision",
                item.get("revision"),
                "schema_version",
                version(definition),
                "requirements",
                ApprovalGate.requirements(db, item, definition),
                "result",
                safe,
                "next_before",
                more ? rows.getLast().get("approval_id") : "",
                "pending_count",
                db.count(
                        "SELECT COUNT(*) FROM os_approval WHERE record_id=? AND record_revision=?"
                                + " AND schema_version=? AND status='requested'",
                        id,
                        item.get("revision"),
                        version(definition)));
    }

    static void eligible(
            Store.Db db,
            String table,
            Map<String, Object> item,
            Map<String, Object> definition,
            String target)
            throws SQLException {
        RecordPolicy.require(definition, "update", db.viewerRole);
        FieldAccess.writing(db, definition, map("state", target));
        if (!((List<?>)
                        object(definition.get("flow"))
                                .getOrDefault(object(item.get("data")).get("state"), List.of()))
                .contains(target)) throw new Failure(409, "Transition is not allowed");
        if (table.equals("change_request")
                && List.of("approved", "rejected").contains(target)
                && !db.viewerRole.equals("admin"))
            throw new Failure(403, "An administrator must review this change");
        TypedFields.transition(definition, target, db.viewerRole);
        TypedFields.writing(
                definition, object(item.get("data")), map("state", target), db.viewerRole);
    }

    static String text(Object value, boolean required) {
        if (!(value instanceof String s)
                || (required && s.isBlank())
                || s.codePointCount(0, s.length()) > 2000
                || s.indexOf('\0') >= 0)
            throw new Failure(
                    400,
                    "Review text must be at most 2000 characters and include required content");
        return s.strip();
    }

    static Map<String, Object> request(
            Store.Db db, String table, String id, Map<String, Object> actor, Object body)
            throws SQLException {
        if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439,4)");
        Authentication.requireCurrent(db, actor);
        var item = context(db, table, id);
        var definition = Service.schema(db, table);
        RecordPolicy.require(definition, "update", db.viewerRole);
        var value = object(body);
        if (!value.keySet()
                        .equals(Set.of("expected_revision", "approver", "target_state", "reason"))
                || !(value.get("expected_revision") instanceof Integer revision)
                || revision < 0
                || !(value.get("approver") instanceof String approver)
                || !(value.get("target_state") instanceof String target))
            throw new Failure(400, "Provide expected_revision, approver, target_state and reason");
        String reason = text(value.get("reason"), true);
        if (revision != ((Number) item.get("revision")).intValue())
            throw new Failure(409, "Record changed; reload before requesting review");
        if (!approver.matches("[A-Za-z0-9]{1,80}"))
            throw new Failure(400, "Reviewer username must contain 1–80 ASCII letters or digits");
        var reviewer = db.one("SELECT * FROM os_user WHERE name=?", approver);
        if (reviewer == null
                || ((Number) reviewer.get("active")).intValue() != 1
                || !Set.of("admin", "agent").contains(reviewer.get("role"))
                || reviewer.get("user_id").equals(actor.get("user_id")))
            throw new Failure(400, "Choose a different active staff reviewer");
        String oldId = db.viewerId, oldRole = db.viewerRole;
        try {
            db.viewerId = reviewer.get("user_id").toString();
            db.viewerRole = reviewer.get("role").toString();
            Service.record(db, table, id);
            eligible(db, table, item, definition, target);
        } catch (Failure invalid) {
            throw new Failure(400, "Reviewer cannot access or authorize the proposed transition");
        } finally {
            db.viewerId = oldId;
            db.viewerRole = oldRole;
        }
        if (db.one(
                        "SELECT approval_id FROM os_approval WHERE record_id=? AND"
                                + " record_revision=? AND schema_version=? AND target_state=? AND"
                                + " approver_id=? AND status<>'withdrawn'",
                        id,
                        revision,
                        version(definition),
                        target,
                        reviewer.get("user_id"))
                != null)
            throw new Failure(409, "This reviewer already has a request for this proposal");
        String approval = id();
        db.update(
                "INSERT INTO"
                    + " os_approval(approval_id,record_id,record_revision,schema_version,target_state,requested_by,requester_name,approver_id,approver_name,status,reason,comment,created_at,decided_at,decision_revision)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,'requested',?,'',?,'',-1)",
                approval,
                id,
                revision,
                version(definition),
                target,
                actor.get("user_id"),
                actor.get("name"),
                reviewer.get("user_id"),
                reviewer.get("name"),
                reason,
                now());
        Service.audit(
                db,
                item,
                actor.get("name").toString(),
                "approval.requested",
                map("approval_id", approval));
        return publicRow(
                db.one("SELECT * FROM os_approval WHERE approval_id=?", approval),
                item,
                definition);
    }

    static Map<String, Object> withdraw(
            Store.Db db,
            String table,
            String id,
            String approval,
            Map<String, Object> actor,
            Object body)
            throws SQLException {
        if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439,4)");
        Authentication.requireCurrent(db, actor);
        var item = context(db, table, id);
        var definition = Service.schema(db, table);
        RecordPolicy.require(definition, "update", db.viewerRole);
        var row =
                db.one(
                        "SELECT * FROM os_approval WHERE record_id=? AND approval_id=?",
                        id,
                        approval);
        if (row == null) throw new Failure(404, "Approval not found");
        if (!"admin".equals(db.viewerRole) && !row.get("requested_by").equals(actor.get("user_id")))
            throw new Failure(403, "Only the requester or an administrator can withdraw a review");
        var value = object(body);
        if (!value.keySet().equals(Set.of("comment")))
            throw new Failure(400, "Provide only a withdrawal comment");
        String comment = text(value.get("comment"), true);
        if (!row.get("status").equals("requested"))
            throw new Failure(409, "This review is already completed");
        db.update(
                "UPDATE os_approval SET"
                    + " status='withdrawn',comment=?,decided_at=?,decision_revision=? WHERE"
                    + " approval_id=?",
                comment,
                now(),
                actor.get("auth_revision"),
                approval);
        Service.audit(
                db,
                item,
                actor.get("name").toString(),
                "approval.withdrawn",
                map("approval_id", approval));
        return publicRow(
                db.one("SELECT * FROM os_approval WHERE approval_id=?", approval),
                item,
                definition);
    }

    static Map<String, Object> decide(
            Store.Db db,
            String table,
            String id,
            String approval,
            Map<String, Object> actor,
            Object body)
            throws SQLException {
        if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439,4)");
        Authentication.requireCurrent(db, actor);
        var item = context(db, table, id);
        var definition = Service.schema(db, table);
        var row =
                db.one(
                        "SELECT * FROM os_approval WHERE record_id=? AND approval_id=?",
                        id,
                        approval);
        if (row == null) throw new Failure(404, "Approval not found");
        if (!row.get("approver_id").equals(actor.get("user_id")))
            throw new Failure(403, "Only the assigned reviewer can decide");
        var value = object(body);
        if (!value.keySet().equals(Set.of("decision", "comment"))
                || !Set.of("approved", "rejected")
                        .contains(Objects.toString(value.get("decision"), "")))
            throw new Failure(400, "Choose approved or rejected and provide a comment field");
        String comment = text(value.get("comment"), value.get("decision").equals("rejected"));
        if (!row.get("status").equals("requested"))
            throw new Failure(409, "This review is already decided");
        if (!current(row, item, definition))
            throw new Failure(409, "Proposal changed; request a new review");
        eligible(db, table, item, definition, row.get("target_state").toString());
        db.update(
                "UPDATE os_approval SET status=?,comment=?,decided_at=?,decision_revision=? WHERE"
                        + " approval_id=?",
                value.get("decision"),
                comment,
                now(),
                actor.get("auth_revision"),
                approval);
        Service.audit(
                db,
                item,
                actor.get("name").toString(),
                "approval.decided",
                map("approval_id", approval, "decision", value.get("decision")));
        return publicRow(
                db.one("SELECT * FROM os_approval WHERE approval_id=?", approval),
                item,
                definition);
    }
}
