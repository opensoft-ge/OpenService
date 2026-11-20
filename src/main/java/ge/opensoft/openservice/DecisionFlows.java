package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class DecisionFlows {
    private DecisionFlows() {}

    static String match(List<Map<String, Object>> rows, Map<String, Object> input) {
        var amount = money(input.get("amount"));
        String risk = choice(input, "risk", "low", "medium", "high");
        String location = text(input, "location", 100);
        String fallback = null;
        for (var row : rows) {
            String operand = row.get("operand").toString();
            boolean matches =
                    switch (row.get("condition").toString()) {
                        case "amount_at_least" -> amount.compareTo(money(operand)) >= 0;
                        case "risk" -> risk.equals(operand);
                        case "location" -> location.equalsIgnoreCase(operand);
                        default -> {
                            fallback = row.get("id").toString();
                            yield false;
                        }
                    };
            if (matches) return row.get("id").toString();
        }
        require(fallback != null, "Define a default row for unmatched inputs");
        return fallback;
    }

    static Object start(
            Store.Db db,
            String design,
            Object version,
            Map<String, Object> release,
            String run,
            String employee,
            Map<String, Object> b)
            throws Exception {
        var paths = objects(release.get("rows"));
        String selected = match(paths, b);
        var path =
                paths.stream().filter(r -> selected.equals(r.get("id"))).findFirst().orElseThrow();
        var spec =
                map(
                        "title",
                        release.get("title"),
                        "days",
                        release.get("days"),
                        "steps",
                        path.get("steps"),
                        "decision",
                        map(
                                "design",
                                design,
                                "row",
                                selected,
                                "amount",
                                money(b.get("amount")).toPlainString(),
                                "risk",
                                b.get("risk"),
                                "location",
                                b.get("location")),
                        "publisher",
                        release.get("publisher"),
                        "publisher_revision",
                        release.get("publisher_revision"));
        return WorkflowStudio.startVersion(db, design, run, employee, version, spec);
    }

    static void expire(Store.Db db, long instant) throws Exception {
        lock(db);
        String expired =
                db.postgres
                        ? "jsonb_exists(definition::jsonb,'decision') AND"
                              + " definition::jsonb->>'state'='running' AND"
                              + " CAST(definition::jsonb->>'deadline' AS BIGINT)<?"
                        : "json_type(definition,'$.decision') IS NOT NULL AND"
                              + " json_extract(definition,'$.state')='running' AND"
                              + " json_extract(definition,'$.deadline')<?";
        for (var row :
                db.all(
                        "SELECT subject,revision,definition FROM os_enterprise_document WHERE"
                            + " area='workflow-run' AND "
                                + expired
                                + " ORDER BY subject LIMIT 50",
                        instant)) {
            var run = object(row.get("definition"));
            run.putAll(
                    map(
                            "state",
                            "timed_out",
                            "expired_at",
                            now(),
                            "expiry_reason",
                            "workflow_deadline"));
            db.update(
                    "UPDATE os_enterprise_document SET revision=revision+1,definition=? WHERE"
                        + " area='workflow-run' AND subject=? AND revision=?",
                    JSON.writeValueAsString(run),
                    row.get("subject"),
                    row.get("revision"));
        }
    }

    static Object command(Store.Db db, String op, Map<String, Object> b) throws Exception {
        String design = id(b, "design_id");
        var draft = get(db, "decision-draft", design);
        var data = object(draft.get("definition"));
        var rows = new ArrayList<>(objects(data.getOrDefault("rows", List.of())));
        var tests = new LinkedHashMap<>(object(data.getOrDefault("tests", map())));
        var published = get(db, "decision-release", design);
        if (op.equals("inspect")) return map("draft", draft, "published", published);
        if (op.equals("start")) {
            ProblemInvestigations.revision(b.get("revision"), published.get("revision"));
            var release = definition(db, "decision-release", design);
            return start(
                    db,
                    design,
                    published.get("revision"),
                    release,
                    id(b, "new_id"),
                    id(b, "user_id"),
                    b);
        }
        ProblemInvestigations.revision(b.get("revision"), draft.get("revision"));
        if (op.equals("publish")) {
            require(
                    !rows.isEmpty() && tests.size() == rows.size(),
                    "Test every row against this draft before publication");
            require(
                    rows.stream().anyMatch(r -> r.get("condition").equals("default")),
                    "Include a tested default");
            var release = new LinkedHashMap<>(data);
            release.putAll(
                    map(
                            "publisher",
                            db.viewerId,
                            "publisher_revision",
                            AgentPlans.account(db, db.viewerId).get("auth_revision"),
                            "draft_revision",
                            draft.get("revision")));
            return put(db, "decision-release", design, published.get("revision"), release);
        }
        String rowId = text(b, "row_id", 80);
        if (op.equals("test")) {
            require(rowId.equals(match(rows, b)), "The inputs routed to a different row");
            tests.put(
                    rowId,
                    map(
                            "amount",
                            b.get("amount"),
                            "risk",
                            b.get("risk"),
                            "location",
                            b.get("location"),
                            "tested_by",
                            db.viewerId));
        } else {
            int position = rows.size();
            for (int i = 0; i < rows.size(); i++)
                if (rowId.equals(rows.get(i).get("id"))) {
                    position = i;
                    break;
                }
            if (op.equals("remove")) {
                require(position < rows.size(), "Choose an existing row");
                rows.remove(position);
            } else {
                String condition =
                        choice(b, "condition", "amount_at_least", "risk", "location", "default");
                String operand = text(b, "operand", 100);
                switch (condition) {
                    case "amount_at_least" -> money(operand);
                    case "risk" -> choice(b, "operand", "low", "medium", "high");
                    case "default" ->
                            require(
                                    rows.stream()
                                            .noneMatch(
                                                    r ->
                                                            "default".equals(r.get("condition"))
                                                                    && !rowId.equals(r.get("id"))),
                                    "Only one default is allowed");
                    default -> {
                        /* Locations use the supplied text. */
                    }
                }
                var steps = new ArrayList<Map<String, Object>>();
                int last = 0;
                for (Object item : batch(b.get("steps"), 24)) {
                    var ref = object(item);
                    var step = map();
                    if (ref.containsKey("response")) {
                        keys(ref, "response", "stage");
                        step.putAll(map("kind", "response", "title", text(ref, "response", 160)));
                    } else {
                        keys(ref, "procedure_id", "stage");
                        String procedure = id(ref, "procedure_id");
                        step.putAll(definition(db, "access-procedure", procedure));
                        Service.readRecord(db, "sys_user_group", step.get("group").toString());
                        step.putAll(
                                map(
                                        "procedure",
                                        procedure,
                                        "procedure_revision",
                                        get(db, "access-procedure", procedure).get("revision")));
                    }
                    int stage = integer(ref.get("stage"), 1, 24);
                    require(
                            stage >= last && stage <= last + 1,
                            "Use consecutive stages starting at one");
                    last = stage;
                    step.putAll(map("stage", stage, "state", "pending"));
                    steps.add(step);
                }
                require(!steps.isEmpty(), "Choose at least one reusable procedure");
                var row =
                        map(
                                "id",
                                rowId,
                                "condition",
                                condition,
                                "operand",
                                operand,
                                "steps",
                                steps);
                if (position == rows.size()) {
                    require(rows.size() < 25, "At most 25 decision rows");
                    rows.add(row);
                } else rows.set(position, row);
                data.putAll(
                        map(
                                "title",
                                text(b, "title", 160),
                                "days",
                                integer(b.get("days"), 1, 365)));
            }
            tests.clear();
        }
        data.putAll(map("rows", rows, "tests", tests));
        return put(db, "decision-draft", design, draft.get("revision"), data);
    }
}
