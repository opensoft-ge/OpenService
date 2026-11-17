package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;
/** Ordinary form fields map to the existing validated, owner-bound definition contract. */
final class ReportForms {
 private ReportForms() {}
 static String save(Store.Db db,String actor,String id,Map<String,String> form) throws SQLException {
  require(Set.of("csrf","operation","name","table","field","group_by","q","minimum","maximum","shared").containsAll(form.keySet()),"Unsupported report form field");
  String shared=form.getOrDefault("shared","");require(Set.of("true","false").contains(shared),"Choose private or shared");
  var query=map();for(String key:List.of("field","group_by","q","minimum","maximum"))query.put(key,form.getOrDefault(key,""));
  return SavedReports.save(db,actor,id,map("name",form.get("name"),"table",form.get("table"),"shared",Boolean.parseBoolean(shared),"query",query)).get("report_id").toString();
 }
}
