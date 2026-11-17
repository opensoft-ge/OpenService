package ge.opensoft.openservice;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
import java.util.*;

/** One bounded source page per durable claim; promotion remains a separate reviewed action. */
final class MigrationFetch {
    private MigrationFetch() {}
    static Map<String,Object> payload(Object input) {
        var body=object(input);require(body.keySet().equals(Set.of("migration_id","token")),"Provide migration_id and token");
        require(WorkJobs.text(body.get("migration_id"),32).matches("[0-9a-f]{32}"),"Use a migration ID");require(WorkJobs.text(body.get("token"),4096).matches("[A-Za-z0-9._~+/=-]{1,4096}"),"Supply a source OAuth bearer token");return body;
    }
    static void validate(Store.Db db,String actor,Object input) throws SQLException {
        Service.requireAccountAdministrator(db,actor);var body=payload(input);TransferJobs.expect(object(TransferJobs.job(db,body.get("migration_id").toString()).get("detail")),"fetching");
    }
    static Map<String,Object> queue(Store store,Store.Db db,String actor,String id,Object input) throws Exception {
        var body=object(input);require(body.keySet().equals(Set.of("key","token")),"Provide key and token");return WorkJobs.enqueue(store,db,actor,map("kind","migration.fetch","key",body.get("key"),"payload",map("migration_id",id,"token",body.get("token"))));
    }
    static Map<String,Object> step(Store.Db db,Object input,String allowed) throws Exception {
        var body=payload(input);String id=body.get("migration_id").toString();var job=TransferJobs.job(db,id);var state=object(job.get("detail"));String status=state.get("status").toString();
        if(status.equals("fetching"))state=object(TransferJobs.step(db,id,body.get("token").toString(),allowed).get("detail"));
        else if(!Set.of("review","applied","cutover").contains(status))throw new Failure(409,"Migration cannot be fetched in its current state");
        return map("migration_id",id,"status",state.get("status"),"phase",state.get("phase"),"staged_rows",db.count("SELECT COUNT(*) FROM os_transfer_item WHERE job_id=?",id),"continue",state.get("status").equals("fetching"));
    }
}
