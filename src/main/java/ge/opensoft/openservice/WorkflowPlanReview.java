package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;

final class WorkflowPlanReview {
    private WorkflowPlanReview(){}
    static List<Map<String,Object>> choices(Map<String,Object> run){
        var result=new ArrayList<Map<String,Object>>();
        var steps=objects(run.get("steps"));
        for(int i=0;i<steps.size();i++){
            var step=steps.get(i);
            if(WorkflowStudio.REMOTE.contains(step.get("kind")) && !"done".equals(step.get("state")))
                result.add(map("step",i+1,"kind",step.get("kind"),"stage",step.get("stage"),"title",step.get("title")));
        }
        return result;
    }
    static List<Integer> selection(Object raw,List<Map<String,Object>> choices){
        var selected=new ArrayList<Integer>();
        int last=0;
        for(Object value:batch(raw,24)){
            int step=integer(value,1,24);
            require(step>last && choices.stream().anyMatch(c->c.get("step").equals(step)),"Choose ordered and authorized workflow actions");
            selected.add(step);
            last=step;
        }
        return selected;
    }
    static Object propose(Store store,Store.Db db,String key,Map<String,Object> run,Object revision,String op,Map<String,Object> b)throws Exception{
        admin(db);
        require("running".equals(run.get("state")) && !run.containsKey("delegate"),"Choose an active workflow before delegation");
        ProblemInvestigations.revision(b.get("revision"),revision);
        var choices=choices(run);
        require(!choices.isEmpty(),"Workflow has no outstanding connector actions");
        var prompt=map("goal",text(b,"goal",2000),"choices",choices);
        String consent=AiConsent.state(store,db,db.viewerId,"workflow-planner",key,map("prompt",prompt,"revision",revision));
        if(op.equals("preview_actions")){
            var review=AiConsent.issue(store,consent);
            review.putAll(map("payload",prompt,"model",store.environment.getProperty("AI_MODEL",""),"provider_alias",store.environment.getProperty("AI_CONNECTOR_ALIAS","")));
            return review;
        }
        require(Boolean.TRUE.equals(b.get("confirm")),"Confirm sending the displayed goal and workflow step titles");
        AiConsent.verify(store,b.get("preview_token"),consent);
        require(store.environment.getProperty("AI_ENABLED",Boolean.class,false),"AI assistance is disabled");
        long started=System.nanoTime();
        var response=ModelPlans.completion(store,db,prompt,"Select the necessary listed actions to satisfy the goal. Return only a JSON array of step numbers in execution order. Return [] when the goal cannot be fulfilled.");
        Object raw;
        try{raw=JSON.readValue(response.get("text").toString(),Object.class);}catch(tools.jackson.core.JacksonException invalid){throw new Failure(502,"Invalid planning response");}
        var proposed=selection(raw,choices);
        require(!proposed.isEmpty(),"Provider found no permitted plan");
        run.putAll(map("proposed",proposed,"meter",PlanEvaluations.meter(store,response.get("usage"),(System.nanoTime()-started)/1000000),"drafted_by",db.viewerId));
        save(db,"workflow-run",key,revision,run);
        return map("proposed",proposed,"effects",choices,"revision",get(db,"workflow-run",key).get("revision"),"meter",run.get("meter"));
    }
}
