package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;

final class RecurringImports {
    private RecurringImports() {}

    static List<Map<String,Object>> schedules(Store.Db db) throws Exception {
        var rows=list(db,"import-schedule");
        var revisions=map();
        for(var profile:db.all("SELECT subject,revision FROM os_enterprise_document WHERE area=?", "schedule-token"))
            revisions.put(profile.get("subject").toString(),profile.get("revision"));
        for(var row:rows) {
            var job=object(row.get("definition"));
            row.put("oauth_revision",revisions.getOrDefault(row.get("subject"),0));
            if(job.containsKey("conflict")) {
                try { IssueSchedules.local(db,job.get("target").toString()); }
                catch(Failure denied) { job.remove("conflict"); }
            }
        }
        return rows;
    }

    static Object configure(Store.Db db, Map<String,Object> body) throws Exception {
        Service.requireAccountAdministrator(db,db.viewerId);
        lock(db);
        String key = EnterpriseData.id(body.get("key"));
        var row = get(db,"import-schedule",key);
        var definition = object(row.get("definition"));
        ProblemInvestigations.revision(body.get("revision"),row.get("revision"));
        if ("schedule".equals(body.get("operation"))) {
            keys(body,"operation","key","revision","kind","target","alias","path","minutes","enabled");
            String kind = WorkJobs.text(body.get("kind"),16);
            require(Set.of("inventory","threat","github","gitlab").contains(kind),"Choose inventory, threat, github or gitlab");
            String alias = WorkJobs.text(body.get("alias"),80);
            var connector = db.one("SELECT * FROM os_connector WHERE alias=?",alias);
            require(connector != null,"Choose an existing connector");
            String target = WorkJobs.text(body.get("target"),160);
            if (!kind.equals("inventory")) target = EnterpriseData.id(target);
            if (IssueAdapters.CATALOG.containsKey(kind)) {
                IssueAdapters.validatePath(kind,Connectors.path(body.get("path")));
            }
            require(body.get("enabled") instanceof Boolean,"Choose enabled or paused");
            definition = map("kind",kind,"target",target,"alias",alias,"connector",Connectors.review(connector),
                    "path",Connectors.path(body.get("path")),"minutes",integer(body.get("minutes"),1,10080),
                    "enabled",body.get("enabled"),"owner",db.viewerId,"authority",AgentPlans.account(db,db.viewerId).get("auth_revision"),
                    "status","scheduled","last_success",null);
            if(IssueAdapters.CATALOG.containsKey(kind)) definition.put("baseline",ConnectorSynchronization.hash(IssueSchedules.local(db,target)));
        } else if("resolve".equals(body.get("operation"))) {
            keys(body,"operation","key","revision","choice","proof");
            require("conflict".equals(definition.get("status")),"Select a current issue conflict");
            var conflict=object(definition.get("conflict"));
            require(conflict.get("proof").equals(body.get("proof")),"Issue conflict changed");
            require(Set.of("local","remote").contains(body.get("choice")),"Choose local or remote values");
            definition.putAll(map("resolution_proof",body.get("proof"),"choice",body.get("choice"),"status","scheduled"));
        } else {
            keys(body,"operation","key","revision");
            require(Boolean.TRUE.equals(definition.get("enabled")),"Enable the schedule before replay");
            definition.put("status","scheduled");
        }
        definition.put("next",0L);
        save(db,"import-schedule",key,row.get("revision"),definition);
        return map("schedule",key);
    }

    record Claim(String key,Map<String,Object> job,int revision,boolean maySend) {}

    static Claim claim(Store.Db db,long now) throws Exception {
        lock(db);
        var due=list(db,"import-schedule").stream().filter(row -> {
            var job=object(row.get("definition"));
            return Boolean.TRUE.equals(job.get("enabled")) && ((Number)job.get("next")).longValue()<=now;
        }).min(Comparator.comparingLong(row -> ((Number)object(row.get("definition")).get("next")).longValue()));
        if(due.isEmpty()) return null;
        var row=due.get();
        String key=row.get("subject").toString();
        var job=object(row.get("definition"));
        db.viewerId=job.get("owner").toString();
        boolean maySend="outbound_pending".equals(job.get("status"));
        job.putAll(map("status","fetching","next",now+120000));
        save(db,"import-schedule",key,row.get("revision"),job);
        db.connection.commit();
        return new Claim(key,job,((Number)row.get("revision")).intValue()+1,maySend);
    }

