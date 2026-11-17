package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** Bounded operator history. Claim and completion use the job lock and transaction. */
final class WorkAttempts {
    private WorkAttempts() {}

    static void start(Store.Db db,Map<String,Object> job,long now) throws SQLException {
        long ordinal=db.count("SELECT COALESCE(MAX(ordinal),0) FROM os_work_attempt WHERE job_id=?",job.get("job_id"))+1;
        db.update("INSERT INTO os_work_attempt(job_id,lease,ordinal,attempt_number,started_at,finished_at,state,error) VALUES(?,?,?,?,?,0,'processing','')",job.get("job_id"),job.get("lease"),ordinal,job.get("attempts"),now);
        db.update("DELETE FROM os_work_attempt WHERE job_id=? AND ordinal<=?",job.get("job_id"),ordinal-100);
    }

    static void finish(Store.Db db,Object job,Object lease,long now,String state,String error) throws SQLException {
        db.update("UPDATE os_work_attempt SET finished_at=?,state=?,error=? WHERE job_id=? AND lease=? AND state='processing'",now,state,error,job,lease);
    }

    static Map<String,Object> page(Store.Db db,String actor,String id,Map<String,String> args) throws SQLException {
        Service.requireAccountAdministrator(db,actor);
        require(Set.of("limit","offset").containsAll(args.keySet()),"Use limit and offset only");
        int limit=Service.number(args.getOrDefault("limit","50"),1,100),offset=Service.number(args.getOrDefault("offset","0"),0,100);
        if(db.one("SELECT job_id FROM os_work WHERE job_id=?",id)==null)throw new Failure(404,"Job not found");
        long total=db.count("SELECT COUNT(*) FROM os_work_attempt WHERE job_id=?",id);
        var rows=db.all("SELECT ordinal,attempt_number,started_at,finished_at,state,error FROM os_work_attempt WHERE job_id=? ORDER BY ordinal DESC LIMIT ? OFFSET ?",id,limit,offset);
        for(var row:rows){
            long start=((Number)row.get("started_at")).longValue(),end=((Number)row.get("finished_at")).longValue();
            row.put("started_at_utc",Instant.ofEpochMilli(start).toString());row.put("finished_at_utc",end==0?"":Instant.ofEpochMilli(end).toString());row.put("duration_ms",end==0?-1:Math.max(0,end-start));
        }
        return map("job_id",id,"result",rows,"limit",limit,"offset",offset,"total",total,"previous_offset",Math.max(0,offset-limit),"next_offset",offset+rows.size()<total?offset+limit:-1);
    }
}
