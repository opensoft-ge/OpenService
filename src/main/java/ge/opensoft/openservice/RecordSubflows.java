package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

final class RecordSubflows {
    private RecordSubflows() {}

    static Map<String, Object> validate(Store.Db db, String table, Map<String, Object> action)
            throws SQLException {
        keys(action, "kind", "design_id", "inputs", "wait_event", "offering_id");
        String design = id(action, "design_id"), offering = id(action, "offering_id");
        var published = get(db, "decision-release", design);
        require(!published.get("revision").equals(0), "Publish the referenced decision subflow");
        var item = Catalogue.find(db, offering, false);
        var inputs = object(action.get("inputs"));
        keys(inputs, "amount", "risk", "location");
        for (var prop : inputs.entrySet()) {
            var binding = object(prop.getValue());
            if (binding.containsKey("literal")) {
                keys(binding, "literal");
                text(binding, "literal", 100);
                continue;
            }
            String source = table;
            var schema = Service.schema(db, table);
            if (binding.containsKey("via")) {
                keys(binding, "via", "field");
                var rule = object(TypedFields.rules(schema).get(text(binding, "via", 80)));
                require(
                        "reference".equals(rule.get("type")),
                        "Select a dictionary reference for related input");
                source = rule.get("target").toString();
            } else keys(binding, "field");
            require(
                    object(Service.schema(db, source).get("fields"))
                            .containsKey(text(binding, "field", 80)),
                    "Choose a field on the source record");
        }
        return map(
                "kind",
                "subflow",
                "design",
                design,
                "release",
                published,
                "inputs",
                inputs,
                "event",
                text(action, "wait_event", 80),
                "offering",
                offering,
                "offering_revision",
                item.get("revision"));
    }

    static Map<String, Object> start(
            Store.Db db, Map<String, Object> record, Map<String, Object> action, String run)
            throws Exception {
        var inputs = map();
        String table = record.get("table").toString();
        for (var entry : object(action.get("inputs")).entrySet()) {
            var binding = object(entry.getValue());
            Object value = binding.get("literal");
            if (value == null) {
                var source = record;
                String sourceTable = table;
                if (binding.containsKey("via")) {
                    String via = binding.get("via").toString();
                    var schema = Service.schema(db, table);
                    require(
                            FieldAccess.allowed(db, schema, via),
                            "Reference field access required");
                    sourceTable =
                            object(TypedFields.rules(schema).get(via)).get("target").toString();
                    source =
                            Service.readRecord(
                                    db,
                                    sourceTable,
                                    EnterpriseData.id(object(record.get("data")).get(via)));
                }
                String field = binding.get("field").toString();
                require(
                        FieldAccess.allowed(db, Service.schema(db, sourceTable), field),
                        "Related field access required");
                value = object(source.get("data")).get(field);
            }
            inputs.put(entry.getKey(), value);
        }
        var release = object(action.get("release"));
        DecisionFlows.start(
                db,
                action.get("design").toString(),
                release.get("revision"),
                object(release.get("definition")),
                run,
                db.viewerId,
                inputs);
        var sub = definition(db, "workflow-run", run);
        return put(
                db,
                "record-continuation",
                run,
                0,
                map(
                        "record",
                        record.get("sys_id"),
                        "table",
                        table,
                        "event",
                        action.get("event"),
                        "offering",
                        action.get("offering"),
                        "offering_revision",
                        action.get("offering_revision"),
                        "owner",
                        db.viewerId,
                        "owner_revision",
                        AgentPlans.account(db, db.viewerId).get("auth_revision"),
                        "selected_row",
                        object(sub.get("decision")).get("row"),
                        "state",
                        "waiting"));
    }