    static boolean runOne(Store store,long now) throws Exception {
        Claim claim;
        try(var db=store.open()) { claim=claim(db,now); }
        if(claim==null) return false;
        String key=claim.key();
        var job=claim.job();
        Object payload=null;
        String etag="";
        boolean fetched=false;
        try {
            String origin,token;
            try(var db=store.open()) {
                lock(db);
                ProblemInvestigations.revision(claim.revision(),get(db,"import-schedule",key).get("revision"));
                authority(db,job);
                var connector=checkedConnector(store,db,job);
                origin=connector.get("origin").toString();
                token=IssueTokens.access(store,db,key,job,connector,true);
                db.connection.commit();
            }
            var response=ServiceNowSource.getResponse(origin,token,job.get("path").toString(),1024*1024);
            etag=response.headers().firstValue("ETag").orElse("");
            payload=ConnectorSynchronization.readJson(response.body());
            fetched=true;
        } catch(Exception unavailable) { /* Finish the lease with a retryable status. */ }
        return finish(store,key,claim.revision(),now,fetched,payload,claim.maySend(),etag);
    }

    static Map<String,Object> checkedConnector(Store store,Store.Db db,Map<String,Object> job) throws Exception {
        var connector=db.one("SELECT * FROM os_connector WHERE alias=?",job.get("alias"));
        require(connector!=null && job.get("connector").equals(Connectors.review(connector)),"Reviewed import connector changed");
        require(Arrays.asList(store.environment.getProperty("CONNECTOR_ALLOWED_ORIGINS","").split(",")).contains(connector.get("origin")),"Import origin no longer allowed");
        return connector;
    }

    static void authority(Store.Db db,Map<String,Object> job) throws Exception {
        db.viewerId=job.get("owner").toString();
        var owner=AgentPlans.account(db,db.viewerId);
        require("admin".equals(owner.get("role")) && owner.get("auth_revision").toString().equals(job.get("authority").toString()),
                "Schedule owner authority changed");
        db.viewerRole="admin";
    }

    static boolean finish(Store store,String key,int revision,long now,boolean fetched,Object payload,boolean maySend,String etag) throws Exception {
        try(var db=store.open()) {
            return finishLocked(store,db,key,revision,now,fetched,payload,maySend,etag);
        }
    }

    static boolean finishLocked(Store store,Store.Db db,String key,int revision,long now,boolean fetched,Object payload,boolean maySend,String etag) throws Exception {
        lock(db);
        var row=get(db,"import-schedule",key);
        if(!row.get("revision").equals(revision)) return false;
        var job=object(row.get("definition"));
        db.viewerId=job.get("owner").toString();
        long next=now+60000L*((Number)job.get("minutes")).intValue();
        var checkpoint=db.connection.setSavepoint();
        try {
            require(fetched,"Source unavailable");
            authority(db,job);
            checkedConnector(store,db,job);
            if(IssueAdapters.CATALOG.containsKey(job.get("kind"))) {
                IssueSchedules.apply(store,db,key,job,payload,maySend,etag);
            } else {
                applySnapshot(db,job,payload);
                job.putAll(map("last_success",now,"status","succeeded","error",""));
            }
            if(!"outbound_pending".equals(job.get("status"))) job.put("next",next);
            save(db,"import-schedule",key,revision,job);
        } catch(Exception invalid) {
            db.connection.rollback(checkpoint);
            job=object(get(db,"import-schedule",key).get("definition"));
            job.remove("conflict");
            job.putAll(map("status","failed","next",next,"error",fetched?"IMPORT_VALIDATION_OR_AUTHORITY":"SOURCE_OR_AUTHORITY_UNAVAILABLE"));
            save(db,"import-schedule",key,revision,job);
        }
        db.connection.releaseSavepoint(checkpoint);
        db.connection.commit();
        return true;
    }

    static void applySnapshot(Store.Db db,Map<String,Object> job,Object payload) throws Exception {
        boolean inventory="inventory".equals(job.get("kind"));
        String area=inventory?"installations":"shared-feed";
        String target=job.get("target").toString();
        String subject=inventory?ConnectorSynchronization.hash(target):target;
        var dest=get(db,area,subject);
        if(inventory) {
            var source=object(payload);
            keys(source,"product","installations");
            require(target.equals(PurchasingControls.canonical(db,source.get("product"))),"Unexpected inventory product");
        }
        String fingerprint=ConnectorSynchronization.hash(payload);
        if(fingerprint.equals(job.get("digest")) && dest.get("revision").equals(job.get("target_revision"))) return;
        if(inventory) SoftwareInventory.replace(db,map("operation","inventory","product",target,"revision",dest.get("revision"),"installations",object(payload).get("installations")));
        else SharedIntelligence.replace(db,target,job.get("alias")+":"+job.get("path"),payload);
        job.put("target_revision",get(db,area,subject).get("revision"));
        job.put("digest",fingerprint);
    }
}
