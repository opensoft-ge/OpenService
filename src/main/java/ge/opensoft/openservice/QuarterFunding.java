package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.math.BigDecimal;
import java.util.*;

final class QuarterFunding {
    private QuarterFunding() {}

    static Map<String, Object> capacity(Store.Db db, String team, String quarter) throws Exception {
        var start =
                java.time.LocalDate.of(
                        Integer.parseInt(quarter.substring(0, 4)),
                        (quarter.charAt(6) - '1') * 3 + 1,
                        1);
        var end = start.plusMonths(3);
        var members = map();
        for (var person :
                db.all(
                        "SELECT m.user_id FROM os_group_member m JOIN os_user u ON"
                                + " u.user_id=m.user_id WHERE m.group_id=? AND u.active=1 ORDER BY"
                                + " m.user_id",
                        team)) {
            String user = person.get("user_id").toString();
            var calendar = get(db, "staff-calendar", user);
            var spec = object(calendar.get("definition"));
            int available = 0;
            if (spec.containsKey("week"))
                for (var day = start; day.isBefore(end); day = day.plusDays(1)) {
                    var minutes =
                            object(spec.get("exceptions"))
                                    .getOrDefault(
                                            day.toString(),
                                            ((List<?>) spec.get("week"))
                                                    .get(day.getDayOfWeek().getValue() - 1));
                    available += ((Number) minutes).intValue();
                }
            members.put(
                    user,
                    map(
                            "calendar_version",
                            calendar.get("revision"),
                            "calendar",
                            spec,
                            "calendar_known",
                            spec.containsKey("week"),
                            "available_minutes",
                            available,
                            "reservations",
                            new ArrayList<>()));
        }
        for (var project : list(db, "portfolio"))
            for (var reservation :
                    objects(
                            object(project.get("definition"))
                                    .getOrDefault("reservations", List.of()))) {
                Object user = reservation.get("user_id");
                if (!members.containsKey(user)
                        || reservation.get("start_on").toString().compareTo(end.toString()) >= 0
                        || reservation.get("finish_on").toString().compareTo(start.toString()) < 0)
                    continue;
                var member = object(members.get(user));
                var reservations = objects(member.get("reservations"));
                reservations.add(map("project", project.get("subject"), "booking", reservation));
                member.put("reservations", reservations);
            }
        return members;
    }

