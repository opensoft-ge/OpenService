package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.apache.commons.csv.*;
import org.apache.commons.codec.digest.DigestUtils;

final class OfflineWorksheet {
    private OfflineWorksheet() {}
    static byte[] download(Store store,Store.Db db,String taskId) throws Exception {
        var task=FieldPlanning.task(db,taskId);
        var dispatch=FieldDispatch.current(db,taskId);
        require("accepted".equals(dispatch.get("status")) && db.viewerId.equals(dispatch.get("technician_id")),"Accept the assignment before downloading");
        var visit=FieldVisits.current(db,taskId);
        require(Set.of("planned","onsite","travelling").contains(visit.get("phase")),"Configure a visit before downloading");
        String parent=task.get("parent_id").toString();
        var packet=map("key",Values.id(),"task",taskId,"owner",db.viewerId,"dispatch",dispatch.get("revision"),"visit",visit.get("revision"),
                "offline_revision",get(db,"offline-task",taskId).get("revision"),"task_revision",task.get("revision"),"parent_revision",WorkTasks.record(db,task.get("parent_table").toString(),parent).get("revision"));
        String ticket=Outbox.encrypt("field-sheet:"+store.secret,JSON.writeValueAsString(packet));
        var buffer=new StringBuilder();
        try(var csv=new CSVPrinter(buffer,CSVFormat.RFC4180)) {
            csv.printRecord("ticket","v1."+ticket);
            csv.printRecord("visit","'"+task.get("summary"));
            csv.printRecord("location","'"+dispatch.get("location"));
            for(Object check:(List<?>)visit.get("checklist")) csv.printRecord("check","'"+check);
            csv.printRecord("minutes","0");
            csv.printRecord("evidence_note","");
            csv.printRecord("part","","","");
        }
        return buffer.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] phone(Store store,Store.Db db,String task) throws Exception {
        String packet=new String(download(store,db,task),StandardCharsets.UTF_8);
        var details=new ArrayList<String>();
        try(var parser=CSVParser.parse(packet,CSVFormat.RFC4180)){
            for(var row:parser.getRecords()) if(Set.of("visit","location","check").contains(row.get(0))) details.add(row.get(1).substring(1));
        }
        var file=new org.springframework.core.io.ClassPathResource("field-phone.html");
        String template=file.getContentAsString(StandardCharsets.UTF_8);
        String script=new org.springframework.core.io.ClassPathResource("static/field-phone.js").getContentAsString(StandardCharsets.UTF_8);
        template=template.replace("PHONE_DIGEST",Base64.getEncoder().encodeToString(DigestUtils.sha256(script))).replace("PHONE_SCRIPT",script);
        // Insert user content last so placeholder-like text stays literal.
        String ticket=packet.substring(0,packet.indexOf('\r'));
        return template.replace("SIGNED_PACKET",org.springframework.web.util.HtmlUtils.htmlEscape(ticket))
                .replace("VISIT_DETAILS",org.springframework.web.util.HtmlUtils.htmlEscape(String.join("\n",details))).getBytes(StandardCharsets.UTF_8);
    }

