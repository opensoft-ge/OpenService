package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;

/** Distinct native inventory custody; CI links never synchronize operational state. */
final class AssetCustody {
    private AssetCustody() {}
    static Map<String,Object> record(Store.Db db,String id)throws SQLException {
        var item=WorkTasks.record(db,"alm_asset",id);for(String field:List.of("asset_tag","model","serial_number"))if(!FieldAccess.allowed(db,Service.schema(db,"alm_asset"),field))throw new Failure(403,"Asset identity field access required");return item;
    }
    static Map<String,Object> latest(Store.Db db,String id)throws SQLException {
        var row=db.one("SELECT revision,state,stockroom_id,assignee_id,detail FROM os_asset_movement WHERE record_id=? ORDER BY revision DESC LIMIT 1",id);return row==null?map("revision",0,"state","registered","stockroom_id","","assignee_id","","detail",map()):row;
    }
    static boolean writable(Store.Db db)throws SQLException {
        var schema=Service.schema(db,"alm_asset");if(!RecordPolicy.allows(schema,"update",db.viewerRole))return false;var rule=object(TypedFields.rules(schema).getOrDefault("state",map()));return !rule.containsKey("write_roles") || ((List<?>)rule.get("write_roles")).contains(db.viewerRole);
    }
    static Map<String,Object> reference(Store.Db db,String table,String id)throws SQLException {
        if(id.isEmpty())return map();try{return FieldAccess.serialize(db,Service.readRecord(db,table,id));}catch(Failure unavailable){return map();}
    }
    static Map<String,Object> projection(Store.Db db,Map<String,Object> movement)throws SQLException {
        var copy=new LinkedHashMap<>(movement);String stock=copy.remove("stockroom_id").toString(),user=copy.remove("assignee_id").toString();copy.put("stockroom",reference(db,"stockroom",stock));var account=user.isEmpty()?null:db.one("SELECT name,active FROM os_user WHERE user_id=?",user);copy.put("assignee",account==null?map():account);return copy;
    }
    static Map<String,Object> page(Store.Db db,String id)throws SQLException {
        var item=record(db,id);var current=latest(db,id);var history=new ArrayList<Map<String,Object>>();for(var row:db.all("SELECT revision,state,stockroom_id,assignee_id,detail FROM os_asset_movement WHERE record_id=? ORDER BY revision DESC",id))history.add(projection(db,row));var identity=db.one("SELECT asset_tag,ci_id FROM os_asset_identity WHERE record_id=?",id);var stockrooms=RecordPolicy.allows(Service.schema(db,"stockroom"),"read",db.viewerRole)?Service.page(db,"stockroom",Map.of("sysparm_limit","100","sysparm_query","state=active"),"").rows():List.of();var cis=RecordPolicy.allows(Service.schema(db,"cmdb_ci"),"read",db.viewerRole)?Service.page(db,"cmdb_ci",Map.of("sysparm_limit","100"),"").rows():List.of();return map("record",FieldAccess.serialize(db,item),"movement",projection(db,current),"history",history,"ci",identity==null?map():reference(db,"cmdb_ci",identity.get("ci_id").toString()),"stockrooms",stockrooms,"cis",cis,"can_edit",writable(db) && !current.get("state").equals("retired"));
    }
    static void stockroom(Store.Db db,String id,boolean active)throws SQLException {
        var item=WorkTasks.record(db,"stockroom",id);if(active && !object(item.get("data")).get("state").equals("active"))throw new Failure(409,"Receive equipment into an active stockroom");
    }
    static Map<String,Object> save(Store.Db db,String id,Object input)throws SQLException {
        if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");var item=record(db,id);if(!writable(db))throw new Failure(403,"Asset custody write access required");var body=object(input);String operation=WorkJobs.text(body.get("operation"),8);require(Set.of("receive","issue","return","retire").contains(operation),"Choose a custody operation");var keys=new HashSet<>(Set.of("operation","revision","record_revision","reason"));switch(operation){case "receive"->keys.addAll(Set.of("stockroom_id","ci_id"));case "return"->keys.add("stockroom_id");case "issue"->keys.add("username");default->keys.add("disposition");}require(body.keySet().equals(keys),"Provide only the selected custody fields");var before=latest(db,id);ProblemInvestigations.revision(body.get("revision"),before.get("revision"));ProblemInvestigations.revision(body.get("record_revision"),item.get("revision"));int revision=((Number)before.get("revision")).intValue()+1;require(revision<=1000,"An asset supports at most 1000 retained custody movements");String reason=WorkJobs.text(body.get("reason"),4000),state=before.get("state").toString(),stock="",assignee="",disposition="";String target=switch(operation){case "receive","return"->"in_stock";case "issue"->"in_use";default->"retired";};String required=switch(operation){case "receive"->"registered";case "return"->"in_use";default->"in_stock";};if(!state.equals(required))throw new Failure(409,"This custody operation is unavailable in the current state");
        if(Set.of("issue","retire").contains(operation) && "held".equals(object(EnterpriseData.get(db,"guide-stock",id).get("definition")).get("state")))
            throw new Failure(409,"This asset is reserved for a guide order");
        if(operation.equals("receive") || operation.equals("return")){stock=WorkJobs.text(body.get("stockroom_id"),32);stockroom(db,stock,true);}else stockroom(db,before.get("stockroom_id").toString(),false);
        if(operation.equals("receive")){
            String tag=WorkJobs.text(object(item.get("data")).get("asset_tag"),80).toUpperCase(Locale.ROOT);require(tag.matches("[A-Z0-9][A-Z0-9._-]{0,79}"),"Use an alphanumeric asset tag with optional dot, underscore or hyphen");if(db.one("SELECT record_id FROM os_asset_identity WHERE asset_tag=?",tag)!=null)throw new Failure(409,"This asset tag is already reserved");Object value=body.get("ci_id");require(value instanceof String,"Choose a configuration item or leave it empty");String ci=(String)value;if(!ci.isEmpty()){Service.readRecord(db,"cmdb_ci",ci);if(db.count("SELECT COUNT(*) FROM os_asset_identity i WHERE i.ci_id=? AND NOT EXISTS(SELECT 1 FROM os_asset_movement m WHERE m.record_id=i.record_id AND m.state='retired')",ci)>0)throw new Failure(409,"This configuration item already has an active asset");}db.update("INSERT INTO os_asset_identity(record_id,asset_tag,ci_id) VALUES(?,?,?)",id,tag,ci);
        }else if(operation.equals("issue")){
            String name=WorkJobs.text(body.get("username"),80);var account=db.one("SELECT user_id FROM os_user WHERE name=? AND active=1",name);require(account!=null,"Choose an active local account for handover");assignee=account.get("user_id").toString();
        }else if(operation.equals("retire")){disposition=WorkJobs.text(body.get("disposition"),20);require(Set.of("disposed","sold","donated","vendor_return").contains(disposition),"Choose a retirement disposition");}
        String actor=Service.account(db,db.viewerId).get("name").toString();var data=object(item.get("data"));var detail=map("operation",operation,"reason",reason,"disposition",disposition,"actor",actor,"actor_id",db.viewerId,"created",now(),"record_revision",item.get("revision"),"asset_tag",data.get("asset_tag"),"model",data.get("model"),"serial_number",data.get("serial_number"));db.update("INSERT INTO os_asset_movement(record_id,revision,state,stockroom_id,assignee_id,detail) VALUES(?,?,?,?,?,?)",id,revision,target,stock,assignee,JSON.writeValueAsString(detail));Service.update(db,"alm_asset",item,map("state",target),actor,db.viewerRole,item.get("revision").toString());Service.audit(db,item,actor,"asset."+operation,map("movement_revision",revision));return page(db,id);
    }
    static void guard(Store.Db db,Map<String,Object> item)throws SQLException {
        if(!"alm_asset".equals(item.get("table")))return;String id=item.get("sys_id").toString();var data=object(item.get("data"));var movement=latest(db,id);if(!data.get("state").equals(movement.get("state")))throw new Failure(409,"Use the asset custody workflow to change state");var identity=db.one("SELECT asset_tag FROM os_asset_identity WHERE record_id=?",id);if(identity!=null){require(Objects.toString(data.get("asset_tag"),"").trim().toUpperCase(Locale.ROOT).equals(identity.get("asset_tag")),"Received asset tags cannot be changed");}
    }
    static void insertion(Map<String,Object> item){if("alm_asset".equals(item.get("table")) && !"registered".equals(object(item.get("data")).get("state")))throw new Failure(409,"Import assets as registered, then record physical receipt");}
    static void deleting(Store.Db db,String id)throws SQLException {
        if(db.one("SELECT record_id FROM os_asset_identity WHERE record_id=?",id)!=null)throw new Failure(409,"Retain assets with custody history; retire them instead");if(db.count("SELECT COUNT(*) FROM os_asset_movement m WHERE m.stockroom_id=? AND m.state='in_stock' AND NOT EXISTS(SELECT 1 FROM os_asset_movement n WHERE n.record_id=m.record_id AND n.revision>m.revision)",id)>0)throw new Failure(409,"Move or retire stock-held assets before deleting their stockroom");
    }
}
