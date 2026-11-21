package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;

final class CloudSpend {
    private CloudSpend() {}

    static String month(Map<String, Object> b) {
        String value = text(b, "month", 7);
        try {
            return YearMonth.parse(value).toString();
        } catch (java.time.DateTimeException e) {
            throw new Failure(400, "Choose a billing month in YYYY-MM format");
        }
    }

    static String account(Map<String, Object> b) {
        String value = text(b, "account", 12);
        require(value.matches("[0-9]{12}"), "Use the AWS usage account ID");
        return value;
    }

    static String currency(Map<String, Object> b) {
        String value = text(b, "currency", 3);
        require(value.matches("[A-Z]{3}"), "Use a three-letter currency");
        return value;
    }

    static BigDecimal amount(Object value) {
        String number = WorkJobs.text(value, 32);
        require(
                number.matches("-?[0-9]{1,15}(\\.[0-9]{1,12})?"),
                "Use a signed decimal billing amount");
        return new BigDecimal(number);
    }

    static Object compare(Store.Db db, Map<String, Object> b) throws Exception {
        var earlier = new LinkedHashMap<>(b);
        earlier.put("month", b.get("previous_month"));
        require(
                java.time.YearMonth.parse(month(earlier))
                        .isBefore(java.time.YearMonth.parse(month(b))),
                "Select an earlier comparison month");
        var before = object(command(db, "cloud_cost", earlier));
        var after = object(command(db, "cloud_cost", b));
        var deltas = new TreeMap<String, BigDecimal>();
        for (var view : List.of(before, after))
            for (var charge : objects(view.get("lines"))) {
                var input = object(charge.get("input"));
                var amount = amount(input.get("cost"));
                deltas.merge(
                        Objects.toString(input.get("resource")),
                        view == before ? amount.negate() : amount,
                        BigDecimal::add);
            }
        return map(
                "before",
                before,
                "after",
                after,
                "resource_changes",
                deltas,
                "change",
                new BigDecimal(after.get("actual").toString())
                        .subtract(new BigDecimal(before.get("actual").toString()))
                        .toPlainString());
    }

    static Object command(Store.Db db, String op, Map<String, Object> b) throws Exception {
        String month = month(b), account = account(b), currency = currency(b);
        if (op.equals("cloud_budget")) {
            String service = text(b, "service", 240);
            require(
                    FederatedMaps.graph(db).roots().containsKey(service),
                    "Select a discovered service");
            return put(
                    db,
                    "cloud-budget",
                    key(account, month, currency, service),
                    b.get("revision"),
                    map(
                            "service",
                            service,
                            "owner",
                            DutyRosters.responder(db, b.get("owner_id")),
                            "amount",
                            money(b.get("amount")).toPlainString()));
        }
        if (op.equals("cloud_import")) {
            String source = text(b, "source", 80);
            var graph = FederatedMaps.graph(db);
            int accepted = 0;
            for (Object raw : batch(b.get("entries"), 250)) {
                var line = object(raw);
                keys(line, "line_id", "resource", "cost");
                String ref = text(line, "line_id", 180);
                Object resource = line.get("resource");
                require(
                        resource instanceof String r && r.length() <= 240,
                        "Use a resource identifier or empty text");
                var input =
                        map(
                                "account",
                                account,
                                "month",
                                month,
                                "currency",
                                currency,
                                "source",
                                source,
                                "resource",
                                resource,
                                "cost",
                                amount(line.get("cost")).toPlainString());
                String id = key(account, month, ref);
                var old = object(get(db, "cloud-charge", id).get("definition"));
                if (!old.isEmpty()) {
                    require(
                            input.equals(old.get("input")),
                            "Billing line changed under the same identifier");
                    continue;
                }
                String identity = ServiceTopology.identity(source, resource.toString());
                String component =
                        object(get(db, "component-alias", identity).get("definition"))
                                .getOrDefault("component", identity)
                                .toString();
                var services = MapHealth.impact(graph, component);
                save(
                        db,
                        "cloud-charge",
                        id,
                        0,
                        map(
                                "input",
                                input,
                                "line_id",
                                ref,
                                "services",
                                services,
                                "component",
                                component));
                accepted++;
            }
            return map("accepted", accepted);
        }
        String service = text(b, "service", 240);
        var budget =
                object(
                        get(db, "cloud-budget", key(account, month, currency, service))
                                .get("definition"));
        require(
                "admin".equals(db.viewerRole)
                        || db.viewerId.equals(budget.get("owner"))
                        || db.viewerId.equals(
                                object(get(db, "service-owner", key(service)).get("definition"))
                                        .get("owner")),
                "Service budget owner required");
        var evidence = new ArrayList<>();
        var total = BigDecimal.ZERO;
        for (var row : list(db, "cloud-charge")) {
            var charge = object(row.get("definition"));
            var i = object(charge.get("input"));
            var services = (List<?>) charge.get("services");
            String bucket =
                    services.isEmpty()
                            ? "unattributed"
                            : services.size() == 1 ? services.getFirst().toString() : "shared";
            if (account.equals(i.get("account"))
                    && month.equals(i.get("month"))
                    && currency.equals(i.get("currency"))
                    && service.equals(bucket)) {
                total = total.add(amount(i.get("cost")));
                evidence.add(charge);
            }
        }
        return map(
                "service",
                service,
                "currency",
                currency,
                "month",
                month,
                "actual",
                total.toPlainString(),
                "budget",
                budget,
                "lines",
                evidence);
    }
}
