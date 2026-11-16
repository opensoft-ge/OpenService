package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

final class CalendarFormsAcceptance {
    static Map<String,Object> form() {
        return map("operation","calendar","name","Office hours","zone","Europe/London","start","09:00","end","17:00","days","1,2,3,4,5","holidays","2026-12-25, 2026-12-28");
    }
    static void run(WorkflowSupport h) throws Exception {
        h.account("CalendarReader","reader");
        var input=form();
        assertEquals(302,h.protectedForm("/admin/operations",input,auth("admin")).statusCode());
        var schedule=objects(parsed(h.get(PlatformOperationsAcceptance.ROOT+"calendars",auth("admin"))).get("result")).getFirst();
        assertEquals(List.of("2026-12-25","2026-12-28"),object(schedule.get("definition")).get("holidays"));
        String id=h.add("incident").get("sys_id").toString();
        String clock=PlatformOperationsAcceptance.post(h,"clocks",map("table","incident","record_id",id,"calendar","Office hours","minutes",60)).get("sla_id").toString();
        input.put("holidays","");input.put("start","08:00");
        assertEquals(302,h.protectedForm("/admin/operations",input,auth("admin")).statusCode());
        try(var db=h.store.open()) {
            var saved=object(db.one("SELECT definition FROM os_calendar WHERE calendar_id='Office hours'").get("definition"));
            assertEquals("08:00",saved.get("start"));assertEquals(List.of(),saved.get("holidays"));
            var snapshot=object(db.one("SELECT definition FROM os_sla WHERE sla_id=?",clock).get("definition"));
            assertEquals("09:00",snapshot.get("start"));assertEquals(2,((List<?>)snapshot.get("holidays")).size());
            assertEquals(2,db.count("SELECT COUNT(*) FROM os_security_event WHERE action='sla.calendar_saved'"));
        }
        assertEquals(403,h.protectedForm("/admin/operations",input,auth("CalendarReader")).statusCode());
        assertEquals(403,h.form("/admin/operations",input,auth("admin")).statusCode());
        for(var invalid:List.of(map("days","8"),map("days","1,"),map("zone","Invalid/Zone"),map("holidays","2026-02-30"),map("holidays","x".repeat(11001)),map("end","07:00"))) {
            var rejected=new LinkedHashMap<>(input);rejected.putAll(invalid);
            assertEquals(400,h.protectedForm("/admin/operations",rejected,auth("admin")).statusCode(),invalid.toString());
        }
        assertTrue(html(h.get("/admin/operations",auth("admin"))).contains("Existing clocks keep their original calendar"));
    }
}
