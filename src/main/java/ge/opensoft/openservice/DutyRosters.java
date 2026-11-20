package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

final class DutyRosters {
    private DutyRosters() {}

    static String responder(Store.Db db, Object value) throws Exception {
        String id = EnterpriseData.id(value);
        var user = AgentPlans.account(db, id);
        require(Set.of("admin", "agent").contains(user.get("role")), "Choose a staff responder");
        return id;
    }

    static Map<String, Object> coverage(Map<String, Object> roster, long at) {
        var start = Instant.ofEpochMilli(((Number) roster.get("anchor")).longValue());
        var zone = ZoneId.of(roster.get("zone").toString());
        long elapsed =
                Duration.between(
                                start.atZone(zone).toLocalDateTime(),
                                Instant.ofEpochMilli(at).atZone(zone).toLocalDateTime())
                        .toHours();
        require(at >= start.toEpochMilli(), "Coverage has not started");
        var people = (List<?>) roster.get("people");
        long shift = Math.floorDiv(elapsed, (Integer) roster.get("hours"));
        for (var override : objects(roster.get("overrides"))) {
            if (at >= ((Number) override.get("start")).longValue()
                    && at < ((Number) override.get("end")).longValue()) return override;
        }
        return map(
                "primary",
                people.get(Math.floorMod(shift, people.size())),
                "backup",
                people.get(Math.floorMod(shift + 1, people.size())));
    }

