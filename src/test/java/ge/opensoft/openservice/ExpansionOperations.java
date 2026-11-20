package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseAcceptance.*;
import static ge.opensoft.openservice.TenJourneysAcceptance.post;
import static ge.opensoft.openservice.TenPolicyAcceptance.as;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;

final class ExpansionOperations {
    private ExpansionOperations() {}

    static void resilience(WorkflowSupport h) throws Exception {
        h.account("Primary", "agent");
        String responder = uid(h, "Primary"), admin = uid(h, "admin"), plan = id(), roster = id();
        var dbci = map("id", "db", "type", "Database", "title", "Orders", "depends_on", List.of());
        var service = map("id", "api", "title", "Ordering", "roots", List.of("db"));
        var snapshot =
                map(
                        "operation",
                        "inventory",
                        "source",
                        "cloud",
                        "sequence",
                        1,
                        "resources",
                        List.of(dbci),
                        "services",
                        List.of(service));
        post(h, "topology", snapshot);
        snapshot.put("source", "inventory");
        post(h, "topology", snapshot);
        for (String source : List.of("cloud", "inventory"))
            post(
                    h,
                    "mapping",
                    map(
                            "operation",
                            "bind",
                            "source",
                            source,
                            "resource_id",
                            "db",
                            "component",
                            "orders-db",
                            "revision",
                            0));
        post(
                h,
                "mapping",
                map(
                        "operation",
                        "prefer",
                        "component",
                        "orders-db",
                        "field",
                        "title",
                        "source",
                        "inventory",
                        "resource_id",
                        "db",
                        "revision",
                        0));
        bad(h, "mapping", "global", map("operation", "facts", "component", "unobserved"), 400);
        bad(
                h,
                "mapping",
                "global",
                map(
                        "operation",
                        "prefer",
                        "component",
                        "orders-db",
                        "field",
                        "title",
                        "source",
                        "cloud",
                        "resource_id",
                        "unobserved",
                        "revision",
                        1),
                400);
        var facts = map("operation", "facts", "component", "orders-db");
        assertEquals(
                "inventory",
                object(object(post(h, "mapping", facts).get("fields")).get("title")).get("source"));
        post(
                h,
                "mapping",
                map(
                        "operation",
                        "failed_collect",
                        "source",
                        "inventory",
                        "sequence",
                        2,
                        "note",
                        "Collector timed out"));
        assertEquals(
                1,
                post(h, "mapping", map("operation", "collection", "source", "inventory"))
                        .get("last_good_sequence"));
        var oldFailure =
                map(
                        "operation",
                        "failed_collect",
                        "source",
                        "inventory",
                        "sequence",
                        1,
                        "note",
                        "An older report");
        bad(h, "mapping", "global", oldFailure, 400);
        post(h, "mapping", ExpansionBoundaryAcceptance.with(oldFailure, "sequence", 4));
        bad(
                h,
                "mapping",
                "global",
                ExpansionBoundaryAcceptance.with(oldFailure, "sequence", 3),
                400);
        var repeated = ExpansionBoundaryAcceptance.with(snapshot, "source", "cloud", "sequence", 2);
        post(h, "topology", repeated);
        snapshot.putAll(map("sequence", 2, "resources", List.of(), "services", List.of()));
        post(h, "topology", snapshot);
        assertTrue(
                object(
                                post(
                                                h,
                                                "mapping",
                                                map(
                                                        "operation",
                                                        "collection",
                                                        "source",
                                                        "inventory"))
                                        .get("retired"))
                        .containsKey("db"));
        assertEquals(
                "source_unavailable",
                object(object(post(h, "mapping", facts).get("fields")).get("title")).get("status"));
        post(
                h,
                "duty",
                map(
                        "operation",
                        "roster",
                        "roster_id",
                        roster,
                        "title",
                        "Recovery duty",
                        "anchor",
                        Instant.ofEpochSecond(Instant.now().getEpochSecond() - 30).toString(),
                        "zone",
                        "Etc/UTC",
                        "hours",
                        12,
                        "minutes",
                        10,
                        "people",
                        List.of(responder, admin),
                        "revision",
                        0));
        bad(
                h,
                "continuity",
                "global",
                map(
                        "operation",
                        "plan",
                        "plan_id",
                        id(),
                        "title",
                        "Missing responders",
                        "rto_minutes",
                        10,
                        "rpo_minutes",
                        1,
                        "revision",
                        0,
                        "component",
                        "orders-db",
                        "service_id",
                        "cloud :: api",
                        "roster_id",
                        roster,
                        "steps",
                        List.of()),
                400);
        post(
                h,
                "continuity",
                map(
                        "operation",
                        "plan",
                        "plan_id",
                        plan,
                        "title",
                        "Ordering recovery",
                        "component",
                        "orders-db",
                        "service_id",
                        "cloud :: api",
                        "roster_id",
                        roster,
                        "rto_minutes",
                        10,
                        "rpo_minutes",
                        1,
                        "steps",
                        List.of(map("owner_id", responder, "title", "Restore database")),
                        "revision",
                        0));
        String failed = id(), later = id();
        for (String id : List.of(failed, later)) {
            var exercise =
                    object(
                            post(
                                            h,
                                            "continuity",
                                            map(
                                                    "operation",
                                                    "exercise",
                                                    "plan_id",
                                                    plan,
                                                    "new_id",
                                                    id,
                                                    "revision",
                                                    1))
                                    .get("definition"));
            long start = ((Number) exercise.get("start")).longValue();
            var task =
                    map(
                            "operation",
                            "task",
                            "exercise_id",
                            id,
                            "step",
                            1,
                            "note",
                            "Restored backup",
                            "revision",
                            1);
            bad(h, "continuity", "global", task, 400);
            as(h, "Primary", "continuity", task, 200);
            var result =
                    post(
                            h,
                            "continuity",
                            map(
                                    "operation",
                                    "measure",
                                    "exercise_id",
                                    id,
                                    "restored_at",
                                    Instant.ofEpochMilli(start).toString(),
                                    "restore_point",
                                    Instant.ofEpochMilli(
                                                    start - (id.equals(failed) ? 120000 : 1000))
                                            .toString(),
                                    "evidence",
                                    "Recovery probe",
                                    "revision",
                                    2));
            if (id.equals(failed)) {
                assertEquals("needs_correction", object(result.get("definition")).get("state"));
                as(
                        h,
                        "Primary",
                        "continuity",
                        map(
                                "operation",
                                "correct",
                                "exercise_id",
                                failed,
                                "note",
                                "Replicate logs more frequently",
                                "revision",
                                3),
                        200);
            }
        }
        assertEquals(
                "verified",
                object(
                                post(
                                                h,
                                                "continuity",
                                                map(
                                                        "operation",
                                                        "retest",
                                                        "exercise_id",
                                                        failed,
                                                        "later_id",
                                                        later,
                                                        "revision",
                                                        4))
                                        .get("definition"))
                        .get("state"));
    }

