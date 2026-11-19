package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
import java.math.*;

final class ApplicationInvestments {
    private ApplicationInvestments(){}
    static Object command(Store.Db db,String op,Map<String,Object> b)throws Exception {
        admin(db);
        if(op.equals("compare_plans"))return compare(db,b);
        if(op.equals("application") || op.equals("dependency")){
            String key=id(b,"application_id");
            var app=object(get(db,"business-app",key).get("definition"));
            if(op.equals("application")){
                String currency=text(b,"currency",3);
                require(currency.matches("[A-Z]{3}"),"Choose a three-letter currency");
                AgentPlans.account(db,id(b,"owner_id"));
                app.putAll(map("title",text(b,"title",160),"capability",text(b,"capability",160),"owner",b.get("owner_id"),"annual_cost",money(b.get("annual_cost")).toPlainString(),"currency",currency));
            }else{
                require(!app.isEmpty() && !"retired".equals(app.get("lifecycle")),"Choose a current registered application");
                var dependencies=new LinkedHashSet<String>();
                for(Object raw:batch(b.get("applications"),64)){
                    String dep=EnterpriseData.id(raw);
                    require(!"retired".equals(definition(db,"business-app",dep).get("lifecycle")),"Select a current dependency");
                    require(!dep.equals(key) && dependencies.add(dep),"Choose distinct external dependencies");
                }
                app.put("dependencies",dependencies);
            }
            save(db,"business-app",key,b.get("revision"),app);
            return get(db,"business-app",key);
        }
        String key=id(b,"plan_id");
        var plan=definition(db,"retire-plan",key);
        if(op.equals("approve_plan")){
            require(!plan.containsKey("choice"),"Plan already decided");
            String option=text(b,"option",8);
            require(Set.of("option_a","option_b").contains(option),"Choose a saved option");
            var choice=object(plan.get(option));
            require(Boolean.TRUE.equals(choice.get("feasible")),"Resolve dependencies, funding and staffing before approval");
            require(plan.get("versions").equals(versions(db)),"Application portfolio changed since comparison");
            for(Object app:(List<?>)choice.get("retire")){
                String appId=app.toString();
                var registration=get(db,"business-app",appId);
                var entry=object(registration.get("definition"));
                require(!entry.containsKey("retirement_plan"),"Application already committed to a retirement plan");
                entry.putAll(map("retirement_plan",key,"lifecycle","retirement_approved"));
                save(db,"business-app",appId,registration.get("revision"),entry);
            }
            plan.putAll(map("choice",option,"approved_by",db.viewerId,"approved_at",now()));
        }else if(op.equals("retire")){
            require(plan.containsKey("choice"),"Approve the investment first");
            String appId=id(b,"application_id");
            var registered=get(db,"business-app",appId);
            var entry=object(registered.get("definition"));
            require(key.equals(entry.get("retirement_plan")) && "retirement_approved".equals(entry.get("lifecycle")),"Choose an active retirement commitment from this plan");
            for(var row:list(db,"business-app")){
                var consumer=object(row.get("definition"));
                if(((List<?>)consumer.getOrDefault("dependencies",List.of())).contains(appId))
                    require("retired".equals(consumer.get("lifecycle")),"Retire or migrate active dependent applications first");
            }
            entry.putAll(map("lifecycle","retired","evidence",text(b,"note",2000),"retired_at",now(),"retired_by",db.viewerId));
            save(db,"business-app",appId,registered.get("revision"),entry);
            var retired=new ArrayList<Object>((List<?>)plan.getOrDefault("retired",List.of()));
            retired.add(appId);plan.put("retired",retired);
        }else{
            require(plan.containsKey("choice"),"Approve the investment first");
            var actuals=object(plan.getOrDefault("actuals",map()));
            String ref=text(b,"reference",100),amount=money(b.get("amount")).toPlainString();
            if(actuals.containsKey(ref)){
                require(amount.equals(actuals.get(ref)),"Savings reference already has a different amount");
                return get(db,"retire-plan",key);
            }
            String receipt=ConnectorSynchronization.hash(List.of(plan.get("currency"),ref));
            save(db,"retirement-actual",receipt,0,map("plan",key,"amount",amount,"reference",ref));
            actuals.put(ref,amount);
            BigDecimal total=actuals.values().stream().map(v->new BigDecimal(v.toString())).reduce(BigDecimal.ZERO,BigDecimal::add);
            plan.putAll(map("actuals",actuals,"realized_total",total.toPlainString(),"remaining_forecast",new BigDecimal(object(plan.get(plan.get("choice"))).get("forecast").toString()).subtract(total).toPlainString()));
        }
        save(db,"retire-plan",key,b.get("revision"),plan);
        return get(db,"retire-plan",key);
    }
    static Map<String,Object> versions(Store.Db db)throws Exception{
        var result=map();
        for(var row:list(db,"business-app")) result.put(row.get("subject").toString(),row.get("revision"));
        return result;
    }
    static Object compare(Store.Db db,Map<String,Object> b)throws Exception {
        String project=id(b,"project_id"),scenario=id(b,"scenario_id"),key=id(b,"new_id");
        var staff=object(StaffingScenarios.command(db,project,map("operation","open_scenario","key",scenario)));
        var options=batch(b.get("options"),2);
        require(options.size()==2,"Specify both retirement options");
        int months=integer(b.get("months"),1,120);
        var plan=map("months",months,"project",project,"staff_scenario",scenario,"versions",versions(db),"currency",object(staff.get("inputs")).get("currency"));
        var apps=list(db,"business-app");
        for(int i=0;i<2;i++){
            String side=i==0?"option_a":"option_b";
            var option=object(options.get(i));
            keys(option,"retire","transition_cost");
            var retire=new TreeSet<String>();
            for(Object value:batch(option.get("retire"),100)){
                String app=EnterpriseData.id(value);
                definition(db,"business-app",app);
                require(retire.add(app),"Duplicate retirement");
            }
            var allocations=object(object(staff.get("comparison")).get(side));
            BigDecimal transition=money(option.get("transition_cost")),annual=BigDecimal.ZERO;
            var blockers=new ArrayList<Map<String,Object>>();
            var affected=new TreeSet<String>();
            var baseline=map();
            for(var row:apps){
                String app=row.get("subject").toString();
                var info=object(row.get("definition"));
                if(retire.contains(app)){
                    require(plan.get("currency").equals(info.get("currency")),"Use matching portfolio currency");
                    baseline.put(app,info);
                    annual=annual.add(money(info.get("annual_cost")));
                    affected.add(info.get("capability").toString());
                }else if(!"retired".equals(info.get("lifecycle"))) for(Object dep:(List<?>)info.getOrDefault("dependencies",List.of()))
                    if(retire.contains(dep))blockers.add(map("consumer",app,"requires",dep));
            }
            BigDecimal funding=new BigDecimal(allocations.get("funding_after_labor").toString()).subtract(transition);
            BigDecimal forecast=annual.multiply(BigDecimal.valueOf(months)).divide(BigDecimal.valueOf(12),2,RoundingMode.HALF_UP).subtract(transition);
            plan.put(side,map("retire",retire,"baseline",baseline,"capabilities",affected,"forecast",forecast.toPlainString(),"transition_cost",transition.toPlainString(),
                    "dependencies",blockers,"staffing",allocations,"remaining_funding",funding.toPlainString(),"feasible",blockers.isEmpty() && funding.signum()>=0 && ((List<?>)allocations.get("overbooked")).isEmpty()));
        }
        save(db,"retire-plan",key,0,plan);
        return get(db,"retire-plan",key);
    }
}
