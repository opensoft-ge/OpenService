package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

final class Improvements {
    private Improvements() {}

    static Object command(Store store, Store.Db db, String op, Map<String, Object> b)
            throws Exception {
        String id = id(b, "new_id");
        if (op.equals("baseline")) {
            var criteria =
                    map(
                            "table",
                            text(b, "table", 80),
                            "query",
                            b.get("query"),
                            "before_start",
                            ProjectDelivery.date(b.get("before_start")),
                            "completed_state",
                            text(b, "completed_state", 80),
                            "days",
                            b.get("days"),
                            "hours",
                            b.get("hours"));
            require(
                    "admin".equals(db.viewerRole) || db.viewerId.equals(b.get("owner_id")),
                    "Create an initiative for your own work");
            var baseline = object(CaseMining.compare(db, criteria, true));
            return put(
                    db,
                    "improvement",
                    id,
                    0,
                    map(
                            "title",
                            text(b, "title", 160),
                            "owner",
                            DutyRosters.responder(db, b.get("owner_id")),
                            "criteria",
                            criteria,
                            "minimum_completed",
                            integer(b.get("limit"), 2, 500),
                            "baseline",
                            baseline.get("before"),
                            "unknown_creation",
                            baseline.get("unknown_creation"),
                            "state",
                            "baseline_frozen",
                            "frozen_at",
                            now()));
        }
        var row = get(db, "improvement", id);
        var data = definition(db, "improvement", id);
        {
            // Visibility must be rechecked before returning any retained histories.
            var criteria = object(data.get("criteria"));
            String table = criteria.get("table").toString();
            for (String field : List.of("baseline", "outcome"))
                if (data.containsKey(field))
                    for (var item : objects(object(data.get(field)).get("cases")))
                        Service.readRecord(db, table, item.get("record_id").toString());
            require(
                    FieldAccess.allowed(db, Service.schema(db, table), "state"),
                    "Readable state required");
        }
        if (op.equals("review")) return row;
        require(
                db.viewerId.equals(data.get("owner"))
                        || (op.equals("publish_change") && "admin".equals(db.viewerRole)),
                "Assigned improvement owner required");
        if (op.equals("publish_change")) {
            require("baseline_frozen".equals(data.get("state")), "Baseline already used");
            String design = id(b, "design_id");
            var previous = get(db, "decision-release", design);
            var draft = definition(db, "decision-draft", design);
            var previousDefinition = object(previous.get("definition"));
            require(
                    !Objects.equals(draft.get("rows"), previousDefinition.get("rows"))
                            || !Objects.equals(draft.get("days"), previousDefinition.get("days")),
                    "Change the routing or its deadline before measuring an intervention");
            var release =
                    object(
                            DecisionFlows.command(
                                    db,
                                    "publish",
                                    map("design_id", design, "revision", b.get("draft_revision"))));
            data.putAll(
                    map(
                            "state",
                            "published",
                            "rule",
                            design,
                            "previous_version",
                            previous.get("revision"),
                            "new_version",
                            release.get("revision"),
                            "rule_snapshot",
                            release.get("definition"),
                            "published_at",
                            now()));
        } else {
            require(
                    "published".equals(data.get("state")),
                    "Publish a routing change before measuring");
            String after = ProjectDelivery.date(b.get("after_start"));
            require(
                    CaseMining.timestamp(after + " 00:00:00")
                            >= CaseMining.timestamp(data.get("published_at")),
                    "The outcome cohort must begin after publication");
            var criteria = new LinkedHashMap<>(object(data.get("criteria")));
            criteria.put("after_start", after);
            var observed = object(CaseMining.compare(db, criteria));
            var outcome = object(observed.get("after"));
            var base = object(data.get("baseline"));
            int minimum = (int) data.get("minimum_completed");
            boolean enough =
                    (int) base.get("completed") >= minimum
                            && (int) outcome.get("completed") >= minimum;
            data.putAll(
                    map(
                            "state",
                            "measured",
                            "after_start",
                            after,
                            "outcome",
                            outcome,
                            "after_unknown_creation",
                            observed.get("unknown_creation"),
                            "sample_status",
                            enough ? "descriptive_comparison" : "insufficient_completed_cases",
                            "measured_at",
                            now()));
            if (enough)
                data.putAll(
                        map(
                                "mean_fulfillment_change_seconds",
                                ((Number) outcome.get("mean_seconds_to_first_completion"))
                                                .longValue()
                                        - ((Number) base.get("mean_seconds_to_first_completion"))
                                                .longValue(),
                                "reopen_rate_change",
                                ((Number) outcome.get("reopen_rate")).doubleValue()
                                        - ((Number) base.get("reopen_rate")).doubleValue()));
        }
        return put(db, "improvement", id, b.get("revision"), data);
    }
}
