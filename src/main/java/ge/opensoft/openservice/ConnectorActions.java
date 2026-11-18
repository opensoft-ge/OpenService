package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;

/** Native configured JSON actions; credentials remain in the existing connection alias. */
final class ConnectorActions {
    private ConnectorActions() {}
    static List<Map<String,Object>> list(Store.Db db) throws Exception {
        Service.requireAccountAdministrator(db,db.viewerId);return db.all("SELECT action_id,name,alias,method,path,active,revision FROM os_connector_action ORDER BY name,action_id LIMIT 200");
    }
    static Map<String,Object> find(Store.Db db,String id) throws Exception {
        Service.requireAccountAdministrator(db,db.viewerId);var row=db.one("SELECT * FROM os_connector_action WHERE action_id=?",id);if(row==null)throw new Failure(404,"Connector action not found");row.put("inputs",JSON.readValue(row.get("inputs").toString(),Object.class));return row;
    }
    static Map<String,Object> save(Store.Db db,String id,Object input) throws Exception {
        Service.requireAccountAdministrator(db,db.viewerId);var body=object(input);require(body.keySet().equals(Set.of("name","alias","method","path","inputs","active","revision")),"Provide name, alias, method, path, inputs, active and revision");
        String name=WorkJobs.text(body.get("name"),160),alias=WorkJobs.text(body.get("alias"),80),method=WorkJobs.text(body.get("method"),10),path=Connectors.path(body.get("path"));
        require(Set.of("POST","PUT","PATCH","DELETE").contains(method),"Choose POST, PUT, PATCH or DELETE");
        ConnectorConsole.page(db,alias);var fields=CatalogueQuestions.validate(body.get("inputs"));require(fields.stream().noneMatch(field->field.containsKey("when")),"Conditional inputs are supported only by catalogue intake");require(body.get("active") instanceof Boolean,"Active must be boolean");require(body.get("revision") instanceof Integer n && n>=0,"Use a nonnegative action revision");int active=Boolean.TRUE.equals(body.get("active"))?1:0;
        if(id.isEmpty()){
            require(body.get("revision").equals(0),"New actions use revision zero");require(db.count("SELECT COUNT(*) FROM os_connector_action")<200,"Connector action limit reached");id=id();db.update("INSERT INTO os_connector_action(action_id,name,alias,method,path,inputs,active,revision) VALUES(?,?,?,?,?,?,?,1)",id,name,alias,method,path,JSON.writeValueAsString(fields),active);
        }else{
            find(db,id);if(db.update("UPDATE os_connector_action SET name=?,alias=?,method=?,path=?,inputs=?,active=?,revision=revision+1 WHERE action_id=? AND revision=?",name,alias,method,path,JSON.writeValueAsString(fields),active,id,body.get("revision"))!=1)throw new Failure(409,"Action changed; reload before saving");
        }
        SecurityEvents.record(db,db.viewerId,db.viewerId,"connector.action_saved",map("action_id",id));return find(db,id);
    }
    static Map<String,Object> proposal(Store store,Store.Db db,String id,Object input) throws Exception {
        var action=find(db,id);require(store.environment.getProperty("CONNECTOR_WRITES_ENABLED",Boolean.class,false),"Connector writes are disabled");require(action.get("active").equals(1),"Connector action is inactive");var body=object(input);require(body.keySet().equals(Set.of("values","key")),"Provide values and key");String key=WorkJobs.text(body.get("key"),80);var values=CatalogueQuestions.input(body.get("values"));CatalogueQuestions.snapshot(db,map("questions",action.get("inputs")),values);String content=JSON.writeValueAsString(values);require(content.length()<=8000,"Action input exceeds 8000 characters");
        var connector=ConnectorConsole.page(db,action.get("alias").toString());require(Arrays.asList(store.environment.getProperty("CONNECTOR_ALLOWED_ORIGINS","").split(",")).contains(connector.get("origin")),"Connector origin is no longer allowed");String origin=connector.get("origin").toString();
        String state=DigestUtils.sha256Hex("connector-write-v1:"+JSON.writeValueAsString(map("actor",db.viewerId,"authority",db.one("SELECT auth_revision FROM os_user WHERE user_id=?",db.viewerId),"action",action,"connector",connector,"values",values,"key",key)));
        return map("action",action,"connector",connector,"destination",origin+action.get("path"),"content",content,"values",values,"key",key,"state",state);
    }
    static Map<String,Object> preview(Store store,Store.Db db,String id,Object input) throws Exception {
        var result=proposal(store,db,id,input);result.putAll(AiConsent.issue(store,result.get("state").toString()));return result;
    }
    static Map<String,Object> validate(Store store,Store.Db db,Object input,boolean consent) throws Exception {
        var body=object(input);require(body.keySet().equals(Set.of("action_id","values","key","state","preview_token","confirm")),"Provide the exact reviewed connector action");String id=WorkJobs.text(body.get("action_id"),32);var current=proposal(store,db,id,map("values",body.get("values"),"key",body.get("key")));require(Boolean.TRUE.equals(body.get("confirm")),"Confirm sending the reviewed request");if(!current.get("state").equals(body.get("state")))throw new Failure(409,"Action, connection or authority changed; review again");if(consent)AiConsent.verify(store,body.get("preview_token"),current.get("state").toString());return current;
    }
    static Map<String,Object> queue(Store store,Store.Db db,String id,Object input) throws Exception {
        var body=new LinkedHashMap<>(object(input));require(!body.containsKey("action_id"),"Action is identified by its route");body.put("action_id",id);return WorkJobs.enqueue(store,db,db.viewerId,map("kind","connector.write","key",body.get("key"),"payload",body));
    }
}
