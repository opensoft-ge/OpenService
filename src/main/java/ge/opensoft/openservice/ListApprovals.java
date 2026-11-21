package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

/** A versioned owner-to-offering mapping can be reused by purchase and HR runs. */
final class ListApprovals {
    private ListApprovals() {}

    static List<Map<String, Object>> pending(Store.Db db) throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        for (var row : list(db, "list-run")) {
            var run = object(row.get("definition"));
            if ("pending".equals(object(run.get("approvals")).get(db.viewerId)))
                rows.add(
                        map(
                                "run",
                                row.get("subject"),
                                "source",
                                run.get("source"),
                                "revision",
                                row.get("revision")));
        }
        return rows;
    }

    static Object command(Store.Db db, String op, Map<String, Object> b) throws Exception {
        if (op.equals("list_policy")) {
            var bindings = new LinkedHashMap<String, Object>();
            for (var item : batch(b.get("entries"), 32)) {
                var entry = new LinkedHashMap<>(object(item));
                var fields = object(entry.getOrDefault("answers_from", map()));
                entry.remove("answers_from");
                Object actionId=entry.remove("action_id");
                keys(entry, "item", "owner_id", "offering_id");
                for (var field : fields.entrySet())
                    require(
                            field.getKey().matches("[a-z][a-z0-9_]{0,63}")
                                    && field.getValue() instanceof String source
                                    && source.matches("[a-z][a-z0-9_]{0,63}"),
                            "Use named fields for catalogue answers");
                String name = text(entry, "item", 120);
                String owner = DutyRosters.responder(db, entry.get("owner_id")),
                        offering = actionId==null ? id(entry,"offering_id") : "";
                if(actionId==null) Catalogue.find(db, offering, true);
                var binding=map("owner",owner,"offering",offering,"fields",fields);
                if(actionId!=null) {
                    definition(db,"purchase-action",EnterpriseData.id(actionId));
                    binding.put("action_id",actionId);
                }
                require(
                        bindings.put(
                                        name,
                                        binding)
                                == null,
                        "Duplicate policy item");
            }
            require(!bindings.isEmpty(), "Add an approval mapping");
            return put(
                    db,
                    "list-policy",
                    id(b, "policy_id"),
                    b.get("revision"),
                    map("title", text(b, "title", 120), "bindings", bindings));
        }
        String run = id(b, "new_id");
        if (op.equals("start_list")) {
            String source = choice(b, "source", "purchase", "hr_case", "requisition"),
                    record = id(b, "record_id"),
                    policy = id(b, "policy_id");
            var rules = object(definition(db, "list-policy", policy).get("bindings"));
            Map<String, Object> inputs;
            if (source.equals("purchase"))
                inputs = ProjectSpend.lines(definition(db, "purchase", record));
            else {
                if(source.equals("hr_case")) EmployeeCases.record(db, record, false);
                else Service.readRecord(db,"sc_request",record);
                inputs = object(b.get("manifest"));
            }
            require(!inputs.isEmpty() && inputs.size() <= 32, "Use 1–32 input records");
            var lines = map();
            var approvals = map();
            for (var item : inputs.entrySet()) {
                require(
                        rules.containsKey(item.getKey()),
                        "Configure each input in the approval policy");
                var rule = object(rules.get(item.getKey()));
                String owner = rule.get("owner").toString();
                require(!db.viewerId.equals(owner), "Choose independent budget owners");
                var offering = rule.containsKey("action_id") ? map("revision",0) : Catalogue.find(db, rule.get("offering").toString(), false);
                approvals.put(owner, "pending");
                lines.put(
                        item.getKey(),
                        map(
                                "owner",
                                owner,
                                "offering",
                                rule.get("offering"),
                                "revision",
                                offering.get("revision"),
                                "fields",
                                rule.getOrDefault("fields", map()),
                                "input",
                                item.getValue()));
                if(rule.containsKey("action_id")) {
                    require(!source.equals("purchase"),"Start a purchase-order action from a requisition or HR case");
                    var action=get(db,"purchase-action",rule.get("action_id").toString());
                    var line=object(lines.get(item.getKey()));
                    line.put("action",object(action.get("definition")));
                    line.put("action_version",action.get("revision"));
                    line.put("action_id",rule.get("action_id"));
                }
            }
            for (String owner : approvals.keySet())
                CustomerImpact.notify(
                        db,
                        owner,
                        "An item approval is waiting in your Decision workflows workspace.");
            return put(
                    db,
                    "list-run",
                    run,
                    0,
                    map(
                            "policy",
                            policy,
                            "version",
                            get(db, "list-policy", policy).get("revision"),
                            "source",
                            source,
                            "record",
                            record,
                            "approvals",
                            approvals,
                            "lines",
                            lines,
                            "state",
                            "review"));
        }
        var row = get(db, "list-run", run);
        var data = definition(db, "list-run", run);
        var approvals = object(data.get("approvals"));
        if (op.equals("review_list")) {
            require(
                    approvals.containsKey(db.viewerId),
                    "This approval belongs to another reviewer");
            var assigned = map();
            object(data.get("lines"))
                    .forEach(
                            (label, value) -> {
                                if (db.viewerId.equals(object(value).get("owner")))
                                    assigned.put(label, object(value).get("input"));
                            });
            return map(
                    "run",
                    run,
                    "items",
                    assigned,
                    "decision",
                    approvals.get(db.viewerId),
                    "revision",
                    row.get("revision"));
        }
        if (op.equals("list_decision")) {
            require(
                    "review".equals(data.get("state"))
                            && "pending".equals(approvals.get(db.viewerId)),
                    "Choose your pending decision");
            approvals.put(db.viewerId, choice(b, "decision", "approved", "rejected"));
            if (!approvals.containsValue("pending"))
                data.put("state", approvals.containsValue("approved") ? "ready" : "rejected");
            save(db, "list-run", run, b.get("revision"), data);
            return map("run", run, "decision", approvals.get(db.viewerId));
        }
        if (op.equals("list_status")) return row;
        require(
                Set.of("ready", "complete").contains(data.get("state")),
                "Wait for each budget owner");
        var lines = object(data.get("lines"));
        String label = text(b, "item", 120);
        var line = object(lines.get(label));
        require("approved".equals(approvals.get(line.get("owner"))), "Item was rejected");
        if (line.containsKey("receipt")) return row;
        var point = db.connection.setSavepoint();
        try {
            if(line.containsKey("action")) {
                require(object(b.get("manifest")).isEmpty(),"Use the approved inputs unchanged");
                line.put("receipt",PurchasingActions.order(db,run,label,line));
            } else {
            var answers = new LinkedHashMap<>(object(b.get("manifest")));
            for (var field : object(line.getOrDefault("fields", map())).entrySet()) {
                var input = object(line.get("input"));
                require(
                        input.containsKey(field.getValue()),
                        "Missing mapped input " + field.getValue());
                answers.put(field.getKey(), input.get(field.getValue()));
            }
            var result =
                    Catalogue.order(
                            db,
                            line.get("offering").toString(),
                            map(
                                    "key",
                                    key(run, label).substring(0, 32),
                                    "revision",
                                    line.get("revision"),
                                    "details",
                                    "Fulfill approved " + label + " for " + data.get("record"),
                                    "answers",
                                    answers));
            line.put("receipt", result.get("sys_id"));
            }
            line.remove("error");
        } catch (Failure invalid) {
            db.connection.rollback(point);
            line.put("error", invalid.getMessage());
        } finally {
            db.connection.releaseSavepoint(point);
        }
        if (lines.values().stream()
                .map(Values::object)
                .allMatch(
                        l ->
                                l.containsKey("receipt")
                                        || "rejected".equals(approvals.get(l.get("owner")))))
            data.put("state", "complete");
        return put(db, "list-run", run, b.get("revision"), data);
    }
}
