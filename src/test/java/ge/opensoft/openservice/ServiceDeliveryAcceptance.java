package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseAcceptance.*;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;

final class ServiceDeliveryAcceptance {
    static Object call(WorkflowSupport h, String user, String area, Object... args)
            throws Exception {
        var response = h.json("POST", api(area, "global"), map(args), auth(user));
        assertEquals(200, response.statusCode(), html(response));
        return EmployeeCasesAcceptance.result(response).get("outcome");
    }

    static Map<String, Object> post(WorkflowSupport h, String area, Object... args)
            throws Exception {
        return object(call(h, "admin", area, args));
    }

    static String offering(WorkflowSupport h, String name) throws Exception {
        try (var db = h.store.open()) {
            String admin = PlatformFoundationTest.bind(db);
            var item = Catalogue.save(db, admin, "", CatalogueAcceptance.offering(name, 0, true));
            db.connection.commit();
            return item.get("offering_id").toString();
        }
    }

    static void guides(WorkflowSupport h) throws Exception {
        h.account("Manager", "requester");
        h.account("Other", "requester");
        h.account("Reviewer", "agent");
        String reviewer = uid(h, "Reviewer");
        String fulfillment = h.add("sys_user_group").get("sys_id").toString();
        String a = offering(h, "Laptop"),
                s = offering(h, "Software"),
                v = offering(h, "VPN"),
                guide = id(),
                order = id();
        try (var db = h.store.open()) {
            String admin = PlatformFoundationTest.bind(db);
            CatalogueRouting.save(db, admin, v, map("group_id", fulfillment, "revision", 1));
            db.connection.commit();
        }
        post(
                h,
                "guides",
                "operation",
                "define",
                "guide_id",
                guide,
                "title",
                "New hire",
                "revision",
                0,
                "entries",
                List.of(
                        map("offering_id", a, "role", "*", "location", "*"),
                        map(
                                "offering_id",
                                s,
                                "role",
                                "engineer",
                                "location",
                                "*",
                                "reviewer_id",
                                reviewer),
                        map(
                                "offering_id",
                                v,
                                "role",
                                "*",
                                "location",
                                "HQ",
                                "reviewer_id",
                                reviewer)));
        var preview =
                object(
                        call(
                                h,
                                "Manager",
                                "guides",
                                "operation",
                                "preview",
                                "guide_id",
                                guide,
                                "job_role",
                                "engineer",
                                "location",
                                "HQ"));
        assertEquals(3, object(preview.get("items")).size());
        assertEquals(
                1,
                object(
                                object(
                                                call(
                                                        h,
                                                        "Manager",
                                                        "guides",
                                                        "operation",
                                                        "preview",
                                                        "guide_id",
                                                        guide,
                                                        "job_role",
                                                        "buyer",
                                                        "location",
                                                        "EU"))
                                        .get("items"))
                        .size());
        var answers = map("revision", 1, "answers", map());
        h.account("Employee", "requester");
        String employee = uid(h, "Employee");
        post(
                h,
                "studio",
                "operation",
                "identity",
                "user_id",
                employee,
                "manager_id",
                uid(h, "Manager"),
                "directory_id",
                java.util.UUID.randomUUID().toString(),
                "revision",
                0);
        Object[] submit = {
            "operation",
            "order_for",
            "employee_id",
            employee,
            "guide_id",
            guide,
            "job_role",
            "engineer",
            "location",
            "HQ",
            "new_id",
            order,
            "details",
            "New hire",
            "revision",
            1,
            "manifest",
            map(a, answers, s, answers, v, map("revision", 2, "answers", map()))
        };
        assertEquals(call(h, "Manager", "guides", submit), call(h, "Manager", "guides", submit));
        var reordered = submit.clone();
        reordered[reordered.length - 1] =
                map(v, map("answers", map(), "revision", 2), s, answers, a, answers);
        assertEquals(call(h, "Manager", "guides", submit), call(h, "Manager", "guides", reordered));
        var track = object(call(h, "Manager", "guides", "operation", "track", "order_id", order));
        assertEquals(3, object(object(track.get("order")).get("items")).size());
        assertTrue(
                h.json(
                                        "POST",
                                        api("guides", "global"),
                                        map("operation", "track", "order_id", order),
                                        auth("Other"))
                                .statusCode()
                        >= 400);
        var employeeTrack =
                object(call(h, "Employee", "guides", "operation", "track", "order_id", order));
        assertEquals(3, objects(employeeTrack.get("items")).size());
        String laptop =
                object(object(object(track.get("order")).get("items")).get(a))
                        .get("request_id")
                        .toString();
        var stock = AssetCustodyAcceptance.setup(h);
        AssetCustodyAcceptance.receive(h, stock);
        post(
                h,
                "guides",
                "operation",
                "reserve_asset",
                "order_id",
                order,
                "offering_id",
                a,
                "asset_id",
                stock[0],
                "revision",
                1);
        String spare = AssetCustodyAcceptance.create(h, "INV-002");
        AssetCustodyAcceptance.receive(
                h, new String[] {spare, stock[1], h.add("cmdb_ci").get("sys_id").toString()});
        post(
                h,
                "guides",
                "operation",
                "reserve_asset",
                "order_id",
                order,
                "offering_id",
                s,
                "asset_id",
                spare,
                "revision",
                2);
        var directIssue = AssetCustodyAcceptance.body(h, spare, "issue");
        directIssue.put("username", "AssetUser");
        assertEquals(
                409,
                h.json("POST", AssetCustodyAcceptance.api(spare), directIssue, auth("AssetKeeper"))
                        .statusCode());
        for (String state : List.of("fulfilling", "complete"))
            assertEquals(
                    200,
                    h.json(
                                    "PATCH",
                                    "/api/now/table/sc_request/" + laptop,
                                    map("state", state),
                                    auth("admin"))
                            .statusCode());
        post(
                h,
                "guides",
                "operation",
                "ship",
                "order_id",
                order,
                "offering_id",
                a,
                "reference",
                "shipment-14",
                "revision",
                3);
        String access =
                object(object(object(track.get("order")).get("items")).get(v))
                        .get("request_id")
                        .toString();
        assertEquals(
                409,
                h.json(
                                "PATCH",
                                "/api/now/table/sc_request/" + access,
                                map("state", "fulfilling"),
                                auth("admin"))
                        .statusCode());
        assertEquals(
                "pending",
                object(object(object(track.get("order")).get("items")).get(v)).get("approval"));
        var review =
                object(
                        call(
                                h,
                                "Reviewer",
                                "guides",
                                "operation",
                                "decide_item",
                                "order_id",
                                order,
                                "offering_id",
                                v,
                                "decision",
                                "approved",
                                "note",
                                "Eligible",
                                "revision",
                                1));
        assertEquals("approved", review.get("approval"));
        assertFalse(review.containsKey("items"));
        try (var db = h.store.open()) {
            assertEquals(
                    fulfillment,
                    db.one("SELECT group_id FROM os_fulfilment WHERE request_id=?", access)
                            .get("group_id"));
        }
        assertEquals(
                200,
                h.json(
                                "PATCH",
                                "/api/now/table/sc_request/" + access,
                                map("state", "fulfilling"),
                                auth("admin"))
                        .statusCode());
        var result =
                object(
                        object(
                                        call(
                                                h,
                                                "Manager",
                                                "guides",
                                                "operation",
                                                "cancel",
                                                "order_id",
                                                order,
                                                "revision",
                                                5))
                                .get("definition"));
        assertEquals("cancelled", result.get("state"));
        try (var db = h.store.open()) {
            assertEquals(employee, AssetCustody.latest(db, stock[0]).get("assignee_id"));
        }
        AssetCustodyAcceptance.issue(h, spare);
        var items = object(result.get("items"));
        assertEquals("return_required", object(items.get(a)).get("reservation"));
        assertEquals("withdrawn", object(items.get(s)).get("approval"));
        assertEquals("released", object(items.get(v)).get("reservation"));
        assertEquals(
                "cancelled",
                object(call(h, "Employee", "guides", "operation", "track", "order_id", order))
                        .get("state"));
    }

