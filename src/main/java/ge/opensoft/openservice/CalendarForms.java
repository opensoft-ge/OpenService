package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;
import java.util.*;

/** Browser input maps to the same validated calendar contract as the native API. */
final class CalendarForms {
    private CalendarForms() {}
    static void save(Store.Db db,String actor,Map<String,String> form) throws Exception {
        String days=WorkJobs.text(form.get("days"),32);
        String holidays=form.getOrDefault("holidays","").strip();
        require(holidays.length()<=11000,"Holiday dates exceed the input limit");
        var weekdays=Arrays.stream(days.split("\\s*,\\s*",-1))
                .map(day->(int)Service.number(day,1,7)).toList();
        ServiceClocks.calendar(db,actor,map("name",form.get("name"),"calendar",map(
                "zone",form.get("zone"),"start",form.get("start"),"end",form.get("end"),
                "days",weekdays,"holidays",holidays.isEmpty()?List.of():Arrays.asList(holidays.split("[,\\s]+")))));
    }
}