    static void inventorySchedule(WorkflowSupport h) throws Exception {
        try (var fixture = new RecordQuestionsAcceptance.Provider(h.store, h.environment)) {
            String source = "assets/fleet";
            var resource =
                    map("id", "srv", "title", "Server", "type", "Asset", "depends_on", List.of());
            var packet =
                    map(
                            "operation",
                            "inventory",
                            "source",
                            source,
                            "sequence",
                            1,
                            "resources",
                            List.of(resource),
                            "services",
                            List.of());
            var response =
                    new java.util.concurrent.atomic.AtomicReference<>(
                            JSON.writeValueAsBytes(packet));
            var advance = new java.util.concurrent.atomic.AtomicReference<>("");
            fixture.server.createContext(
                    "/inventory",
                    e -> {
                        String area = advance.getAndSet("");
                        if (!area.isEmpty()) {
                            try (var db = h.store.open()) {
                                db.update(
                                        "UPDATE os_enterprise_document SET revision=revision+1"
                                                + " WHERE area=? AND subject=?",
                                        area,
                                        source);
                                db.connection.commit();
                            } catch (java.sql.SQLException error) {
                                throw new java.io.IOException(error);
                            }
                        }
                        byte[] bytes = response.get();
                        e.sendResponseHeaders(200, bytes.length);
                        e.getResponseBody().write(bytes);
                        e.close();
                    });
            var schedule =
                    map(
                            "operation",
                            "schedule_collect",
                            "source",
                            source,
                            "alias",
                            fixture.alias,
                            "path",
                            "/inventory",
                            "minutes",
                            5,
                            "active",
                            true,
                            "revision",
                            0);
            var inspect = map("operation", "collection", "source", source);
            post(h, "mapping", schedule);
            long start = System.currentTimeMillis() + 1000;
            assertTrue(InventorySchedules.refresh(h.store, start));
            assertFalse(InventorySchedules.refresh(h.store, start));
            assertEquals(1, post(h, "mapping", inspect).get("last_good_sequence"));
            packet.putAll(map("sequence", 2, "source", "wrong"));
            response.set(JSON.writeValueAsBytes(packet));
            assertTrue(InventorySchedules.refresh(h.store, start + 300000));
            var status = post(h, "mapping", inspect);
            assertEquals(
                    "failed", object(object(status.get("refresh")).get("definition")).get("state"));
            assertTrue(object(status.get("retired")).isEmpty());
            packet.putAll(map("source", source, "resources", List.of()));
            response.set(JSON.writeValueAsBytes(packet));
            assertTrue(InventorySchedules.refresh(h.store, start + 600000));
            assertTrue(object(post(h, "mapping", inspect).get("retired")).containsKey("srv"));
            packet.putAll(map("sequence", 3, "resources", List.of(resource)));
            response.set(JSON.writeValueAsBytes(packet));
            assertTrue(InventorySchedules.refresh(h.store, start + 900000));
            assertTrue(object(post(h, "mapping", inspect).get("retired")).isEmpty());
            packet.put("resources", List.of(resource, resource));
            response.set(JSON.writeValueAsBytes(packet));
            assertTrue(InventorySchedules.refresh(h.store, start + 1200000));
            assertEquals(3, post(h, "mapping", inspect).get("last_good_sequence"));
            packet.put("resources", List.of(resource));
            packet.put("sequence", 4);
            for (var invalid : List.of(map("operation", "report"), map("source", "different"))) {
                var wrong = new LinkedHashMap<>(packet);
                wrong.putAll(invalid);
                response.set(JSON.writeValueAsBytes(wrong));
                assertTrue(InventorySchedules.refresh(h.store, start += 1800000));
                assertEquals(3, post(h, "mapping", inspect).get("last_good_sequence"));
            }
            response.set(JSON.writeValueAsBytes(packet));
            for (String area : List.of("inventory-progress", "inventory-schedule")) {
                advance.set(area);
                assertFalse(InventorySchedules.refresh(h.store, start + 1800000));
                assertEquals(3, post(h, "mapping", inspect).get("last_good_sequence"));
            }
            long due = start + 1800000;
            for (int failAt : List.of(1, 2)) {
                Store failing = org.mockito.Mockito.spy(h.store);
                var openings = new java.util.concurrent.atomic.AtomicInteger();
                org.mockito.Mockito.doAnswer(
                                invocation -> {
                                    var real = h.store.open();
                                    if (openings.incrementAndGet() != failAt) return real;
                                    real.connection.close();
                                    var broken = org.mockito.Mockito.spy(real);
                                    org.mockito.Mockito.doThrow(
                                                    new java.sql.SQLException(
                                                            "Secondary close failure"))
                                            .when(broken)
                                            .close();
                                    return broken;
                                })
                        .when(failing)
                        .open();
                var error =
                        assertThrows(
                                java.sql.SQLException.class,
                                () -> InventorySchedules.refresh(failing, due));
                assertEquals(1, error.getSuppressed().length);
            }
            schedule.putAll(map("revision", 2, "active", false));
            post(h, "mapping", schedule);
            assertFalse(InventorySchedules.refresh(h.store, start + 1500000));
        }
    }

