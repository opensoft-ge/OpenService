package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;

/** Native automation portability. Credentials and active execution authority stay local. */
final class AutomationPackages {
 private AutomationPackages() {}
 static final String FORMAT="openservice.automation-package.v1";
 static Map<String,Object> page(Store.Db db)throws Exception {
  Service.requireAccountAdministrator(db,db.viewerId);
  return map("flows",db.all("SELECT flow_id,name FROM os_event_flow ORDER BY name,flow_id"),"actions",ConnectorActions.list(db),"receipts",db.all("SELECT package_id,name,version,created FROM os_automation_install ORDER BY created DESC,package_id LIMIT 100"),"review",null,"receipt",null);
 }
 static Map<String,Object> capture(Store.Db db,Object input)throws Exception {
  Service.requireAccountAdministrator(db,db.viewerId);var body=object(input);
  require(body.keySet().equals(Set.of("operation","name","version","flows","actions")),"Provide name, version and selected flow/action IDs");
  String name=WorkJobs.text(body.get("name"),160),version=WorkJobs.text(body.get("version"),80);var selected=map();int count=0;
  for(String kind:List.of("flows","actions")){
   require(body.get(kind) instanceof List<?> list && list.size()<=20,"Select workflow and action ID lists");var items=map();
   for(Object value:(List<?>)body.get(kind)){
    require(value instanceof String id && id.matches("[0-9a-f]{32}") && !items.containsKey(id),"Select unique current configuration IDs");String id=value.toString();Map<String,Object> definition;
    if(kind.equals("flows")){var row=db.one("SELECT definition FROM os_event_flow WHERE flow_id=?",id);if(row==null)throw new Failure(404,"Event flow not found");definition=EventWorkflows.definition(db,row.get("definition"));}
    else{var row=ConnectorActions.find(db,id);definition=map();for(String field:List.of("name","alias","method","path","inputs"))definition.put(field,row.get(field));}
    items.put(id,definition);count++;
   }
   selected.put(kind,items);
  }
  require(count>0 && count<=20,"Select one to twenty automation definitions");
  return document(map("format",FORMAT,"package_id",id(),"name",name,"version",version,"source_schema",Migrations.VERSION,"flows",selected.get("flows"),"actions",selected.get("actions")));
 }
 static Map<String,Object> document(Object input)throws Exception {
  var doc=object(input);require(JSON.writeValueAsBytes(doc).length<=262144,"Automation package exceeds 256 KiB");
  require(doc.keySet().equals(Set.of("format","package_id","name","version","source_schema","flows","actions")) && FORMAT.equals(doc.get("format")),"Use a native automation package");
  require(WorkJobs.text(doc.get("package_id"),32).matches("[0-9a-f]{32}"),"Use a 32-character package ID");WorkJobs.text(doc.get("name"),160);WorkJobs.text(doc.get("version"),80);
  require(doc.get("source_schema") instanceof Integer version && version>0 && version<=Migrations.VERSION,"Package requires a supported source schema");
  var flows=object(doc.get("flows"));var actions=object(doc.get("actions"));require(flows.size()+actions.size()>0 && flows.size()+actions.size()<=20,"Select one to twenty automation definitions");
  for(var items:List.of(flows,actions))for(var entry:items.entrySet()){require(entry.getKey().matches("[0-9a-f]{32}"),"Use original configuration IDs");object(entry.getValue());}
  return doc;
 }
 static Map<String,Object> bindings(Map<String,Object> doc,Object input)throws Exception {
  var bindings=object(input);require(bindings.keySet().equals(Set.of("groups","staff","aliases")),"Provide group, staff and alias bindings");var required=map("groups",new TreeSet<String>(),"staff",new TreeSet<String>(),"aliases",new TreeSet<String>());
  for(Object value:object(doc.get("flows")).values()){
   var flow=object(value);require(flow.get("actions") instanceof List<?>,"Workflow actions must be a list");
   for(Object action:(List<?>)flow.get("actions")){var step=object(action);String kind=WorkJobs.text(step.get("kind"),20);if(Set.of("assign","task").contains(kind))((Set<String>)required.get("groups")).add(WorkJobs.text(step.get("group_id"),32));if(kind.equals("assign")){require(step.get("assignee") instanceof String,"Use a staff username or empty text");String staff=step.get("assignee").toString();if(!staff.isEmpty())((Set<String>)required.get("staff")).add(staff);}}
  }
  for(Object value:object(doc.get("actions")).values())((Set<String>)required.get("aliases")).add(WorkJobs.text(object(value).get("alias"),80));
  for(String kind:required.keySet()){var values=object(bindings.get(kind));require(values.keySet().equals(required.get(kind)),"Bind every referenced "+kind+" entry exactly once");for(Object value:values.values())WorkJobs.text(value,80);}
  return bindings;
 }
 static Map<String,Object> prepare(Store store,Store.Db db,Map<String,Object> doc,Map<String,Object> bindings)throws Exception {
  var flows=map();var actions=map();var dependencies=map();
  for(var entry:object(bindings.get("groups")).entrySet())dependencies.put("group:"+entry.getKey(),Service.readRecord(db,"sys_user_group",entry.getValue().toString()));
  for(var entry:object(bindings.get("staff")).entrySet()){EventWorkflows.assignee(db,entry.getValue());dependencies.put("staff:"+entry.getKey(),db.one("SELECT user_id,name,role,active,auth_revision,password_reset_required FROM os_user WHERE name=?",entry.getValue()));}
  for(var entry:object(bindings.get("aliases")).entrySet()){var connector=ConnectorConsole.page(db,entry.getValue().toString());require(Arrays.asList(store.environment.getProperty("CONNECTOR_ALLOWED_ORIGINS","").split(",")).contains(connector.get("origin")),"Connector origin is no longer allowed");dependencies.put("alias:"+entry.getKey(),connector);}
  for(var entry:object(doc.get("flows")).entrySet()){
   var definition=new LinkedHashMap<>(object(entry.getValue()));var steps=new ArrayList<Map<String,Object>>();
   for(Object value:(List<?>)definition.get("actions")){var step=new LinkedHashMap<>(object(value));String kind=step.get("kind").toString();if(Set.of("assign","task").contains(kind))step.put("group_id",object(bindings.get("groups")).get(step.get("group_id")));if(kind.equals("assign") && !step.get("assignee").equals(""))step.put("assignee",object(bindings.get("staff")).get(step.get("assignee")));steps.add(step);}
   definition.put("actions",steps);definition= new LinkedHashMap<>(EventWorkflows.definition(db,definition));flows.put(entry.getKey(),definition);dependencies.put("schema:"+definition.get("table"),Service.schema(db,definition.get("table").toString()));
  }
  for(var entry:object(doc.get("actions")).entrySet()){var definition=new LinkedHashMap<>(object(entry.getValue()));require(definition.keySet().equals(Set.of("name","alias","method","path","inputs")),"Provide only portable connector action fields");definition.put("alias",object(bindings.get("aliases")).get(definition.get("alias")));definition.put("active",false);definition.put("revision",0);actions.put(entry.getKey(),definition);}
  return map("flows",flows,"actions",actions,"dependencies",dependencies);
 }
 static Map<String,Object> save(Store.Db db,Map<String,Object> prepared)throws Exception {
  var ids=map("flows",map(),"actions",map());
  for(var entry:object(prepared.get("flows")).entrySet()){var result=EventWorkflows.save(db,"",map("revision",0,"active",false,"definition",entry.getValue()));object(ids.get("flows")).put(entry.getKey(),object(result.get("flow")).get("flow_id"));}
  for(var entry:object(prepared.get("actions")).entrySet())object(ids.get("actions")).put(entry.getKey(),ConnectorActions.save(db,"",entry.getValue()).get("action_id"));
  return ids;
 }
 static Map<String,Object> review(Store store,Store.Db db,Map<String,Object> doc,Map<String,Object> bindings)throws Exception {
  var prepared=prepare(store,db,doc,bindings);var checkpoint=db.connection.setSavepoint();try{save(db,prepared);}finally{db.connection.rollback(checkpoint);db.connection.releaseSavepoint(checkpoint);}
  String proof=DigestUtils.sha256Hex("automation-package-v1:"+JSON.writeValueAsString(map("actor",db.viewerId,"authority",db.one("SELECT auth_revision FROM os_user WHERE user_id=?",db.viewerId),"document",doc,"bindings",bindings,"prepared",prepared,"flows",db.all("SELECT * FROM os_event_flow ORDER BY flow_id"),"actions",db.all("SELECT * FROM os_connector_action ORDER BY action_id"))));
  return map("review",proof,"prepared",prepared,"name",doc.get("name"),"version",doc.get("version"),"package_id",doc.get("package_id"));
 }
 static Map<String,Object> receipt(Store.Db db,String id)throws Exception {
  Service.requireAccountAdministrator(db,db.viewerId);var row=db.one("SELECT * FROM os_automation_install WHERE package_id=?",id);if(row==null)throw new Failure(404,"Automation installation not found");return row;
 }
 static Map<String,Object> command(Store store,Store.Db db,Object input)throws Exception {
  Service.requireAccountAdministrator(db,db.viewerId);var body=object(input);String operation=WorkJobs.text(body.get("operation"),20);require(Set.of("review","install").contains(operation),"Choose review or install");boolean installing=operation.equals("install");require(body.keySet().equals(installing?Set.of("operation","document","bindings","review","confirm"):Set.of("operation","document","bindings")),"Provide only the exact package review fields");
  var doc=document(body.get("document"));var bindings=bindings(doc,body.get("bindings"));String hash=DigestUtils.sha256Hex(JSON.writeValueAsString(doc));
  if(installing){require(Boolean.TRUE.equals(body.get("confirm")),"Confirm installing inactive automation copies");var previous=db.one("SELECT * FROM os_automation_install WHERE package_id=?",doc.get("package_id"));if(previous!=null){var detail=object(previous.get("detail"));if(!previous.get("fingerprint").equals(hash) || !detail.get("bindings").equals(bindings) || !detail.get("review").equals(body.get("review")))throw new Failure(409,"Package ID identifies another reviewed installation");return map("review",null,"receipt",previous);}}
  var reviewed=review(store,db,doc,bindings);if(!installing)return map("review",reviewed,"receipt",null);if(!reviewed.get("review").equals(body.get("review")))throw new Failure(409,"Destination or package changed; review again");require(db.count("SELECT COUNT(*) FROM os_automation_install")<1000,"Automation receipt capacity reached");var ids=save(db,object(reviewed.get("prepared")));var detail=map("document",doc,"bindings",bindings,"review",body.get("review"),"destinations",ids);
  db.update("INSERT INTO os_automation_install(package_id,name,version,fingerprint,actor_id,created,detail) VALUES(?,?,?,?,?,?,?)",doc.get("package_id"),doc.get("name"),doc.get("version"),hash,db.viewerId,now(),JSON.writeValueAsString(detail));SecurityEvents.record(db,db.viewerId,db.viewerId,"automation.installed",map("package_id",doc.get("package_id"),"fingerprint",hash,"destinations",ids));return map("review",null,"receipt",receipt(db,doc.get("package_id").toString()));
 }
}
