package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

class ServiceOperationsReviewTest {
    @TempDir Path root;
    Store store;
    Store.Db db;
    String admin;

    @BeforeEach
    void open() throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        store = new Store(fixture.configuration(false));
        db = store.open();
        admin = PlatformFoundationTest.bind(db);
    }

    @AfterEach
    void close() throws Exception {
        db.close();
    }

    void seed(String area, String key, Map<String, Object> value) throws Exception {
        EnterpriseData.save(
                db, area, key, EnterpriseData.get(db, area, key).get("revision"), value);
    }

    Object invoke(String area, String op, Map<String, Object> args) throws Exception {
        var entry = new DeliveryWorkspace.Entry(area, op, "", "", "admin");
        return ServiceDelivery.command(store, db, entry, args);
    }

    Failure rejects(String area, String op, Map<String, Object> args) {
        return assertThrows(Failure.class, () -> invoke(area, op, args));
    }

    @Test
    void checkoutRejectsMismatchedOperationsAndBindsManagedEmployee() {
        String employee = id();
        var form =
                map(
                        "forms",
                        List.of(
                                map("operation", "order", "fields", List.of()),
                                map("operation", "confirm_order", "fields", List.of())));
        var managed =
                EnterpriseWorkspaces.form(
                        form,
                        Map.of("operation", "order", "checkout", "guide", "employee_id", employee));
        assertEquals("order_for", managed.get("operation"));
        var personal =
                EnterpriseWorkspaces.form(
                        form, Map.of("operation", "order", "checkout", "guide", "employee_id", ""));
        assertEquals("order", personal.get("operation"));
        assertFalse(personal.containsKey("employee_id"));
        for (var args :
                List.of(
                        Map.of("operation", "order", "checkout", "conversation"),
                        Map.of("operation", "confirm_order", "checkout", "guide"),
                        Map.of("operation", "order", "checkout", "unknown")))
            assertThrows(Failure.class, () -> EnterpriseWorkspaces.form(form, args));
        assertEquals(404, rejects("unknown", "read", map()).status);
    }

    @Test
    void scorerDoesNotAcceptPlausibleButUnverifiedWork() throws Exception {
        var cases = new ArrayList<Object>();
        String offer = id(), article = id(), group = id(), suite = id();
        for (String defect :
                List.of(
                        "wrong_article",
                        "wrong_item",
                        "awaiting_confirmation",
                        "order_missing",
                        "handoff_pending",
                        "handoff_missing")) {
            String conversation = id();
            boolean handoff = defect.startsWith("handoff");
            var expected =
                    map(
                            "conversation",
                            conversation,
                            "employee",
                            admin,
                            "expected",
                            handoff ? "handed_off" : "submitted",
                            "offering_id",
                            offer,
                            "article_id",
                            article,
                            "group_id",
                            group,
                            "forbidden_articles",
                            List.of());
            cases.add(expected);
            var session =
                    map(
                            "owner",
                            admin,
                            "citation",
                            map(
                                    "article_id",
                                    defect.equals("wrong_article") ? id() : article,
                                    "offering_id",
                                    defect.equals("wrong_item") ? id() : offer),
                            "state",
                            switch (defect) {
                                case "handoff_missing" -> "handed_off";
                                case "handoff_pending" -> "handoff_required";
                                case "awaiting_confirmation" -> "confirmation_required";
                                default -> "submitted";
                            },
                            "request_id",
                            id());
            seed("employee-conversation", conversation, session);
        }
        seed("employee-holdout", suite, map("cases", cases));
        var score = object(invoke("assistant", "score", map("new_id", suite)));
        assertEquals(0, score.get("verified_successes"));
        assertEquals(0, score.get("correct_handoff_owner"));
        assertEquals(2, score.get("grounded"));
    }

    @Test
    void deskOffersRespectCurrentPeopleAndRecordState() throws Exception {
        String record = PlatformFoundationTest.record(db, "incident", "Urgent incident");
        var work =
                map(
                        "record_id",
                        record,
                        "table",
                        "incident",
                        "skill",
                        "storage",
                        "declined",
                        List.of(),
                        "due",
                        123);
        var item = Service.readRecord(db, "incident", record);
        assertEquals("inactive", DeskAssignment.availability(db, id(), work, Map.of(), item));
        for (var state :
                List.of(
                        map("active", 0, "reset", 0, "role", "admin"),
                        map("active", 1, "reset", 1, "role", "admin"),
                        map("active", 1, "reset", 0, "role", "reader"))) {
            db.update(
                    "UPDATE os_user SET active=?,password_reset_required=?,role=? WHERE user_id=?",
                    state.get("active"),
                    state.get("reset"),
                    state.get("role"),
                    admin);
            assertEquals("inactive", DeskAssignment.availability(db, admin, work, Map.of(), item));
        }
        db.update(
                "UPDATE os_user SET active=1,password_reset_required=0,role='admin' WHERE"
                        + " user_id=?",
                admin);
        assertEquals("missing_skill", DeskAssignment.availability(db, admin, work, Map.of(), item));
        seed("desk-offer", record, work);
        db.viewerRole = "agent";
        assertEquals(400, rejects("assignment", "offers", map("agent_id", id())).status);
        db.viewerRole = "admin";
        assertEquals(List.of(), invoke("assignment", "offers", map("agent_id", id())));
        var data = object(item.get("data"));
        data.put("state", "6");
        db.update(
                "UPDATE os_record SET data=" + db.jsonParameter() + " WHERE sys_id=?",
                JSON.writeValueAsString(data),
                record);
        assertEquals(List.of(), invoke("assignment", "waiting", map("limit", 5)));
        assertEquals(
                400,
                rejects(
                                "assignment",
                                "accept",
                                map("table", "incident", "record_id", record, "revision", 1))
                        .status);
        work.put("owner", admin);
        seed("desk-offer", record, work);
        assertEquals(Map.of(), DeskAssignment.loads(db));
    }

    @Test
    void queueReadsHideUnavailableRecordsAndPropagateUnexpectedErrors() throws Exception {
        String record = id(), outage = id();
        seed(
                "desk-offer",
                record,
                map(
                        "table",
                        "incident",
                        "record_id",
                        record,
                        "skill",
                        "storage",
                        "declined",
                        List.of(),
                        "due",
                        1));
        seed("site-outage", outage, map("sites", List.of(map("case_id", record))));
        try (var records = mockStatic(Service.class, CALLS_REAL_METHODS)) {
            for (int status : List.of(403, 404, 409)) {
                doReadFailure(records, "incident", record, status);
                doReadFailure(records, "customer_case", record, status);
                if (status == 409) {
                    assertEquals(409, rejects("assignment", "waiting", map("limit", 5)).status);
                    assertEquals(409, rejects("impact", "my_impact", map("limit", 5)).status);
                } else {
                    assertEquals(List.of(), invoke("assignment", "waiting", map("limit", 5)));
                    assertEquals(List.of(), invoke("impact", "my_impact", map("limit", 5)));
                }
            }
        }
    }

    void doReadFailure(
            org.mockito.MockedStatic<Service> calls, String table, String record, int status) {
        calls.when(() -> Service.readRecord(db, table, record))
                .thenThrow(new Failure(status, "Read unavailable"));
    }

    @Test
    void assistantRequiresAnAvailableDeskAndVisibleSources() throws Exception {
        String conversation = id(), intent = id(), article = id(), offer = id();
        assertEquals(
                400, rejects("assistant", "intent", map("new_id", intent, "terms", "x")).status);
        seed(
                "employee-intent",
                intent,
                map(
                        "terms",
                        List.of("policy"),
                        "offering",
                        offer,
                        "article",
                        article,
                        "group",
                        id()));
        try (var catalog = mockStatic(Catalogue.class);
                var knowledge = mockStatic(KnowledgeFeedback.class);
                var fields = mockStatic(FieldAccess.class)) {
            catalog.when(() -> Catalogue.find(db, offer, false))
                    .thenReturn(
                            map(
                                    "offering_id",
                                    offer,
                                    "revision",
                                    1,
                                    "name",
                                    "VPN",
                                    "questions",
                                    List.of()));
            knowledge
                    .when(() -> KnowledgeFeedback.page(db, article))
                    .thenReturn(
                            map(
                                    "article",
                                    map("title", "Policy", "article_id", article, "version", 1)));
            for (boolean titleVisible : List.of(false, true)) {
                fields.when(() -> FieldAccess.allowed(eq(db), anyMap(), eq("short_description")))
                        .thenReturn(titleVisible);
                var absent =
                        object(
                                invoke(
                                        "assistant",
                                        "ask",
                                        map("new_id", id(), "question", "policy")));
                assertFalse(absent.containsKey("citation"));
                assertFalse(absent.containsKey("group"));
            }
            for (int status : List.of(403, 404, 409)) {
                knowledge
                        .when(() -> KnowledgeFeedback.page(db, article))
                        .thenThrow(new Failure(status, "Publication unavailable"));
                var body = map("new_id", id(), "question", "policy");
                if (status == 409) assertEquals(409, rejects("assistant", "ask", body).status);
                else assertFalse(object(invoke("assistant", "ask", body)).containsKey("citation"));
            }
        }
        var handoff = map("new_id", conversation, "details", "help");
        seed(
                "employee-conversation",
                conversation,
                map("owner", admin, "state", "handoff_required"));
        assertEquals(400, rejects("assistant", "handoff", handoff).status);
        seed(
                "employee-conversation",
                conversation,
                map("owner", admin, "state", "submitted", "group", id(), "request_id", id()));
        assertEquals(400, rejects("assistant", "handoff", handoff).status);
    }

    @Test
    void mappingRequiresOwnershipOfBothServiceTrees() throws Exception {
        var blank = map("title", "", "age_seconds", 0);
        var graph =
                new FederatedMaps.Graph(
                        Map.of("a", Set.of(), "b", Set.of(), "orphan", Set.of()),
                        Map.of("a", List.of(blank), "b", List.of(blank), "orphan", List.of(blank)),
                        Map.of("one", Set.of("a"), "two", Set.of("b")),
                        Map.of());
        seed("service-owner", DeliveryWorkspace.key("one"), map("owner", admin, "service", "one"));
        db.viewerRole = "agent";
        try (var topology = mockStatic(FederatedMaps.class, CALLS_REAL_METHODS)) {
            topology.when(() -> FederatedMaps.graph(db)).thenReturn(graph);
            assertEquals(
                    List.of(), object(invoke("mapping", "health", map("days", 1))).get("queue"));
            for (var pair :
                    List.of(
                            List.of("a", "absent"),
                            List.of("orphan", "a"),
                            List.of("a", "orphan"),
                            List.of("b", "a"),
                            List.of("a", "b")))
                assertEquals(
                        400,
                        rejects(
                                        "mapping",
                                        "preview_merge",
                                        map("component", pair.getFirst(), "target", pair.getLast()))
                                .status);
        }
    }

    @Test
    void subflowsKeepTheirApprovalWhenTheOwnerChanges() throws Exception {
        String run = id(),
                record = PlatformFoundationTest.record(db, "incident", "Intake"),
                design = id(),
                supplier = SupplierGovernance.identity("Supplier");
        var flow =
                map(
                        "record",
                        record,
                        "table",
                        "incident",
                        "owner",
                        admin,
                        "owner_revision",
                        -1,
                        "reference",
                        "signed",
                        "state",
                        "waiting");
        assertThrows(
                Failure.class,
                () ->
                        RecordSubflows.validate(
                                db,
                                "incident",
                                map(
                                        "kind",
                                        "subflow",
                                        "design_id",
                                        design,
                                        "offering_id",
                                        id(),
                                        "wait_event",
                                        "signed",
                                        "inputs",
                                        map())));
        seed("workflow-run", run, map("state", "complete"));
        seed("record-continuation", run, flow);
        assertEquals(400, rejects("designer", "continue_record", map("run_id", run)).status);
        RecordSubflows.ready(db, run);
        assertEquals(
                "provisioning_failed",
                JourneyForms.definition(db, "record-continuation", run).get("state"));
        db.update("UPDATE os_user SET role='agent' WHERE user_id=?", admin);
        RecordSubflows.ready(db, run);
        db.update("UPDATE os_user SET role='admin' WHERE user_id=?", admin);
        flow.put("request_id", id());
        seed("record-continuation", run, flow);
        RecordSubflows.ready(db, run);
        assertEquals(flow, JourneyForms.definition(db, "record-continuation", run));
        String sr = SupplierFlows.instance(design, supplier);
        seed("supplier-trigger", design, map());
        var principal = AgentPlans.account(db, admin);
        var config =
                map(
                        "owner",
                        admin,
                        "owner_revision",
                        principal.get("auth_revision"),
                        "contract",
                        "abc");
        seed(
                "managed-supplier",
                supplier,
                map("legal", admin, "contract", map("reference", "abc")));
        seed("workflow-run", sr, map("state", "complete"));
        seed("supplier-flow", sr, config);
        var request = map("design_id", design, "supplier", "Supplier");
        SupplierFlows.changed(db, supplier, map("assessment", map("decision", "fail")));
        SupplierFlows.changed(db, supplier, map("assessment", map("decision", "pass")));
        SupplierFlows.signed(db, supplier, map("contract", map("reference", "abc")));
        config.put("owner", id());
        seed("supplier-flow", sr, config);
        assertEquals(400, rejects("designer", "retry_provision", request).status);
        config.putAll(map("owner", admin, "owner_revision", -1));
        seed("supplier-flow", sr, config);
        assertEquals(400, rejects("designer", "retry_provision", request).status);
        config.put("owner_revision", principal.get("auth_revision"));
        config.remove("contract");
        seed("supplier-flow", sr, config);
        assertEquals(400, rejects("designer", "retry_provision", request).status);
    }

    @Test
    void guidesReportHistoricalChildrenAndIndependentReviews() throws Exception {
        String order = id(),
                child = PlatformFoundationTest.record(db, "sc_request", "Hardware"),
                offering = id(),
                employee = id();
        var line = map("request_id", child, "name", "Laptop", "reservation", "released");
        var guide =
                map(
                        "title",
                        "New hire",
                        "owner",
                        admin,
                        "employee",
                        employee,
                        "state",
                        "open",
                        "items",
                        map(offering, line));
        var track = map("order_id", order);
        seed("guide-order", order, guide);
        var data = object(Service.record(db, "sc_request", child).get("data"));
        data.put("state", "complete");
        db.update(
                "UPDATE os_record SET data=" + db.jsonParameter() + " WHERE sys_id=?",
                JSON.writeValueAsString(data),
                child);
        var result = object(object(invoke("guides", "track", track)).get("order"));
        assertEquals("complete", result.get("state"));
        assertEquals(
                "cancelled",
                object(object(result.get("items")).get(offering)).get("request_state"));
        db.viewerId = employee;
        assertEquals("complete", object(invoke("guides", "track", track)).get("state"));
        db.viewerId = admin;
        guide.put("state", "cancelled");
        seed("guide-order", order, guide);
        assertEquals(
                "cancelled",
                object(object(invoke("guides", "track", track)).get("order")).get("state"));
        db.viewerId = employee;
        assertEquals("cancelled", object(invoke("guides", "track", track)).get("state"));
        db.viewerId = admin;
        line.putAll(map("request_id", id(), "reservation", "reserved"));
        seed("guide-order", order, guide);
        db.viewerId = employee;
        assertEquals(
                "unavailable",
                objects(object(invoke("guides", "track", track)).get("items"))
                        .getFirst()
                        .get("state"));
        db.viewerId = admin;
        line.put("request_id", child);
        guide.putAll(map("employee", admin, "state", "open"));
        seed("guide-order", order, guide);
        assertTrue(object(invoke("guides", "track", track)).containsKey("order"));
        var decision =
                map(
                        "order_id",
                        order,
                        "offering_id",
                        offering,
                        "decision",
                        "approved",
                        "note",
                        "ok",
                        "revision",
                        1);
        for (var review :
                List.of(
                        map("state", "pending", "reviewer", employee),
                        map("state", "approved", "reviewer", admin))) {
            seed("guide-review", child, review);
            assertEquals(400, rejects("guides", "decide_item", decision).status);
        }
        assertEquals(400, rejects("guides", "define", map("entries", List.of())).status);
        String guideId = id();
        seed("order-guide", guideId, map("entries", List.of(), "title", "Empty guide"));
        assertEquals(
                400,
                rejects(
                                "guides",
                                "order",
                                map(
                                        "guide_id",
                                        guideId,
                                        "new_id",
                                        id(),
                                        "revision",
                                        1,
                                        "job_role",
                                        "analyst",
                                        "location",
                                        "HQ"))
                        .status);
    }

    @Test
    void outageConfigurationAndRecoveryRefuseUnrelatedRecords() throws Exception {
        String bundle = id(), sale = id(), contact = id(), ent = id(), account = id();
        seed(
                "site-bundle",
                bundle,
                map("account", account, "sites", map("HQ", map("opportunity", sale))));
        var bind =
                map(
                        "bundle_id",
                        bundle,
                        "site",
                        "HQ",
                        "service",
                        "S",
                        "contact_id",
                        contact,
                        "entitlement_id",
                        ent,
                        "calendar",
                        "missing");
        for (var p :
                List.of(
                        map("state", "proposed"),
                        map("state", "installed", "entitlement", id()),
                        map("state", "installed", "entitlement", ent))) {
            seed("opportunity", sale, p);
            assertEquals(400, rejects("impact", "bind_site", bind).status);
        }
        var graph =
                new FederatedMaps.Graph(
                        Map.of("db", Set.of()),
                        Map.of("db", List.of()),
                        Map.of("S", Set.of("db")),
                        Map.of());
        try (var topology = mockStatic(FederatedMaps.class, CALLS_REAL_METHODS);
                var contacts = mockStatic(CustomerAccounts.class)) {
            topology.when(() -> FederatedMaps.graph(db)).thenReturn(graph);
            for (String linked : List.of(id(), account)) {
                contacts.when(() -> CustomerAccounts.contact(db, contact))
                        .thenReturn(map("active", 1, "account_id", linked));
                assertEquals(400, rejects("impact", "bind_site", bind).status);
            }
            String outage = id();
            seed(
                    "site-outage",
                    outage,
                    map("component", "other", "title", "Incident", "state", "open"));
            assertEquals(
                    400,
                    rejects(
                                    "impact",
                                    "outage",
                                    map("new_id", outage, "component", "db", "title", "Incident"))
                            .status);
            assertEquals(400, rejects("impact", "recover", map("new_id", id())).status);
            seed("site-monitor", id(), map("service", "unaffected"));
            var first =
                    object(
                            object(
                                            invoke(
                                                    "impact",
                                                    "outage",
                                                    map(
                                                            "new_id",
                                                            id(),
                                                            "component",
                                                            "db",
                                                            "title",
                                                            "Incident")))
                                    .get("definition"));
            assertEquals(List.of(), first.get("sites"));
            seed(
                    "site-monitor",
                    id(),
                    map(
                            "service",
                            "S",
                            "contact",
                            contact,
                            "entitlement",
                            ent,
                            "account",
                            account));
            try (var entitlements = mockStatic(CustomerCases.class)) {
                for (int status : List.of(403, 404, 409)) {
                    entitlements
                            .when(() -> CustomerCases.offering(db, ent))
                            .thenThrow(new Failure(status, "Entitlement unavailable"));
                    var input = map("new_id", id(), "component", "db", "title", "Data unavailable");
                    if (status == 409) assertEquals(409, rejects("impact", "outage", input).status);
                    else
                        assertEquals(
                                1,
                                objects(
                                                object(
                                                                object(
                                                                                invoke(
                                                                                        "impact",
                                                                                        "outage",
                                                                                        input))
                                                                        .get("definition"))
                                                        .get("skipped"))
                                        .size());
                    assertEquals(admin, db.viewerId);
                }
            }
        }
    }

    @Test
    void licenseReclamationRequiresTheRightSubscriptionAndInvoice() throws Exception {
        String license = id(),
                otherLicense = id(),
                product = "Mail",
                notice = DeliveryWorkspace.key(license, admin);
        var rights =
                map(
                        "product",
                        product,
                        "expires_on",
                        "2099-01-01",
                        "seats",
                        1,
                        "users",
                        List.of(admin));
        var body =
                map(
                        "license_id",
                        license,
                        "user_id",
                        admin,
                        "product",
                        product,
                        "revision",
                        1,
                        "usage_revision",
                        1,
                        "confirm",
                        true);
        seed("entitlement", license, rights);
        assertEquals(400, rejects("licenses", "verify_release", body).status);
        assertEquals(400, rejects("licenses", "waitlist", body).status);
        assertEquals(400, rejects("licenses", "exception", body).status);
        seed("seat-notice", notice, map("state", "notified", "user", admin, "license", license));
        rights.put("users", List.of());
        seed("entitlement", license, rights);
        assertEquals(400, rejects("licenses", "waitlist", body).status);
        assertEquals(400, rejects("licenses", "exception", body).status);
        rights.put("users", List.of(admin));
        seed("entitlement", license, rights);
        try (var settlement = mockStatic(InvoiceSettlement.class)) {
            var misses =
                    List.of(
                            map("license_id", otherLicense, "user_id", admin),
                            map("license_id", license, "user_id", id()));
            settlement
                    .when(() -> InvoiceSettlement.command(eq(db), eq("reclaim"), anyMap()))
                    .thenReturn(map("candidates", misses));
            assertEquals(400, rejects("licenses", "notice", body).status);
            assertThrows(Failure.class, () -> SeatRecovery.candidate(db, license, admin));
            settlement
                    .when(() -> InvoiceSettlement.command(eq(db), eq("reclaim"), anyMap()))
                    .thenReturn(
                            map(
                                    "candidates",
                                    List.of(map("license_id", license, "user_id", admin))));
            assertEquals(1, object(invoke("licenses", "notice", body)).get("revision"));
            seed(
                    "seat-notice",
                    notice,
                    map("state", "released", "user", admin, "license", license));
            var renewed = object(invoke("licenses", "notice", body));
            assertEquals("notified", object(renewed.get("definition")).get("state"));
        }
        seed(
                "seat-publisher",
                license,
                map(
                        "alias",
                        "publisher",
                        "origin",
                        "https://inventory.invalid",
                        "subscriber_path",
                        "/seats/{user_id}"));
        seed(
                "seat-usage",
                ConnectorSynchronization.hash(product),
                map(
                        "source",
                        "vendor",
                        "as_of",
                        java.time.LocalDate.now().toString(),
                        "users",
                        map(admin, "2000-01-01")));
        seed(
                "seat-wait",
                id(),
                map("license", otherLicense, "state", "waiting", "user", admin, "created", 1));
        seed(
                "seat-wait",
                id(),
                map("license", license, "state", "fulfilled", "user", admin, "created", 2));
        seed(
                "seat-wait",
                id(),
                map("license", license, "state", "waiting", "user", admin, "created", 3));
        try (var console = mockStatic(ConnectorConsole.class);
                var fetch = mockStatic(Connectors.class)) {
            console.when(() -> ConnectorConsole.page(db, "publisher"))
                    .thenReturn(map("origin", "https://inventory.invalid"));
            assertEquals(
                    400,
                    rejects(
                                    "licenses",
                                    "publisher",
                                    map(
                                            "license_id",
                                            license,
                                            "alias",
                                            "publisher",
                                            "subscriber_path",
                                            "/subscribers"))
                            .status);
            try (var actions = mockStatic(ConnectorActions.class)) {
                String action = id();
                for (var spec :
                        List.of(
                                map("alias", "different", "active", 1),
                                map("alias", "publisher", "active", 0))) {
                    actions.when(() -> ConnectorActions.find(db, action)).thenReturn(spec);
                    assertEquals(
                            400,
                            rejects(
                                            "licenses",
                                            "bind_removal",
                                            map("license_id", license, "action_id", action))
                                    .status);
                }
            }
            body.put("revision", 3);
            for (var subscriber :
                    List.of(
                            map("product", "Wrong", "user_id", admin, "assigned", false),
                            map("product", product, "user_id", id(), "assigned", false))) {
                fetch.when(() -> Connectors.fetch(eq(store), eq(db), any()))
                        .thenReturn(
                                map("content", JSON.writeValueAsBytes(subscriber), "revision", 1));
                assertEquals(400, rejects("licenses", "verify_release", body).status);
                assertEquals(
                        List.of(admin),
                        JourneyForms.definition(db, "entitlement", license).get("users"));
            }
            fetch.when(() -> Connectors.fetch(eq(store), eq(db), any()))
                    .thenReturn(
                            map(
                                    "content",
                                    JSON.writeValueAsBytes(
                                            map(
                                                    "product",
                                                    product,
                                                    "user_id",
                                                    admin,
                                                    "assigned",
                                                    false)),
                                    "revision",
                                    1));
            var release = object(invoke("licenses", "verify_release", body));
            assertFalse(
                    object(object(release.get("definition")).get("receipt"))
                            .containsKey("replacement"));
        }
        seed("seat-receipt", id(), map("license", otherLicense));
        seed("seat-credit", id(), map("license", otherLicense));
        var stats = object(invoke("licenses", "savings", body));
        assertEquals(1, stats.get("recovered_seats"));
        assertEquals(0, stats.get("purchases_avoided"));
        String invoice = id();
        seed(
                "posted-expense",
                DeliveryWorkspace.key("credit-1"),
                map("kind", "credit", "invoice", invoice, "batch", id()));
        seed("finance-settlement", invoice, map("exports", List.of()));
        assertEquals(
                409,
                rejects(
                                "licenses",
                                "record_credit",
                                map("license_id", license, "reference", "credit-1"))
                        .status);
    }

    @Test
    void packagesRefuseIncompatibleFormatsAndOccupiedDrafts() throws Exception {
        String group = PlatformFoundationTest.record(db, "sys_user_group", "Local team"),
                design = id(),
                app = id();
        var pkg =
                map(
                        "source_app",
                        app,
                        "source_version",
                        1,
                        "source_group",
                        group,
                        "title",
                        "Legal",
                        "fields",
                        List.of(),
                        "workflow",
                        map("rows", List.of()),
                        "format",
                        "bad",
                        "roles",
                        List.of("requester", "fulfillment_group"));
        var args =
                map(
                        "app_id",
                        app,
                        "manifest",
                        pkg,
                        "group_id",
                        group,
                        "design_id",
                        design,
                        "bindings",
                        map());
        assertEquals(400, rejects("apps", "install_dependency", args).status);
        pkg.putAll(map("format", "openservice.intake.v1", "roles", List.of("admin")));
        assertEquals(400, rejects("apps", "install_dependency", args).status);
        pkg.put("roles", List.of("requester", "fulfillment_group"));
        assertEquals(400, rejects("apps", "install_dependency", args).status);
        seed("decision-draft", design, map("title", "Existing draft"));
        assertEquals(400, rejects("apps", "install_dependency", args).status);
    }

    @Test
    void deskWritesHonorConfiguredAssignmentFieldsAndExistingOwners() throws Exception {
        String table = "incident";
        var schema = Service.schema(db, table);
        String group = PlatformFoundationTest.record(db, "sys_user_group", "Desk");
        db.update("INSERT INTO os_group_member(group_id,user_id) VALUES(?,?)", group, admin);
        String unstaffed = PlatformFoundationTest.record(db, table, "Unstaffed");
        seed(
                "desk-offer",
                unstaffed,
                map(
                        "record_id",
                        unstaffed,
                        "table",
                        table,
                        "skill",
                        "storage",
                        "declined",
                        List.of(),
                        "due",
                        123));
        assertEquals(
                400,
                rejects(
                                "assignment",
                                "accept",
                                map("table", table, "record_id", unstaffed, "revision", 1))
                        .status);
        seed("desk-capacity", admin, map("skills", List.of("storage"), "capacity", 10));
        seed("desk-presence", admin, map("active", true));
        for (String mode : List.of("busy", "account", "configured", "desk_only")) {
            var fields = object(schema.get("fields"));
            var rules = object(schema.computeIfAbsent("field_rules", ignored -> map()));
            if (mode.equals("account")) rules.put("assigned_to", map("type", "account"));
            if (mode.equals("configured")) {
                fields.putAll(map("owner", "Owner", "team", "Team"));
                rules.putAll(
                        map(
                                "owner",
                                map("type", "account"),
                                "team",
                                map("type", "reference", "target", "sys_user_group")));
                schema.put("assignment", map("user_field", "owner", "group_field", "team"));
            }
            if (mode.equals("desk_only")) {
                schema.remove("assignment");
                fields.remove("assigned_to");
                rules.remove("assigned_to");
            }
            db.update(
                    "UPDATE os_schema SET definition=" + db.jsonParameter() + " WHERE name=?",
                    JSON.writeValueAsString(schema),
                    table);
            var data = map("short_description", "Assign work");
            if (mode.equals("configured")) data.put("team", group);
            if (mode.equals("busy")) data.put("assigned_to", "admin");
            var item = Service.create(db, table, data, "admin");
            String record = item.get("sys_id").toString();
            seed(
                    "desk-offer",
                    record,
                    map(
                            "record_id",
                            record,
                            "table",
                            table,
                            "skill",
                            "storage",
                            "declined",
                            List.of(),
                            "due",
                            123));
            var accept = map("table", table, "record_id", record, "revision", 1);
            if (mode.equals("busy")) {
                assertEquals(400, rejects("assignment", "accept", accept).status);
                continue;
            }
            var assigned = object(object(invoke("assignment", "accept", accept)).get("definition"));
            assertEquals(admin, assigned.get("owner"));
            if (!mode.equals("desk_only"))
                assertEquals(
                        admin,
                        object(Service.record(db, table, record).get("data"))
                                .get(mode.equals("configured") ? "owner" : "assigned_to"));
            assertEquals(400, rejects("assignment", "accept", accept).status);
        }
    }

    @Test
    void appImportPreservesSharedProceduresAndFlagsLocalConflicts() throws Exception {
        String design = id(),
                app = id(),
                group = PlatformFoundationTest.record(db, "sys_user_group", "Legal"),
                external = id(),
                proc = id();
        var step =
                map(
                        "kind",
                        "work",
                        "procedure",
                        proc,
                        "group",
                        external,
                        "title",
                        "Legal review",
                        "stage",
                        1);
        var row =
                map(
                        "id",
                        "standard",
                        "condition",
                        "default",
                        "operand",
                        "any",
                        "steps",
                        List.of(step));
        var test = map("amount", "0", "risk", "low", "location", "HQ");
        var pkg =
                map(
                        "source_app",
                        app,
                        "source_version",
                        1,
                        "source_group",
                        id(),
                        "title",
                        "Legal",
                        "fields",
                        List.of(),
                        "roles",
                        List.of("requester", "fulfillment_group"),
                        "format",
                        "openservice.intake.v1",
                        "workflow",
                        map("rows", List.of(row), "tests", map("standard", test), "days", 5));
        var args =
                map(
                        "app_id",
                        app,
                        "manifest",
                        pkg,
                        "group_id",
                        group,
                        "design_id",
                        design,
                        "bindings",
                        map(external, group));
        String local = DeliveryWorkspace.key("portable-procedure", design, proc).substring(0, 32);
        var procedure = map("kind", "work", "title", "Different procedure", "group", group);
        seed("access-procedure", local, procedure);
        assertEquals(400, rejects("apps", "install_dependency", args).status);
        procedure.put("title", "Legal review");
        seed("access-procedure", local, procedure);
        var revision = EnterpriseData.get(db, "access-procedure", local).get("revision");
        invoke("apps", "install_dependency", args);
        assertEquals(revision, EnterpriseData.get(db, "access-procedure", local).get("revision"));
        assertEquals(group, JourneyForms.definition(db, "access-procedure", local).get("group"));
    }

    @Test
    void improvementMeasurementsRequireOwnerAndEnoughCasesInBothCohorts() throws Exception {
        String initiative = id(), design = id();
        var criteria =
                map(
                        "table",
                        "incident",
                        "before_start",
                        "2020-01-01",
                        "completed_state",
                        "6",
                        "query",
                        "",
                        "hours",
                        2,
                        "days",
                        1);
        var baseline =
                map(
                        "cases",
                        List.of(),
                        "completed",
                        10,
                        "mean_seconds_to_first_completion",
                        100,
                        "reopen_rate",
                        0);
        var args =
                map(
                        "new_id",
                        initiative,
                        "design_id",
                        design,
                        "revision",
                        1,
                        "draft_revision",
                        1,
                        "after_start",
                        "2020-04-01");
        var plan =
                map(
                        "owner",
                        id(),
                        "state",
                        "published",
                        "criteria",
                        criteria,
                        "minimum_completed",
                        2,
                        "baseline",
                        baseline,
                        "published_at",
                        "2020-02-01 00:00:00");
        seed("improvement", initiative, plan);
        assertEquals(400, rejects("mining", "measure", args).status);
        db.viewerRole = "agent";
        assertEquals(400, rejects("mining", "publish_change", args).status);
        db.viewerRole = "admin";
        plan.putAll(map("state", "baseline_frozen", "owner", id()));
        seed("improvement", initiative, plan);
        seed("decision-release", design, map("days", 3, "rows", List.of()));
        seed("decision-draft", design, map("days", 4, "rows", List.of()));
        try (var decisions = mockStatic(DecisionFlows.class)) {
            decisions
                    .when(() -> DecisionFlows.command(eq(db), eq("publish"), anyMap()))
                    .thenReturn(map("revision", 2, "definition", map("days", 4)));
            args.put("revision", 2);
            assertEquals(
                    "published",
                    object(object(invoke("mining", "publish_change", args)).get("definition"))
                            .get("state"));
        }
        plan = JourneyForms.definition(db, "improvement", initiative);
        plan.putAll(map("owner", admin, "published_at", "2020-02-01 00:00:00"));
        seed("improvement", initiative, plan);
        args.put("revision", 4);
        var report = object(object(invoke("mining", "measure", args)).get("definition"));
        assertEquals("insufficient_completed_cases", report.get("sample_status"));
        assertFalse(report.containsKey("reopen_rate_change"));
    }

    @Test
    void aMissedOrUnmappedMonitorEventCannotPublishCustomerUpdates() throws Exception {
        String source = "cloud", resource = "db", episode = id();
        var body = map("source", source, "resource_id", resource);
        try (var monitors = mockStatic(ServiceTopology.class, CALLS_REAL_METHODS)) {
            monitors.when(() -> ServiceTopology.command(db, "signals", body))
                    .thenReturn(map("definition", map()));
            assertEquals(
                    map("definition", map()), CustomerImpact.topology(store, db, "signals", body));
            seed("site-monitor", id(), map("service", "customers"));
            for (var status : List.of(map(), map("episode", episode))) {
                monitors.when(() -> ServiceTopology.command(db, "signals", body))
                        .thenReturn(map("definition", status));
                assertEquals(
                        map("definition", status),
                        CustomerImpact.topology(store, db, "signals", body));
            }
            monitors.when(() -> ServiceTopology.command(db, "recover", body))
                    .thenReturn(map("state", "recovered"));
            assertEquals(
                    map("state", "recovered"), CustomerImpact.topology(store, db, "recover", body));
            seed(
                    "monitor-state",
                    ServiceTopology.identity(source, resource),
                    map("episode", episode));
            assertEquals(
                    map("state", "recovered"), CustomerImpact.topology(store, db, "recover", body));
            assertTrue(EnterpriseData.list(db, "site-outage").isEmpty());
        }
    }

    @Test
    void selfApprovalAndUnreviewedOrdersAreRejected() throws Exception {
        String guide = id(), offer = id(), employee = id(), request = id();
        var row =
                map(
                        "offering_id",
                        offer,
                        "name",
                        "Laptops",
                        "fulfilment_group_id",
                        "",
                        "revision",
                        1);
        var item = map("offering_id", offer, "location", "*", "role", "*", "reviewer_id", admin);
        var spec = map("title", "New hire", "entries", List.of(item));
        var args =
                map(
                        "guide_id",
                        guide,
                        "new_id",
                        id(),
                        "job_role",
                        "manager",
                        "location",
                        "HQ",
                        "revision",
                        1,
                        "details",
                        "Start",
                        "manifest",
                        map(offer, map("answers", map(), "revision", 1)));
        try (var catalog = mockStatic(Catalogue.class);
                var roster = mockStatic(DutyRosters.class);
                var principal = mockStatic(AgentPlans.class)) {
            catalog.when(() -> Catalogue.find(db, offer, false)).thenReturn(row);
            catalog.when(() -> Catalogue.order(eq(db), eq(offer), any()))
                    .thenReturn(map("sys_id", request));
            roster.when(() -> DutyRosters.responder(eq(db), any()))
                    .thenAnswer(inv -> inv.getArgument(1));
            principal
                    .when(() -> AgentPlans.account(db, employee))
                    .thenReturn(map("role", "requester"));
            seed("order-guide", guide, spec);
            assertEquals(400, rejects("guides", "order", args).status);
            item.put("reviewer_id", employee);
            seed("order-guide", guide, spec);
            args.putAll(map("employee_id", employee, "revision", 2));
            seed("workflow-identity", employee, map("manager", admin));
            assertEquals(400, rejects("guides", "order_for", args).status);
            catalog.when(() -> Catalogue.find(db, offer, false))
                    .thenThrow(new Failure(409, "Offering changed"));
            assertEquals(409, rejects("guides", "preview", args).status);
        }
    }

    @Test
    void nativeRequestStillEnforcesItsLifecycleOutsideFulfillment() throws Exception {
        String request = PlatformFoundationTest.record(db, "sc_request", "Staged work");
        var child = Service.record(db, "sc_request", request);
        child =
                Service.update(
                        db,
                        "sc_request",
                        child,
                        map("state", "fulfilling"),
                        "admin",
                        "admin",
                        child.get("revision").toString());
        var fulfilling = child;
        var failure =
                assertThrows(
                        Failure.class,
                        () ->
                                Service.update(
                                        db,
                                        "sc_request",
                                        fulfilling,
                                        map("state", "open"),
                                        "admin",
                                        "admin",
                                        fulfilling.get("revision").toString()));
        assertEquals(409, failure.status);
        assertEquals(
                "fulfilling",
                object(Service.record(db, "sc_request", request).get("data")).get("state"));
    }

    @Test
    void supersededStockReservationsCannotBeIssuedOrReleased() throws Exception {
        String child = PlatformFoundationTest.record(db, "sc_request", "Device"),
                asset = PlatformFoundationTest.record(db, "alm_asset", "Laptop"),
                offering = id(),
                order = id(),
                owner = id();
        var item = map("request_id", child, "name", "Device", "reservation", "reserved");
        var guide =
                map(
                        "owner",
                        owner,
                        "employee",
                        owner,
                        "state",
                        "open",
                        "items",
                        map(offering, item));
        var data = object(Service.record(db, "sc_request", child).get("data"));
        data.put("state", "complete");
        db.update(
                "UPDATE os_record SET data=" + db.jsonParameter() + " WHERE sys_id=?",
                JSON.writeValueAsString(data),
                child);
        seed("guide-order", order, guide);
        var args =
                map(
                        "order_id",
                        order,
                        "offering_id",
                        offering,
                        "reference",
                        "Dispatch",
                        "asset_id",
                        asset,
                        "revision",
                        1);
        db.viewerRole = "agent";
        try (var custody = mockStatic(AssetCustody.class, CALLS_REAL_METHODS)) {
            custody.when(() -> AssetCustody.writable(db)).thenReturn(true);
            custody.when(() -> AssetCustody.latest(db, asset)).thenReturn(map("state", "issued"));
            assertEquals(400, rejects("guides", "reserve_asset", args).status);
            custody.when(() -> AssetCustody.latest(db, asset)).thenReturn(map("state", "in_stock"));
            item.put("shipment", "sent");
            seed("guide-order", order, guide);
            assertEquals(400, rejects("guides", "reserve_asset", args).status);
            assertEquals(400, rejects("guides", "ship", args).status);
            item.remove("shipment");
            seed("guide-order", order, guide);
            args.put("revision", 3);
            seed("guide-stock", asset, map("state", "held", "request", id()));
            assertEquals(400, rejects("guides", "reserve_asset", args).status);
            // The transport may dispatch unreserved equipment tracked outside stock custody.
            assertEquals(
                    "shipped",
                    object(
                                    object(
                                                    object(
                                                                    object(
                                                                                    invoke(
                                                                                            "guides",
                                                                                            "ship",
                                                                                            args))
                                                                            .get("definition"))
                                                            .get("items"))
                                            .get(offering))
                            .get("reservation"));
            seed("guide-order", order, guide);
            seed("guide-stock-item", child, map("asset", asset));
            assertEquals(400, rejects("guides", "ship", args).status);
            seed("guide-stock", asset, map("state", "released", "request", id()));
            assertEquals(400, rejects("guides", "ship", args).status);
            assertEquals(400, rejects("guides", "reserve_asset", args).status);
            args.put("revision", 5);
            db.viewerRole = "admin";
            invoke("guides", "cancel", args);
            assertEquals(
                    "released", JourneyForms.definition(db, "guide-stock", asset).get("state"));
        }
    }
}
