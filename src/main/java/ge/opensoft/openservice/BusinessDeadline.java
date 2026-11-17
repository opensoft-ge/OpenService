package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
/** A bounded, read-only explanation using the same local business windows as active SLA clocks. */
final class BusinessDeadline {
 private BusinessDeadline() {}
 static Map<String,Object> preview(Store.Db db,String actor,Map<String,String> args) throws SQLException {
  Service.requireAccountAdministrator(db,actor);require(args.keySet().equals(Set.of("calendar","start","minutes")),"Provide calendar, start and minutes");
  var row=db.one("SELECT definition FROM os_calendar WHERE calendar_id=?",args.get("calendar"));if(row==null)throw new Failure(404,"Business calendar not found");
  int minutes=Service.number(args.get("minutes"),1,525600);
  try {Instant start=Instant.parse(args.get("start"));require(start.getNano()%1000000==0,"Use at most millisecond precision");return calculate(object(row.get("definition")),start.toEpochMilli(),minutes);}
  catch(DateTimeException | ArithmeticException invalid){throw new Failure(400,"Use a supported ISO-8601 start instant");}
 }
 static Map<String,Object> calculate(Map<String,Object> calendar,long start,int minutes) {
  ZoneId zone=ZoneId.of(calendar.get("zone").toString());LocalDate first=Instant.ofEpochMilli(start).atZone(zone).toLocalDate();
  long remaining=(long)minutes*60000;int windows=0;var segments=new ArrayList<Map<String,Object>>();
  for(int day=0;day<3660;day++) {
   LocalDate date=first.plusDays(day);
   if(!((List<?>)calendar.get("days")).contains(date.getDayOfWeek().getValue()) || ((List<?>)calendar.get("holidays")).contains(date.toString()))continue;
   long from=Math.max(start,date.atTime(LocalTime.parse(calendar.get("start").toString())).atZone(zone).toInstant().toEpochMilli());
   long to=date.atTime(LocalTime.parse(calendar.get("end").toString())).atZone(zone).toInstant().toEpochMilli();
   if(to<=from)continue;
   long used=Math.min(remaining,to-from);remaining-=used;windows++;
   if(segments.size()<100)segments.add(map("from",Instant.ofEpochMilli(from).atZone(zone).toString(),"to",Instant.ofEpochMilli(from+used).atZone(zone).toString(),"business_ms",used));
   if(remaining==0)return map("due",Instant.ofEpochMilli(from+used).toString(),"due_local",Instant.ofEpochMilli(from+used).atZone(zone).toString(),"zone",zone.getId(),"business_ms",(long)minutes*60000,"wall_ms",from+used-start,"windows",windows,"segments",segments,"truncated",windows>segments.size());
  }
  throw new Failure(400,"The business budget does not fit within the 3660-day preview horizon");
 }
}
