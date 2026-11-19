package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
import java.time.LocalDate;

final class SharedIntelligence {
    private SharedIntelligence() {}

    static void replace(Store.Db db,String id,String source,Object payload) throws Exception {
        var row=get(db,"shared-feed",id);
        var previous=object(object(row.get("definition")).getOrDefault("indicators",map()));
        var active=new TreeMap<String,Object>();
        for(Object value:batch(payload,100)) {
            var item=object(value);
            keys(item,"sha256","label","expires_on");
            String digest=ThreatIndicators.digest(item.get("sha256"));
            var indicator=map("label",WorkJobs.text(item.get("label"),160),"expires_on",ProjectDelivery.date(item.get("expires_on")));
            require(active.put(digest,indicator)==null,"Report each indicator once");
        }
        var withdrawn=new LinkedHashMap<>(object(object(row.get("definition")).getOrDefault("withdrawn",map())));
        withdrawn.keySet().removeAll(active.keySet());
        for(var prior:previous.entrySet()) {
            if(active.containsKey(prior.getKey())) continue;
            var tombstone=object(prior.getValue());
            tombstone.put("withdrawn_at",now());
            withdrawn.put(prior.getKey(),tombstone);
        }
        while(withdrawn.size()>100) withdrawn.remove(withdrawn.keySet().iterator().next());
        save(db,"shared-feed",id,row.get("revision"),map("source",source,"received",now(),"indicators",active,"withdrawn",withdrawn));
    }

    static Object command(Store.Db db,String incident,Map<String,Object> body) throws Exception {
        lock(db);
        var record=WorkTasks.record(db,"security_incident",incident);
        String op=body.get("operation").toString();
        var row=get(db,"feed-link",incident);
        var link=object(row.get("definition"));
        if(op.equals("link_feed")) {
            Service.requireAccountAdministrator(db,db.viewerId);
            keys(body,"operation","feed","revision");
            String feed=EnterpriseData.id(body.get("feed"));
            require(!object(get(db,"shared-feed",feed).get("definition")).isEmpty(),"Import the feed before linking it");
            save(db,"feed-link",incident,body.get("revision"),map("feed",feed,"observations",link.getOrDefault("observations",List.of())));
            return map("feed",feed);
        }
        require(link.containsKey("feed"),"Link a shared feed to this case");
        if(op.equals("observe")) {
            require(SecurityIncidents.writable(db),"Security write access required");
            require(!"closed".equals(object(record.get("data")).get("state")),"Observations require an open incident");
            keys(body,"operation","sha256","revision");
            var hashes=new TreeSet<String>();
            for(Object v:(List<?>)link.get("observations")) hashes.add(v.toString());
            hashes.add(ThreatIndicators.digest(body.get("sha256")));
            require(hashes.size()<=100,"At most 100 observations per incident");
            link.put("observations",hashes);
            save(db,"feed-link",incident,body.get("revision"),link);
        } else keys(body,"operation");
        var feed=get(db,"shared-feed",link.get("feed").toString());
        var data=object(feed.get("definition"));
        var hits=new ArrayList<Map<String,Object>>();
        for(var entry:list(db,"feed-link")) {
            var candidate=object(entry.get("definition"));
            if(!candidate.get("feed").equals(link.get("feed"))) continue;
            String id=entry.get("subject").toString();
            try {WorkTasks.record(db,"security_incident",id);} catch(Failure denied){continue;}
            for(Object hash:(List<?>)candidate.get("observations")) {
                var current=object(data.get("indicators"));
                var withdrawn=object(data.get("withdrawn"));
                String status="not_listed";
                if(current.containsKey(hash)) {
                    status=LocalDate.parse(object(current.get(hash)).get("expires_on").toString()).isBefore(LocalDate.now())?"expired":"active";
                } else if(withdrawn.containsKey(hash)) status="withdrawn";
                hits.add(map("incident",id,"sha256",hash,"status",status,"indicator",current.getOrDefault(hash,withdrawn.get(hash))));
            }
        }
        return map("source",data.get("source"),"feed_revision",feed.get("revision"),"matches",hits);
    }
}
