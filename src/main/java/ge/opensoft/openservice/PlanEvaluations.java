package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
import java.math.BigDecimal;

final class PlanEvaluations {
    private PlanEvaluations() {}

    static Map<String,Object> meter(Store store,Object usage,long latency) {
        var result=map("provider_latency_ms",latency,"estimated_cost_usd",null);
        if(!(usage instanceof Map<?,?> counts)) return result;
        Object input=counts.get("prompt_tokens"),output=counts.get("completion_tokens");
        if(!(input instanceof Integer in) || !(output instanceof Integer out) || in<0 || out<0) return result;
        result.putAll(map("input_tokens",in,"output_tokens",out));
        String inputPrice=store.environment.getProperty("AI_PLAN_INPUT_USD_PER_MILLION","");
        String outputPrice=store.environment.getProperty("AI_PLAN_OUTPUT_USD_PER_MILLION","");
        if(!inputPrice.matches("[0-9]{1,8}([.][0-9]{1,8})?") || !outputPrice.matches("[0-9]{1,8}([.][0-9]{1,8})?")) return result;
        BigDecimal cost=new BigDecimal(inputPrice).multiply(BigDecimal.valueOf(in))
                .add(new BigDecimal(outputPrice).multiply(BigDecimal.valueOf(out))).movePointLeft(6);
        result.put("estimated_cost_usd",cost.toPlainString());
        return result;
    }

    static Object run(Store store,Store.Db db,Map<String,Object> body) throws Exception {
        lock(db);
        keys(body,"operation","key","revision");
        String key=EnterpriseData.id(body.get("key"));
        var plan=AgentPlans.owned(db,key);
        ProblemInvestigations.revision(body.get("revision"),plan.get("revision"));
        String delegate=object(plan.get("definition")).get("delegate").toString();
        var metrics=map("generation",object(get(db,"plan-meter",key).get("definition")));
        for(String scenario:List.of("task_success","revocation_rejected","stop_prevented_effects")) {
            var checkpoint=db.connection.setSavepoint();
            long started=System.nanoTime();
            try {
                AgentPlans.command(store,db,map("operation","start","key",key,"revision",plan.get("revision"),"confirm",true));
                if(scenario.equals("revocation_rejected")) {
                    AgentPlans.command(store,db,map("operation","revoke","delegate_id",delegate,
                            "revision",AgentPlans.grant(db,delegate).get("revision")));
                }
                if(scenario.equals("stop_prevented_effects")) {
                    AgentPlans.command(store,db,map("operation","stop","key",key,"revision",AgentPlans.owned(db,key).get("revision"),"reason","Evaluation stop"));
                }
                int steps=objects(object(plan.get("definition")).get("steps")).size();
                for(int i=0;i<steps;i++) {
                    var trial=AgentPlans.owned(db,key);
                    trial.put("subject",key);
                    AgentPlans.executeStep(db,trial);
                }
                var result=object(AgentPlans.owned(db,key).get("definition"));
                int effects=objects(result.get("receipts")).size();
                String expected=Map.of("task_success","completed","revocation_rejected","failed","stop_prevented_effects","stopped").get(scenario);
                metrics.put(scenario,map("passed",List.of(expected,scenario.equals("task_success")?steps:0).equals(List.of(result.get("state"),effects)),
                        "state",result.get("state"),"native_effects_in_trial",effects,"latency_ms",(System.nanoTime()-started)/1000000));
            } finally {
                db.connection.rollback(checkpoint);
                db.connection.releaseSavepoint(checkpoint);
            }
        }
        var old=get(db,"plan-evaluation",key);
        save(db,"plan-evaluation",key,old.get("revision"),metrics);
        return metrics;
    }
}