    static void ready(Store.Db db, String run) throws Exception {
        for (String area : List.of("supplier-flow", "record-continuation")) {
            var row = get(db, area, run);
            var state = object(row.get("definition"));
            if (state.isEmpty()
                    || state.containsKey("request_id")
                    || !state.containsKey(area.equals("supplier-flow") ? "contract" : "reference"))
                continue;
            if (!"complete".equals(definition(db, "workflow-run", run).get("state"))) continue;
            String previous = db.viewerId, role = db.viewerRole;
            var point = db.connection.setSavepoint();
            try {
                String owner = state.get("owner").toString();
                var principal = AgentPlans.account(db, owner);
                require(
                        "admin".equals(principal.get("role"))
                                && principal
                                        .get("auth_revision")
                                        .toString()
                                        .equals(Objects.toString(state.get("owner_revision"))),
                        "Workflow owner authorization changed");
                db.viewerId = owner;
                db.viewerRole = "admin";
                if (area.equals("record-continuation"))
                    command(null, db, "continue_record", map("run_id", run));
                else
                    SupplierFlows.command(
                            null,
                            db,
                            "retry_provision",
                            map(
                                    "design_id",
                                    state.get("design"),
                                    "supplier",
                                    definition(
                                                    db,
                                                    "managed-supplier",
                                                    state.get("supplier").toString())
                                            .get("title")));
            } catch (Failure denied) {
                db.connection.rollback(point);
                db.viewerId = previous;
                db.viewerRole = role;
                state.putAll(
                        map("state", "provisioning_failed", "last_failure", denied.getMessage()));
                save(db, area, run, row.get("revision"), state);
            } finally {
                db.viewerId = previous;
                db.viewerRole = role;
                db.connection.releaseSavepoint(point);
            }
        }
    }

    static Object command(Store store, Store.Db db, String op, Map<String, Object> b)
            throws Exception {
        if (op.equals("on_record")) {
            String table = text(b, "table", 80);
            return EventWorkflows.save(
                    db,
                    "",
                    map(
                            "revision",
                            0,
                            "active",
                            true,
                            "definition",
                            map(
                                    "name",
                                    text(b, "title", 160),
                                    "table",
                                    table,
                                    "event",
                                    b.get("event"),
                                    "state",
                                    b.get("state"),
                                    "actions",
                                    List.of(
                                            map(
                                                    "kind",
                                                    "subflow",
                                                    "design_id",
                                                    id(b, "design_id"),
                                                    "inputs",
                                                    b.get("bindings"),
                                                    "wait_event",
                                                    text(b, "wait_event", 80),
                                                    "offering_id",
                                                    id(b, "offering_id"))))));
        }
        String run = id(b, "run_id");
        var row = get(db, "record-continuation", run);
        var state = definition(db, "record-continuation", run);
        Service.readRecord(db, state.get("table").toString(), state.get("record").toString());
        if (op.equals("flow_event")) {
            require(
                    state.get("event").equals(text(b, "event", 80)),
                    "Callback event does not match");
            String token = text(b, "reference", 160);
            if (state.containsKey("reference")) {
                require(token.equals(state.get("reference")), "A different event was consumed");
                return row;
            }
            state.putAll(map("reference", token, "received_at", now(), "received_by", db.viewerId));
        } else {
            require(
                    db.viewerId.equals(state.get("owner"))
                            && AgentPlans.account(db, db.viewerId)
                                    .get("auth_revision")
                                    .toString()
                                    .equals(Objects.toString(state.get("owner_revision"))),
                    "Configured workflow owner authorization required");
            if (state.containsKey("request_id")) return row;
            require(
                    state.containsKey("reference")
                            && "complete".equals(definition(db, "workflow-run", run).get("state")),
                    "Subflow completion and callback required");
            var req =
                    Catalogue.order(
                            db,
                            state.get("offering").toString(),
                            map(
                                    "revision",
                                    state.get("offering_revision"),
                                    "key",
                                    run,
                                    "details",
                                    "Provision "
                                            + state.get("record")
                                            + " for decision "
                                            + state.get("selected_row")
                                            + " and event "
                                            + state.get("reference")));
            state.putAll(map("state", "provisioned", "request_id", req.get("sys_id")));
        }
        save(db, "record-continuation", run, row.get("revision"), state);
        if (op.equals("flow_event")) ready(db, run);
        return get(db, "record-continuation", run);
    }
}
