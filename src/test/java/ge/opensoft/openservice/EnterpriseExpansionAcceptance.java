package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseAcceptance.*;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class EnterpriseExpansionAcceptance {
    static Map<String, Object> cmd(
            WorkflowSupport h, String user, String area, String op, Object... fields)
            throws Exception {
        var b = map(fields);
        b.put("operation", op);
        var r = h.json("POST", api(area, "global"), b, auth(user));
        assertEquals(200, r.statusCode(), html(r));
        return object(EmployeeCasesAcceptance.result(r).get("outcome"));
    }

    static void denied(WorkflowSupport h, String user, String area, String op, Object... fields)
            throws Exception {
        var b = map(fields);
        b.put("operation", op);
        var r = h.json("POST", api(area, "global"), b, auth(user));
        assertTrue(Set.of(400, 403, 404, 409).contains(r.statusCode()), html(r));
    }

    static void seed(Store.Db db, String area, String id, Map<String, Object> data)
            throws Exception {
        EnterpriseData.save(db, area, id, EnterpriseData.get(db, area, id).get("revision"), data);
    }

    static void delta(WorkflowSupport h) throws Exception {
        try (var provider = new RecordQuestionsAcceptance.Provider(h.store, h.environment)) {
            var busy = new AtomicBoolean(true);
            var pagination = new java.util.concurrent.atomic.AtomicReference<>("ordinary");
            var number = new java.util.concurrent.atomic.AtomicInteger();
            var seen = new ArrayList<String>();
            provider.server.createContext(
                    "/delta",
                    x -> {
                        String query = x.getRequestURI().getRawQuery();
                        seen.add(query);
                        if ("after=b".equals(query) && busy.get()) {
                            x.sendResponseHeaders(429, -1);
                            x.close();
                            return;
                        }
                        var rows =
                                switch (query) {
                                    case "after=a" -> List.of(map("event", "b"));
                                    case "after=b" -> List.of(map("event", "b"), map("event", "c"));
                                    default -> List.of();
                                };
                        var bytes =
                                JSON.writeValueAsBytes(
                                        map(
                                                "rows",
                                                rows,
                                                "next",
                                                switch (pagination.get()) {
                                                    case "other" -> "/another";
                                                    case "cycle" -> "/delta?after=c";
                                                    case "legacy" -> "/delta?page=prior";
                                                    case "long" ->
                                                            "/delta?page="
                                                                    + number.incrementAndGet();
                                                    default ->
                                                            "after=a".equals(query)
                                                                    ? "/delta?after=b"
                                                                    : "";
                                                }));
                        x.sendResponseHeaders(200, bytes.length);
                        x.getResponseBody().write(bytes);
                        x.close();
                    });
            String pkg = id();
            var spec =
                    map(
                            "method",
                            "GET",
                            "auth",
                            "bearer",
                            "path",
                            "/delta",
                            "inputs",
                            List.of(
                                    map(
                                            "key",
                                            "after",
                                            "type",
                                            "string",
                                            "label",
                                            "After",
                                            "required",
                                            true,
                                            "choices",
                                            List.of())),
                            "outputs",
                            map("event", "string"),
                            "items",
                            "rows",
                            "next",
                            "next");
            cmd(
                    h,
                    "admin",
                    "packages",
                    "rest_define",
                    "package_id",
                    pkg,
                    "manifest",
                    spec,
                    "revision",
                    0,
                    "title",
                    "Audit delta");
            cmd(
                    h,
                    "admin",
                    "packages",
                    "rest_bind",
                    "package_id",
                    pkg,
                    "environment",
                    "test",
                    "alias",
                    provider.alias,
                    "revision",
                    0);
            cmd(
                    h,
                    "admin",
                    "packages",
                    "schedule_delta",
                    "package_id",
                    pkg,
                    "environment",
                    "test",
                    "cursor_input",
                    "after",
                    "cursor_field",
                    "event",
                    "cursor",
                    "a",
                    "minutes",
                    1,
                    "active",
                    true,
                    "manifest",
                    map(),
                    "revision",
                    0);
            IncrementalImports.poll(h.store, 100);
            IncrementalImports.poll(h.store, 101);
            var status =
                    object(
                            cmd(
                                            h,
                                            "admin",
                                            "packages",
                                            "delta_status",
                                            "package_id",
                                            pkg,
                                            "environment",
                                            "test")
                                    .get("definition"));
            assertEquals("retry_pending", status.get("state"));
            assertEquals("b", object(status.get("values")).get("after"));
            busy.set(false);
            IncrementalImports.poll(h.store, 60099);
            IncrementalImports.poll(new Store(h.environment), 60101);
            IncrementalImports.poll(h.store, 120102);
            assertEquals(List.of("after=a", "after=b", "after=b", "after=c"), seen);
            assertEquals(
                    "c",
                    cmd(
                                    h,
                                    "admin",
                                    "packages",
                                    "read_change",
                                    "package_id",
                                    pkg,
                                    "environment",
                                    "test",
                                    "cursor",
                                    "c")
                            .get("event"));
            denied(
                    h,
                    "admin",
                    "packages",
                    "read_change",
                    "package_id",
                    pkg,
                    "environment",
                    "production",
                    "cursor",
                    "c");
            long tick = 200000;
            for (String mode : List.of("other", "cycle", "long", "legacy")) {
                if (mode.equals("legacy")) {
                    try (var db = h.store.open()) {
                        PlatformFoundationTest.bind(db);
                        String binding = ImportReceipts.binding(map("package_id",pkg,"environment","test"));
                        var row = EnterpriseData.get(db,"incremental-import",binding);
                        var prior = object(row.get("definition"));
                        prior.put("visited",List.of("/delta?page=prior"));
                        EnterpriseData.save(db,"incremental-import",binding,row.get("revision"),prior);
                        db.connection.commit();
                    }
                }
                pagination.set(mode);
                for (int i = 0; i < (mode.equals("long") ? 256 : 1); i++)
                    IncrementalImports.poll(h.store, tick++);
                var progress =
                        object(
                                cmd(
                                                h,
                                                "admin",
                                                "packages",
                                                "delta_status",
                                                "package_id",
                                                pkg,
                                                "environment",
                                                "test")
                                        .get("definition"));
                assertEquals("retry_pending", progress.get("state"));
                assertEquals("c", object(progress.get("values")).get("after"));
                tick += 60001;
            }
            try (var db = h.store.open()) {
                assertEquals(2, db.count("SELECT COUNT(*) FROM os_import_receipt WHERE binding=?",
                        ImportReceipts.binding(map("package_id", pkg, "environment", "test"))));
            }
        }
    }

    static void approvals(WorkflowSupport h) throws Exception {
        h.account("BudgetOwner", "agent");
        h.account("AccessOwner", "agent");
        String reviewer = uid(h, "BudgetOwner"),
                second = uid(h, "AccessOwner"),
                laptop = ServiceDeliveryAcceptance.offering(h, "Laptop"),
                screen = ServiceDeliveryAcceptance.offering(h, "Screen"),
                access = ServiceDeliveryAcceptance.offering(h, "Engineering access"),
                policy = id(),
                record = id(),
                run = id();
        var equipment = map("model", "Field laptop", "quantity", 1);
        try (var db = h.store.open()) {
            String admin = PlatformFoundationTest.bind(db);
            CatalogueQuestions.save(
                    db,
                    admin,
                    laptop,
                    map(
                            "revision",
                            1,
                            "questions",
                            List.of(
                                    map(
                                            "key",
                                            "device",
                                            "label",
                                            "Device model",
                                            "type",
                                            "string",
                                            "required",
                                            true,
                                            "choices",
                                            List.of()))));
            seed(
                    db,
                    "purchase",
                    record,
                    map(
                            "lines",
                            map(
                                    "equipment",
                                    equipment,
                                    "display",
                                    map("quantity", 2),
                                    "access",
                                    map("quantity", 1))));
            db.connection.commit();
        }
        cmd(
                h,
                "admin",
                "designer",
                "list_policy",
                "policy_id",
                policy,
                "revision",
                0,
                "title",
                "Budget review",
                "entries",
                List.of(
                        map(
                                "item",
                                "equipment",
                                "owner_id",
                                reviewer,
                                "offering_id",
                                laptop,
                                "answers_from",
                                map("device", "model")),
                        map("item", "display", "owner_id", reviewer, "offering_id", screen),
                        map("item", "access", "owner_id", second, "offering_id", access)));
        cmd(
                h,
                "admin",
                "designer",
                "start_list",
                "new_id",
                run,
                "policy_id",
                policy,
                "source",
                "purchase",
                "record_id",
                record,
                "manifest",
                map());
        denied(
                h,
                "admin",
                "designer",
                "list_decision",
                "new_id",
                run,
                "decision",
                "approved",
                "revision",
                1);
        assertEquals(
                Set.of("equipment", "display"),
                object(cmd(h, "BudgetOwner", "designer", "review_list", "new_id", run).get("items"))
                        .keySet());
        cmd(
                h,
                "BudgetOwner",
                "designer",
                "list_decision",
                "new_id",
                run,
                "decision",
                "approved",
                "revision",
                1);
        denied(
                h,
                "admin",
                "designer",
                "fulfill_line",
                "new_id",
                run,
                "item",
                "equipment",
                "manifest",
                map(),
                "revision",
                2);
        cmd(
                h,
                "AccessOwner",
                "designer",
                "list_decision",
                "new_id",
                run,
                "decision",
                "approved",
                "revision",
                2);
        var first =
                cmd(
                        h,
                        "admin",
                        "designer",
                        "fulfill_line",
                        "new_id",
                        run,
                        "item",
                        "equipment",
                        "manifest",
                        map(),
                        "revision",
                        3);
        assertEquals(
                first,
                cmd(
                        h,
                        "admin",
                        "designer",
                        "fulfill_line",
                        "new_id",
                        run,
                        "item",
                        "equipment",
                        "manifest",
                        map(),
                        "revision",
                        3));
        var two =
                object(
                        cmd(
                                        h,
                                        "admin",
                                        "designer",
                                        "fulfill_line",
                                        "new_id",
                                        run,
                                        "item",
                                        "display",
                                        "manifest",
                                        map(),
                                        "revision",
                                        4)
                                .get("definition"));
        var error =
                object(
                        cmd(
                                        h,
                                        "admin",
                                        "designer",
                                        "fulfill_line",
                                        "new_id",
                                        run,
                                        "item",
                                        "access",
                                        "manifest",
                                        map("unknown", "never ordered"),
                                        "revision",
                                        5)
                                .get("definition"));
        assertTrue(object(object(error.get("lines")).get("access")).containsKey("error"));
        var completed =
                object(
                        cmd(
                                        h,
                                        "admin",
                                        "designer",
                                        "fulfill_line",
                                        "new_id",
                                        run,
                                        "item",
                                        "access",
                                        "manifest",
                                        map(),
                                        "revision",
                                        6)
                                .get("definition"));
        assertEquals("complete", completed.get("state"));
        assertEquals(two.get("approvals"), completed.get("approvals"));
        for (String name : List.of("equipment", "display"))
            assertEquals(
                    object(two.get("lines")).get(name), object(completed.get("lines")).get(name));
        var setup = EmployeeCasesAcceptance.setup(h);
        String hr = EmployeeCasesAcceptance.create(h, setup[0], "EmployeeOne"), other = id();
        cmd(
                h,
                "admin",
                "designer",
                "start_list",
                "new_id",
                other,
                "policy_id",
                policy,
                "source",
                "hr_case",
                "record_id",
                hr,
                "manifest",
                map("equipment", map("model", "Transfer loaner")));
        cmd(
                h,
                "BudgetOwner",
                "designer",
                "list_decision",
                "new_id",
                other,
                "decision",
                "rejected",
                "revision",
                1);
        assertEquals(
                "rejected",
                object(
                                cmd(h, "admin", "designer", "list_status", "new_id", other)
                                        .get("definition"))
                        .get("state"));
    }

    static void apps(WorkflowSupport h) throws Exception {
        for (String user : List.of("Employee", "Outside", "Lawyer", "Publisher"))
            h.account(
                    user,
                    switch (user) {
                        case "Lawyer" -> "agent";
                        case "Publisher" -> "admin";
                        default -> "requester";
                    });
        String team = h.add("sys_user_group").get("sys_id").toString(),
                app = id(),
                design = ServiceDeliveryAcceptance.decision(h, id());
        FulfilmentAcceptance.member(h, team, "Lawyer", true);
        cmd(
                h,
                "admin",
                "apps",
                "release_test",
                "app_id",
                app,
                "requester",
                uid(h, "Employee"),
                "reviewer",
                uid(h, "Lawyer"),
                "outsider",
                uid(h, "Outside"),
                "private_field",
                "brief",
                "revision",
                0);
        var fields =
                List.of(
                        map(
                                "name",
                                "brief",
                                "label",
                                "Evidence",
                                "visibility",
                                "confidential",
                                "reference_table",
                                "sys_user_group"));
        cmd(
                h,
                "admin",
                "apps",
                "build",
                "app_id",
                app,
                "title",
                "Supplier exception",
                "group_id",
                team,
                "design_id",
                design,
                "fields",
                fields,
                "revision",
                0);
        cmd(
                h,
                "Publisher",
                "apps",
                "tested_promote",
                "app_id",
                app,
                "revision",
                1,
                "release_revision",
                0);
        String ref = h.add("sys_user_group").get("sys_id").toString(), caseId = id();
        try (var db = h.store.open()) {
            String admin = PlatformFoundationTest.bind(db);
            var submit =
                    map(
                            "app_id",
                            app,
                            "new_id",
                            caseId,
                            "entries",
                            List.of(map("name", "brief", "value", ref)),
                            "revision",
                            1,
                            "risk",
                            "low",
                            "location",
                            "HQ",
                            "amount",
                            "0");
            // A newly submitted case appears in the assigned team's queue without first opening it.
            var beforeWorkspace=db.connection.setSavepoint();
            String stage=id();
            GuidedWorkspaces.run(db,"review_stage",map("stage_id",stage,"group_id",team,"title","Legal","note","Review the request","revision",0));
            GuidedWorkspaces.run(db,"compose_workspace",map("app_id",app,"title","Legal intake","fields",List.of("brief"),"steps",List.of(stage),"revision",0));
            IntakeApps.command(db,"submit",submit);
            db.viewerId=RecordAccess.owner(db,"Lawyer");db.viewerRole="agent";
            assertEquals(caseId,objects(GuidedWorkspaces.run(db,"guided_queue",map("app_id",app))).getFirst().get("case_id"));
            db.connection.rollback(beforeWorkspace);db.connection.releaseSavepoint(beforeWorkspace);
            db.viewerId=admin;db.viewerRole="admin";
            IntakeApps.command(db, "submit", submit);
            db.viewerId = RecordAccess.owner(db, "Lawyer");
            db.viewerRole = "agent";
            assertEquals(
                    ref,
                    object(
                                    object(
                                                    object(
                                                                    IntakeApps.command(
                                                                            db,
                                                                            "case",
                                                                            map("case_id", caseId)))
                                                            .get("case"))
                                            .get("private_answers"))
                            .get("brief"));
            db.update(
                    "INSERT INTO os_record_access(record_id,visibility,owner_id) VALUES(?,?,?) ON"
                            + " CONFLICT(record_id) DO UPDATE SET"
                            + " visibility=excluded.visibility,owner_id=excluded.owner_id",
                    ref,
                    "private",
                    admin);
            assertFalse(
                    object(
                                    object(
                                                    object(
                                                                    IntakeApps.command(
                                                                            db,
                                                                            "case",
                                                                            map("case_id", caseId)))
                                                            .get("case"))
                                            .get("private_answers"))
                            .containsKey("brief"));
            db.connection.rollback();
        }
        fields.getFirst().put("visibility", "requester");
        cmd(
                h,
                "admin",
                "apps",
                "build",
                "app_id",
                app,
                "title",
                "Unsafe change",
                "group_id",
                team,
                "design_id",
                design,
                "fields",
                fields,
                "revision",
                1);
        denied(
                h,
                "Publisher",
                "apps",
                "promote",
                "app_id",
                app,
                "revision",
                2,
                "release_revision",
                1);
        var export = cmd(h, "admin", "apps", "package_app", "app_id", app);
        assertEquals("confidential", objects(export.get("fields")).getFirst().get("visibility"));
        assertEquals(
                "sys_user_group", objects(export.get("fields")).getFirst().get("reference_table"));
    }

    static String graph(Store.Db db, String ci, String ci2) throws Exception {
        String source = "aws";
        var resources =
                map(
                        "lb",
                        map(
                                "title",
                                "Load balancer",
                                "type",
                                "elb",
                                "depends_on",
                                List.of("db"),
                                "ci_id",
                                ci),
                        "db",
                        map(
                                "title",
                                "Database",
                                "type",
                                "rds",
                                "depends_on",
                                List.of(),
                                "ci_id",
                                ci2));
        seed(
                db,
                "service-map",
                source,
                map(
                        "observed",
                        now(),
                        "sequence",
                        1,
                        "resources",
                        resources,
                        "services",
                        map("Ordering", map("roots", List.of("lb")))));
        return "aws :: Ordering";
    }

    static void cab(WorkflowSupport h) throws Exception {
        h.account("Pipeline", "agent");
        String pipe = uid(h, "Pipeline"),
                roster = id(),
                a = id(),
                b = id(),
                ci = h.add("cmdb_ci").get("sys_id").toString(),
                ci2 = h.add("cmdb_ci").get("sys_id").toString();
        String change = h.add("change_request").get("sys_id").toString();
        try (var db = h.store.open()) {
            String admin = PlatformFoundationTest.bind(db);
            graph(db, ci, ci2);
            seed(
                    db,
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
                            List.of(admin, pipe),
                            "overrides",
                            List.of()));
            for (String id : List.of(a, b))
                seed(
                        db,
                        "pipeline-release",
                        id,
                        map(
                                "change_id",
                                change,
                                "state",
                                "authorized",
                                "policy",
                                map("pipeline", pipe, "ci", id.equals(a) ? ci : ci2),
                                "submitted",
                                map("artifact", "sha-1")));
            db.connection.commit();
        }
        for (String id : List.of(a, b))
            cmd(
                    h,
                    "admin",
                    "releases",
                    "cab_schedule",
                    "release_id",
                    id,
                    "start",
                    "2030-01-01T10:00:00Z",
                    "end",
                    "2030-01-01T11:00:00Z",
                    "note",
                    "Board review",
                    "roster_id",
                    roster,
                    "revision",
                    0);
        cmd(
                h,
                "admin",
                "releases",
                "cab_approve",
                "release_id",
                a,
                "note",
                "Reviewed on call",
                "revision",
                1);
        assertEquals(
                2,
                ((List<?>)
                                cmd(h, "admin", "releases", "cab_history", "release_id", a)
                                        .get("decisions"))
                        .size());
        var agenda =
                object(cmd(h, "admin", "releases", "cab_agenda", "release_id", b).get("review"));
        assertEquals(List.of(a), agenda.get("conflicts"));
        assertTrue(((List<?>) agenda.get("services")).contains("aws :: Ordering"));
        denied(
                h,
                "admin",
                "releases",
                "cab_approve",
                "release_id",
                b,
                "note",
                "Collision",
                "revision",
                1);
        cmd(
                h,
                "admin",
                "releases",
                "cab_schedule",
                "release_id",
                b,
                "start",
                "2030-01-01T11:00:00Z",
                "end",
                "2030-01-01T12:00:00Z",
                "note",
                "New time",
                "roster_id",
                roster,
                "revision",
                1);
        cmd(
                h,
                "admin",
                "releases",
                "cab_approve",
                "release_id",
                b,
                "note",
                "Adjacent",
                "revision",
                2);
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            long at = Instant.parse("2030-01-01T11:30:00Z").toEpochMilli();
            assertEquals(
                    ChangeBoard.clear(db, b, "sha-1", at), ChangeBoard.clear(db, b, "sha-1", at));
            ChangeBoard.outcome(db, b, "sha-1", true, at + 86400000);
            assertThrows(
                    Failure.class,
                    () -> ChangeBoard.outcome(db, b, "different", true, at + 86400000));
            ChangeBoard.guard(db, b, "sha-1", at);
            assertThrows(Failure.class, () -> ChangeBoard.guard(db, b, "wrong", at));
            assertThrows(Failure.class, () -> ChangeBoard.guard(db, a, "sha-1", at));
        }
    }

    static void cloud(WorkflowSupport h) throws Exception {
        h.account("ServiceOwner", "agent");
        String account = "123456789012", month = "2027-01", service = "aws :: Ordering";
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            graph(db, id(), id());
            db.connection.commit();
        }
        cmd(
                h,
                "admin",
                "mapping",
                "cloud_budget",
                "service",
                service,
                "owner_id",
                uid(h, "ServiceOwner"),
                "amount",
                "75",
                "currency",
                "USD",
                "account",
                account,
                "month",
                month,
                "revision",
                0);
        var charges =
                List.of(
                        map("line_id", "bill", "resource", "db", "cost", "80.1234"),
                        map("line_id", "credit", "resource", "db", "cost", "-7.00"),
                        map("line_id", "tax", "resource", "", "cost", "1"));
        for (int n : List.of(3, 0))
            assertEquals(
                    n,
                    cmd(
                                    h,
                                    "admin",
                                    "mapping",
                                    "cloud_import",
                                    "account",
                                    account,
                                    "month",
                                    month,
                                    "currency",
                                    "USD",
                                    "source",
                                    "aws",
                                    "entries",
                                    charges)
                            .get("accepted"));
        assertEquals(
                "73.1234",
                cmd(
                                h,
                                "admin",
                                "mapping",
                                "cloud_variance",
                                "account",
                                account,
                                "month",
                                month,
                                "previous_month",
                                "2026-12",
                                "currency",
                                "USD",
                                "service",
                                service)
                        .get("change"));
        var result =
                cmd(
                        h,
                        "ServiceOwner",
                        "mapping",
                        "cloud_cost",
                        "account",
                        account,
                        "month",
                        month,
                        "currency",
                        "USD",
                        "service",
                        service);
        assertEquals("73.1234", result.get("actual"));
        assertEquals(2, objects(result.get("lines")).size());
        denied(
                h,
                "ServiceOwner",
                "mapping",
                "cloud_cost",
                "account",
                account,
                "month",
                month,
                "currency",
                "USD",
                "service",
                "unattributed");
        assertEquals(
                "1",
                cmd(
                                h,
                                "admin",
                                "mapping",
                                "cloud_cost",
                                "account",
                                account,
                                "month",
                                month,
                                "currency",
                                "USD",
                                "service",
                                "unattributed")
                        .get("actual"));
    }

    static void search(WorkflowSupport h) throws Exception {
        h.account("Requester", "requester");
        String user = uid(h, "Requester"),
                group = h.add("sys_user_group").get("sys_id").toString(),
                suite = id();
        String[] titles = {"VPN access", "Parental leave", "Purchase order", "Salary adjustment"},
                questions =
                        {
                            "My network needs VPN",
                            "Where is parental paperwork?",
                            "Can procurement arrange a purchase?",
                            "Salary details please",
                            "I need help tomorrow",
                            "How would I arrange access for my VPN client?",
                            "What is our parental leave policy?",
                            "Purchase equipment for the new office",
                            "Does everyone see salary adjustment paperwork?",
                            "I am deciding between a purchase and leave",
                            "Can someone set up a tunnel into the company network?",
                            "Help me start a purchase order for displays",
                            "I need leave after my baby is born",
                            "VPN setup for my first business trip",
                            "Something went wrong yesterday"
                        };
        int[] expected = {0, 1, 2, -1, -1, 0, 1, 2, -1, -1, 0, 2, 1, 0, -1};
        var offers = new ArrayList<String>();
        var articles = new ArrayList<String>();
        var intents = new ArrayList<String>();
        var tests = new ArrayList<Map<String, Object>>();
        for (String title : titles) {
            String offering = ServiceDeliveryAcceptance.offering(h, title), article, intent = id();
            try (var db = h.store.open()) {
                String admin = PlatformFoundationTest.bind(db);
                article =
                        KnowledgeSearchAcceptance.article(
                                db, admin, title + " policy", "Team guidance for " + title);
                db.connection.commit();
            }
            cmd(
                    h,
                    "admin",
                    "assistant",
                    "intent",
                    "new_id",
                    intent,
                    "terms",
                    title,
                    "article_id",
                    article,
                    "offering_id",
                    offering,
                    "group_id",
                    group,
                    "revision",
                    0);
            offers.add(offering);
            articles.add(article);
            intents.add(intent);
        }
        try (var db = h.store.open()) {
            String admin = PlatformFoundationTest.bind(db);
            db.update(
                    "INSERT INTO os_record_access(record_id,visibility,owner_id) VALUES(?,?,?) ON"
                            + " CONFLICT(record_id) DO UPDATE SET visibility=excluded.visibility",
                    articles.get(3),
                    "private",
                    admin);
            db.connection.commit();
        }
        cmd(h, "admin", "assistant", "fallback", "group_id", group, "revision", 0);
        for (int i = 0; i < questions.length; i++)
            tests.add(
                    map(
                            "conversation",
                            id(),
                            "employee",
                            user,
                            "expected",
                            expected[i] >= 0 ? "submitted" : "handed_off",
                            "offering_id",
                            expected[i] >= 0 ? offers.get(expected[i]) : "",
                            "article_id",
                            expected[i] >= 0 ? articles.get(expected[i]) : "",
                            "group_id",
                            group,
                            "forbidden_articles",
                            List.of(articles.get(3))));
        cmd(h, "admin", "assistant", "holdout", "new_id", suite, "cases", tests);
        for (int i = 0; i < tests.size(); i++) {
            Object session = tests.get(i).get("conversation");
            var results =
                    cmd(
                            h,
                            "Requester",
                            "assistant",
                            "search_services",
                            "question",
                            questions[i],
                            "new_id",
                            session);
            int index = expected[i];
            if (index >= 0
                    && objects(results.get("suggestions")).stream()
                            .anyMatch(s -> s.get("intent_id").equals(intents.get(index)))) {
                assertEquals("clarification_required", results.get("state"));
                var choice =
                        cmd(
                                h,
                                "Requester",
                                "assistant",
                                "choose_service",
                                "new_id",
                                session,
                                "intent_id",
                                intents.get(index));
                assertEquals(articles.get(index), object(choice.get("citation")).get("article_id"));
                cmd(
                        h,
                        "Requester",
                        "assistant",
                        "confirm_order",
                        "new_id",
                        session,
                        "details",
                        "Employee reviewed policy",
                        "manifest",
                        map(),
                        "confirm",
                        true);
            } else {
                assertTrue(
                        objects(results.get("suggestions")).stream()
                                .noneMatch(s -> s.get("intent_id").equals(intents.get(3))));
                cmd(
                        h,
                        "Requester",
                        "assistant",
                        "handoff",
                        "new_id",
                        session,
                        "details",
                        "Please investigate");
            }
        }
        var score = cmd(h, "admin", "assistant", "score", "new_id", suite);
        assertEquals(14, score.get("verified_successes"));
        assertEquals(0, score.get("unauthorized_citations"));
        String extra = id();
        var matches =
                cmd(
                        h,
                        "Requester",
                        "assistant",
                        "search_services",
                        "question",
                        "Purchase or VPN access?",
                        "new_id",
                        extra);
        assertEquals(2, objects(matches.get("suggestions")).size());
        try (var db = h.store.open()) {
            String admin = PlatformFoundationTest.bind(db);
            db.update(
                    "INSERT INTO os_record_access(record_id,visibility,owner_id) VALUES(?,?,?) ON"
                            + " CONFLICT(record_id) DO UPDATE SET visibility=excluded.visibility",
                    articles.get(0),
                    "private",
                    admin);
            db.connection.commit();
        }
        denied(
                h,
                "Requester",
                "assistant",
                "choose_service",
                "new_id",
                extra,
                "intent_id",
                intents.get(0));
        try (var db = h.store.open()) {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of(
                            "target",
                            "enterprise-search-" + (db.postgres ? "postgres" : "sqlite") + ".json"),
                    JSON.writeValueAsString(score));
        }
    }

    static void agreements(WorkflowSupport h) throws Exception {
        var setup = EmployeeCasesAcceptance.setup(h);
        String hr = EmployeeCasesAcceptance.create(h, setup[0], "EmployeeOne"), team = setup[1];
        String day = LocalDate.now().plusDays(3).toString();
        cmd(
                h,
                "admin",
                "workplace",
                "agreement_template",
                "location",
                "East",
                "body",
                "{employee} moves to {location} on {effective}",
                "days",
                365,
                "revision",
                0);
        String transfer = EmployeeCasesAcceptance.create(h, setup[0], "EmployeeTwo");
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            var rec = EmployeeCases.record(db, transfer, false);
            EmploymentChanges.command(
                    db,
                    transfer,
                    map(
                            "operation",
                            "schedule_change",
                            "type",
                            "transfer",
                            "effective",
                            day,
                            "return_on",
                            "none",
                            "steps",
                            List.of(
                                    map(
                                            "phase",
                                            "effective",
                                            "summary",
                                            "Update location",
                                            "details",
                                            "Verify equipment",
                                            "group_id",
                                            team)),
                            "revision",
                            0,
                            "record_revision",
                            rec.get("revision")));
            db.connection.commit();
        }
        cmd(
                h,
                "admin",
                "workplace",
                "transfer_agreement",
                "case_id",
                transfer,
                "location",
                "East",
                "revision",
                0);
        var transferDoc = cmd(h, "EmployeeTwo", "workplace", "my_agreement", "case_id", transfer);
        assertEquals(
                "East",
                object(object(transferDoc.get("agreement")).get("definition")).get("location"));
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            EmploymentChanges.command(
                    db,
                    transfer,
                    map(
                            "operation",
                            "move_change",
                            "effective",
                            LocalDate.now().plusDays(6).toString(),
                            "return_on",
                            "none",
                            "revision",
                            1,
                            "reason",
                            "New transfer date"));
            db.connection.commit();
        }
        assertNotEquals(
                transferDoc.get("document_id"),
                cmd(h, "EmployeeTwo", "workplace", "my_agreement", "case_id", transfer)
                        .get("document_id"));
        cmd(
                h,
                "admin",
                "workplace",
                "schedule",
                "case_id",
                hr,
                "type",
                "move",
                "effective",
                day,
                "space",
                "East",
                "access_team",
                team,
                "equipment_team",
                team,
                "workplace_team",
                team,
                "revision",
                0);
        var view = cmd(h, "EmployeeOne", "workplace", "my_agreement", "case_id", hr);
        Object doc = view.get("document_id");
        var agreement = object(object(view.get("agreement")).get("definition"));
        assertTrue(agreement.get("body").toString().contains("EmployeeOne moves to East"));
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            var event = map("space", "East", "effective", day, "state", "scheduled");
            EmployeeAgreements.refresh(db, hr, event);
            assertEquals(doc, JourneyForms.definition(db, "agreement-head", hr).get("document"));
            EmployeeAgreements.expire(db, LocalDate.now());
            assertThrows(Failure.class, () -> EmployeeAgreements.refresh(db, id(), event));
            event.put("state", "cancelled");
            EmployeeAgreements.refresh(db, hr, event);
            db.viewerRole = "requester";
            db.viewerId = RecordAccess.owner(db, "EmployeeOne");
            assertEquals(
                    "none",
                    object(EmployeeAgreements.command(db, "my_agreement", map("case_id", hr)))
                            .get("state"));
            db.connection.rollback();
        }
        denied(
                h,
                "EmployeeOne",
                "workplace",
                "sign_agreement",
                "case_id",
                hr,
                "document_id",
                doc,
                "digest",
                "wrong",
                "revision",
                1);
        denied(
                h,
                "EmployeeTwo",
                "workplace",
                "sign_agreement",
                "case_id",
                hr,
                "document_id",
                doc,
                "digest",
                agreement.get("digest"),
                "revision",
                1);
        cmd(
                h,
                "EmployeeOne",
                "workplace",
                "sign_agreement",
                "case_id",
                hr,
                "document_id",
                doc,
                "digest",
                agreement.get("digest"),
                "revision",
                1);
        cmd(
                h,
                "admin",
                "workplace",
                "move",
                "case_id",
                hr,
                "effective",
                LocalDate.now().plusDays(8).toString(),
                "revision",
                1);
        assertNotEquals(
                doc,
                cmd(h, "EmployeeOne", "workplace", "my_agreement", "case_id", hr)
                        .get("document_id"));
        cmd(
                h,
                "admin",
                "workplace",
                "agreement_hold",
                "case_id",
                hr,
                "active",
                true,
                "note",
                "Retain during review",
                "revision",
                0);
        try (var db = h.store.open()) {
            EmployeeAgreements.expire(db, LocalDate.now().plusYears(2));
            db.connection.commit();
        }
        assertEquals(
                "signed",
                object(
                                cmd(
                                                h,
                                                "admin",
                                                "workplace",
                                                "review_agreement",
                                                "case_id",
                                                hr,
                                                "document_id",
                                                doc)
                                        .get("definition"))
                        .get("state"));
        cmd(
                h,
                "admin",
                "workplace",
                "agreement_hold",
                "case_id",
                hr,
                "active",
                false,
                "note",
                "Review completed",
                "revision",
                1);
        try (var db = h.store.open()) {
            EmployeeAgreements.expire(db, LocalDate.now().plusYears(2));
            db.connection.commit();
        }
        try (var db = h.store.open()) {
            EmployeeAgreements.expire(db, LocalDate.now().plusYears(3));
            db.connection.commit();
        }
        assertFalse(
                object(
                                cmd(
                                                h,
                                                "admin",
                                                "workplace",
                                                "review_agreement",
                                                "case_id",
                                                hr,
                                                "document_id",
                                                doc)
                                        .get("definition"))
                        .containsKey("body"));
    }

    static void funding(WorkflowSupport h) throws Exception {
        h.account("Approver", "admin");
        String project = h.add("project").get("sys_id").toString(),
                team = h.add("sys_user_group").get("sys_id").toString(),
                goal = id(),
                plan = id();
        cmd(
                h,
                "admin",
                "finance",
                "budget",
                "project_id",
                project,
                "amount",
                "100",
                "currency",
                "USD",
                "revision",
                0);
        cmd(
                h,
                "admin",
                "finance",
                "fund_goal",
                "goal_id",
                goal,
                "title",
                "Field availability",
                "quarter",
                "2027-Q1",
                "amount",
                "500",
                "currency",
                "USD");
        var lines =
                List.of(
                        map(
                                "project_id",
                                project,
                                "amount",
                                "250",
                                "labor_remaining",
                                "75",
                                "team_id",
                                team));
        cmd(
                h,
                "admin",
                "finance",
                "funding_option",
                "goal_id",
                goal,
                "plan_id",
                plan,
                "entries",
                lines,
                "title",
                "Staff project");
        denied(
                h,
                "admin",
                "finance",
                "approve_funding",
                "goal_id",
                goal,
                "plan_id",
                plan,
                "revision",
                1);
        cmd(
                h,
                "Approver",
                "finance",
                "approve_funding",
                "goal_id",
                goal,
                "plan_id",
                plan,
                "revision",
                1);
        h.account("TeamWorker", "agent");
        String worker = uid(h, "TeamWorker"), ticket = id();
        FulfilmentAcceptance.member(h, team, "TeamWorker", true);
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            PortfolioResources.command(
                    db,
                    project,
                    map(
                            "operation",
                            "budget",
                            "revision",
                            0,
                            "currency",
                            "USD",
                            "budget",
                            "350",
                            "remaining",
                            "0"));
            seed(db, "staff-calendar", worker, map("rate", "24.00", "currency", "USD"));
            db.connection.commit();
        }
        var posting =
                cmd(
                        h,
                        "admin",
                        "finance",
                        "book_labor",
                        "goal_id",
                        goal,
                        "project_id",
                        project,
                        "user_id",
                        worker,
                        "new_id",
                        ticket,
                        "day",
                        "2027-02-16",
                        "minutes",
                        90);
        assertEquals("36.00", posting.get("amount"));
        assertEquals(
                posting,
                cmd(
                        h,
                        "admin",
                        "finance",
                        "book_labor",
                        "goal_id",
                        goal,
                        "project_id",
                        project,
                        "user_id",
                        worker,
                        "new_id",
                        ticket,
                        "day",
                        "2027-02-16",
                        "minutes",
                        90));
        var old = cmd(h, "admin", "finance", "view_funding", "goal_id", goal, "plan_id", plan);
        String alternative = id();
        lines.getFirst().put("amount", "100");
        cmd(
                h,
                "admin",
                "finance",
                "funding_option",
                "goal_id",
                goal,
                "plan_id",
                alternative,
                "entries",
                lines,
                "title",
                "Defer to Q2");
        cmd(
                h,
                "Approver",
                "finance",
                "approve_funding",
                "goal_id",
                goal,
                "plan_id",
                alternative,
                "revision",
                1);
        var compared =
                cmd(
                        h,
                        "admin",
                        "finance",
                        "compare_funding",
                        "goal_id",
                        goal,
                        "plan_id",
                        plan,
                        "other_id",
                        alternative);
        assertEquals(
                "36.00",
                object(object(object(compared.get("second")).get("snapshots")).get(project))
                        .get("labor_actual"));
        assertEquals(
                old, cmd(h, "admin", "finance", "view_funding", "goal_id", goal, "plan_id", plan));
        assertEquals(
                "200.00",
                object(doc(h, "project-funding", project).get("definition")).get("amount"));
        String paused = id();
        lines.getFirst().put("amount", "0");
        cmd(
                h,
                "admin",
                "finance",
                "funding_option",
                "goal_id",
                goal,
                "plan_id",
                paused,
                "entries",
                lines,
                "title",
                "Pause funding");
        cmd(
                h,
                "Approver",
                "finance",
                "approve_funding",
                "goal_id",
                goal,
                "plan_id",
                paused,
                "revision",
                1);
        assertEquals(
                posting,
                cmd(
                        h,
                        "admin",
                        "finance",
                        "book_labor",
                        "goal_id",
                        goal,
                        "project_id",
                        project,
                        "user_id",
                        worker,
                        "new_id",
                        ticket,
                        "day",
                        "2027-02-16",
                        "minutes",
                        90));
        denied(
                h,
                "admin",
                "finance",
                "book_labor",
                "goal_id",
                goal,
                "project_id",
                project,
                "user_id",
                worker,
                "new_id",
                id(),
                "day",
                "2027-02-16",
                "minutes",
                90);
    }

    static void controls(WorkflowSupport h) throws Exception {
        h.account("Auditor", "admin");
        h.account("Owner", "agent");
        h.account("Signer", "agent");
        h.account("Recipient", "requester");
        String owner = uid(h, "Owner"),
                signer = uid(h, "Signer"),
                employee = uid(h, "Recipient"),
                run = id(),
                control = id(),
                group = h.add("sys_user_group").get("sys_id").toString(),
                template = id();
        FulfilmentAcceptance.member(h, group, "Signer", true);
        try (var db = h.store.open()) {
            String admin = PlatformFoundationTest.bind(db);
            seed(db, "workflow-identity", employee, map("manager", admin));
            seed(db, "access-run", run, map("privileged", template));
            seed(
                    db,
                    "workflow-run",
                    run,
                    map(
                            "template",
                            template,
                            "employee",
                            employee,
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
                                    map(
                                            "kind",
                                            "team_approval",
                                            "state",
                                            "done",
                                            "by",
                                            signer,
                                            "group",
                                            group,
                                            "signer_revision",
                                            AgentPlans.account(db, signer).get("auth_revision")))));
            db.connection.commit();
        }
        cmd(
                h,
                "admin",
                "audits",
                "monitor_policy",
                "control_id",
                control,
                "run_id",
                run,
                "owner_id",
                owner,
                "minutes",
                1);
        try (var db = h.store.open()) {
            GrantIndicators.tick(db, 1);
            db.connection.commit();
        }
        FulfilmentAcceptance.member(h, group, "Signer", false);
        try (var db = h.store.open()) {
            GrantIndicators.tick(db, 60001);
            GrantIndicators.tick(db, 120001);
            db.connection.commit();
        }
        var evidence = cmd(h, "Owner", "audits", "indicator_history", "control_id", control);
        assertEquals(3, objects(evidence.get("samples")).size());
        assertEquals(2, object(evidence.get("issue")).get("revision"));
        denied(h, "Auditor", "audits", "retest_indicator", "control_id", control, "revision", 2);
        FulfilmentAcceptance.member(h, group, "Signer", true);
        assertEquals(
                "closed",
                object(
                                cmd(
                                                h,
                                                "Auditor",
                                                "audits",
                                                "retest_indicator",
                                                "control_id",
                                                control,
                                                "revision",
                                                2)
                                        .get("definition"))
                        .get("state"));
    }
}
