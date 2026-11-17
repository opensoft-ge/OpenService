package ge.opensoft.openservice;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
import java.util.*;
/** Browser controls are constrained to the currently visible record. */
final class RecordClocks {
 private RecordClocks() {}
 static Map<String,Object> page(Store.Db db,String table,String id) throws SQLException {
  RecordPolicy.require(Service.schema(db,table),"read",db.viewerRole);
  var result=ServiceClocks.forRecord(db,id,map("table",table));
  result.put("calendars",db.viewerRole.equals("admin")?db.all("SELECT calendar_id FROM os_calendar ORDER BY calendar_id"):List.of());return result;
 }
 static void submit(Store.Db db,String table,String id,Map<String,String> input) throws SQLException {
  Service.requireAccountAdministrator(db,db.viewerId);page(db,table,id);
  var form=new LinkedHashMap<>(input);form.remove("csrf");String operation=form.getOrDefault("operation","");
  if(operation.equals("start")){
   require(form.keySet().equals(Set.of("operation","calendar","minutes")),"Provide calendar and minutes only");
   ServiceClocks.start(db,db.viewerId,map("table",table,"record_id",id,"calendar",form.get("calendar"),"minutes",Service.number(form.get("minutes"),1,525600)),System.currentTimeMillis());
  }else{
   require(form.keySet().equals(Set.of("operation","clock_id")),"Provide operation and clock_id only");
   var clock=db.one("SELECT sla_id FROM os_sla WHERE sla_id=? AND table_name=? AND record_id=?",form.get("clock_id"),table,id);
   if(clock==null)throw new Failure(404,"Service clock not found on this record");
   ServiceClocks.control(db,db.viewerId,form.get("clock_id"),operation,System.currentTimeMillis());
  }
 }
}
