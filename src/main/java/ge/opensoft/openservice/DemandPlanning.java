package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;

/** Native transparent demand scoring, authorized portfolio comparison and retained decisions. */
final class DemandPlanning {
    private DemandPlanning() {}
    static Map<String,Object> record(Store.Db db,String id)throws SQLException {
        var item=WorkTasks.record(db,"demand",id);if(!FieldAccess.allowed(db,Service.schema(db,"demand"),"score"))throw new Failure(403,"Demand score access required");return item;
    }
    static Map<String,Object> latest(Store.Db db,String id)throws SQLException {
        var row=db.one("SELECT revision,portfolio_id,planning_year,score,detail FROM os_demand_assessment WHERE record_id=? ORDER BY revision DESC LIMIT 1",id);return row==null?map("revision",0,"detail",map()):row;
    }
    static Map<String,Object> basis(Store.Db db,Map<String,Object> item)throws SQLException {
        var data=new TreeMap<>(object(item.get("data")));data.remove("state");return map("data",data,"schema_version",Approvals.version(Service.schema(db,"demand")));
    }
    static boolean current(Store.Db db,Map<String,Object> item,Map<String,Object> assessment)throws SQLException {
        return ((Number)assessment.get("revision")).intValue()>0 && object(object(assessment.get("detail")).get("basis")).equals(basis(db,item));
    }
    static boolean writable(Store.Db db)throws SQLException {
        var schema=Service.schema(db,"demand");if(!RecordPolicy.allows(schema,"update",db.viewerRole))return false;for(String field:List.of("description","score","state")){var rule=object(TypedFields.rules(schema).getOrDefault(field,map()));if(rule.containsKey("write_roles") && !((List<?>)rule.get("write_roles")).contains(db.viewerRole))return false;}return true;
    }
    static int year(Object input){require(input instanceof Integer n && n>=2000 && n<=2200,"Use a planning year from 2000 to 2200");return (Integer)input;}
    static Map<String,Object> portfolio(Store.Db db,String id)throws SQLException {
        var item=WorkTasks.record(db,"portfolio",id);if(!object(item.get("data")).get("state").equals("active"))throw new Failure(409,"Use an active portfolio for planning");return item;
    }
    static Map<String,Object> projection(Store.Db db,Map<String,Object> assessment)throws SQLException {
        var result=new LinkedHashMap<>(assessment);var detail=new LinkedHashMap<>(object(result.get("detail")));detail.remove("basis");result.put("detail",detail);
        try{var portfolio=FieldAccess.serialize(db,Service.readRecord(db,"portfolio",result.get("portfolio_id").toString()));result.put("portfolio_label",portfolio.getOrDefault("short_description","Portfolio"));}catch(Failure inaccessible){result.put("portfolio_id","");result.put("portfolio_label","Restricted or unavailable portfolio");}return result;
    }
    static Map<String,Object> page(Store.Db db,String id)throws SQLException {
        var item=record(db,id);var assessment=latest(db,id);var history=new ArrayList<Map<String,Object>>();for(var row:db.all("SELECT revision,portfolio_id,planning_year,score,detail FROM os_demand_assessment WHERE record_id=? ORDER BY revision DESC",id))history.add(projection(db,row));var portfolios=RecordPolicy.allows(Service.schema(db,"portfolio"),"read",db.viewerRole)?Service.page(db,"portfolio",Map.of("sysparm_limit","100","sysparm_query","state=active"),"").rows():List.of();String state=object(item.get("data")).get("state").toString();return map("delivery",ProjectDelivery.originView(db,item),"record",FieldAccess.serialize(db,item),"assessment",history.isEmpty()?map():history.getFirst(),"inputs",history.isEmpty()?map():history.getFirst().get("detail"),"revision",assessment.get("revision"),"current",current(db,item,assessment),"history",history,"portfolios",portfolios,"can_edit",Set.of("draft","screen").contains(state) && writable(db),"can_decide",state.equals("qualified") && writable(db),"year",java.time.Year.now(java.time.ZoneOffset.UTC).getValue());
    }
    static Map<String,Object> ready(Store.Db db,Map<String,Object> item)throws SQLException {
        var assessment=latest(db,item.get("sys_id").toString());if(!current(db,item,assessment))throw new Failure(409,"Record a current demand assessment before advancing");portfolio(db,assessment.get("portfolio_id").toString());return assessment;
    }
    static Map<String,Object> save(Store.Db db,String id,Object input)throws SQLException {
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");var item=record(db,id);if(!writable(db))throw new Failure(403,"Demand planning write access required");var body=object(input);String operation=WorkJobs.text(body.get("operation"),8);require(Set.of("assess","decide").contains(operation),"Choose assess or decide");var keys=new HashSet<>(Set.of("operation","revision","record_revision","rationale"));keys.addAll(operation.equals("assess")?Set.of("portfolio_id","year","value","alignment","urgency","effort"):Set.of("decision"));require(body.keySet().equals(keys),"Provide only the selected planning fields");var assessment=latest(db,id);ProblemInvestigations.revision(body.get("revision"),assessment.get("revision"));ProblemInvestigations.revision(body.get("record_revision"),item.get("revision"));int revision=((Number)assessment.get("revision")).intValue()+1;require(revision<=100,"A demand supports at most 100 retained planning versions");String state=object(item.get("data")).get("state").toString(),actor=Service.account(db,db.viewerId).get("name").toString(),reason=WorkJobs.text(body.get("rationale"),4000),portfolio;int year,score;Map<String,Object> detail;
        if(operation.equals("assess")){
            if(!Set.of("draft","screen").contains(state))throw new Failure(409,"Return to screening before reassessment");portfolio=WorkJobs.text(body.get("portfolio_id"),32);portfolio(db,portfolio);year=year(body.get("year"));require(db.count("SELECT COUNT(*) FROM os_demand_assessment a WHERE portfolio_id=? AND planning_year=? AND record_id<>? AND NOT EXISTS(SELECT 1 FROM os_demand_assessment n WHERE n.record_id=a.record_id AND n.revision>a.revision)",portfolio,year,id)<100,"A portfolio/year supports at most 100 currently assessed demands");detail=new LinkedHashMap<>();for(String field:List.of("value","alignment","urgency","effort")){Object value=body.get(field);require(value instanceof Integer n && n>=1 && n<=5,"Scoring inputs must be integers from 1 to 5");detail.put(field,value);}score=2*(4*(Integer)detail.get("value")+3*(Integer)detail.get("alignment")+2*(Integer)detail.get("urgency")+6-(Integer)detail.get("effort"));Service.update(db,"demand",item,map("score",Integer.toString(score)),actor,db.viewerRole,item.get("revision").toString());detail.putAll(map("basis",basis(db,item),"rationale",reason,"assessor_id",db.viewerId,"assessor",actor,"created",now(),"decision",map()));
        }else{
            if(!state.equals("qualified"))throw new Failure(409,"Qualify the demand before a decision");assessment=ready(db,item);String decision=WorkJobs.text(body.get("decision"),8);require(Set.of("approved","deferred","rejected").contains(decision),"Choose approved, deferred or rejected");detail=new LinkedHashMap<>(object(assessment.get("detail")));require(object(detail.get("decision")).isEmpty(),"Reassess before recording another decision");detail.put("decision",map("outcome",decision,"rationale",reason,"actor_id",db.viewerId,"actor",actor,"created",now(),"record_revision",item.get("revision")));portfolio=assessment.get("portfolio_id").toString();year=((Number)assessment.get("planning_year")).intValue();score=((Number)assessment.get("score")).intValue();
        }
        db.update("INSERT INTO os_demand_assessment(record_id,revision,portfolio_id,planning_year,score,detail) VALUES(?,?,?,?,?,?)",id,revision,portfolio,year,score,JSON.writeValueAsString(detail));if(operation.equals("decide"))Service.update(db,"demand",item,map("state",object(detail.get("decision")).get("outcome")),actor,db.viewerRole,item.get("revision").toString());Service.audit(db,item,actor,"demand."+operation,map("assessment_revision",revision,"planning_year",year,"score",score));return page(db,id);
    }
    static Map<String,Object> board(Store.Db db,String id,int year)throws SQLException {
        var portfolio=WorkTasks.record(db,"portfolio",id);RecordPolicy.read(db,Service.schema(db,"demand"));for(String field:List.of("description","state","score"))if(!FieldAccess.allowed(db,Service.schema(db,"demand"),field))throw new Failure(403,"Demand planning field access required");var parameters=new ArrayList<Object>(List.of(id,year(year)));String filter=RecordAccess.filter(db,"a.record_id",parameters);var rows=new ArrayList<Map<String,Object>>();int rank=0;
        for(var row:db.all("SELECT a.record_id,a.revision,a.portfolio_id,a.planning_year,a.score,a.detail FROM os_demand_assessment a WHERE a.portfolio_id=? AND a.planning_year=? AND NOT EXISTS(SELECT 1 FROM os_demand_assessment n WHERE n.record_id=a.record_id AND n.revision>a.revision) AND "+filter+" ORDER BY a.score DESC,a.record_id",parameters.toArray())){var item=record(db,row.get("record_id").toString());boolean current=current(db,item,row);boolean ranked=current && Set.of("draft","screen","qualified","approved").contains(object(item.get("data")).get("state"));var entry=map("record",FieldAccess.serialize(db,item),"assessment",projection(db,row),"current",current,"rank",ranked?++rank:0);rows.add(entry);}return map("portfolio",FieldAccess.serialize(db,portfolio),"year",year,"items",rows,"total",rows.size(),"can_create_demand",RecordPolicy.allows(Service.schema(db,"demand"),"create",db.viewerRole));
    }
    static void guard(Store.Db db,Map<String,Object> item)throws SQLException {
        if(!"demand".equals(item.get("table")))return;String state=object(item.get("data")).get("state").toString();if(Set.of("draft","screen").contains(state))return;var assessment=ready(db,item);var decision=object(object(assessment.get("detail")).get("decision"));if(state.equals("qualified")){if(!decision.isEmpty())throw new Failure(409,"Reassess before qualifying a decided demand");return;}var before=db.one("SELECT * FROM os_record WHERE sys_id=?",item.get("sys_id"));boolean entering=!object(before.get("data")).get("state").equals(state);if(!state.equals(decision.get("outcome")) || (entering && !before.get("revision").equals(decision.get("record_revision"))))throw new Failure(409,"Record a current demand decision before entering this state");if(state.equals("approved") && entering && ApprovalGate.qualifying(db,before,Service.schema(db,"demand"),state).isEmpty())throw new Failure(409,"A current independent demand approval is required");
    }
    static void insertion(Map<String,Object> item){if("demand".equals(item.get("table")) && !"draft".equals(object(item.get("data")).get("state")))throw new Failure(409,"Import demands in draft, then assess and review them");}
}