    static void assistant(WorkflowSupport h) throws Exception {
        h.account("Employee", "requester");
        String offer = offering(h, "Remote access VPN"),
                group = h.add("sys_user_group").get("sys_id").toString(),
                article;
        try (var db = h.store.open()) {
            String admin = PlatformFoundationTest.bind(db);
            article =
                    KnowledgeSearchAcceptance.article(
                            db,
                            admin,
                            "Remote access policy",
                            "VPN access requires manager approval.");
            db.connection.commit();
        }
        post(
                h,
                "assistant",
                "operation",
                "intent",
                "new_id",
                id(),
                "terms",
                "VPN,work remotely,remote access",
                "article_id",
                article,
                "offering_id",
                offer,
                "group_id",
                group,
                "revision",
                0);
        post(h, "assistant", "operation", "fallback", "group_id", group, "revision", 0);
        String hrPolicy, policyOwner = uid(h, "admin");
        try (var db = h.store.open()) {
            String admin = PlatformFoundationTest.bind(db);
            hrPolicy =
                    KnowledgeSearchAcceptance.article(
                            db,
                            admin,
                            "HR salary appeals",
                            "Only the compensation team can review salary bands.");
            db.connection.commit();
        }
        post(
                h,
                "assistant",
                "operation",
                "intent",
                "new_id",
                id(),
                "terms",
                "salary bands",
                "article_id",
                hrPolicy,
                "offering_id",
                offer,
                "group_id",
                group,
                "revision",
                0);
        try (var db = h.store.open()) {
            db.update(
                    "INSERT INTO os_record_access(record_id,visibility,owner_id) VALUES(?,?,?) ON"
                            + " CONFLICT(record_id) DO UPDATE SET visibility=excluded.visibility",
                    hrPolicy,
                    "private",
                    policyOwner);
            db.connection.commit();
        }
        String session = id(), unknown = id(), privateCase = id(), employee = uid(h, "Employee");
        var expected =
                map(
                        "conversation",
                        session,
                        "employee",
                        employee,
                        "expected",
                        "submitted",
                        "offering_id",
                        offer,
                        "article_id",
                        article,
                        "group_id",
                        "",
                        "forbidden_articles",
                        List.of());
        var ambiguous =
                map(
                        "conversation",
                        unknown,
                        "employee",
                        employee,
                        "expected",
                        "handed_off",
                        "offering_id",
                        "",
                        "article_id",
                        "",
                        "group_id",
                        group,
                        "forbidden_articles",
                        List.of());
        var hidden = new LinkedHashMap<>(ambiguous);
        hidden.putAll(map("conversation", privateCase, "forbidden_articles", List.of(article)));
        String hrCase = id();
        var hrExpectation = new LinkedHashMap<>(ambiguous);
        hrExpectation.putAll(map("conversation", hrCase, "forbidden_articles", List.of(hrPolicy)));
        String suite = id();
        post(
                h,
                "assistant",
                "operation",
                "holdout",
                "new_id",
                suite,
                "cases",
                List.of(expected, ambiguous, hidden, hrExpectation));
        var before = post(h, "assistant", "operation", "score", "new_id", suite);
        assertEquals(0, before.get("verified_successes"));
        assertNull(before.get("p95_processing_ms"));
        var forbidden = new LinkedHashMap<>(expected);
        forbidden.put("forbidden_articles", List.of(article));
        String auditSuite = id();
        post(
                h,
                "assistant",
                "operation",
                "holdout",
                "new_id",
                auditSuite,
                "cases",
                List.of(forbidden));
        var suggested =
                object(
                        call(
                                h,
                                "Employee",
                                "assistant",
                                "operation",
                                "ask",
                                "question",
                                "How can I work remotely on Friday?",
                                "new_id",
                                session));
        assertEquals(article, object(suggested.get("citation")).get("article_id"));
        Object[] confirm = {
            "operation",
            "confirm_order",
            "new_id",
            session,
            "manifest",
            map(),
            "details",
            "Friday",
            "confirm",
            true
        };
        assertEquals(
                object(call(h, "Employee", "assistant", confirm)).get("request_id"),
                object(call(h, "Employee", "assistant", confirm)).get("request_id"));
        var flagged = post(h, "assistant", "operation", "score", "new_id", auditSuite);
        assertEquals(1, flagged.get("unauthorized_citations"));
        assertEquals(0, flagged.get("verified_successes"));
        bad(
                h,
                "assistant",
                "global",
                map("operation", "holdout", "new_id", id(), "cases", List.of(expected)),
                400);
        var ask =
                object(
                        call(
                                h,
                                "Employee",
                                "assistant",
                                "operation",
                                "ask",
                                "question",
                                "Where is the shuttle?",
                                "new_id",
                                unknown));
        assertEquals("handoff_required", ask.get("state"));
        var handoff =
                object(
                        call(
                                h,
                                "Employee",
                                "assistant",
                                "operation",
                                "handoff",
                                "new_id",
                                unknown,
                                "details",
                                "Please advise"));
        String adminId = uid(h, "admin");
        try (var db = h.store.open()) {
            assertEquals(
                    group,
                    db.one(
                                    "SELECT group_id FROM os_fulfilment WHERE request_id=?",
                                    handoff.get("request_id"))
                            .get("group_id"));
            db.update(
                    "INSERT INTO os_record_access(record_id,visibility,owner_id) VALUES(?,?,?) ON"
                            + " CONFLICT(record_id) DO UPDATE SET visibility=excluded.visibility",
                    article,
                    "private",
                    adminId);
            db.connection.commit();
        }
        var restricted =
                object(
                        call(
                                h,
                                "Employee",
                                "assistant",
                                "operation",
                                "ask",
                                "question",
                                "I need VPN",
                                "new_id",
                                privateCase));
        assertFalse(restricted.containsKey("citation"));
        assertEquals("handoff_required", restricted.get("state"));
        call(
                h,
                "Employee",
                "assistant",
                "operation",
                "handoff",
                "new_id",
                privateCase,
                "details",
                "Policy unavailable");
        var hrSession =
                object(
                        call(
                                h,
                                "Employee",
                                "assistant",
                                "operation",
                                "ask",
                                "question",
                                "Could I see the salary bands for my colleagues?",
                                "new_id",
                                hrCase));
        assertFalse(hrSession.containsKey("citation"));
        assertFalse(hrSession.toString().contains(hrPolicy));
        Object[] toDesk = {
            "operation",
            "handoff",
            "new_id",
            hrCase,
            "details",
            "Please route my question to a person."
        };
        var handed = object(call(h, "Employee", "assistant", toDesk));
        assertEquals(
                handed.get("request_id"),
                object(call(h, "Employee", "assistant", toDesk)).get("request_id"));
        var score = post(h, "assistant", "operation", "score", "new_id", suite);
        assertEquals(4, score.get("verified_successes"));
        assertEquals(3, score.get("correct_handoff_owner"));
        assertEquals(0, score.get("unauthorized_citations"));
        assertEquals(1, score.get("grounded"));
        assertEquals(1, score.get("grounding_trials"));
        assertEquals(0, score.get("model_cost_usd"));
        assertTrue(((Number) score.get("p95_processing_ms")).longValue() >= 0);
        try (var db = h.store.open()) {
            var file =
                    java.nio.file.Path.of(
                            "target",
                            "employee-assistant-holdout-"
                                    + (db.postgres ? "postgres" : "sqlite")
                                    + ".json");
            java.nio.file.Files.writeString(
                    file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(score));
        }
    }

    static String decision(WorkflowSupport h, String design) throws Exception {
        return decision(h, design, map("response", "Confirm approval", "stage", 1));
    }

    static String decision(WorkflowSupport h, String design, Map<String, Object> step)
            throws Exception {
        post(
                h,
                "designer",
                "operation",
                "path",
                "design_id",
                design,
                "title",
                "Supplier review",
                "row_id",
                "ordinary",
                "condition",
                "default",
                "operand",
                "any",
                "steps",
                List.of(step),
                "days",
                5,
                "revision",
                0);
        post(
                h,
                "designer",
                "operation",
                "test",
                "design_id",
                design,
                "row_id",
                "ordinary",
                "amount",
                "1",
                "risk",
                "low",
                "location",
                "HQ",
                "revision",
                1);
        post(h, "designer", "operation", "publish", "design_id", design, "revision", 2);
        return design;
    }

