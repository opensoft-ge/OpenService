package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
import java.sql.SQLException;
/** Direct relationship administration reuses the same edges consumed by impact analysis. */
final class AssetRelationships {
    private AssetRelationships() {}
    static String direction(Object value){require(value instanceof String && Set.of("dependencies","dependents").contains(value),"Choose dependencies or dependents");return value.toString();}
    static Map<String,Object> source(Store.Db db,String id)throws SQLException{return object(FieldAccess.serialize(db,Service.record(db,"cmdb_ci",id)));}
    static Map<String,Object> page(Store.Db db,String id,Map<String,String> args,boolean choosing)throws SQLException{
        var source=source(db,id);var selection=new LinkedHashMap<>(args);String direction=direction(selection.getOrDefault("direction","dependencies"));selection.remove("direction");
        if(choosing){Service.requireAccountAdministrator(db,db.viewerId);var result=ReferenceSearch.search(db,"cmdb_ci",selection);result.put("source",source);result.put("direction",direction);result.put("query",selection.getOrDefault("q",""));return result;}
        require(Set.of("limit","offset").containsAll(selection.keySet()),"Use direction, limit and offset");int limit=Service.number(selection.getOrDefault("limit","20"),1,100),offset=Service.number(selection.getOrDefault("offset","0"),0,1000000);boolean outbound=direction.equals("dependencies");String near=outbound?"dependent":"dependency",far=outbound?"dependency":"dependent";var parameters=new ArrayList<Object>();parameters.add(id);String access=RecordAccess.filter(db,"r.sys_id",parameters);String sql=" FROM os_dependency d JOIN os_record r ON r.sys_id=d."+far+" AND r.\"table\"='cmdb_ci' WHERE d."+near+"=? AND "+access;long total=db.count("SELECT COUNT(*)"+sql,parameters.toArray());parameters.add(limit);parameters.add(offset);var items=new ArrayList<Map<String,Object>>();for(var row:db.all("SELECT r.*"+sql+" ORDER BY r.seq LIMIT ? OFFSET ?",parameters.toArray())){var data=object(FieldAccess.serialize(db,row));items.add(map("value",row.get("sys_id"),"label",data.getOrDefault("short_description",row.get("sys_id"))));}
        return map("source",source,"direction",direction,"items",items,"total",total,"limit",limit,"offset",offset,"previous_offset",Math.max(0,offset-limit),"next_offset",offset+items.size()<total?offset+limit:-1);
    }
    static void change(Store.Db db,String id,Object input)throws SQLException{
        Service.requireAccountAdministrator(db,db.viewerId);var body=object(input);require(body.keySet().equals(Set.of("direction","related_id","remove")),"Provide direction, related_id and remove");boolean outbound=direction(body.get("direction")).equals("dependencies");String related=WorkJobs.text(body.get("related_id"),32);AssetOperations.link(db,db.viewerId,map("dependent",outbound?id:related,"dependency",outbound?related:id,"remove",body.get("remove")));
    }
}
