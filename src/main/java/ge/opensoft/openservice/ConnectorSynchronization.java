package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.EnterpriseData.*;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;

/** Reviewed native pulls and explicit outbound projection through existing durable connector actions. */
final class ConnectorSynchronization {
    private ConnectorSynchronization() {}
    static Object readJson(byte[] bytes) {
        try {
            return JSON.readValue(bytes, Object.class);
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new Failure(502, "Connector returned invalid JSON");
        }
    }
    static String hash(Object value){return DigestUtils.sha256Hex(JSON.writeValueAsString(value));}
    static Map<String,Object> page(Store.Db db)throws Exception {
        Service.requireAccountAdministrator(db,db.viewerId);
        return EnterpriseData.page("Enterprise connection lifecycle","Refresh protected OAuth credentials, review incoming record values and queue exact outbound record projections. Conflicts require a fresh explicit review. External writes retain the existing reconciliation safeguards.",map("oauth",ConnectorCredentials.publicProfiles(db),"synchronizations",list(db,"sync"),"issue_adapters",IssueAdapters.CATALOG,"schedules",RecurringImports.schedules(db)),
            form("review_issue","Review vendor issue import",field("adapter","Catalog adapter (github or gitlab)","text","github"),field("alias","Approved connector alias","text",""),field("path","Vendor issue API path","text",""),field("record_id","Local incident ID","text","")),
            form("apply_issue","Apply reviewed issue import",field("adapter","Catalog adapter","text","github"),field("alias","Approved connector alias","text",""),field("path","Vendor issue API path","text",""),field("record_id","Local incident ID","text",""),field("proof","Review fingerprint","text",""),field("confirm","Confirm incoming values","boolean",false)),
            form("schedule","Schedule snapshot import or two-way issue sync",field("key","Schedule ID","text",Values.id()),field("revision","Current revision","number",0),field("kind","Connector type (inventory, threat, github or gitlab)","text","inventory"),field("target","Product name, shared feed ID or incident ID","text",""),field("alias","Connector alias","text",""),field("path","Snapshot endpoint","text","/snapshot"),field("minutes","Minutes between imports","number",60),field("enabled","Enabled","boolean",true)),
            form("resolve","Resolve issue conflict",field("key","Schedule ID","text",""),field("revision","Schedule revision","number",0),field("proof","Conflict fingerprint","text",""),field("choice","Winning side (local or remote)","text","remote")),
            form("schedule_oauth","Configure automatic OAuth renewal",field("key","Owned schedule ID","text",""),field("revision","Renewal profile revision","number",0),field("issuer_alias","Approved token issuer connector alias","text",""),field("token_path","Token path","text","/oauth/token"),field("client_id","Client ID","text",""),field("client_secret","Client secret","password",""),field("refresh_token","Refresh token","password","")),
            form("replay","Retry scheduled import",field("key","Schedule ID","text",""),field("revision","Current revision","number",0)),
            form("oauth","Configure OAuth client credentials",field("alias","Existing connector alias","text",""),field("revision","OAuth profile revision","number",0),field("connector_revision","Connector revision","number",1),field("token_path","Token endpoint path","text","/oauth/token"),field("client_id","Client identifier","text",""),field("client_secret","Client secret","password",""),field("scope","Requested scope","text","")),
            form("refresh","Exchange client credentials",field("alias","Connector alias","text",""),field("revision","OAuth profile revision","number",1)),
            form("review_pull","Review incoming record",field("alias","Connector alias","text",""),field("path","Native source path","text","/record"),field("table","Target table","text","incident"),field("record_id","Target record ID","text",""),field("fields","Allowed incoming fields","json","[\"short_description\"]")),
            form("apply_pull","Apply reviewed incoming values",field("alias","Connector alias","text",""),field("path","Native source path","text","/record"),field("table","Target table","text","incident"),field("record_id","Target record ID","text",""),field("fields","Allowed incoming fields","json","[\"short_description\"]"),field("proof","Review fingerprint","text",""),field("confirm","Confirm reviewed source wins","boolean",false)),
            form("review_push","Review outbound record projection",field("table","Source table","text","incident"),field("record_id","Source record ID","text",""),field("action_id","Configured connector action","text",""),field("mapping","Action input names to source fields","json","{}"),field("key","Unique outbound key","text",Values.id())),
            form("queue_push","Queue reviewed outbound projection",field("table","Source table","text","incident"),field("record_id","Source record ID","text",""),field("action_id","Configured connector action","text",""),field("mapping","Action input names to source fields","json","{}"),field("key","Outbound key","text",""),field("proof","Record fingerprint","text",""),field("state","Action fingerprint","text",""),field("preview_token","Action consent token","text",""),field("confirm","Confirm this external transmission","boolean",false)));
    }
    static Map<String,Object> pull(Store store,Store.Db db,Map<String,Object> body)throws Exception {
        String table=WorkJobs.text(body.get("table"),80),id=EnterpriseData.id(body.get("record_id"));var record=Service.record(db,table,id);var schema=Service.schema(db,table);RecordPolicy.require(schema,"update",db.viewerRole);
        require(body.get("fields") instanceof List<?> list && !list.isEmpty() && list.size()<=10,"Select one to ten incoming fields");var fields=new TreeSet<String>();for(Object value:(List<?>)body.get("fields")){String field=WorkJobs.text(value,80);require(fields.add(field) && object(schema.get("fields")).containsKey(field),"Select unique current fields");require(FieldAccess.allowed(db,schema,field),"Incoming field is unavailable");}
        var fetched=Connectors.fetch(store,db,map("alias",body.get("alias"),"path",body.get("path")));var source=object(readJson((byte[])fetched.get("content")));if (body.containsKey("adapter")) {
            source = IssueAdapters.source(WorkJobs.text(body.get("adapter"), 16), body.get("path").toString(), source, id);
        } else {
            keys(source,"record_id","version","values");
        }
        require(id.equals(source.get("record_id")),"Source record correlation does not match");WorkJobs.text(source.get("version"),160);var values=object(source.get("values"));require(values.keySet().equals(fields),"Source values must match exactly the reviewed field selection");var local=map();var visible=FieldAccess.serialize(db,record);for(String field:fields)local.put(field,visible.getOrDefault(field,""));String key=hash(map("alias",body.get("alias"),"path",body.get("path"),"table",table,"record_id",id));var prior=object(get(db,"sync",key).get("definition"));boolean localChanged=!prior.isEmpty() && !hash(local).equals(prior.get("local_hash")),remoteChanged=!prior.isEmpty() && !hash(values).equals(prior.get("remote_hash"));
        String proof=hash(map("actor",db.viewerId,"authority",accountRevision(db),"record",record,"schema",schema,"source",source,"alias",body.get("alias"),"path",body.get("path"),"connector",fetched.get("revision"),"baseline",prior));
        return map("key",key,"proof",proof,"before",local,"after",values,"version",source.get("version"),"conflict",localChanged && remoteChanged,"record_revision",record.get("revision"));
    }
    static Object accountRevision(Store.Db db)throws Exception{return db.one("SELECT auth_revision FROM os_user WHERE user_id=?",db.viewerId);}
    static Object command(Store store,Store.Db db,Object input)throws Exception {
        Service.requireAccountAdministrator(db,db.viewerId);lock(db);var body=object(input);String op=WorkJobs.text(body.get("operation"),16);
        if(Set.of("oauth","refresh").contains(op)){ConnectorCredentials.command(store,db,body);return map("saved",true);}
        boolean vendor = Set.of("review_issue", "apply_issue").contains(op);
        if (vendor) {
            var names = new HashSet<>(Set.of("operation","adapter","alias","path","record_id"));
            if (op.equals("apply_issue")) names.addAll(Set.of("proof","confirm"));
            require(body.keySet().equals(names), "Provide only the selected issue import fields");
            IssueAdapters.validatePath(WorkJobs.text(body.get("adapter"),16), Connectors.path(body.get("path")));
            body.put("fields", List.of("short_description","description"));
            body.put("table", "incident");
            op = op.replace("_issue", "_pull");
        }
        if(Set.of("review_pull","apply_pull").contains(op)){
            boolean apply=op.equals("apply_pull");if (!vendor) keys(body,apply?new String[]{"operation","alias","path","table","record_id","fields","proof","confirm"}:new String[]{"operation","alias","path","table","record_id","fields"});var review=pull(store,db,body);if(!apply)return review;require(Boolean.TRUE.equals(body.get("confirm")),"Confirm the reviewed incoming values and any conflict resolution");if(!review.get("proof").equals(body.get("proof")))throw new Failure(409,"Source, target or authority changed; review synchronization again");String table=body.get("table").toString(),id=body.get("record_id").toString();var record=Service.record(db,table,id);Service.update(db,table,record,review.get("after"),actor(db),db.viewerRole,record.get("revision").toString());String key=review.get("key").toString();var prior=get(db,"sync",key);save(db,"sync",key,prior.get("revision"),map("table",table,"record_id",id,"source_version",review.get("version"),"local_hash",hash(review.get("after")),"remote_hash",hash(review.get("after")),"actor",db.viewerId,"updated",now()));return map("applied",true,"conflict_resolved",review.get("conflict"));
        }
        require(Set.of("review_push","queue_push").contains(op),"Choose OAuth, incoming review or outbound projection");boolean queue=op.equals("queue_push");keys(body,queue?new String[]{"operation","table","record_id","action_id","mapping","key","proof","state","preview_token","confirm"}:new String[]{"operation","table","record_id","action_id","mapping","key"});String table=WorkJobs.text(body.get("table"),80);var record=Service.readRecord(db,table,EnterpriseData.id(body.get("record_id")));var values=map();var mapping=object(body.get("mapping"));require(!mapping.isEmpty() && mapping.size()<=10,"Map one to ten explicit source fields");var shown=FieldAccess.serialize(db,record);
        for(var entry:mapping.entrySet()){String field=WorkJobs.text(entry.getValue(),80);require(shown.containsKey(field),"Source field is unavailable");values.put(entry.getKey(),shown.get(field));}String action=EnterpriseData.id(body.get("action_id")),key=EnterpriseData.id(body.get("key"));String proof=hash(map("actor",db.viewerId,"record",record,"mapping",mapping));
        if(!queue){var preview=ConnectorActions.preview(store,db,action,map("values",values,"key",key));preview.put("proof",proof);return preview;}
        if(!proof.equals(body.get("proof")))throw new Failure(409,"Source record changed; review the outbound projection again");return ConnectorActions.queue(store,db,action,map("values",values,"key",key,"state",body.get("state"),"preview_token",body.get("preview_token"),"confirm",body.get("confirm")));
    }
}
