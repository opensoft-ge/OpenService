package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
import java.sql.SQLException;
import org.apache.commons.codec.digest.DigestUtils;
/** Read-only inspection of retained staging; neither source completeness nor applied outcomes. */
final class MigrationRows {
 private MigrationRows(){}
 static Map<String,Object> context(Store.Db db,String id,String table)throws SQLException{
  Service.requireAccountAdministrator(db,db.viewerId);var job=TransferJobs.job(db,id);var plan=object(job.get("definition"));require(TransferJobs.phases(plan).contains(table),"Choose a source phase in this migration");var mapping=object(plan.get("tables")).get(table);if(mapping!=null)RecordPolicy.read(db,Service.schema(db,object(mapping).get("target").toString()));return job;
 }
 static Map<String,Object> page(Store.Db db,String id,Map<String,String> args)throws SQLException{
  Service.requireAccountAdministrator(db,db.viewerId);require(Set.of("source_table","limit","offset").containsAll(args.keySet()),"Use source_table, limit and offset");var job=TransferJobs.job(db,id);var phases=TransferJobs.phases(object(job.get("definition")));String table=args.getOrDefault("source_table",phases.getFirst());context(db,id,table);int limit=Service.number(args.getOrDefault("limit","50"),1,100),offset=Service.number(args.getOrDefault("offset","0"),0,10000);long total=db.count("SELECT COUNT(*) FROM os_transfer_item WHERE job_id=? AND source_table=?",id,table);var rows=db.all("SELECT source_id,COALESCE(LENGTH(content),0) AS attachment_bytes FROM os_transfer_item WHERE job_id=? AND source_table=? ORDER BY source_id LIMIT ? OFFSET ?",id,table,limit,offset);return map("job_id",id,"state",object(job.get("detail")).get("status"),"source_table",table,"phases",phases,"rows",rows,"total",total,"limit",limit,"offset",offset,"previous_offset",Math.max(0,offset-limit),"next_offset",offset+rows.size()<total?offset+rows.size():-1);
 }
 static Map<String,Object> inspect(Store.Db db,String id,String table,String sourceId)throws SQLException{
  var job=context(db,id,table);var row=db.one("SELECT data,content FROM os_transfer_item WHERE job_id=? AND source_table=? AND source_id=?",id,table,sourceId);if(row==null)throw new Failure(404,"Staged row not found");var mapping=object(job.get("definition")).get("tables");Object selected=object(mapping).get(table);Map<String,Object> mapped=map();String error="";if(selected!=null){try{mapped=TransferApply.mapped(db,object(selected),object(row.get("data")));}catch(Failure invalid){error=invalid.getMessage();}}
  byte[] content=(byte[])row.get("content");return map("job_id",id,"state",object(job.get("detail")).get("status"),"source_table",table,"source_id",sourceId,"source",row.get("data"),"mapping",selected,"mapped",mapped,"validation_error",error,"attachment_bytes",content==null?0:content.length,"attachment_sha256",content==null?"":DigestUtils.sha256Hex(content));
 }
}
