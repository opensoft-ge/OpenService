package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import java.util.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Fixed vendor operations. A committed attempt precedes transmission and a readback precedes success. */
final class VerifiedActions {
    private VerifiedActions(){}
    private static final String PROCESS_ID=UUID.randomUUID().toString();
    static Map<String,Object> identity(Store store,String attempt,String token,Object revision,Object source,Object alias) throws Exception {
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");
        var utf8=java.nio.charset.StandardCharsets.UTF_8;
        mac.init(new javax.crypto.spec.SecretKeySpec(store.secret.getBytes(utf8),"HmacSHA256"));
        mac.update(("OpenService qualification/"+attempt+"/").getBytes(utf8));
        return map("process_id",PROCESS_ID,"worker",store.environment.getProperty("CONNECTOR_WORKER_ID",""),
            "credential_tag",HexFormat.of().formatHex(mac.doFinal(token.getBytes(utf8))),
            "token_revision",revision,"credential_source",source,"write_alias",alias);
    }
    private static final HttpClient CLIENT=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();

    static String guid(Object value){
        String id=WorkJobs.text(value,36).toLowerCase(Locale.ROOT);
        require(id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),"Choose a directory object UUID");
        return id;
    }
    static void bind(Store.Db db,Map<String,Object> step)throws Exception {
        String kind=step.get("kind").toString();
        if(kind.equals("jira_transition")){
            keys(step,"stage","kind","title","alias","oauth","issue","transition","status_id");
            require(text(step,"issue",80).matches("[A-Z][A-Z0-9_]*-[1-9][0-9]*"),"Choose a Jira issue key");
            require(text(step,"transition",32).matches("[0-9]+") && text(step,"status_id",32).matches("[0-9]+"),"Choose numeric transition and destination status IDs");
        }else if(kind.equals("jira_label")){
            keys(step,"stage","kind","title","alias","oauth","issue","label");
            require(text(step,"issue",80).matches("[A-Z][A-Z0-9_]*-[1-9][0-9]*"),"Choose a Jira issue key");
            require(text(step,"label",80).matches("[A-Za-z0-9_-]+"),"Choose a Jira label");
        }else {
            keys(step,"stage","kind","title","alias","oauth","target");
            step.put("target",guid(step.get("target")));
        }
        var connector=ConnectorConsole.page(db,text(step,"alias",80));
        step.put("connector",connector.get("review"));
        if(!"alias".equals(step.get("oauth"))){
            var oauth=definition(db,"import-schedule",id(step,"oauth"));
            require(List.of(db.viewerId,step.get("alias"),step.get("connector")).equals(List.of(oauth.get("owner"),oauth.get("alias"),oauth.get("connector"))),"Choose your matching OAuth schedule");
        }
    }
    static void delegation(Store.Db db,Map<String,Object> run)throws Exception {
        require("running".equals(run.get("state")),"Workflow has stopped");
        String delegate=WorkJobs.text(run.get("delegate"),32);
        var account=AgentPlans.account(db,delegate);AgentPlans.identity(account);
        ProblemInvestigations.revision(run.get("delegate_revision"),account.get("auth_revision"));
        var owner=AgentPlans.account(db,run.get("approver").toString());
        require("admin".equals(owner.get("role")),"Delegation owner lost authority");
        ProblemInvestigations.revision(run.get("approver_revision"),owner.get("auth_revision"));
    }
    static Map<String,Object> authority(Store store,Store.Db db,Map<String,Object> run,Map<String,Object> step)throws Exception {
        require(store.environment.getProperty("CONNECTOR_WRITES_ENABLED",Boolean.class,false),"Connector execution is disabled");
        if(!db.viewerRole.equals("admin")){
            require(store.environment.getProperty("AI_AGENT_EXECUTION_ENABLED",Boolean.class,false) && db.viewerId.equals(run.get("delegate")),"Execute as the authorized agent");
            delegation(db,run);
        }
        AccessPolicies.lease(db,run);
        validateApprovals(db,run);
        return RecurringImports.checkedConnector(store,db,step);
    }
    static void validateApprovals(Store.Db db,Map<String,Object> run)throws Exception{
        var publisher=AgentPlans.account(db,run.get("publisher").toString());
        require("admin".equals(publisher.get("role")),"Workflow publishing authority changed");
        ProblemInvestigations.revision(run.get("publisher_revision"),publisher.get("auth_revision"));
        AgentPlans.account(db,run.get("employee").toString());
        var identity=get(db,"workflow-identity",run.get("employee").toString());
        ProblemInvestigations.revision(run.get("identity_revision"),identity.get("revision"));
        for(var approval:objects(run.get("steps"))){
            if(!Set.of("approval","team_approval").contains(approval.get("kind")) || !"done".equals(approval.get("state")))continue;
            ProblemInvestigations.revision(approval.get("signer_revision"),AgentPlans.account(db,approval.get("by").toString()).get("auth_revision"));
            if("team_approval".equals(approval.get("kind")))require(db.count("SELECT COUNT(*) FROM os_group_member WHERE group_id=? AND user_id=?",approval.get("group"),approval.get("by"))==1,"Approval team membership changed");
        }
    }
    record Observation(boolean satisfied,int status,long delay){}
    static Observation request(String origin,String bearer,Map<String,Object> step,String member,boolean write,String correlation)throws Exception {
        String kind=step.get("kind").toString();
        boolean transition=kind.equals("jira_transition");
        boolean jira=kind.startsWith("jira_"),remove=kind.equals("entra_remove");
        String group="/v1.0/groups/"+step.get("target")+"/members";
        String path=jira?"/rest/api/3/issue/"+step.get("issue"):group+"/"+member;
        String method="GET",payload="";
        if(write){
            if(transition){method="POST";path+="/transitions";payload=JSON.writeValueAsString(map("transition",map("id",step.get("transition")), "historyMetadata",map("type","opensoft.openservice","extraData",map("correlation_id",correlation))));}
            else if(jira){ method="PUT";payload=JSON.writeValueAsString(map("update",map("labels",List.of(map("add",step.get("label"))))));}
            else if(remove){method="DELETE";path+="/$ref";}
            else {method="POST";path=group+"/$ref";payload=JSON.writeValueAsString(map("@odata.id","https://graph.microsoft.com/v1.0/directoryObjects/"+member));}
        }else if(jira) path+=transition?"?fields=status":"?fields=labels";
        var builder=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+bearer).header("Accept","application/json");
        if(write && !jira) builder.header("client-request-id",correlation);
        if(write) builder.header("Content-Type","application/json").method(method,ConnectorWrites.body(payload));
        var r=CLIENT.send(builder.build(),HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),1048576));
        int code=r.statusCode();
        if(code==429){
            String retry=r.headers().firstValue("Retry-After").orElse("60");
            long seconds=retry.matches("[0-9]{1,8}")?Long.parseLong(retry):60;
            return new Observation(false,429,Math.min(86400,Math.max(1,seconds))*1000);
        }
        if(write) return new Observation(false,code,0);
        if(!jira && code==404){
            if(remove){
                var lookup=HttpRequest.newBuilder(URI.create(origin+"/v1.0/groups/"+step.get("target")+"?$select=id")).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+bearer).GET().build();
                var parent=CLIENT.send(lookup,HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),65536));
                require(parent.statusCode()==200 && step.get("target").equals(object(ConnectorSynchronization.readJson(parent.body())).get("id")),"Verify that the target group is accessible");
            }
            return new Observation(remove,code,0);
        }
        require(code==200,"Destination read unavailable");
        var response=object(ConnectorSynchronization.readJson(r.body()));
        if(jira){
            require(step.get("issue").equals(response.get("key")),"Jira issue identity changed");
            if(transition)return new Observation(step.get("status_id").equals(object(object(response.get("fields")).get("status")).get("id")),code,0);
            return new Observation(((List<?>)object(response.get("fields")).get("labels")).contains(step.get("label")),code,0);
        }
        require(member.equalsIgnoreCase(Objects.toString(response.get("id"))),"Directory member identity changed");
        return new Observation(!remove,code,0);
    }
    static void apply(Store store,Store.Db db,Map<String,Object> run,Map<String,Object> step,String op,Map<String,Object> b)throws Exception {
        require(WorkflowStudio.REMOTE.contains(step.get("kind")) && !"done".equals(step.get("state")),"Choose an unfinished connector step");
        var connector=authority(store,db,run,step);
        if(op.equals("rearm")){
            admin(db);
            boolean observed="awaiting_acceptance".equals(step.get("state"));
            require(Boolean.TRUE.equals(b.get("confirm")) && (observed || "different".equals(step.get("state"))),"Recheck the destination before accepting the result or authorizing another attempt");
            var ticket=step.get("investigation");
            if(ticket!=null){
                var investigation=get(db,"agent-investigation",ticket.toString());
                var detail=object(investigation.get("definition"));
                require(db.viewerId.equals(detail.get("owner")),"Assigned investigator must authorize the retry");
                detail.putAll(map("state","resumed","authorized_by",db.viewerId,"authorization",observed?"accepted_observed_result":"retry_after_discrepancy","observed_http",step.get("observed_http"),"resumed_at",now()));
                save(db,"agent-investigation",ticket.toString(),investigation.get("revision"),detail);
                step.remove("investigation");
            }
            if(observed){
                step.put("state","done");
                return;
            }
            step.put("generation",((Number)step.getOrDefault("generation",0)).intValue()+1);
            step.put("state","pending");
            return;
        }
        boolean execute=op.equals("act");
        if(execute){
            require(Boolean.TRUE.equals(b.get("confirm")),"Confirm reviewed external effect");
            require(!step.containsKey("investigation"),"Recheck the destination and obtain fresh authorization from the assigned investigator");
        }
        require(System.currentTimeMillis()>=((Number)step.getOrDefault("retry_at",0)).longValue(),"Wait for the destination retry interval");
        String token;
        Object tokenRevision=connector.get("revision");
        if("alias".equals(step.get("oauth"))) token=Outbox.decrypt(store.secret,connector.get("envelope").toString()).get("token").toString();
        else{
            String key=step.get("oauth").toString();
            var schedule=definition(db,"import-schedule",key);
            require(List.of(run.get("publisher"),step.get("alias"),step.get("connector")).equals(List.of(schedule.get("owner"),schedule.get("alias"),schedule.get("connector"))),"OAuth schedule binding changed");
            definition(db,"schedule-token",key);
            token=IssueTokens.access(store,db,key,schedule,connector,true);
            tokenRevision=get(db,"schedule-token",key).get("revision");
        }
        String origin=connector.get("origin").toString(),member=run.get("directory_id").toString();
        String attempt=ConnectorSynchronization.hash(List.of(b.get("run_id"),b.get("step"),step.getOrDefault("generation",0)));
        String correlation=UUID.nameUUIDFromBytes(attempt.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).toString();
        step.put("provider_correlation_id",correlation);
        boolean fresh=get(db,"workflow-attempt",attempt).get("revision").equals(0);
        if(execute && fresh){
            var journal=identity(store,attempt,token,tokenRevision,step.get("oauth"),step.get("alias"));
            journal.putAll(map("actor",db.viewerId,"run",b.get("run_id"),"step",b.get("step"),"correlation_id",correlation,
                "origin",origin,"credential_revision",connector.get("revision"),"at",now(),"recorded_at",java.time.Instant.now().toString(),
                "kind",step.get("kind"),"group",step.get("target"),"member",member,"issue",step.get("issue"),"to_status",step.get("status_id")));
            save(db,"workflow-attempt",attempt,0,journal);
            db.connection.commit();
            lock(db);
            ProblemInvestigations.revision(b.get("revision"),get(db,"workflow-run",b.get("run_id").toString()).get("revision"));
            WorkflowStudio.current(db,b.get("run_id").toString(),run);
            authority(store,db,run,step);
        }
        boolean sending=false;
        try{
            var seen=request(origin,token,step,member,false,correlation);
            if(seen.satisfied && !step.containsKey("effect_origin"))step.put("effect_origin",execute && fresh?"preexisting":"observed_only");
            if(execute && fresh && !seen.satisfied && seen.status!=429){
                sending=true;
                var sent=request(origin,token,step,member,true,correlation);
                sending=false;
                step.put("write_status",sent.status);
                step.put("changed",sent.status/100==2);
                step.put("effect_origin",sent.status/100==2?"acknowledged_write":"unconfirmed_write");
                seen=sent.status==429?sent:request(origin,token,step,member,false,correlation);
            }
            if (!execute && !fresh && seen.satisfied) {
                var readback=identity(store,attempt,token,tokenRevision,step.get("oauth"),step.get("alias"));
                readback.putAll(map("observed_at",java.time.Instant.now().toString(),"correlation_id",correlation,
                    "origin",origin,"credential_revision",connector.get("revision"),"satisfied",true,"actor",db.viewerId));
                var prior=get(db,"workflow-readback",attempt);
                if (prior.get("revision").equals(0)) save(db,"workflow-readback",attempt,0,readback);
            }
            step.putAll(map("state",seen.satisfied?(step.containsKey("investigation")?"awaiting_acceptance":"done"):seen.status==429?"throttled":"different","observed_http",seen.status,
                    "retry_at",System.currentTimeMillis()+seen.delay,"verified_by",db.viewerId,"verified_at",now()));
        }catch(Exception unavailable){
            if (sending) save(db,"workflow-uncertain-write",attempt,0,map("correlation_id",correlation,
                "recorded_at",java.time.Instant.now().toString(),"process_id",PROCESS_ID));
            step.putAll(map("state","unverified","checked_at",now()));
            step.put("investigation",AgentInvestigations.open(db,"connector",b.get("run_id").toString(),run.get("publisher").toString(),map("step",b.get("step"),"attempt",attempt,"alias",step.get("alias"),"result","unverified")));
            if(unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }
}