    static void flows(WorkflowSupport h) throws Exception {
        h.account("Reviewer", "agent");
        h.account("Legal", "agent");
        String group = h.add("sys_user_group").get("sys_id").toString(), procedure = id();
        RecordScopesAcceptance.member(h, group, uid(h, "Reviewer"), true);
        post(
                h,
                "access",
                "operation",
                "procedure",
                "procedure_id",
                procedure,
                "group_id",
                group,
                "kind",
                "team_approval",
                "title",
                "Approve supplier",
                "revision",
                0);
        String design = decision(h, id(), map("procedure_id", procedure, "stage", 1)),
                offer = offering(h, "Supplier provisioning"),
                name = "Paper Ltd",
                admin = uid(h, "admin");
        post(
                h,
                "designer",
                "operation",
                "trigger",
                "design_id",
                design,
                "offering_id",
                offer,
                "risk",
                "low",
                "amount",
                "10",
                "location",
                "HQ",
                "revision",
                0);
        post(
                h,
                "suppliers",
                "operation",
                "request",
                "supplier",
                name,
                "security_id",
                uid(h, "Reviewer"),
                "legal_id",
                uid(h, "Legal"),
                "finance_id",
                admin);
        call(
                h,
                "Reviewer",
                "suppliers",
                "operation",
                "assess",
                "supplier",
                name,
                "expires_on",
                LocalDate.now().plusMonths(6).toString(),
                "decision",
                "pass",
                "note",
                "Reviewed",
                "revision",
                1);
        call(
                h,
                "Legal",
                "suppliers",
                "operation",
                "contract",
                "supplier",
                name,
                "reference",
                "C-12",
                "note",
                "Executed",
                "revision",
                2);
        Object[] event = {
            "operation",
            "contract_event",
            "supplier",
            name,
            "design_id",
            design,
            "reference",
            "C-12"
        };
        assertEquals(call(h, "Legal", "designer", event), call(h, "Legal", "designer", event));
        var state =
                post(
                        h,
                        "designer",
                        "operation",
                        "flow_status",
                        "supplier",
                        name,
                        "design_id",
                        design);
        assertEquals("running", state.get("approval_state"));
        bad(
                h,
                "designer",
                "global",
                map("operation", "retry_provision", "supplier", name, "design_id", design),
                400);
        String run = SupplierFlows.instance(design, SupplierGovernance.identity(name));
        Map<String, Object> original;
        try (var db = h.store.open()) {
            original = Service.schema(db, "sc_request");
            var temporarilyClosed = new LinkedHashMap<>(original);
            temporarilyClosed.put("record_roles", map("create", List.of()));
            db.update(
                    "UPDATE os_schema SET definition="
                            + db.jsonParameter()
                            + " WHERE name='sc_request'",
                    JSON.writeValueAsString(temporarilyClosed));
            db.connection.commit();
        }
        call(
                h,
                "Reviewer",
                "studio",
                "operation",
                "advance",
                "run_id",
                run,
                "step",
                1,
                "revision",
                1,
                "note",
                "Approved");
        var pending = object(doc(h, "supplier-flow", run).get("definition"));
        assertEquals("provisioning_failed", pending.get("state"));
        assertEquals(
                "complete", object(doc(h, "workflow-run", run).get("definition")).get("state"));
        try (var db = h.store.open()) {
            db.update(
                    "UPDATE os_schema SET definition="
                            + db.jsonParameter()
                            + " WHERE name='sc_request'",
                    JSON.writeValueAsString(original));
            db.connection.commit();
        }
        var saved =
                post(
                        h,
                        "designer",
                        "operation",
                        "retry_provision",
                        "supplier",
                        name,
                        "design_id",
                        design);
        assertEquals("provisioned", object(saved.get("definition")).get("state"));
        assertEquals(
                saved,
                post(
                        h,
                        "designer",
                        "operation",
                        "retry_provision",
                        "supplier",
                        name,
                        "design_id",
                        design));

        String early = "Signed First";
        post(
                h,
                "suppliers",
                "operation",
                "request",
                "supplier",
                early,
                "security_id",
                uid(h, "Reviewer"),
                "legal_id",
                uid(h, "Legal"),
                "finance_id",
                admin);
        call(
                h,
                "Legal",
                "suppliers",
                "operation",
                "contract",
                "supplier",
                early,
                "reference",
                "C-early",
                "note",
                "Signed before risk review",
                "revision",
                1);
        call(
                h,
                "Reviewer",
                "suppliers",
                "operation",
                "assess",
                "supplier",
                early,
                "expires_on",
                LocalDate.now().plusMonths(3).toString(),
                "decision",
                "pass",
                "note",
                "Passed",
                "revision",
                2);
        String earlyRun = SupplierFlows.instance(design, SupplierGovernance.identity(early));
        var earlyFlow = object(doc(h, "supplier-flow", earlyRun).get("definition"));
        assertEquals("C-early", earlyFlow.get("contract"));
        assertFalse(earlyFlow.containsKey("request_id"));
        bad(
                h,
                "designer",
                "global",
                map(
                        "operation",
                        "contract_event",
                        "supplier",
                        early,
                        "design_id",
                        design,
                        "reference",
                        "C-invalid"),
                400);

        // Recover an approval that finished before its contract event was delivered.
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            var row = EnterpriseData.get(db, "supplier-flow", earlyRun);
            var stateBeforeEvent = object(row.get("definition"));
            stateBeforeEvent.remove("contract");
            EnterpriseData.save(
                    db, "supplier-flow", earlyRun, row.get("revision"), stateBeforeEvent);
            db.connection.commit();
        }
        call(
                h,
                "Reviewer",
                "studio",
                "operation",
                "advance",
                "run_id",
                earlyRun,
                "step",
                1,
                "revision",
                1,
                "note",
                "Approved");
        Object[] signed = {
            "operation",
            "contract_event",
            "supplier",
            early,
            "design_id",
            design,
            "reference",
            "C-early"
        };
        var delivered = object(call(h, "Legal", "designer", signed));
        assertEquals("provisioned", object(delivered.get("definition")).get("state"));
        assertEquals(delivered, call(h, "Legal", "designer", signed));
    }

    static void maps(WorkflowSupport h) throws Exception {
        for (String src : List.of("one", "two"))
            post(
                    h,
                    "topology",
                    "operation",
                    "inventory",
                    "source",
                    src,
                    "sequence",
                    1,
                    "resources",
                    List.of(
                            map(
                                    "id",
                                    "db",
                                    "title",
                                    "Database",
                                    "type",
                                    "Database",
                                    "depends_on",
                                    List.of())),
                    "services",
                    List.of(map("id", "site", "title", "Site", "roots", List.of("db"))));
        var health = post(h, "mapping", "operation", "health", "days", 2);
        assertEquals(3, objects(health.get("queue")).size());
        String from = ServiceTopology.identity("one", "db"),
                to = ServiceTopology.identity("two", "db");
        h.account("MapOwner", "agent");
        String owner = uid(h, "MapOwner");
        post(
                h,
                "mapping",
                "operation",
                "owner",
                "service",
                "one :: site",
                "owner_id",
                owner,
                "revision",
                0);
        var notAllowed =
                h.json(
                        "POST",
                        api("mapping", "global"),
                        map("operation", "preview_merge", "component", from, "target", to),
                        auth("MapOwner"));
        assertEquals(400, notAllowed.statusCode());
        post(
                h,
                "mapping",
                "operation",
                "owner",
                "service",
                "two :: site",
                "owner_id",
                owner,
                "revision",
                0);
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            var snapshot = JourneyForms.definition(db, "service-map", "one");
            snapshot.put("observed", LocalDate.now().minusDays(4) + " 01:00:00");
            EnterpriseData.save(db, "service-map", "one", 1, snapshot);
            db.connection.commit();
        }
        var issues =
                objects(
                        object(call(h, "MapOwner", "mapping", "operation", "health", "days", 2))
                                .get("queue"));
        assertTrue(issues.stream().anyMatch(r -> "stale_source".equals(r.get("kind"))));
        var preview =
                post(h, "mapping", "operation", "preview_merge", "component", from, "target", to);
        assertEquals(2, ((List<?>) preview.get("after")).size());
        assertEquals(1, ((List<?>) preview.get("before")).size());
        var merge =
                post(
                        h,
                        "mapping",
                        "operation",
                        "merge",
                        "component",
                        from,
                        "target",
                        to,
                        "fingerprint",
                        preview.get("fingerprint"));
        assertEquals(true, merge.get("merged"));
        post(
                h,
                "topology",
                "operation",
                "inventory",
                "source",
                "one",
                "sequence",
                2,
                "resources",
                List.of(
                        map(
                                "id",
                                "db",
                                "type",
                                "Database",
                                "title",
                                "Renamed database",
                                "depends_on",
                                List.of())),
                "services",
                List.of(map("id", "site", "title", "Site", "roots", List.of("db"))));
        try (var db = h.store.open()) {
            assertEquals(
                    List.of("one :: site", "two :: site"),
                    MapHealth.impact(FederatedMaps.graph(db), to));
        }
        assertTrue(
                objects(post(h, "mapping", "operation", "health", "days", 2).get("queue"))
                        .isEmpty());
    }

    static void assignment(WorkflowSupport h) throws Exception {
        h.account("One", "agent");
        h.account("Two", "agent");
        String record = h.add("incident").get("sys_id").toString();
        for (String name : List.of("One", "Two")) {
            post(
                    h,
                    "assignment",
                    "operation",
                    "capacity",
                    "agent_id",
                    uid(h, name),
                    "skills",
                    List.of("VPN"),
                    "capacity",
                    1,
                    "revision",
                    0);
            call(h, name, "assignment", "operation", "presence", "active", true);
        }
        post(
                h,
                "assignment",
                "operation",
                "route",
                "table",
                "incident",
                "record_id",
                record,
                "skill",
                "VPN",
                "due",
                Instant.now()
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                        .plusSeconds(3600)
                        .toString());
        assertEquals(
                1,
                ((List<?>)
                                call(
                                        h,
                                        "One",
                                        "assignment",
                                        "operation",
                                        "offers",
                                        "agent_id",
                                        uid(h, "One")))
                        .size());
        var action =
                map("operation", "accept", "table", "incident", "record_id", record, "revision", 1);
        try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results =
                    threads.invokeAll(
                            List.of(
                                    () ->
                                            h.json(
                                                            "POST",
                                                            api("assignment", "global"),
                                                            action,
                                                            auth("One"))
                                                    .statusCode(),
                                    () ->
                                            h.json(
                                                            "POST",
                                                            api("assignment", "global"),
                                                            action,
                                                            auth("Two"))
                                                    .statusCode()));
            int wins = 0;
            for (var result : results) if (result.get().equals(200)) wins++;
            assertEquals(1, wins);
        }
        var override =
                post(
                        h,
                        "assignment",
                        "operation",
                        "override",
                        "agent_id",
                        uid(h, "Two"),
                        "table",
                        "incident",
                        "record_id",
                        record,
                        "note",
                        "Override",
                        "revision",
                        2);
        assertEquals(uid(h, "Two"), object(override.get("definition")).get("owner"));
        post(
                h,
                "assignment",
                "operation",
                "auto_route",
                "table",
                "incident",
                "skill",
                "VPN",
                "minutes",
                30,
                "active",
                true,
                "revision",
                0);
        String urgent = h.add("incident").get("sys_id").toString();
        call(h, "One", "assignment", "operation", "presence", "active", false);
        var waiting = objects(call(h, "admin", "assignment", "operation", "waiting", "limit", 50));
        assertEquals(1, waiting.size());
        assertEquals(1, object(waiting.getFirst().get("unavailable")).get("offline"));
        assertEquals(1, object(waiting.getFirst().get("unavailable")).get("at_capacity"));
        call(h, "One", "assignment", "operation", "presence", "active", true);
        call(
                h,
                "One",
                "assignment",
                "operation",
                "decline",
                "table",
                "incident",
                "record_id",
                urgent,
                "revision",
                1);
        waiting = objects(call(h, "admin", "assignment", "operation", "waiting", "limit", 50));
        assertEquals(1, object(waiting.getFirst().get("unavailable")).get("declined"));
        assertTrue(
                objects(
                                call(
                                        h,
                                        "One",
                                        "assignment",
                                        "operation",
                                        "offers",
                                        "agent_id",
                                        uid(h, "One")))
                        .isEmpty());
        post(
                h,
                "assignment",
                "operation",
                "override",
                "agent_id",
                uid(h, "One"),
                "table",
                "incident",
                "record_id",
                urgent,
                "note",
                "Confirmed exception to decline",
                "revision",
                2);
    }

    static void connectors(WorkflowSupport h) throws Exception {
        try (var fixture = new ConnectorActionsAcceptance.Endpoint(h)) {
            fixture.provider.server.createContext(
                    "/pages",
                    x -> {
                        boolean last = x.getRequestURI().getQuery() != null;
                        byte[] response =
                                JSON.writeValueAsBytes(
                                        map(
                                                "results",
                                                List.of(
                                                        map(
                                                                "name",
                                                                last ? "second" : "first",
                                                                "secret",
                                                                "omit")),
                                                "next",
                                                last ? null : "/pages?p=2"));
                        x.sendResponseHeaders(200, response.length);
                        x.getResponseBody().write(response);
                        x.close();
                    });
            String read = id(), write = id();
            var spec =
                    map(
                            "method",
                            "GET",
                            "path",
                            "/pages",
                            "auth",
                            "bearer",
                            "inputs",
                            List.of(),
                            "outputs",
                            map("name", "string"),
                            "items",
                            "results",
                            "next",
                            "next");
            post(
                    h,
                    "packages",
                    "operation",
                    "rest_define",
                    "package_id",
                    read,
                    "title",
                    "Paginated import",
                    "manifest",
                    spec,
                    "revision",
                    0);
            post(
                    h,
                    "packages",
                    "operation",
                    "rest_bind",
                    "package_id",
                    read,
                    "environment",
                    "test",
                    "alias",
                    fixture.provider.alias,
                    "revision",
                    0);
            var page =
                    post(
                            h,
                            "packages",
                            "operation",
                            "rest_read",
                            "package_id",
                            read,
                            "environment",
                            "test",
                            "manifest",
                            map());
            assertEquals(2, page.get("pages"));
            assertEquals(List.of(map("name", "first"), map("name", "second")), page.get("rows"));
            spec.putAll(
                    map(
                            "method", "POST", "path", "/write", "outputs", map(), "items", "",
                            "next", ""));
            post(
                    h,
                    "packages",
                    "operation",
                    "rest_define",
                    "package_id",
                    write,
                    "title",
                    "Remote write",
                    "manifest",
                    spec,
                    "revision",
                    0);
            post(
                    h,
                    "packages",
                    "operation",
                    "rest_bind",
                    "package_id",
                    write,
                    "environment",
                    "test",
                    "alias",
                    fixture.provider.alias,
                    "revision",
                    0);
            String token = id();
            var preview =
                    post(
                            h,
                            "packages",
                            "operation",
                            "rest_preview",
                            "package_id",
                            write,
                            "environment",
                            "test",
                            "manifest",
                            map(),
                            "new_id",
                            token);
            Object[] cmd = {
                "operation",
                "rest_write",
                "package_id",
                write,
                "environment",
                "test",
                "manifest",
                map(),
                "new_id",
                token,
                "state",
                preview.get("state"),
                "preview_token",
                preview.get("preview_token"),
                "confirm",
                true
            };
            assertEquals(
                    post(h, "packages", cmd).get("job_id"), post(h, "packages", cmd).get("job_id"));
            assertTrue(
                    new WorkWorker(new Store(h.environment))
                            .runOne(System.currentTimeMillis() + 1000));
            assertEquals(1, fixture.calls.get());
            try (var db = h.store.open()) {
                String admin = PlatformFoundationTest.bind(db);
                Connectors.save(
                        h.store,
                        db,
                        admin,
                        map(
                                "alias",
                                fixture.provider.alias,
                                "origin",
                                fixture.provider.origin(),
                                "token",
                                "rotated-fixture-token"));
                db.connection.commit();
            }
            fixture.mode.set("close");
            String another = id();
            var rotated =
                    post(
                            h,
                            "packages",
                            "operation",
                            "rest_preview",
                            "package_id",
                            write,
                            "environment",
                            "test",
                            "manifest",
                            map(),
                            "new_id",
                            another);
            var job =
                    post(
                            h,
                            "packages",
                            "operation",
                            "rest_write",
                            "package_id",
                            write,
                            "environment",
                            "test",
                            "manifest",
                            map(),
                            "new_id",
                            another,
                            "state",
                            rotated.get("state"),
                            "preview_token",
                            rotated.get("preview_token"),
                            "confirm",
                            true);
            new WorkWorker(h.store).runOne(System.currentTimeMillis() + 2000);
            assertEquals("Bearer rotated-fixture-token", fixture.provider.authorization.get());
            assertEquals(2, fixture.calls.get());
            assertEquals(job.get("job_id"), fixture.key.get());
            var restart = new Store(h.environment);
            new WorkWorker(restart).runOne(System.currentTimeMillis() + 900000);
            try (var db = restart.open()) {
                PlatformFoundationTest.bind(db);
                var outcome =
                        ConnectorWrites.reconcile(
                                db,
                                fixture.key.get(),
                                map(
                                        "decision",
                                        "applied",
                                        "note",
                                        "Fixture journal contains the remote correlation key"));
                assertEquals("applied", object(outcome.get("resolution")).get("decision"));
                db.connection.commit();
            }
            assertEquals(2, fixture.calls.get());
        }
    }

    static void seats(WorkflowSupport h) throws Exception {
        h.account("Holder", "requester");
        h.account("Waiting", "requester");
        String holder = uid(h, "Holder"), license = id(), product = "Editor";
        String today = LocalDate.now().toString();
        post(
                h,
                "assets",
                "operation",
                "license",
                "key",
                license,
                "product",
                product,
                "seats",
                1,
                "expires_on",
                LocalDate.now().plusYears(1).toString());
        post(
                h,
                "assets",
                "operation",
                "allocate",
                "license_id",
                license,
                "user_id",
                holder,
                "revision",
                1);
        post(
                h,
                "assets",
                "operation",
                "seat_usage",
                "product",
                product,
                "source",
                "Vendor",
                "as_of",
                today,
                "observations",
                List.of(
                        map(
                                "user_id",
                                holder,
                                "last_active",
                                LocalDate.now().minusDays(100).toString())),
                "revision",
                0);
        post(
                h,
                "licenses",
                "operation",
                "notice",
                "product",
                product,
                "license_id",
                license,
                "user_id",
                holder);
        h.account("Departed", "requester");
        call(h, "Departed", "licenses", "operation", "waitlist", "license_id", license);
        try (var db = h.store.open()) {
            db.update("UPDATE os_user SET active=0 WHERE name=?", "Departed");
            db.connection.commit();
        }
        call(h, "Waiting", "licenses", "operation", "waitlist", "license_id", license);
        try (var provider = new RecordQuestionsAcceptance.Provider(h.store, h.environment)) {
            provider.settings.put("CONNECTOR_WRITES_ENABLED", "true");
            var assigned = new java.util.concurrent.atomic.AtomicBoolean(true);
            provider.server.createContext(
                    "/remove-seat",
                    x -> {
                        var payload =
                                object(
                                        JSON.readValue(
                                                x.getRequestBody().readAllBytes(), Object.class));
                        boolean match =
                                holder.equals(payload.get("user_id"))
                                        && product.equals(payload.get("product"));
                        if (match) assigned.set(false);
                        x.sendResponseHeaders(match ? 204 : 400, -1);
                        x.close();
                    });
            provider.server.createContext(
                    "/usage",
                    x -> {
                        byte[] report =
                                JSON.writeValueAsBytes(
                                        map(
                                                "product",
                                                product,
                                                "as_of",
                                                today,
                                                "observations",
                                                List.of(
                                                        map(
                                                                "user_id",
                                                                holder,
                                                                "last_active",
                                                                LocalDate.now()
                                                                        .minusDays(100)
                                                                        .toString()))));
                        x.sendResponseHeaders(200, report.length);
                        x.getResponseBody().write(report);
                        x.close();
                    });
            post(
                    h,
                    "licenses",
                    "operation",
                    "publisher",
                    "license_id",
                    license,
                    "alias",
                    provider.alias,
                    "usage_path",
                    "/usage",
                    "subscriber_path",
                    "/subscription/{user_id}",
                    "revision",
                    0);
            post(h, "licenses", "operation", "import_usage", "license_id", license, "revision", 1);
            provider.server.createContext(
                    "/subscription/",
                    x -> {
                        byte[] bytes =
                                JSON.writeValueAsBytes(
                                        map(
                                                "product",
                                                product,
                                                "user_id",
                                                holder,
                                                "assigned",
                                                assigned.get()));
                        x.sendResponseHeaders(200, bytes.length);
                        x.getResponseBody().write(bytes);
                        x.close();
                    });
            Object[] release = {
                "operation",
                "verify_release",
                "product",
                product,
                "license_id",
                license,
                "user_id",
                holder,
                "usage_revision",
                2,
                "revision",
                2
            };
            assertEquals(
                    1,
                    objects(call(h, "Holder", "licenses", "operation", "notices", "limit", 10))
                            .size());
            assertTrue(
                    objects(call(h, "Waiting", "licenses", "operation", "notices", "limit", 10))
                            .isEmpty());
            bad(h, "licenses", "global", map(release), 400);
            String action;
            try (var db = h.store.open()) {
                PlatformFoundationTest.bind(db);
                var input =
                        List.of(
                                map(
                                        "key",
                                        "product",
                                        "type",
                                        "string",
                                        "label",
                                        "Product",
                                        "required",
                                        true,
                                        "choices",
                                        List.of()),
                                map(
                                        "key",
                                        "user_id",
                                        "type",
                                        "string",
                                        "label",
                                        "User",
                                        "required",
                                        true,
                                        "choices",
                                        List.of()));
                action =
                        ConnectorActions.save(
                                        db,
                                        "",
                                        map(
                                                "name",
                                                "Remove subscription",
                                                "alias",
                                                provider.alias,
                                                "method",
                                                "POST",
                                                "path",
                                                "/remove-seat",
                                                "inputs",
                                                input,
                                                "active",
                                                true,
                                                "revision",
                                                0))
                                .get("action_id")
                                .toString();
                db.connection.commit();
            }
            post(
                    h,
                    "licenses",
                    "operation",
                    "bind_removal",
                    "license_id",
                    license,
                    "action_id",
                    action,
                    "revision",
                    1);
            String token = id();
            post(
                    h,
                    "licenses",
                    "operation",
                    "exception",
                    "license_id",
                    license,
                    "user_id",
                    holder,
                    "expires_on",
                    LocalDate.now().plusDays(1).toString(),
                    "note",
                    "Required for audit",
                    "revision",
                    1);
            bad(
                    h,
                    "licenses",
                    "global",
                    map(
                            "operation",
                            "preview_removal",
                            "license_id",
                            license,
                            "user_id",
                            holder,
                            "new_id",
                            token),
                    400);
            // Expiration represents the following day without changing the system clock.
            try (var db = h.store.open()) {
                PlatformFoundationTest.bind(db);
                String key = DeliveryWorkspace.key(license, holder);
                var notice = JourneyForms.definition(db, "seat-notice", key);
                notice.put("exception_until", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString());
                EnterpriseData.save(db, "seat-notice", key, 2, notice);
                db.connection.commit();
            }
            var preview =
                    post(
                            h,
                            "licenses",
                            "operation",
                            "preview_removal",
                            "license_id",
                            license,
                            "user_id",
                            holder,
                            "new_id",
                            token);
            Object[] remove = {
                "operation",
                "remove_at_provider",
                "license_id",
                license,
                "user_id",
                holder,
                "new_id",
                token,
                "state",
                preview.get("state"),
                "preview_token",
                preview.get("preview_token"),
                "confirm",
                true
            };
            assertEquals(
                    post(h, "licenses", remove).get("job_id"),
                    post(h, "licenses", remove).get("job_id"));
            assertTrue(new WorkWorker(h.store).runOne(System.currentTimeMillis() + 1000));
            assertFalse(assigned.get());
            var saved = post(h, "licenses", release);
            assertEquals(saved, post(h, "licenses", release));
            assertEquals(
                    List.of(uid(h, "Waiting")),
                    object(doc(h, "entitlement", license).get("definition")).get("users"));
            var licenseRow = doc(h, "entitlement", license);
            post(
                    h,
                    "assets",
                    "operation",
                    "release",
                    "license_id",
                    license,
                    "user_id",
                    uid(h, "Waiting"),
                    "revision",
                    licenseRow.get("revision"));
            post(
                    h,
                    "assets",
                    "operation",
                    "allocate",
                    "license_id",
                    license,
                    "user_id",
                    holder,
                    "revision",
                    doc(h, "entitlement", license).get("revision"));
            var rejoined =
                    object(
                            call(
                                    h,
                                    "Waiting",
                                    "licenses",
                                    "operation",
                                    "waitlist",
                                    "license_id",
                                    license));
            assertEquals("waiting", object(rejoined.get("definition")).get("state"));
            assertEquals(
                    rejoined,
                    call(h, "Waiting", "licenses", "operation", "waitlist", "license_id", license));
            var stats = post(h, "licenses", "operation", "savings", "license_id", license);
            assertEquals(1, stats.get("purchases_avoided"));
            assertEquals(List.of(), stats.get("posted_invoice_credits"));
            String ref = "CREDIT-204", batch = id(), invoice = id();
            try (var db = h.store.open()) {
                PlatformFoundationTest.bind(db);
                EnterpriseData.save(
                        db,
                        "posted-expense",
                        DeliveryWorkspace.key(ref),
                        0,
                        map(
                                "kind", "credit", "invoice", invoice, "batch", batch, "amount",
                                "24.00", "project", id()));
                EnterpriseData.save(
                        db,
                        "finance-settlement",
                        invoice,
                        0,
                        map(
                                "exports",
                                List.of(
                                        map(
                                                "export_id",
                                                batch,
                                                "currency",
                                                "USD",
                                                "lines",
                                                map(product, map("amount", "24.00"))))));
                db.connection.commit();
            }
            post(
                    h,
                    "licenses",
                    "operation",
                    "record_credit",
                    "license_id",
                    license,
                    "reference",
                    ref);
            var credited = post(h, "licenses", "operation", "savings", "license_id", license);
            assertEquals(
                    24.0,
                    ((Number) object(credited.get("invoice_savings_by_currency")).get("USD"))
                            .doubleValue());
        }
    }

    static String[] installedSites(WorkflowSupport h) throws Exception {
        var accounts = CustomerCasesAcceptance.setup(h);
        post(
                h,
                "topology",
                "operation",
                "inventory",
                "source",
                "prod",
                "sequence",
                1,
                "resources",
                List.of(
                        map(
                                "id",
                                "db",
                                "title",
                                "Customer DB",
                                "type",
                                "Database",
                                "depends_on",
                                List.of())),
                "services",
                List.of(map("id", "service", "title", "Service", "roots", List.of("db"))));
        for (int i = 0; i < 2; i++) {
            String account = accounts[i],
                    name = i == 0 ? "CustomerOne" : "CustomerOther",
                    contact = uid(h, name),
                    ent = CustomerCasesAcceptance.entitlement(h, account, 5),
                    op = id(),
                    bundle = id();
            try (var db = h.store.open()) {
                String admin = PlatformFoundationTest.bind(db);
                ServiceClocks.calendar(
                        db,
                        admin,
                        map(
                                "name",
                                "Business",
                                "calendar",
                                map(
                                        "zone",
                                        "UTC",
                                        "days",
                                        List.of(1, 2, 3, 4, 5),
                                        "start",
                                        "09:00",
                                        "end",
                                        "17:00",
                                        "holidays",
                                        List.of())));
                EnterpriseData.save(
                        db,
                        "site-bundle",
                        bundle,
                        0,
                        map("account", account, "sites", map("Office", map("opportunity", op))));
                EnterpriseData.save(
                        db,
                        "opportunity",
                        op,
                        0,
                        map(
                                "state",
                                "installed",
                                "account",
                                account,
                                "asset",
                                id(),
                                "entitlement",
                                ent));
                db.connection.commit();
            }
            post(
                    h,
                    "impact",
                    "operation",
                    "bind_site",
                    "site",
                    "Office",
                    "bundle_id",
                    bundle,
                    "service",
                    "prod :: service",
                    "entitlement_id",
                    ent,
                    "contact_id",
                    contact,
                    "calendar",
                    "Business",
                    "minutes",
                    30,
                    "revision",
                    0);
        }
        return accounts;
    }

    static void impact(WorkflowSupport h) throws Exception {
        var accounts=installedSites(h);
        long seconds = Instant.now().minusSeconds(10).getEpochSecond();
        Object[] signal = {
            "operation",
            "signals",
            "source",
            "prod",
            "resource_id",
            "db",
            "alerts",
            List.of(TenJourneysAcceptance.signal("availability", "alert-1", seconds, "down"))
        };
        var observed = post(h, "topology", signal);
        assertEquals(observed, post(h, "topology", signal));
        String outage = object(observed.get("definition")).get("episode").toString();
        var one = objects(call(h, "CustomerOne", "impact", "operation", "my_impact", "limit", 10));
        var other =
                objects(call(h, "CustomerOther", "impact", "operation", "my_impact", "limit", 10));
        assertEquals(1, one.size());
        assertEquals(1, other.size());
        assertNotEquals(one.getFirst().get("case_id"), other.getFirst().get("case_id"));
        String change = h.add("change_request").get("sys_id").toString();
        for (String stage : List.of("review", "approved"))
            ChangePlanningAcceptance.patch(h, change, map("state", stage), 200);
        var restored =
                post(
                        h,
                        "topology",
                        "operation",
                        "signals",
                        "source",
                        "prod",
                        "resource_id",
                        "db",
                        "alerts",
                        List.of(
                                TenJourneysAcceptance.signal(
                                        "availability", "alert-2", seconds + 2, "up")));
        post(
                h,
                "topology",
                "operation",
                "recover",
                "source",
                "prod",
                "resource_id",
                "db",
                "change_id",
                change,
                "note",
                "Restored and verified",
                "revision",
                restored.get("revision"));
        var restoredCase =
                objects(call(h, "CustomerOther", "impact", "operation", "my_impact", "limit", 1))
                        .getFirst();
        assertEquals("recovered", restoredCase.get("state"));
        assertTrue(restoredCase.get("recovery").toString().contains("Monitoring confirmed"));
        post(
                h,
                "impact",
                "operation",
                "recover",
                "new_id",
                outage,
                "note",
                "The database is available again.");
        var recovered =
                objects(call(h, "CustomerOne", "impact", "operation", "my_impact", "limit", 10));
        assertEquals("recovered", recovered.getFirst().get("state"));
        String key = id(), node = ServiceTopology.identity("prod", "db");
        CustomerCasesAcceptance.contact(h, accounts[1], "CustomerOther", false);
        Object[] secondEpisode = {
            "operation", "outage", "new_id", key, "component", node, "title", "Database maintenance"
        };
        var second = post(h, "impact", secondEpisode);
        assertEquals(second, post(h, "impact", secondEpisode));
        var detail = object(second.get("definition"));
        assertEquals(1, objects(detail.get("sites")).size());
        assertEquals(1, objects(detail.get("skipped")).size());
        bad(
                h,
                "impact",
                "global",
                map(
                        "operation",
                        "outage",
                        "new_id",
                        key,
                        "component",
                        node,
                        "title",
                        "Different problem"),
                400);

        String contact = uid(h, "CustomerOne");
        // A second incident may outlive its clock or the original contact's session eligibility.
        String maintenance = id();
        var independent = secondEpisode.clone();
        independent[3] = maintenance;
        var extra = post(h, "impact", independent);
        var extraSite = objects(object(extra.get("definition")).get("sites")).getFirst();
        long notices;
        try (var db = h.store.open()) {
            String admin = PlatformFoundationTest.bind(db);
            ServiceClocks.control(
                    db,
                    admin,
                    objects(detail.get("sites")).getFirst().get("sla_id").toString(),
                    "complete",
                    System.currentTimeMillis());
            db.update("DELETE FROM os_sla WHERE sla_id=?", extraSite.get("sla_id"));
            db.update("UPDATE os_user SET password_reset_required=1 WHERE user_id=?", contact);
            db.connection.commit();
            notices = db.count("SELECT COUNT(*) FROM os_notice WHERE user_id=?", contact);
        }
        post(
                h,
                "impact",
                "operation",
                "recover",
                "new_id",
                maintenance,
                "note",
                "Maintenance completed");
        try (var db = h.store.open()) {
            assertEquals(
                    notices, db.count("SELECT COUNT(*) FROM os_notice WHERE user_id=?", contact));
            db.update("UPDATE os_user SET password_reset_required=0 WHERE user_id=?", contact);
            db.connection.commit();
        }
        CustomerCasesAcceptance.manage(
                h,
                accounts[0],
                CustomerCasesAcceptance.accountBody(h, accounts[0], false, accounts[2]));
        post(
                h,
                "impact",
                "operation",
                "recover",
                "new_id",
                key,
                "note",
                "Support restored service");
        try (var db = h.store.open()) {
            assertEquals(
                    notices, db.count("SELECT COUNT(*) FROM os_notice WHERE user_id=?", contact));
        }
    }

    static void improvements(WorkflowSupport h) throws Exception {
        String change = id(), design = decision(h, id()), admin = uid(h, "admin");
        var now = LocalDate.now(ZoneOffset.UTC);
        for (int n = 0; n < 4; n++) {
            String record = h.add("incident").get("sys_id").toString();
            String day = now.minusDays(n < 2 ? 15 : 4).toString();
            String finish = n < 2 ? "00:30:00" : "00:10:00";
            try (var db = h.store.open()) {
                db.update(
                        "UPDATE os_event SET created=? WHERE record_id=? AND action='created'",
                        day + " 00:00:00",
                        record);
                String sql =
                        "INSERT INTO os_event(record_id,actor,action,created,detail)"
                                + " VALUES(?,'admin','updated',?,"
                                + db.jsonParameter()
                                + ")";
                db.update(
                        sql,
                        record,
                        day + " " + finish,
                        JSON.writeValueAsString(map("after", map("state", "6"))));
                if (n == 3)
                    db.update(
                            sql,
                            record,
                            day + " 00:15:00",
                            JSON.writeValueAsString(map("after", map("state", "2"))));
                db.connection.commit();
            }
        }
        var baseline =
                post(
                        h,
                        "mining",
                        "operation",
                        "baseline",
                        "new_id",
                        change,
                        "title",
                        "Approval wait",
                        "owner_id",
                        admin,
                        "table",
                        "incident",
                        "query",
                        "",
                        "before_start",
                        now.minusDays(15).toString(),
                        "days",
                        1,
                        "hours",
                        1,
                        "completed_state",
                        "6",
                        "limit",
                        5);
        assertEquals("baseline_frozen", object(baseline.get("definition")).get("state"));
        bad(
                h,
                "mining",
                "global",
                map(
                        "operation",
                        "publish_change",
                        "new_id",
                        change,
                        "design_id",
                        design,
                        "draft_revision",
                        2,
                        "revision",
                        1),
                400);
        post(
                h,
                "designer",
                "operation",
                "path",
                "design_id",
                design,
                "title",
                "Expedited requests",
                "row_id",
                "ordinary",
                "condition",
                "default",
                "operand",
                "any",
                "steps",
                List.of(map("response", "Expedited approval", "stage", 1)),
                "days",
                2,
                "revision",
                2);
        post(
                h,
                "designer",
                "operation",
                "test",
                "design_id",
                design,
                "row_id",
                "ordinary",
                "amount",
                "1",
                "risk",
                "low",
                "location",
                "HQ",
                "revision",
                3);
        post(
                h,
                "mining",
                "operation",
                "publish_change",
                "new_id",
                change,
                "design_id",
                design,
                "draft_revision",
                4,
                "revision",
                1);
        bad(
                h,
                "mining",
                "global",
                map(
                        "operation",
                        "measure",
                        "new_id",
                        change,
                        "after_start",
                        now.minusDays(4).toString(),
                        "revision",
                        2),
                400);
        // Advance the fixture's publication timestamp to exercise mature post-change cohorts.
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            var row = EnterpriseData.get(db, "improvement", change);
            var data = object(row.get("definition"));
            data.put("published_at", now.minusDays(7) + " 00:00:00");
            EnterpriseData.save(db, "improvement", change, row.get("revision"), data);
            db.connection.commit();
        }
        var result =
                post(
                        h,
                        "mining",
                        "operation",
                        "measure",
                        "new_id",
                        change,
                        "after_start",
                        now.minusDays(4).toString(),
                        "revision",
                        3);
        assertEquals(
                "insufficient_completed_cases",
                object(result.get("definition")).get("sample_status"));
        var observed = object(result.get("definition"));
        assertEquals(
                1800, object(observed.get("baseline")).get("mean_seconds_to_first_completion"));
        assertEquals(600, object(observed.get("outcome")).get("mean_seconds_to_first_completion"));
        assertEquals(0.5, object(observed.get("outcome")).get("reopen_rate"));
        assertEquals(result, post(h, "mining", "operation", "review", "new_id", change));
        var review =
                h.form(
                        "/enterprise/mining/global",
                        map("csrf", h.login("admin"), "operation", "review", "new_id", change),
                        null);
        assertEquals(200, review.statusCode(), html(review));
        assertTrue(html(review).contains("insufficient_completed_cases"));
        assertFalse(html(review).contains("<h2>Case cohorts</h2>"));
    }

    static void promotion(WorkflowSupport h) throws Exception {
        String app = id(), group = h.add("sys_user_group").get("sys_id").toString(), proc = id();
        post(
                h,
                "access",
                "operation",
                "procedure",
                "procedure_id",
                proc,
                "group_id",
                group,
                "kind",
                "team_approval",
                "title",
                "Legal approval",
                "revision",
                0);
        String design = decision(h, id(), map("procedure_id", proc, "stage", 1));
        post(
                h,
                "designer",
                "operation",
                "path",
                "design_id",
                design,
                "title",
                "Cross-team review",
                "row_id",
                "ordinary",
                "condition",
                "default",
                "operand",
                "any",
                "steps",
                List.of(
                        map("procedure_id", proc, "stage", 1),
                        map("response", "Acknowledge handoff", "stage", 2)),
                "days",
                5,
                "revision",
                2);
        post(
                h,
                "designer",
                "operation",
                "test",
                "design_id",
                design,
                "row_id",
                "ordinary",
                "amount",
                "1",
                "risk",
                "low",
                "location",
                "HQ",
                "revision",
                3);
        post(h, "designer", "operation", "publish", "design_id", design, "revision", 4);
        h.account("Publisher", "admin");
        post(
                h,
                "apps",
                "operation",
                "build",
                "app_id",
                app,
                "title",
                "Legal intake",
                "group_id",
                group,
                "fields",
                List.of(
                        map(
                                "name",
                                "matter",
                                "label",
                                "Private matter",
                                "visibility",
                                "confidential")),
                "design_id",
                design,
                "revision",
                0);
        call(
                h,
                "Publisher",
                "apps",
                "operation",
                "promote",
                "app_id",
                app,
                "revision",
                1,
                "release_revision",
                0);
        var pkg = post(h, "apps", "operation", "package_app", "app_id", app);
        for (String environment : List.of("test", "production")) {
            var file = java.nio.file.Files.createTempFile("openservice-" + environment, ".db");
            try {
                var instance =
                        new Store(
                                new org.springframework.mock.env.MockEnvironment()
                                        .withProperty("DATABASE_URL", "jdbc:sqlite:" + file)
                                        .withProperty("ADMIN_PASSWORD", PASSWORD)
                                        .withProperty(
                                                "CONNECTOR_ALLOWED_ORIGINS",
                                                "https://example.invalid")
                                        .withProperty("SECRET_KEY", id()));
                try (var db = instance.open()) {
                    String admin = PlatformFoundationTest.bind(db);
                    Service.createManagedAccount(
                            db,
                            map("name", "Publisher", "role", "admin", "password", PASSWORD),
                            admin);
                    String localTeam = PlatformFoundationTest.record(db, "sys_user_group", "Legal"),
                            localDesign = id();
                    Connectors.save(
                            instance,
                            db,
                            admin,
                            map(
                                    "alias",
                                    "local-binding",
                                    "origin",
                                    "https://example.invalid",
                                    "token",
                                    "qualification-fixture"));
                    var localBinding = ConnectorConsole.page(db, "local-binding");
                    for(String name:List.of("Requester","Reviewer","Outsider"))
                        Service.createAccount(db,map("name",name,"role",name.equals("Reviewer")?"agent":"requester","password",PASSWORD));
                    String reviewer=RecordAccess.owner(db,"Reviewer");
                    db.update("INSERT INTO os_group_member(group_id,user_id) VALUES(?,?)",localTeam,reviewer);
                    AppReleaseTests.configure(db,map("app_id",app,"private_field","matter","requester",RecordAccess.owner(db,"Requester"),"reviewer",reviewer,"outsider",RecordAccess.owner(db,"Outsider"),"revision",0));
                    var transfer =
                            map(
                                    "app_id",
                                    app,
                                    "manifest",
                                    pkg,
                                    "group_id",
                                    localTeam,
                                    "design_id",
                                    localDesign,
                                    "bindings",
                                    map());
                    var compare =
                            object(PortableApps.command(instance, db, "compare_package", transfer));
                    assertTrue(object(compare.get("changes")).containsKey("workflow_dependency"));
                    var stageBeforeInstall = new LinkedHashMap<>(transfer);
                    stageBeforeInstall.putAll(map("release_revision", 0, "revision", 0));
                    assertThrows(
                            Failure.class,
                            () ->
                                    PortableApps.command(
                                            instance, db, "stage_package", stageBeforeInstall));
                    PortableApps.command(instance, db, "install_dependency", transfer);
                    assertThrows(
                            Failure.class,
                            () ->
                                    PortableApps.command(
                                            instance, db, "install_dependency", transfer));
                    transfer.putAll(map("revision", 0, "release_revision", 0));
                    PortableApps.command(instance, db, "stage_package", transfer);
                    db.viewerId = RecordAccess.owner(db, "Publisher");
                    AppReleaseTests.promote(db,map("app_id", app, "revision", 1, "release_revision", 0));
                    String openCase = id();
                    IntakeApps.command(
                            db,
                            "submit",
                            map(
                                    "app_id",
                                    app,
                                    "new_id",
                                    openCase,
                                    "entries",
                                    List.of(map("name", "matter", "value", "Sensitive")),
                                    "revision",
                                    1,
                                    "risk",
                                    "low",
                                    "location",
                                    "HQ",
                                    "amount",
                                    "0"));
                    db.viewerId = admin;
                    var changed = new LinkedHashMap<>(pkg);
                    changed.put("title", "Changed");
                    transfer.putAll(map("manifest", changed, "revision", 1, "release_revision", 0));
                    assertThrows(
                            Failure.class,
                            () -> PortableApps.command(instance, db, "stage_package", transfer));
                    transfer.put("release_revision", 1);
                    PortableApps.command(instance, db, "stage_package", transfer);
                    db.viewerId = RecordAccess.owner(db, "Publisher");
                    IntakeApps.command(
                            db,
                            "promote",
                            map("app_id", app, "revision", 2, "release_revision", 1));
                    IntakeApps.command(db, "rollback", map("app_id", app, "revision", 2));
                    var preserved = JourneyForms.definition(db, "intake-case", openCase);
                    assertEquals(
                            "Sensitive", object(preserved.get("private_answers")).get("matter"));
                    assertEquals(localTeam, preserved.get("group"));
                    assertEquals(localBinding, ConnectorConsole.page(db, "local-binding"));
                    var release = JourneyForms.definition(db, "decision-release", localDesign);
                    assertEquals(
                            localTeam,
                            objects(objects(release.get("rows")).getFirst().get("steps"))
                                    .getFirst()
                                    .get("group"));
                    var localExport=PortableApps.export(db,app);
                    assertEquals(pkg.get("title"),localExport.get("title"));
                    db.connection.commit();
                }
            } finally {
                java.nio.file.Files.deleteIfExists(file);
            }
        }
    }

    static void events(WorkflowSupport h) throws Exception {
        String design = decision(h, id()),
                offering = offering(h, "Provision"),
                group = h.add("sys_user_group").get("sys_id").toString();
        try (var db = h.store.open()) {
            var schema = Service.schema(db, "incident");
            var rules = new LinkedHashMap<>(TypedFields.rules(schema));
            rules.put("assignment_group", map("type", "reference", "target", "sys_user_group"));
            schema.put("field_rules", rules);
            db.update(
                    "UPDATE os_schema SET definition="
                            + db.jsonParameter()
                            + " WHERE name='incident'",
                    JSON.writeValueAsString(schema));
            db.connection.commit();
        }
        post(
                h,
                "designer",
                "operation",
                "on_record",
                "title",
                "Provision on state change",
                "table",
                "incident",
                "event",
                "entered",
                "state",
                "2",
                "design_id",
                design,
                "bindings",
                map(
                        "amount",
                        map("field", "priority"),
                        "risk",
                        map("literal", "low"),
                        "location",
                        map("via", "assignment_group", "field", "short_description")),
                "wait_event",
                "signed",
                "offering_id",
                offering);
        String incident = h.add("incident").get("sys_id").toString();
        assertEquals(
                200,
                h.json(
                                "PATCH",
                                "/api/now/table/incident/" + incident,
                                map("assignment_group", group, "state", "2"),
                                auth("admin"))
                        .statusCode());
        assertTrue(new WorkWorker(h.store).runOne(System.currentTimeMillis() + 1000));
        String run;
        try (var db = h.store.open()) {
            run =
                    db.one("SELECT run_id FROM os_event_run WHERE record_id=?", incident)
                            .get("run_id")
                            .toString();
        }
        var resume = map("operation", "continue_record", "run_id", run);
        bad(h, "designer", "global", resume, 400);
        bad(
                h,
                "designer",
                "global",
                map(
                        "operation",
                        "flow_event",
                        "run_id",
                        run,
                        "event",
                        "unsigned",
                        "reference",
                        "signature-1"),
                400);
        Object[] callback = {
            "operation", "flow_event", "run_id", run, "event", "signed", "reference", "signature-1"
        };
        assertEquals(post(h, "designer", callback), post(h, "designer", callback));
        bad(h, "designer", "global", resume, 400);
        bad(
                h,
                "designer",
                "global",
                map(
                        "operation",
                        "flow_event",
                        "run_id",
                        run,
                        "event",
                        "signed",
                        "reference",
                        "other-signature"),
                400);
        h.account("OtherAdmin", "admin");
        assertEquals(
                400,
                h.json("POST", api("designer", "global"), resume, auth("OtherAdmin")).statusCode());
        post(
                h,
                "studio",
                "operation",
                "advance",
                "run_id",
                run,
                "step",
                1,
                "revision",
                1,
                "note",
                "Reviewed");
        var result = post(h, "designer", "operation", "continue_record", "run_id", run);
        assertEquals("provisioned", object(result.get("definition")).get("state"));
        assertEquals("ordinary", object(result.get("definition")).get("selected_row"));
    }
}
