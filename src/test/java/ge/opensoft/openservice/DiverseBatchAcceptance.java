package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class DiverseBatchAcceptance {
    static final String ROOT = "/api/openservice/operations/";

    static Map<String, Object> result(WorkflowSupport h, String path) throws Exception {
        var response = h.get(ROOT + path, auth("admin"));
        assertEquals(200, response.statusCode(), html(response));
        return object(parsed(response).get("result"));
    }

    static void retireConnector(WorkflowSupport h) throws Exception {
        h.account("RetireAgent", "agent");
        try (var db = h.store.open()) {
            db.update("INSERT INTO os_connector(alias,origin,envelope,revision) VALUES(?,?,?,1)",
                    "retirable", "https://example.invalid",
                    Outbox.encrypt(h.store.secret, JSON.writeValueAsString(map("token", "synthetic-retirement-token"))));
            db.connection.commit();
        }
        String path = ROOT + "connectors/retirable";
        var reason = map("reason", "Integration no longer used");
        assertEquals(401, h.json("DELETE", path, reason, null).statusCode());
        assertEquals(403, h.json("DELETE", path, reason, auth("RetireAgent")).statusCode());
        assertEquals(400, h.json("DELETE", path, map(), auth("admin")).statusCode());
        assertEquals(400, h.json("DELETE", path, map("reason", " "), auth("admin")).statusCode());
        assertEquals(400, h.json("DELETE", ROOT + "connectors", reason, auth("admin")).statusCode());
        String job = PlatformOperationsAcceptance.post(h, "jobs", map("kind", "connector.read", "key", "retired-test",
                "payload", map("alias", "retirable", "path", "/health"))).get("job_id").toString();
        assertEquals(200, h.json("DELETE", path, reason, auth("admin")).statusCode());
        assertEquals(404, h.json("DELETE", path, reason, auth("admin")).statusCode());
        assertTrue(new WorkWorker(h.store).runOne(System.currentTimeMillis()));
        try (var db = h.store.open()) {
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_connector WHERE alias='retirable'"));
            var row = db.one("SELECT state,envelope,error FROM os_work WHERE job_id=?", job);
            assertEquals("cancelled", row.get("state"));
            assertEquals("", row.get("envelope"));
            assertEquals("AUTHORITY_OR_INPUT_UNAVAILABLE", row.get("error"));
            var events = db.all("SELECT detail FROM os_security_event WHERE action='connector.retired'");
            assertEquals(1, events.size());
            assertTrue(events.toString().contains("Integration no longer used"));
            assertFalse(events.toString().contains("synthetic-retirement-token"));
        }
    }

    static void knowledgeHistory(WorkflowSupport h) throws Exception {
        assertEquals(405, h.get(ROOT + "assist", auth("admin")).statusCode());
        h.account("HistoryReader", "reader");
        String article = h.add("kb_knowledge").get("sys_id").toString();
        String tablePath = "/api/now/table/kb_knowledge/" + article;
        assertEquals(200, h.json("PATCH", tablePath, map("text", "Published content"), auth("admin")).statusCode());
        PlatformOperationsAcceptance.post(h, "knowledge/" + article, map("operation", "publish"));
        assertEquals(200, h.json("PATCH", tablePath, map("text", "PRIVATE DRAFT"), auth("admin")).statusCode());
        try (var db = h.store.open()) {
            for (int i=2; i<=102; i++) db.update("INSERT INTO os_publication(article_id,version,title,content,published_at,retired) VALUES(?,?,?,?,?,1)", article,i,"Title "+i,"Snapshot "+i,now());
            db.connection.commit();
        }
        String path = "knowledge/" + article;
        var first = result(h, path);
        assertEquals(100, objects(first.get("versions")).size());
        assertEquals("3", first.get("next_before"));
        assertEquals(102, ((Number)objects(first.get("versions")).getFirst().get("version")).intValue());
        var last = result(h, path + "?before=3");
        assertEquals(2, objects(last.get("versions")).size());
        assertEquals("", last.get("next_before"));
        assertEquals("Published content", objects(last.get("versions")).getLast().get("content"));
        assertFalse(last.toString().contains("PRIVATE DRAFT"));
        assertTrue(objects(result(h, path + "?before=1").get("versions")).isEmpty());
        assertEquals(401, h.get(ROOT + path, null).statusCode());
        assertEquals(403, h.get(ROOT + path, auth("HistoryReader")).statusCode());
        assertEquals(404, h.get(ROOT + "knowledge/missing", auth("admin")).statusCode());
        for (String query : List.of("before=0", "before=no", "before=2147483648", "extra=1", "before=2&before=3"))
            assertEquals(400, h.get(ROOT + path + "?" + query, auth("admin")).statusCode(), query);
        assertEquals(400, h.json("POST", ROOT + path + "?before=2", map("operation", "retire"), auth("admin")).statusCode());
    }

    static void cancelClocks(WorkflowSupport h) throws Exception {
        h.account("ClockReader", "reader");
        String record = h.add("incident").get("sys_id").toString();
        PlatformOperationsAcceptance.post(h, "calendars", map("name", "cancel-hours", "calendar", PlatformFoundationTest.calendar()));
        for (String initial : List.of("running", "paused")) {
            String clock = PlatformOperationsAcceptance.post(h, "clocks", map("table", "incident", "record_id", record, "calendar", "cancel-hours", "minutes", 120)).get("sla_id").toString();
            if (initial.equals("paused")) PlatformOperationsAcceptance.post(h, "clocks/"+clock, map("operation", "pause"));
            assertEquals(403, h.json("POST", ROOT+"clocks/"+clock, map("operation", "cancel"), auth("ClockReader")).statusCode());
            PlatformOperationsAcceptance.post(h, "clocks/"+clock, map("operation", "cancel"));
            assertEquals(400, h.json("POST", ROOT+"clocks/"+clock, map("operation", "resume"), auth("admin")).statusCode());
            try (var db = h.store.open()) {
                var before = db.one("SELECT state,last_tick,remaining_ms FROM os_sla WHERE sla_id=?", clock);
                assertEquals("cancelled", before.get("state"));
                ServiceClocks.tick(db, System.currentTimeMillis() + 86400000);
                assertEquals(before, db.one("SELECT state,last_tick,remaining_ms FROM os_sla WHERE sla_id=?", clock));
                assertEquals(1, db.count("SELECT COUNT(*) FROM os_security_event WHERE action='sla.cancel' AND CAST(detail AS TEXT) LIKE ?", "%"+clock+"%"));
            }
        }
        String late = PlatformOperationsAcceptance.post(h, "clocks", map("table", "incident", "record_id", record, "calendar", "cancel-hours", "minutes", 120)).get("sla_id").toString();
        try (var db = h.store.open()) {
            db.update("UPDATE os_sla SET last_tick=? WHERE sla_id=?", System.currentTimeMillis()-7L*86400000, late);
            db.connection.commit();
        }
        PlatformOperationsAcceptance.post(h, "clocks/"+late, map("operation", "cancel"));
        try (var db = h.store.open()) {
            assertEquals("breached", db.one("SELECT state FROM os_sla WHERE sla_id=?", late).get("state"));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_security_event WHERE action='sla.cancel' AND CAST(detail AS TEXT) LIKE ?", "%"+late+"%"));
        }
        assertEquals(400, h.json("POST", ROOT+"clocks/"+late, map("operation", "cancel"), auth("admin")).statusCode());
    }

    static void updateReport(WorkflowSupport h) throws Exception {
        h.account("ReportAgent", "agent");
        String original;
        try (var db = h.store.open()) {
            var schema = Service.schema(db, "incident");
            original = JSON.writeValueAsString(schema);
            object(schema.get("fields")).put("cost", "Cost");
            schema.put("field_rules", map("cost", map("type", "decimal")));
            db.update("UPDATE os_schema SET definition="+db.jsonParameter()+" WHERE name='incident'", JSON.writeValueAsString(schema));
            db.connection.commit();
        }
        try {
            var definition = map("name", "Original report", "table", "incident", "shared", true, "query", map("field", "cost"));
            String id = PlatformOperationsAcceptance.post(h, "reports", definition).get("report_id").toString();
            String path = ROOT+"reports/"+id;
            assertEquals(200, h.get(path, auth("ReportAgent")).statusCode());
            assertEquals(404, h.json("POST", path, definition, auth("ReportAgent")).statusCode());
            assertEquals(404, h.json("POST", ROOT+"reports/missing", definition, auth("admin")).statusCode());
            var invalid = new LinkedHashMap<>(definition);
            invalid.put("query", map("field", "not_a_number"));
            assertEquals(400, h.json("POST", path, invalid, auth("admin")).statusCode());
            definition.put("name", "Updated report");
            definition.put("shared", false);
            assertEquals(id, PlatformOperationsAcceptance.post(h, "reports/"+id, definition).get("report_id"));
            assertEquals(404, h.get(path, auth("ReportAgent")).statusCode());
            assertEquals(200, h.get(path, auth("admin")).statusCode());
            try (var db = h.store.open()) {
                assertEquals(1, db.count("SELECT COUNT(*) FROM os_saved_report"));
                assertEquals("Updated report", db.one("SELECT name FROM os_saved_report WHERE report_id=?", id).get("name"));
            }
            definition.put("shared", true);
            PlatformOperationsAcceptance.post(h, "reports/"+id, definition);
            assertEquals(200, h.get(path, auth("ReportAgent")).statusCode());
        } finally {
            try (var db = h.store.open()) {
                db.update("UPDATE os_schema SET definition="+db.jsonParameter()+" WHERE name='incident'", original);
                db.connection.commit();
            }
        }
    }

    static void scheduledWork(WorkflowSupport h) throws Exception {
        h.account("ScheduleReader", "reader");
        long due = System.currentTimeMillis() + 3600000;
        var body = map("kind", "notification", "key", "scheduled", "payload", map("username", "ScheduleReader", "message", "Scheduled reminder"),
                "run_at", java.time.Instant.ofEpochMilli(due).toString());
        for (Object invalid : Arrays.asList(null, "yesterday", "2020-01-01T00:00:00Z", java.time.Instant.ofEpochMilli(due).plusNanos(1).toString(), java.time.Instant.ofEpochMilli(due + 367L*86400000).toString(), "+999999999-12-31T23:59:59Z")) {
            var bad = new LinkedHashMap<>(body); bad.put("run_at", invalid);
            assertEquals(400, h.json("POST", ROOT+"jobs", bad, auth("admin")).statusCode());
        }
        assertEquals(403, h.json("POST", ROOT+"jobs", body, auth("ScheduleReader")).statusCode());
        String job = PlatformOperationsAcceptance.post(h, "jobs", body).get("job_id").toString();
        assertEquals(job, PlatformOperationsAcceptance.post(h, "jobs", body).get("job_id"));
        var conflict = new LinkedHashMap<>(body); conflict.put("run_at", java.time.Instant.ofEpochMilli(due+1000).toString());
        assertEquals(409, h.json("POST", ROOT+"jobs", conflict, auth("admin")).statusCode());
        var restarted = new WorkWorker(new Store(h.environment));
        assertFalse(restarted.runOne(due-1));
        assertTrue(restarted.runOne(due));
        assertFalse(restarted.runOne(due+1));
        try (var db = h.store.open()) {
            assertEquals("succeeded", db.one("SELECT state FROM os_work WHERE job_id=?", job).get("state"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_notice WHERE notice_id=?", job));
        }
        var notices = objects(parsed(h.get("/api/openservice/me/notifications", auth("ScheduleReader"))).get("result"));
        assertEquals("Scheduled reminder", notices.getFirst().get("message"));
    }

    static void numericRanges(WorkflowSupport h) throws Exception {
        h.account("RangeReader", "reader");
        String original;
        try (var db = h.store.open()) {
            var schema = Service.schema(db, "incident");
            original = JSON.writeValueAsString(schema);
            object(schema.get("fields")).put("cost", "Cost");
            schema.put("field_rules", map("cost", map("type", "decimal")));
            db.update("UPDATE os_schema SET definition="+db.jsonParameter()+" WHERE name='incident'", JSON.writeValueAsString(schema));
            db.connection.commit();
        }
        try {
            for(String value : List.of("-10","2","10","9007199254740993.01","9007199254740993.02","","\u2003")) {
                String id=h.add("incident").get("sys_id").toString();
                assertEquals(200,h.json("PATCH","/api/now/table/incident/"+id,map("cost",value),auth("admin")).statusCode());
            }
            String path="/api/openservice/analytics/incident?field=cost";
            var expected=Map.of("&minimum=2&maximum=10","2", "&minimum=2","4", "&maximum=-10","1",
                    "&minimum=9007199254740993.01&maximum=9007199254740993.01","1",
                    "&minimum=11&maximum=12","0", "","7", "&minimum=&maximum=","7");
            for(var entry:expected.entrySet()) {
                var response=h.get(path+entry.getKey(),auth("RangeReader"));
                assertEquals(200,response.statusCode(),html(response));
                var totals=objects(object(parsed(response).get("result")).get("rows")).getFirst();
                assertEquals(entry.getValue(),totals.get("count"),entry.getKey());
                if(entry.getKey().contains("9007199254740993.01")) assertEquals("9007199254740993.01",totals.get("sum"));
            }
            var grouped=h.get(path+"&minimum=2&maximum=10&group_by=state",auth("RangeReader"));
            assertEquals(200,grouped.statusCode(),html(grouped));
            assertEquals("12",objects(object(parsed(grouped).get("result")).get("rows")).getFirst().get("sum"));
            for(String query:List.of("&minimum=bad","&minimum=1e2","&minimum=20&maximum=10","&maximum="+"9".repeat(129)))
                assertEquals(400,h.get(path+query,auth("admin")).statusCode(),query);
            var definition=map("name","Ranged totals","table","incident","shared",true,"query",map("field","cost","minimum","2","maximum","10"));
            String report=PlatformOperationsAcceptance.post(h,"reports",definition).get("report_id").toString();
            assertEquals("12",objects(result(h,"reports/"+report).get("rows")).getFirst().get("sum"));
            String hidden=h.tableQuery("cost=10","").getFirst().get("sys_id").toString();
            try(var db=h.store.open()) {
                db.update("INSERT INTO os_record_access(record_id,visibility,owner_id,group_id) VALUES(?,'private',?,'')",hidden,RecordAccess.owner(db,"admin"));
                db.connection.commit();
            }
            var restricted=h.get(path+"&minimum=2&maximum=10",auth("RangeReader"));
            assertEquals("2",objects(object(parsed(restricted).get("result")).get("rows")).getFirst().get("sum"));
            try(var db=h.store.open()) {
                var schema=Service.schema(db,"incident");
                object(TypedFields.rules(schema).get("cost")).put("read_roles",List.of("admin"));
                db.update("UPDATE os_schema SET definition="+db.jsonParameter()+" WHERE name='incident'",JSON.writeValueAsString(schema));
                db.connection.commit();
            }
            assertEquals(400,h.get(path+"&minimum=2",auth("RangeReader")).statusCode());
        } finally {
            try(var db=h.store.open()) {
                db.update("UPDATE os_schema SET definition="+db.jsonParameter()+" WHERE name='incident'",original);
                db.connection.commit();
            }
        }
    }

    static void aiPreview(WorkflowSupport h) throws Exception {
        h.account("PreviewReader","reader");
        String record = h.add("incident").get("sys_id").toString();
        var request = map("table","incident","record_id",record);
        assertEquals(401,h.json("POST",ROOT+"assist-preview",request,null).statusCode());
        assertEquals(403,h.json("POST",ROOT+"assist-preview",request,auth("PreviewReader")).statusCode());
        assertEquals(400,h.json("POST",ROOT+"assist-preview",map(),auth("admin")).statusCode());
        assertEquals(404,h.json("POST",ROOT+"assist-preview",map("table","incident","record_id",id()),auth("admin")).statusCode());
        var preview = PlatformOperationsAcceptance.post(h,"assist-preview",request);
        assertEquals(false,preview.get("external_request_sent"));
        assertEquals(record,preview.get("record_id"));
        var fields = object(preview.get("grounding"));
        assertTrue(fields.containsKey("short_description"));
        assertFalse(fields.containsKey("assigned_to"));
        assertFalse(fields.containsKey("number"));
        assertEquals(JSON.writeValueAsString(fields),preview.get("content"));
        assertEquals(503,h.json("POST",ROOT+"assist",map("table","incident","record_id",record,"allow_external",true),auth("admin")).statusCode());
        try(var db = h.store.open()) {
            assertEquals(0,db.count("SELECT COUNT(*) FROM os_security_event WHERE action='ai.summary_generated'"));
        }
    }

    static void eventActions(WorkflowSupport h) throws Exception {
        h.account("EventAgent","agent");
        String asset=h.add("cmdb_ci").get("sys_id").toString(),key="monitor / with spaces";
        var event=map("key",key,"asset_id",asset,"severity","critical","summary","Service unavailable");
        assertEquals("open",PlatformOperationsAcceptance.post(h,"events",event).get("state"));
        var command=map("key",key,"operation","acknowledge","reason","Investigating");
        assertEquals(401,h.json("POST",ROOT+"event-actions",command,null).statusCode());
        assertEquals(403,h.json("POST",ROOT+"event-actions",command,auth("EventAgent")).statusCode());
        assertEquals(400,h.json("POST",ROOT+"event-actions",map(),auth("admin")).statusCode());
        var bad=new LinkedHashMap<>(command); bad.put("operation","invalid");
        assertEquals(400,h.json("POST",ROOT+"event-actions",bad,auth("admin")).statusCode());
        bad.put("operation","resolve"); bad.put("key","missing");
        assertEquals(404,h.json("POST",ROOT+"event-actions",bad,auth("admin")).statusCode());
        assertEquals("acknowledged",PlatformOperationsAcceptance.post(h,"event-actions",command).get("state"));
        assertEquals("acknowledged",PlatformOperationsAcceptance.post(h,"event-actions",command).get("state"));
        assertEquals("acknowledged",PlatformOperationsAcceptance.post(h,"events",event).get("state"));
        command.put("operation","resolve");
        assertEquals("resolved",PlatformOperationsAcceptance.post(h,"event-actions",command).get("state"));
        command.put("operation","acknowledge");
        assertEquals(409,h.json("POST",ROOT+"event-actions",command,auth("admin")).statusCode());
        command.put("operation","reopen");
        assertEquals("open",PlatformOperationsAcceptance.post(h,"event-actions",command).get("state"));
        command.put("operation","resolve");
        PlatformOperationsAcceptance.post(h,"event-actions",command);
        var recurrence=PlatformOperationsAcceptance.post(h,"events",event);
        assertEquals("open",recurrence.get("state"));
        assertEquals(3,((Number)recurrence.get("occurrences")).intValue());
        try(var db=h.store.open()) {
            assertEquals(1,db.count("SELECT COUNT(*) FROM os_security_event WHERE action='event.acknowledge'"));
            assertEquals(2,db.count("SELECT COUNT(*) FROM os_security_event WHERE action='event.resolve'"));
            assertEquals(1,db.count("SELECT COUNT(*) FROM os_security_event WHERE action='event.reopen'"));
            assertEquals(0,db.count("SELECT COUNT(*) FROM os_record WHERE \"table\"='incident'"));
        }
    }

    static void restoreKnowledge(WorkflowSupport h) throws Exception {
        h.account("RestoreRequester","requester");
        String id=h.add("kb_knowledge").get("sys_id").toString(),path="/api/now/table/kb_knowledge/"+id,resource="knowledge/"+id;
        assertEquals(200,h.json("PATCH",path,map("text","Original public content"),auth("admin")).statusCode());
        PlatformOperationsAcceptance.post(h,resource,map("operation","publish"));
        assertEquals(200,h.json("PATCH",path,map("text","Second publication"),auth("admin")).statusCode());
        PlatformOperationsAcceptance.post(h,resource,map("operation","publish"));
        PlatformOperationsAcceptance.post(h,resource,map("operation","retire"));
        assertEquals(200,h.json("PATCH",path,map("text","PRIVATE DRAFT"),auth("admin")).statusCode());
        var command=map("operation","restore","version",1);
        assertEquals(403,h.json("POST",ROOT+resource,command,auth("RestoreRequester")).statusCode());
        for(var bad : List.of(map("operation","restore"),map("operation","restore","version","1"),map("operation","restore","version",0)))
            assertEquals(400,h.json("POST",ROOT+resource,bad,auth("admin")).statusCode());
        assertEquals(404,h.json("POST",ROOT+resource,map("operation","restore","version",999),auth("admin")).statusCode());
        try(var db=h.store.open()) {
            db.update("INSERT INTO os_record_access(record_id,visibility,owner_id,group_id) VALUES(?,'private',?,'')",id,RecordAccess.owner(db,"admin"));
            db.connection.commit();
        }
        assertEquals(400,h.json("POST",ROOT+resource,command,auth("admin")).statusCode());
        try(var db=h.store.open()) { db.update("DELETE FROM os_record_access WHERE record_id=?",id); db.connection.commit(); }
        assertEquals(3,PlatformOperationsAcceptance.post(h,resource,command).get("version"));
        var published=objects(parsed(h.get("/api/openservice/knowledge",auth("RestoreRequester"))).get("result"));
        assertEquals("Original public content",published.getFirst().get("content"));
        assertFalse(published.toString().contains("PRIVATE DRAFT"));
        try(var db=h.store.open()) {
            assertEquals("PRIVATE DRAFT",object(db.one("SELECT data FROM os_record WHERE sys_id=?",id).get("data")).get("text"));
            assertEquals(2,db.count("SELECT COUNT(*) FROM os_publication WHERE article_id=? AND retired=1",id));
            assertEquals(1,db.count("SELECT COUNT(*) FROM os_event WHERE record_id=? AND action='knowledge.restored'",id));
            db.update("INSERT INTO os_publication(article_id,version,title,content,published_at) VALUES(?,?,?,?,?)",id,Integer.MAX_VALUE,"Limit","Limit",now());
            db.connection.commit();
        }
        assertEquals(400,h.json("POST",ROOT+resource,command,auth("admin")).statusCode());
        assertEquals(400,h.json("POST",ROOT+resource,map("operation","publish"),auth("admin")).statusCode());
    }

    static void jobReceipts(WorkflowSupport h) throws Exception {
        h.account("ReceiptReader","reader");
        String id=PlatformOperationsAcceptance.post(h,"jobs",map("kind","notification","key","receipt",
                "payload",map("username","ReceiptReader","message","Private message"))).get("job_id").toString();
        assertEquals(403,h.get(ROOT+"jobs/"+id,auth("ReceiptReader")).statusCode());
        assertEquals(404,h.get(ROOT+"jobs/missing",auth("admin")).statusCode());
        assertEquals("not_available",result(h,"jobs/"+id).get("receipt_status"));
        assertTrue(new WorkWorker(h.store).runOne(System.currentTimeMillis()));
        var complete=result(h,"jobs/"+id);
        assertEquals("succeeded",complete.get("state"));
        assertEquals("available",complete.get("receipt_status"));
        assertEquals(map("delivered",true),complete.get("receipt"));
        assertFalse(complete.toString().contains("Private message"));
        assertFalse(complete.containsKey("envelope"));
        for(String kind:List.of("connector.read","migration.step")) {
            try(var db=h.store.open()) {
                db.update("UPDATE os_work SET kind=?,result=? WHERE job_id=?",kind,
                        Outbox.encrypt(h.store.secret,JSON.writeValueAsString(map("receipt",
                                map("alias","safe","revision",1,"bytes",42,"sha256","a".repeat(64),"token","synthetic-secret")))),id);
                db.connection.commit();
            }
            var receipt=object(result(h,"jobs/"+id).get("receipt"));
            assertEquals(kind.equals("connector.read")?4:0,receipt.size());
            assertFalse(receipt.toString().contains("synthetic-secret"));
        }
        try(var db=h.store.open()) { db.update("UPDATE os_work SET result='invalid-envelope' WHERE job_id=?",id); db.connection.commit(); }
        var unreadable=result(h,"jobs/"+id);
        assertEquals("unreadable",unreadable.get("receipt_status"));
        assertEquals(map(),unreadable.get("receipt"));
        assertFalse(unreadable.toString().contains("invalid-envelope"));
    }

    static void impact(WorkflowSupport h) throws Exception {
        String source = h.add("cmdb_ci").get("sys_id").toString();
        String middle;
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            middle = PlatformFoundationTest.record(db, "cmdb_ci", "Middle");
            db.update("INSERT INTO os_dependency VALUES(?,?)", middle, source);
            db.update("INSERT INTO os_dependency VALUES(?,?)", source, middle);
            for (int i = 0; i < 99; i++) {
                String id = PlatformFoundationTest.record(db, "cmdb_ci", "Dependent " + i);
                db.update("INSERT INTO os_dependency VALUES(?,?)", id, middle);
            }
            db.connection.commit();
        }
        var exact = result(h, "dependencies/" + source);
        assertEquals(100, objects(exact.get("impacted")).size());
        assertEquals(false, exact.get("truncated"));
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            for (int i = 0; i < 110; i++) {
                String id = PlatformFoundationTest.record(db, "cmdb_ci", "Overflow " + i);
                db.update("INSERT INTO os_dependency VALUES(?,?)", id, middle);
            }
            db.connection.commit();
        }
        var overflow = result(h, "dependencies/" + source);
        var rows = objects(overflow.get("impacted"));
        assertEquals(100, rows.size());
        assertEquals(true, overflow.get("truncated"));
        assertEquals(100, rows.stream().map(r -> r.get("sys_id")).distinct().count());
        assertFalse(rows.stream().anyMatch(r -> source.equals(r.get("sys_id"))));
        assertEquals(401, h.get(ROOT + "dependencies/" + source, null).statusCode());
    }

    static void reminders(WorkflowSupport h) throws Exception {
        h.account("ReminderReviewer", "agent");
        String record = h.add("incident").get("sys_id").toString();
        String path = "/api/openservice/records/incident/"+record+"/approvals";
        String approval = ApprovalAcceptance.create(h, path, "ReminderReviewer", "admin");
        String endpoint = "approval-reminders/"+approval;
        assertEquals(401, h.json("POST", ROOT+endpoint, map(), null).statusCode());
        assertEquals(403, h.json("POST", ROOT+endpoint, map(), auth("ReminderReviewer")).statusCode());
        assertEquals(400, h.json("POST", ROOT+endpoint, map("extra", true), auth("admin")).statusCode());
        assertEquals(404, h.json("POST", ROOT+"approval-reminders/"+id(), map(), auth("admin")).statusCode());
        assertEquals(200, h.json("POST", "/api/openservice/me/notifications", map("enabled", false), auth("ReminderReviewer")).statusCode());
        assertEquals(false, PlatformOperationsAcceptance.post(h, endpoint, map()).get("delivered"));
        assertEquals(200, h.json("POST", "/api/openservice/me/notifications", map("enabled", true), auth("ReminderReviewer")).statusCode());
        assertEquals(map("delivered", true, "duplicate", false), PlatformOperationsAcceptance.post(h, endpoint, map()));
        assertEquals(map("delivered", true, "duplicate", true), PlatformOperationsAcceptance.post(h, endpoint, map()));
        var notices = objects(parsed(h.get("/api/openservice/me/notifications", auth("ReminderReviewer"))).get("result"));
        assertEquals(1, notices.size());
        assertTrue(notices.getFirst().get("message").toString().contains(approval));
        try (var db = h.store.open()) {
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_event WHERE action='approval.reminded' AND record_id=?", record));
            db.update("UPDATE os_user SET active=0 WHERE name='ReminderReviewer'");
            db.connection.commit();
        }
        assertEquals(409, h.json("POST", ROOT+endpoint, map(), auth("admin")).statusCode());
        try (var db = h.store.open()) {
            db.update("UPDATE os_user SET active=1 WHERE name='ReminderReviewer'");
            db.update("INSERT INTO os_record_access(record_id,visibility,owner_id,group_id) VALUES(?,'private',?,'')", record, RecordAccess.owner(db,"admin"));
            db.connection.commit();
        }
        assertEquals(404, h.json("POST", ROOT+endpoint, map(), auth("admin")).statusCode());
        try (var db = h.store.open()) {
            db.update("DELETE FROM os_record_access WHERE record_id=?", record);
            db.connection.commit();
        }
        assertEquals(200, h.json("POST", path+"/"+approval+"/withdraw", map("comment","No longer needed"), auth("admin")).statusCode());
        assertEquals(409, h.json("POST", ROOT+endpoint, map(), auth("admin")).statusCode());
        String fresh = ApprovalAcceptance.create(h, path, "ReminderReviewer", "admin");
        assertEquals(200, h.json("PATCH", "/api/now/table/incident/"+record, map("short_description","Updated proposal"), auth("admin")).statusCode());
        assertEquals(409, h.json("POST", ROOT+"approval-reminders/"+fresh, map(), auth("admin")).statusCode());
    }
}
