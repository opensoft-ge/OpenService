package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.math.BigDecimal;
import java.util.*;

final class InvestmentScenarios {
    private InvestmentScenarios() {}

    static Map<String,Object> compare(Store.Db db, Map<String,Object> body) throws Exception {
        lock(db);
        keys(body, "operation", "currency", "ceiling", "option_a", "option_b");
        String currency = WorkJobs.text(body.get("currency"), 3);
        require(currency.matches("[A-Z]{3}"), "Choose a three-letter currency");
        var ceiling = money(body.get("ceiling"));
        var first = estimate(db, body.get("option_a"), currency, ceiling);
        var second = estimate(db, body.get("option_b"), currency, ceiling);
        var difference = new BigDecimal(second.get("forecast").toString())
                .subtract(new BigDecimal(first.get("forecast").toString()));
        return map("currency", currency, "option_a", first, "option_b", second,
                "additional_forecast_for_b", difference.toPlainString());
    }

    private static Map<String,Object> estimate(Store.Db db, Object projects, String currency,
                                               BigDecimal ceiling) throws Exception {
        var revisions = new LinkedHashMap<String,Object>();
        var forecast = new BigDecimal("0.00");
        for (Object entry : EnterpriseData.batch(projects, 25)) {
            String id = EnterpriseData.id(entry);
            ProjectDelivery.record(db, id);
            var row = get(db, "portfolio", id);
            var plan = object(row.get("definition"));
            require(currency.equals(plan.get("currency")), "Set a matching budget currency on each project");
            require(revisions.put(id, row.get("revision")) == null, "Select each project once in an option");
            forecast = forecast.add(money(plan.get("remaining")));
            for (var receipt : objects(plan.getOrDefault("actuals", List.of()))) {
                forecast = forecast.add(money(receipt.get("amount")));
            }
        }
        return map("project_revisions", revisions, "forecast", forecast.toPlainString(),
                "funding_remaining", ceiling.subtract(forecast).toPlainString());
    }
}
