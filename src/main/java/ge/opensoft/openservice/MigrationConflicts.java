package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
import java.sql.SQLException;
import org.apache.commons.codec.digest.DigestUtils;

/** Explicit decisions for one frozen synchronization; global correlations change only on apply. */
final class MigrationConflicts {
    private MigrationConflicts() {}
    static Map<String,Object> context(Store.Db db,String id) throws SQLException {
        Service.requireAccountAdministrator(db,db.viewerId);var job=TransferJobs.job(db,id);var state=object(job.get("detail"));TransferJobs.expect(state,"review");require(state.containsKey("previous_job"),"Run a synchronization before resolving destination conflicts");
        if(TransferJobs.generation(db,job.get("source_url").toString())!=((Number)state.get("generation")).longValue())throw new Failure(409,"Another migration was applied; create a fresh synchronization");return job;
    }
    static Map<String,Object> decisions(Map<String,Object> job) {return object(object(job.get("detail")).getOrDefault("resolutions",map()));}
    static boolean decided(Map<String,Object> job,String table,String id){return decisions(job).containsKey(table+":"+id);}
    static Map<String,Object> page(Store.Db db,String id,Map<String,String> args) throws SQLException {
        Service.requireAccountAdministrator(db,db.viewerId);require(Set.of("limit","offset").containsAll(args.keySet()),"Use limit and offset");int limit=Service.number(args.getOrDefault("limit","100"),1,100),offset=Service.number(args.getOrDefault("offset","0"),0,10000);var job=TransferJobs.job(db,id);var state=object(job.get("detail"));var conflicts=new ArrayList<Map<String,Object>>();
        boolean editable=state.get("status").equals("review") && state.containsKey("previous_job");
        if(editable)context(db,id);
        if(editable)for(var row:db.all("SELECT i.source_table,i.source_id,l.detail FROM os_transfer_item i JOIN os_transfer_link l ON l.source_url=? AND l.source_table=i.source_table AND l.source_id=i.source_id WHERE i.job_id=? ORDER BY i.source_table,i.source_id",job.get("source_url"),id)){
            var detail=object(row.get("detail"));if(detail.get("kind").equals("record") && !Objects.equals(detail.get("destination"),TransferApply.snapshot(db,"record",detail.get("target").toString())))conflicts.add(map("source_table",row.get("source_table"),"source_id",row.get("source_id"),"saved",decisions(job).getOrDefault(row.get("source_table")+":"+row.get("source_id"),map())));
        }
        // Include retained decisions even when the conflict disappeared or the job was applied.
        var keys=new HashSet<String>();for(var row:conflicts)keys.add(row.get("source_table")+":"+row.get("source_id"));for(var decision:decisions(job).entrySet())if(keys.add(decision.getKey())){var saved=object(decision.getValue());conflicts.add(map("source_table",saved.get("source_table"),"source_id",saved.get("source_id"),"saved",saved));}
        conflicts.sort(Comparator.comparing(row->row.get("source_table")+":"+row.get("source_id")));int start=Math.min(offset,conflicts.size()),end=Math.min(start+limit,conflicts.size());return map("job_id",id,"state",state.get("status"),"editable",editable,"conflicts",conflicts.subList(start,end),"total",conflicts.size(),"offset",offset,"limit",limit,"previous_offset",Math.max(0,offset-limit),"next_offset",end<conflicts.size()?end:-1);

    }
    static Map<String,Object> inspect(Store.Db db,String id,String table,String sourceId) throws SQLException {
        var job=context(db,id);var mapping=object(object(job.get("definition")).get("tables")).get(table);var row=db.one("SELECT data FROM os_transfer_item WHERE job_id=? AND source_table=? AND source_id=?",id,table,sourceId);var link=db.one("SELECT detail FROM os_transfer_link WHERE source_url=? AND source_table=? AND source_id=?",job.get("source_url"),table,sourceId);if(mapping==null || row==null || link==null)throw new Failure(404,"Staged correlated record not found");
        var previous=object(link.get("detail"));require(previous.get("kind").equals("record") && previous.get("target").equals(sourceId),"Only existing correlated record conflicts can be resolved");var current=TransferApply.snapshot(db,"record",sourceId);if(current==null)throw new Failure(409,"Deleted destinations need separate reconciliation");
        String target=object(mapping).get("target").toString();require(current.get("table").equals(target),"Destination table changed");var schema=Service.schema(db,target);RecordPolicy.read(db,schema);Service.record(db,target,sourceId);
        if(Objects.equals(previous.get("destination"),current))throw new Failure(409,"The destination no longer has this conflict; refresh the synchronization");
        var source=map("source",row.get("data"),"mapping",mapping);String review=DigestUtils.sha256Hex("migration-record-decision-v1:"+JSON.writeValueAsString(map("job_id",id,"generation",object(job.get("detail")).get("generation"),"table",table,"source_id",sourceId,"source",source,"previous",previous,"current",current,"schema",schema)));
        var saved=object(decisions(job).getOrDefault(table+":"+sourceId,map()));return map("job_id",id,"source_table",table,"source_id",sourceId,"target_table",target,"baseline",previous.get("destination"),"current",current,"mapped",TransferApply.mapped(db,object(mapping),object(row.get("data"))),"source",source,"review",review,"revision",saved.getOrDefault("revision",0),"saved",saved,"job",job);
    }
    static Map<String,Object> save(Store.Db db,String id,String table,String sourceId,Object input) throws SQLException {
        var view=inspect(db,id,table,sourceId);var body=object(input);require(body.keySet().equals(Set.of("decision","reason","review","revision","confirm")),"Provide the reviewed decision, reason, revision and confirmation");String decision=WorkJobs.text(body.get("decision"),20),reason=WorkJobs.text(body.get("reason"),2000);require(Set.of("keep_destination","use_source").contains(decision),"Choose keep_destination or use_source");require(Boolean.TRUE.equals(body.get("confirm")),"Confirm the conflict decision");if(!view.get("review").equals(body.get("review")) || !view.get("revision").equals(body.get("revision")))throw new Failure(409,"Conflict or decision changed; review it again");
        var job=object(view.get("job"));var state=object(job.get("detail"));var decisions=new LinkedHashMap<>(decisions(job));decisions.put(table+":"+sourceId,map("source_table",table,"source_id",sourceId,"decision",decision,"reason",reason,"review",view.get("review"),"revision",((Number)view.get("revision")).intValue()+1,"actor_id",db.viewerId,"auth_revision",db.one("SELECT auth_revision FROM os_user WHERE user_id=?",db.viewerId).get("auth_revision"),"recorded_at",now()));state.put("resolutions",decisions);TransferJobs.save(db,id,state);SecurityEvents.record(db,db.viewerId,db.viewerId,"migration.conflict_decided",map("job_id",id,"source_table",table,"source_id",sourceId,"decision",decision));return inspect(db,id,table,sourceId);
    }
    static Map<String,Object> consume(Store.Db db,Map<String,Object> job,String table,String sourceId,Object source,String kind) throws SQLException {
        require(kind.equals("record"),"Only record conflicts support reviewed decisions");var decision=object(decisions(job).get(table+":"+sourceId));var view=inspect(db,job.get("job_id").toString(),table,sourceId);if(!view.get("review").equals(decision.get("review")))throw new Failure(409,"Saved conflict decision is stale; review it again");
        var actor=db.one("SELECT active,role,auth_revision FROM os_user WHERE user_id=?",decision.get("actor_id"));if(actor==null || !actor.get("active").equals(1) || !actor.get("role").equals("admin") || ((Number)actor.get("auth_revision")).longValue()!=((Number)decision.get("auth_revision")).longValue())throw new Failure(409,"Conflict reviewer authority changed; obtain a fresh decision");
        var link=map("source",source,"kind",kind,"target",sourceId);if(decision.get("decision").equals("keep_destination")){TransferApply.remember(db,job,table,sourceId,link,kind);return null;}return link;
    }
}