    static Object run(Store store, Store.Db db, String op, Map<String, Object> b) throws Exception {
        if (op.equals("team")) {
            String team = id(b, "group_id"), roster = id(b, "roster_id");
            Service.readRecord(db, "sys_user_group", team);
            definition(db, "duty-roster", roster);
            return put(
                    db,
                    "duty-team",
                    team,
                    b.get("revision"),
                    map("roster", roster, "owner", db.viewerId));
        }
        if (op.equals("watch")) {
            String roster = id(b, "roster_id");
            definition(db, "duty-roster", roster);
            return put(
                    db,
                    "duty-watch",
                    text(b, "source", 80),
                    b.get("revision"),
                    map("roster", roster, "owner", db.viewerId));
        }
        if (op.equals("roster")) {
            String id = id(b, "roster_id"), zone = text(b, "zone", 100);
            require(ZoneId.getAvailableZoneIds().contains(zone), "Choose an IANA time zone");
            var people = new LinkedHashSet<String>();
            for (Object value : batch(b.get("people"), 32))
                require(people.add(responder(db, value)), "Duplicate responder");
            require(people.size() >= 2, "Choose a primary and backup");
            var previous = object(get(db, "duty-roster", id).get("definition"));
            return put(
                    db,
                    "duty-roster",
                    id,
                    b.get("revision"),
                    map(
                            "title",
                            text(b, "title", 160),
                            "anchor",
                            ChangeScheduling.instant(b.get("anchor")),
                            "zone",
                            zone,
                            "hours",
                            integer(b.get("hours"), 1, 168),
                            "minutes",
                            integer(b.get("minutes"), 1, 240),
                            "people",
                            people,
                            "overrides",
                            previous.getOrDefault("overrides", List.of())));
        }
        if (Set.of("override", "who").contains(op)) {
            String id = id(b, "roster_id");
            var roster = definition(db, "duty-roster", id);
            if (op.equals("who")) return coverage(roster, ChangeScheduling.instant(b.get("at")));
            long start = ChangeScheduling.instant(b.get("start")),
                    end = ChangeScheduling.instant(b.get("end"));
            String primary = responder(db, b.get("primary")),
                    backup = responder(db, b.get("backup"));
            require(
                    start < end && !primary.equals(backup),
                    "Choose a valid interval and two responders");
            var shifts = new ArrayList<>(objects(roster.get("overrides")));
            require(
                    shifts.stream()
                            .noneMatch(
                                    s ->
                                            start < ((Number) s.get("end")).longValue()
                                                    && end > ((Number) s.get("start")).longValue()),
                    "Overlapping overrides");
            shifts.add(
                    map(
                            "start",
                            start,
                            "end",
                            end,
                            "primary",
                            primary,
                            "backup",
                            backup,
                            "note",
                            text(b, "note", 300)));
            roster.put("overrides", shifts);
            return put(db, "duty-roster", id, b.get("revision"), roster);
        }
        String incident = id(b, "incident_id");
        var record = Service.readRecord(db, "incident", incident);
        var row = get(db, "incident-page", incident);
        var data = object(row.get("definition"));
        if (op.equals("page") && data.isEmpty()) {
            String rosterId = id(b, "roster_id");
            var roster = definition(db, "duty-roster", rosterId);
            require(
                    Set.of("1", "2", "3").contains(object(record.get("data")).get("state")),
                    "Choose an open incident");
            require(
                    "declared".equals(MajorIncidents.major(db, incident).get("status"))
                            || "1"
                                    .equals(
                                            Objects.toString(
                                                    object(record.get("data")).get("priority"))),
                    "Choose a declared major or critical incident");
            long now = System.currentTimeMillis();
            var team = coverage(roster, now);
            data.putAll(
                    map(
                            "state",
                            "primary",
                            "roster",
                            rosterId,
                            "primary",
                            team.get("primary"),
                            "backup",
                            team.get("backup"),
                            "due",
                            now + 60000L * (Integer) roster.get("minutes"),
                            "owner",
                            db.viewerId,
                            "history",
                            List.of()));
            notify(store, db, incident, data, "primary", now);
            save(db, "incident-page", incident, 0, data);
        } else if (op.equals("page")) {
            require(
                    id(b, "roster_id").equals(data.get("roster")),
                    "Incident already belongs to another roster");
        } else if (op.equals("handoff")) {
            require(
                    !data.isEmpty() && !"acknowledged".equals(data.get("state")),
                    "Only open incident duty can be transferred");
            require(
                    "admin".equals(db.viewerRole)
                            || db.viewerId.equals(data.get(data.get("state").toString())),
                    "Active responder required");
            String tier = data.get("state").toString(), next = responder(db, b.get("responder_id"));
            require(
                    !List.of(data.get("primary"), data.get("backup")).contains(next),
                    "Choose a responder outside the current duty pair");
            var history = new ArrayList<>(objects(data.get("history")));
            history.add(
                    map(
                            "handoff_from",
                            data.get(tier),
                            "to",
                            next,
                            "actor",
                            db.viewerId,
                            "note",
                            text(b, "note", 500),
                            "at",
                            now()));
            data.put("history", history);
            data.put(tier, next);
            notify(store, db, incident, data, tier, System.currentTimeMillis());
            save(db, "incident-page", incident, b.get("revision"), data);
        } else if (op.equals("ack")) {
            require(
                    List.of(data.getOrDefault("primary", ""), data.getOrDefault("backup", ""))
                            .contains(db.viewerId),
                    "Only a paged responder can acknowledge");
            require(
                    db.viewerId.equals(data.get("primary")) || !"primary".equals(data.get("state")),
                    "Backup has not been paged");
            if (!"acknowledged".equals(data.get("state"))) {
                data.putAll(
                        map(
                                "state",
                                "acknowledged",
                                "acknowledged_by",
                                db.viewerId,
                                "acknowledged_at",
                                now()));
                save(db, "incident-page", incident, row.get("revision"), data);
            }
        }
        var result = get(db, "incident-page", incident);
        if (op.equals("history")) {
            var detail = object(result.get("definition"));
            for (var event : objects(detail.getOrDefault("history", List.of()))) {
                if (event.containsKey("job"))
                    event.put(
                            "transport",
                            db.one(
                                    "SELECT state,attempts,error FROM os_outbox WHERE job_id=?",
                                    event.get("job")));
            }
        }
        return result;
    }

    static void notify(
            Store store,
            Store.Db db,
            String incident,
            Map<String, Object> page,
            String tier,
            long now)
            throws Exception {
        String person = page.get(tier).toString();
        String notice = Values.id();
        var account =
                db.one(
                        "SELECT name,email,email_verified,active,role FROM os_user WHERE user_id=?",
                        person);
        var receipt = map("tier", tier, "user", person, "at", now, "delivery", "unavailable");
        if (account != null
                && account.get("active").equals(1)
                && Set.of("admin", "agent").contains(account.get("role"))) {
            var delivered =
                    Notifications.deliver(
                            db,
                            notice,
                            map(
                                    "username",
                                    account.get("name"),
                                    "message",
                                    "An incident needs your on-call acknowledgment. Open the"
                                            + " on-call workspace."),
                            now);
            receipt.put("inbox", delivered.get("delivered"));
            if (account.get("email_verified").equals(1)) {
                String job =
                        Outbox.enqueue(
                                db,
                                store.secret,
                                "email.duty",
                                key(incident, notice),
                                map(
                                        "type",
                                        "duty",
                                        "incident",
                                        incident,
                                        "person",
                                        person,
                                        "email",
                                        account.get("email"),
                                        "path",
                                        "/enterprise/duty/global"),
                                now + 86400000L);
                receipt.putAll(map("delivery", "queued", "job", job));
            }
        }
        var history = new ArrayList<>(objects(page.get("history")));
        history.add(receipt);
        page.put("history", history);
    }

