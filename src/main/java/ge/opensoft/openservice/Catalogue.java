package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;

/** Original single-offering intake; orders snapshot text and never grant record access. */
final class Catalogue {
    private Catalogue() {}

    static List<Map<String,Object>> list(Store.Db db,boolean administration) throws SQLException {
        var rows=administration?db.all("SELECT * FROM os_offering ORDER BY name,offering_id LIMIT 200"):db.all("SELECT * FROM os_offering WHERE active=1 AND "+CatalogueEligibility.filter()+" ORDER BY name,offering_id LIMIT 200",db.viewerId);
        for(var row:rows)CatalogueQuestions.decode(row);return rows;
    }

    static Map<String,Object> find(Store.Db db,String id,boolean administration) throws SQLException {
        var row=administration?db.one("SELECT * FROM os_offering WHERE offering_id=?",id):db.one("SELECT * FROM os_offering WHERE offering_id=? AND active=1 AND "+CatalogueEligibility.filter(),id,db.viewerId);
        if(row==null)throw new Failure(404,"Offering not found");
        return CatalogueQuestions.decode(row);
    }

    static Map<String,Object> save(Store.Db db,String actor,String id,Object input) throws SQLException {
        Service.requireAccountAdministrator(db,actor);
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        var body=object(input);
        require(body.keySet().equals(Set.of("name","description","active","revision")),"Provide name, description, active and revision");
        String name=WorkJobs.text(body.get("name"),160),description=WorkJobs.text(body.get("description"),4000);
        require(body.get("active") instanceof Boolean,"Active must be boolean");
        require(body.get("revision") instanceof Integer revision && revision>=0,"Use a nonnegative integer revision");
        int revision=(Integer)body.get("revision"),active=body.get("active").equals(true)?1:0;
        if(id.isEmpty()){
            require(revision==0,"New offerings use revision zero");
            require(db.count("SELECT COUNT(*) FROM os_offering")<200,"Catalogue offering limit reached");
            id=id();db.update("INSERT INTO os_offering(offering_id,name,description,active,revision) VALUES(?,?,?,?,1)",id,name,description,active);
        }else{
            find(db,id,true);
            if(db.update("UPDATE os_offering SET name=?,description=?,active=?,revision=revision+1 WHERE offering_id=? AND revision=?",name,description,active,id,revision)!=1)throw new Failure(409,"Offering changed; reload before saving");
        }
        SecurityEvents.record(db,actor,actor,"catalogue.saved",map("offering_id",id));
        return find(db,id,true);
    }

    static Map<String,Object> order(Store.Db db,String id,Object input) throws SQLException {
        if(!Set.of("admin","agent","requester").contains(db.viewerRole))throw new Failure(403,"Request creation permission required");
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        var body=object(input);require(body.keySet().containsAll(Set.of("revision","details","key")) && Set.of("revision","details","key","answers").containsAll(body.keySet()),"Provide revision, details, key and optional answers");
        var answers=CatalogueQuestions.input(body.getOrDefault("answers",map()));
        require(body.get("revision") instanceof Integer n && n>0,"Use a positive integer revision");
        require(body.get("details") instanceof String,"Request details must be text");String details=(String)body.get("details");
        require(details.length()<=2000 && details.indexOf('\0')<0,"Use at most 2000 detail characters without NUL");
        String key=WorkJobs.text(body.get("key"),32);require(key.matches("[0-9a-f]{32}"),"Use a 32-character order key");
        var intended=map("offering_id",id,"revision",body.get("revision"),"details",details);if(!answers.isEmpty())intended.put("answers",answers);
        String fingerprint=DigestUtils.sha256Hex(JSON.writeValueAsString(intended));
        var previous=db.one("SELECT request_id,fingerprint FROM os_catalog_order WHERE user_id=? AND order_key=?",db.viewerId,key);
        if(previous!=null){if(!fingerprint.equals(previous.get("fingerprint")))throw new Failure(409,"Order key already identifies different work");return Service.record(db,"sc_request",previous.get("request_id").toString());}
        var offering=find(db,id,false);
        if(!offering.get("revision").equals(body.get("revision")))throw new Failure(409,"Offering changed; review it again");
        var submitted=CatalogueQuestions.snapshot(db,offering,answers);
        var item=Requests.create(db,Service.account(db,db.viewerId).get("name").toString(),map("short_description",offering.get("name"),"description",offering.get("description")+"\n\nRequest details:\n"+details+CatalogueQuestions.description(submitted)));
        CatalogueRouting.create(db,offering,item);
        db.update("INSERT INTO os_catalog_order(user_id,order_key,offering_id,offering_revision,request_id,fingerprint,answers) VALUES(?,?,?,?,?,?,?)",db.viewerId,key,id,body.get("revision"),item.get("sys_id"),fingerprint,JSON.writeValueAsString(submitted));
        SecurityEvents.record(db,db.viewerId,db.viewerId,"catalogue.ordered",map("offering_id",id,"request_id",item.get("sys_id"),"revision",body.get("revision")));
        return item;
    }
}
