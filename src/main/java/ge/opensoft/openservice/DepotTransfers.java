package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class DepotTransfers {
    private DepotTransfers() {}

    static Object source(Store.Db db, Map<String, Object> b) throws Exception {
        String taskId = id(b, "task_id"), model = text(b, "model", 120);
        var task = FieldPlanning.task(db, taskId);
        FieldDispatch.team(db, task);
        require(
                "customer_case".equals(task.get("parent_table")) && AssetCustody.writable(db),
                "Customer visit and inventory access required");
        var choices = new ArrayList<Object>();
        var depots = new HashSet<String>();
        for (Object raw : batch(b.get("entries"), 20)) {
            String depot = EnterpriseData.id(raw);
            Service.readRecord(db, "stockroom", depot);
            require(depots.add(depot), "Choose each stockroom once");
            for (var stock :
                    db.all(
                            "SELECT m.record_id,m.detail FROM os_asset_movement m WHERE"
                                + " m.stockroom_id=? AND m.state='in_stock' AND NOT EXISTS (SELECT"
                                + " 1 FROM os_asset_movement n WHERE n.record_id=m.record_id AND"
                                + " n.revision>m.revision) ORDER BY m.record_id",
                            depot)) {
                if (!model.equalsIgnoreCase(
                        Objects.toString(object(stock.get("detail")).get("model"), ""))) continue;
                String asset = stock.get("record_id").toString();
                if (!"held"
                        .equals(
                                object(get(db, "guide-stock", asset).get("definition"))
                                        .get("state")))
                    choices.add(map("asset_id", asset, "stockroom_id", depot));
            }
        }
        require(!depots.isEmpty(), "Select stockrooms to search");
        var dispatch = FieldDispatch.current(db, taskId);
        var result =
                map(
                        "task_id",
                        taskId,
                        "case_id",
                        task.get("parent_id"),
                        "stock",
                        choices,
                        "shortage",
                        choices.isEmpty(),
                        "scheduled_start",
                        dispatch.get("starts"));
        if (choices.isEmpty())
            result.put(
                    "alternatives",
                    FieldPlanning.command(
                            db,
                            taskId,
                            map(
                                    "operation",
                                    "suggest",
                                    "start",
                                    b.get("start"),
                                    "minutes",
                                    b.get("minutes"),
                                    "days",
                                    b.get("days"))));
        return result;
    }

    static Object command(Store.Db db, String op, Map<String, Object> b) throws Exception {
        String taskId = id(b, "task_id"), asset = id(b, "asset_id");
        var task = FieldPlanning.task(db, taskId);
        FieldDispatch.team(db, task);
        require(
                "customer_case".equals(task.get("parent_table")),
                "Choose an installed customer service task");
        var customer =
                db.one(
                        "SELECT account_id,user_id FROM os_customer_case WHERE record_id=?",
                        task.get("parent_id"));
        require(customer != null, "Choose an entitled customer case");
        AssetCustody.record(db, asset);
        require(AssetCustody.writable(db), "Stock movement access required");
        var movement = AssetCustody.latest(db, asset);
        var hold = get(db, "guide-stock", asset);
        var row = get(db, "depot-transfer", key(taskId, asset));
        var transfer = object(row.get("definition"));
        var dispatch = FieldDispatch.current(db, taskId);
        if (op.equals("return_serial")) {
            var link = definition(db, "installation-account", asset);
            require(
                    customer.get("account_id").equals(link.get("account"))
                            && customer.get("user_id").equals(movement.get("assignee_id")),
                    "Select a serialized asset issued to this customer");
            move(
                    db,
                    asset,
                    "return",
                    "stockroom_id",
                    id(b, "stockroom_id"),
                    "Returned for repair " + taskId);
            save(
                    db,
                    "installation-account",
                    asset,
                    get(db, "installation-account", asset).get("revision"),
                    map(
                            "asset",
                            asset,
                            "account",
                            "",
                            "returned_for",
                            taskId,
                            "received_at",
                            now(),
                            "former_account",
                            customer.get("account_id"),
                            "state",
                            "returned"));
            return map("asset", asset, "state", "returned");
        }
        if (op.equals("reserve_serial")) {
            require(
                    "in_stock".equals(movement.get("state"))
                            && movement.get("stockroom_id").equals(id(b, "stockroom_id"))
                            && !"held".equals(object(hold.get("definition")).get("state")),
                    "Stock unavailable in this depot");
            require(
                    Set.of("assigned", "accepted").contains(dispatch.get("status")),
                    "Assign a technician first");
            save(
                    db,
                    "guide-stock",
                    asset,
                    hold.get("revision"),
                    map("state", "held", "request", taskId));
            transfer =
                    map(
                            "task",
                            taskId,
                            "asset",
                            asset,
                            "depot",
                            movement.get("stockroom_id"),
                            "technician",
                            dispatch.get("technician_id"),
                            "state",
                            "reserved");
        } else {
            require(
                    "reserved".equals(transfer.get("state"))
                            && taskId.equals(object(hold.get("definition")).get("request")),
                    "Choose this task's reserved asset");
            save(
                    db,
                    "guide-stock",
                    asset,
                    hold.get("revision"),
                    map("state", "released", "request", taskId));
            if (op.equals("pickup_serial")) {
                require(
                        transfer.get("technician").equals(dispatch.get("technician_id"))
                                && "accepted".equals(dispatch.get("status")),
                        "Technician must still hold the accepted visit");
                move(
                        db,
                        asset,
                        "issue",
                        "username",
                        Service.account(db, dispatch.get("technician_id").toString()).get("name"),
                        "Pickup for " + taskId);
                transfer.put("state", "picked_up");
            } else transfer.put("state", "released");
        }
        return put(db, "depot-transfer", key(taskId, asset), b.get("revision"), transfer);
    }

    static String replacement(
            Store.Db db, String task, Object account, Map<String, Object> returned)
            throws Exception {
        require(
                task.equals(returned.get("returned_for"))
                        && account.equals(returned.get("former_account")),
                "Customer asset assignment changed");
        for (var row : list(db, "depot-transfer")) {
            var transfer = object(row.get("definition"));
            if (task.equals(transfer.get("task")) && "installed".equals(transfer.get("state"))) {
                String serial = transfer.get("asset").toString();
                if (account.equals(
                        object(get(db, "installation-account", serial).get("definition"))
                                .get("account"))) return serial;
            }
        }
        throw new Failure(400, "Verify the replacement installation before handoff");
    }

    static void move(
            Store.Db db, String asset, String op, String field, Object value, String reason)
            throws Exception {
        AssetCustody.save(
                db,
                asset,
                map(
                        "operation",
                        op,
                        field,
                        value,
                        "reason",
                        reason,
                        "revision",
                        AssetCustody.latest(db, asset).get("revision"),
                        "record_revision",
                        AssetCustody.record(db, asset).get("revision")));
    }

    static void install(Store.Db db, String task, String asset) throws Exception {
        var row = get(db, "depot-transfer", key(task, asset));
        var transfer = object(row.get("definition"));
        require(
                "picked_up".equals(transfer.get("state"))
                        && db.viewerId.equals(transfer.get("technician"))
                        && db.viewerId.equals(AssetCustody.latest(db, asset).get("assignee_id")),
                "Choose stock picked up for this technician and visit");
        var work = FieldPlanning.task(db, task);
        var customer =
                db.one(
                        "SELECT account_id,user_id FROM os_customer_case WHERE record_id=?",
                        work.get("parent_id"));
        move(
                db,
                asset,
                "return",
                "stockroom_id",
                transfer.get("depot"),
                "Transfer from technician " + task);
        move(
                db,
                asset,
                "issue",
                "username",
                Service.account(db, customer.get("user_id").toString()).get("name"),
                "Installed on visit " + task);
        save(
                db,
                "installation-account",
                asset,
                get(db, "installation-account", asset).get("revision"),
                map("asset", asset, "account", customer.get("account_id"), "installed_at", now()));
        transfer.put("state", "installed");
        save(db, "depot-transfer", key(task, asset), row.get("revision"), transfer);
    }
}
