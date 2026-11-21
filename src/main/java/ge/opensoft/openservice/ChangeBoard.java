package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class ChangeBoard {
    private ChangeBoard() {}

    static Set<String> services(Store.Db db, String ci) throws Exception {
        var graph = FederatedMaps.graph(db);
        var affected = new TreeSet<String>();
        for (var node : graph.facts().entrySet())
            if (node.getValue().stream()
                    .map(Values::object)
                    .anyMatch(f -> ci.equals(f.get("ci_id"))))
                affected.addAll(MapHealth.impact(graph, node.getKey()));
        return affected;
    }

    static Map<String, Object> agenda(Store.Db db, String release, Map<String, Object> slot)
            throws Exception {
        long start = ((Number) slot.get("start")).longValue(),
                end = ((Number) slot.get("end")).longValue();
        var affected = services(db, slot.get("ci").toString());
        var conflicts =
                new ArrayList<>(ChangeWindows.conflicts(db, slot.get("ci").toString(), start, end));
        for (var row : list(db, "cab-window")) {
            var other = object(row.get("definition"));
            if (release.equals(row.get("subject"))
                    || !"approved".equals(other.get("state"))
                    || ((Number) other.get("end")).longValue() <= start
                    || ((Number) other.get("start")).longValue() >= end) continue;
            if (other.get("ci").equals(slot.get("ci"))
                    || !Collections.disjoint(affected, services(db, other.get("ci").toString())))
                conflicts.add(row.get("subject").toString());
        }
        return map(
                "services",
                affected,
                "objectives", ReliabilityObjectives.review(db,slot.get("ci").toString()),
                "conflicts",
                conflicts,
                "coverage",
                DutyRosters.coverage(
                        definition(db, "duty-roster", slot.get("roster").toString()), start));
    }

    static Object command(Store.Db db, String op, Map<String, Object> b) throws Exception {
        String release = id(b, "release_id");
        var data = definition(db, "pipeline-release", release);
        Service.readRecord(db, "change_request", data.get("change_id").toString());
        var row = get(db, "cab-window", release);
        var slot = object(row.get("definition"));
        if (op.equals("cab_history")) {
            var history = new ArrayList<Object>();
            for (int index = 0; index < ((Number) row.get("revision")).intValue(); index++)
                history.add(definition(db, "cab-history", key(release, index)));
            return map(
                    "decisions",
                    history,
                    "clearance",
                    get(db, "cab-clearance", release),
                    "deployment",
                    data);
        }
        if (op.equals("cab_schedule")) {
            require(
                    Set.of("authorized", "awaiting_review").contains(data.get("state")),
                    "Choose a pending deployment");
            long start = ChangeScheduling.instant(b.get("start")),
                    end = ChangeScheduling.instant(b.get("end"));
            require(start < end, "Choose a positive deployment window");
            slot =
                    map(
                            "ci",
                            object(data.get("policy")).get("ci"),
                            "roster",
                            id(b, "roster_id"),
                            "start",
                            start,
                            "end",
                            end,
                            "state",
                            "proposed",
                            "artifact",
                            object(data.get("submitted")).get("artifact"),
                            "reason",
                            text(b, "note", 400));
        } else slot = definition(db, "cab-window", release);
        var review = agenda(db, release, slot);
        if (op.equals("cab_agenda")) return map("window", slot, "review", review);
        if (op.equals("cab_approve")) {
            require(
                    !db.viewerId.equals(object(data.get("policy")).get("pipeline"))
                            && ((List<?>) review.get("conflicts")).isEmpty(),
                    "Independent review and a conflict-free window required");
            slot.putAll(
                    map("state", "approved", "by", db.viewerId, "reason", text(b, "note", 400)));
        }
        slot.putAll(map("review", review, "recorded_by", db.viewerId, "recorded_at", now()));
        save(db, "cab-history", key(release, row.get("revision")), 0, slot);
        return put(db, "cab-window", release, b.get("revision"), slot);
    }

    static Object clear(Store.Db db, String release, Object artifact, long at) throws Exception {
        var slot = get(db, "cab-window", release);
        require(!object(slot.get("definition")).isEmpty(), "Choose a board-reviewed release");
        guard(db, release, artifact, at);
        var row = get(db, "cab-clearance", release);
        var previous = object(row.get("definition"));
        if (previous.containsKey("window") && previous.get("window").equals(slot.get("revision")))
            return row;
        return put(
                db,
                "cab-clearance",
                release,
                row.get("revision"),
                map(
                        "artifact",
                        artifact,
                        "window",
                        slot.get("revision"),
                        "pipeline",
                        db.viewerId,
                        "authorized_at",
                        at));
    }

    static void outcome(Store.Db db, String release, Object artifact, boolean board, long at)
            throws Exception {
        var receipt = object(get(db, "cab-clearance", release).get("definition"));
        var slot = get(db, "cab-window", release);
        if (board)
            require(!object(slot.get("definition")).isEmpty(), "Choose a board-reviewed release");
        if (artifact != null
                && artifact.equals(receipt.get("artifact"))
                && db.viewerId.equals(receipt.get("pipeline"))
                && slot.get("revision").equals(receipt.get("window"))) return;
        guard(db, release, artifact, at);
    }

    static void guard(Store.Db db, String release, Object artifact, long now) throws Exception {
        var slot = object(get(db, "cab-window", release).get("definition"));
        if (slot.isEmpty()) return;
        require(
                "approved".equals(slot.get("state"))
                        && slot.get("artifact").equals(artifact)
                        && ((Number) slot.get("start")).longValue() <= now
                        && now < ((Number) slot.get("end")).longValue(),
                "Approved release artifact and current window required");
        require(
                ((List<?>) agenda(db, release, slot).get("conflicts")).isEmpty(),
                "Deployment window now conflicts");
    }
}
