package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;
/** Durable stop before application; this does not undo destination data or abort an in-flight read. */
final class MigrationStops {
 private MigrationStops() {}
 static Map<String,Object> directory(Store.Db db) throws SQLException {
  Service.requireAccountAdministrator(db,db.viewerId);var rows=db.all("SELECT job_id,source_url,created,detail FROM os_transfer_job ORDER BY created DESC,job_id DESC LIMIT 101");boolean more=rows.size()>100;if(more)rows.removeLast();return map("rows",rows,"truncated",more);
 }
 static Map<String,Object> cancel(Store.Db db,String id,Object input) throws SQLException {
  Service.requireAccountAdministrator(db,db.viewerId);if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");var body=object(input);require(body.keySet().equals(Set.of("reason")),"Provide a cancellation reason");String reason=WorkJobs.text(body.get("reason"),2000);var job=TransferJobs.job(db,id);var state=object(job.get("detail"));String status=state.get("status").toString();
  if(status.equals("cancelled"))return TransferJobs.describe(db,id);
  if(!Set.of("fetching","review").contains(status))throw new Failure(409,"Only a migration that has not been applied can be cancelled");
  state.put("status","cancelled");state.put("cancelled_at",now());state.put("cancelled_by",db.viewerId);state.put("cancel_reason",reason);TransferJobs.save(db,id,state);
  SecurityEvents.record(db,db.viewerId,db.viewerId,"migration.cancelled",map("job_id",id,"previous_status",status,"reason",reason));return TransferJobs.describe(db,id);
 }
}
