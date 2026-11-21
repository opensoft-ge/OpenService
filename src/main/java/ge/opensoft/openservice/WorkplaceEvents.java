package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.LocalDate;
import java.util.*;

final class WorkplaceEvents {
    private WorkplaceEvents() {}

    static final List<String> STREAMS = List.of("access", "equipment", "workplace");
    static final Map<String, List<String>> WORK =
            Map.of(
                    "hire",
                            List.of(
                                    "Provision approved day-one access",
                                    "Prepare and issue starter equipment",
                                    "Reserve and prepare the employee workspace"),
                    "move",
                            List.of(
                                    "Review access for the new location",
                                    "Transfer assigned equipment",
                                    "Prepare the destination and release the former workspace"),
                    "departure",
                            List.of(
                                    "Revoke access on the departure date",
                                    "Recover and inventory issued equipment",
                                    "Release the workspace and collect entry credentials"));

    static void reserve(Store.Db db, String space, String day, String caseId) throws Exception {
        for (var row : list(db, "workplace-event")) {
            var other = object(row.get("definition"));
            require(
                    row.get("subject").equals(caseId)
                            || "cancelled".equals(other.get("state"))
                            || !List.of(space, day)
                                    .equals(List.of(other.get("space"), other.get("effective"))),
                    "Space already reserved for this date");
        }
    }

    static Object run(Store store, Store.Db db, String op, Map<String, Object> b) throws Exception {
        String hr = id(b, "case_id");
        boolean manager = "admin".equals(db.viewerRole);
        if (manager) EmployeeCases.record(db, hr, false);
        var event = object(get(db, "workplace-event", hr).get("definition"));
        if (op.equals("schedule")) {
            require(
                    event.isEmpty() && EmployeeOnboarding.open(EmployeeCases.record(db, hr, false)),
                    "Choose an open HR case without a workplace event");
            String type = choice(b, "type", "hire", "move", "departure"),
                    day = ProjectDelivery.date(b.get("effective")),
                    space = text(b, "space", 100);
            require(
                    !LocalDate.parse(day).isBefore(LocalDate.now()),
                    "Choose today or a future effective date");
            reserve(db, space, day, hr);
            var tasks = map();
            for (String stream : STREAMS) {
                String team = id(b, stream + "_team");
                Service.readRecord(db, "sys_user_group", team);
                tasks.put(
                        stream,
                        map(
                                "team",
                                team,
                                "state",
                                "pending",
                                "due",
                                day,
                                "work",
                                WORK.get(type).get(STREAMS.indexOf(stream))));
            }
            event.putAll(
                    map(
                            "type",
                            type,
                            "effective",
                            day,
                            "space",
                            space,
                            "state",
                            "scheduled",
                            "tasks",
                            tasks));
        } else {
            require(!event.isEmpty(), "Event unavailable");
            var tasks = object(event.get("tasks"));
            if (op.equals("readiness")) {
                var visible = map();
                for (var e : tasks.entrySet())
                    if (manager
                            || Fulfilment.member(db, object(e.getValue()).get("team").toString()))
                        visible.put(e.getKey(), e.getValue());
                require(!visible.isEmpty(), "No assigned workplace tasks");
                if (manager) return get(db, "workplace-event", hr);
                return map(
                        "tasks",
                        visible,
                        "revision",
                        get(db, "workplace-event", hr).get("revision"));
            }
            require(!"cancelled".equals(event.get("state")), "Event was cancelled");
            if (op.equals("cancel")) {
                for (Object value : tasks.values()) {
                    var task = object(value);
                    task.put(
                            "state",
                            "done".equals(task.get("state")) ? "reversal_review" : "cancelled");
                }
                event.putAll(map("state", "cancelled", "reason", text(b, "note", 400)));
            } else if (op.equals("move")) {
                require("scheduled".equals(event.get("state")), "Event is already complete");
                String day = ProjectDelivery.date(b.get("effective"));
                require(!LocalDate.parse(day).isBefore(LocalDate.now()), "Choose a future date");
                reserve(db, event.get("space").toString(), day, hr);
                event.put("effective", day);
                for (Object value : tasks.values()) {
                    var task = object(value);
                    if ("pending".equals(task.get("state"))) task.put("due", day);
                }
            } else {
                String stream = choice(b, "stream", "access", "equipment", "workplace");
                var task = object(tasks.get(stream));
                require(
                        Fulfilment.member(db, task.get("team").toString())
                                && "pending".equals(task.get("state")),
                        "A pending task assigned to your team is required");
                var parent =
                        db.one(
                                "SELECT data FROM os_record WHERE sys_id=? AND \"table\"='hr_case'",
                                hr);
                require(
                        parent != null
                                && !Set.of("closed", "cancelled")
                                        .contains(object(parent.get("data")).get("state")),
                        "Employment case has closed");
                task.putAll(
                        map(
                                "state",
                                "done",
                                "outcome",
                                text(b, "note", 500),
                                "actor",
                                db.viewerId,
                                "completed",
                                now()));
                if (tasks.values().stream().allMatch(v -> "done".equals(object(v).get("state"))))
                    event.put("state", "ready");
            }
        }
        save(db, "workplace-event", hr, b.get("revision"), event);
        if (manager) EmployeeAgreements.refresh(db,hr,event);
        if (!manager) return map("saved", true);
        return get(db, "workplace-event", hr);
    }
}
