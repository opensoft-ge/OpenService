package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import java.util.*;
import java.time.*;

final class WorkflowStudio {
    private WorkflowStudio() {}
    static final Set<String> REMOTE=Set.of("entra_add","entra_remove","jira_label");
    static final Set<String> TYPES=Set.of("approval","team_approval","work","entra_add","entra_remove","jira_label");

    static Object directory(Store.Db db)throws Exception {
        var templates=new ArrayList<Map<String,Object>>();
        for(var row:list(db,"workflow-template")){
            var spec=object(row.get("definition"));
            templates.add(map("template_id",row.get("subject"),"title",spec.get("title"),"version",row.get("revision")));
        }
        var runs=new ArrayList<Map<String,Object>>();
        for(var row:list(db,"workflow-run")){
            var run=object(row.get("definition"));
            if(visible(db,run)) runs.add(map("run_id",row.get("subject"),"title",run.get("title"),"state",run.get("state"),"revision",row.get("revision")));
        }
        var directory=map("templates",templates,"runs",runs);
        if("admin".equals(db.viewerRole)) directory.put("drafts",list(db,"workflow-draft"));
        return directory;
    }
    static boolean visible(Store.Db db,Map<String,Object> run)throws Exception {
        if("admin".equals(db.viewerRole) || List.of(run.get("employee"),run.get("manager"),run.getOrDefault("delegate","")).contains(db.viewerId)) return true;
        for(var step:objects(run.get("steps")))
            if(Set.of("work","team_approval").contains(step.get("kind")) && Fulfilment.member(db,step.get("group").toString())) return true;
        return false;
    }
    static Object command(Store store,Store.Db db,String op,Map<String,Object> b)throws Exception {
        if(Set.of("preview_benchmark","run_benchmark").contains(op))return WorkflowBenchmark.run(store,db,op,b);
        if(op.equals("identity")){
            admin(db);
            String user=id(b,"user_id"),manager=id(b,"manager_id");
            AgentPlans.account(db,user);AgentPlans.account(db,manager);
            require(!user.equals(manager),"Choose a separate manager");
            String directory=VerifiedActions.guid(b.get("directory_id"));
            save(db,"workflow-identity",user,b.get("revision"),map("manager",manager,"directory_id",directory));
            return map("user_id",user);
        }
        if(op.startsWith("append_") || Set.of("remove_step","publish_draft").contains(op)) return draft(db,op,b);
        if(op.equals("publish")) return publish(db,b);
        if(op.equals("request")) return start(db,b,db.viewerId);
        String key=id(b,"run_id");
        var row=get(db,"workflow-run",key);
        var run=definition(db,"workflow-run",key);
        require(visible(db,run),"Workflow is unavailable");
        if(op.equals("inspect")) {
            var terms=object(get(db,"transfer-terms",key).get("definition"));
            if("admin".equals(db.viewerRole)){row.put("transfer",terms);return row;}
            var tasks=new ArrayList<Map<String,Object>>();
            for(var step:objects(run.get("steps"))) tasks.add(map("kind",step.get("kind"),"title",step.get("title"),"stage",step.get("stage"),"state",step.get("state")));
            return map("title",run.get("title"),"state",run.get("state"),"steps",tasks,"transfer",List.of(run.get("employee"),run.get("manager")).contains(db.viewerId)?terms:map(),"version",run.get("version"),"revision",row.get("revision"));
        }
        if(Set.of("preview_actions","draft_actions").contains(op)) return WorkflowPlanReview.propose(store,db,key,run,row.get("revision"),op,b);
        if(op.equals("evaluate_run")) return evaluate(db,run);
        ProblemInvestigations.revision(b.get("revision"),row.get("revision"));
        require("running".equals(run.get("state")),"This workflow is terminal");
        if(op.equals("stop_run")){
            require(db.viewerId.equals(run.get("employee")) || db.viewerRole.equals("admin"),"Only the requester or administrator can cancel");
            run.putAll(map("state","stopped","stop_reason",text(b,"note",1000)));
        } else {
            current(db,key,run);
            if(op.equals("delegate_run")){
                admin(db);
                require(Boolean.TRUE.equals(b.get("confirm")),"Confirm this exact delegation");
                require(run.containsKey("proposed"),"Review a drafted plan before delegation");
                String agent=id(b,"delegate_id");
                var account=AgentPlans.account(db,agent);AgentPlans.identity(account);
                run.putAll(map("delegate",agent,"delegate_revision",account.get("auth_revision"),"approver",db.viewerId,
                        "approver_revision",AgentPlans.account(db,db.viewerId).get("auth_revision")));
            } else if(op.equals("queue_run")){
                admin(db);
                require(Boolean.TRUE.equals(b.get("confirm")) && db.viewerId.equals(run.get("approver")),"Confirm scheduling as the approving administrator");
                VerifiedActions.delegation(db,run);
                run.put("queued",true);
            } else {
                var steps=objects(run.get("steps"));
                var step=steps.get(integer(b.get("step"),1,steps.size())-1);
                require(Objects.equals(step.get("stage"),stage(run)),"Complete earlier stages first");
                if(op.equals("advance")) {
                    require("pending".equals(step.get("state")),"This step is already settled");
                    if("approval".equals(step.get("kind"))){
                        var identity=definition(db,"workflow-identity",run.get("employee").toString());
                        require(db.viewerId.equals(identity.get("manager")) && db.viewerId.equals(run.get("manager")),"Current assigned manager approval required");
                    } else {
                        require(Set.of("work","team_approval").contains(step.get("kind")) && Fulfilment.member(db,step.get("group").toString()),"Assigned team member required");
                        if("team_approval".equals(step.get("kind"))) require(!db.viewerId.equals(run.get("employee")) && steps.stream().noneMatch(s->"team_approval".equals(s.get("kind")) && db.viewerId.equals(s.get("by")) && step.get("stage").equals(s.get("stage"))),"Each parallel team approval requires an independent signer");
                    }
                    step.putAll(map("state","done","by",db.viewerId,"signer_revision",AgentPlans.account(db,db.viewerId).get("auth_revision"),"note",text(b,"note",1000),"at",now()));
                } else {
                    if(!"admin".equals(db.viewerRole)) require(((List<?>)run.getOrDefault("proposed",List.of())).contains(b.get("step")),"Action outside the approved plan");
                    VerifiedActions.apply(store,db,run,step,op,b);
                }
                run.put("steps",steps);
                if(stage(run)==null) run.put("state","complete");
            }
        }
        save(db,"workflow-run",key,row.get("revision"),run);
        return map("workflow_state",run.get("state"),"revision",get(db,"workflow-run",key).get("revision"));
    }
    static void current(Store.Db db,String key,Map<String,Object> run)throws Exception{
            var provenance=object(get(db,"hr-system-run",key).get("definition"));
            if("effective".equals(provenance.get("phase"))){
                String hr=provenance.get("case").toString();
                var parent=db.one("SELECT data FROM os_record WHERE sys_id=?",hr);
                require(parent!=null && !Set.of("closed","cancelled").contains(object(parent.get("data")).get("state")),"Employment change is closed or cancelled");
                require(!LocalDate.now().isBefore(LocalDate.parse(definition(db,"employment-change",hr).get("effective").toString())),"Employment change was rescheduled");
            }
            require(System.currentTimeMillis()<=((Number)run.get("deadline")).longValue(),"Workflow deadline has passed");
    }
    static Object stage(Map<String,Object> run){
        return objects(run.get("steps")).stream().filter(s->!"done".equals(s.get("state"))).map(s->(Integer)s.get("stage")).min(Integer::compareTo).orElse(null);
    }
    static Object draft(Store.Db db,String op,Map<String,Object> b)throws Exception{
        admin(db);
        String key=id(b,"template_id");
        var row=get(db,"workflow-draft",key);
        ProblemInvestigations.revision(b.get("draft_revision"),row.get("revision"));
        var steps=new ArrayList<>(objects(object(row.get("definition")).getOrDefault("steps",List.of())));
        if(op.equals("publish_draft")){
            var publication=new LinkedHashMap<>(b);
            publication.put("steps",steps);
            return publish(db,publication);
        }
        if(op.equals("remove_step"))steps.remove(integer(b.get("step"),1,steps.size())-1);
        else{
            require(steps.size()<24,"A workflow supports 24 steps");
            var step=new LinkedHashMap<>(b);
            for(String field:List.of("operation","template_id","draft_revision"))step.remove(field);
            switch(op){
                case "append_approval" -> step.put("kind","approval");
                case "append_issue" -> step.put("kind","jira_label");
                case "append_team" -> require(Set.of("work","team_approval").contains(step.get("kind")),"Choose work or team_approval");
                default -> require(Set.of("entra_add","entra_remove").contains(step.get("kind")),"Choose entra_add or entra_remove");
            }
            steps.add(step);
        }
        save(db,"workflow-draft",key,row.get("revision"),map("steps",steps));
        return get(db,"workflow-draft",key);
    }
    static Object publish(Store.Db db,Map<String,Object> b)throws Exception {
        admin(db);
        String key=id(b,"template_id");
        var steps=new ArrayList<Map<String,Object>>();
        int previous=0,approvalStage=0;
        for(Object entry:batch(b.get("steps"),24)){
            var s=new LinkedHashMap<>(object(entry));
            String kind=text(s,"kind",20);
            require(TYPES.contains(kind),"Choose a supported task kind");
            int stage=integer(s.get("stage"),1,24);
            require(stage>=previous && stage<=previous+1,"Use consecutive stages starting at one");
            previous=stage;
            text(s,"title",160);
            if(kind.equals("approval")){
                keys(s,"kind","stage","title");
                approvalStage=stage;
            } else if(Set.of("team_approval","work").contains(kind)){
                keys(s,"kind","stage","title","group");
                Service.readRecord(db,"sys_user_group",id(s,"group"));
            } else {
                require(approvalStage>0 && approvalStage<stage,"Put manager approval before external actions");
                VerifiedActions.bind(db,s);
            }
            s.put("state","pending");
            steps.add(s);
        }
        require(!steps.isEmpty(),"Add workflow steps");
        save(db,"workflow-template",key,b.get("revision"),map("title",text(b,"title",160),"days",integer(b.get("days"),1,365),"steps",steps,
                "publisher",db.viewerId,"publisher_revision",AgentPlans.account(db,db.viewerId).get("auth_revision")));
        return get(db,"workflow-template",key);
    }
    static Map<String,Object> start(Store.Db db,Map<String,Object> b,String employee)throws Exception {
        String template=id(b,"template_id"),key=id(b,"new_id");
        var row=get(db,"workflow-template",template);
        ProblemInvestigations.revision(b.get("revision"),row.get("revision"));
        var spec=definition(db,"workflow-template",template);
        var identity=get(db,"workflow-identity",employee);
        var subject=definition(db,"workflow-identity",employee);
        var run=new LinkedHashMap<>(spec);
        run.putAll(map("template",template,"version",row.get("revision"),"state","running","employee",employee,"manager",subject.get("manager"),
                "directory_id",subject.get("directory_id"),"identity_revision",identity.get("revision"),"requested_by",db.viewerId,
                "deadline",Instant.now().plus(Duration.ofDays(((Number)spec.get("days")).longValue())).toEpochMilli()));
        save(db,"workflow-run",key,0,run);
        return map("run_id",key,"version",run.get("version"),"state",run.get("state"));
    }
    static boolean runOne(Store store)throws Exception{
        if(!store.environment.getProperty("AI_AGENT_EXECUTION_ENABLED",Boolean.class,false) || !store.environment.getProperty("CONNECTOR_WRITES_ENABLED",Boolean.class,false))return false;
        try(var db=store.open()){
            lock(db);
            return runNext(store,db);
        }
    }
    static boolean runNext(Store store,Store.Db db)throws Exception{
        for(var row:list(db,"workflow-run")){
            var run=object(row.get("definition"));
            if(!Boolean.TRUE.equals(run.get("queued")) || !"running".equals(run.get("state")))continue;
            var steps=objects(run.get("steps"));
            for(Object choice:(List<?>)run.get("proposed")){
                int index=(int)choice;
                var step=steps.get(index-1);
                if(!"pending".equals(step.get("state")) || !step.get("stage").equals(stage(run)))continue;
                db.viewerId=run.get("delegate").toString();db.viewerRole="agent";
                String key=row.get("subject").toString();
                try{
                    command(store,db,"act",map("run_id",key,"revision",row.get("revision"),"step",index,"confirm",true));
                }catch(Failure unavailable){
                    db.connection.rollback();
                    lock(db);
                    var fresh=get(db,"workflow-run",key);
                    if(!Objects.equals(fresh.get("revision"),row.get("revision")))return false;
                    run.putAll(map("queued",false,"queue_status","requires_review"));
                    save(db,"workflow-run",key,row.get("revision"),run);
                }
                db.connection.commit();
                return true;
            }
        }
        return false;
    }
    static Object evaluate(Store.Db db,Map<String,Object> run)throws Exception {
        admin(db);
        var trials=new ArrayList<Map<String,Object>>();
        for(String scenario:List.of("current","revoked","stopped")){
            var copy=new LinkedHashMap<>(run);
            if(scenario.equals("revoked")) copy.put("delegate_revision",-1);
            if(scenario.equals("stopped")) copy.put("state","stopped");
            long started=System.nanoTime();
            boolean permitted=true;
            try{VerifiedActions.delegation(db,copy);}catch(Failure denied){permitted=false;}
            trials.add(map("scenario",scenario,"permitted",permitted,"expected",scenario.equals("current"),"latency_us",(System.nanoTime()-started)/1000));
        }
        return map("trials",trials,"completed_steps",objects(run.get("steps")).stream().filter(s->"done".equals(s.get("state"))).count(),"mode","authority replay");
    }
    static Object employment(Store.Db db,String caseId,String op,Map<String,Object> b)throws Exception {
        admin(db);
        var record=EmployeeCases.record(db,caseId,false);
        var change=definition(db,"employment-change",caseId);
        String employee=db.one("SELECT user_id FROM os_hr_case WHERE record_id=?",caseId).get("user_id").toString();
        if(op.equals("systems")){
            String run=id(b,"run_id");
            require(caseId.equals(definition(db,"hr-system-run",run).get("case")),"Choose work from this case");
            return get(db,"workflow-run",run);
        }
        String phase="effective";
        if(op.equals("reverse")){
            String prior=id(b,"run_id");
            var provenance=definition(db,"hr-system-run",prior);
            require(caseId.equals(provenance.get("case")) && "effective".equals(provenance.get("phase")),"Choose the original system change");
            require("cancelled".equals(object(record.get("data")).get("state")),"Cancel the employee change before reversal");
            var original=definition(db,"workflow-run",prior);
            require(objects(original.get("steps")).stream().anyMatch(s->"done".equals(s.get("state")) && REMOTE.contains(s.get("kind"))),"No acknowledged system effect to reverse");
            var permitted=new HashMap<List<Object>,String>();
            for(var effect:objects(original.get("steps"))){
                String kind=effect.get("kind").toString();
                if(!Set.of("entra_add","entra_remove").contains(kind) || !Boolean.TRUE.equals(effect.get("changed")) || !"done".equals(effect.get("state")))continue;
                var target=List.of(effect.get("alias"),effect.get("connector"),effect.get("target"));
                String inverse=kind.equals("entra_add")?"entra_remove":"entra_add";
                // Opposite acknowledged changes to the same membership cancel out.
                if(kind.equals(permitted.get(target)))permitted.remove(target);
                else permitted.putIfAbsent(target,inverse);
            }
            var reversal=definition(db,"workflow-template",id(b,"template_id"));
            for(var step:objects(reversal.get("steps"))) if(REMOTE.contains(step.get("kind"))){
                var target=List.of(step.get("alias"),step.get("connector"),step.getOrDefault("target",""));
                require(step.get("kind").equals(permitted.remove(target)),"Only reverse confirmed directory effects on their original targets");
            }
            require(permitted.isEmpty(),"Include every confirmed directory change in the reversal");
            require(original.get("directory_id").equals(definition(db,"workflow-identity",employee).get("directory_id")),"Directory identity changed since provisioning");
            phase="reversal";
        } else {
            require(EmployeeOnboarding.open(record),"An open HR change is required");
            require(!LocalDate.now().isBefore(LocalDate.parse(change.get("effective").toString())),"Wait for the effective date");
        }
        var result=start(db,b,employee);
        String run=result.get("run_id").toString();
        save(db,"hr-system-phase",caseId+phase,0,map("run",run));
        save(db,"hr-system-run",run,0,map("case",caseId,"phase",phase,"original",b.getOrDefault("run_id","")));
        return result;
    }
}
