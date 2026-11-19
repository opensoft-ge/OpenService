package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.math.*;
import java.time.*;
import java.util.*;

final class StaffingScenarios {
    private StaffingScenarios() {}
    static Object calendar(Store.Db db,Map<String,Object> body) throws Exception {
        Service.requireAccountAdministrator(db,db.viewerId);
        keys(body,"operation","user_id","revision","currency","hourly_rate","week","exceptions");
        String user=EnterpriseData.id(body.get("user_id"));
        Service.account(db,user);
        String currency=WorkJobs.text(body.get("currency"),3);
        require(currency.matches("[A-Z]{3}"),"Use a three-letter currency");
        var week=batch(body.get("week"),7);
        require(week.size()==7,"Enter seven available-minute totals starting Monday");
        for(var minutes:week) integer(minutes,0,1440);
        var exceptions=object(body.get("exceptions"));
        require(exceptions.size()<=100,"At most 100 dated overrides");
        for(var entry:exceptions.entrySet()) {
            ProjectDelivery.date(entry.getKey());
            integer(entry.getValue(),0,1440);
        }
        var calendar=map("currency",currency,"rate",money(body.get("hourly_rate")).toPlainString(),"week",week,"exceptions",exceptions);
        save(db,"staff-calendar",user,body.get("revision"),calendar);
        return calendar;
    }

    static Object command(Store.Db db,String project,Map<String,Object> body) throws Exception {
        lock(db);
        if("calendar".equals(body.get("operation"))) return calendar(db,body);
        require(ProjectDelivery.writable(db),"Project planner access required");
        String key=EnterpriseData.id(body.get("key"));
        if("open_scenario".equals(body.get("operation"))) {
            keys(body,"operation","key");
            var saved=object(get(db,"staff-scenario",key).get("definition"));
            require(project.equals(saved.get("project")) && db.viewerId.equals(saved.get("owner")),"Select your saved scenario");
            for(String side:List.of("option_a","option_b"))
                for(Object id:(List<?>)object(saved.get("inputs")).get(side)) ProjectDelivery.record(db,id.toString());
            return saved;
        }
        keys(body,"operation","key","title","currency","ceiling","option_a","option_b","start_on","finish_on");
        var inputs=new LinkedHashMap<>(body);
        var financial=InvestmentScenarios.compare(db,map("operation","compare","currency",body.get("currency"),"ceiling",body.get("ceiling"),
                "option_a",body.get("option_a"),"option_b",body.get("option_b")));
        LocalDate start=LocalDate.parse(ProjectDelivery.date(body.get("start_on")));
        LocalDate finish=LocalDate.parse(ProjectDelivery.date(body.get("finish_on")));
        long days=java.time.temporal.ChronoUnit.DAYS.between(start,finish);
        require(days>=0 && days<366,"Choose up to 366 scenario days");
        for(String side:List.of("option_a","option_b")) {
            var option=object(financial.get(side));
            var projects=map();
            var demand=new TreeMap<String,Map<LocalDate,Integer>>();
            for(Object id:(List<?>)body.get(side)) {
                var plan=object(get(db,"portfolio",id.toString()).get("definition"));
                var reservations=objects(plan.getOrDefault("reservations",List.of()));
                projects.put(id.toString(),map("reservations",reservations,"remaining",plan.get("remaining"),"actuals",plan.getOrDefault("actuals",List.of())));
                for(var reservation:reservations) {
                    String user=reservation.get("user_id").toString();

                    int minutes=480*((Number)reservation.get("percent")).intValue();
                    LocalDate from=LocalDate.parse(reservation.get("start_on").toString());
                    LocalDate to=LocalDate.parse(reservation.get("finish_on").toString());
                    for(LocalDate day=start.isAfter(from)?start:from;!day.isAfter(to) && !day.isAfter(finish);day=day.plusDays(1)) {
                        if(day.getDayOfWeek().getValue()<=5) demand.computeIfAbsent(user,ignored->new TreeMap<>()).merge(day,minutes,Integer::sum);
                    }
                }
            }
            var gaps=new ArrayList<Map<String,Object>>();
            var assumptions=map();
            BigDecimal labor=BigDecimal.ZERO;
            for(var person:demand.entrySet()) {
                var row=get(db,"staff-calendar",person.getKey());
                var cal=object(row.get("definition"));
                require(body.get("currency").equals(cal.get("currency")),"Set a matching calendar and rate for every reserved person");
                assumptions.put(person.getKey(),row);
                for(var entry:person.getValue().entrySet()) {
                    var day=entry.getKey();
                    int available=((Number)object(cal.get("exceptions")).getOrDefault(day.toString(),
                            ((List<?>)cal.get("week")).get(day.getDayOfWeek().getValue()-1))).intValue();
                    var hours=BigDecimal.valueOf(entry.getValue()).divide(BigDecimal.valueOf(6000),8,RoundingMode.HALF_UP);
                    labor=labor.add(hours.multiply(money(cal.get("rate"))));
                    if(entry.getValue()>available*100) gaps.add(map("user_id",person.getKey(),"day",day.toString(),
                            "required_minutes",BigDecimal.valueOf(entry.getValue(),2),"available_minutes",available));
                }
            }
            labor=labor.setScale(2,RoundingMode.HALF_UP);
            option.putAll(map("labor",labor.toPlainString(),"project_inputs",projects,"calendars",assumptions,"overbooked",gaps,
                    "funding_after_labor",new BigDecimal(option.get("funding_remaining").toString()).subtract(labor).toPlainString()));
        }
        var saved=map("owner",db.viewerId,"project",project,"title",WorkJobs.text(body.get("title"),160),
                "inputs",inputs,"comparison",financial,"saved_at",now());
        save(db,"staff-scenario",key,0,saved);
        return saved;
    }
}
