package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
import java.net.URI;
import java.net.http.*;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.codec.digest.DigestUtils;

/** One attempted mutation. A lost lease or uncertain transport outcome must be reconciled. */
final class ConnectorWrites {
    private ConnectorWrites() {}
    static HttpRequest.BodyPublisher body(String content) {
        var used=new AtomicBoolean();byte[] bytes=content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return HttpRequest.BodyPublishers.ofInputStream(()->used.compareAndSet(false,true)?new ByteArrayInputStream(bytes):null);
    }
    static Map<String,Object> execute(Store store,Store.Db db,Map<String,Object> job,Object input) throws Exception {
        if(((Number)job.get("attempts")).intValue()!=1)throw new Failure(503,"Prior write attempt requires reconciliation");
        SeatRecovery.guardWrite(db,job.get("job_id").toString());
        var proposal=ConnectorActions.validate(store,db,input,false);var action=object(proposal.get("action"));var row=db.one("SELECT envelope FROM os_connector WHERE alias=?",action.get("alias"));String token=Outbox.decrypt(store.secret,row.get("envelope").toString()).get("token").toString();
        var request=HttpRequest.newBuilder(URI.create(proposal.get("destination").toString())).version(HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Idempotency-Key",job.get("job_id").toString()).method(action.get("method").toString(),body(proposal.get("content").toString())).build();
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()){
            var response=client.send(request,HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),1024*1024));
            return map("action_id",action.get("action_id"),"action_revision",action.get("revision"),"alias",action.get("alias"),"http_status",response.statusCode(),"bytes",response.body().length,"sha256",DigestUtils.sha256Hex(response.body()),"acknowledged",response.statusCode()/100==2,"correlation_id",job.get("job_id"));
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new Failure(503,"Write outcome requires reconciliation");}
        catch(IOException unknown){throw new Failure(503,"Write outcome requires reconciliation");}
    }
    static Map<String,Object> reconciliation(Store.Db db,String id) throws Exception {
        Service.requireAccountAdministrator(db,db.viewerId);var job=db.one("SELECT job_id,kind,state FROM os_work WHERE job_id=?",id);if(job==null)throw new Failure(404,"Job not found");require(job.get("kind").equals("connector.write"),"Only connector writes have external reconciliation");return map("job",job,"resolution",db.one("SELECT * FROM os_connector_resolution WHERE job_id=?",id));
    }
    static Map<String,Object> reconcile(Store.Db db,String id,Object input) throws Exception {
        var current=reconciliation(db,id);require(Set.of("succeeded","failed","cancelled").contains(object(current.get("job")).get("state")),"Wait for the write attempt to stop before reconciling");var body=object(input);require(body.keySet().equals(Set.of("decision","note")),"Provide decision and note");String decision=WorkJobs.text(body.get("decision"),20),note=WorkJobs.text(body.get("note"),2000);require(Set.of("applied","not_applied").contains(decision),"Choose verified applied or not_applied");
        if(current.get("resolution")!=null){var previous=object(current.get("resolution"));if(!previous.get("decision").equals(decision) || !previous.get("note").equals(note))throw new Failure(409,"Reconciliation was already recorded");return current;}
        db.update("INSERT INTO os_connector_resolution(job_id,decision,note,actor_id,recorded_at) VALUES(?,?,?,?,?)",id,decision,note,db.viewerId,now());SecurityEvents.record(db,db.viewerId,db.viewerId,"connector.reconciled",map("job_id",id,"decision",decision));return reconciliation(db,id);
    }
}
