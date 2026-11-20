package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseAcceptance.*;
import static ge.opensoft.openservice.TenJourneysAcceptance.post;
import static ge.opensoft.openservice.TenPolicyAcceptance.as;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

final class ServiceDeskOutcomeAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        assertFalse(DeskTrials.finalState("stop", false, 1, 1, "stopped"));
        assertFalse(DeskTrials.finalState("stop", false, 0, 1, "queued"));
        assertFalse(DeskTrials.finalState("execute", true, 0, 1, "completed"));
        assertFalse(DeskTrials.finalState("handoff", false, 1, 1, "completed"));
        assertEquals("stop", DeskTrials.decision("{\"decision\":\"stop\"}"));
        for (String invalid :
                List.of(
                        "{bad",
                        "{\"decision\":\"stop\",\"extra\":1}",
                        "go",
                        "\"",
                        "\"open",
                        "stop\"")) assertEquals("invalid_response", DeskTrials.decision(invalid));
        h.account("OrderOwner", "requester");
        h.account("ServiceAgent", "agent");
        String group = h.add("sys_user_group").get("sys_id").toString(),
                user = uid(h, "ServiceAgent");
        FulfilmentAcceptance.member(h, group, "ServiceAgent", true);
        String offering =
                FulfilmentAcceptance.result(
                                h.json(
                                        "POST",
                                        FulfilmentAcceptance.CATALOG,
                                        CatalogueAcceptance.offering("Managed access", 0, true),
                                        auth("admin")),
                                201)
                        .get("offering_id")
                        .toString();
        FulfilmentAcceptance.result(
                h.json(
                        "PUT",
                        FulfilmentAcceptance.CATALOG + "/" + offering + "/routing",
                        map("group_id", group, "revision", 1),
                        auth("admin")),
                200);
        String request = FulfilmentAcceptance.order(h, offering, 2),
                incident = h.add("incident").get("sys_id").toString(),
                workflow = id(),
                suite = id();
        EnterpriseAgentFaults.user(h, "ServiceAgent", "api_only", 1);
        String owner = uid(h, "OrderOwner");
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            EnterpriseData.save(
                    db,
                    "workflow-run",
                    workflow,
                    0,
                    map(
                            "employee",
                            owner,
                            "state",
                            "complete",
                            "steps",
                            List.of(map("kind", "entra_add", "state", "done"))));
            db.connection.commit();
        }
        try (var provider = new RecordQuestionsAcceptance.Provider(h.store, h.environment)) {
            provider.settings.put("AI_AGENT_EXECUTION_ENABLED", "true");
            for (var invalid :
                    List.of(
                            map("employee", owner, "state", "running", "steps", List.of()),
                            map(
                                    "employee",
                                    owner,
                                    "state",
                                    "complete",
                                    "steps",
                                    List.of(
                                            map("kind", "approval", "state", "done"),
                                            map("kind", "entra_add", "state", "pending"))),
                            map(
                                    "employee",
                                    user,
                                    "state",
                                    "complete",
                                    "steps",
                                    List.of(map("kind", "entra_add", "state", "done"))))) {
                String key = id();
                try (var db = h.store.open()) {
                    PlatformFoundationTest.bind(db);
                    EnterpriseData.save(db, "workflow-run", key, 0, invalid);
                    db.connection.commit();
                }
                bad(
                        h,
                        "desk",
                        "global",
                        map("operation", "bind_access", "new_id", request, "run_id", key),
                        400);
            }
            post(h, "desk", map("operation", "bind_access", "new_id", request, "run_id", workflow));
            var targets =
                    List.of(
                            map(
                                    "table",
                                    "sc_request",
                                    "record_id",
                                    request,
                                    "fields",
                                    List.of("state")),
                            map(
                                    "table",
                                    "incident",
                                    "record_id",
                                    incident,
                                    "fields",
                                    List.of("short_description")));
            send(
                    h,
                    "agents",
                    "global",
                    map(
                            "operation",
                            "grant",
                            "revision",
                            0,
                            "active",
                            true,
                            "targets",
                            targets,
                            "delegate_id",
                            user));
            try (var db = h.store.open()) {
                db.viewerId = user;
                db.viewerRole = "agent";
                var record = Service.readRecord(db, "sc_request", request);
                var step =
                        map(
                                "table",
                                "sc_request",
                                "record_id",
                                request,
                                "patch",
                                map("state", "unsupported"));
                assertThrows(Failure.class, () -> AgentPlans.apply(db, step, record));
                step.put("patch", map("state", "fulfilling"));
                AgentPlans.apply(db, step, record);
                step.put("patch", map("state", "complete"));
                assertEquals(
                        "complete",
                        object(
                                        AgentPlans.apply(
                                                        db,
                                                        step,
                                                        Service.readRecord(
                                                                db, "sc_request", request))
                                                .get("data"))
                                .get("state"));
            }
            var triage =
                    map(
                            "table",
                            "incident",
                            "record_id",
                            incident,
                            "patch",
                            map(
                                    "short_description",
                                    "Triage confirmed a customer-impacting outage"));
            var access =
                    map(
                            "table",
                            "sc_request",
                            "record_id",
                            request,
                            "patch",
                            map("state", "complete"));
            String triageId = id(), accessId = id(), failure = id(), recovery = id();
            for (var e : Map.of(triageId, triage, accessId, access, failure, access).entrySet())
                send(
                        h,
                        "agents",
                        "global",
                        map(
                                "operation",
                                "review",
                                "delegate_id",
                                user,
                                "key",
                                e.getKey(),
                                "grant_revision",
                                1,
                                "steps",
                                List.of(e.getValue())));
            send(
                    h,
                    "agents",
                    "global",
                    map("operation", "start", "key", failure, "revision", 1, "confirm", true));
            try (var db = h.store.open()) {
                PlatformFoundationTest.bind(db);
                db.update(
                        "UPDATE os_fulfilment SET revision=revision+1 WHERE request_id=?", request);
                var r = EnterpriseData.get(db, "agent-plan", failure);
                r.put("subject", failure);
                AgentPlans.executeStep(db, r);
                db.connection.commit();
            }
            post(h, "desk", map("operation", "recover", "new_id", recovery, "failed_id", failure));
            accessId = id();
            send(
                    h,
                    "agents",
                    "global",
                    map(
                            "operation",
                            "review",
                            "delegate_id",
                            user,
                            "key",
                            accessId,
                            "grant_revision",
                            1,
                            "steps",
                            List.of(access)));
            post(
                    h,
                    "desk",
                    map(
                            "operation",
                            "register",
                            "new_id",
                            suite,
                            "triage",
                            triageId,
                            "access",
                            accessId,
                            "recovery",
                            recovery));
            try (var db = h.store.open()) {
                PlatformFoundationTest.bind(db);
                EnterpriseData.save(db, "desk-suite", id(), 0, map("owner", user));
                assertFalse(DeliveryWorkspace.workspaceData(db, "desk").isEmpty());
                db.viewerRole = "requester";
                assertThrows(Failure.class, () -> DeliveryWorkspace.page(db, "desk", "global"));
                assertThrows(
                        Failure.class,
                        () ->
                                DeliveryWorkspace.command(
                                        h.store, db, "desk", map("operation", "register")));
            }
            var suiteInputs =
                    map(
                            "operation",
                            "register",
                            "new_id",
                            id(),
                            "triage",
                            triageId,
                            "access",
                            recovery,
                            "recovery",
                            recovery);
            bad(h, "desk", "global", suiteInputs, 400);
            String local = System.getenv("OPENSERVICE_QA_OLLAMA");
            if (local != null) {
                assertEquals("http://127.0.0.1:11459", local);
                provider.settings.put("AI_MODEL", "gemma4:e2b-mlx");
                provider.settings.put("CONNECTOR_ALLOWED_ORIGINS", local);
                try (var db = h.store.open()) {
                    db.update(
                            "UPDATE os_connector SET origin=? WHERE alias=?",
                            local,
                            provider.alias);
                    db.connection.commit();
                }
            } else {
                var count = new AtomicInteger();
                provider.server.removeContext("/v1/chat/completions");
                provider.server.createContext(
                        "/v1/chat/completions",
                        e -> {
                            int i = count.getAndIncrement() % 6;
                            String answer = i < 3 ? "execute" : i == 4 ? "stop" : "handoff";
                            var bytes =
                                    JSON.writeValueAsBytes(
                                            map(
                                                    "choices",
                                                    List.of(
                                                            map(
                                                                    "message",
                                                                    map(
                                                                            "content",
                                                                            JSON.writeValueAsString(
                                                                                    answer)))),
                                                    "usage",
                                                    map(
                                                            "prompt_tokens",
                                                            45,
                                                            "completion_tokens",
                                                            3)));
                            e.sendResponseHeaders(200, bytes.length);
                            e.getResponseBody().write(bytes);
                            e.close();
                        });
            }
            provider.settings.put("AI_AGENT_EXECUTION_ENABLED", "true");
            try (var db = h.store.open()) {
                String actor = PlatformFoundationTest.bind(db);
                var reviewed = object(AgentPlans.owned(db, triageId).get("definition"));
                AgentPlans.command(
                        h.store,
                        db,
                        map("operation", "revoke", "delegate_id", user, "revision", 1));
                assertThrows(Failure.class, () -> DeskTrials.authorizeContext(db, reviewed));
                assertEquals(actor, db.viewerId);
            }
            var preview = post(h, "desk", map("operation", "preview", "new_id", suite));
            var evaluate =
                    map(
                            "operation",
                            "evaluate",
                            "new_id",
                            suite,
                            "preview_token",
                            preview.get("preview_token"),
                            "confirm",
                            true);
            evaluate.put("confirm", false);
            bad(h, "desk", "global", evaluate, 400);
            evaluate.put("confirm", true);
            provider.settings.put("AI_ENABLED", "false");
            bad(h, "desk", "global", evaluate, 400);
            provider.settings.put("AI_ENABLED", "true");
            var result = object(post(h, "desk", evaluate).get("definition"));
            assertEquals(6, objects(result.get("results")).size());
            if (local != null && "1".equals(System.getenv("OPENSERVICE_QA_HOLDOUT")))
                ServiceDeskHoldout.qualify(
                        h, Map.of("triage", triageId, "access", accessId, "recovery", recovery));
            if (local == null) assertEquals(6, result.get("passed"));
            else
                Files.writeString(
                        Path.of("target/service-desk-local-model.json"),
                        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            assertEquals(
                    result,
                    object(
                            post(h, "desk", map("operation", "report", "new_id", suite))
                                    .get("definition")));
            post(h, "desk", evaluate);
            try (var db = h.store.open()) {
                assertEquals("queued", Fulfilment.row(db, request).get("state"));
            }
            if (local == null) {
                String downtime = id();
                post(
                        h,
                        "desk",
                        map(
                                "operation",
                                "register",
                                "new_id",
                                downtime,
                                "triage",
                                triageId,
                                "access",
                                accessId,
                                "recovery",
                                recovery));
                var token = post(h, "desk", map("operation", "preview", "new_id", downtime));
                var failedEvaluation =
                        map(
                                "operation",
                                "evaluate",
                                "new_id",
                                downtime,
                                "preview_token",
                                token.get("preview_token"),
                                "confirm",
                                true);
                try (var db = h.store.open()) {
                    PlatformFoundationTest.bind(db);
                    try {
                        Thread.currentThread().interrupt();
                        assertThrows(
                                Failure.class,
                                () -> DeskTrials.run(h.store, db, "evaluate", failedEvaluation));
                    } finally {
                        Thread.interrupted();
                    }
                    for (var trial : DeskTrials.CASES.subList(3, 6)) {
                        var blocked = DeskTrials.exercise(h.store, db, accessId, trial, "execute");
                        assertEquals(0, blocked.get("writes"));
                        assertTrue(blocked.containsKey("execution_error"));
                    }
                    var stale = EnterpriseData.get(db, "agent-plan", triageId);
                    var definition = object(stale.get("definition"));
                    objects(definition.get("steps")).getFirst().put("revision", -1);
                    EnterpriseData.save(
                            db, "agent-plan", triageId, stale.get("revision"), definition);
                    assertFalse(
                            (Boolean)
                                    DeskTrials.exercise(
                                                    h.store,
                                                    db,
                                                    triageId,
                                                    DeskTrials.CASES.getFirst(),
                                                    "execute")
                                            .get("passed"));
                }
                var baseAlias = provider.settings.put("AI_MODEL", "");
                var reconsent = post(h, "desk", map("operation", "preview", "new_id", downtime));
                failedEvaluation.put("preview_token", reconsent.get("preview_token"));
                assertEquals(
                        0,
                        object(post(h, "desk", failedEvaluation).get("definition")).get("passed"));
                provider.settings.put("AI_MODEL", baseAlias);
            }
            for (String plan : List.of(triageId, recovery)) {
                if (local == null) {
                    var job =
                            map(
                                    "operation",
                                    "preview_job",
                                    "new_id",
                                    id(),
                                    "plan_id",
                                    plan,
                                    "revision",
                                    1,
                                    "job",
                                    plan.equals(recovery) ? "recovery" : "triage",
                                    "goal",
                                    "Execute the approved service task");
                    var operator = post(h, "desk", job);
                    job.putAll(
                            map(
                                    "operation",
                                    "propose_job",
                                    "preview_token",
                                    operator.get("preview_token"),
                                    "confirm",
                                    true));
                    assertEquals(
                            "execute",
                            object(post(h, "desk", job).get("definition")).get("decision"));
                    job.remove("preview_token");
                    job.put("operation", "approve_job");
                    post(h, "desk", job);
                } else
                    send(
                            h,
                            "agents",
                            "global",
                            map("operation", "start", "key", plan, "revision", 1, "confirm", true));
                assertTrue(AgentPlans.runOne(h.store));
            }
            try (var db = h.store.open()) {
                assertEquals("complete", Fulfilment.row(db, request).get("state"));
            }
            if (local == null) {
                provider.server.removeContext("/v1/chat/completions");
                var proposed = new java.util.concurrent.atomic.AtomicReference<>("handoff");
                provider.server.createContext(
                        "/v1/chat/completions",
                        e -> {
                            var bytes =
                                    JSON.writeValueAsBytes(
                                            map(
                                                    "choices",
                                                    List.of(
                                                            map(
                                                                    "message",
                                                                    map(
                                                                            "content",
                                                                            proposed.get())))));
                            e.sendResponseHeaders(200, bytes.length);
                            e.getResponseBody().write(bytes);
                            e.close();
                        });
                var job =
                        map(
                                "operation",
                                "preview_job",
                                "new_id",
                                id(),
                                "plan_id",
                                accessId,
                                "revision",
                                1,
                                "job",
                                "access",
                                "goal",
                                "Stop this work");
                var operator = post(h, "desk", job);
                job.putAll(
                        map(
                                "operation",
                                "propose_job",
                                "preview_token",
                                operator.get("preview_token"),
                                "confirm",
                                true));
                post(h, "desk", job);
                job.remove("preview_token");
                job.put("operation", "approve_job");
                assertEquals(
                        "awaiting_operator",
                        object(post(h, "desk", job).get("definition")).get("follow_up"));
                var review =
                        map(
                                "operation",
                                "finish_review",
                                "new_id",
                                job.get("new_id"),
                                "note",
                                "Reconciled with the directory administrator",
                                "revision",
                                1);
                h.account("DifferentAdmin", "admin");
                as(h, "DifferentAdmin", "desk", review, 400);
                post(h, "desk", review);
                bad(h, "desk", "global", review, 400);
                proposed.set("stop");
                for (int revision : List.of(1, 2)) {
                    job.putAll(
                            map("new_id", id(), "operation", "preview_job", "revision", revision));
                    job.remove("confirm");
                    var token = post(h, "desk", job);
                    job.putAll(
                            map(
                                    "operation",
                                    "propose_job",
                                    "preview_token",
                                    token.get("preview_token"),
                                    "confirm",
                                    true));
                    post(h, "desk", job);
                    job.remove("preview_token");
                    job.put("operation", "approve_job");
                    assertEquals(
                            "stopped", object(post(h, "desk", job).get("definition")).get("state"));
                }
            }
        }
    }
}
