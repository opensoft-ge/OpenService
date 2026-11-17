package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** Personal work discovery applies record and field visibility before paging. */
final class ApprovalInbox {
    private ApprovalInbox() {}

    static Map<String, Object> page(Store.Db db, Map<String, String> args) throws SQLException {
        if (!Set.of("admin", "agent").contains(db.viewerRole))
            throw new Failure(403, "Staff access is required for approvals");
        String status = args.getOrDefault("status", "requested");
        if (!Set.of("status", "before").containsAll(args.keySet())
                || !Set.of("requested", "approved", "rejected", "withdrawn").contains(status))
            throw new Failure(400, "Choose requested, approved, rejected or withdrawn reviews");
        long before = Long.MAX_VALUE;
        if (args.containsKey("before")) {
            String cursor = args.get("before");
            if (!cursor.matches("[0-9a-f]{32}")) throw new Failure(400, "Invalid approval cursor");
            var row =
                    db.one(
                            "SELECT sequence FROM os_approval WHERE approval_id=? AND"
                                    + " approver_id=?",
                            cursor,
                            db.viewerId);
            if (row == null)
                throw new Failure(400, "Approval cursor is unavailable for this account");
            before = ((Number) row.get("sequence")).longValue();
        }
        var schemas = new LinkedHashMap<String, Map<String, Object>>();
        for (var row : db.all("SELECT name,definition FROM os_schema ORDER BY name")) {
            var definition = object(row.get("definition"));
            if (RecordPolicy.allows(definition,"read",db.viewerRole)
                    && object(definition.get("fields")).containsKey("state")
                    && FieldAccess.allowed(db, definition, "state"))
                schemas.put(row.get("name").toString(), definition);
        }
        var result = new ArrayList<Map<String, Object>>();
        String next = "";
        if (!schemas.isEmpty()) {
            var parameters = new ArrayList<Object>(List.of(db.viewerId, status, before));
            parameters.addAll(schemas.keySet());
            String visible = RecordAccess.filter(db, "r.sys_id", parameters);
            var rows =
                    db.all(
                            "SELECT a.*,r.\"table\" AS record_table FROM os_approval a JOIN"
                                + " os_record r ON r.sys_id=a.record_id WHERE a.approver_id=? AND"
                                + " a.status=? AND a.sequence<? AND r.\"table\" IN ("
                                    + String.join(",", Collections.nCopies(schemas.size(), "?"))
                                    + ") AND "
                                    + visible
                                    + " ORDER BY a.sequence DESC LIMIT 101",
                            parameters.toArray());
            if (rows.size() > 100) {
                rows.removeLast();
                next = rows.getLast().get("approval_id").toString();
            }
            for (var row : rows) {
                String table = row.get("record_table").toString();
                var item = Service.record(db, table, row.get("record_id").toString());
                var safe = Approvals.publicRow(row, item, schemas.get(table));
                safe.put(
                        "record_number",
                        FieldAccess.serialize(db, item).getOrDefault("number", ""));
                safe.put("table_label", schemas.get(table).get("label"));
                result.add(safe);
            }
        }
        return map("result", result, "status", status, "next_before", next);
    }
}