    static void packages(WorkflowSupport h) throws Exception {
        String id = id();
        post(
                h,
                "packages",
                map(
                        "operation",
                        "pack",
                        "package_id",
                        id,
                        "title",
                        "Read GitHub issue",
                        "kind",
                        "github_issue",
                        "revision",
                        0));
        try (var fixture = new RecordQuestionsAcceptance.Provider(h.store, h.environment)) {
            var exported = post(h, "packages", map("package_id", id, "operation", "export"));
            var imported = map("operation", "import", "package_id", id, "revision", 1);
            for (var invalid :
                    List.of(
                            ExpansionBoundaryAcceptance.with(exported, "format", "unknown"),
                            ExpansionBoundaryAcceptance.with(exported, "package_id", id()))) {
                bad(
                        h,
                        "packages",
                        "global",
                        ExpansionBoundaryAcceptance.with(imported, "manifest", invalid),
                        400);
            }
            h.account("OtherPackageAuthor", "agent");
            as(
                    h,
                    "OtherPackageAuthor",
                    "packages",
                    map(
                            "operation",
                            "pack",
                            "package_id",
                            id,
                            "title",
                            "Take ownership",
                            "kind",
                            "github_issue",
                            "revision",
                            1),
                    400);

            post(
                    h,
                    "packages",
                    map(
                            "operation",
                            "import",
                            "package_id",
                            id,
                            "manifest",
                            exported,
                            "revision",
                            1));
            object(exported.get("action")).put("permission", "Unrestricted");
            bad(
                    h,
                    "packages",
                    "global",
                    map(
                            "operation",
                            "import",
                            "package_id",
                            id,
                            "manifest",
                            exported,
                            "revision",
                            2),
                    400);
            for (String kind : List.of("jira_transition", "entra_add", "entra_remove")) {
                String key = id();
                h.account("PackageAuthor" + kind.replace("_", ""), "agent");
                as(
                        h,
                        "PackageAuthor" + kind.replace("_", ""),
                        "packages",
                        map(
                                "operation",
                                "pack",
                                "package_id",
                                key,
                                "title",
                                kind,
                                "kind",
                                kind,
                                "revision",
                                0),
                        200);
                boolean jira = kind.startsWith("jira");
                var install =
                        map(
                                "operation",
                                "install",
                                "package_id",
                                key,
                                "environment",
                                "test",
                                "alias",
                                fixture.alias,
                                "oauth",
                                "alias",
                                "target",
                                jira ? "TEST-1" : UUID.randomUUID().toString(),
                                "argument",
                                jira ? "21" : UUID.randomUUID().toString(),
                                "expected",
                                jira ? "31" : "membership",
                                "expires",
                                "2099-01-01T00:00:00Z",
                                "revision",
                                0,
                                "package_revision",
                                1);
                post(h, "packages", install);
                assertEquals(
                        kind,
                        object(
                                        object(
                                                        object(
                                                                        post(
                                                                                        h,
                                                                                        "packages",
                                                                                        map(
                                                                                                "operation",
                                                                                                "inspect",
                                                                                                "package_id",
                                                                                                key,
                                                                                                "environment",
                                                                                                "test"))
                                                                                .get(
                                                                                        "installation"))
                                                                .get("definition"))
                                                .get("manifest"))
                                .get("kind"));
            }
            String path = "/repos/testing/fixture/issues/3";
            var malformed = new java.util.concurrent.atomic.AtomicBoolean(false);
            fixture.server.createContext(
                    path,
                    e -> {
                        byte[] bytes =
                                JSON.writeValueAsBytes(
                                        map(
                                                "number",
                                                3,
                                                "id",
                                                42,
                                                "title",
                                                "Verify a package",
                                                "updated_at",
                                                "2026-11-01T09:00:00Z"));
                        if (malformed.get())
                            bytes = "{".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        e.sendResponseHeaders(200, bytes.length);
                        e.getResponseBody().write(bytes);
                        e.close();
                    });
            var install =
                    map(
                            "operation",
                            "install",
                            "package_id",
                            id,
                            "environment",
                            "test",
                            "alias",
                            fixture.alias,
                            "oauth",
                            "alias",
                            "target",
                            "issue",
                            "argument",
                            "read",
                            "expected",
                            "issue",
                            "expires",
                            "2099-01-01T00:00:00Z",
                            "revision",
                            0,
                            "package_revision",
                            2);
            bad(
                    h,
                    "packages",
                    "global",
                    ExpansionBoundaryAcceptance.with(install, "expires", "2020-01-01T00:00:00Z"),
                    400);
            try (var db = h.store.open()) {
                PlatformFoundationTest.bind(db);
                db.update(
                        "DELETE FROM os_enterprise_document WHERE area='deployment-origin' AND"
                                + " subject=?",
                        DeliveryWorkspace.key(fixture.alias));
                ActionPackages.command(h.store, db, "install", install);
                String key = DeliveryWorkspace.key(id, "test");
                var binding = EnterpriseData.get(db, "package-install", key);
                var config = object(binding.get("definition"));
                config.put("expires", 0);
                EnterpriseData.save(db, "package-install", key, 1, config);
                assertThrows(
                        Failure.class,
                        () ->
                                ActionPackages.command(
                                        h.store,
                                        db,
                                        "read",
                                        map(
                                                "package_id",
                                                id,
                                                "environment",
                                                "test",
                                                "revision",
                                                2,
                                                "path",
                                                path)));
            }
            post(h, "packages", install);
            var read =
                    map(
                            "operation",
                            "read",
                            "package_id",
                            id,
                            "environment",
                            "test",
                            "path",
                            path,
                            "revision",
                            1);
            malformed.set(true);
            bad(h, "packages", "global", read, 502);
            malformed.set(false);
            assertEquals("Verify a package", post(h, "packages", read).get("title"));
            install.put("environment", "production");
            bad(h, "packages", "global", install, 400);
            try (var db = h.store.open()) {
                String actor = PlatformFoundationTest.bind(db);
                Connectors.save(
                        h.store,
                        db,
                        actor,
                        map(
                                "alias",
                                fixture.alias,
                                "origin",
                                fixture.origin(),
                                "token",
                                "replacement-test-token"));
                db.connection.commit();
            }
            bad(h, "packages", "global", read, 409);
            install.putAll(map("environment", "test", "revision", 1));
            post(h, "packages", install);
            read.put("revision", 2);
            assertEquals("42", post(h, "packages", read).get("issue_id"));
            try (var db = h.store.open()) {
                PlatformFoundationTest.bind(db);
                var key = DeliveryWorkspace.key(id, "test");
                EnterpriseData.save(
                        db,
                        "package-read-window",
                        key,
                        2,
                        map("minute", System.currentTimeMillis() / 60000, "count", 30));
                db.connection.commit();
            }
            bad(h, "packages", "global", read, 429);
        }
    }

