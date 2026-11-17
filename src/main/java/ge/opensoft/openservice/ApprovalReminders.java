package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

/** Deduplicated retained in-app reminders, with current reviewer authorization checked at delivery. */
final class ApprovalReminders {
    private ApprovalReminders() {}

    static Map<String, Object> send(Store.Db db, String actor, String id, Object input)
            throws Exception {
        Service.requireAccountAdministrator(db, actor);
        require(object(input).isEmpty(), "The reminder does not accept extra fields");
        var row = db.one("SELECT a.*,r.\"table\" AS table_name FROM os_approval a JOIN os_record r ON r.sys_id=a.record_id WHERE a.approval_id=?", id);
        if (row == null) throw new Failure(404, "Approval not found");
        String table = row.get("table_name").toString(), record = row.get("record_id").toString();
        var item = Approvals.context(db, table, record);
        var definition = Service.schema(db, table);
        if (!row.get("status").equals("requested") || !Approvals.current(row, item, definition))
            throw new Failure(409, "Only a current pending review can be reminded");
        var reviewer = db.one("SELECT * FROM os_user WHERE user_id=? AND active=1 AND role IN ('admin','agent')", row.get("approver_id"));
        if (reviewer == null || PasswordReset.required(reviewer)) throw new Failure(409, "The reviewer is no longer available");
        String previousId = db.viewerId, previousRole = db.viewerRole;
        try {
            db.viewerId = reviewer.get("user_id").toString();
            db.viewerRole = reviewer.get("role").toString();
            Service.record(db, table, record);
            Approvals.eligible(db, table, item, definition, row.get("target_state").toString());
        } finally {
            db.viewerId = previousId;
            db.viewerRole = previousRole;
        }
        String notice = Passwords.tokenHash("approval-reminder:" + id + ":" + row.get("approver_id")).substring(0, 32);
        if (db.one("SELECT notice_id FROM os_notice WHERE notice_id=?", notice) != null)
            return map("delivered", true, "duplicate", true);
        var result = Notifications.deliver(db, notice, map("username", reviewer.get("name"),
                "message", "A review is waiting for you in My approvals. Reference: " + id), System.currentTimeMillis());
        if (Boolean.TRUE.equals(result.get("delivered")))
            Service.audit(db, item, Service.account(db, actor).get("name").toString(),
                    "approval.reminded", map("approval_id", id, "approver_id", row.get("approver_id")));
        return map("delivered", result.get("delivered"), "duplicate", false);
    }
}
