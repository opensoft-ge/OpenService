package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.EnterpriseData.*;
import java.util.*;
import java.time.LocalDate;
import java.math.BigDecimal;

/** Capacity reservations and exact same-currency project forecasts. */
final class PortfolioResources {
    private PortfolioResources() {}
    static Map<String,Object> page(Store.Db db,String id)throws Exception {
        var record=ProjectDelivery.record(db,id);var row=get(db,"portfolio",id);var data=object(row.get("definition"));BigDecimal actual=BigDecimal.ZERO;for(var item:objects(data.getOrDefault("actuals",List.of())))actual=actual.add(money(item.get("amount")));BigDecimal forecast=actual.add(money(data.getOrDefault("remaining","0")));
        return EnterpriseData.page("Project resources and financial forecast","Reservations enforce each account's 100% capacity across overlapping project dates. Forecast equals recorded actual costs plus your remaining estimate; currencies are never converted.",map("record",FieldAccess.serialize(db,record),"plan",row,"actual",actual.toPlainString(),"forecast",forecast.toPlainString(),"variance",money(data.getOrDefault("budget","0")).subtract(forecast).toPlainString()),
            form("calendar","Set resource calendar and rate",field("user_id","Resource account ID","text",""),field("revision","Current revision","number",0),field("week","Available minutes Monday through Sunday","json","[480,480,480,480,480,0,0]"),field("exceptions","Date overrides (YYYY-MM-DD to minutes)","json","{}"),field("currency","Currency","text","USD"),field("hourly_rate","Hourly labor rate","text","0.00")),
            form("save_scenario","Save staffed investment comparison",field("key","New scenario ID","text",Values.id()),field("title","Scenario name","text",""),field("currency","Currency","text","USD"),field("ceiling","Total available funding","text","0"),field("start_on","First day","date",""),field("finish_on","Last day","date",""),field("option_a","Option A project IDs","json","[]"),field("option_b","Option B project IDs","json","[]")),
            form("open_scenario","Reopen saved comparison",field("key","Scenario ID","text","")),
            form("compare","Compare investment scenarios",field("option_a","Option A project IDs","json","[]"),field("option_b","Option B project IDs","json","[]"),field("currency","Planning currency","text","USD"),field("ceiling","Available funding","text","0.00")),
            form("budget","Set project budget and estimate",field("revision","Plan revision","number",row.get("revision")),field("currency","Currency code","text",data.getOrDefault("currency","USD")),field("budget","Approved budget","text",data.getOrDefault("budget","0.00")),field("remaining","Estimated remaining cost","text",data.getOrDefault("remaining","0.00"))),
            form("reserve","Reserve project capacity",field("revision","Plan revision","number",row.get("revision")),field("key","Reservation key","text",Values.id()),field("user_id","Account ID","text",""),field("start_on","First day","text",""),field("finish_on","Last day (inclusive)","text",""),field("percent","Capacity percentage","number",50)),
            form("release","Release capacity reservation",field("revision","Plan revision","number",row.get("revision")),field("key","Reservation key","text","")),
            form("actual","Record actual cost",field("revision","Plan revision","number",row.get("revision")),field("key","Unique cost receipt key","text",Values.id()),field("amount","Cost amount","text","0.00"),field("note","Cost description","text","")));
    }
    static void capacity(Store.Db db,Map<String,Object> proposed)throws Exception {
        var events=new TreeMap<LocalDate,Integer>();var all=new ArrayList<Map<String,Object>>();for(var row:list(db,"portfolio"))all.addAll(objects(object(row.get("definition")).getOrDefault("reservations",List.of())));all.add(proposed);
        for(var r:all)if(r.get("user_id").equals(proposed.get("user_id"))){int percent=((Number)r.get("percent")).intValue();events.merge(LocalDate.parse(r.get("start_on").toString()),percent,Integer::sum);events.merge(LocalDate.parse(r.get("finish_on").toString()).plusDays(1),-percent,Integer::sum);}
        int used=0;for(int delta:events.values()){used+=delta;require(used<=100,"Account capacity exceeds 100% on overlapping dates");}
    }
    static void guard(Store.Db db,Map<String,Object> project)throws java.sql.SQLException {
        var reservations=objects(object(get(db,"portfolio",project.get("sys_id").toString()).get("definition")).getOrDefault("reservations",List.of()));
        require(!Set.of("complete","cancelled").contains(object(project.get("data")).get("state")) || reservations.isEmpty(),"Release capacity reservations before closing the project");
        for(var reservation:reservations){var dates=object(project.get("data"));require(reservation.get("start_on").toString().compareTo(ProjectDelivery.date(dates.get("start_on")))>=0 && reservation.get("finish_on").toString().compareTo(ProjectDelivery.date(dates.get("finish_on")))<=0,"Release reservations before shortening the project window");}
    }
    static void command(Store.Db db,String id,Object input)throws Exception {
        lock(db);var record=ProjectDelivery.record(db,id);if(!ProjectDelivery.writable(db))throw new Failure(403,"Project planning write access required");require(Set.of("planning","active").contains(object(record.get("data")).get("state")),"Reopen project planning before changing resources");var body=object(input);String op=WorkJobs.text(body.get("operation"),10);var row=get(db,"portfolio",id);ProblemInvestigations.revision(body.get("revision"),row.get("revision"));var data=object(row.get("definition"));
        if(op.equals("budget")){keys(body,"operation","revision","currency","budget","remaining");String currency=WorkJobs.text(body.get("currency"),3);require(currency.matches("[A-Z]{3}"),"Use a three-letter currency code");require(objects(data.getOrDefault("actuals",List.of())).isEmpty() || currency.equals(data.get("currency")),"Currency is fixed after recording actual costs");data.putAll(map("currency",currency,"budget",money(body.get("budget")).toPlainString(),"remaining",money(body.get("remaining")).toPlainString()));}
        else if(op.equals("actual")){keys(body,"operation","revision","key","amount","note");require(data.containsKey("currency"),"Set the project currency and budget first");var actuals=objects(data.getOrDefault("actuals",new ArrayList<>()));String key=EnterpriseData.id(body.get("key"));require(actuals.size()<200 && actuals.stream().noneMatch(x->x.get("key").equals(key)),"Cost key already recorded or receipt capacity reached");actuals.add(map("key",key,"amount",money(body.get("amount")).toPlainString(),"note",WorkJobs.text(body.get("note"),1000),"actor",db.viewerId,"created",now()));data.put("actuals",actuals);}
        else{
            require(Set.of("reserve","release").contains(op),"Choose budget, cost or capacity operation");keys(body,op.equals("reserve")?new String[]{"operation","revision","key","user_id","start_on","finish_on","percent"}:new String[]{"operation","revision","key"});var reservations=objects(data.getOrDefault("reservations",new ArrayList<>()));String key=EnterpriseData.id(body.get("key"));
            if(op.equals("release"))require(reservations.removeIf(x->x.get("key").equals(key)),"Reservation not found");else{require(reservations.size()<100 && reservations.stream().noneMatch(x->x.get("key").equals(key)),"Reservation key exists or capacity reached");String user=EnterpriseData.id(body.get("user_id"));require(db.count("SELECT COUNT(*) FROM os_user WHERE user_id=? AND active=1",user)==1,"Choose an active account");String start=ProjectDelivery.date(body.get("start_on")),end=ProjectDelivery.date(body.get("finish_on"));require(start.compareTo(end)<=0 && end.compareTo("9999-12-30")<=0,"Use a valid finite reservation interval");var dates=object(record.get("data"));require(start.compareTo(ProjectDelivery.date(dates.get("start_on")))>=0 && end.compareTo(ProjectDelivery.date(dates.get("finish_on")))<=0,"Reservation must fit the project window");var reservation=map("key",key,"user_id",user,"start_on",start,"finish_on",end,"percent",integer(body.get("percent"),1,100));capacity(db,reservation);reservations.add(reservation);}data.put("reservations",reservations);
        }save(db,"portfolio",id,row.get("revision"),data);
    }
}
