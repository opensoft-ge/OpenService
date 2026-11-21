package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

class EnterprisePlatformRulesTest {
    @TempDir Path directory;
    Store store;
    Store.Db db;
    String admin;

    @BeforeEach
    void open() throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = directory;
        store = new Store(fixture.configuration(false));
        db = store.open();
        admin = PlatformFoundationTest.bind(db);
    }

    @AfterEach
    void close() throws Exception {
        db.close();
    }

    void write(String area, String id, Map<String, Object> value) throws Exception {
        save(db, area, id, get(db, area, id).get("revision"), value);
    }

    @Test
    void policyFormBindsAnOrderFieldAndRejectsIncompleteMappings() throws Exception {
        var page = map("title", "Decision workflows");
        var form =
                map(
                        "operation",
                        "list_policy",
                        "designer_rows",
                        "entries",
                        "item_1",
                        "screen",
                        "owner_1",
                        id(),
                        "offering_1",
                        id(),
                        "answer_1",
                        "units",
                        "input_1",
                        "quantity",
                        "item_2",
                        "postage");
        DesignInputs.read(page, form);
        var rows = objects(JSON.readValue(form.get("entries").toString(), List.class));
        assertEquals(2, rows.size());
        assertEquals(map("units", "quantity"), rows.getFirst().get("answers_from"));
        for (String field : List.of("answer_1", "input_1")) {
            var partial =
                    map("operation", "list_policy", "designer_rows", "entries", field, "quantity");
            assertThrows(Failure.class, () -> DesignInputs.read(page, partial));
        }
        for (var bad :
                List.of(
                        map("operation", "case", "designer_rows", "entries"),
                        map(
                                "operation",
                                "list_policy",
                                "designer_rows",
                                "entries",
                                "entries",
                                "[]")))
            assertThrows(Failure.class, () -> DesignInputs.read(page, bad));
        assertThrows(
                Failure.class,
                () ->
                        DesignInputs.read(
                                map("title", "Intake applications"),
                                map("operation", "list_policy", "designer_rows", "entries")));
    }

    @Test
    void scheduledApprovalControlKeepsHistoricalFailuresReproducible() throws Exception {
        String recipient = id(), flow = id(), policy = id(), group = id();
        write("access-run", flow, map("privileged", policy));
        write("workflow-identity", recipient, map("manager", admin));
        Object revision = AgentPlans.account(db, admin).get("auth_revision");
        var manager =
                map("kind", "approval", "state", "done", "by", admin, "signer_revision", revision);
        var second =
                map(
                        "kind",
                        "team_approval",
                        "state",
                        "done",
                        "by",
                        admin,
                        "group",
                        group,
                        "signer_revision",
                        revision);
        db.update("INSERT INTO os_group_member(group_id,user_id) VALUES(?,?)", group, admin);
        var steps = new ArrayList<>(List.of(map("kind", "task"), manager, second));
        var run =
                map("template", policy, "employee", recipient, "state", "complete", "steps", steps);
        write("workflow-run", flow, run);
        assertEquals(false, GrantIndicators.sample(db, flow).get("passed"));
        manager.remove("signer_revision");
        write("workflow-run", flow, run);
        assertEquals(false, GrantIndicators.sample(db, flow).get("passed"));
        manager.put("signer_revision", 999);
        write("workflow-run", flow, run);
        assertEquals(false, GrantIndicators.sample(db, flow).get("passed"));
        manager.put("by", id());
        write("workflow-run", flow, run);
        assertEquals(false, GrantIndicators.sample(db, flow).get("passed"));
        run.put("employee", admin);
        manager.putAll(map("by", admin, "signer_revision", revision));
        write("workflow-identity", admin, map("manager", admin));
        write("workflow-run", flow, run);
        assertEquals(false, GrantIndicators.sample(db, flow).get("passed"));
        assertEquals(false, GrantIndicators.inspect(db, map("policy", id())).get("passed"));
        run.put("steps", List.of());
        write("workflow-run", flow, run);
        assertEquals(false, GrantIndicators.inspect(db, map("run", flow)).get("passed"));
        String monitor = id();
        write(
                "grant-monitor",
                monitor,
                map(
                        "run",
                        id(),
                        "minutes",
                        1,
                        "next_at",
                        0,
                        "scheduled_by",
                        admin,
                        "authority",
                        revision));
        GrantIndicators.tick(db, 100);
        var status = JourneyForms.definition(db, "grant-monitor", monitor);
        assertTrue(status.containsKey("error"));
        assertEquals(60100, ((Number) status.get("next_at")).intValue());
        assertEquals(admin, db.viewerId);
    }

    @Test
    void teamCalendarSnapshotsShowRealDatedAssumptionsAndOutsideReservations() throws Exception {
        String group = id(), project = id();
        db.update("INSERT INTO os_group_member(group_id,user_id) VALUES(?,?)", group, admin);
        write(
                "staff-calendar",
                admin,
                map(
                        "week",
                        List.of(480, 480, 480, 480, 480, 0, 0),
                        "exceptions",
                        map("2027-01-01", 0),
                        "rate",
                        "24.00",
                        "currency",
                        "USD"));
        var inside =
                map(
                        "user_id",
                        admin,
                        "percent",
                        50,
                        "start_on",
                        "2027-03-30",
                        "finish_on",
                        "2027-04-03");
        write(
                "portfolio",
                project,
                map(
                        "reservations",
                        List.of(
                                inside,
                                map("user_id", id()),
                                map("user_id", admin, "start_on", "2027-04-01"),
                                map(
                                        "user_id",
                                        admin,
                                        "start_on",
                                        "2026-12-01",
                                        "finish_on",
                                        "2026-12-15"))));
        var snapshot = QuarterFunding.capacity(db, group, "2027-Q1");
        var resource = object(snapshot.get(admin));
        assertEquals(30240, resource.get("available_minutes"));
        assertEquals(
                List.of(map("project", project, "booking", inside)), resource.get("reservations"));
        write(
                "staff-calendar",
                admin,
                map(
                        "week",
                        List.of(240, 240, 240, 240, 240, 0, 0),
                        "exceptions",
                        map(),
                        "rate",
                        "24.00",
                        "currency",
                        "USD"));
        assertNotEquals(snapshot, QuarterFunding.capacity(db, group, "2027-Q1"));
    }

    @Test
    void monthlyBillingRequiresValidAccountMonthCurrencyAndVisibleService() throws Exception {
        var args =
                map(
                        "month",
                        "2027-01",
                        "previous_month",
                        "2026-12",
                        "account",
                        "123456789012",
                        "currency",
                        "EUR",
                        "service",
                        "unattributed");
        for (var invalid :
                List.of(
                        map("account", "123"),
                        map("month", "2027-15"),
                        map("currency", "usd"),
                        map("previous_month", "2027-04"))) {
            var altered = new LinkedHashMap<>(args);
            altered.putAll(invalid);
            assertThrows(Failure.class, () -> CloudSpend.compare(db, altered));
        }
        assertThrows(Failure.class, () -> CloudSpend.amount("NaN"));
        assertThrows(Failure.class, () -> CloudSpend.command(db, "cloud_budget", args));
        db.viewerId = id();
        db.viewerRole = "agent";
        assertThrows(Failure.class, () -> CloudSpend.compare(db, args));
        assertThrows(
                Failure.class, () -> EnterpriseOperations.command(store, db, "unknown", map()));
    }

    @Test
    void monthlyCloudVarianceKeepsSharedChargesAndCreditsSeparate() throws Exception {
        String account = "123456789012", service = "billing";
        for (var row :
                List.of(
                        map(
                                "month",
                                "2027-01",
                                "currency",
                                "USD",
                                "resource",
                                "db",
                                "cost",
                                "17.00",
                                "services",
                                List.of(service)),
                        map(
                                "month",
                                "2027-01",
                                "currency",
                                "USD",
                                "resource",
                                "db",
                                "cost",
                                "-5.00",
                                "services",
                                List.of(service)),
                        map(
                                "month",
                                "2027-02",
                                "currency",
                                "USD",
                                "resource",
                                "db",
                                "cost",
                                "20.20",
                                "services",
                                List.of(service)),
                        map(
                                "month",
                                "2027-02",
                                "currency",
                                "USD",
                                "resource",
                                "shared",
                                "cost",
                                "10",
                                "services",
                                List.of(service, "other")),
                        map(
                                "month",
                                "2027-02",
                                "currency",
                                "EUR",
                                "resource",
                                "db",
                                "cost",
                                "999",
                                "services",
                                List.of(service)))) {
            var input = new LinkedHashMap<>(row);
            input.remove("services");
            input.put("account", account);
            write("cloud-charge", id(), map("input", input, "services", row.get("services")));
        }
        var args =
                map(
                        "account",
                        account,
                        "month",
                        "2027-02",
                        "previous_month",
                        "2027-01",
                        "currency",
                        "USD",
                        "service",
                        service);
        write("service-owner", DeliveryWorkspace.key(service), map("owner", admin));
        db.viewerRole = "agent";
        var diff = object(CloudSpend.compare(db, args));
        assertEquals("8.20", diff.get("change"));
        assertEquals("8.20", object(diff.get("resource_changes")).get("db").toString());
        db.viewerRole = "admin";
        args.put("service", "shared");
        assertEquals("10", object(CloudSpend.command(db, "cloud_cost", args)).get("actual"));
        args.put("account", "111111111111");
        assertEquals("0", object(CloudSpend.command(db, "cloud_cost", args)).get("actual"));
        args.put("source", "aws");
        for (Object bad : List.of(17, "x".repeat(241))) {
            args.put("entries", List.of(map("line_id", "bad", "resource", bad, "cost", "10")));
            assertThrows(Failure.class, () -> CloudSpend.command(db, "cloud_import", args));
        }
    }

    @Test
    void boardClearanceCannotBeReusedAfterReschedulingOrByAnotherIdentity() throws Exception {
        String release = id(),
                ci = PlatformFoundationTest.record(db, "cmdb_ci", "Database"),
                roster = id(),
                other = id();
        var slot =
                map(
                        "ci",
                        ci,
                        "roster",
                        roster,
                        "start",
                        100,
                        "end",
                        200,
                        "state",
                        "approved",
                        "artifact",
                        "abc");
        write(
                "duty-roster",
                roster,
                map(
                        "anchor",
                        0,
                        "zone",
                        "UTC",
                        "hours",
                        8,
                        "people",
                        List.of(admin, other),
                        "overrides",
                        List.of()));
        assertThrows(Failure.class, () -> ChangeBoard.clear(db, release, "abc", 101));
        assertThrows(Failure.class, () -> ChangeBoard.outcome(db, release, "abc", true, 101));
        write("cab-window", release, slot);
        assertThrows(Failure.class, () -> ChangeBoard.clear(db, release, "abc", 99));
        ChangeBoard.clear(db, release, "abc", 101);
        db.viewerId = other;
        assertThrows(Failure.class, () -> ChangeBoard.outcome(db, release, "abc", true, 201));
        db.viewerId = admin;
        slot.put("start", 400);
        slot.put("end", 500);
        write("cab-window", release, slot);
        assertThrows(Failure.class, () -> ChangeBoard.outcome(db, release, "abc", true, 201));
        ChangeBoard.clear(db, release, "abc", 450);
        ChangeBoard.outcome(db, release, "abc", true, 900);
        var overlap = new LinkedHashMap<>(slot);
        overlap.put("ci", id());
        write("cab-window", other, overlap);
        assertTrue(((List<?>) ChangeBoard.agenda(db, release, slot).get("conflicts")).isEmpty());
        overlap.put("ci", ci);
        write("cab-window", other, overlap);
        assertThrows(Failure.class, () -> ChangeBoard.clear(db, release, "abc", 450));
        overlap.put("start", 500);
        overlap.put("end", 600);
        write("cab-window", other, overlap);
        assertTrue(((List<?>) ChangeBoard.agenda(db, release, slot).get("conflicts")).isEmpty());
    }

    @Test
    void conversationsRefuseUnownedClosedOrUnlistedChoices() throws Exception {
        String session = id(), choice = id();
        var args = map("new_id", session, "intent_id", choice);
        assertThrows(Failure.class, () -> ServiceSearch.command(store,db, "choose_service", args));
        write(
                "employee-conversation",
                session,
                map("owner", admin, "state", "submitted", "choices", List.of(choice)));
        assertThrows(Failure.class, () -> ServiceSearch.command(store,db, "choose_service", args));
        write(
                "employee-conversation",
                session,
                map("owner", admin, "state", "clarification_required", "choices", List.of()));
        assertThrows(Failure.class, () -> ServiceSearch.command(store,db, "choose_service", args));
        assertThrows(Failure.class, () -> ServiceSearch.command(store,db, "search_services", args));
    }

    @Test
    void monitoringSkipsRevokedAuthorityAndUnripeSchedules() throws Exception {
        var config =
                map(
                        "owner",
                        admin,
                        "run",
                        id(),
                        "scheduled_by",
                        admin,
                        "authority",
                        999,
                        "next_at",
                        0,
                        "minutes",
                        1);
        String first = id(), second = id();
        write("grant-monitor", first, config);
        config.put("next_at", 5000);
        write("grant-monitor", second, config);
        GrantIndicators.tick(db, 100);
        assertEquals(0, list(db, "indicator-sample").size());
        assertEquals(1, get(db, "grant-monitor", first).get("revision"));
        db.viewerRole = "agent";
        db.viewerId = id();
        assertThrows(
                Failure.class,
                () -> GrantIndicators.command(db, "indicator_history", map("control_id", first)));
    }

    @Test
    void searchDistinguishesHiddenKnowledgeFromAnUnavailableSource() throws Exception {
        write(
                "employee-intent",
                id(),
                map("article", id(), "offering", id(), "terms", List.of("vpn")));
        for (var reason :
                List.of(
                        new Failure(403, "Source hidden"),
                        new Failure(404, "Removed source"),
                        new Failure(400, "Policy access required"),
                        new Failure(409, "Source configuration changed"))) {
            try (var access = org.mockito.Mockito.mockStatic(EmployeeAssistant.class)) {
                access.when(
                                () ->
                                        EmployeeAssistant.normalized(
                                                org.mockito.ArgumentMatchers.anyString()))
                        .thenCallRealMethod();
                access.when(
                                () ->
                                        EmployeeAssistant.sources(org.mockito.ArgumentMatchers.eq(store),
                                                org.mockito.ArgumentMatchers.eq(db),
                                                org.mockito.ArgumentMatchers.anyMap()))
                        .thenThrow(reason);
                var query = map("new_id", id(), "question", "How do I request VPN?");
                if (reason.status == 409)
                    assertEquals(
                            reason,
                            assertThrows(
                                    Failure.class,
                                    () -> ServiceSearch.command(store,db, "search_services", query)));
                else
                    assertEquals(
                            "handoff_required",
                            object(ServiceSearch.command(store,db, "search_services", query))
                                    .get("state"));
            }
        }
    }

    @Test
    void approvalPolicyRejectsMalformedMappingsAndSelfApproval() throws Exception {
        String id = id(),
                run = id(),
                offering =
                        Catalogue.save(
                                        db,
                                        admin,
                                        "",
                                        CatalogueAcceptance.offering("Approved equipment", 0, true))
                                .get("offering_id")
                                .toString();
        var entry = map("item", "device", "owner_id", admin, "offering_id", offering);
        var b =
                map(
                        "policy_id",
                        id,
                        "title",
                        "Equipment",
                        "revision",
                        0,
                        "entries",
                        List.of(entry));
        for (var fields :
                List.of(map("Bad Key", "good"), map("name", 3), map("key", "NotAField"))) {
            entry.put("answers_from", fields);
            assertThrows(Failure.class, () -> ListApprovals.command(db, "list_policy", b));
        }
        entry.remove("answers_from");
        b.put("entries", List.of());
        assertThrows(Failure.class, () -> ListApprovals.command(db, "list_policy", b));
        b.put("entries", List.of(entry, entry));
        assertThrows(Failure.class, () -> ListApprovals.command(db, "list_policy", b));
        b.put("entries", List.of(entry));
        ListApprovals.command(db, "list_policy", b);
        String purchase = id();
        write("purchase", purchase, map("lines", map("device", map("quantity", 1))));
        assertThrows(
                Failure.class,
                () ->
                        ListApprovals.command(
                                db,
                                "start_list",
                                map(
                                        "policy_id",
                                        id,
                                        "new_id",
                                        run,
                                        "source",
                                        "purchase",
                                        "record_id",
                                        purchase)));
        assertThrows(
                Failure.class,
                () ->
                        AppReleaseTests.configure(
                                db,
                                map(
                                        "app_id",
                                        id,
                                        "requester",
                                        admin,
                                        "reviewer",
                                        admin,
                                        "outsider",
                                        id())));
    }

    @Test
    void grantMonitorIdentifiesRemovedAccountsAndPreservesRetestOwnership() throws Exception {
        String group = id(), run = id(), workflow = id(), recipient = id(), second;
        Service.createAccount(
                db,
                map("name", "NewSigner", "role", "agent", "password", WorkflowSupport.PASSWORD));
        second = RecordAccess.owner(db, "NewSigner");
        db.update("INSERT INTO os_group_member(group_id,user_id) VALUES(?,?)", group, second);
        write("workflow-identity", recipient, map("manager", admin));
        write("access-run", run, map("privileged", workflow));
        var team =
                map(
                        "kind",
                        "team_approval",
                        "state",
                        "done",
                        "group",
                        group,
                        "by",
                        second,
                        "signer_revision",
                        AgentPlans.account(db, second).get("auth_revision"));
        var flow =
                map(
                        "template",
                        workflow,
                        "employee",
                        recipient,
                        "state",
                        "complete",
                        "steps",
                        List.of(
                                map(
                                        "kind",
                                        "approval",
                                        "state",
                                        "done",
                                        "by",
                                        admin,
                                        "signer_revision",
                                        AgentPlans.account(db, admin).get("auth_revision")),
                                team));
        write("workflow-run", run, flow);
        for (String field : List.of("active", "password_reset_required")) {
            db.update(
                    "UPDATE os_user SET " + field + "=? WHERE user_id=?",
                    field.equals("active") ? 0 : 1,
                    second);
            assertEquals(false, GrantIndicators.sample(db, run).get("passed"));
            db.update(
                    "UPDATE os_user SET " + field + "=? WHERE user_id=?",
                    field.equals("active") ? 1 : 0,
                    second);
        }
        team.put("state", "pending");
        write("workflow-run", run, flow);
        assertEquals(false, GrantIndicators.sample(db, run).get("passed"));
        String control = id();
        var args = map("control_id", control, "run_id", run, "owner_id", second, "minutes", 1);
        GrantIndicators.command(db, "monitor_grant", args);
        GrantIndicators.tick(db, 10);
        write("indicator-sample", id(), map("control", id()));
        assertEquals(
                1,
                objects(
                                object(GrantIndicators.command(db, "indicator_history", args))
                                        .get("samples"))
                        .size());
        db.viewerId = second;
        assertThrows(Failure.class, () -> GrantIndicators.command(db, "retest_indicator", args));
        db.viewerId = admin;
        flow.put("state", "in_progress");
        write("workflow-run", run, flow);
        assertEquals(false, GrantIndicators.inspect(db, map("policy", workflow)).get("passed"));
    }

    @Test
    void budgetOptionsRejectOvercommitAndDriftInSpendStaffOrCalendar() throws Exception {
        String goal = id(),
                project = PlatformFoundationTest.record(db, "project", "Platform rollout"),
                team = PlatformFoundationTest.record(db, "sys_user_group", "Delivery"),
                other = id();
        Service.createAccount(
                db,
                map("name", "BudgetReader", "role", "admin", "password", WorkflowSupport.PASSWORD));
        String reviewer = RecordAccess.owner(db, "BudgetReader");
        db.update("INSERT INTO os_group_member(group_id,user_id) VALUES(?,?)", team, reviewer);
        write("project-funding", project, map("amount", "100.00", "currency", "USD"));
        var base =
                map(
                        "quarter",
                        "2027-Q1",
                        "currency",
                        "USD",
                        "ceiling",
                        "100.00",
                        "allocations",
                        map());
        write("funding-goal", goal, base);
        var item =
                map(
                        "project_id",
                        project,
                        "amount",
                        "100",
                        "labor_remaining",
                        "0",
                        "team_id",
                        team);
        String chosen = id();
        var make =
                map(
                        "goal_id",
                        goal,
                        "plan_id",
                        chosen,
                        "title",
                        "Launch",
                        "entries",
                        List.of(item));
        make.put("entries", List.of());
        assertThrows(
                Failure.class, () -> QuarterFunding.command(store, db, "funding_option", make));
        make.put("entries", List.of(item, item));
        assertThrows(
                Failure.class, () -> QuarterFunding.command(store, db, "funding_option", make));
        item.put("amount", "101");
        make.put("entries", List.of(item));
        assertThrows(
                Failure.class, () -> QuarterFunding.command(store, db, "funding_option", make));
        item.put("amount", "100");
        write(
                "funding-labor",
                id(),
                map("input", map("goal", other, "project", project), "amount", "1000"));
        write(
                "funding-labor",
                id(),
                map("input", map("goal", goal, "project", other), "amount", "200"));
        QuarterFunding.command(store, db, "funding_option", make);
        var approve = map("goal_id", goal, "plan_id", chosen, "revision", 1);
        assertThrows(
                Failure.class,
                () ->
                        QuarterFunding.command(
                                store,
                                db,
                                "compare_funding",
                                map("goal_id", goal, "plan_id", chosen, "other_id", chosen)));
        write("funding-option", other, map("goal", other));
        assertThrows(
                Failure.class,
                () ->
                        QuarterFunding.command(
                                store,
                                db,
                                "compare_funding",
                                map("goal_id", goal, "plan_id", chosen, "other_id", other)));
        db.viewerId = reviewer;
        var point = db.connection.setSavepoint();
        write("purchase-reservation", id(), map("project", project, "amount", "1.00"));
        assertThrows(
                Failure.class, () -> QuarterFunding.command(store, db, "approve_funding", approve));
        db.connection.rollback(point);
        write(
                "staff-calendar",
                reviewer,
                map("week", List.of(0, 0, 0, 0, 0, 0, 0), "exceptions", map()));
        assertThrows(
                Failure.class, () -> QuarterFunding.command(store, db, "approve_funding", approve));
        db.connection.rollback(point);
        write("portfolio", project, map("remaining", "2"));
        assertThrows(
                Failure.class, () -> QuarterFunding.command(store, db, "approve_funding", approve));
        db.connection.rollback(point);
        QuarterFunding.command(store, db, "approve_funding", approve);
        assertThrows(
                Failure.class, () -> QuarterFunding.command(store, db, "approve_funding", approve));
        var labor =
                map(
                        "goal_id",
                        goal,
                        "project_id",
                        project,
                        "user_id",
                        admin,
                        "day",
                        "2027-02-01",
                        "new_id",
                        id(),
                        "minutes",
                        60);
        assertThrows(Failure.class, () -> QuarterFunding.command(store, db, "book_labor", labor));
        labor.put("project_id", other);
        assertThrows(Failure.class, () -> QuarterFunding.command(store, db, "book_labor", labor));
    }

    @Test
    void importsRejectWritesAndNonStringCheckpoints() throws Exception {
        String pkg = id(), binding = DeliveryWorkspace.key(pkg, "test");
        var cfg =
                map(
                        "package_id",
                        pkg,
                        "environment",
                        "test",
                        "cursor_input",
                        "after",
                        "cursor_field",
                        "event");
        for (var spec :
                List.of(
                        map("method", "POST", "outputs", map("event", "string")),
                        map("method", "GET", "outputs", map("event", "integer")))) {
            write("rest-binding", binding, map("spec", spec));
            assertThrows(Failure.class, () -> IncrementalImports.configure(db, cfg));
        }
        write("incremental-import", binding, map("active", false, "next_at", 0));
        db.connection.commit();
        db.close();
        IncrementalImports.poll(store, 10);
    }

    @Test
    void evaluationCountsDisclosuresInSuggestionsEvenWhenTheEmployeeDoesNotChooseThem()
            throws Exception {
        String conversation = id(), suite = id(), restricted = id();
        write(
                "employee-holdout",
                suite,
                map(
                        "cases",
                        List.of(
                                map(
                                        "conversation",
                                        conversation,
                                        "employee",
                                        admin,
                                        "expected",
                                        "handed_off",
                                        "group_id",
                                        id(),
                                        "forbidden_articles",
                                        List.of(restricted)))));
        write(
                "employee-conversation",
                conversation,
                map(
                        "owner",
                        admin,
                        "state",
                        "clarification_required",
                        "shown_articles",
                        List.of(restricted)));
        var score = object(EmployeeOutcomes.command(db, "score", map("new_id", suite)));
        assertEquals(1, score.get("unauthorized_citations"));
        assertEquals(0, score.get("verified_successes"));
    }

    @Test
    void exchangedAssetHandoffRequiresAReplacementForThisVisitAndCustomer() throws Exception {
        String task = id(), account = id(), serial = id(), other = id();
        var returned = map("returned_for", task, "former_account", account);
        assertThrows(Failure.class, () -> DepotTransfers.replacement(db, other, account, returned));
        assertThrows(Failure.class, () -> DepotTransfers.replacement(db, task, other, returned));
        write("depot-transfer", id(), map("task", other, "state", "installed", "asset", serial));
        write("depot-transfer", id(), map("task", task, "state", "reserved", "asset", serial));
        assertThrows(Failure.class, () -> DepotTransfers.replacement(db, task, account, returned));
        write("depot-transfer", id(), map("task", task, "state", "installed", "asset", serial));
        write("installation-account", serial, map("account", other));
        assertThrows(Failure.class, () -> DepotTransfers.replacement(db, task, account, returned));
        write("installation-account", serial, map("account", account));
        assertEquals(serial, DepotTransfers.replacement(db, task, account, returned));
    }

    @Test
    void boardAndListReviewsRejectExpiredOrInvalidDecisions() throws Exception {
        String release = id(),
                change = PlatformFoundationTest.record(db, "change_request", "Deploy"),
                ci = id(),
                roster = id();
        write(
                "pipeline-release",
                release,
                map(
                        "state",
                        "authorized",
                        "change_id",
                        change,
                        "policy",
                        map("pipeline", admin, "ci", ci),
                        "submitted",
                        map("artifact", "a")));
        write(
                "duty-roster",
                roster,
                map(
                        "anchor",
                        0,
                        "zone",
                        "UTC",
                        "hours",
                        8,
                        "people",
                        List.of(admin, id()),
                        "overrides",
                        List.of()));
        var b =
                map(
                        "release_id",
                        release,
                        "start",
                        "2030-01-01T10:00:00Z",
                        "end",
                        "2030-01-01T09:00:00Z",
                        "roster_id",
                        roster,
                        "revision",
                        0,
                        "note",
                        "Review");
        assertThrows(Failure.class, () -> ChangeBoard.command(db, "cab_schedule", b));
        b.put("end", "2030-01-01T11:00:00Z");
        ChangeBoard.command(db, "cab_schedule", b);
        b.put("revision", 1);
        assertThrows(Failure.class, () -> ChangeBoard.command(db, "cab_approve", b));
        assertThrows(Failure.class, () -> ChangeBoard.guard(db, release, "a", 0));
        String policy = id(), order = id(), run = id(), reject = id(), offer = id();
        write("list-policy", policy, map("bindings", map()));
        var input = map();
        for (int size : List.of(0, 33)) {
            for (int i = 0; i < size; i++) input.put("row" + i, map());
            write("purchase", order, map("lines", input));
            assertThrows(
                    Failure.class,
                    () ->
                            ListApprovals.command(
                                    db,
                                    "start_list",
                                    map(
                                            "policy_id",
                                            policy,
                                            "source",
                                            "purchase",
                                            "record_id",
                                            order,
                                            "new_id",
                                            id())));
        }
        write(
                "list-run",
                run,
                map(
                        "state",
                        "ready",
                        "approvals",
                        map(admin, "approved", reject, "rejected"),
                        "lines",
                        map(
                                "old",
                                map("owner", reject),
                                "new",
                                map("owner", admin, "revision", 1, "offering", offer)),
                        "record",
                        order));
        assertThrows(
                Failure.class,
                () -> ListApprovals.command(db, "list_decision", map("new_id", run)));
        try (var catalogue = org.mockito.Mockito.mockStatic(Catalogue.class)) {
            catalogue
                    .when(
                            () ->
                                    Catalogue.order(
                                            org.mockito.ArgumentMatchers.eq(db),
                                            org.mockito.ArgumentMatchers.eq(offer),
                                            org.mockito.ArgumentMatchers.anyMap()))
                    .thenReturn(map("sys_id", id()));
            var result =
                    object(
                            ListApprovals.command(
                                    db,
                                    "fulfill_line",
                                    map(
                                            "new_id",
                                            run,
                                            "revision",
                                            1,
                                            "item",
                                            "new",
                                            "manifest",
                                            map())));
            assertEquals("complete", object(result.get("definition")).get("state"));
        }
        for (String key : List.of("owner_1", "offering_1")) {
            var form = map("designer_rows", "entries", "operation", "list_policy", key, id());
            DesignInputs.read(map("title", "Decision workflows"), form);
            assertTrue(form.get("entries").toString().contains("owner_id"));
        }
        var form =
                map(
                        "designer_rows",
                        "fields",
                        "operation",
                        "build",
                        "name_1",
                        "asset",
                        "label_1",
                        "Asset",
                        "reference_1",
                        "cmdb_ci",
                        "visibility_1",
                        "confidential");
        DesignInputs.read(map("title", "Intake applications"), form);
        assertTrue(form.get("fields").toString().contains("reference_table"));
    }

    @Test
    void agreementSignatureRejectsStaleAndMisdirectedAttestations() throws Exception {
        String hr = id(), doc = id();
        var args = map("case_id", hr, "document_id", doc, "digest", "d", "revision", 1);
        var valid =
                map(
                        "case_id",
                        hr,
                        "employee",
                        admin,
                        "state",
                        "pending",
                        "superseded",
                        false,
                        "digest",
                        "d",
                        "retain_until",
                        "2099-01-01",
                        "location",
                        "First",
                        "effective",
                        "2028-01-01");
        var rec = map("data", map("state", "open"));
        try (var cases = org.mockito.Mockito.mockStatic(EmployeeCases.class)) {
            cases.when(() -> EmployeeCases.record(db, hr, true)).thenReturn(rec);
            cases.when(() -> EmployeeCases.record(db, hr, false)).thenReturn(rec);
            for (var altered :
                    List.of(
                            map("employee", id()),
                            map("state", "signed"),
                            map("superseded", true),
                            map("retain_until", "2000-01-01"))) {
                var row = new LinkedHashMap<>(valid);
                row.putAll(altered);
                write("agreement", doc, row);
                assertThrows(
                        Failure.class,
                        () -> EmployeeAgreements.command(db, "sign_agreement", args));
            }
            write("agreement", doc, valid);
            object(rec.get("data")).put("state", "cancelled");
            assertThrows(
                    Failure.class, () -> EmployeeAgreements.command(db, "sign_agreement", args));
            for (String type : List.of("transfer", "leave")) {
                write("employment-change", hr, map("type", type));
                assertThrows(
                        Failure.class,
                        () -> EmployeeAgreements.command(db, "transfer_agreement", args));
            }
            write("agreement-head", hr, map("document", doc));
            EmployeeAgreements.refresh(
                    db,
                    hr,
                    map("space", "Elsewhere", "effective", "2028-01-01", "state", "scheduled"));
            assertEquals(true, JourneyForms.definition(db, "agreement", doc).get("superseded"));
        }
    }

    @Test
    void releaseTestsDetectOverexposureAndPropagateOperationalFailures() throws Exception {
        String app = id();
        var spec = map("fields", map("private", map("visibility", "confidential")), "group", id());
        for (String persona : List.of("outsider", "requester", "unavailable")) {
            write(
                    "app-release-test",
                    app,
                    map(
                            "users",
                            map(persona.equals("unavailable") ? "reviewer" : persona, admin),
                            "field",
                            "private"));
            try (var intake = org.mockito.Mockito.mockStatic(IntakeApps.class)) {
                var view =
                        intake.when(
                                () ->
                                        IntakeApps.command(
                                                org.mockito.ArgumentMatchers.eq(db),
                                                org.mockito.ArgumentMatchers.eq("case"),
                                                org.mockito.ArgumentMatchers.anyMap()));
                if (persona.equals("unavailable")) view.thenThrow(new Failure(409, "Case changed"));
                else
                    view.thenReturn(
                            map("case", map("private_answers", map("private", "sensitive"))));
                assertThrows(Failure.class, () -> AppReleaseTests.verify(db, app, spec));
                assertEquals(admin, db.viewerId);
            }
        }
        String caseId = id(), ref = id();
        write(
                "intake-case",
                caseId,
                map(
                        "requester",
                        admin,
                        "group",
                        id(),
                        "fields",
                        map("ref", map("reference_table", "cmdb_ci")),
                        "answers",
                        map("ref", ref)));
        for (int status : List.of(403, 404, 409)) {
            try (var service = org.mockito.Mockito.mockStatic(Service.class)) {
                service.when(() -> Service.readRecord(db, "cmdb_ci", ref))
                        .thenThrow(new Failure(status, "Record changed"));
                if (status == 409)
                    assertThrows(
                            Failure.class,
                            () -> IntakeApps.command(db, "case", map("case_id", caseId)));
                else
                    assertTrue(
                            object(
                                            object(
                                                            object(
                                                                            IntakeApps.command(
                                                                                    db,
                                                                                    "case",
                                                                                    map(
                                                                                            "case_id",
                                                                                            caseId)))
                                                                    .get("case"))
                                                    .get("answers"))
                                    .isEmpty());
            }
        }
    }

    @Test
    void depotTransfersRejectWrongVisitStateStockroomTechnicianOrCustomer() throws Exception {
        String taskId = id(),
                parent = id(),
                asset = id(),
                depot = PlatformFoundationTest.record(db, "stockroom", "Stock"),
                account = id(),
                technician = admin;
        var task = map("parent_table", "incident", "parent_id", parent);
        var dispatch = map("status", "assigned", "technician_id", technician);
        var move = map("state", "registered", "stockroom_id", depot, "assignee_id", id());
        var args =
                map(
                        "task_id",
                        taskId,
                        "asset_id",
                        asset,
                        "stockroom_id",
                        depot,
                        "model",
                        "Monitor",
                        "entries",
                        List.of(depot),
                        "revision",
                        0);
        var tx = org.mockito.Mockito.spy(db);
        String customerSql = "SELECT account_id,user_id FROM os_customer_case WHERE record_id=?";
        try (var plans = org.mockito.Mockito.mockStatic(FieldPlanning.class);
                var visits = org.mockito.Mockito.mockStatic(FieldDispatch.class);
                var custody = org.mockito.Mockito.mockStatic(AssetCustody.class)) {
            plans.when(() -> FieldPlanning.task(tx, taskId)).thenReturn(task);
            visits.when(() -> FieldDispatch.current(tx, taskId)).thenReturn(dispatch);
            custody.when(() -> AssetCustody.latest(tx, asset)).thenReturn(move);
            assertThrows(Failure.class, () -> DepotTransfers.source(tx, args));
            task.put("parent_table", "customer_case");
            assertThrows(Failure.class, () -> DepotTransfers.source(tx, args));
            custody.when(() -> AssetCustody.writable(tx)).thenReturn(true);
            org.mockito.Mockito.doReturn(
                            List.of(map("record_id", asset, "detail", map("model", "Printer"))))
                    .when(tx)
                    .all(
                            org.mockito.ArgumentMatchers.startsWith("SELECT m.record_id,m.detail"),
                            org.mockito.ArgumentMatchers.eq(depot));
            assertEquals(true, object(DepotTransfers.source(tx, args)).get("shortage"));
            assertThrows(Failure.class, () -> DepotTransfers.command(tx, "reserve_serial", args));
            org.mockito.Mockito.doReturn(map("account_id", account, "user_id", admin))
                    .when(tx)
                    .one(customerSql, parent);
            assertThrows(Failure.class, () -> DepotTransfers.command(tx, "reserve_serial", args));
            move.put("state", "in_stock");
            move.put("stockroom_id", id());
            assertThrows(Failure.class, () -> DepotTransfers.command(tx, "reserve_serial", args));
            move.put("stockroom_id", depot);
            assertThrows(Failure.class, () -> DepotTransfers.command(tx, "release_serial", args));
            String key = DeliveryWorkspace.key(taskId, asset);
            var reserved = map("state", "reserved", "technician", id());
            write("depot-transfer", key, reserved);
            write("guide-stock", asset, map("request", id()));
            assertThrows(Failure.class, () -> DepotTransfers.command(tx, "release_serial", args));
            write("guide-stock", asset, map("request", taskId));
            assertThrows(Failure.class, () -> DepotTransfers.command(tx, "pickup_serial", args));
            reserved.put("technician", technician);
            write("depot-transfer", key, reserved);
            assertThrows(Failure.class, () -> DepotTransfers.command(tx, "pickup_serial", args));
            write("installation-account", asset, map("account", id()));
            assertThrows(Failure.class, () -> DepotTransfers.command(tx, "return_serial", args));
            write("installation-account", asset, map("account", account));
            assertThrows(Failure.class, () -> DepotTransfers.command(tx, "return_serial", args));
            assertThrows(Failure.class, () -> DepotTransfers.install(tx, taskId, asset));
            write("depot-transfer", key, map("state", "picked_up", "technician", id()));
            assertThrows(Failure.class, () -> DepotTransfers.install(tx, taskId, asset));
            write("depot-transfer", key, map("state", "picked_up", "technician", tx.viewerId));
            assertThrows(Failure.class, () -> DepotTransfers.install(tx, taskId, asset));
        }
    }

    @Test
    void closedControlRetestsAndNonPrivilegedRunsAreExcluded() throws Exception {
        String policy = id(), run = id(), control = id();
        write("access-run", run, map("privileged", policy));
        write("workflow-run", run, map("state", "complete", "template", id()));
        assertEquals(false, GrantIndicators.inspect(db, map("policy", policy)).get("passed"));
        write("grant-monitor", control, map("owner", id(), "run", run));
        write("indicator-issue", control, map("state", "closed"));
        assertThrows(
                Failure.class,
                () -> GrantIndicators.command(db, "retest_indicator", map("control_id", control)));
    }
}
