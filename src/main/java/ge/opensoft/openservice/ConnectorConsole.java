package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
final class ConnectorConsole {
 private ConnectorConsole() {}
 static Map<String,Object> page(Store.Db db,String alias) throws Exception {
  Service.requireAccountAdministrator(db,db.viewerId);var row=db.one("SELECT * FROM os_connector WHERE alias=?",alias);if(row==null)throw new Failure(404,"Connector alias not found");
  return map("alias",alias,"origin",row.get("origin"),"revision",row.get("revision"),"review",Connectors.review(row));
 }
 static String submit(Store store,Store.Db db,String alias,Map<String,String> input) throws Exception {
  var current=page(db,alias);var form=new LinkedHashMap<>(input);form.remove("csrf");
  if(!current.get("review").equals(form.get("review")))throw new Failure(409,"Connector changed; review it again");
  String operation=form.getOrDefault("operation","");require(Set.of("probe","retire").contains(operation),"Choose probe or retire");
  if(operation.equals("retire")){require(form.keySet().equals(Set.of("operation","review","reason")),"Provide a retirement reason only");Connectors.retire(db,db.viewerId,alias,map("reason",form.get("reason")));return "/admin/operations";}
  require(form.keySet().equals(Set.of("operation","review","path","key")),"Provide path and key only");String path=Connectors.path(form.get("path"));
  var job=WorkJobs.enqueue(store,db,db.viewerId,map("kind","connector.read","key",form.get("key"),"payload",map("alias",alias,"path",path,"review",form.get("review"))));return "/admin/jobs/"+job.get("job_id");
 }
}