    static boolean valid(Store.Db db, Map<String, Object> payload) throws Exception {
        var data =
                object(
                        get(db, "incident-page", payload.get("incident").toString())
                                .get("definition"));
        return List.of(data.getOrDefault("primary", ""), data.getOrDefault("backup", ""))
                        .contains(payload.get("person"))
                && !"acknowledged".equals(data.get("state"))
                && db.count(
                                "SELECT COUNT(*) FROM os_user WHERE user_id=? AND active=1 AND"
                                    + " email_verified=1 AND email=? AND role IN ('admin','agent')",
                                payload.get("person"),
                                payload.get("email"))
                        == 1;
    }

    static void tick(Store store, Store.Db db, long now) throws Exception {
        String actor = db.viewerId, role = db.viewerRole;
        try {
            dispatchDue(store, db, now);
        } finally {
            db.viewerId = actor;
            db.viewerRole = role;
        }
    }

    static void pageWatched(
            Store store, Store.Db db, Map<String, Object> watch, String incident, long now)
            throws Exception {
        db.viewerId = watch.get("owner").toString();
        if (db.count(
                        "SELECT COUNT(*) FROM os_user WHERE user_id=? AND role='admin' AND active=1"
                                + " AND password_reset_required=0",
                        db.viewerId)
                == 0) return;
        db.viewerRole = "admin";
        var roster = definition(db, "duty-roster", watch.get("roster").toString());
        if (((Number) roster.get("anchor")).longValue() > now) return;
        var beforePage = db.connection.setSavepoint();
        try {
            run(store, db, "page", map("incident_id", incident, "roster_id", watch.get("roster")));
        } catch (Failure unavailable) {
            db.connection.rollback(beforePage);
        } finally {
            db.connection.releaseSavepoint(beforePage);
        }
    }

    static void dispatchDue(Store store, Store.Db db, long now) throws Exception {
        lock(db);
        for (var team : list(db, "duty-team")) {
            var policy = object(team.get("definition"));
            String group = team.get("subject").toString();
            String field = db.postgres ? "r.data->>'%s'" : "json_extract(r.data,'$.%s')";
            String sql =
                    "SELECT r.sys_id FROM os_record r WHERE r.\"table\"='incident' AND "
                            + field.formatted("assignment_group")
                            + "=? AND "
                            + field.formatted("priority")
                            + "='1' AND "
                            + field.formatted("state")
                            + " IN ('1','2','3') AND NOT EXISTS(SELECT 1 FROM"
                            + " os_enterprise_document d WHERE d.area='incident-page' AND"
                            + " d.subject=r.sys_id) ORDER BY r.sys_id LIMIT 50";
            for (var candidate : db.all(sql, group))
                pageWatched(store, db, policy, candidate.get("sys_id").toString(), now);
        }
        for (var episode : list(db, "service-episode")) {
            var item = object(episode.get("definition"));
            String incident = item.get("incident").toString();
            if (!"open".equals(item.get("state"))
                    || !item.containsKey("commander")
                    || !get(db, "incident-page", incident).get("revision").equals(0)) continue;
            var watch =
                    object(get(db, "duty-watch", item.get("source").toString()).get("definition"));
            if (watch.isEmpty()) continue;
            pageWatched(store, db, watch, incident, now);
        }
        for (var row : list(db, "incident-page")) {
            var page = object(row.get("definition"));
            if (!"primary".equals(page.get("state"))
                    || ((Number) page.get("due")).longValue() > now) continue;
            String incident = row.get("subject").toString();
            db.viewerId = page.get("owner").toString();
            notify(store, db, incident, page, "backup", now);
            page.put("state", "backup");
            save(db, "incident-page", incident, row.get("revision"), page);
        }
    }
}
