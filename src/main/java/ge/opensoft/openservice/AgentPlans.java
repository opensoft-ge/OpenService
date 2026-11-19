package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.EnterpriseData.*;
import java.util.*;

/** Supervised native plans with delegated authority and one atomic durable step per tick. */
final class AgentPlans {
    private AgentPlans() {}
    static Map<String,Object> account(Store.Db db,String user)throws Exception {
        var row=db.one("SELECT user_id,name,role,active,auth_revision,api_only,password_reset_required FROM os_user WHERE user_id=?",user);
        require(row!=null && row.get("active").equals(1) && row.get("password_reset_required").equals(0),"Agent account authority is unavailable");return row;
    }
    static void identity(Map<String,Object> user){require(user.get("role").equals("agent") && user.get("api_only").equals(1),"Delegate to an API-only agent account");}
    static Map<String,Object> grant(Store.Db db,String user)throws Exception {
        var row=get(db,"agent-grant",user);var data=object(row.get("definition"));require(data.getOrDefault("owner","").equals(db.viewerId) && Boolean.TRUE.equals(data.get("active")),"Current owned agent grant required");identity(account(db,user));return row;
    }
    static Map<String,Object> record(Store.Db db,Map<String,Object> step)throws Exception {String table=WorkJobs.text(step.get("table"),80);require(Set.of("incident","problem","cmdb_ci","sc_request").contains(table),"Native plans currently support incidents, problems, configuration items and managed requests");return Service.readRecord(db,table,EnterpriseData.id(step.get("record_id")));}
    static void permitted(Store.Db db,Map<String,Object> step,Map<String,Object> grant)throws Exception {
        record(db,step);var patch=object(step.get("patch"));require(!patch.isEmpty() && patch.size()<=10,"Plan updates require one to ten fields");
        var targets=objects(object(grant.get("definition")).get("targets"));var match=targets.stream().filter(t->t.get("table").equals(step.get("table")) && t.get("record_id").equals(step.get("record_id"))).findFirst();
        require(match.isPresent() && ((List<?>)match.get().get("fields")).containsAll(patch.keySet()),"Plan exceeds the delegated target or field grant");FieldAccess.writing(db,Service.schema(db,step.get("table").toString()),patch);
    }
    static Object taskRevision(Store.Db db,Map<String,Object> step) throws Exception {
        if("sc_request".equals(step.get("table"))) return Fulfilment.row(db,step.get("record_id").toString()).get("revision");
        return 0;
    }
    static Map<String,Object> apply(Store.Db db,Map<String,Object> step,Map<String,Object> record) throws Exception {
        String table=step.get("table").toString();
        if(table.equals("sc_request")) {
            require(object(step.get("patch")).equals(map("state","fulfilling")),"For managed requests propose state=fulfilling to claim the task");
            String id=step.get("record_id").toString();
            Fulfilment.control(db,id,map("operation","claim","revision",taskRevision(db,step),"request_revision",record.get("revision")));
            return Service.readRecord(db,table,id);
        }
        return Service.update(db,table,record,step.get("patch"),actor(db),db.viewerRole,record.get("revision").toString());
    }
    static Map<String,Object> previewStep(Store.Db db,String delegate,Map<String,Object> step,Map<String,Object> grant)throws Exception {
        permitted(db,step,grant);String owner=db.viewerId,role=db.viewerRole;var checkpoint=db.connection.setSavepoint();
        try{db.viewerId=delegate;db.viewerRole="agent";permitted(db,step,grant);var record=record(db,step);int revision=((Number)record.get("revision")).intValue();var taskRevision=taskRevision(db,step);var before=FieldAccess.serialize(db,record);var after=FieldAccess.serialize(db,apply(db,step,record));var shown=map();for(String field:object(step.get("patch")).keySet())shown.put(field,map("before",before.getOrDefault(field,""),"after",after.getOrDefault(field,"")));var preview=map("table",step.get("table"),"record_id",step.get("record_id"),"patch",step.get("patch"),"revision",revision,"fulfilment_revision",taskRevision,"review",shown);if("sc_request".equals(step.get("table"))) preview.put("assignment",map("operation","claim","assignee",delegate,"result","in_progress"));return preview;}
        finally{db.connection.rollback(checkpoint);db.connection.releaseSavepoint(checkpoint);db.viewerId=owner;db.viewerRole=role;}
    }
    static Map<String,Object> owned(Store.Db db,String id)throws Exception {var row=get(db,"agent-plan",id);require(object(row.get("definition")).getOrDefault("owner","").equals(db.viewerId),"Agent plan not found for this account");return row;}
    static Map<String,Object> page(Store.Db db)throws Exception {
        Service.requireAccountAdministrator(db,db.viewerId);var plans=new ArrayList<Map<String,Object>>();for(var row:list(db,"agent-plan")){var data=object(row.get("definition"));if(!data.get("owner").equals(db.viewerId))continue;try{for(var step:objects(data.get("steps"))){record(db,step);FieldAccess.writing(db,Service.schema(db,step.get("table").toString()),object(step.get("patch")));}}catch(Failure unavailable){row.put("definition",map("state",data.get("state"),"unavailable",true,"completed_steps",objects(data.get("receipts")).size()));}row.put("evaluation",get(db,"plan-evaluation",row.get("subject").toString()).get("definition"));plans.add(row);}
        var grants=new ArrayList<Map<String,Object>>();for(var row:list(db,"agent-grant"))if(object(row.get("definition")).get("owner").equals(db.viewerId)){var data=object(row.get("definition"));try{for(var target:objects(data.get("targets")))record(db,target);}catch(Failure unavailable){row.put("definition",map("active",data.get("active"),"unavailable",true));}grants.add(row);}
        return EnterpriseData.page("Delegated agent plans","Grant exact targets and fields to an API-only agent, review a bounded plan, then authorize execution. Each step rechecks both identities and current record rules. Stop prevents further steps and retains completed receipts.",map("grants",grants,"plans",plans),
            form("grant","Set delegated tool grant",field("delegate_id","API-only agent account ID","text",""),field("revision","Grant revision","number",0),field("active","Enable grant","boolean",true),field("targets","Allowed targets: table, record_id, fields","json","[]")),
            form("evaluate","Evaluate reviewed plan",field("key","Reviewed plan ID","text",""),field("revision","Plan revision","number",1)),
            form("preview_model","Preview model planning request",field("key","New plan key","text",Values.id()),field("delegate_id","Delegated account ID","text",""),field("grant_revision","Grant revision","number",1),field("goal","Planning goal","text","")),
            form("draft_model","Generate plan for review",field("key","Previewed plan key","text",""),field("delegate_id","Delegated account ID","text",""),field("grant_revision","Grant revision","number",1),field("goal","Previewed planning goal","text",""),field("preview_token","Planning preview token","text",""),field("confirm","Send reviewed request to model","boolean",false)),
            form("revoke","Revoke delegated tool grant",field("delegate_id","Delegated account ID","text",""),field("revision","Grant revision","number",1)),
            form("review","Prepare and review a plan",field("key","Unique plan key","text",Values.id()),field("delegate_id","Delegated account ID","text",""),field("grant_revision","Grant revision","number",1),field("steps","Ordered steps: table, record_id, patch","json","[]")),
            form("start","Authorize reviewed plan",field("key","Plan key","text",""),field("revision","Plan revision","number",1),field("confirm","Confirm exact reviewed effects","boolean",false)),
            form("stop","Stop future plan steps",field("key","Plan key","text",""),field("revision","Plan revision","number",1),field("reason","Stop reason","text","")));
    }
    static void command(Store store,Store.Db db,Object input)throws Exception {
        Service.requireAccountAdministrator(db,db.viewerId);lock(db);var body=object(input);String op=WorkJobs.text(body.get("operation"),10);
        if(op.equals("revoke")){keys(body,"operation","delegate_id","revision");String delegate=EnterpriseData.id(body.get("delegate_id"));var row=get(db,"agent-grant",delegate);var data=object(row.get("definition"));require(data.getOrDefault("owner","").equals(db.viewerId),"Owned grant required");data.put("active",false);save(db,"agent-grant",delegate,body.get("revision"),data);return;}
        if(op.equals("grant")){
            keys(body,"operation","delegate_id","revision","active","targets");String delegate=EnterpriseData.id(body.get("delegate_id"));identity(account(db,delegate));require(body.get("active") instanceof Boolean,"Choose active or inactive");require(body.get("targets") instanceof List<?> list && !list.isEmpty() && list.size()<=20,"Grant one to twenty exact targets");var targets=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();
            for(var target:objects(body.get("targets"))){keys(target,"table","record_id","fields");record(db,target);require(seen.add(target.get("record_id").toString()),"Grant each record once");require(target.get("fields") instanceof List<?> fields && !fields.isEmpty() && fields.size()<=10,"Grant one to ten fields");var names=new HashSet<String>();for(Object field:(List<?>)target.get("fields")){String name=WorkJobs.text(field,80);require(names.add(name) && object(Service.schema(db,target.get("table").toString()).get("fields")).containsKey(name),"Grant unique current editable fields");}targets.add(map("table",target.get("table"),"record_id",target.get("record_id"),"fields",new ArrayList<>(new TreeSet<>(names))));}
            var prior=object(get(db,"agent-grant",delegate).get("definition"));require(prior.isEmpty() || prior.get("owner").equals(db.viewerId),"Another account owns this delegated grant");save(db,"agent-grant",delegate,body.get("revision"),map("owner",db.viewerId,"active",body.get("active"),"targets",targets));return;
        }
        String key=EnterpriseData.id(body.get("key"));
        if(op.equals("review")){
            keys(body,"operation","key","delegate_id","grant_revision","steps");String delegate=EnterpriseData.id(body.get("delegate_id"));var grant=grant(db,delegate);ProblemInvestigations.revision(body.get("grant_revision"),grant.get("revision"));require(body.get("steps") instanceof List<?> list && !list.isEmpty() && list.size()<=10,"Review one to ten native record updates");var steps=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();for(var step:objects(body.get("steps"))){keys(step,"table","record_id","patch");require(seen.add(EnterpriseData.id(step.get("record_id"))),"Use each target once per plan");steps.add(previewStep(db,delegate,step,grant));}
            save(db,"agent-plan",key,0,map("owner",db.viewerId,"owner_revision",account(db,db.viewerId).get("auth_revision"),"delegate",delegate,"delegate_revision",account(db,delegate).get("auth_revision"),"grant_revision",grant.get("revision"),"state","reviewed","steps",steps,"receipts",new ArrayList<>(),"expires_at",System.currentTimeMillis()+86400000L));return;
        }
        require(Set.of("start","stop").contains(op),"Choose grant, review, start or stop");keys(body,op.equals("start")?new String[]{"operation","key","revision","confirm"}:new String[]{"operation","key","revision","reason"});var row=owned(db,key);var data=object(row.get("definition"));ProblemInvestigations.revision(body.get("revision"),row.get("revision"));
        if(op.equals("start")){require(store.environment.getProperty("AI_AGENT_EXECUTION_ENABLED",Boolean.class,false),"Delegated execution is disabled");require(data.get("state").equals("reviewed") && Boolean.TRUE.equals(body.get("confirm")),"Confirm a reviewed plan once");authority(db,data);data.put("state","queued");}else{require(Set.of("reviewed","queued").contains(data.get("state")),"Only pending work can be stopped");data.putAll(map("state","stopped","reason",WorkJobs.text(body.get("reason"),2000)));}save(db,"agent-plan",key,row.get("revision"),data);
    }
    static Map<String,Object> authority(Store.Db db,Map<String,Object> data)throws Exception {
        var owner=account(db,data.get("owner").toString());require(owner.get("role").equals("admin") && owner.get("auth_revision").toString().equals(data.get("owner_revision").toString()),"Approving authority changed");db.viewerId=owner.get("user_id").toString();db.viewerRole="admin";
        var delegate=account(db,data.get("delegate").toString());identity(delegate);require(delegate.get("auth_revision").toString().equals(data.get("delegate_revision").toString()),"Delegated authority changed");var grant=grant(db,delegate.get("user_id").toString());ProblemInvestigations.revision(data.get("grant_revision"),grant.get("revision"));require(((Number)data.get("expires_at")).longValue()>System.currentTimeMillis(),"Agent plan expired");return grant;
    }
    static boolean runOne(Store store)throws Exception {
        if(!store.environment.getProperty("AI_AGENT_EXECUTION_ENABLED",Boolean.class,false))return false;
        try(var db=store.open()){lock(db);return executeNext(db);}
    }
    static boolean executeNext(Store.Db db)throws Exception {
            Map<String,Object> row=null;for(var candidate:list(db,"agent-plan"))if(object(candidate.get("definition")).get("state").equals("queued")){row=candidate;break;}if(row==null)return false;
            executeStep(db,row);
            db.connection.commit();
            return true;
    }
    static void executeStep(Store.Db db,Map<String,Object> row) throws Exception {
            if(!"queued".equals(object(row.get("definition")).get("state"))) return;
            var data=object(row.get("definition"));var checkpoint=db.connection.setSavepoint();var receipts=objects(data.get("receipts"));String owner=data.get("owner").toString();
            try{var grant=authority(db,data);var step=objects(data.get("steps")).get(receipts.size());permitted(db,step,grant);db.viewerId=data.get("delegate").toString();db.viewerRole="agent";permitted(db,step,grant);var record=record(db,step);ProblemInvestigations.revision(step.get("revision"),record.get("revision"));ProblemInvestigations.revision(step.getOrDefault("fulfilment_revision",0),taskRevision(db,step));var after=apply(db,step,record);receipts.add(map("step",receipts.size()+1,"record_id",step.get("record_id"),"revision",after.get("revision"),"executed_by",db.viewerId,"completed",now()));data.put("receipts",receipts);if(receipts.size()==objects(data.get("steps")).size())data.put("state","completed");}
            catch(Exception failed){db.connection.rollback(checkpoint);data.putAll(map("state","failed","error","AUTHORITY_INPUT_OR_EXECUTION_UNAVAILABLE"));}
            finally{db.connection.releaseSavepoint(checkpoint);db.viewerId=owner;db.viewerRole="admin";}
            save(db,"agent-plan",row.get("subject").toString(),row.get("revision"),data);
    }
}
