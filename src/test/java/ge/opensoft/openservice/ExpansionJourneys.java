package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseAcceptance.*;
import static ge.opensoft.openservice.TenJourneysAcceptance.post;
import static ge.opensoft.openservice.TenPolicyAcceptance.as;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;

final class ExpansionJourneys {
    private ExpansionJourneys() {}

    static Map<String, Object> revision(Map<String, Object> body, int revision) {
        var changed = new LinkedHashMap<>(body);
        changed.put("revision", revision);
        return changed;
    }

    static void intake(WorkflowSupport h) throws Exception {
        h.account("Promoter", "admin");
        h.account("Employee", "requester");
        h.account("Peer", "requester");
        h.account("Lawyer", "agent");
        String group = h.add("sys_user_group").get("sys_id").toString(),
                proc = id(),
                design = id(),
                employee = uid(h, "Employee");
        RecordScopesAcceptance.member(h, group, uid(h, "Lawyer"), true);
        post(
                h,
                "access",
                map(
                        "operation",
                        "procedure",
                        "procedure_id",
                        proc,
                        "group_id",
                        group,
                        "kind",
                        "team_approval",
                        "title",
                        "Approve intake",
                        "revision",
                        0));
        var path =
                map(
                        "operation",
                        "path",
                        "design_id",
                        design,
                        "row_id",
                        "ordinary",
                        "title",
                        "Supplier onboarding",
                        "condition",
                        "default",
                        "operand",
                        "any",
                        "days",
                        3,
                        "steps",
                        List.of(map("procedure_id", proc, "stage", 1)),
                        "revision",
                        0);
        post(h, "designer", path);
        var high = revision(path, 1);
        high.putAll(
                map(
                        "row_id",
                        "large",
                        "condition",
                        "amount_at_least",
                        "operand",
                        "5000",
                        "steps",
                        List.of(
                                map("procedure_id", proc, "stage", 1),
                                map("procedure_id", proc, "stage", 1))));
        post(h, "designer", high);
        var publish = map("operation", "publish", "design_id", design, "revision", 2);
        bad(h, "designer", "global", publish, 400);
        var test =
                map(
                        "operation",
                        "test",
                        "row_id",
                        "ordinary",
                        "design_id",
                        design,
                        "amount",
                        "10",
                        "risk",
                        "low",
                        "location",
                        "HQ",
                        "revision",
                        2);
        post(h, "designer", test);
        test.putAll(map("row_id", "large", "amount", "4999", "revision", 3));
        bad(h, "designer", "global", test, 400);
        test.put("amount", "5000");
        post(h, "designer", test);
        post(h, "designer", revision(publish, 4));
        String run = id();
        post(
                h,
                "designer",
                map(
                        "operation",
                        "start",
                        "design_id",
                        design,
                        "user_id",
                        employee,
                        "new_id",
                        run,
                        "amount",
                        "5050",
                        "risk",
                        "high",
                        "location",
                        "HQ",
                        "revision",
                        1));
        String transfer = id();
        var second = revision(path, 0);
        second.putAll(
                map(
                        "design_id",
                        transfer,
                        "title",
                        "Employee transfer",
                        "steps",
                        List.of(
                                map("response", "Confirm the destination office", "stage", 1),
                                map("procedure_id", proc, "stage", 2))));
        post(h, "designer", second);
        var transferTest = revision(test, 1);
        transferTest.putAll(map("row_id", "ordinary", "design_id", transfer));
        post(h, "designer", transferTest);
        post(h, "designer", map("operation", "publish", "design_id", transfer, "revision", 2));
        String move = id();
        post(
                h,
                "designer",
                map(
                        "operation",
                        "start",
                        "design_id",
                        transfer,
                        "new_id",
                        move,
                        "user_id",
                        employee,
                        "amount",
                        "0",
                        "location",
                        "HQ",
                        "risk",
                        "medium",
                        "revision",
                        1));
        var reply =
                map(
                        "operation",
                        "advance",
                        "run_id",
                        move,
                        "step",
                        1,
                        "revision",
                        1,
                        "note",
                        "Confirmed location");
        as(h, "Lawyer", "studio", reply, 400);
        as(h, "Employee", "studio", reply, 200);
        as(
                h,
                "Lawyer",
                "studio",
                map(
                        "operation",
                        "advance",
                        "run_id",
                        move,
                        "step",
                        2,
                        "revision",
                        2,
                        "note",
                        "Transfer approved"),
                200);
        var changed = revision(high, 4);
        changed.putAll(map("condition", "location", "operand", "London"));
        post(h, "designer", changed);
        bad(h, "designer", "global", revision(publish, 5), 400);
        test.putAll(map("row_id", "ordinary", "location", "HQ", "revision", 5));
        post(h, "designer", test);
        test.putAll(map("row_id", "large", "location", "London", "revision", 6));
        post(h, "designer", test);
        post(h, "designer", revision(publish, 7));
        var snapshot = object(doc(h, "workflow-run", run).get("definition"));
        assertEquals(1, snapshot.get("version"));
        assertEquals("large", object(snapshot.get("decision")).get("row"));
        as(
                h,
                "Lawyer",
                "studio",
                map(
                        "operation",
                        "advance",
                        "run_id",
                        run,
                        "revision",
                        1,
                        "step",
                        1,
                        "note",
                        "Approved"),
                200);
        as(
                h,
                "Lawyer",
                "studio",
                map(
                        "operation",
                        "advance",
                        "run_id",
                        run,
                        "revision",
                        2,
                        "step",
                        2,
                        "note",
                        "Again"),
                400);
        try (var db = h.store.open()) {
            DecisionFlows.expire(db, System.currentTimeMillis() + 5 * 86400000L);
            db.connection.commit();
        }
        assertEquals(
                "timed_out", object(doc(h, "workflow-run", run).get("definition")).get("state"));
        String app = id();
        var build =
                map(
                        "operation",
                        "build",
                        "app_id",
                        app,
                        "title",
                        "Legal advice",
                        "group_id",
                        group,
                        "design_id",
                        design,
                        "fields",
                        List.of(
                                map(
                                        "name",
                                        "summary",
                                        "label",
                                        "Summary",
                                        "visibility",
                                        "requester"),
                                map(
                                        "name",
                                        "brief",
                                        "label",
                                        "Legal instructions",
                                        "visibility",
                                        "confidential")),
                        "revision",
                        0);
        post(h, "apps", build);
        var promote =
                map("operation", "promote", "app_id", app, "revision", 1, "release_revision", 0);
        bad(h, "apps", "global", promote, 400);
        as(h, "Promoter", "apps", promote, 200);
        String legal = id();
        as(
                h,
                "Employee",
                "apps",
                map(
                        "operation",
                        "submit",
                        "app_id",
                        app,
                        "new_id",
                        legal,
                        "entries",
                        List.of(
                                map("name", "summary", "value", "Contract review"),
                                map("name", "brief", "value", "Confidential advice")),
                        "amount",
                        "7500",
                        "location",
                        "HQ",
                        "risk",
                        "low",
                        "revision",
                        1),
                200);
        var get = map("operation", "case", "case_id", legal);
        assertFalse(as(h, "Employee", "apps", get, 200).toString().contains("Confidential advice"));
        assertTrue(as(h, "Lawyer", "apps", get, 200).toString().contains("Confidential advice"));
        as(h, "Peer", "apps", get, 400);
        as(
                h,
                "Lawyer",
                "apps",
                map(
                        "operation",
                        "note",
                        "case_id",
                        legal,
                        "revision",
                        1,
                        "note",
                        "Privileged work product"),
                200);
        build.putAll(map("revision", 1, "title", "Legal services"));
        post(h, "apps", build);
        promote.putAll(map("revision", 2, "release_revision", 1));
        as(h, "Promoter", "apps", promote, 409);
        post(
                h,
                "apps",
                map(
                        "operation",
                        "rebase",
                        "app_id",
                        app,
                        "revision",
                        2,
                        "release_revision",
                        1,
                        "confirm",
                        true));
        as(h, "Promoter", "apps", revision(promote, 3), 200);
        post(h, "apps", map("operation", "rollback", "app_id", app, "revision", 2));
        assertEquals(
                "Legal advice",
                as(h, "Employee", "apps", map("operation", "form", "app_id", app), 200)
                        .get("title"));
        assertEquals(1, object(as(h, "Employee", "apps", get, 200).get("case")).get("version"));
        assertEquals(
                1,
                objects(
                                as(
                                                h,
                                                "Lawyer",
                                                "apps",
                                                map("operation", "queue", "app_id", app),
                                                200)
                                        .get("cases"))
                        .size());
    }

