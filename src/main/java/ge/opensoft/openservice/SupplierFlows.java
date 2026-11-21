package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

/** An approved assessment starts a version-pinned subflow. Contract delivery may arrive first. */
final class SupplierFlows {
    private SupplierFlows() {}

    static String instance(String design, String supplier) {
        return key(design, supplier).substring(0, 32);
    }

    static void changed(Store.Db db, String supplier, Map<String, Object> data) throws Exception {
        if (!"pass".equals(object(data.get("assessment")).get("decision"))) return;
        for (var row : list(db, "supplier-trigger")) {
            String design = row.get("subject").toString(), run = instance(design, supplier);
            if (!get(db, "supplier-flow", run).get("revision").equals(0)) continue;
            var config = object(row.get("definition"));
            var release = get(db, "decision-release", design);
            var inputs = object(config.get("inputs"));
            DecisionFlows.start(
                    db,
                    design,
                    release.get("revision"),
                    definition(db, "decision-release", design),
                    run,
                    data.get("requester").toString(),
                    inputs);
            var state =
                    map(
                            "supplier",
                            supplier,
                            "design",
                            design,
                            "subflow",
                            run,
                            "inputs",
                            inputs,
                            "offering",
                            config.get("offering"),
                            "offering_revision",
                            config.get("offering_revision"),
                            "state",
                            "awaiting_approval_and_contract",
                            "owner",
                            config.get("owner"),
                            "owner_revision",
                            config.get("owner_revision"));
            if (data.containsKey("contract"))
                state.put("contract", object(data.get("contract")).get("reference"));
            save(db, "supplier-flow", run, 0, state);
        }
    }

    static void signed(Store.Db db, String supplier, Map<String, Object> record) throws Exception {
        for (var cfg : list(db, "supplier-trigger")) {
            String run = instance(cfg.get("subject").toString(), supplier);
            var row = get(db, "supplier-flow", run);
            var state = object(row.get("definition"));
            if (state.isEmpty() || state.containsKey("contract")) continue;
            state.put("contract", object(record.get("contract")).get("reference"));
            save(db, "supplier-flow", run, row.get("revision"), state);
            RecordSubflows.ready(db, run);
        }
    }

    static Object command(Store store, Store.Db db, String op, Map<String, Object> b)
            throws Exception {
        String design = id(b, "design_id");
        if (op.equals("trigger")) {
            var release = definition(db, "decision-release", design);
            var inputs =
                    map(
                            "amount",
                            money(b.get("amount")).toPlainString(),
                            "risk",
                            choice(b, "risk", "low", "medium", "high"),
                            "location",
                            text(b, "location", 100));
            DecisionFlows.match(objects(release.get("rows")), inputs);
            var offering = Catalogue.find(db, id(b, "offering_id"), false);
            return put(
                    db,
                    "supplier-trigger",
                    design,
                    b.get("revision"),
                    map(
                            "inputs",
                            inputs,
                            "owner",
                            db.viewerId,
                            "owner_revision",
                            AgentPlans.account(db, db.viewerId).get("auth_revision"),
                            "offering",
                            offering.get("offering_id"),
                            "offering_revision",
                            offering.get("revision")));
        }
        String supplier = SupplierGovernance.identity(text(b, "supplier", 160)),
                run = instance(design, supplier);
        var record = definition(db, "managed-supplier", supplier);
        var row = get(db, "supplier-flow", run);
        var flow = definition(db, "supplier-flow", run);
        var subflow = definition(db, "workflow-run", run);
        if (op.equals("flow_status"))
            return map(
                    "flow",
                    row,
                    "approval_state",
                    subflow.get("state"),
                    "decision_output",
                    subflow.get("decision"));
        if (op.equals("contract_event")) {
            require(db.viewerId.equals(record.get("legal")), "Assigned legal reviewer required");
            String reference = text(b, "reference", 160);
            require(
                    reference.equals(object(record.get("contract")).get("reference")),
                    "Use the recorded signed contract");
            if (flow.containsKey("contract")) {
                require(
                        reference.equals(flow.get("contract")),
                        "A different contract was acknowledged");
                return row;
            }
            flow.put("contract", reference);
            save(db, "supplier-flow", run, row.get("revision"), flow);
            RecordSubflows.ready(db, run);
            return get(db, "supplier-flow", run);
        } else {
            if (flow.containsKey("request_id")) return row;
            require(
                    db.viewerId.equals(flow.get("owner"))
                            && AgentPlans.account(db, db.viewerId)
                                    .get("auth_revision")
                                    .toString()
                                    .equals(Objects.toString(flow.get("owner_revision"))),
                    "Current workflow owner authorization required");
            require(
                    flow.containsKey("contract") && "complete".equals(subflow.get("state")),
                    "Approval and signed contract required");
            var request =
                    Catalogue.order(
                            db,
                            flow.get("offering").toString(),
                            map(
                                    "revision",
                                    flow.get("offering_revision"),
                                    "key",
                                    run,
                                    "details",
                                    "Provision "
                                            + record.get("title")
                                            + " under contract "
                                            + flow.get("contract")
                                            + " (decision "
                                            + object(subflow.get("decision")).get("row")
                                            + ")"));
            flow.putAll(
                    map(
                            "state",
                            "provisioned",
                            "request_id",
                            request.get("sys_id"),
                            "decision_output",
                            subflow.get("decision")));
        }
        return put(db, "supplier-flow", run, row.get("revision"), flow);
    }
}
