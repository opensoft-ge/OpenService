package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;
/** Current source and target policies are checked independently for every picker request. */
final class ReferencePicker {
 private ReferencePicker() {}
 static Map<String,Object> context(Store.Db db,String table,String id,String field) throws SQLException {
  if(!Set.of("admin","agent").contains(db.viewerRole))throw new Failure(403,"Staff editing permission required");
  var definition=Service.schema(db,table);RecordPolicy.require(definition,"read",db.viewerRole);RecordPolicy.require(definition,"update",db.viewerRole);
  var item=Service.record(db,table,id);FieldAccess.writing(db,definition,map(field,""));
  var rule=object(TypedFields.rules(definition).getOrDefault(field,map()));
  require("reference".equals(rule.get("type")),"Choose a typed reference field");
  if(rule.containsKey("write_roles") && !((List<?>)rule.get("write_roles")).contains(db.viewerRole))throw new Failure(403,"Reference field is not writable");
  String target=rule.get("target").toString();var targetDefinition=Service.schema(db,target);RecordPolicy.require(targetDefinition,"read",db.viewerRole);
  if(!FieldAccess.allowed(db,targetDefinition,"short_description"))throw new Failure(403,"Reference labels are not readable");
  return map("item",item,"target",target,"label",object(definition.get("fields")).get(field));
 }
 static void save(Store.Db db,String table,String id,String field,Map<String,String> form) throws SQLException {
  require(Set.of("csrf","value","revision").containsAll(form.keySet()),"Unsupported reference selection");
  var context=context(db,table,id,field);String value=form.getOrDefault("value","");require(value.isEmpty() || value.matches("[0-9a-f]{32}"),"Choose a reference record");
  int revision=Service.number(form.getOrDefault("revision",""),0,Integer.MAX_VALUE);
  if(!value.isEmpty())Service.record(db,context.get("target").toString(),value);
  Service.update(db,table,object(context.get("item")),map(field,value),Service.account(db,db.viewerId).get("name").toString(),db.viewerRole,Integer.toString(revision));
 }
}
