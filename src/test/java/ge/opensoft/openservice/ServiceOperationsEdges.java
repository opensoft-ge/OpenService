package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseAcceptance.bad;
import static ge.opensoft.openservice.EnterpriseAcceptance.uid;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.ServiceDeliveryAcceptance.*;
import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

final class ServiceOperationsEdges {
    static void streams(WorkflowSupport h) throws Exception {
        try (var provider = new RecordQuestionsAcceptance.Provider(h.store, h.environment)) {
            String pkg = Values.id(), alias = provider.alias;
            var page =
                    new AtomicReference<Map<String, Object>>(
                            map(
                                    "items",
                                    List.of(map("enabled", true, "count", 2, "name", "Jira")),
                                    "next",
                                    ""));
            var generatedSize = new java.util.concurrent.atomic.AtomicInteger(-1);
            var pageNumber = new java.util.concurrent.atomic.AtomicInteger();
            var query = new AtomicReference<String>();
            provider.server.createContext(
                    "/inventory",
                    x -> {
                        query.set(x.getRequestURI().getRawQuery());
                        var response = page.get();
                        if (generatedSize.get() >= 0) {
                            var rows =
                                    Collections.nCopies(
                                            generatedSize.get(),
                                            map("name", "Page item", "enabled", true, "count", 1));
                            response =
                                    map(
                                            "items",
                                            rows,
                                            "next",
                                            "/inventory?cursor=" + pageNumber.incrementAndGet());
                        }
                        byte[] bytes = JSON.writeValueAsBytes(response);
                        x.sendResponseHeaders(200, bytes.length);
                        x.getResponseBody().write(bytes);
                        x.close();
                    });
            var input =
                    List.of(
                            map(
                                    "key",
                                    "filter",
                                    "type",
                                    "string",
                                    "choices",
                                    List.of(),
                                    "required",
                                    true,
                                    "label",
                                    "Filter"));
            var get =
                    map(
                            "auth",
                            "bearer",
                            "method",
                            "GET",
                            "path",
                            "/inventory?active=true",
                            "inputs",
                            input,
                            "outputs",
                            map("name", "string", "enabled", "boolean", "count", "number"),
                            "items",
                            "items",
                            "next",
                            "next");
            var install =
                    map(
                            "operation",
                            "rest_bind",
                            "package_id",
                            pkg,
                            "environment",
                            "test",
                            "alias",
                            alias,
                            "revision",
                            0);
            var define =
                    map(
                            "operation",
                            "rest_define",
                            "package_id",
                            pkg,
                            "title",
                            "Inventory",
                            "manifest",
                            get,
                            "revision",
                            0);
            var write = new LinkedHashMap<>(get);
            write.putAll(map("method", "POST", "outputs", map(), "items", "", "next", ""));
            define.put("manifest", write);
            post(h, "packages", pairs(define));
            var first = object(post(h, "packages", pairs(install)).get("definition"));
            Object action = first.get("action");
            install.put("revision", 1);
            assertEquals(
                    action,
                    object(post(h, "packages", pairs(install)).get("definition")).get("action"));
            define.putAll(map("revision", 1, "manifest", get));
            post(h, "packages", pairs(define));
            install.put("revision", 2);
            post(h, "packages", pairs(install));
            try (var db = h.store.open()) {
                PlatformFoundationTest.bind(db);
                assertEquals(0, ConnectorActions.find(db, action.toString()).get("active"));
            }
            var run =
                    map(
                            "operation",
                            "rest_read",
                            "package_id",
                            pkg,
                            "environment",
                            "test",
                            "manifest",
                            map("filter", "A B"));
            var ok = post(h, "packages", pairs(run));
            assertEquals(1, ok.get("pages"));
            assertEquals("active=true&filter=A+B", query.get());
            for (var invalid :
                    List.of(
                            map(
                                    "items",
                                    List.of(map("enabled", true, "count", "two", "name", "Jira")),
                                    "next",
                                    ""),
                            map(
                                    "items",
                                    List.of(
                                            map(
                                                    "name",
                                                    "x".repeat(2001),
                                                    "count",
                                                    2,
                                                    "enabled",
                                                    true)),
                                    "next",
                                    ""),
                            map(
                                    "items",
                                    List.of(map("name", false, "count", 2, "enabled", true)),
                                    "next",
                                    ""),
                            map("items", List.of(), "next", "/other"),
                            map("items", List.of(), "next", "/inventory?active=true&filter=A+B"))) {
                page.set(invalid);
                bad(h, "packages", "global", run, 400);
            }
            for (int size : List.of(0, 250)) {
                pageNumber.set(0);
                generatedSize.set(size);
                bad(h, "packages", "global", run, 400);
                assertEquals(size == 0 ? 20 : 5, pageNumber.get());
            }
            generatedSize.set(-1);
            try (var db = h.store.open()) {
                assertEquals(
                        1,
                        EnterpriseData.get(db, "rest-import", DeliveryWorkspace.key(pkg, "test"))
                                .get("revision"));
            }
            page.set(map("items", List.of(), "next", ""));
            get.put("path", "/inventory");
            define.putAll(map("revision", 2, "manifest", get));
            post(h, "packages", pairs(define));
            install.put("revision", 3);
            post(h, "packages", pairs(install));
            assertEquals(1, post(h, "packages", pairs(run)).get("pages"));
            assertEquals("filter=A+B", query.get());
            try (var other = new RecordQuestionsAcceptance.Provider(h.store, h.environment)) {
                try (var db = h.store.open()) {
                    String admin = PlatformFoundationTest.bind(db);
                    Connectors.save(
                            h.store,
                            db,
                            admin,
                            map("alias", alias, "origin", other.origin(), "token", "rotated"));
                    db.connection.commit();
                }
                bad(h, "packages", "global", run, 409);
            }
            install.put("revision", 1);
            bad(h, "packages", "global", install, 409);
        }
    }

