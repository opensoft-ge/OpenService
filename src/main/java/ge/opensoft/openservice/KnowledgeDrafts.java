package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
import java.sql.SQLException;
import org.apache.commons.codec.digest.DigestUtils;
/** Review historical recovery separately from publishing the resulting editable draft. */
final class KnowledgeDrafts {
    private KnowledgeDrafts() {}
    static Map<String,Object> review(Store.Db db,String id,Map<String,String> args)throws SQLException{
        Service.requireAccountAdministrator(db,db.viewerId);require(Set.of("version").containsAll(args.keySet()),"Use version only");var schema=Service.schema(db,"kb_knowledge");RecordPolicy.read(db,schema);RecordPolicy.require(schema,"update",db.viewerRole);var item=Service.record(db,"kb_knowledge",id);
        var latest=db.one("SELECT * FROM os_publication WHERE article_id=? ORDER BY version DESC LIMIT 1",id);if(latest==null)latest=map();int version=Service.number(args.getOrDefault("version",latest.getOrDefault("version",0).toString()),0,Integer.MAX_VALUE);var source=version==0?map():db.one("SELECT * FROM os_publication WHERE article_id=? AND version=?",id,version);if(source==null)throw new Failure(404,"Publication version not found");
        var access=RecordAccess.status(db,id);String review=DigestUtils.sha256Hex("knowledge-draft-v1:"+JSON.writeValueAsString(map("actor",db.viewerId,"auth_revision",db.one("SELECT auth_revision FROM os_user WHERE user_id=?",db.viewerId).get("auth_revision"),"item",item,"schema",schema,"access",access,"source",source,"latest",latest)));
        return map("article_id",id,"version",version,"source",source,"latest",latest,"draft",FieldAccess.serialize(db,item),"access",access,"review",review);
    }
    static Map<String,Object> act(Store.Db db,String id,Object input)throws SQLException{
        var body=object(input);require(body.keySet().equals(Set.of("operation","version","review","confirm")),"Provide operation, version, review and confirmation");require(body.get("version") instanceof Integer,"Version must be an integer");String operation=WorkJobs.text(body.get("operation"),20);require(Set.of("recover","publish").contains(operation),"Choose recover or publish");require(Boolean.TRUE.equals(body.get("confirm")),"Confirm the reviewed knowledge action");var view=review(db,id,Map.of("version",body.get("version").toString()));if(!view.get("review").equals(body.get("review")))throw new Failure(409,"Knowledge or authority changed; review it again");
        if(operation.equals("publish"))return PublishedKnowledge.publish(db,db.viewerId,id);
        var source=object(view.get("source"));require(!source.isEmpty(),"Choose a publication version to recover");var item=Service.record(db,"kb_knowledge",id);String actor=Service.account(db,db.viewerId).get("name").toString();Service.update(db,"kb_knowledge",item,map("short_description",source.get("title"),"text",source.get("content")),actor,db.viewerRole,item.get("revision").toString());Service.audit(db,item,actor,"knowledge.draft_recovered",map("source_version",view.get("version")));return map("article_id",id,"source_version",view.get("version"),"draft_revision",item.get("revision"));
    }
}
