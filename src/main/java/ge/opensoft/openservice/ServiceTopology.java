package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
import java.time.Instant;

final class ServiceTopology {
    private ServiceTopology(){}
    static String identity(String source,String resource){return ConnectorSynchronization.hash(List.of(source,resource));}
    static Map<String,Object> observed(Object raw,String key){
        Map<String,Object> records=new TreeMap<>();
        for(Object item:batch(raw,200)){
            var row=object(item);
            String name=text(row,"id",200);
            if(key.equals("depends_on")){
                keys(row,"id","title","type",key);
                text(row,"type",80);
            }else keys(row,"id","title",key);
            text(row,"title",160);
            var edges=new TreeSet<String>();
            for(Object dependency:batch(row.get(key),100))edges.add(WorkJobs.text(dependency,200));
            var normalized=new TreeMap<>(row);
            normalized.put(key,List.copyOf(edges));
            require(records.put(name,normalized)==null,"Duplicate inventory identifier");
        }
        return records;
    }
    static Object inventory(Store.Db db,String source,Map<String,Object> b)throws Exception{
        var row=get(db,"service-map",source);
        var last=object(row.get("definition"));
        int generation=integer(b.get("sequence"),1,Integer.MAX_VALUE);
        var resources=observed(b.get("resources"),"depends_on");
        var services=observed(b.get("services"),"roots");
        String digest=ConnectorSynchronization.hash(List.of(resources,services));
        if(Objects.equals(last.get("sequence"),generation)){
            require(digest.equals(last.get("digest")),"Sequence already refers to another snapshot");
            return row;
        }
        require(generation>(int)last.getOrDefault("sequence",0),"Old inventory snapshot");
        for(var item:resources.values())require(resources.keySet().containsAll((List<?>)object(item).get("depends_on")),"Dependencies must be present in the snapshot");
        for(var item:services.values())require(resources.keySet().containsAll((List<?>)object(item).get("roots")),"Service roots must be present in the snapshot");
        for(var item:resources.entrySet()){
            var resource=object(item.getValue());
            String key=identity(source,item.getKey());
            var registration=get(db,"discovered-ci",key);
            var mapping=object(registration.get("definition"));
            if(mapping.isEmpty()){
                var ci=Service.create(db,"cmdb_ci",map("short_description",resource.get("title"),"description","Discovered "+resource.get("type")+" from "+source),actor(db));
                mapping.put("ci_id",ci.get("sys_id"));
                save(db,"discovered-ci",key,0,mapping);
            }
            Service.readRecord(db,"cmdb_ci",mapping.get("ci_id").toString());
            resource.put("ci_id",mapping.get("ci_id"));
        }
        save(db,"service-map",source,row.get("revision"),map("source",source,"sequence",generation,"digest",digest,"resources",resources,"services",services,"observed",now()));
        return get(db,"service-map",source);
    }
    static List<String> affected(Map<String,Object> inventory,String target){
        var resources=object(inventory.get("resources"));
        var result=new ArrayList<String>();
        for(var item:object(inventory.get("services")).entrySet()){
            var todo=new ArrayDeque<Object>((List<?>)object(item.getValue()).get("roots"));
            var seen=new HashSet<>();
            while(!todo.isEmpty()){
                Object id=todo.removeFirst();
                if(!seen.add(id))continue;
                if(id.equals(target)){result.add(item.getKey());break;}
                for(Object dep:(List<?>)object(resources.get(id)).get("depends_on")) todo.add(dep);
            }
        }
        return result;
    }
    static String impactDescription(List<String> services){
        String description="Affected services: "+String.join(", ",services);
        return description.substring(0,Math.min(4000,description.length()));
    }
    static Object command(Store.Db db,String op,Map<String,Object> b)throws Exception{
        admin(db);
        String source=text(b,"source",80);
        if(op.equals("inventory"))return inventory(db,source,b);
        String resource=text(b,"resource_id",200),key=identity(source,resource);
        var snapshot=definition(db,"service-map",source);
        var resources=object(snapshot.get("resources"));
        if(op.equals("recover"))return recover(db,key,b);
        if(op.equals("declare")){
            String episode=text(definition(db,"monitor-state",key),"episode",32);
            var row=get(db,"service-episode",episode);
            var detail=object(row.get("definition"));
            String incident=detail.get("incident").toString(),note=text(b,"note",2000);
            var impact=object(detail.get("impact"));
            String description=impactDescription(((List<?>)impact.get("services")).stream().map(Object::toString).toList());
            major(db,incident,"propose",map("reason",note,"impact",description));
            major(db,incident,"accept",map("reason",note,"impact",description,"commander",text(b,"commander",80)));
            major(db,incident,"publish",map("message",note));
            detail.put("commander",MajorIncidents.major(db,incident).get("commander_id"));
            save(db,"service-episode",episode,b.get("revision"),detail);
            return get(db,"service-episode",episode);
        }
        var current=get(db,"monitor-state",key);
        var state=object(current.get("definition"));
        Map<String,Object> ci;
        if(resources.containsKey(resource))ci=object(resources.get(resource));
        else{
            require(op.equals("signals") && state.containsKey("episode"),"Resource absent from current snapshot");
            ci=object(object(definition(db,"service-episode",state.get("episode").toString()).get("impact")).get("resource"));
        }
        if(op.equals("overlay")){
            save(db,"ci-overlay",key,b.get("revision"),map("title",text(b,"title",160),"updated_by",db.viewerId));
        }
        var impact=map("resource",ci,"manual",get(db,"ci-overlay",key),"services",affected(snapshot,resource),"sequence",snapshot.get("sequence"));
        if(!op.equals("signals"))return impact;
        boolean changed=false;
        var monitors=object(state.getOrDefault("monitors",map()));
        for(Object entry:batch(b.get("alerts"),64)){
            var event=object(entry);
            keys(event,"monitor","id","observed_at","state");
            String monitor=text(event,"monitor",80),eventId=text(event,"id",120);
            long at=ServiceOutages.time(event.get("observed_at"),System.currentTimeMillis());
            require(Set.of("down","up").contains(event.get("state")),"Choose up or down");
            var old=object(monitors.getOrDefault(monitor,map()));
            String receipt=ConnectorSynchronization.hash(List.of(key,monitor,eventId));
            var prior=object(get(db,"signal-receipt",receipt).get("definition"));
            if(!prior.isEmpty()){require(prior.equals(event),"Event identity conflict");continue;}
            require(old.isEmpty() || at>Instant.parse(old.get("observed_at").toString()).toEpochMilli(),"Stale monitor observation");
            changed=true;
            save(db,"signal-receipt",receipt,0,event);
            monitors.put(monitor,event);
            boolean down=monitors.values().stream().anyMatch(v->"down".equals(object(v).get("state")));
            if(down && !state.containsKey("episode")){
                String episode=Values.id();
                var incident=Service.create(db,"incident",map("short_description",ci.get("title"),"description",impactDescription(affected(snapshot,resource))),actor(db));
                String nativeCi=ci.get("ci_id").toString();
                var outage=ServiceOutages.save(db,nativeCi,map("operation","create","record_revision",Service.readRecord(db,"cmdb_ci",nativeCi).get("revision"),"kind","outage","begin",event.get("observed_at"),"end","","summary",ci.get("title"),"evidence",eventId,"request_id",episode),System.currentTimeMillis());
                save(db,"service-episode",episode,0,map("source",source,"resource",resource,"ci",nativeCi,"outage",outage.get("outage_id"),"incident",incident.get("sys_id"),"impact",impact,"began",at,"state","open"));
                state.put("episode",episode);
            }
            state.put("all_up",!down);
        }
        state.put("monitors",monitors);
        if(changed)save(db,"monitor-state",key,current.get("revision"),state);
        return get(db,"monitor-state",key);
    }
    static void major(Store.Db db,String incident,String op,Map<String,Object> fields)throws Exception{
        fields.putAll(map("operation",op,"revision",MajorIncidents.major(db,incident).get("revision"),"record_revision",WorkTasks.record(db,"incident",incident).get("revision")));
        MajorIncidents.command(db,incident,fields);
    }
    static Object recover(Store.Db db,String key,Map<String,Object> b)throws Exception{
        var current=get(db,"monitor-state",key);
        var state=object(current.get("definition"));
        require(Boolean.TRUE.equals(state.get("all_up")) && state.containsKey("episode"),"Wait for all monitors to recover");
        String episode=state.get("episode").toString();
        var row=get(db,"service-episode",episode);
        var detail=object(row.get("definition"));
        String change=id(b,"change_id"),note=text(b,"note",2000);
        var changeRecord=Service.readRecord(db,"change_request",change);
        require(Set.of("approved","implement","closed").contains(object(changeRecord.get("data")).get("state")),"Select an approved remediation change");
        String ci=detail.get("ci").toString();
        String outage=detail.get("outage").toString();
        long restored=object(state.get("monitors")).values().stream().mapToLong(v->Instant.parse(object(v).get("observed_at").toString()).toEpochMilli()).max().orElseThrow();
        ServiceOutages.save(db,ci,map("operation","close","record_revision",Service.readRecord(db,"cmdb_ci",ci).get("revision"),
                "outage_id",outage,"revision",db.one("SELECT revision FROM os_ci_outage WHERE outage_id=?",outage).get("revision"),"end",Instant.ofEpochMilli(restored).toString(),"evidence",note),System.currentTimeMillis());
        String incident=detail.get("incident").toString();
        if("declared".equals(MajorIncidents.major(db,incident).get("status")))major(db,incident,"restore",map("outcome",note));
        else Service.audit(db,WorkTasks.record(db,"incident",incident),actor(db),"monitoring.restored",map("change",change,"review",note));
        detail.putAll(map("state","recovered","review",note,"change",change,"change_state",object(changeRecord.get("data")).get("state"),"recovered_at",restored,"reviewer",db.viewerId));
        save(db,"service-episode",episode,row.get("revision"),detail);
        state.remove("episode");
        save(db,"monitor-state",key,b.get("revision"),state);
        return get(db,"service-episode",episode);
    }
}
