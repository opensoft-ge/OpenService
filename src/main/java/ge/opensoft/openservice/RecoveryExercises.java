package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class RecoveryExercises {
    private RecoveryExercises() {}

    static Object command(Store.Db db, String op, Map<String, Object> b) throws Exception {
        if (op.equals("plan")) {
            String component = text(b, "component", 100),
                    service = text(b, "service_id", 300),
                    roster = id(b, "roster_id");
            checkDependency(db, service, component);
            definition(db, "duty-roster", roster);
            var steps = new ArrayList<Object>();
            for (Object raw : batch(b.get("steps"), 24)) {
                var step = object(raw);
                keys(step, "title", "owner_id");
                steps.add(
                        map(
                                "title",
                                text(step, "title", 160),
                                "owner",
                                DutyRosters.responder(db, step.get("owner_id")),
                                "state",
                                "pending"));
            }
            require(!steps.isEmpty(), "Assign at least one recovery task");
            var planId=id(b,"plan_id");
            var previous=object(get(db,"continuity-plan",planId).get("definition"));
            var updated=map(
                            "title",
                            text(b, "title", 160),
                            "component",
                            component,
                            "service",
                            service,
                            "roster",
                            roster,
                            "rto",
                            integer(b.get("rto_minutes"), 1, 43200),
                            "rpo",
                            integer(b.get("rpo_minutes"), 0, 43200),
                            "steps",
                            steps);
            if(previous.containsKey("business"))updated.put("business",previous.get("business"));
            return put(db,"continuity-plan",planId,b.get("revision"),updated);
        }
        if (op.equals("exercise")) {
            String plan = id(b, "plan_id");
            var spec = definition(db, "continuity-plan", plan);
            ProblemInvestigations.revision(
                    b.get("revision"), get(db, "continuity-plan", plan).get("revision"));
            var dependencies =
                    checkDependency(
                            db, spec.get("service").toString(), spec.get("component").toString());
            long start = System.currentTimeMillis();
            var duty =
                    DutyRosters.coverage(
                            definition(db, "duty-roster", spec.get("roster").toString()), start);
            for (var step : objects(spec.get("steps")))
                DutyRosters.responder(db, step.get("owner"));
            return put(
                    db,
                    "recovery-exercise",
                    id(b, "new_id"),
                    0,
                    map(
                            "plan",
                            plan,
                            "version",
                            b.get("revision"),
                            "spec",
                            spec,
                            "dependencies",
                            dependencies,
                            "duty",
                            duty,
                            "start",
                            start,
                            "state",
                            "running",
                            "steps",
                            spec.get("steps")));
        }
        String id = id(b, "exercise_id");
        var data = definition(db, "recovery-exercise", id);
        var steps = objects(data.get("steps"));
        var duty = object(data.get("duty"));
        var spec = object(data.get("spec"));
        String state = data.get("state").toString();
        require(
                "admin".equals(db.viewerRole)
                        || duty.get("primary").equals(db.viewerId)
                        || steps.stream().anyMatch(s -> db.viewerId.equals(s.get("owner"))),
                "Assigned exercise access required");
        if (op.equals("inspect")) return get(db, "recovery-exercise", id);
        if (op.equals("task")) {
            var step = steps.get(integer(b.get("step"), 1, steps.size()) - 1);
            require(
                    state.equals("running")
                            && step.get("state").equals("pending")
                            && db.viewerId.equals(step.get("owner")),
                    "Choose your unfinished recovery task");
            step.putAll(map("state", "complete", "note", text(b, "note", 1500), "at", now()));
        } else if (op.equals("measure")) {
            require(
                    state.equals("running")
                            && steps.stream().allMatch(s -> "complete".equals(s.get("state"))),
                    "Finish all recovery tasks first");
            long start = ((Number) data.get("start")).longValue(),
                    restored =
                            ServiceOutages.time(b.get("restored_at"), System.currentTimeMillis()),
                    point = ServiceOutages.time(b.get("restore_point"), start);
            require(restored >= start, "Restoration must follow the disruption");
            long elapsed = restored - start, loss = start - point;
            data.putAll(
                    map(
                            "state",
                            BusinessTolerance.acceptable(spec,data) && within(spec, elapsed, loss) ? "passed" : "needs_correction",
                            "actual_recovery_ms",
                            elapsed,
                            "actual_data_loss_ms",
                            loss,
                            "measured_at",
                            restored,
                            "owner",
                            duty.get("primary"),
                            "evidence",
                            text(b, "evidence", 2000)));
        } else if (op.equals("correct")) {
            require(
                    state.equals("needs_correction") && db.viewerId.equals(data.get("owner")),
                    "Assigned correction owner required");
            data.putAll(
                    map(
                            "state",
                            "awaiting_retest",
                            "correction",
                            text(b, "note", 2000),
                            "corrected_at",
                            System.currentTimeMillis()));
        } else {
            var later = definition(db, "recovery-exercise", id(b, "later_id"));
            require(
                    state.equals("awaiting_retest")
                            && data.get("plan").equals(later.get("plan"))
                            && data.get("version").equals(later.get("version"))
                            && "passed".equals(later.get("state"))
                            && ((Number) later.get("start")).longValue()
                                    >= ((Number) data.get("corrected_at")).longValue(),
                    "Choose a later passing exercise using the original plan version");
            data.putAll(
                    map(
                            "state",
                            "verified",
                            "retest",
                            b.get("later_id"),
                            "verified_by",
                            db.viewerId));
        }
        return put(db, "recovery-exercise", id, b.get("revision"), data);
    }

    static boolean within(Map<String, Object> spec, long recovery, long loss) {
        return recovery <= ((Number) spec.get("rto")).longValue() * 60000
                && loss <= ((Number) spec.get("rpo")).longValue() * 60000;
    }

    static Set<String> checkDependency(Store.Db db, String service, String component)
            throws Exception {
        var graph = FederatedMaps.graph(db);
        require(graph.roots().containsKey(service), "Choose a mapped service");
        var nodes = FederatedMaps.trace(graph, graph.roots().get(service));
        require(nodes.contains(component), "Choose a current service dependency");
        return nodes;
    }
}
