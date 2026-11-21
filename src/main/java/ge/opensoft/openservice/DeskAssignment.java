package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class DeskAssignment {
    private DeskAssignment() {}

    static void created(Store.Db db, Map<String, Object> item) throws java.sql.SQLException {
        String table = item.get("table").toString();
        if (!Set.of("incident", "sc_request", "customer_case").contains(table)) return;
        var rule = object(get(db, "desk-route-policy", table).get("definition"));
        if (!Boolean.TRUE.equals(rule.get("active"))) return;
        save(
                db,
                "desk-offer",
                item.get("sys_id").toString(),
                0,
                map(
                        "record_id",
                        item.get("sys_id"),
                        "table",
                        table,
                        "skill",
                        rule.get("skill"),
                        "due",
                        System.currentTimeMillis()
                                + ((Number) rule.get("minutes")).longValue() * 60000,
                        "declined",
                        List.of()));
    }

    static final Set<String> TERMINAL =
            Set.of("6", "7", "8", "closed", "resolved", "complete", "cancelled");

    static Map<String, Integer> loads(Store.Db db) throws Exception {
        var result = new HashMap<String, Integer>();
        for (var row :
                db.all(
                        "SELECT d.definition,r.data FROM os_enterprise_document d JOIN os_record r"
                                + " ON r.sys_id=d.subject WHERE d.area='desk-offer'")) {
            var assignment = object(row.get("definition"));
            var fields = object(row.get("data"));
            if (assignment.containsKey("owner")
                    && !TERMINAL.contains(Objects.toString(fields.get("state"), "")))
                result.merge(assignment.get("owner").toString(), 1, Integer::sum);
        }
        return result;
    }

    static String availability(
            Store.Db db,
            String user,
            Map<String, Object> work,
            Map<String, Integer> loads,
            Map<String, Object> item)
            throws Exception {
        if (((List<?>) work.get("declined")).contains(user)) return "declined";
        var person =
                db.one(
                        "SELECT user_id,role,active,password_reset_required FROM os_user WHERE"
                                + " user_id=?",
                        user);
        if (person == null
                || !person.get("active").equals(1)
                || !person.get("password_reset_required").equals(0)
                || !Set.of("admin", "agent").contains(person.get("role"))) return "inactive";
        var data = object(get(db, "desk-capacity", user).get("definition"));
        if (!((List<?>) data.getOrDefault("skills", List.of())).contains(work.get("skill")))
            return "missing_skill";
        if (!Boolean.TRUE.equals(
                object(get(db, "desk-presence", user).get("definition")).get("active")))
            return "offline";
        if (loads.getOrDefault(user, 0) >= ((Number) data.get("capacity")).intValue())
            return "at_capacity";
        return SlaAlerts.visible(db, person, item) ? "eligible" : "outside_record_scope";
    }

    static Object command(Store store, Store.Db db, String op, Map<String, Object> b)
            throws Exception {
        if (op.equals("auto_route")) {
            String table = choice(b, "table", "sc_request", "incident", "customer_case");
            require(b.get("active") instanceof Boolean, "Choose an enabled state");
            return put(
                    db,
                    "desk-route-policy",
                    table,
                    b.get("revision"),
                    map(
                            "skill",
                            text(b, "skill", 80),
                            "minutes",
                            integer(b.get("minutes"), 1, 43200),
                            "active",
                            b.get("active")));
        }
        if (op.equals("capacity")) {
            String agent = DutyRosters.responder(db, b.get("agent_id"));
            var skills = new TreeSet<String>();
            for (Object value : batch(b.get("skills"), 40)) skills.add(WorkJobs.text(value, 80));
            return put(
                    db,
                    "desk-capacity",
                    agent,
                    b.get("revision"),
                    map("skills", skills, "capacity", integer(b.get("capacity"), 1, 100)));
        }
        if (op.equals("presence")) {
            require(b.get("active") instanceof Boolean, "Choose online or offline");
            return put(
                    db,
                    "desk-presence",
                    db.viewerId,
                    get(db, "desk-presence", db.viewerId).get("revision"),
                    map("active", b.get("active")));
        }
        if (Set.of("offers", "waiting").contains(op)) {
            String user = op.equals("offers") ? id(b, "agent_id") : db.viewerId;
            require(
                    db.viewerId.equals(user) || "admin".equals(db.viewerRole),
                    "View your own offers");
            var result = new ArrayList<Map<String, Object>>();
            var used = loads(db);
            var roster = list(db, "desk-capacity");
            for (var row : list(db, "desk-offer")) {
                var work = object(row.get("definition"));
                if (work.containsKey("owner")) continue;
                Map<String, Object> item;
                try {
                    item =
                            Service.readRecord(
                                    db,
                                    work.get("table").toString(),
                                    work.get("record_id").toString());
                } catch (Failure denied) {
                    if (denied.status != 403 && denied.status != 404) throw denied;
                    continue;
                }
                if (TERMINAL.contains(Objects.toString(object(item.get("data")).get("state"), "")))
                    continue;
                if (op.equals("offers")
                        && !availability(db, user, work, used, item).equals("eligible")) continue;
                var matches = new ArrayList<>();
                var reasons = new TreeMap<String, Integer>();
                if (op.equals("waiting"))
                    for (var person : roster) {
                        String candidate = person.get("subject").toString();
                        String reason = availability(db, candidate, work, used, item);
                        if (reason.equals("eligible")) matches.add(candidate);
                        else reasons.merge(reason, 1, Integer::sum);
                    }
                var clock =
                        db.one(
                                "SELECT state,remaining_ms,breached_at FROM os_sla WHERE"
                                    + " record_id=? AND state IN ('running','breached') ORDER BY"
                                    + " remaining_ms LIMIT 1",
                                work.get("record_id"));
                if (clock != null) {
                    work.put("clock", clock);
                    long deadline =
                            "breached".equals(clock.get("state"))
                                    ? ((Number) clock.get("breached_at")).longValue()
                                    : System.currentTimeMillis()
                                            + ((Number) clock.get("remaining_ms")).longValue();
                    work.put("due", Math.min(((Number) work.get("due")).longValue(), deadline));
                }
                result.add(
                        map(
                                "work",
                                work,
                                "revision",
                                row.get("revision"),
                                "eligible",
                                matches,
                                "unavailable",
                                reasons));
            }
            result.sort(
                    Comparator.comparingLong(
                            x -> ((Number) object(x.get("work")).get("due")).longValue()));
            return result.stream()
                    .limit(op.equals("waiting") ? integer(b.get("limit"), 1, 200) : 200)
                    .toList();
        }
        String table = choice(b, "table", "incident", "sc_request", "customer_case"),
                record = id(b, "record_id");
        var item = Service.readRecord(db, table, record);
        var row = get(db, "desk-offer", record);
        if (op.equals("route"))
            return put(
                    db,
                    "desk-offer",
                    record,
                    0,
                    map(
                            "record_id",
                            record,
                            "table",
                            table,
                            "skill",
                            text(b, "skill", 80),
                            "due",
                            ChangeScheduling.instant(b.get("due")),
                            "declined",
                            List.of()));
        var work = definition(db, "desk-offer", record);
        var declined = new ArrayList<>((List<?>) work.get("declined"));
        require(
                !TERMINAL.contains(Objects.toString(object(item.get("data")).get("state"), "")),
                "Work is already complete");
        if (!op.equals("override"))
            require(
                    !work.containsKey("owner")
                            && availability(db, db.viewerId, work, loads(db), item)
                                    .equals("eligible"),
                    "Offer unavailable");
        if (op.equals("decline")) {
            var users = new ArrayList<Object>(declined);
            users.add(db.viewerId);
            work.put("declined", users);
        } else {
            String owner =
                    op.equals("override")
                            ? DutyRosters.responder(db, b.get("agent_id"))
                            : db.viewerId;
            if (op.equals("override"))
                require(
                        SlaAlerts.visible(db, AgentPlans.account(db, owner), item),
                        "Choose an assignee who can access this record");
            var schema = Service.schema(db, table);
            var assignment = object(schema.getOrDefault("assignment", map()));
            String field = Objects.toString(assignment.getOrDefault("user_field", "assigned_to"));
            if (object(schema.get("fields")).containsKey(field)) {
                require(
                        op.equals("override")
                                || Objects.toString(object(item.get("data")).get(field), "")
                                        .isBlank(),
                        "Record already assigned");
                Service.update(
                        db,
                        table,
                        Service.record(db, table, record),
                        map(
                                field,
                                assignment.containsKey("user_field")
                                                || "account"
                                                        .equals(
                                                                object(
                                                                                TypedFields.rules(
                                                                                                schema)
                                                                                        .getOrDefault(
                                                                                                field,
                                                                                                map()))
                                                                        .get("type"))
                                        ? owner
                                        : Service.account(db, owner).get("name")),
                        actor(db),
                        db.viewerRole,
                        item.get("revision").toString());
            }
            work.putAll(map("owner", owner, "by", db.viewerId, "at", now()));
            if (op.equals("override")) work.put("override_reason", text(b, "note", 1000));
            Service.audit(
                    db,
                    item,
                    actor(db),
                    "desk.assigned",
                    map(
                            "owner",
                            owner,
                            "reason",
                            work.getOrDefault("override_reason", "Offer accepted")));
        }
        return put(db, "desk-offer", record, b.get("revision"), work);
    }
}
