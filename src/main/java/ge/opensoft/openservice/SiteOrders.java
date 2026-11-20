package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.math.*;
import java.util.*;

final class SiteOrders {
    private SiteOrders() {}

    static Map<String, Object> line(Store.Db db, Map<String, Object> b, String currency)
            throws Exception {
        String product = id(b, "product_id"), option = text(b, "option", 80);
        var row = get(db, "sale-product", product);
        var spec = definition(db, "sale-product", product);
        var options = object(spec.get("options"));
        require(
                currency.equals(spec.get("currency")) && options.containsKey(option),
                "Choose an available option in the quote currency");
        int qty = integer(b.get("quantity"), 1, 10000);
        var unit = money(spec.get("price")).add(money(options.get(option)));
        return map(
                "product",
                product,
                "product_version",
                row.get("revision"),
                "title",
                spec.get("title"),
                "option",
                option,
                "unit",
                unit.toPlainString(),
                "quantity",
                qty,
                "frequency",
                choice(b, "frequency", "once", "monthly"),
                "total",
                unit.multiply(BigDecimal.valueOf(qty)).toPlainString());
    }

    static Object command(Store store, Store.Db db, String op, Map<String, Object> b)
            throws Exception {
        if (op.equals("bundle")) {
            String id = id(b, "new_id"), account = null, currency = null;
            var sites = map();
            for (Object item : batch(b.get("entries"), 25)) {
                var entry = object(item);
                keys(entry, "site", "opportunity_id", "revision", "lines");
                String site = text(entry, "site", 120), opportunity = id(entry, "opportunity_id");
                var row = get(db, "opportunity", opportunity);
                var sale = definition(db, "opportunity", opportunity);
                SalesOrders.account(db, sale.get("account").toString());
                ProblemInvestigations.revision(entry.get("revision"), row.get("revision"));
                require(
                        "offered".equals(sale.get("state")),
                        "Each installation quote needs prior discount approval");
                if (account == null) {
                    account = sale.get("account").toString();
                    currency = sale.get("currency").toString();
                }
                require(
                        account.equals(sale.get("account"))
                                && currency.equals(sale.get("currency")),
                        "Use one customer and currency");
                var lines = new ArrayList<>();
                for (Object input : batch(entry.get("lines"), 32)) {
                    var fields = object(input);
                    keys(fields, "product_id", "option", "quantity", "frequency");
                    lines.add(line(db, fields, currency));
                }
                require(
                        sites.put(
                                        site,
                                        map(
                                                "opportunity",
                                                opportunity,
                                                "revision",
                                                row.get("revision"),
                                                "installation_total",
                                                sale.get("total"),
                                                "installation",
                                                sale.get("product_name"),
                                                "lines",
                                                lines))
                                == null,
                        "Use distinct site names");
                save(db, "site-bundle-link", opportunity, 0, map("bundle", id));
            }
            require(!sites.isEmpty(), "Choose at least one site");
            return put(
                    db,
                    "site-bundle",
                    id,
                    0,
                    map(
                            "title",
                            text(b, "title", 160),
                            "state",
                            "offered",
                            "account",
                            account,
                            "currency",
                            currency,
                            "sites",
                            sites));
        }
        String id = id(b, "bundle_id");
        var data = definition(db, "site-bundle", id);
        var sites = object(data.get("sites"));
        SalesOrders.account(db, data.get("account").toString());
        if (op.equals("sites")) {
            var current = map();
            for (var entry : sites.entrySet()) {
                var site = object(entry.getValue());
                var sale = definition(db, "opportunity", site.get("opportunity").toString());
                BigDecimal once = new BigDecimal(site.get("installation_total").toString()),
                        monthly = BigDecimal.ZERO.setScale(2);
                for (var charge : objects(site.get("lines"))) {
                    var amount = new BigDecimal(charge.get("total").toString());
                    if ("monthly".equals(charge.get("frequency"))) monthly = monthly.add(amount);
                    else once = once.add(amount);
                }
                current.put(
                        entry.getKey(),
                        map(
                                "one_time_charges",
                                once.toPlainString(),
                                "monthly_charges",
                                monthly.toPlainString(),
                                "quote",
                                site,
                                "state",
                                sale.get("state"),
                                "case_id",
                                sale.get("case_id"),
                                "order_id",
                                sale.get("order_id"),
                                "task",
                                sale.get("installation_task"),
                                "entitlement",
                                sale.get("entitlement")));
            }
            return map("bundle", get(db, "site-bundle", id), "sites", current);
        }
        if (op.equals("order_sites") && "ordered".equals(data.get("state"))) {
            require(
                    db.viewerId.equals(data.get("customer"))
                            && Objects.equals(b.get("revision"), data.get("accepted_revision")),
                    "Acceptance differs from the placed order");
            return get(db, "site-bundle", id);
        }
        ProblemInvestigations.revision(
                b.get("revision"), get(db, "site-bundle", id).get("revision"));
        if (op.equals("order_sites")) {
            require("requester".equals(db.viewerRole), "Customer acceptance required");
            for (var value : sites.values()) {
                var site = object(value);
                SalesOrders.run(
                        store,
                        db,
                        "accept",
                        map(
                                "opportunity_id",
                                site.get("opportunity"),
                                "revision",
                                site.get("revision"),
                                "bundle",
                                id));
            }
            data.putAll(
                    map(
                            "state",
                            "ordered",
                            "customer",
                            db.viewerId,
                            "accepted_revision",
                            b.get("revision")));
        } else {
            String name = text(b, "site", 120);
            require(
                    "ordered".equals(data.get("state")) && sites.containsKey(name),
                    "Choose an ordered site");
            var site = object(sites.get(name));
            if (op.equals("amend_site")) {
                require(!site.containsKey("pending"), "Resolve the previous amendment first");
                site.put("pending", line(db, b, data.get("currency").toString()));
            } else {
                require(
                        "requester".equals(db.viewerRole) && site.containsKey("pending"),
                        "A customer must accept a pending amendment");
                var line = object(site.remove("pending"));
                line.putAll(
                        map(
                                "accepted_by",
                                db.viewerId,
                                "at",
                                now(),
                                "accepted_revision",
                                b.get("revision")));
                ((List<Object>) site.get("lines")).add(line);
            }
        }
        return put(db, "site-bundle", id, b.get("revision"), data);
    }
}
