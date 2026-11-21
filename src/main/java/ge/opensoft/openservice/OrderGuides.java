package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class OrderGuides {
    private OrderGuides() {}

    static Map<String, Object> selected(
            Store.Db db, Map<String, Object> guide, Map<String, Object> b) throws Exception {
        String role = text(b, "job_role", 100), location = text(b, "location", 100);
        var items = map();
        for (var line : objects(guide.get("entries"))) {
            if (!List.of("*", role).contains(line.get("role"))
                    || !List.of("*", location).contains(line.get("location"))) continue;
            String item = line.get("offering_id").toString();
            try {
                var offering = Catalogue.find(db, item, false);
                if (line.containsKey("reviewer_id"))
                    offering.put("guide_reviewer", line.get("reviewer_id"));
                items.put(item, offering);
            } catch (Failure e) {
                if (e.status != 404) throw e;
            }
        }
        return items;
    }

    static Map<String, Object> selectedFor(
            Store.Db db, Map<String, Object> spec, Map<String, Object> b, String employee)
            throws Exception {
        var items = selected(db, spec, b);
        if (employee != null) {
            var identity = definition(db, "workflow-identity", employee);
            require(db.viewerId.equals(identity.get("manager")), "Order for a managed employee");
            String previous = db.viewerId, role = db.viewerRole;
            try {
                db.viewerRole = AgentPlans.account(db, employee).get("role").toString();
                db.viewerId = employee;
                items.keySet().retainAll(selected(db, spec, b).keySet());
            } finally {
                db.viewerId = previous;
                db.viewerRole = role;
            }
        }
        return items;
    }

    static Object command(Store store, Store.Db db, String op, Map<String, Object> b)
            throws Exception {
        if (op.equals("define")) {
            var entries = new ArrayList<>();
            var seen = new HashSet<String>();
            for (var item : batch(b.get("entries"), 25)) {
                var line = new LinkedHashMap<>(object(item));
                Object reviewer = line.remove("reviewer_id");
                keys(line, "offering_id", "role", "location");
                String offering = id(line, "offering_id");
                Catalogue.find(db, offering, true);
                require(seen.add(offering), "List each offering once");
                var configured =
                        map(
                                "offering_id",
                                offering,
                                "role",
                                text(line, "role", 100),
                                "location",
                                text(line, "location", 100));
                if (reviewer != null)
                    configured.put("reviewer_id", DutyRosters.responder(db, reviewer));
                entries.add(configured);
            }
            require(!entries.isEmpty(), "Select at least one offering");
            return put(
                    db,
                    "order-guide",
                    id(b, "guide_id"),
                    b.get("revision"),
                    map("title", text(b, "title", 160), "entries", entries));
        }
        if (Set.of("preview", "preview_for", "order", "order_for").contains(op)) {
            String guide = id(b, "guide_id");
            var row = get(db, "order-guide", guide);
            var spec = definition(db, "order-guide", guide);
            String beneficiary = op.endsWith("_for") ? id(b, "employee_id") : null;
            if (op.startsWith("preview"))
                return map(
                        "items",
                        selectedFor(db, spec, b, beneficiary),
                        "revision",
                        row.get("revision"));
            String order = id(b, "new_id");
            String fingerprint =
                    org.apache.commons.codec.digest.DigestUtils.sha256Hex(
                            JSON.writer()
                                    .with(
                                            tools.jackson.databind.SerializationFeature
                                                    .ORDER_MAP_ENTRIES_BY_KEYS)
                                    .writeValueAsBytes(List.of(db.viewerId, b)));
            var old = get(db, "guide-order", order);
            if (!old.get("revision").equals(0)) {
                require(
                        List.of(fingerprint, key(db.viewerId, new TreeMap<>(b)))
                                .contains(object(old.get("definition")).get("fingerprint")),
                        "Order identifier already in use");
                return old;
            }
            ProblemInvestigations.revision(b.get("revision"), row.get("revision"));
            String employee = Objects.requireNonNullElse(beneficiary, db.viewerId);
            var items = selectedFor(db, spec, b, beneficiary);
            require(!items.isEmpty(), "No eligible items for this guide");
            var answers = object(b.get("manifest"));
            require(
                    answers.keySet().equals(items.keySet()),
                    "Review each selected item's revision and answers");
            var children = map();
            String details = text(b, "details", 2000);
            for (var entry : items.entrySet()) {
                var values = object(answers.get(entry.getKey()));
                keys(values, "revision", "answers");
                var child =
                        Catalogue.order(
                                db,
                                entry.getKey(),
                                map(
                                        "key",
                                        key(order, entry.getKey()).substring(0, 32),
                                        "revision",
                                        values.get("revision"),
                                        "details",
                                        details,
                                        "answers",
                                        values.get("answers")));
                var offering = object(entry.getValue());
                var line =
                        map(
                                "request_id",
                                child.get("sys_id"),
                                "name",
                                offering.get("name"),
                                "reservation",
                                "reserved",
                                "fulfillment_group",
                                offering.get("fulfilment_group_id"));
                if (offering.containsKey("guide_reviewer")) {
                    String reviewer = DutyRosters.responder(db, offering.get("guide_reviewer"));
                    String request = child.get("sys_id").toString();
                    require(
                            !reviewer.equals(db.viewerId) && !reviewer.equals(employee),
                            "Choose an independent guide reviewer");
                    save(
                            db,
                            "guide-review",
                            request,
                            0,
                            map("reviewer", reviewer, "state", "pending"));
                    line.put("approval", "pending");
                    CustomerImpact.notify(
                            db,
                            reviewer,
                            "Review guide order "
                                    + order
                                    + ", item "
                                    + entry.getKey()
                                    + " ("
                                    + offering.get("name")
                                    + ")");
                }
                children.put(entry.getKey(), line);
            }
            return put(
                    db,
                    "guide-order",
                    order,
                    0,
                    map(
                            "title",
                            spec.get("title"),
                            "owner",
                            db.viewerId,
                            "employee",
                            employee,
                            "fingerprint",
                            fingerprint,
                            "items",
                            children,
                            "state",
                            "open"));
        }
        String order = id(b, "order_id");
        var data = definition(db, "guide-order", order);
        var items = object(data.get("items"));
        if (op.equals("decide_item")) {
            require("open".equals(data.get("state")), "Order is closed");
            var line = object(items.get(id(b, "offering_id")));
            String request = id(line, "request_id");
            Service.readRecord(db, "sc_request", request);
            var row = get(db, "guide-review", request);
            var review = object(row.get("definition"));
            require(
                    db.viewerId.equals(review.get("reviewer"))
                            && "pending".equals(review.get("state")),
                    "Pending approval belongs to the assigned reviewer");
            String decision = choice(b, "decision", "approved", "rejected");
            review.putAll(
                    map("state", decision, "decided_at", now(), "note", text(b, "note", 1000)));
            save(db, "guide-review", request, b.get("revision"), review);
            line.put("approval", decision);
            save(db, "guide-order", order, get(db, "guide-order", order).get("revision"), data);
            return map("request_id", request, "approval", decision);
        }
        if (op.equals("track")
                && db.viewerId.equals(data.get("employee"))
                && !db.viewerId.equals(data.get("owner"))) {
            var lines = new ArrayList<>();
            boolean complete = true;
            for (var raw : items.values()) {
                var line = object(raw);
                String status = line.get("reservation").toString();
                var record =
                        db.one(
                                "SELECT data FROM os_record WHERE sys_id=? AND"
                                        + " \"table\"='sc_request'",
                                line.get("request_id"));
                String state =
                        record == null
                                ? "unavailable"
                                : Objects.toString(object(record.get("data")).get("state"), "");
                lines.add(
                        map(
                                "item",
                                line.get("name"),
                                "approval",
                                line.getOrDefault("approval", "not_required"),
                                "fulfillment_group",
                                line.getOrDefault("fulfillment_group", ""),
                                "state",
                                status.equals("reserved") ? state : status));
                complete &= "complete".equals(state);
            }
            return map(
                    "title",
                    data.get("title"),
                    "state",
                    complete && "open".equals(data.get("state")) ? "complete" : data.get("state"),
                    "items",
                    lines);
        }
        require(
                db.viewerId.equals(data.get("owner"))
                        || "admin".equals(db.viewerRole)
                        || Set.of("ship", "reserve_asset").contains(op),
                "Order unavailable");
        if (op.equals("track")) {
            boolean done = true;
            for (var item : items.values()) {
                var line = object(item);
                var record =
                        Service.readRecord(db, "sc_request", line.get("request_id").toString());
                String state = Objects.toString(object(record.get("data")).get("state"), "");
                line.put(
                        "request_state",
                        "released".equals(line.get("reservation")) ? "cancelled" : state);
                done &= "complete".equals(state);
            }
            if (done && "open".equals(data.get("state"))) data.put("state", "complete");
            return map("order", data, "revision", get(db, "guide-order", order).get("revision"));
        }
        require("open".equals(data.get("state")), "Order already cancelled");
        if (op.equals("reserve_asset")) {
            var line = object(items.get(id(b, "offering_id")));
            String request = id(line, "request_id"), asset = id(b, "asset_id");
            Service.readRecord(db, "sc_request", request);
            AssetCustody.record(db, asset);
            require(AssetCustody.writable(db), "Asset custody permission required");
            require(
                    "in_stock".equals(AssetCustody.latest(db, asset).get("state"))
                            && !line.containsKey("shipment"),
                    "Select available stock for an unshipped item");
            var reservation = get(db, "guide-stock", asset);
            require(
                    !"held".equals(object(reservation.get("definition")).get("state"))
                            && get(db, "guide-stock-item", request).get("revision").equals(0),
                    "Equipment or request already reserved");
            save(
                    db,
                    "guide-stock",
                    asset,
                    reservation.get("revision"),
                    map("request", request, "state", "held"));
            save(db, "guide-stock-item", request, 0, map("asset", asset));
            line.put("reservation", "stock_reserved");
        } else if (op.equals("ship")) {
            var line = object(items.get(id(b, "offering_id")));
            String request = id(line, "request_id");
            var record = Service.readRecord(db, "sc_request", request);
            require(
                    "complete".equals(object(record.get("data")).get("state")),
                    "Finish the approved fulfillment work before recording dispatch");
            require(!line.containsKey("shipment"), "Shipment already recorded");
            var hold = object(get(db, "guide-stock-item", request).get("definition"));
            if (hold.containsKey("asset")) {
                String asset = hold.get("asset").toString();
                var booked = get(db, "guide-stock", asset);
                require(
                        "held".equals(object(booked.get("definition")).get("state"))
                                && request.equals(object(booked.get("definition")).get("request")),
                        "Asset reservation changed");
                save(
                        db,
                        "guide-stock",
                        asset,
                        booked.get("revision"),
                        map("request", request, "state", "issued"));
                AssetCustody.save(
                        db,
                        asset,
                        map(
                                "operation",
                                "issue",
                                "revision",
                                AssetCustody.latest(db, asset).get("revision"),
                                "record_revision",
                                AssetCustody.record(db, asset).get("revision"),
                                "reason",
                                "Guide shipment " + text(b, "reference", 160),
                                "username",
                                Service.account(
                                                db,
                                                data.getOrDefault("employee", data.get("owner"))
                                                        .toString())
                                        .get("name")));
            }
            line.putAll(map("shipment", text(b, "reference", 160), "reservation", "shipped"));
        } else {
            for (var item : items.values()) {
                var line = object(item);
                String request = id(line, "request_id");
                if ("pending".equals(line.get("approval"))) {
                    var current = get(db, "guide-review", request);
                    var review = object(current.get("definition"));
                    review.put("state", "withdrawn");
                    save(db, "guide-review", request, current.get("revision"), review);
                    line.put("approval", "withdrawn");
                }
                if (line.containsKey("shipment")) {
                    line.put("reservation", "return_required");
                    continue;
                }
                var held = object(get(db, "guide-stock-item", request).get("definition"));
                if (held.containsKey("asset")) {
                    String asset = held.get("asset").toString();
                    var booked = get(db, "guide-stock", asset);
                    if (request.equals(object(booked.get("definition")).get("request")))
                        save(
                                db,
                                "guide-stock",
                                asset,
                                booked.get("revision"),
                                map("request", request, "state", "released"));
                }
                if ("complete"
                        .equals(
                                object(Service.readRecord(db, "sc_request", request).get("data"))
                                        .get("state"))) {
                    line.put("reservation", "reversal_review");
                    continue;
                }
                line.put("reservation", "released");
                save(
                        db,
                        "cancelled-guide-item",
                        request,
                        0,
                        map("order", order, "by", db.viewerId));
                int tasks =
                        db.update(
                                "UPDATE os_case_task SET state='cancelled',outcome='Guide order"
                                    + " cancelled',revision=revision+1 WHERE parent_id=? AND state"
                                    + " IN ('queued','working')",
                                request);
                if (tasks > 0)
                    db.update(
                            "UPDATE os_task_plan SET revision=revision+1 WHERE parent_id=?",
                            request);
                db.update(
                        "UPDATE os_approval SET status='withdrawn',comment='Guide order"
                            + " cancelled',decided_at=?,decision_revision=? WHERE record_id=? AND"
                            + " status='requested'",
                        now(),
                        AgentPlans.account(db, db.viewerId).get("auth_revision"),
                        request);
                Service.audit(
                        db,
                        Service.readRecord(db, "sc_request", request),
                        actor(db),
                        "guide.cancelled",
                        map("order_id", order));
                db.update(
                        "UPDATE os_fulfilment SET state='cancelled',outcome='Guide order"
                            + " cancelled',revision=revision+1,updated_at=? WHERE request_id=? AND"
                            + " state IN ('queued','in_progress')",
                        now(),
                        request);
            }
            data.put("state", "cancelled");
        }
        return put(db, "guide-order", order, b.get("revision"), data);
    }
}
