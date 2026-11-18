package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.sql.SQLException;
import java.util.*;

/** Optional, restrictive parent access boundaries; no implicit grants or recursive chains. */
final class RecordScopes {
    private RecordScopes() {}

    static Map<String,Object> scope(Store.Db db,String id) throws SQLException {
        var row=db.one("SELECT parent_id,revision FROM os_record_scope WHERE record_id=?",id);
        return row==null?map("parent_id","","revision",0):row;
    }

    static Map<String,Object> view(Store.Db db,String table,String id,Map<String,String> args) throws SQLException {
        Service.requireAccountAdministrator(db,db.viewerId);var item=Service.readRecord(db,table,id);var current=scope(db,id);Map<String,Object> parent=null;
        if(!current.get("parent_id").equals("")){try{var row=db.one("SELECT \"table\" FROM os_record WHERE sys_id=?",current.get("parent_id"));parent=FieldAccess.serialize(db,Service.readRecord(db,row.get("table").toString(),current.get("parent_id").toString()));}catch(Failure inaccessible){ /* Detachment remains possible without disclosing the parent. */ }}
        var children=new ArrayList<Map<String,Object>>();
        for(var row:db.all("SELECT r.* FROM os_record_scope s JOIN os_record r ON r.sys_id=s.record_id WHERE s.parent_id=? ORDER BY r.seq LIMIT 100",id))
            if(RecordPolicy.allows(Service.schema(db,row.get("table").toString()),"read",db.viewerRole))children.add(FieldAccess.serialize(db,row));
        var searchArgs=new LinkedHashMap<>(args);String parentTable=searchArgs.remove("parent_table");if(parentTable==null)parentTable=table;
        var search=ReferenceSearch.search(db,parentTable,searchArgs);for(var candidate:objects(search.get("items"))){String candidateId=candidate.get("value").toString();candidate.put("revision",Service.readRecord(db,parentTable,candidateId).get("revision"));candidate.put("eligible",!id.equals(candidateId) && db.one("SELECT record_id FROM os_record_scope WHERE record_id=?",candidateId)==null);}var tables=new ArrayList<Map<String,Object>>();
        for(var row:db.all("SELECT name,definition FROM os_schema ORDER BY name"))if(RecordPolicy.allows(object(row.get("definition")),"read",db.viewerRole))tables.add(map("value",row.get("name"),"label",object(row.get("definition")).get("label")));
        return map("record",FieldAccess.serialize(db,item),"scope",map("revision",current.get("revision"),"has_parent",!current.get("parent_id").equals("")),"parent",parent,"children",children,"can_attach",db.count("SELECT COUNT(*) FROM os_record_scope WHERE parent_id=?",id)==0,"search",search,"parent_table",parentTable,"tables",tables);
    }

    static Map<String,Object> save(Store.Db db,String table,String id,Object input) throws SQLException {
        Service.requireAccountAdministrator(db,db.viewerId);if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");
        var item=Service.readRecord(db,table,id);var current=scope(db,id);var body=object(input);String operation=WorkJobs.text(body.get("operation"),8);boolean attach=operation.equals("attach");require(attach || operation.equals("detach"),"Choose attach or detach");
        var keys=new HashSet<>(Set.of("operation","revision","record_revision"));if(attach)keys.addAll(Set.of("parent_table","parent_id","parent_revision"));require(body.keySet().equals(keys),"Provide only the selected boundary fields");
        ProblemInvestigations.revision(body.get("revision"),current.get("revision"));ProblemInvestigations.revision(body.get("record_revision"),item.get("revision"));RecordPolicy.require(Service.schema(db,table),"update",db.viewerRole);
        if(attach){
            String parentTable=WorkJobs.text(body.get("parent_table"),80),parentId=WorkJobs.text(body.get("parent_id"),32);var parent=Service.readRecord(db,parentTable,parentId);ProblemInvestigations.revision(body.get("parent_revision"),parent.get("revision"));RecordPolicy.require(Service.schema(db,parentTable),"update",db.viewerRole);
            require(!id.equals(parentId),"A record cannot be its own permission parent");
            require(db.one("SELECT record_id FROM os_record_scope WHERE record_id=?",parentId)==null,"Choose an unscoped parent; nested boundaries are not supported");
            require(db.count("SELECT COUNT(*) FROM os_record_scope WHERE parent_id=?",id)==0,"A parent with scoped children cannot become a child");
            require(db.count("SELECT COUNT(*) FROM os_record_scope WHERE parent_id=? AND record_id<>?",parentId,id)<100,"A parent supports at most 100 scoped children");
            db.update("INSERT INTO os_record_scope(record_id,parent_id,revision) VALUES(?,?,?) ON CONFLICT(record_id) DO UPDATE SET parent_id=excluded.parent_id,revision=excluded.revision",id,parentId,((Number)current.get("revision")).intValue()+1);
        }else db.update("DELETE FROM os_record_scope WHERE record_id=?",id);
        String actor=Service.account(db,db.viewerId).get("name").toString();Service.update(db,table,item,map(),actor,db.viewerRole,body.get("record_revision").toString());Service.audit(db,item,actor,"record.scope_"+operation,map("before",current,"after",scope(db,id)));return view(db,table,id,Map.of());
    }

    static void deleting(Store.Db db,String id) throws SQLException {
        if(db.count("SELECT COUNT(*) FROM os_record_scope WHERE parent_id=?",id)>0)throw new Failure(409,"Detach scoped children explicitly before deleting their permission parent");
    }
}