    static Map<String,Object> sync(Store store,Store.Db db,String id,byte[] bytes,String photoName,byte[] photo) throws Exception {
        lock(db);
        require(bytes.length<=64000 && photo.length<=5*1024*1024,"Worksheet or photo exceeds the upload limit");
        List<CSVRecord> rows;
        try(var parser=CSVParser.parse(new String(bytes,StandardCharsets.UTF_8).replaceFirst("^\uFEFF",""),CSVFormat.RFC4180)) {
            rows=parser.getRecords();
        } catch(java.io.IOException | java.io.UncheckedIOException malformed) {
            throw new Failure(400,"Invalid worksheet CSV");
        }
        require(!rows.isEmpty(),"Missing worksheet ticket");
        var header=rows.getFirst();
        require(header.size()>=2 && header.get(0).equals("ticket"),"Missing worksheet ticket");
        require(header.stream().skip(2).allMatch(String::isBlank),"Unexpected worksheet ticket columns");
        Map<String,Object> packet;
        try {packet=Outbox.decrypt("field-sheet:"+store.secret,header.get(1).replaceFirst("^v1[.]",""));}
        catch(Exception invalid){throw new Failure(400,"Invalid worksheet ticket");}
        require(id.equals(packet.get("task")) && db.viewerId.equals(packet.get("owner")),"Use your own downloaded visit");
        var dispatch=FieldDispatch.current(db,id);
        String key=packet.get("key").toString();
        String fingerprint=ConnectorSynchronization.hash(List.of(DigestUtils.sha256Hex(bytes),photoName,DigestUtils.sha256Hex(photo)));
        var prior=object(get(db,"offline-receipt",key).get("definition"));
        if(!prior.isEmpty() && db.viewerId.equals(dispatch.get("technician_id"))) {
            FieldPlanning.task(db,id);
            if(!fingerprint.equals(prior.get("fingerprint"))) throw new Failure(409,"Uploaded worksheet differs from the synchronized copy");
            return prior;
        }
        if(!dispatch.get("revision").equals(packet.get("dispatch")) || !db.viewerId.equals(dispatch.get("technician_id"))) {
            var flag=get(db,"offline-conflict",id+key);
            if(flag.get("revision").equals(0)) save(db,"offline-conflict",id+key,0,
                    map("task",id,"worksheet",key,"submitted_by",db.viewerId,"status","needs_review","reported_at",now()));
            return map("status","needs_review");
        }
        var task=FieldPlanning.task(db,id);
        String parent=task.get("parent_id").toString(),table=task.get("parent_table").toString();
        RecordPolicy.require(Service.schema(db,table),"update",db.viewerRole);
        var visit=FieldVisits.current(db,id);
        ProblemInvestigations.revision(packet.get("visit"),visit.get("revision"));
        ProblemInvestigations.revision(packet.get("task_revision"),task.get("revision"));
        ProblemInvestigations.revision(packet.get("parent_revision"),WorkTasks.record(db,table,parent).get("revision"));
        var last=get(db,"offline-task",id);
        ProblemInvestigations.revision(packet.get("offline_revision"),last.get("revision"));
        int minutes=0;
        String note="";
        var parts=new ArrayList<Map<String,Object>>();
        for(var row:rows.subList(1,rows.size())) {
            String kind=row.get(0);
            require(row.size()>=2,"Invalid worksheet row");
            switch(kind) {
                case "visit","location","check" -> { /* Downloaded reference information. */ }
                case "minutes" -> minutes=Service.number(row.get(1),1,1440);
                case "evidence_note" -> note=WorkJobs.text(row.get(1),2000);
                case "installed_serial" -> {
                    if(!row.get(1).isBlank()) DepotTransfers.install(db,id,EnterpriseData.id(row.get(1)));
                }
                case "part" -> {
                    if(row.get(1).isBlank()) continue;
                    require(row.size()==4,"Part rows need item ID, quantity and notes");
                    parts.add(map("ci_id",EnterpriseData.id(row.get(1)),"quantity",Service.number(row.get(2),1,10000),
                            "direction","used","notes",WorkJobs.text(row.get(3),1000)));
                }
                default -> throw new Failure(400,"Unknown worksheet row");
            }
        }
        require(minutes>0 && !note.isEmpty(),"Enter worked minutes and a visit evidence note");
        if(!"onsite".equals(visit.get("phase"))) action(db,table,parent,id,"checkin",map());
        for(var part:parts) action(db,table,parent,id,"addpart",part);
        String attachment="";
        if(photo.length>0) {
            attachment=Values.id();
            db.update("INSERT INTO os_attachment(sys_id,record_id,name,content,content_type) VALUES(?,?,?,?,?)",attachment,parent,
                    WebController.filename(photoName),photo,"application/octet-stream");
            AttachmentScanning.enqueue(db,attachment);
            Service.audit(db,WorkTasks.record(db,table,parent),actor(db),"attachment",map("name",WebController.filename(photoName)));
        }
        var receipt=map("task",id,"technician",db.viewerId,"dispatch_revision",dispatch.get("revision"),"minutes",minutes,"note",note,"parts",parts.size(),
                "attachment",attachment,"fingerprint",fingerprint,"synced",now());
        save(db,"offline-task",id,last.get("revision"),receipt);
        save(db,"offline-receipt",key,0,receipt);
        return receipt;
    }

    static void action(Store.Db db,String table,String parent,String id,String op,Map<String,Object> fields) throws Exception {
        fields.putAll(map("operation",op,"record_revision",WorkTasks.record(db,table,parent).get("revision"),
                "task_revision",WorkTasks.task(db,parent,id).get("revision"),"revision",FieldVisits.current(db,id).get("revision")));
        FieldVisits.command(db,table,parent,id,fields);
    }
}