    static Object command(Store store, Store.Db db, String op, Map<String, Object> b)
            throws Exception {
        String goal = id(b, "goal_id");
        if (op.equals("fund_goal")) {
            String quarter = text(b, "quarter", 7);
            require(quarter.matches("[0-9]{4}-Q[1-4]"), "Choose a fiscal quarter such as 2027-Q1");
            return put(
                    db,
                    "funding-goal",
                    goal,
                    0,
                    map(
                            "title",
                            text(b, "title", 160),
                            "quarter",
                            quarter,
                            "currency",
                            CloudSpend.currency(b),
                            "ceiling",
                            money(b.get("amount")).toPlainString(),
                            "allocations",
                            map()));
        }
        var funding = get(db, "funding-goal", goal);
        var base = definition(db, "funding-goal", goal);
        if (op.equals("book_labor")) {
            String project = id(b, "project_id"),
                    worker = id(b, "user_id"),
                    ticket = id(b, "new_id"),
                    day = ProjectDelivery.date(b.get("day"));
            var date = java.time.LocalDate.parse(day);
            require(
                    base.get("quarter")
                            .equals(date.getYear() + "-Q" + ((date.getMonthValue() - 1) / 3 + 1)),
                    "Choose a day in the funded quarter");
            int minutes = integer(b.get("minutes"), 1, 1440);
            var request =
                    map(
                            "goal", goal, "project", project, "worker", worker, "day", day,
                            "minutes", minutes);
            var existing = object(get(db, "funding-labor", ticket).get("definition"));
            if (!existing.isEmpty()) {
                require(
                        request.equals(existing.get("input")),
                        "Labor receipt already identifies different work");
                return existing;
            }
            var approved =
                    definition(
                            db, "funding-option", Objects.toString(base.get("approved_plan"), ""));
            require(
                    object(approved.get("allocations")).containsKey(project)
                            && money(object(approved.get("allocations")).get(project)).signum() > 0,
                    "Choose a funded project");
            var allocation = object(object(approved.get("snapshots")).get(project));
            require(
                    db.count(
                                    "SELECT COUNT(*) FROM os_group_member WHERE user_id=? AND"
                                            + " group_id=?",
                                    worker,
                                    allocation.get("team"))
                            == 1,
                    "Choose a worker from the funded team");
            var calendar = get(db, "staff-calendar", worker);
            var rate = object(calendar.get("definition"));
            require(
                    base.get("currency").equals(rate.get("currency")),
                    "Set the worker rate in the project currency");
            var cost =
                    money(rate.get("rate"))
                            .multiply(BigDecimal.valueOf(minutes))
                            .divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP);
            var resources = get(db, "portfolio", project);
            require(
                    base.get("currency")
                            .equals(object(resources.get("definition")).get("currency")),
                    "Set project resource currency before posting labor");
            PortfolioResources.command(
                    db,
                    project,
                    map(
                            "operation",
                            "actual",
                            "revision",
                            resources.get("revision"),
                            "key",
                            ticket,
                            "amount",
                            cost.toPlainString(),
                            "note",
                            "Delivered labor " + day + " for " + worker));
            var receipt =
                    map(
                            "input",
                            request,
                            "calendar_version",
                            calendar.get("revision"),
                            "rate",
                            rate.get("rate"),
                            "amount",
                            cost.toPlainString(),
                            "by",
                            db.viewerId);
            save(db, "funding-labor", ticket, 0, receipt);
            return receipt;
        }
        String plan = id(b, "plan_id");
        if (op.equals("funding_option")) {
            var allocations = map();
            var snapshots = map();
            BigDecimal total = BigDecimal.ZERO;
            for (Object raw : batch(b.get("entries"), 32)) {
                var line = object(raw);
                keys(line, "project_id", "amount", "labor_remaining", "team_id");
                String project = id(line, "project_id");
                ProjectDelivery.record(db, project);
                Service.readRecord(db, "sys_user_group", id(line, "team_id"));
                var amount = money(line.get("amount"));
                total = total.add(amount);
                require(
                        allocations.put(project, amount.toPlainString()) == null,
                        "Duplicate project");
                var budget = get(db, "project-funding", project);
                var current = object(budget.get("definition"));
                require(
                        base.get("currency").equals(current.get("currency")),
                        "Select a funded project in the goal currency");
                var balance = ProjectSpend.balance(db, project, current);
                var labor = BigDecimal.ZERO;
                for (var laborRow : list(db, "funding-labor")) {
                    var receipt = object(laborRow.get("definition"));
                    var input = object(receipt.get("input"));
                    if (project.equals(input.get("project")) && goal.equals(input.get("goal")))
                        labor = labor.add(money(receipt.get("amount")));
                }
                BigDecimal forecast =
                        new BigDecimal(balance.get("forecast").toString())
                                .add(labor)
                                .add(money(line.get("labor_remaining")));
                snapshots.put(
                        project,
                        map(
                                "forecast",
                                forecast.toPlainString(),
                                "labor_actual",
                                labor.toPlainString(),
                                "budget_version",
                                budget.get("revision"),
                                "balance",
                                balance,
                                "labor_remaining",
                                money(line.get("labor_remaining")).toPlainString(),
                                "team",
                                line.get("team_id"),
                                "team_capacity",
                                capacity(
                                        db,
                                        line.get("team_id").toString(),
                                        base.get("quarter").toString()),
                                "resource_plan",
                                get(db, "portfolio", project)));
            }
            require(
                    !allocations.isEmpty() && total.compareTo(money(base.get("ceiling"))) <= 0,
                    "Option exceeds quarterly funding or has no projects");
            require(
                    allocations.keySet().containsAll(object(base.get("allocations")).keySet()),
                    "Include existing funded projects, using zero to defund");
            return put(
                    db,
                    "funding-option",
                    plan,
                    0,
                    map(
                            "goal",
                            goal,
                            "goal_version",
                            funding.get("revision"),
                            "title",
                            text(b, "title", 160),
                            "creator",
                            db.viewerId,
                            "state",
                            "proposed",
                            "allocations",
                            allocations,
                            "snapshots",
                            snapshots,
                            "quarter",
                            base.get("quarter"),
                            "currency",
                            base.get("currency")));
        }
        var row = get(db, "funding-option", plan);
        var option = definition(db, "funding-option", plan);
        require(goal.equals(option.get("goal")), "Select an option for this goal");
        if (op.equals("view_funding")) return row;
        if (op.equals("compare_funding")) {
            String other = id(b, "other_id");
            var alternative = definition(db, "funding-option", other);
            require(
                    !other.equals(plan) && goal.equals(alternative.get("goal")),
                    "Compare two options for the same goal");
            return map("first", option, "second", alternative);
        }
        require(
                !db.viewerId.equals(option.get("creator"))
                        && "proposed".equals(option.get("state")),
                "Independent funding approval required");
        ProblemInvestigations.revision(option.get("goal_version"), funding.get("revision"));
        var allocations = object(option.get("allocations"));
        for (var entry : allocations.entrySet()) {
            String project = entry.getKey();
            var snapshot = object(object(option.get("snapshots")).get(project));
            var budget = get(db, "project-funding", project);
            var old = object(budget.get("definition"));
            ProblemInvestigations.revision(snapshot.get("budget_version"), budget.get("revision"));
            require(
                    ProjectSpend.balance(db, project, old).equals(snapshot.get("balance"))
                            && get(db, "portfolio", project).equals(snapshot.get("resource_plan"))
                            && capacity(
                                            db,
                                            snapshot.get("team").toString(),
                                            base.get("quarter").toString())
                                    .equals(snapshot.get("team_capacity")),
                    "Spend or capacity changed; prepare another option");
            var delta =
                    money(entry.getValue())
                            .subtract(
                                    money(
                                            object(base.get("allocations"))
                                                    .getOrDefault(project, "0")));
            ProjectSpend.run(
                    store,
                    db,
                    "budget",
                    map(
                            "project_id",
                            project,
                            "amount",
                            money(old.get("amount")).add(delta).toPlainString(),
                            "currency",
                            base.get("currency"),
                            "revision",
                            budget.get("revision")));
        }
        base.put("approved_plan", plan);
        base.put("allocations", allocations);
        save(db, "funding-goal", goal, funding.get("revision"), base);
        option.putAll(map("state", "approved", "by", db.viewerId, "at", now()));
        return put(db, "funding-option", plan, b.get("revision"), option);
    }
}
