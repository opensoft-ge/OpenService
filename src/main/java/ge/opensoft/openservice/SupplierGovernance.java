package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

final class SupplierGovernance {
    private SupplierGovernance() {}

    static String identity(Object name) {
        return key(name.toString().strip().toLowerCase(Locale.ROOT));
    }

    static boolean valid(Object expiry) {
        return !LocalDate.parse(expiry.toString()).isBefore(LocalDate.now(ZoneOffset.UTC));
    }

    static Object command(Store.Db db, String op, Map<String, Object> b) throws Exception {
        String name = text(b, "supplier", 160), id = identity(name);
        if (op.equals("request")) {
            String security = DutyRosters.responder(db, b.get("security_id")),
                    legal = DutyRosters.responder(db, b.get("legal_id")),
                    finance = id(b, "finance_id");
            require(
                    "admin".equals(AgentPlans.account(db, finance).get("role")),
                    "Finance must have purchase approval authority");
            require(
                    new HashSet<>(List.of(security, legal, finance)).size() == 3,
                    "Assign three distinct reviewers");
            return put(
                    db,
                    "managed-supplier",
                    id,
                    0,
                    map(
                            "title",
                            name,
                            "security",
                            security,
                            "legal",
                            legal,
                            "finance",
                            finance,
                            "requester",
                            db.viewerId));
        }
        var row = get(db, "managed-supplier", id);
        var data = definition(db, "managed-supplier", id);
        if (op.equals("trace")) {
            var orders = map();
            for (var order : list(db, "supplier-purchase")) {
                var receipt = object(order.get("definition"));
                if (id.equals(receipt.get("supplier")))
                    orders.put(
                            order.get("subject").toString(),
                            map("release", receipt, "invoices", map()));
            }
            var included = new HashMap<String, Map<String, Object>>();
            for (var invoice : list(db, "invoice")) {
                var detail = object(invoice.get("definition"));
                var order = orders.get(detail.get("order"));
                if (order == null) continue;
                String ref = invoice.get("subject").toString();
                var bill =
                        map(
                                "invoice",
                                detail,
                                "settlement",
                                get(db, "finance-settlement", ref),
                                "postings",
                                new ArrayList<>());
                object(object(order).get("invoices")).put(ref, bill);
                included.put(ref, bill);
            }
            for (var rowPosting : list(db, "posted-expense")) {
                var posting = object(rowPosting.get("definition"));
                var invoice = included.get(posting.get("invoice").toString());
                if (invoice != null) objects(invoice.get("postings")).add(posting);
            }
            return map("supplier", row, "orders", orders);
        }
        ProblemInvestigations.revision(b.get("revision"), row.get("revision"));
        String role =
                switch (op) {
                    case "assess" -> "security";
                    case "contract" -> "legal";
                    default -> "finance";
                };
        require(db.viewerId.equals(data.get(role)), "Assigned supplier owner required");
        if (op.equals("release")) {
            String order = id(b, "order_id"), project = id(b, "project_id");
            var purchase = get(db, "purchase", order);
            var terms = object(purchase.get("definition"));
            require(
                    id.equals(identity(terms.getOrDefault("supplier", "")))
                            && "draft".equals(terms.get("state")),
                    "Choose a draft order for this supplier");
            require(
                    data.containsKey("contract") && data.containsKey("assessment"),
                    "Record the contract and assessment first");
            var assessment = object(data.get("assessment"));
            require(
                    "pass".equals(assessment.get("decision")),
                    "A rejected assessment blocks purchasing");
            boolean excepted = !valid(assessment.get("expiry"));
            if (excepted)
                require(
                        data.containsKey("exception")
                                && valid(object(data.get("exception")).get("expiry")),
                        "Assessment expired; a current dated exception is required");
            AssetPlanning.approveDraft(db, terms);
            save(db, "purchase", order, purchase.get("revision"), terms);
            ProjectSpend.run(
                    null,
                    db,
                    "reserve",
                    map(
                            "project_id",
                            project,
                            "order_id",
                            order,
                            "revision",
                            get(db, "project-funding", project).get("revision")));
            return put(
                    db,
                    "supplier-purchase",
                    order,
                    0,
                    map(
                            "supplier",
                            id,
                            "project",
                            project,
                            "assessment",
                            assessment,
                            "contract",
                            data.get("contract"),
                            "exception",
                            excepted ? data.get("exception") : map(),
                            "finance",
                            db.viewerId,
                            "at",
                            now(),
                            "commitment",
                            definition(db, "purchase-reservation", order)));
        }
        var evidence = map("note", text(b, "note", 2000), "by", db.viewerId, "at", now());
        if (op.equals("contract")) {
            evidence.put("reference", text(b, "reference", 160));
            data.put("contract", evidence);
        } else {
            String expiry = ProjectDelivery.date(b.get("expires_on"));
            evidence.put("expiry", expiry);
            if (op.equals("assess")) {
                evidence.put("decision", choice(b, "decision", "pass", "fail"));
                data.remove("exception");
                data.put("assessment", evidence);
            } else {
                require(
                        data.containsKey("assessment")
                                && !valid(object(data.get("assessment")).get("expiry"))
                                && valid(expiry),
                        "Grant a current exception to an expired assessment");
                data.put("exception", evidence);
            }
        }
        save(db, "managed-supplier", id, row.get("revision"), data);
        return map("revision", get(db, "managed-supplier", id).get("revision"), "saved", op);
    }
}
