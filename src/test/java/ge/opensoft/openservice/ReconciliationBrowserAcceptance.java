package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
final class ReconciliationBrowserAcceptance {
 static void run(WorkflowSupport h) throws Exception {
  TransferAcceptance.seed();h.account("ReconciliationReader","reader");
  String id=TransferAcceptance.result(h.json("POST",TransferAcceptance.API,TransferAcceptance.plan(),auth("admin")),201).get("job_id").toString();
  String page="/admin/migrations/"+id+"/reconciliation";
  var initial=h.get(page,auth("admin"));assertEquals(200,initial.statusCode(),html(initial));assertTrue(html(initial).contains("Local checks need review"));
  assertEquals(302,h.get(page,null).statusCode());assertEquals(403,h.get(page,auth("ReconciliationReader")).statusCode());
  for(String query:new String[]{"extra=x","download=yes","download=true&download=false"})assertEquals(400,h.get(page+"?"+query,auth("admin")).statusCode());
  assertEquals(405,h.protectedForm(page,map(),auth("admin")).statusCode());assertEquals(404,h.get("/admin/migrations/"+id()+"/reconciliation",auth("admin")).statusCode());
  TransferAcceptance.fetch(h,id);TransferAcceptance.result(h.json("POST",TransferAcceptance.API+"/"+id+"/apply",map(),auth("admin")),200);
  assertTrue(html(h.get(page+"?download=false",auth("admin"))).contains("Local checks passed"));
  var download=h.get(page+"?download=true",auth("admin"));assertEquals(200,download.statusCode());assertTrue(download.headers().firstValue("Content-Disposition").orElseThrow().contains("migration-reconciliation-"+id+".json"));
  var diagnostic=object(parsed(download).get("result"));assertEquals(false,diagnostic.get("source_completeness_verified"));assertEquals(true,diagnostic.get("local_reconciliation_passed"));assertFalse(html(download).contains(MigrationSource.TOKEN));
  assertEquals(TransferAcceptance.result(h.get(TransferAcceptance.API+"/"+id+"/reconciliation",auth("admin")),200),diagnostic);
  try(var db=h.store.open()){
   var link=db.all("SELECT * FROM os_transfer_link").stream().filter(row->object(row.get("detail")).get("kind").equals("record")).findFirst().orElseThrow();
   db.update("UPDATE os_record SET revision=revision+1 WHERE sys_id=?",object(link.get("detail")).get("target"));
   db.update("DELETE FROM os_transfer_item WHERE job_id=? AND source_table=? AND source_id=?",id,link.get("source_table"),link.get("source_id"));db.connection.commit();
  }
  var changed=html(h.get(page,auth("admin")));assertTrue(changed.contains("Local checks need review"));assertTrue(changed.contains("Source completeness is not verified"));
  var json=object(parsed(h.get(page+"?download=true",auth("admin"))).get("result"));assertEquals(1,json.get("missing_source_items"));assertEquals(1,json.get("changed_destinations"));
  try(var db=h.store.open()){
   String origin=TransferJobs.job(db,id).get("source_url").toString();
   for(int i=0;i<101;i++){String target=id();db.update("INSERT INTO os_transfer_link(source_url,source_table,source_id,target_id,detail) VALUES(?,?,?,?,?)",origin,"incident",id(),target,JSON.writeValueAsString(map("kind","record","target",target,"destination",map("revision",0))));}
   db.connection.commit();
  }
  var bounded=html(h.get(page,auth("admin")));assertTrue(bounded.contains("Only the first 100 entries"));
  assertEquals(100,objects(object(parsed(h.get(page+"?download=true",auth("admin"))).get("result")).get("missing")).size());

 }
}
