package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;
import java.util.*;

/** Durable reminders reuse the live approval checks; scheduling grants no future authority. */
final class ScheduledApprovalReminders {
    private ScheduledApprovalReminders() {}

    static String approval(Object input) {
        var body=object(input);
        require(body.keySet().equals(Set.of("approval_id")),"Provide approval_id only");
        return WorkJobs.text(body.get("approval_id"),32);
    }

    static void validate(Store.Db db,String actor,Object input) throws Exception {
        var diagnostic=ApprovalDiagnostics.inspect(db,actor,approval(input));
        if(!diagnostic.get("reason").equals("awaiting_decision"))throw new Failure(409,"Only a current eligible pending review can be scheduled");
    }

    static Map<String,Object> schedule(Store store,Store.Db db,String actor,String id,Object input) throws Exception {
        var body=object(input);
        require(body.keySet().equals(Set.of("key","run_at")),"Provide key and run_at");
        return WorkJobs.enqueue(store,db,actor,map("kind","approval.reminder","key",body.get("key"),"run_at",body.get("run_at"),"payload",map("approval_id",id)));
    }

    static Map<String,Object> deliver(Store.Db db,String actor,Object input) throws Exception {
        return ApprovalReminders.send(db,actor,approval(input),map());
    }
}