    static void handoff(WorkflowSupport h) throws Exception {
        h.account("DelegatedWorker", "agent");
        h.account("SeparateAdmin", "admin");
        EnterpriseAgentFaults.user(h, "DelegatedWorker", "api_only", 1);
        String delegate = uid(h, "DelegatedWorker"),
                incident = h.add("incident").get("sys_id").toString(),
                original = id(),
                replacement = id();
        var grant =
                map(
                        "operation",
                        "grant",
                        "delegate_id",
                        delegate,
                        "revision",
                        0,
                        "active",
                        true,
                        "targets",
                        List.of(
                                map(
                                        "table",
                                        "incident",
                                        "record_id",
                                        incident,
                                        "fields",
                                        List.of("short_description"))));
        post(
                h,
                "desk",
                map(
                        "operation",
                        "reviewer",
                        "reviewer_id",
                        uid(h, "SeparateAdmin"),
                        "revision",
                        0));
        send(h, "agents", "global", grant);
        send(
                h,
                "agents",
                "global",
                map(
                        "operation",
                        "review",
                        "key",
                        original,
                        "delegate_id",
                        delegate,
                        "grant_revision",
                        1,
                        "steps",
                        List.of(
                                map(
                                        "table",
                                        "incident",
                                        "record_id",
                                        incident,
                                        "patch",
                                        map("short_description", "Reauthorized service action")))));
        try (var db = h.store.open()) {
            var owner = PlatformFoundationTest.bind(db);
            var reviewer = RecordAccess.owner(db, "SeparateAdmin");
            EnterpriseData.save(
                    db,
                    "agent-investigation",
                    id(),
                    0,
                    map("subject", original, "state", "resumed", "owner", reviewer));
            EnterpriseData.save(
                    db,
                    "agent-investigation",
                    id(),
                    0,
                    map("subject", id(), "state", "open", "owner", reviewer));
            var halted = new DeskTrials.Trial("triage", "Stop", "stop", true, true);
            assertEquals(
                    "stop",
                    DeskTrials.exercise(h.store, db, original, halted, "execute", reviewer)
                            .get("effective_decision"));
            var revoked =
                    new DeskTrials.Trial("triage", "Assign incident", "handoff", false, false);
            assertEquals(
                    true,
                    DeskTrials.exercise(h.store, db, original, revoked, "execute", reviewer)
                            .get("passed"));
            assertEquals(
                    false,
                    DeskTrials.exercise(h.store, db, original, revoked, "execute", owner)
                            .get("passed"));
        }
        send(
                h,
                "agents",
                "global",
                map("operation", "revoke", "delegate_id", delegate, "revision", 1));
        try (var db = h.store.open()) {
            String admin = PlatformFoundationTest.bind(db);
            var originalPlan = JourneyForms.definition(db, "agent-plan", original);
            String proposal = id();
            var decision = map("plan", original, "owner", admin, "decision", "execute");
            var body =
                    map(
                            "plan_id",
                            original,
                            "revision",
                            1,
                            "job",
                            "triage",
                            "goal",
                            "Assign incident",
                            "confirm",
                            true);
            assertThrows(
                    Failure.class,
                    () ->
                            DeskTrials.assist(
                                    h.store,
                                    db,
                                    "approve_job",
                                    proposal,
                                    ExpansionBoundaryAcceptance.with(body, "confirm", false)));
            int revision = 0;
            for (var invalid : List.of(map("owner", id()), map("plan", id()))) {
                var badDecision = new LinkedHashMap<>(decision);
                badDecision.putAll(invalid);
                EnterpriseData.save(db, "desk-proposal", proposal, revision++, badDecision);
                assertThrows(
                        Failure.class,
                        () -> DeskTrials.assist(h.store, db, "approve_job", proposal, body));
            }
            EnterpriseData.save(db, "desk-proposal", proposal, revision, decision);
            var outcome = object(DeskTrials.assist(h.store, db, "approve_job", proposal, body));
            assertEquals("handoff", object(outcome.get("definition")).get("decision"));
            var renewed = JourneyForms.definition(db, "agent-grant", delegate);
            renewed.put("active", true);
            EnterpriseData.save(db, "agent-grant", delegate, 2, renewed);
            assertEquals("handoff", DeskTrials.controlled(db, original, originalPlan, "execute"));
            String other = RecordAccess.owner(db, "SeparateAdmin");
            for (var variant :
                    List.of(map("delegate", id()), map("owner", other), map("state", "stopped"))) {
                var pending = new LinkedHashMap<>(originalPlan);
                pending.putAll(variant);
                EnterpriseData.save(db, "agent-plan", id(), 0, pending);
            }
            AgentInvestigations.revoked(db, delegate);
            var routed =
                    AgentInvestigations.open(
                            db, "connector", id(), admin, map("reason", "unverified"));
            var command = map("investigation_id", routed, "confirm", true);
            assertThrows(
                    Failure.class,
                    () -> AgentInvestigations.command(h.store, db, "handoff_resume", command));
            db.viewerId = other;
            AgentInvestigations.command(
                    h.store,
                    db,
                    "handoff_review",
                    map("investigation_id", routed, "note", "Investigated", "revision", 1));
            db.viewerId = admin;
            assertThrows(
                    Failure.class,
                    () -> AgentInvestigations.command(h.store, db, "handoff_resume", command));
            AgentInvestigations.open(db, "connector", id(), other, map());
            db.viewerId = admin;
            assertTrue(
                    object(
                                    AgentInvestigations.command(
                                            h.store,
                                            db,
                                            "investigations",
                                            map("state", "reviewed")))
                            .containsKey("investigations"));
            db.update("UPDATE os_user SET api_only=1 WHERE user_id=?", other);
            assertThrows(
                    Failure.class,
                    () ->
                            AgentInvestigations.command(
                                    h.store,
                                    db,
                                    "reviewer",
                                    map("reviewer_id", other, "revision", 1)));
            db.update("UPDATE os_user SET active=0 WHERE role='admin'");
            String unassigned =
                    AgentInvestigations.open(
                            db, "connector", id(), admin, map("reason", "no eligible owner"));
            assertEquals(
                    "unassigned",
                    JourneyForms.definition(db, "agent-investigation", unassigned).get("owner"));
            db.update("UPDATE os_user SET active=1 WHERE user_id=?", admin);
            var queue =
                    object(
                            AgentInvestigations.command(
                                    h.store, db, "investigations", map("state", "unassigned")));
            assertTrue(
                    objects(queue.get("investigations")).stream()
                            .anyMatch(t -> unassigned.equals(t.get("subject"))));
            var claim =
                    map(
                            "investigation_id",
                            unassigned,
                            "revision",
                            1,
                            "note",
                            "Taking over the investigation");
            EnterpriseWorkspaces.command(
                    h.store,
                    db,
                    "desk",
                    "global",
                    ExpansionBoundaryAcceptance.with(claim, "operation", "claim"));
            assertEquals(
                    admin,
                    JourneyForms.definition(db, "agent-investigation", unassigned).get("owner"));
            assertThrows(
                    Failure.class, () -> AgentInvestigations.command(h.store, db, "claim", claim));
            db.update("UPDATE os_user SET active=1 WHERE user_id=?", other);
            db.viewerId = other;
            assertThrows(
                    Failure.class, () -> AgentInvestigations.command(h.store, db, "claim", claim));
            db.update("UPDATE os_user SET role='agent' WHERE user_id=?", other);
            assertThrows(
                    Failure.class, () -> AgentInvestigations.command(h.store, db, "claim", claim));
        }
        var listing = map("operation", "investigations", "state", "open");
        assertTrue(objects(post(h, "desk", listing).get("investigations")).isEmpty());
        var ticket =
                objects(as(h, "SeparateAdmin", "desk", listing, 200).get("investigations"))
                        .getFirst();
        assertEquals(original, object(ticket.get("definition")).get("subject"));
        String id = ticket.get("subject").toString();
        var review =
                map(
                        "operation",
                        "handoff_review",
                        "investigation_id",
                        id,
                        "note",
                        "Checked record version and confirmed no prior write",
                        "revision",
                        1);
        bad(h, "desk", "global", review, 400);
        bad(
                h,
                "desk",
                "global",
                map("operation", "reviewer", "reviewer_id", delegate, "revision", 1),
                400);
        as(h, "SeparateAdmin", "desk", review, 200);
        assertEquals(
                1,
                objects(
                                post(
                                                h,
                                                "desk",
                                                ExpansionBoundaryAcceptance.with(
                                                        listing, "state", "reviewed"))
                                        .get("investigations"))
                        .size());
        assertTrue(
                objects(as(h, "SeparateAdmin", "desk", listing, 200).get("investigations"))
                        .isEmpty());
        var resume =
                map(
                        "operation",
                        "handoff_resume",
                        "investigation_id",
                        id,
                        "new_id",
                        replacement,
                        "revision",
                        2,
                        "confirm",
                        true);
        bad(h, "desk", "global", resume, 400);
        grant.put("revision", 2);
        send(h, "agents", "global", grant);
        bad(h, "desk", "global", ExpansionBoundaryAcceptance.with(resume, "confirm", false), 400);
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            var row = EnterpriseData.get(db, "agent-investigation", id);
            var same = object(row.get("definition"));
            same.put("reviewed_grant", 3);
            EnterpriseData.save(db, "agent-investigation", id, row.get("revision"), same);
            assertThrows(
                    Failure.class,
                    () -> AgentInvestigations.command(h.store, db, "handoff_resume", resume));
        }
        post(h, "desk", resume);
        assertEquals(
                "reviewed",
                object(doc(h, "agent-plan", replacement).get("definition")).get("state"));
        try (var provider = new RecordQuestionsAcceptance.Provider(h.store, h.environment)) {
            provider.settings.put("AI_AGENT_EXECUTION_ENABLED", "true");
            bad(
                    h,
                    "agents",
                    "global",
                    map("operation", "start", "key", original, "revision", 1, "confirm", true),
                    409);
            send(
                    h,
                    "agents",
                    "global",
                    map("operation", "start", "key", replacement, "revision", 1, "confirm", true));
            assertTrue(AgentPlans.runOne(h.store));
            assertEquals(
                    "completed",
                    object(doc(h, "agent-plan", replacement).get("definition")).get("state"));
        }
    }

    static void mining(WorkflowSupport h) throws Exception {
        String first = h.add("incident").get("sys_id").toString(),
                second = h.add("incident").get("sys_id").toString();
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            for (String id : List.of(first, second)) {
                var item = Service.readRecord(db, "incident", id);
                var date = id.equals(first) ? "2024-01-01" : "2024-02-01";
                db.update(
                        "UPDATE os_event SET created=? WHERE record_id=? AND action='created'",
                        date + " 00:00:00",
                        id);
                var path = id.equals(first) ? List.of("2", "6", "2", "6") : List.of("6");
                int hour = 0;
                for (String state : path) {
                    long seq =
                            Service.audit(
                                    db,
                                    item,
                                    "admin",
                                    "updated",
                                    map("after", map("state", state)));
                    db.update(
                            "UPDATE os_event SET created=? WHERE seq=?",
                            date + " 0" + (++hour) + ":00:00",
                            seq);
                }
            }
            db.connection.commit();
        }
        var query =
                map(
                        "operation",
                        "compare",
                        "table",
                        "incident",
                        "query",
                        "",
                        "before_start",
                        "2024-01-01",
                        "after_start",
                        "2024-02-01",
                        "days",
                        2,
                        "hours",
                        24,
                        "completed_state",
                        "6");
        var bounded = h.add("incident").get("sys_id").toString();
        var withoutCreation = h.add("incident").get("sys_id").toString();
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            db.update(
                    "UPDATE os_event SET action='updated' WHERE record_id=? AND action='created'",
                    withoutCreation);
            db.update(
                    "UPDATE os_event SET created=? WHERE record_id=? AND action='created'",
                    "2024-01-02 00:00:00",
                    bounded);
            String insert =
                    "INSERT INTO os_event(record_id,actor,action,created,detail) SELECT"
                            + " ?,'admin','updated',?,"
                            + db.jsonParameter()
                            + " FROM (WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n"
                            + " WHERE i<500) SELECT i FROM n) AS copies";
            db.update(
                    insert,
                    bounded,
                    "2024-01-02 00:30:00",
                    JSON.writeValueAsString(map("after", map("state", "1"))));
            var observed = object(CaseMining.compare(db, query));
            assertEquals(1, object(observed.get("before")).get("excluded_incomplete_histories"));
            assertTrue(((Number) observed.get("unknown_creation")).intValue() >= 1);
            db.update(
                    "UPDATE os_event SET created=? WHERE seq=(SELECT MAX(seq) FROM os_event WHERE"
                            + " record_id=?)",
                    "2024-01-04 00:00:00",
                    bounded);
            var included = object(CaseMining.compare(db, query));
            assertEquals(0, object(included.get("before")).get("excluded_incomplete_histories"));
            assertEquals(2, objects(object(included.get("before")).get("cases")).size());
            // Moving the start outside both cohorts removes this case from both samples.
            db.update(
                    "UPDATE os_event SET created=? WHERE record_id=? AND action='created'",
                    "2024-01-03 00:00:00",
                    bounded);
            assertEquals(
                    1,
                    objects(
                                    object(object(CaseMining.compare(db, query)).get("before"))
                                            .get("cases"))
                            .size());
            for (var invalid :
                    List.of(
                            map("completed_state", "unknown"),
                            map("after_start", "2024-01-02"),
                            map("after_start", "2099-01-01"))) {
                var request = new LinkedHashMap<>(query);
                request.putAll(invalid);
                assertThrows(Failure.class, () -> CaseMining.compare(db, request));
            }
        }
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            var empty =
                    object(
                            CaseMining.compare(
                                    db,
                                    ExpansionBoundaryAcceptance.with(
                                            query,
                                            "before_start",
                                            "2010-01-01",
                                            "after_start",
                                            "2011-01-01")));
            assertNull(object(empty.get("before")).get("reopen_rate"));
            assertNull(object(empty.get("after")).get("mean_seconds_to_first_completion"));
            db.update("DELETE FROM os_event WHERE record_id=?", first);
            db.update(
                    "UPDATE os_event SET created='2009-01-01 00:00:00' WHERE record_id=?", second);
            assertTrue(
                    ((Number) object(CaseMining.compare(db, query)).get("unknown_creation"))
                                    .intValue()
                            >= 1);
            String columns =
                    "sys_id,\"table\",data,original,created,updated,creator,updater,revision";
            String serial = db.postgres ? "LPAD(CAST(n.i AS TEXT),30,'0')" : "printf('%030d',n.i)";
            db.update(
                    "INSERT INTO os_record("
                            + columns
                            + ") SELECT 'qa'||"
                            + serial
                            + ",r.\"table\",r.data,r.original,r.created,r.updated,r.creator,r.updater,1"
                            + " FROM os_record r CROSS JOIN (WITH RECURSIVE nums(i) AS (SELECT 1"
                            + " UNION ALL SELECT i+1 FROM nums WHERE i<501) SELECT i FROM nums) n"
                            + " WHERE r.sys_id=?",
                    first);
            assertThrows(Failure.class, () -> CaseMining.compare(db, query));
        }
        var result = post(h, "mining", query);
        var before = object(result.get("before"));
        var after = object(result.get("after"));
        assertEquals(1, before.get("reopened"));
        assertEquals(1, before.get("looping_cases"));
        assertEquals(7200, before.get("mean_seconds_to_first_completion"));
        assertEquals(3600, after.get("mean_seconds_to_first_completion"));
        assertEquals(0, after.get("reopened"));
        assertTrue(after.toString().contains(second));
        h.account("RestrictedMiner", "agent");
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            var schema = Service.schema(db, "incident");
            var rules = TypedFields.rules(schema);
            rules.put("state", map("read_roles", List.of("admin")));
            schema.put("field_rules", rules);
            db.update(
                    "UPDATE os_schema SET definition="
                            + db.jsonParameter()
                            + " WHERE name='incident'",
                    JSON.writeValueAsString(schema));
            db.viewerId = RecordAccess.owner(db, "RestrictedMiner");
            db.viewerRole = "agent";
            assertThrows(Failure.class, () -> CaseMining.compare(db, query));
        }
    }
}
