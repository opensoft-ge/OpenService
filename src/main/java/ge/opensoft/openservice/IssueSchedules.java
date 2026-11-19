package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.TypedFields.require;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

final class IssueSchedules {
    private IssueSchedules() {}

    static Map<String,Object> local(Store.Db db,String id) throws Exception {
        var record=Service.readRecord(db,"incident",id);
        var shown=FieldAccess.serialize(db,record);
        var schema=Service.schema(db,"incident");
        RecordPolicy.require(schema,"update",db.viewerRole);
        var fields=map();
        for(String field:List.of("short_description","description")) {
            require(FieldAccess.allowed(db,schema,field),"Issue field is unavailable");
            fields.put(field,Objects.toString(shown.get(field),""));
        }
        return fields;
    }

    static void apply(Store store,Store.Db db,String key,Map<String,Object> job,Object payload,boolean maySend,String etag) throws Exception {
        String adapter=job.get("kind").toString(), path=job.get("path").toString(), id=job.get("target").toString();
        var issue=IssueAdapters.source(adapter,path,object(payload),id);
        var remote=object(issue.get("values"));
        var current=local(db,id);
        var pair=map("local",current,"remote",remote,"issue",issue.get("id"));
        String proof=ConnectorSynchronization.hash(pair);
        Object approved=job.remove("resolution_proof");
        String choice=Objects.toString(job.remove("choice"),"");
        String localHash=ConnectorSynchronization.hash(current),remoteHash=ConnectorSynchronization.hash(remote);
        String direction;
        Object outbound=job.remove("outbound");
        if(proof.equals(approved)) direction=choice;
        else if(!issue.get("id").equals(job.getOrDefault("issue_id",issue.get("id")))) direction="conflict";
        else if(current.equals(remote)) direction="matched";
        else if(outbound!=null) direction=maySend && proof.equals(outbound)?"send":"conflict";
        else if(localHash.equals(job.get("baseline"))) direction="remote";
        else if(remoteHash.equals(job.get("baseline"))) direction="local";
        else direction="conflict";
        job.remove("conflict");
        switch(direction) {
            case "remote" -> {
                var record=Service.record(db,"incident",id);
                Service.update(db,"incident",record,remote,actor(db),db.viewerRole,record.get("revision").toString());
                job.put("baseline",remoteHash);
            }
            case "local" -> {
                job.putAll(map("outbound",proof,"status","outbound_pending","next",0L,"issue_id",issue.get("id")));
                return;
            }
            case "send" -> {
                require(store.environment.getProperty("CONNECTOR_WRITES_ENABLED",Boolean.class,false),"Connector writes are disabled");
                var connector=RecurringImports.checkedConnector(store,db,job);
                String token=IssueTokens.access(store,db,key,job,connector,false);
                boolean github=adapter.equals("github");
                String json=JSON.writeValueAsString(map("title",current.get("short_description"),github?"body":"description",current.get("description")));
                var request=HttpRequest.newBuilder(URI.create(connector.get("origin")+path)).timeout(Duration.ofSeconds(15))
                        .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                        .method(github?"PATCH":"PUT",ConnectorWrites.body(json));
                if(etag.startsWith("\"")) request.header("If-Match",etag);
                var response=IssueTokens.exchange(request.build());
                var updated=IssueAdapters.source(adapter,path,response,id);
                require(List.of(updated.get("id"),updated.get("values")).equals(List.of(issue.get("id"),current)),"Verify outbound issue result");
                job.put("baseline",localHash);
            }
            case "matched" -> job.put("baseline",localHash);
            default -> {
                pair.put("proof",proof);
                job.putAll(map("status","conflict","conflict",pair));
                return;
            }
        }
        job.putAll(map("issue_id",issue.get("id"),"last_success",System.currentTimeMillis(),"status","succeeded","error",""));
    }
}
