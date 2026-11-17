package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
final class AttachmentHoldsAcceptance {
 static Map<String,Object> result(WorkflowSupport h,String path) throws Exception {var response=h.get(path,auth("admin"));assertEquals(200,response.statusCode(),html(response));return object(parsed(response).get("result"));}
 static void run(WorkflowSupport h) throws Exception {
  h.account("HoldReader","reader");h.account("HoldAgent","agent");String record=h.add("incident").get("sys_id").toString();byte[] bytes="private-file-content".getBytes();var upload=h.request("POST",h.binaryPath(record,"held.txt"),bytes,"text/plain",auth("admin"));assertEquals(201,upload.statusCode());String id=object(parsed(upload).get("result")).get("sys_id").toString(),api="/api/openservice/attachment-access/"+id,ui="/admin/attachments/"+id;
  var initial=result(h,api);assertEquals(false,initial.get("held"));assertFalse(initial.toString().contains("private-file-content"));var hold=map("held",true,"review",initial.get("review"),"reason","Review required");
  assertEquals(200,h.json("POST",api,hold,auth("admin")).statusCode());assertEquals(409,h.json("POST",api,hold,auth("admin")).statusCode());
  for(String actor:List.of("admin","HoldReader","HoldAgent"))for(String download:List.of("/attachment/"+id,"/api/now/attachment/"+id+"/file")){var response=h.get(download,auth(actor));assertEquals(423,response.statusCode());assertFalse(html(response).contains("private-file-content"));}
  assertEquals(200,h.get("/api/now/attachment/"+id,auth("HoldReader")).statusCode());assertTrue(html(h.get("/record/incident/"+record,auth("HoldReader"))).contains("Held for review"));
  assertEquals(403,h.get(api,auth("HoldReader")).statusCode());assertEquals(403,h.json("POST",api,hold,auth("HoldAgent")).statusCode());assertEquals(401,h.get(api,null).statusCode());assertEquals(404,h.get(api.replace(id,id()),auth("admin")).statusCode());assertEquals(400,h.get(api+"?x=y",auth("admin")).statusCode());assertEquals(405,h.json("PUT",api,hold,auth("admin")).statusCode());
  var current=result(h,api);for(var bad:List.of(map(),map("held","false","review",current.get("review"),"reason","r"),map("held",false,"review",current.get("review"),"reason",""),map("held",true,"review",current.get("review"),"reason","No change")))assertEquals(400,h.json("POST",api,bad,auth("admin")).statusCode());
  assertEquals(403,h.form(ui,map("held","false","review",current.get("review"),"reason","r"),auth("admin")).statusCode());assertEquals(400,h.protectedForm(ui,map("held","maybe","review",current.get("review"),"reason","r"),auth("admin")).statusCode());
  try(var db=h.store.open()){db.update("UPDATE os_attachment SET content=? WHERE sys_id=?","replacement-file".getBytes(),id);db.connection.commit();}
  assertEquals(409,h.json("POST",api,map("held",false,"review",current.get("review"),"reason","Old content reviewed"),auth("admin")).statusCode());new Store(h.environment);current=result(h,api);assertEquals(true,current.get("held"));
  assertEquals(302,h.protectedForm(ui,map("held","false","review",current.get("review"),"reason","Current file reviewed"),auth("admin")).statusCode());
  for(String download:List.of("/attachment/"+id,"/api/now/attachment/"+id+"/file"))assertEquals("replacement-file",html(h.get(download,auth("HoldReader"))));
  try(var db=h.store.open()){assertEquals(2,db.count("SELECT COUNT(*) FROM os_security_event WHERE action='attachment.access_changed'"));db.update("INSERT INTO os_record_access(record_id,visibility,owner_id,group_id) VALUES(?,'private',?,'')",record,RecordAccess.owner(db,"admin"));db.connection.commit();}
  assertEquals(404,h.get("/attachment/"+id,auth("HoldReader")).statusCode());assertEquals(404,h.get("/api/now/attachment/"+id+"/file",auth("HoldReader")).statusCode());
  TransferAcceptance.seed();
  String imported=MigrationSource.key(12),importApi="/api/openservice/attachment-access/"+imported;
  String first=TransferAcceptance.result(h.json("POST",TransferAcceptance.API,TransferAcceptance.plan(),auth("admin")),201).get("job_id").toString();
  TransferAcceptance.fetch(h,first);TransferAcceptance.result(h.json("POST",TransferAcceptance.API+"/"+first+"/apply",map(),auth("admin")),200);
  var beforeHold=result(h,importApi);assertEquals(200,h.json("POST",importApi,map("held",true,"review",beforeHold.get("review"),"reason","Imported content requires review"),auth("admin")).statusCode());
  var heldImport=result(h,importApi);MigrationSource.files.put(imported,"other".getBytes());
  String second=TransferAcceptance.result(h.json("POST",TransferAcceptance.API,TransferAcceptance.plan(),auth("admin")),201).get("job_id").toString();
  TransferAcceptance.fetch(h,second);TransferAcceptance.result(h.json("POST",TransferAcceptance.API+"/"+second+"/apply",map(),auth("admin")),200);
  var replaced=result(h,importApi);assertEquals(true,replaced.get("held"));assertEquals(heldImport.get("revision"),replaced.get("revision"));assertNotEquals(heldImport.get("sha256"),replaced.get("sha256"));
  for(String download:List.of("/attachment/"+imported,"/api/now/attachment/"+imported+"/file"))assertEquals(423,h.get(download,auth("admin")).statusCode());
  assertEquals(409,h.json("POST",importApi,map("held",false,"review",heldImport.get("review"),"reason","Stale import review"),auth("admin")).statusCode());
  assertEquals(200,h.json("POST",importApi,map("held",false,"review",replaced.get("review"),"reason","Updated import reviewed"),auth("admin")).statusCode());
  assertEquals("other",html(h.get("/api/now/attachment/"+imported+"/file",auth("admin"))));

 }
}
