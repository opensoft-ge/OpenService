package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;

/** One accessible operator surface over the domain-specific contracts. */
final class EnterpriseWorkspaces {
    private EnterpriseWorkspaces() {}
    static Map<String,Object> page(Store.Db db,String area,String subject)throws Exception {
        if(DeliveryWorkspace.AREAS.containsKey(area)) return DeliveryWorkspace.page(db,area,subject);
        if(JourneyForms.GLOBAL.contains(area)) return JourneyForms.decorate(db,area,JourneyForms.global(db,area,subject));
        if(!Set.of("admin","agent").contains(db.viewerRole))throw new Failure(403,"Staff workspace access required");
        return JourneyForms.decorate(db,area,switch(area){
            case "hr" -> HrLifecycle.page(db,EnterpriseData.id(subject));
            case "field" -> FieldPlanning.page(db,subject);
            case "security" -> SecurityPlaybooks.page(db,EnterpriseData.id(subject));
            case "portfolio" -> PortfolioResources.page(db,EnterpriseData.id(subject));
            case "assets" -> AssetPlanning.page(db);
            case "integrations" -> ConnectorSynchronization.page(db);
            case "agents" -> AgentPlans.page(db);
            default -> throw new Failure(404,"Enterprise workspace not found");
        });
    }
    static Object command(Store store,Store.Db db,String area,String subject,Object input)throws Exception {
        page(db,area,subject);
        var body = object(input);
        if(DeliveryWorkspace.AREAS.containsKey(area)) return DeliveryWorkspace.command(store,db,area,body);
        Object operation = body.get("operation");
        String op=Objects.toString(operation);
        for(var descriptor:JourneyForms.ALL) if(descriptor.area().equals(area) && descriptor.name().equals(op)) return JourneyForms.execute(store,db,subject,descriptor,body);
        if(JourneyForms.GLOBAL.contains(area)) throw new Failure(400,"Unknown workspace operation");
        if(area.equals("integrations") && op.equals("schedule_oauth")) return IssueTokens.configure(store,db,body);
        if(area.equals("integrations") && Set.of("schedule","replay","resolve").contains(op)) return RecurringImports.configure(db,body);
        if(area.equals("assets") && Set.of("alias","invoice","dated_invoice").contains(op)) return PurchasingControls.command(db,body);
        if(area.equals("security") && Set.of("link_feed","observe","shared_matches").contains(op)) return SharedIntelligence.command(db,subject,body);
        if(area.equals("portfolio") && Set.of("calendar","save_scenario","open_scenario").contains(op)) return StaffingScenarios.command(db,subject,body);
        if(area.equals("hr") && Set.of("schedule_change","move_change").contains(op)) return EmploymentChanges.command(db,subject,body);
        if(area.equals("agents") && op.equals("evaluate")) return PlanEvaluations.run(store,db,body);
        if (area.equals("portfolio") && "compare".equals(operation)) {
            return InvestmentScenarios.compare(db, body);
        }
        if (area.equals("security") && "match".equals(operation)) {
            return ThreatIndicators.lookup(db, subject, body);
        }
        if (area.equals("agents") && Set.of("preview_model","draft_model").contains(Objects.toString(operation))) {
            return ModelPlans.propose(store, db, body);
        }
        return switch(area){
            case "hr" -> {HrLifecycle.command(db,subject,input);yield map("saved",true);}
            case "field" -> FieldPlanning.command(db,subject,input);
            case "security" -> {SecurityPlaybooks.command(store,db,subject,input);yield map("saved",true);}
            case "portfolio" -> {PortfolioResources.command(db,subject,input);yield map("saved",true);}
            case "assets" -> {AssetPlanning.command(db,input);yield map("saved",true);}
            case "integrations" -> ConnectorSynchronization.command(store,db,input);
            default -> {AgentPlans.command(store,db,input);yield map("saved",true);}
        };
    }
    static Map<String,Object> form(Map<String,Object> page,Map<String,String> arguments){
        var body=new LinkedHashMap<String,Object>(arguments);body.remove("csrf");var chosen=objects(page.get("forms")).stream().filter(f->f.get("operation").equals(body.get("operation"))).findFirst();require(chosen.isPresent(),"Choose a displayed operation");
        if(body.containsKey("checkout")){
            String checkout=Objects.toString(body.remove("checkout"));var answers=map();
            if(checkout.equals("guide") && "order".equals(body.get("operation"))){
                for(String name:new ArrayList<>(body.keySet()))if(name.startsWith("version.")){
                    String item=EnterpriseData.id(name.substring(8));String prefix="answer."+item+".";var values=map();
                    for(String key:new ArrayList<>(body.keySet()))if(key.startsWith(prefix))values.put(key.substring(prefix.length()),body.remove(key));
                    answers.put(item,map("revision",Service.number(body.remove(name).toString(),1,Integer.MAX_VALUE),"answers",values));
                }
                if(Objects.toString(body.get("employee_id"),"").isBlank())body.remove("employee_id");else body.put("operation","order_for");
            }else if(checkout.equals("conversation") && "confirm_order".equals(body.get("operation"))){
                for(String key:new ArrayList<>(body.keySet()))if(key.startsWith("answer."))answers.put(key.substring(7),body.remove(key));
            }else throw new Failure(400,"Choose a reviewed checkout");
            body.put("manifest",JSON.writeValueAsString(answers));
        }
        if(body.containsKey("designer_rows")) DesignInputs.read(page, body);
        for(var field:objects(chosen.get().get("fields"))){String name=field.get("name").toString();if(!body.containsKey(name))continue;String value=body.get(name).toString();switch(field.get("type").toString()){
            case "number" -> body.put(name,Service.number(value,0,Integer.MAX_VALUE));
            case "boolean" -> {require(Set.of("true","false").contains(value),"Choose yes or no");body.put(name,Boolean.valueOf(value));}
            case "json" -> {try{body.put(name,JSON.readValue(value,Object.class));}catch(tools.jackson.core.JacksonException invalid){throw new Failure(400,"Use valid structured field values");}}
            default -> { /* Text stays literal. */ }
        }}return body;
    }
    static void deleting(Store.Db db,String id)throws java.sql.SQLException {
        if(db.count("SELECT COUNT(*) FROM os_enterprise_document WHERE subject=? AND area IN ('hr','playbook','threat-feed','feed-link','employment-change','portfolio')",id)>0)throw new Failure(409,"Retain records with enterprise lifecycle or planning history");
    }
}
