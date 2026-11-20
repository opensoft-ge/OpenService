package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.math.BigDecimal;
import java.util.*;

final class ProjectSpend {
    private ProjectSpend() {}

    static Map<String, Object> lines(Map<String, Object> purchase) {
        return object(
                purchase.getOrDefault(
                        "lines", map(Objects.toString(purchase.get("model")), purchase)));
    }

    static BigDecimal cost(Object item, Object quantity) {
        return money(object(item).get("unit_cost"))
                .multiply(BigDecimal.valueOf(((Number) quantity).longValue()));
    }

    static Map<String, Object> balance(Store.Db db, String project, Map<String, Object> budget)
            throws Exception {
        BigDecimal committed = BigDecimal.ZERO,
                actual = BigDecimal.ZERO,
                cleared = BigDecimal.ZERO,
                posted = BigDecimal.ZERO;
        var reserved = new HashSet<String>();
        for (var row : list(db, "purchase-reservation")) {
            var r = object(row.get("definition"));
            if (project.equals(r.get("project"))) {
                reserved.add(row.get("subject").toString());
                committed = committed.add(money(r.get("amount")));
            }
        }
        for (var row : list(db, "finance-settlement")) {
            String order =
                    definition(db, "invoice", row.get("subject").toString())
                            .get("order")
                            .toString();
            if (!reserved.contains(order)) continue;
            var purchase = lines(definition(db, "purchase", order));
            for (var batch : objects(object(row.get("definition")).get("exports")))
                for (var entry : object(batch.get("lines")).entrySet()) {
                    var line = object(entry.getValue());
                    actual = actual.add(money(line.get("amount")));
                    cleared = cleared.add(cost(purchase.get(entry.getKey()), line.get("quantity")));
                }
        }
        for (var row : list(db, "posted-expense")) {
            var r = object(row.get("definition"));
            if (!project.equals(r.get("project"))) continue;
            var amount = money(r.get("amount"));
            if ("credit".equals(r.get("kind"))) {
                actual = actual.subtract(amount);
                posted = posted.subtract(amount);
            } else posted = posted.add(amount);
        }
        BigDecimal open = committed.subtract(cleared), forecast = open.add(actual);
        return map(
                "currency",
                budget.get("currency"),
                "budget",
                budget.get("amount"),
                "committed",
                open.toPlainString(),
                "actual",
                actual.toPlainString(),
                "posted",
                posted.toPlainString(),
                "awaiting_posting",
                actual.subtract(posted).toPlainString(),
                "forecast",
                forecast.toPlainString(),
                "available",
                money(budget.get("amount")).subtract(forecast).toPlainString());
    }

    static Object run(Store store, Store.Db db, String op, Map<String, Object> b) throws Exception {
        String project = id(b, "project_id");
        Service.readRecord(db, "project", project);
        if (op.equals("budget")) {
            var previous = object(get(db, "project-funding", project).get("definition"));
            String currency = text(b, "currency", 3);
            require(
                    currency.matches("[A-Z]{3}")
                            && currency.equals(previous.getOrDefault("currency", currency)),
                    "Use the original funding currency");
            var data = map("amount", money(b.get("amount")).toPlainString(), "currency", currency);
            require(
                    new BigDecimal(balance(db, project, data).get("available").toString()).signum()
                            >= 0,
                    "Budget below existing forecast");
            return put(db, "project-funding", project, b.get("revision"), data);
        }
        var budget = definition(db, "project-funding", project);
        if (op.equals("import")) {
            for (Object item : batch(b.get("entries"), 100)) {
                var transaction = new LinkedHashMap<>(object(item));
                keys(
                        transaction,
                        "invoice_id",
                        "batch_id",
                        "reference",
                        "kind",
                        "amount",
                        "original");
                transaction.put("project_id", project);
                run(store, db, "post", transaction);
            }
        }
        if (op.equals("reserve")) {
            String order = id(b, "order_id");
            var purchase = definition(db, "purchase", order);
            require(
                    "approved".equals(purchase.get("state"))
                            && budget.get("currency").equals(purchase.get("currency")),
                    "Reserve an approved order in the budget currency");
            var total =
                    lines(purchase).values().stream()
                            .map(l -> cost(l, object(l).get("quantity")))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            var reservation = map("project", project, "amount", total.toPlainString());
            var old = object(get(db, "purchase-reservation", order).get("definition"));
            if (!old.equals(reservation)) {
                require(old.isEmpty(), "Purchase already reserved");
                ProblemInvestigations.revision(
                        b.get("revision"), get(db, "project-funding", project).get("revision"));
                require(
                        new BigDecimal(balance(db, project, budget).get("available").toString())
                                        .compareTo(total)
                                >= 0,
                        "Insufficient project funding");
                save(db, "purchase-reservation", order, 0, reservation);
            }
        }
        if (op.equals("post")) {
            String invoice = text(b, "invoice_id", 64),
                    batch = id(b, "batch_id"),
                    ref = text(b, "reference", 120),
                    kind = choice(b, "kind", "charge", "credit");
            String order = definition(db, "invoice", invoice).get("order").toString();
            require(
                    project.equals(definition(db, "purchase-reservation", order).get("project")),
                    "Invoice belongs to another project");
            var export =
                    objects(definition(db, "finance-settlement", invoice).get("exports")).stream()
                            .filter(r -> batch.equals(r.get("export_id")))
                            .findFirst()
                            .orElseThrow(
                                    () -> new Failure(400, "Choose an eligible invoice batch"));
            var amount = money(b.get("amount"));
            require(amount.signum() > 0, "A posting must be positive");
            var data =
                    map(
                            "project",
                            project,
                            "invoice",
                            invoice,
                            "batch",
                            batch,
                            "reference",
                            ref,
                            "kind",
                            kind,
                            "amount",
                            amount.toPlainString(),
                            "original",
                            text(b, "original", 120));
            var prices = lines(definition(db, "purchase", order));
            BigDecimal released = BigDecimal.ZERO, total = BigDecimal.ZERO;
            for (var line : object(export.get("lines")).entrySet()) {
                var charge = object(line.getValue());
                total = total.add(money(charge.get("amount")));
                released = released.add(cost(prices.get(line.getKey()), charge.get("quantity")));
            }
            data.put("released", kind.equals("charge") ? released.toPlainString() : "0.00");
            String reference = key(ref);
            var old = object(get(db, "posted-expense", reference).get("definition"));
            if (!data.equals(old)) {
                require(old.isEmpty(), "Ledger reference already represents another transaction");
                if (kind.equals("charge")) {
                    require(total.compareTo(amount) == 0, "Charge must equal the payable export");
                    save(db, "posted-batch", batch, 0, map("reference", ref));
                } else {
                    var original = definition(db, "posted-expense", key(data.get("original")));
                    require(
                            List.of(project, batch, "charge")
                                    .equals(
                                            List.of(
                                                    original.get("project"),
                                                    original.get("batch"),
                                                    original.get("kind"))),
                            "Credit must reference the original charge");
                    var credit = get(db, "batch-credit", batch);
                    var credited =
                            money(object(credit.get("definition")).getOrDefault("amount", "0"))
                                    .add(amount);
                    require(credited.compareTo(total) <= 0, "Credit exceeds posted amount");
                    save(
                            db,
                            "batch-credit",
                            batch,
                            credit.get("revision"),
                            map("amount", credited.toPlainString()));
                }
                save(db, "posted-expense", reference, 0, data);
            }
        }
        return balance(db, project, budget);
    }
}
