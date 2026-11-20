package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseAcceptance.*;
import static ge.opensoft.openservice.TenJourneysAcceptance.post;
import static ge.opensoft.openservice.TenPolicyAcceptance.as;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;

final class DeliveryBusinessAcceptance {
    static Map<String, Object> def(Map<String, Object> row) {
        return object(row.get("definition"));
    }

    static void audits(WorkflowSupport h) throws Exception {
        h.account("Auditee", "agent");
        h.account("Reviewer", "admin");
        h.account("Other", "agent");
        String engagement = id(), sample = id(), owner = uid(h, "Auditee");
        for (String control : List.of("AC-3", "AC-6"))
            post(
                    h,
                    "audits",
                    map(
                            "operation",
                            "control",
                            "control_id",
                            control,
                            "title",
                            "Access management",
                            "framework",
                            "Internal access policy",
                            "owner_id",
                            owner,
                            "revision",
                            0));
        post(
                h,
                "audits",
                map(
                        "operation",
                        "engage",
                        "title",
                        "Quarterly review",
                        "new_id",
                        engagement,
                        "controls",
                        List.of("AC-3", "AC-6")));
        bad(
                h,
                "audits",
                "global",
                map(
                        "operation",
                        "engage",
                        "new_id",
                        id(),
                        "controls",
                        List.of(),
                        "title",
                        "No controls"),
                400);
        var common =
                map(
                        "engagement_id",
                        engagement,
                        "evidence_id",
                        sample,
                        "operation",
                        "request",
                        "owner_id",
                        owner,
                        "controls",
                        List.of("AC-3", "AC-6"),
                        "samples",
                        List.of("Request 45", "Request 88"),
                        "revision",
                        1);
        bad(
                h,
                "audits",
                "global",
                map("operation", "close", "engagement_id", engagement, "revision", 1),
                400);
        post(h, "audits", common);
        bad(h, "audits", "global", common, 400);
        var submit =
                map(
                        "engagement_id",
                        engagement,
                        "evidence_id",
                        sample,
                        "operation",
                        "submit",
                        "source",
                        "ledger/34",
                        "note",
                        "Access was reviewed",
                        "revision",
                        2);
        as(h, "Other", "audits", submit, 400);
        try (var db = h.store.open()) {
            db.viewerId = owner;
            db.viewerRole = "agent";
            AuditEngagements.run(h.store, db, "submit", submit);
            var saved = JourneyForms.definition(db, "audit-engagement", engagement);
            assertFalse(object(object(saved.get("evidence")).get(sample)).containsKey("content"));
        }
        submit.putAll(
                map(
                        "operation",
                        "capture",
                        "content",
                        "Request 45: approved. Request 88: provisioning pending."));
        as(h, "Auditee", "audits", submit, 200);
        as(h, "Auditee", "audits", submit, 400);
        post(
                h,
                "audits",
                map(
                        "engagement_id",
                        engagement,
                        "evidence_id",
                        sample,
                        "operation",
                        "approve",
                        "revision",
                        3));
        var test =
                map(
                        "engagement_id",
                        engagement,
                        "evidence_id",
                        sample,
                        "control_id",
                        "AC-3",
                        "operation",
                        "test",
                        "result",
                        "fail",
                        "due",
                        "2027-01-01",
                        "note",
                        "Access needs review",
                        "owner_id",
                        owner,
                        "revision",
                        4);
        rejectVariants(
                h,
                "audits",
                "global",
                test,
                List.of(map("control_id", "Unknown"), map("evidence_id", id())));
        post(h, "audits", test);
        test.put("revision", 5);
        bad(h, "audits", "global", test, 400);
        var inspect = map("operation", "inspect", "engagement_id", engagement);
        as(h, "Other", "audits", inspect, 400);
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            var record = JourneyForms.definition(db, "audit-engagement", engagement);
            var evidence = object(object(record.get("evidence")).get(sample));
            db.viewerId = owner;
            db.viewerRole = "admin";
            var validate = map("engagement_id", engagement, "evidence_id", sample, "revision", 5);
            assertThrows(
                    Failure.class, () -> AuditEngagements.run(h.store, db, "approve", validate));
            evidence.put("state", "submitted");
            EnterpriseData.save(db, "audit-engagement", engagement, 5, record);
            assertThrows(
                    Failure.class, () -> AuditEngagements.run(h.store, db, "approve", validate));
        }
        assertTrue(as(h, "Auditee", "audits", inspect, 200).containsKey("findings"));
        as(
                h,
                "Other",
                "audits",
                map(
                        "operation",
                        "remediate",
                        "engagement_id",
                        engagement,
                        "control_id",
                        "AC-3",
                        "note",
                        "Invalid",
                        "revision",
                        5),
                400);
        as(
                h,
                "Auditee",
                "audits",
                map(
                        "operation",
                        "remediate",
                        "engagement_id",
                        engagement,
                        "control_id",
                        "AC-3",
                        "note",
                        "Access removed",
                        "revision",
                        5),
                200);
        as(
                h,
                "Auditee",
                "audits",
                map(
                        "operation",
                        "remediate",
                        "engagement_id",
                        engagement,
                        "control_id",
                        "AC-3",
                        "note",
                        "Repeat",
                        "revision",
                        6),
                400);
        try (var db = h.store.open()) {
            db.viewerId = owner;
            db.viewerRole = "admin";
            assertThrows(Failure.class, () -> AuditEngagements.run(h.store, db, "test", test));
            PlatformFoundationTest.bind(db);
            var fixture = JourneyForms.definition(db, "audit-engagement", engagement);
            object(fixture.get("evidence")).clear();
            EnterpriseData.save(db, "audit-engagement", engagement, 6, fixture);
            db.viewerId = owner;
            db.viewerRole = "agent";
            assertFalse(
                    object(
                                    object(
                                                    AuditEngagements.run(
                                                            h.store,
                                                            db,
                                                            "inspect",
                                                            map("engagement_id", engagement)))
                                            .get("findings"))
                            .isEmpty());
        }
        test.putAll(map("revision", 6, "result", "pass"));
        as(h, "Reviewer", "audits", test, 200);
        test.putAll(map("revision", 7, "control_id", "AC-6"));
        post(h, "audits", test);
        var close = map("operation", "close", "engagement_id", engagement, "revision", 8);
        post(h, "audits", close);
        assertEquals("sealed", def(post(h, "audits", inspect)).get("state"));
        bad(h, "audits", "global", close, 400);
        var archive = post(h, "audits", map("operation", "package", "engagement_id", engagement));
        String content = archive.get("content").toString();
        assertEquals(
                org.apache.commons.codec.digest.DigestUtils.sha256Hex(content),
                archive.get("sha256"));
        assertTrue(content.contains("Request 88: provisioning pending"));
        String path = "/audit-evidence/" + engagement + ".json";
        var exported = h.get(path, auth("admin"));
        assertEquals(200, exported.statusCode());
        assertArrayEquals(
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8), exported.body());
        assertEquals(403, h.get(path, auth("Auditee")).statusCode());
    }

    static void workplace(WorkflowSupport h) throws Exception {
        var ids = EmployeeCasesAcceptance.setup(h);
        String hr = EmployeeCasesAcceptance.create(h, ids[0], "EmployeeOne");
        String day = LocalDate.now().plusDays(3).toString();
        var schedule =
                map(
                        "operation",
                        "schedule",
                        "case_id",
                        hr,
                        "type",
                        "move",
                        "effective",
                        day,
                        "space",
                        "Desk 23",
                        "access_team",
                        ids[1],
                        "equipment_team",
                        ids[1],
                        "workplace_team",
                        ids[1],
                        "revision",
                        0);
        rejectVariants(
                h,
                "workplace",
                "global",
                schedule,
                List.of(map("effective", "2000-01-01"), map("type", "other")));
        post(h, "workplace", schedule);
        bad(h, "workplace", "global", schedule, 400);
        var info = map("operation", "readiness", "case_id", hr);
        as(h, "HrOutsider", "workplace", info, 400);
        assertEquals(3, object(as(h, "HrAgent", "workplace", info, 200).get("tasks")).size());
        String another = EmployeeCasesAcceptance.create(h, ids[0], "EmployeeTwo");
        schedule.put("case_id", another);
        bad(h, "workplace", "global", schedule, 400);
        var complete =
                map(
                        "operation",
                        "complete",
                        "case_id",
                        hr,
                        "stream",
                        "access",
                        "note",
                        "Group membership granted",
                        "revision",
                        1);
        as(h, "HrOutsider", "workplace", complete, 400);
        as(h, "HrAgent", "workplace", complete, 200);
        as(h, "HrAgent", "workplace", complete, 400);
        bad(
                h,
                "workplace",
                "global",
                map("operation", "move", "case_id", hr, "effective", "2000-01-01", "revision", 2),
                400);
        post(
                h,
                "workplace",
                map(
                        "operation",
                        "move",
                        "case_id",
                        hr,
                        "effective",
                        LocalDate.now().plusDays(6).toString(),
                        "revision",
                        2));
        var third = EmployeeCasesAcceptance.create(h, ids[0], "EmployeeTwo");
        schedule.putAll(map("case_id", third, "space", "Desk 24"));
        post(h, "workplace", schedule);
        schedule.putAll(map("case_id", another, "space", "Desk 23"));
        var cancelled =
                def(
                        post(
                                h,
                                "workplace",
                                map(
                                        "operation",
                                        "cancel",
                                        "case_id",
                                        hr,
                                        "note",
                                        "Move withdrawn",
                                        "revision",
                                        3)));
        assertEquals(
                "reversal_review",
                object(object(cancelled.get("tasks")).get("access")).get("state"));
        bad(
                h,
                "workplace",
                "global",
                map("operation", "move", "case_id", hr, "effective", day, "revision", 4),
                400);
        String closed = EmployeeCasesAcceptance.create(h, ids[0], "EmployeeOne");
        as(h, "HrAgent", "workplace", map("operation", "readiness", "case_id", closed), 400);
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            for (String record : List.of(third, closed)) {
                var r = EmployeeCases.record(db, record, false);
                var fields = object(r.get("data"));
                fields.put("state", "closed");
                db.update(
                        "UPDATE os_record SET data=" + db.jsonParameter() + " WHERE sys_id=?",
                        JSON.writeValueAsString(fields),
                        record);
            }
            db.connection.commit();
        }
        var denied = new LinkedHashMap<>(complete);
        denied.putAll(map("case_id", third, "revision", 1));
        as(h, "HrAgent", "workplace", denied, 400);
        try (var db = h.store.open()) {
            String orphan = id();
            PlatformFoundationTest.bind(db);
            var detached = JourneyForms.definition(db, "workplace-event", third);
            EnterpriseData.save(db, "workplace-event", orphan, 0, detached);
            db.viewerRole = "agent";
            db.viewerId = RecordAccess.owner(db, "HrAgent");
            var command = new LinkedHashMap<>(denied);
            command.put("case_id", orphan);
            assertThrows(
                    Failure.class, () -> WorkplaceEvents.run(h.store, db, "complete", command));
        }
        var stopped = new LinkedHashMap<>(schedule);
        stopped.put("case_id", closed);
        bad(h, "workplace", "global", stopped, 400);
        post(h, "workplace", schedule);
        for (String stream : WorkplaceEvents.STREAMS) {
            complete.putAll(
                    map(
                            "case_id",
                            another,
                            "stream",
                            stream,
                            "revision",
                            WorkplaceEvents.STREAMS.indexOf(stream) + 1));
            as(h, "HrAgent", "workplace", complete, 200);
        }
        bad(
                h,
                "workplace",
                "global",
                map("operation", "move", "case_id", another, "effective", day, "revision", 4),
                400);
        assertEquals(
                "ready",
                def(post(h, "workplace", map("operation", "readiness", "case_id", another)))
                        .get("state"));
    }

    static void finance(WorkflowSupport h) throws Exception {
        String project = h.add("project").get("sys_id").toString(), order = id();
        var physical = AssetCustodyAcceptance.setup(h);
        post(h, "assets", order(order));
        post(h, "assets", map("operation", "approve", "order_id", order, "revision", 1));
        String unfunded = h.add("project").get("sys_id").toString(),
                foreign = h.add("project").get("sys_id").toString();
        String pending = id();
        post(h, "assets", order(pending));
        var tmp =
                map(
                        "operation",
                        "budget",
                        "project_id",
                        unfunded,
                        "currency",
                        "USD",
                        "amount",
                        "10",
                        "revision",
                        0);
        post(h, "finance", tmp);
        var held =
                map(
                        "operation",
                        "reserve",
                        "project_id",
                        unfunded,
                        "order_id",
                        pending,
                        "revision",
                        1);
        bad(h, "finance", "global", held, 400);
        held.put("order_id", order);
        bad(h, "finance", "global", held, 400);
        tmp.putAll(map("project_id", foreign, "currency", "EUR"));
        post(h, "finance", tmp);
        held.put("project_id", foreign);
        bad(h, "finance", "global", held, 400);
        var budget =
                map(
                        "operation",
                        "budget",
                        "project_id",
                        project,
                        "currency",
                        "USD",
                        "amount",
                        "2000",
                        "revision",
                        0);
        post(h, "finance", budget);
        var reserve =
                map(
                        "operation",
                        "reserve",
                        "project_id",
                        project,
                        "order_id",
                        order,
                        "revision",
                        1);
        var update = new LinkedHashMap<>(budget);
        update.put("revision", 1);
        rejectVariants(
                h,
                "finance",
                "global",
                update,
                List.of(map("currency", "usd"), map("currency", "EUR")));
        assertEquals("1200.00", post(h, "finance", reserve).get("committed"));
        post(h, "finance", reserve);
        update.put("amount", "100");
        bad(h, "finance", "global", update, 400);
        post(
                h,
                "assets",
                map(
                        "operation",
                        "receive",
                        "order_id",
                        order,
                        "revision",
                        2,
                        "asset_id",
                        physical[0],
                        "stockroom_id",
                        physical[1],
                        "received_on",
                        LocalDate.now().toString()));
        post(
                h,
                "assets",
                map(
                        "operation",
                        "invoice",
                        "order",
                        order,
                        "invoice",
                        "A-100",
                        "currency",
                        "USD",
                        "lines",
                        List.of(
                                map(
                                        "model",
                                        "Field laptop",
                                        "quantity",
                                        1,
                                        "unit_cost",
                                        "1200.00"))));
        String invoice = ConnectorSynchronization.hash(List.of("Local supplier <script>", "A-100"));
        String batch =
                objects(
                                def(post(
                                                h,
                                                "assets",
                                                map(
                                                        "operation",
                                                        "settle",
                                                        "invoice_id",
                                                        invoice,
                                                        "revision",
                                                        0)))
                                        .get("exports"))
                        .getFirst()
                        .get("export_id")
                        .toString();
        assertEquals(
                "1200.00",
                post(h, "finance", map("operation", "balance", "project_id", project))
                        .get("awaiting_posting"));
        var posting =
                map(
                        "operation",
                        "post",
                        "project_id",
                        project,
                        "invoice_id",
                        invoice,
                        "batch_id",
                        batch,
                        "reference",
                        "J-1",
                        "original",
                        "none",
                        "kind",
                        "charge",
                        "amount",
                        "1200");
        rejectVariants(
                h,
                "finance",
                "global",
                posting,
                List.of(map("amount", "0"), map("amount", "1199"), map("batch_id", id())));
        post(h, "finance", posting);
        assertEquals("0.00", post(h, "finance", posting).get("committed"));
        posting.putAll(
                map("reference", "C-1", "original", "J-1", "kind", "credit", "amount", "300"));
        post(h, "finance", posting);
        assertEquals("900.00", post(h, "finance", posting).get("actual"));
        posting.put("amount", "400");
        bad(h, "finance", "global", posting, 400);
        var entry = new LinkedHashMap<>(posting);
        entry.remove("project_id");
        entry.remove("operation");
        entry.putAll(map("reference", "C-2", "amount", "200"));
        var invalid = new LinkedHashMap<>(entry);
        invalid.putAll(map("reference", "C-3", "amount", "800"));
        var batchImport =
                map(
                        "operation",
                        "import",
                        "project_id",
                        project,
                        "entries",
                        List.of(entry, invalid));
        bad(h, "finance", "global", batchImport, 400);
        assertEquals(
                "1100.00",
                post(h, "finance", map("operation", "balance", "project_id", project))
                        .get("available"));
        batchImport.put("entries", List.of(entry, entry));
        assertEquals("1300.00", post(h, "finance", batchImport).get("available"));
        String second = h.add("project").get("sys_id").toString();
        budget.put("project_id", second);
        post(h, "finance", budget);
        assertEquals(
                "0",
                post(h, "finance", map("operation", "balance", "project_id", second))
                        .get("actual"));
        posting.put("project_id", second);
        bad(h, "finance", "global", posting, 400);
        held.put("project_id", second);
        bad(h, "finance", "global", held, 400);
    }

    static void sales(WorkflowSupport h) throws Exception {
        h.account("AssetKeeper", "agent");
        var customer = CustomerCasesAcceptance.setup(h);
        String entitlement = CustomerCasesAcceptance.entitlement(h, customer[0], 5),
                product = id(),
                sale = id();
        var config = map("name", "Rack", "surcharge", "50");
        var offer =
                map(
                        "operation",
                        "offer",
                        "product_id",
                        product,
                        "title",
                        "Field appliance",
                        "currency",
                        "USD",
                        "price",
                        "200",
                        "options",
                        List.of(config),
                        "limit",
                        10,
                        "revision",
                        0);
        rejectVariants(
                h,
                "sales",
                "global",
                offer,
                List.of(
                        map("options", List.of()),
                        map("options", List.of(config, config)),
                        map("currency", "usd")));
        post(h, "sales", offer);
        as(
                h,
                "CustomerAgent",
                "sales",
                map(
                        "operation",
                        "opportunity",
                        "account_id",
                        customer[0],
                        "entitlement_id",
                        entitlement,
                        "title",
                        "Install appliance",
                        "new_id",
                        sale),
                200);
        var quote =
                map(
                        "operation",
                        "quote",
                        "opportunity_id",
                        sale,
                        "product_id",
                        product,
                        "option",
                        "Rack",
                        "quantity",
                        2,
                        "discount",
                        20,
                        "revision",
                        1);
        rejectVariants(
                h,
                "sales",
                "global",
                quote,
                List.of(map("option", "Unavailable"), map("discount", 101)));
        as(h, "CustomerAgent", "sales", quote, 200);
        var accept = map("operation", "accept", "opportunity_id", sale, "revision", 2);
        as(h, "CustomerOne", "sales", accept, 400);
        post(h, "sales", map("operation", "approve", "opportunity_id", sale, "revision", 2));
        accept.put("revision", 3);
        var placed = def(as(h, "CustomerOne", "sales", accept, 200));
        post(h, "sales", map("operation", "inspect", "opportunity_id", sale));
        as(h, "CustomerTwo", "sales", accept, 400);
        var old = new LinkedHashMap<>(accept);
        old.put("revision", 2);
        as(h, "CustomerOne", "sales", old, 400);
        assertEquals("400.00", placed.get("total"));
        assertEquals(placed, def(as(h, "CustomerOne", "sales", accept, 200)));
        install(h, sale, placed, 4);
        assertEquals(
                entitlement,
                def(post(h, "sales", map("operation", "inspect", "opportunity_id", sale)))
                        .get("entitlement"));
        String initial = id();
        as(
                h,
                "CustomerAgent",
                "sales",
                map(
                        "operation",
                        "opportunity",
                        "account_id",
                        customer[0],
                        "entitlement_id",
                        "new",
                        "title",
                        "First purchase",
                        "new_id",
                        initial),
                200);
        quote.putAll(map("opportunity_id", initial, "discount", 5));
        as(h, "CustomerAgent", "sales", quote, 200);
        accept.putAll(map("opportunity_id", initial, "revision", 2));
        as(h, "CustomerOther", "sales", accept, 400);
        placed = def(as(h, "CustomerOne", "sales", accept, 200));
        assertTrue(placed.containsKey("installation_entitlement"));
        install(h, initial, placed, 3);
        String revised = id();
        post(
                h,
                "sales",
                map(
                        "operation",
                        "opportunity",
                        "account_id",
                        customer[0],
                        "entitlement_id",
                        entitlement,
                        "title",
                        "Revised sale",
                        "new_id",
                        revised));
        quote.putAll(map("opportunity_id", revised, "revision", 1, "discount", 40));
        post(h, "sales", quote);
        bad(
                h,
                "sales",
                "global",
                map("operation", "approve", "opportunity_id", revised, "revision", 2),
                400);
        quote.putAll(map("revision", 2, "discount", 0));
        var versions = def(post(h, "sales", quote));
        assertEquals(1, objects(versions.get("prior_quotes")).size());
        bad(
                h,
                "sales",
                "global",
                map("operation", "approve", "opportunity_id", revised, "revision", 3),
                400);
        bad(
                h,
                "sales",
                "global",
                map("operation", "accept", "opportunity_id", revised, "revision", 3),
                400);
        var finished =
                def(post(h, "sales", map("operation", "inspect", "opportunity_id", initial)));
        CustomerCasesAcceptance.contact(h, customer[0], "CustomerTwo", false);
        as(h, "CustomerTwo", "sales", map("operation", "inspect", "opportunity_id", initial), 400);
        try (var db = h.store.open()) {
            db.viewerId = RecordAccess.owner(db, "CustomerReader");
            db.viewerRole = "reader";
            assertThrows(Failure.class, () -> SalesOrders.account(db, customer[0]));
            PlatformFoundationTest.bind(db);
            while (db.count(
                            "SELECT COUNT(*) FROM os_customer_entitlement WHERE account_id=?",
                            customer[0])
                    < 20) SalesOrders.entitlement(db, finished, initial, true);
            assertThrows(Failure.class, () -> SalesOrders.entitlement(db, finished, initial, true));
        }
        assertNotEquals("new", finished.get("entitlement"));
        assertNotEquals(finished.get("installation_entitlement"), finished.get("entitlement"));
    }

    static void install(WorkflowSupport h, String sale, Map<String, Object> placed, int revision)
            throws Exception {
        String task = placed.get("installation_task").toString(),
                parent = placed.get("case_id").toString();
        String asset = AssetCustodyAcceptance.create(h, "SALE-" + sale.substring(0, 8));
        var install =
                map(
                        "operation",
                        "install",
                        "opportunity_id",
                        sale,
                        "asset_id",
                        asset,
                        "revision",
                        revision);
        bad(h, "sales", "global", install, 400);
        try (var db = h.store.open()) {
            db.viewerId = RecordAccess.owner(db, "CustomerAgent");
            db.viewerRole = "agent";
            for (String op : List.of("claim", "complete")) {
                var cmd =
                        map(
                                "operation",
                                op,
                                "task_id",
                                task,
                                "revision",
                                WorkTasks.task(db, parent, task).get("revision"),
                                "record_revision",
                                WorkTasks.record(db, "customer_case", parent).get("revision"));
                if (op.equals("complete")) cmd.put("outcome", "Installed and tested");
                WorkTasks.command(db, "customer_case", parent, cmd);
            }
            db.connection.commit();
        }
        assertEquals("installed", def(post(h, "sales", install)).get("state"));
    }
}