    static void purchasing(WorkflowSupport h) throws Exception {
        h.account("SecurityOfficer", "agent");
        h.account("Counsel", "agent");
        String name = "Acme Ltd", order = id(), project = h.add("project").get("sys_id").toString();
        post(
                h,
                "suppliers",
                map(
                        "operation",
                        "request",
                        "supplier",
                        name,
                        "security_id",
                        uid(h, "SecurityOfficer"),
                        "legal_id",
                        uid(h, "Counsel"),
                        "finance_id",
                        uid(h, "admin")));
        bad(
                h,
                "suppliers",
                "global",
                map(
                        "operation",
                        "request",
                        "supplier",
                        "Duplicate reviewers",
                        "security_id",
                        uid(h, "Counsel"),
                        "legal_id",
                        uid(h, "Counsel"),
                        "finance_id",
                        uid(h, "admin")),
                400);
        var earlyRelease =
                map(
                        "operation",
                        "release",
                        "supplier",
                        name,
                        "revision",
                        1,
                        "project_id",
                        project,
                        "order_id",
                        order);
        bad(h, "suppliers", "global", earlyRelease, 400);
        bad(
                h,
                "suppliers",
                "global",
                map(
                        "operation",
                        "exception",
                        "supplier",
                        name,
                        "revision",
                        1,
                        "expires_on",
                        "2099-01-01",
                        "note",
                        "Premature exception"),
                400);
        var purchase = order(order);
        purchase.put("supplier", "ACME LTD");
        send(h, "assets", "global", purchase);
        bad(
                h,
                "assets",
                "global",
                map("operation", "approve", "order_id", order, "revision", 1),
                400);
        bad(h, "suppliers", "global", earlyRelease, 400);
        var assessment =
                map(
                        "operation",
                        "assess",
                        "supplier",
                        name,
                        "expires_on",
                        "2020-01-01",
                        "decision",
                        "pass",
                        "note",
                        "Review on file",
                        "revision",
                        1);
        as(h, "Counsel", "suppliers", assessment, 400);
        as(h, "SecurityOfficer", "suppliers", assessment, 200);
        as(
                h,
                "Counsel",
                "suppliers",
                map(
                        "operation",
                        "contract",
                        "supplier",
                        name,
                        "reference",
                        "MSA-1",
                        "note",
                        "Annual independent assessment required",
                        "revision",
                        2),
                200);
        var release =
                map(
                        "operation",
                        "release",
                        "supplier",
                        name,
                        "order_id",
                        order,
                        "project_id",
                        project,
                        "revision",
                        3);
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            String supplier = SupplierGovernance.identity(name);
            var details = JourneyForms.definition(db, "managed-supplier", supplier);
            var assessmentEvidence = details.remove("assessment");
            EnterpriseData.save(db, "managed-supplier", supplier, 3, details);
            assertThrows(
                    Failure.class,
                    () -> SupplierGovernance.command(db, "release", revision(release, 4)));
            details.put("assessment", assessmentEvidence);
            details.put("exception", map("expiry", "2020-01-01"));
            EnterpriseData.save(db, "managed-supplier", supplier, 4, details);
            assertThrows(
                    Failure.class,
                    () -> SupplierGovernance.command(db, "release", revision(release, 5)));
            assertThrows(
                    Failure.class,
                    () ->
                            SupplierGovernance.command(
                                    db,
                                    "exception",
                                    map(
                                            "supplier",
                                            name,
                                            "revision",
                                            5,
                                            "expires_on",
                                            "2020-01-02",
                                            "note",
                                            "Past exception")));
        }
        bad(h, "suppliers", "global", release, 400);
        post(
                h,
                "suppliers",
                map(
                        "operation",
                        "exception",
                        "supplier",
                        name,
                        "expires_on",
                        LocalDate.now().plusDays(2).toString(),
                        "note",
                        "Bridge signed renewal",
                        "revision",
                        3));
        var budget =
                map(
                        "operation",
                        "budget",
                        "project_id",
                        project,
                        "currency",
                        "USD",
                        "amount",
                        "500",
                        "revision",
                        0);
        post(h, "finance", budget);
        release.put("revision", 4);
        bad(h, "suppliers", "global", release, 400);
        assertEquals("draft", object(doc(h, "purchase", order).get("definition")).get("state"));
        budget.putAll(map("revision", 1, "amount", "1400"));
        post(h, "finance", budget);
        post(h, "suppliers", release);
        assertTrue(
                post(h, "suppliers", map("operation", "trace", "supplier", name))
                        .toString()
                        .contains("Bridge signed renewal"));
        assertEquals(
                "1200.00",
                object(doc(h, "purchase-reservation", order).get("definition")).get("amount"));
        var physical = AssetCustodyAcceptance.setup(h);
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
                        "AC-14",
                        "currency",
                        "USD",
                        "lines",
                        List.of(map("model", "Field laptop", "quantity", 1, "unit_cost", "1200"))));
        var invoice = ConnectorSynchronization.hash(List.of("ACME LTD", "AC-14"));
        var settlement =
                object(
                        post(
                                        h,
                                        "assets",
                                        map(
                                                "operation",
                                                "settle",
                                                "invoice_id",
                                                invoice,
                                                "revision",
                                                0))
                                .get("definition"));
        String batch = objects(settlement.get("exports")).getFirst().get("export_id").toString();
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
                        "GL-14",
                        "original",
                        "none",
                        "kind",
                        "charge",
                        "amount",
                        "1200");
        post(h, "finance", posting);
        posting.putAll(
                map("reference", "CR-14", "kind", "credit", "original", "GL-14", "amount", "250"));
        post(h, "finance", posting);
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            EnterpriseData.save(db, "supplier-purchase", id(), 0, map("supplier", "unrelated"));
            EnterpriseData.save(db, "invoice", id(), 0, map("order", "unrelated"));
            EnterpriseData.save(db, "posted-expense", id(), 0, map("invoice", "unrelated"));
            var only = object(SupplierGovernance.command(db, "trace", map("supplier", name)));
            assertEquals(1, object(only.get("orders")).size());
        }
        var trace = post(h, "suppliers", map("operation", "trace", "supplier", name));
        var bill =
                object(
                        object(object(object(trace.get("orders")).get(order)).get("invoices"))
                                .get(invoice));
        assertEquals(2, objects(bill.get("postings")).size());
        assertEquals("AC-14", object(bill.get("invoice")).get("invoice"));
        var second = order(id());
        second.put("supplier", name);
        send(h, "assets", "global", second);
        var future = LocalDate.now().plusYears(1).toString();
        assessment.putAll(map("revision", 4, "decision", "fail", "expires_on", future));
        as(h, "SecurityOfficer", "suppliers", assessment, 200);
        var next = revision(release, 5);
        next.put("order_id", second.get("key"));
        bad(h, "suppliers", "global", next, 400);
        var exception =
                map(
                        "operation",
                        "exception",
                        "supplier",
                        name,
                        "expires_on",
                        future,
                        "note",
                        "Review required",
                        "revision",
                        5);
        bad(h, "suppliers", "global", exception, 400);
        assessment.putAll(map("revision", 5, "decision", "pass"));
        as(h, "SecurityOfficer", "suppliers", assessment, 200);
        budget.putAll(map("revision", 2, "amount", "3000"));
        post(h, "finance", budget);
        post(h, "suppliers", revision(next, 6));
        bad(h, "suppliers", "global", revision(next, 6), 400);
    }

    static void pipeline(WorkflowSupport h) throws Exception {
        h.account("Pipeline", "agent");
        String ci = h.add("cmdb_ci").get("sys_id").toString(), policy = id();
        post(
                h,
                "releases",
                map(
                        "operation",
                        "policy",
                        "policy_id",
                        policy,
                        "title",
                        "Reviewed releases",
                        "service_ci",
                        ci,
                        "pipeline_id",
                        uid(h, "Pipeline"),
                        "limit",
                        5,
                        "revision",
                        0));
        bad(
                h,
                "releases",
                "global",
                map(
                        "operation",
                        "policy",
                        "policy_id",
                        id(),
                        "title",
                        "Self review",
                        "service_ci",
                        ci,
                        "pipeline_id",
                        uid(h, "admin"),
                        "limit",
                        5,
                        "revision",
                        0),
                400);
        var submit =
                map(
                        "operation",
                        "submit",
                        "policy_id",
                        policy,
                        "new_id",
                        id(),
                        "artifact",
                        "build-42",
                        "evidence",
                        "ci/test/42",
                        "rollback",
                        "restore build-41",
                        "impact_score",
                        1,
                        "likelihood",
                        2);
        var low = object(as(h, "Pipeline", "releases", submit, 200).get("definition"));
        assertEquals("authorized", low.get("state"));
        assertEquals(low, as(h, "Pipeline", "releases", submit, 200).get("definition"));
        String release = id();
        submit.putAll(map("new_id", release, "impact_score", 5));
        as(h, "Pipeline", "releases", submit, 200);
        var outcome =
                map(
                        "operation",
                        "outcome",
                        "release_id",
                        release,
                        "revision",
                        1,
                        "result",
                        "failed",
                        "reference",
                        "pipeline/run/43");
        as(h, "Pipeline", "releases", outcome, 400);
        var manual = map("release_id", release, "note", "Review", "revision", 1);
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            db.viewerId = RecordAccess.owner(db, "Pipeline");
            assertThrows(Failure.class, () -> PipelineReleases.command(db, "authorize", manual));
        }
        post(
                h,
                "releases",
                map(
                        "operation",
                        "authorize",
                        "release_id",
                        release,
                        "revision",
                        1,
                        "note",
                        "Change review completed"));
        bad(
                h,
                "releases",
                "global",
                ExpansionBoundaryAcceptance.with(manual, "operation", "authorize", "revision", 2),
                400);
        var failed =
                object(as(h, "Pipeline", "releases", revision(outcome, 2), 200).get("definition"));
        assertNotNull(failed.get("incident_id"));
        assertNotNull(failed.get("problem_id"));
        var recovered =
                object(
                        post(
                                        h,
                                        "releases",
                                        map(
                                                "operation",
                                                "recover",
                                                "release_id",
                                                release,
                                                "revision",
                                                3,
                                                "rollback",
                                                "redeployed build-41",
                                                "evidence",
                                                "service probes healthy"))
                                .get("definition"));
        assertEquals("recovered", recovered.get("state"));
        var lowId = object(low.get("submitted")).get("artifact");
        assertEquals("build-42", lowId);
        assertEquals(
                "recovered",
                object(
                                post(
                                                h,
                                                "releases",
                                                map("operation", "inspect", "release_id", release))
                                        .get("definition"))
                        .get("state"));
        h.account("OtherPipeline", "agent");
        as(h, "OtherPipeline", "releases", map("operation", "inspect", "release_id", release), 400);
        var mismatch = new LinkedHashMap<>(submit);
        mismatch.put("artifact", "different");
        as(h, "Pipeline", "releases", mismatch, 400);
        submit.putAll(map("new_id", id(), "impact_score", 1, "likelihood", 1));
        var ok = object(as(h, "Pipeline", "releases", submit, 200).get("definition"));
        assertEquals("approved", object(docChange(h, ok).get("data")).get("state"));
        var success =
                map(
                        "operation",
                        "outcome",
                        "release_id",
                        submit.get("new_id"),
                        "revision",
                        1,
                        "result",
                        "deployed",
                        "reference",
                        "pipeline/rolled-out");
        bad(h, "releases", "global", success, 400);
        String roster=id();var gate=map("operation","deployment_gate","release_id",submit.get("new_id"),"artifact","build-42");
        as(h,"Pipeline","releases",gate,400);
        try(var db=h.store.open()){
            String admin=PlatformFoundationTest.bind(db);
            EnterpriseData.save(db,"duty-roster",roster,0,map("anchor",0,"zone","UTC","hours",8,"people",List.of(admin,RecordAccess.owner(db,"Pipeline")),"overrides",List.of()));
            db.connection.commit();
        }
        var at=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        post(h,"releases",map("operation","cab_schedule","release_id",submit.get("new_id"),"roster_id",roster,"start",at.minusSeconds(60).toString(),"end",at.plusSeconds(600).toString(),"note","Reviewed production window","revision",0));
        post(h,"releases",map("operation","cab_approve","release_id",submit.get("new_id"),"note","Approved for deployment","revision",1));
        as(h,"Pipeline","releases",gate,200);
        success.put("operation","checked_outcome");success.put("artifact","build-42");
        as(h, "Pipeline", "releases", success, 200);
        Map<String, Object> schema;
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            PipelineReleases.transition(db, ok.get("change_id").toString(), "implement");
            schema = Service.schema(db, "change_request");
        }
        var guarded = new LinkedHashMap<>(schema);
        guarded.put("transition_rules", map("approved", map("min_approvals", 1)));
        ReferencePickerAcceptance.writeSchema(h, "change_request", guarded);
        try {
            submit.put("new_id", id());
            var held = object(as(h, "Pipeline", "releases", submit, 200).get("definition"));
            assertEquals("awaiting_review", held.get("state"));
            assertEquals("review", object(docChange(h, held).get("data")).get("state"));
        } finally {
            ReferencePickerAcceptance.writeSchema(h, "change_request", schema);
        }
    }

    static Map<String, Object> docChange(WorkflowSupport h, Map<String, Object> release)
            throws Exception {
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            return Service.readRecord(db, "change_request", release.get("change_id").toString());
        }
    }

    static void telecom(WorkflowSupport h) throws Exception {
        h.account("AssetKeeper", "agent");
        var customer = CustomerCasesAcceptance.setup(h);
        String entitlement = CustomerCasesAcceptance.entitlement(h, customer[0], 10),
                product = id(),
                bundle = id();
        post(
                h,
                "sales",
                map(
                        "operation",
                        "offer",
                        "product_id",
                        product,
                        "title",
                        "Broadband",
                        "currency",
                        "USD",
                        "price",
                        "60",
                        "options",
                        List.of(map("name", "Fiber", "surcharge", "5")),
                        "limit",
                        10,
                        "revision",
                        0));
        var sites = new ArrayList<Object>();
        for (String site : List.of("North", "South")) {
            String sale = id();
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
                            site,
                            "new_id",
                            sale));
            post(
                    h,
                    "sales",
                    map(
                            "operation",
                            "quote",
                            "opportunity_id",
                            sale,
                            "product_id",
                            product,
                            "option",
                            "Fiber",
                            "quantity",
                            1,
                            "discount",
                            0,
                            "revision",
                            1));
            sites.add(
                    map(
                            "site",
                            site,
                            "opportunity_id",
                            sale,
                            "revision",
                            2,
                            "lines",
                            List.of(
                                    map(
                                            "product_id",
                                            product,
                                            "option",
                                            "Fiber",
                                            "frequency",
                                            "monthly",
                                            "quantity",
                                            1))));
        }
        var draft = map("operation", "bundle", "new_id", bundle, "title", "Two branches");
        bad(
                h,
                "sales",
                "global",
                ExpansionBoundaryAcceptance.with(draft, "entries", List.of()),
                400);
        for (var patch : List.of(map("account", customer[1]), map("currency", "EUR"))) {
            try (var db = h.store.open()) {
                PlatformFoundationTest.bind(db);
                var entry = object(sites.getLast());
                String saleId = entry.get("opportunity_id").toString();
                var sale = JourneyForms.definition(db, "opportunity", saleId);
                sale.putAll(patch);
                EnterpriseData.save(db, "opportunity", saleId, 2, sale);
                var mixed =
                        List.of(
                                sites.getFirst(),
                                ExpansionBoundaryAcceptance.with(entry, "revision", 3));
                assertThrows(
                        Failure.class,
                        () ->
                                SiteOrders.command(
                                        h.store,
                                        db,
                                        "bundle",
                                        ExpansionBoundaryAcceptance.with(draft, "entries", mixed)));
            }
        }
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            var line = object(objects(object(sites.getFirst()).get("lines")).getFirst());
            assertThrows(Failure.class, () -> SiteOrders.line(db, line, "EUR"));
        }
        var duplicate = ExpansionBoundaryAcceptance.with(object(sites.getLast()), "site", "North");
        bad(
                h,
                "sales",
                "global",
                ExpansionBoundaryAcceptance.with(
                        draft, "entries", List.of(sites.getFirst(), duplicate)),
                400);
        post(
                h,
                "sales",
                map(
                        "operation",
                        "bundle",
                        "new_id",
                        bundle,
                        "title",
                        "Two branches",
                        "entries",
                        sites));
        as(
                h,
                "CustomerOne",
                "sales",
                map(
                        "operation",
                        "accept",
                        "opportunity_id",
                        object(sites.getFirst()).get("opportunity_id"),
                        "revision",
                        2),
                400);
        var amendment =
                map(
                        "operation",
                        "amend_site",
                        "bundle_id",
                        bundle,
                        "site",
                        "North",
                        "product_id",
                        product,
                        "option",
                        "Fiber",
                        "quantity",
                        1,
                        "frequency",
                        "once",
                        "revision",
                        1);
        bad(h, "sales", "global", amendment, 400);
        var accept = map("operation", "order_sites", "bundle_id", bundle, "revision", 1);
        as(h, "CustomerOther", "sales", accept, 400);
        var accepted = as(h, "CustomerOne", "sales", accept, 200);
        assertEquals(accepted, as(h, "CustomerOne", "sales", accept, 200));
        as(h, "CustomerOne", "sales", revision(accept, 2), 400);
        bad(h, "sales", "global", revision(accept, 1), 400);
        bad(
                h,
                "sales",
                "global",
                ExpansionBoundaryAcceptance.with(amendment, "revision", 2, "site", "Unknown site"),
                400);
        bad(
                h,
                "sales",
                "global",
                ExpansionBoundaryAcceptance.with(amendment, "revision", 2, "option", "Not offered"),
                400);
        as(
                h,
                "CustomerOne",
                "sales",
                map(
                        "operation",
                        "accept_site_change",
                        "bundle_id",
                        bundle,
                        "site",
                        "North",
                        "revision",
                        2),
                400);
        post(
                h,
                "sales",
                map(
                        "operation",
                        "amend_site",
                        "site",
                        "North",
                        "bundle_id",
                        bundle,
                        "product_id",
                        product,
                        "option",
                        "Fiber",
                        "frequency",
                        "once",
                        "quantity",
                        2,
                        "revision",
                        2));
        bad(h, "sales", "global", revision(amendment, 3), 400);
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            assertThrows(
                    Failure.class,
                    () ->
                            SiteOrders.command(
                                    h.store,
                                    db,
                                    "accept_site_change",
                                    map("bundle_id", bundle, "site", "North", "revision", 3)));
        }
        as(
                h,
                "CustomerOne",
                "sales",
                map(
                        "operation",
                        "accept_site_change",
                        "site",
                        "North",
                        "bundle_id",
                        bundle,
                        "revision",
                        3),
                200);
        var current =
                object(
                        as(
                                        h,
                                        "CustomerOne",
                                        "sales",
                                        map("operation", "sites", "bundle_id", bundle),
                                        200)
                                .get("sites"));
        var n = object(current.get("North"));
        var s = object(current.get("South"));
        assertNotEquals(n.get("case_id"), s.get("case_id"));
        assertEquals("195.00", n.get("one_time_charges"));
        assertEquals("65.00", s.get("monthly_charges"));
        String north = object(sites.getFirst()).get("opportunity_id").toString();
        DeliveryBusinessAcceptance.install(
                h, north, object(doc(h, "opportunity", north).get("definition")), 3);
        var updated =
                object(
                        as(
                                        h,
                                        "CustomerOne",
                                        "sales",
                                        map("operation", "sites", "bundle_id", bundle),
                                        200)
                                .get("sites"));
        assertEquals("installed", object(updated.get("North")).get("state"));
        assertEquals("ordered", object(updated.get("South")).get("state"));
        assertEquals(2, objects(object(n.get("quote")).get("lines")).size());
        assertEquals(1, objects(object(s.get("quote")).get("lines")).size());
    }
}
