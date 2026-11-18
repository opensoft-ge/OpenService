package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;

/** Explicit, bounded record changes with native validation and atomic receipts. */
final class BulkChanges {
 private BulkChanges() {}
 static Map<String,Object> authority(Store.Db db,String table)throws Exception {
  if(!Set.of("admin","agent").contains(db.viewerRole))throw new Failure(403,"Bulk changes require a staff account");
  var schema=Service.schema(db,table);RecordPolicy.read(db,schema);RecordPolicy.require(schema,"update",db.viewerRole);return schema;
 }
 static Map<String,Object> page(Store.Db db,String table)throws Exception {
  authority(db,table);return map("selected",List.of(),"records",Service.page(db,table,Map.of("sysparm_limit","100"),"").rows(),"receipts",db.all("SELECT request_key,created FROM os_bulk_receipt WHERE actor_id=? AND table_name=? ORDER BY created DESC,request_key",db.viewerId,table),"review",null,"receipt",null);
 }
 static Map<String,Object> selection(Store.Db db,String table,Map<String,Object> body)throws Exception {
  var schema=authority(db,table);require(body.get("records") instanceof List<?> rows && !rows.isEmpty() && rows.size()<=20,"Select one to twenty explicit records");var ids=new LinkedHashSet<String>();for(Object value:(List<?>)body.get("records"))require(value instanceof String id && id.matches("[0-9a-f]{32}") && ids.add(id),"Select unique record IDs");
  var patch=object(body.get("patch"));require(!patch.isEmpty() && patch.size()<=10,"Change one to ten fields");require(object(schema.get("fields")).keySet().containsAll(patch.keySet()),"Choose current editable fields");FieldAccess.writing(db,schema,patch);var records=new ArrayList<Map<String,Object>>();for(String id:ids)records.add(Service.record(db,table,id));return map("schema",schema,"records",records,"patch",patch);
 }
 static Map<String,Object> review(Store.Db db,String table,Map<String,Object> selected)throws Exception {
  String proof=DigestUtils.sha256Hex("bulk-record-v1:"+JSON.writeValueAsString(map("actor",db.viewerId,"authority",db.one("SELECT auth_revision FROM os_user WHERE user_id=?",db.viewerId),"table",table,"selection",selected)));
  var changes=new ArrayList<Map<String,Object>>();var checkpoint=db.connection.setSavepoint();try{
   String actor=Service.account(db,db.viewerId).get("name").toString();var patch=object(selected.get("patch"));
   for(var row:objects(selected.get("records"))){var before=FieldAccess.serialize(db,row);var after=FieldAccess.serialize(db,Service.update(db,table,row,patch,actor,db.viewerRole,row.get("revision").toString()));var fields=new ArrayList<Map<String,Object>>();for(String field:patch.keySet())fields.add(map("field",field,"before",before.getOrDefault(field,""),"after",after.getOrDefault(field,"")));changes.add(map("id",row.get("sys_id"),"label",before.getOrDefault("number",row.get("sys_id")),"fields",fields));}
  }finally{db.connection.rollback(checkpoint);db.connection.releaseSavepoint(checkpoint);}
  return map("proof",proof,"changes",changes);
 }
 static Map<String,Object> receipt(Store.Db db,String table,String key)throws Exception {
  authority(db,table);var row=db.one("SELECT * FROM os_bulk_receipt WHERE actor_id=? AND table_name=? AND request_key=?",db.viewerId,table,key);if(row==null)throw new Failure(404,"Bulk receipt not found");selection(db,table,object(row.get("detail")));for(var change:objects(object(object(row.get("detail")).get("review")).get("changes")))change.put("label",FieldAccess.serialize(db,Service.readRecord(db,table,change.get("id").toString())).getOrDefault("number",change.get("id")));return row;
 }
 static Map<String,Object> command(Store.Db db,String table,Object input)throws Exception {
  authority(db,table);var body=object(input);String operation=WorkJobs.text(body.get("operation"),20);require(Set.of("review","apply").contains(operation),"Choose review or apply");boolean applying=operation.equals("apply");require(body.keySet().equals(applying?Set.of("operation","records","patch","proof","key","confirm"):Set.of("operation","records","patch")),"Provide exact bulk review fields");var selected=selection(db,table,body);String key="",fingerprint="";
  if(applying){require(Boolean.TRUE.equals(body.get("confirm")),"Confirm the reviewed bulk change");key=WorkJobs.text(body.get("key"),32);require(key.matches("[0-9a-f]{32}"),"Use a 32-character request key");String proof=WorkJobs.text(body.get("proof"),64);require(proof.matches("[0-9a-f]{64}"),"Use the exact review proof");fingerprint=DigestUtils.sha256Hex(JSON.writeValueAsString(map("table",table,"records",body.get("records"),"patch",body.get("patch"),"proof",proof)));var previous=db.one("SELECT fingerprint FROM os_bulk_receipt WHERE actor_id=? AND request_key=?",db.viewerId,key);if(previous!=null){if(!previous.get("fingerprint").equals(fingerprint))throw new Failure(409,"Request key identifies another bulk change");return map("review",null,"receipt",receipt(db,table,key));}}
  var reviewed=review(db,table,selected);if(!applying)return map("review",reviewed,"receipt",null);if(!reviewed.get("proof").equals(body.get("proof")))throw new Failure(409,"Records, schema or authority changed; review again");require(db.count("SELECT COUNT(*) FROM os_bulk_receipt WHERE actor_id=?",db.viewerId)<100 && db.count("SELECT COUNT(*) FROM os_bulk_receipt")<5000,"Bulk receipt capacity reached");
  String actor=Service.account(db,db.viewerId).get("name").toString();for(Object id:(List<?>)body.get("records")){var row=Service.record(db,table,id.toString());Service.update(db,table,row,body.get("patch"),actor,db.viewerRole,row.get("revision").toString());}
  // Store selected fields only; access is rechecked before returning any retained detail.
  var detail=map("records",body.get("records"),"patch",body.get("patch"),"review",reviewed);db.update("INSERT INTO os_bulk_receipt(actor_id,request_key,table_name,fingerprint,created,detail) VALUES(?,?,?,?,?,?)",db.viewerId,key,table,fingerprint,now(),JSON.writeValueAsString(detail));SecurityEvents.record(db,db.viewerId,db.viewerId,"records.bulk_updated",map("table",table,"count",((List<?>)body.get("records")).size(),"request_key",key));return map("review",null,"receipt",receipt(db,table,key));
 }
}