    static Object[] pairs(Map<String, Object> body) {
        var result = new ArrayList<>();
        body.forEach(
                (k, v) -> {
                    result.add(k);
                    result.add(v);
                });
        return result.toArray();
    }

    static void corrections(WorkflowSupport h) throws Exception {
        String owner;
        h.account("Custodian", "agent");
        owner = uid(h, "Custodian");
        var resources =
                List.of(
                        map("id", "a", "title", "DB", "type", "Database", "depends_on", List.of()),
                        map("id", "b", "title", "DB", "type", "Database", "depends_on", List.of()),
                        map(
                                "id",
                                "c",
                                "title",
                                "Cluster",
                                "type",
                                "Workload",
                                "depends_on",
                                List.of()),
                        map(
                                "id",
                                "d",
                                "title",
                                "Spare",
                                "type",
                                "Database",
                                "depends_on",
                                List.of()));
        post(
                h,
                "topology",
                "operation",
                "inventory",
                "source",
                "s",
                "sequence",
                1,
                "resources",
                resources,
                "services",
                List.of(map("id", "public", "title", "Public", "roots", List.of("a", "b", "c"))));
        String a = ServiceTopology.identity("s", "a"),
                b = ServiceTopology.identity("s", "b"),
                c = ServiceTopology.identity("s", "c"),
                d = ServiceTopology.identity("s", "d");
        post(
                h,
                "mapping",
                "operation",
                "owner",
                "service",
                "s :: public",
                "owner_id",
                owner,
                "revision",
                0);
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            for (var edge : List.of(List.of(a, c), List.of(c, a), List.of(b, c))) {
                save(
                        db,
                        "component-link",
                        DeliveryWorkspace.key(edge),
                        0,
                        map(
                                "component",
                                edge.getFirst(),
                                "dependency",
                                edge.getLast(),
                                "operator",
                                db.viewerId));
            }
            db.connection.commit();
        }
        var preview =
                object(
                        call(
                                h,
                                "Custodian",
                                "mapping",
                                "operation",
                                "preview_merge",
                                "component",
                                a,
                                "target",
                                b));
        bad(
                h,
                "mapping",
                "global",
                map("operation", "merge", "component", a, "target", b, "fingerprint", "stale"),
                400);
        try (var db = h.store.open()) {
            assertTrue(FederatedMaps.graph(db).edges().containsKey(a));
        }
        var health = object(call(h, "Custodian", "mapping", "operation", "health", "days", 2));
        assertFalse(health.toString().contains(d));
        call(
                h,
                "Custodian",
                "mapping",
                "operation",
                "merge",
                "component",
                a,
                "target",
                b,
                "fingerprint",
                preview.get("fingerprint"));
        try (var db = h.store.open()) {
            var graph = FederatedMaps.graph(db);
            assertFalse(graph.edges().containsKey(a));
            assertTrue(graph.edges().get(c).contains(b));
        }
    }

    static void samples(WorkflowSupport h) throws Exception {
        var id = Values.id();
        String admin = uid(h, "admin");
        h.account("ProcessOwner", "agent");
        String owner = uid(h, "ProcessOwner");
        var criteria =
                map(
                        "table",
                        "incident",
                        "query",
                        "",
                        "before_start",
                        "2020-04-01",
                        "completed_state",
                        "6",
                        "days",
                        1,
                        "hours",
                        12);
        var fixture =
                map(
                        "owner",
                        owner,
                        "state",
                        "published",
                        "published_at",
                        "2020-05-01 00:00:00",
                        "criteria",
                        criteria,
                        "minimum_completed",
                        2,
                        "baseline",
                        map(
                                "cases",
                                List.of(),
                                "completed",
                                2,
                                "mean_seconds_to_first_completion",
                                3600,
                                "reopen_rate",
                                0.0));
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            for (int i = 0; i < 2; i++) {
                var rec =
                        Service.create(
                                db,
                                "incident",
                                map("short_description", "Reference case"),
                                "admin");
                Object record = rec.get("sys_id");
                db.update(
                        "UPDATE os_event SET created='2020-06-01 00:00:00' WHERE record_id=? AND"
                                + " action='created'",
                        record);
                db.update(
                        "INSERT INTO os_event(record_id,actor,action,created,detail)"
                                + " VALUES(?,'admin','updated','2020-06-01 00:30:00',"
                                + db.jsonParameter()
                                + ")",
                        record,
                        JSON.writeValueAsString(map("after", map("state", "6"))));
            }
            save(db, "improvement", id, 0, fixture);
            db.connection.commit();
        }
        var report =
                object(
                        object(
                                        call(
                                                h,
                                                "ProcessOwner",
                                                "mining",
                                                "operation",
                                                "measure",
                                                "new_id",
                                                id,
                                                "after_start",
                                                "2020-06-01",
                                                "revision",
                                                1))
                                .get("definition"));
        assertEquals("descriptive_comparison", report.get("sample_status"));
        assertEquals(-1800, report.get("mean_fulfillment_change_seconds"));
        assertEquals(0.0, report.get("reopen_rate_change"));
        var ownerBaseline = new LinkedHashMap<>(criteria);
        ownerBaseline.putAll(
                map(
                        "operation",
                        "baseline",
                        "new_id",
                        Values.id(),
                        "title",
                        "Self-owned",
                        "limit",
                        5,
                        "owner_id",
                        owner));
        call(h, "ProcessOwner", "mining", pairs(ownerBaseline));
        ownerBaseline.put("owner_id", admin);
        assertEquals(
                400,
                h.json(
                                "POST",
                                EnterpriseAcceptance.api("mining", "global"),
                                ownerBaseline,
                                WorkflowSupport.auth("ProcessOwner"))
                        .statusCode());
    }

    static void desks(WorkflowSupport h) throws Exception {
        var customers = CustomerCasesAcceptance.setup(h);
        String agent = uid(h, "CustomerAgent"), outsider = uid(h, "CustomerOutsider");
        for (String table : List.of("sc_request", "customer_case")) {
            post(
                    h,
                    "assignment",
                    "operation",
                    "auto_route",
                    "table",
                    table,
                    "active",
                    true,
                    "skill",
                    "service",
                    "minutes",
                    30,
                    "revision",
                    0);
        }
        for (String person : List.of("CustomerAgent", "CustomerOutsider")) {
            post(
                    h,
                    "assignment",
                    "operation",
                    "capacity",
                    "agent_id",
                    uid(h, person),
                    "skills",
                    List.of("service"),
                    "capacity",
                    2,
                    "revision",
                    0);
            call(h, person, "assignment", "operation", "presence", "active", true);
        }
        String contract = CustomerCasesAcceptance.entitlement(h, customers[0], 5);
        var ticket =
                CustomerCasesAcceptance.create(
                        h,
                        contract,
                        CustomerCasesAcceptance.body(h, contract, "CustomerOne"),
                        "CustomerOne");
        String customer = ticket.get("sys_id").toString(), internal;
        try (var db = h.store.open()) {
            String admin = PlatformFoundationTest.bind(db);
            internal =
                    Requests.create(
                                    db,
                                    "admin",
                                    map("short_description", "Access", "description", "Enable VPN"))
                            .get("sys_id")
                            .toString();
            ServiceClocks.calendar(
                    db,
                    admin,
                    map(
                            "name",
                            "All days",
                            "calendar",
                            map(
                                    "zone",
                                    "UTC",
                                    "days",
                                    List.of(1, 2, 3, 4, 5, 6, 7),
                                    "start",
                                    "00:00",
                                    "end",
                                    "23:59",
                                    "holidays",
                                    List.of())));
            long now = System.currentTimeMillis();
            for (String record : List.of(customer, internal)) {
                boolean urgent = record.equals(customer);
                var clock =
                        ServiceClocks.start(
                                db,
                                admin,
                                map(
                                        "record_id",
                                        record,
                                        "table",
                                        urgent ? "customer_case" : "sc_request",
                                        "calendar",
                                        "All days",
                                        "minutes",
                                        1,
                                        "purpose",
                                        "resolution"),
                                urgent ? now - 86400000 : now);
                if (urgent) ServiceClocks.advance(db, clock, now);
            }
            db.connection.commit();
        }
        var waiting = objects(call(h, "admin", "assignment", "operation", "waiting", "limit", 20));
        assertEquals(2, waiting.size());
        var first = object(waiting.getFirst().get("work"));
        assertEquals(customer, first.get("record_id"));
        assertEquals("breached", object(first.get("clock")).get("state"));
        assertEquals(
                "running", object(object(waiting.getLast().get("work")).get("clock")).get("state"));
        assertTrue(((List<?>) waiting.getFirst().get("eligible")).contains(agent));
        assertFalse(((List<?>) waiting.getFirst().get("eligible")).contains(outsider));
        assertEquals(1, object(waiting.getFirst().get("unavailable")).get("outside_record_scope"));
        assertTrue(
                objects(
                                call(
                                        h,
                                        "CustomerOutsider",
                                        "assignment",
                                        "operation",
                                        "offers",
                                        "agent_id",
                                        outsider))
                        .stream()
                        .noneMatch(r -> customer.equals(object(r.get("work")).get("record_id"))));
        bad(
                h,
                "assignment",
                "global",
                map(
                        "operation",
                        "override",
                        "agent_id",
                        outsider,
                        "table",
                        "customer_case",
                        "record_id",
                        customer,
                        "note",
                        "Out of team",
                        "revision",
                        1),
                400);
        call(
                h,
                "CustomerAgent",
                "assignment",
                "operation",
                "accept",
                "table",
                "customer_case",
                "record_id",
                customer,
                "revision",
                1);
        assertEquals(
                agent,
                object(EnterpriseAcceptance.doc(h, "desk-offer", customer).get("definition"))
                        .get("owner"));
    }

    static void definitions(WorkflowSupport h) throws Exception {
        var read =
                map(
                        "method",
                        "GET",
                        "auth",
                        "bearer",
                        "path",
                        "/inventory",
                        "inputs",
                        List.of(),
                        "outputs",
                        map("name", "string"),
                        "items",
                        "rows",
                        "next",
                        "next");
        var write = new LinkedHashMap<>(read);
        write.putAll(map("method", "POST", "outputs", map(), "items", "", "next", ""));
        var tooMany = new LinkedHashMap<String, Object>();
        for (int i = 0; i < 33; i++) tooMany.put("field" + i, "string");
        for (var base : List.of(read, write)) {
            var invalid =
                    base == read
                            ? List.of(
                                    map("auth", "anonymous"),
                                    map("outputs", map("value", "object")),
                                    map("outputs", tooMany),
                                    map("outputs", map()),
                                    map("outputs", map("bad field", "string")),
                                    map("method", "TRACE"),
                                    map("items", "bad field"))
                            : List.of(
                                    map("items", "rows"),
                                    map("next", "next"),
                                    map("outputs", map("echo", "string")));
            for (var change : invalid) {
                var spec = new LinkedHashMap<>(base);
                spec.putAll(change);
                bad(
                        h,
                        "packages",
                        "global",
                        map(
                                "operation",
                                "rest_define",
                                "package_id",
                                Values.id(),
                                "title",
                                "Broken contract",
                                "revision",
                                0,
                                "manifest",
                                spec),
                        400);
            }
        }
        String key = Values.id(), group = h.add("sys_user_group").get("sys_id").toString();
        h.account("Worker", "requester");
        var test =
                map(
                        "conversation",
                        Values.id(),
                        "employee",
                        uid(h, "Worker"),
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
        var wrong = new LinkedHashMap<>(test);
        wrong.put("employee", uid(h, "admin"));
        for (var tests : List.of(List.of(), List.of(test, test), List.of(wrong))) {
            bad(
                    h,
                    "assistant",
                    "global",
                    map("operation", "holdout", "new_id", key, "cases", tests),
                    400);
        }
        bad(
                h,
                "mapping",
                "global",
                map("operation", "preview_merge", "component", "a", "target", "a"),
                400);
        bad(
                h,
                "mapping",
                "global",
                map("operation", "preview_merge", "component", "a", "target", "b"),
                400);
        bad(
                h,
                "mapping",
                "global",
                map(
                        "operation",
                        "owner",
                        "service",
                        "missing",
                        "owner_id",
                        uid(h, "admin"),
                        "revision",
                        0),
                400);
    }

    static void cancelledWork(WorkflowSupport h) throws Exception {
        String completed = offering(h, "Completed setup"),
                queued = offering(h, "Queue setup"),
                retired = offering(h, "Retired setup");
        String guide = Values.id(),
                order = Values.id(),
                group = h.add("sys_user_group").get("sys_id").toString();
        post(
                h,
                "guides",
                "operation",
                "define",
                "title",
                "Department setup",
                "guide_id",
                guide,
                "revision",
                0,
                "entries",
                List.of(completed, queued, retired).stream()
                        .map(k -> map("offering_id", k, "role", "*", "location", "*"))
                        .toList());
        try (var db = h.store.open()) {
            String admin = PlatformFoundationTest.bind(db);
            Catalogue.save(
                    db, admin, retired, CatalogueAcceptance.offering("Retired setup", 1, false));
            CatalogueWorkflows.save(
                    db,
                    queued,
                    map(
                            "operation",
                            "add",
                            "revision",
                            1,
                            "summary",
                            "Set up account",
                            "details",
                            "Required for employee",
                            "group_id",
                            group));
            CatalogueWorkflows.save(db, queued, map("operation", "enable", "revision", 2));
            db.connection.commit();
        }
        var ordered =
                post(
                        h,
                        "guides",
                        "operation",
                        "order",
                        "guide_id",
                        guide,
                        "new_id",
                        order,
                        "job_role",
                        "staff",
                        "location",
                        "HQ",
                        "details",
                        "Intake",
                        "revision",
                        1,
                        "manifest",
                        map(
                                completed,
                                map("revision", 1, "answers", map()),
                                queued,
                                map("revision", 3, "answers", map())));
        var items = object(object(ordered.get("definition")).get("items"));
        assertFalse(items.containsKey(retired));
        String done = object(items.get(completed)).get("request_id").toString(),
                pending = object(items.get(queued)).get("request_id").toString();
        for (String state : List.of("fulfilling", "complete"))
            assertEquals(
                    200,
                    h.json(
                                    "PATCH",
                                    "/api/now/table/sc_request/" + done,
                                    map("state", state),
                                    WorkflowSupport.auth("admin"))
                            .statusCode());
        var cancelled = post(h, "guides", "operation", "cancel", "order_id", order, "revision", 1);
        var lines = object(object(cancelled.get("definition")).get("items"));
        assertEquals("reversal_review", object(lines.get(completed)).get("reservation"));
        assertEquals(
                409,
                h.json(
                                "PATCH",
                                "/api/now/table/sc_request/" + pending,
                                map("state", "fulfilling"),
                                WorkflowSupport.auth("admin"))
                        .statusCode());
        try (var db = h.store.open()) {
            assertEquals(
                    1,
                    db.count(
                            "SELECT COUNT(*) FROM os_case_task WHERE parent_id=? AND"
                                    + " state='cancelled'",
                            pending));
        }
    }
}
