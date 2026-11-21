package ge.opensoft.openservice;

import static ge.opensoft.openservice.PlatformFoundationTest.*;
import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;

class PlatformRuntimeTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realHttpConnectorsMigrationJobsAndAssistanceHaveBoundedAuthority(boolean postgres)
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var status = new AtomicInteger(200);
        var reply =
                new AtomicReference<>(
                        "{\"choices\":[{\"message\":{\"content\":\"Grounded draft\"}}]}");
        var captured = new AtomicReference<String>();
        server.createContext(
                "/",
                exchange -> {
                    captured.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] bytes =
                            (exchange.getRequestURI().getPath().startsWith("/api/now/")
                                            ? "{\"result\":[]}"
                                            : reply.get())
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status.get(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        var fixture = new MigrationsTest();
        fixture.root = root;
        var env =
                fixture.configuration(postgres)
                        .withProperty("CONNECTOR_ALLOWED_ORIGINS", origin)
                        .withProperty("MIGRATION_ALLOWED_ORIGINS", origin);
        var store = new Store(env);
        String actor;
        try {
            try (var db = store.open()) {
                actor = bind(db);
                var connector =
                        map("alias", "sample", "origin", origin, "token", "synthetic-token");
                assertEquals(
                        1,
                        ((Number)
                                        object(
                                                        Operations.execute(
                                                                store,
                                                                db,
                                                                actor,
                                                                "connectors",
                                                                "",
                                                                "POST",
                                                                connector))
                                                .get("revision"))
                                .intValue());
                assertEquals(
                        2,
                        ((Number)
                                        object(
                                                        Operations.execute(
                                                                store,
                                                                db,
                                                                actor,
                                                                "connectors",
                                                                "",
                                                                "POST",
                                                                connector))
                                                .get("revision"))
                                .intValue());
                assertFalse(
                        db.one("SELECT envelope FROM os_connector WHERE alias='sample'")
                                .get("envelope")
                                .toString()
                                .contains("synthetic-token"));
                assertTrue(
                        ((Number)
                                                Connectors.read(
                                                                store,
                                                                db,
                                                                map(
                                                                        "alias",
                                                                        "sample",
                                                                        "path",
                                                                        "/resource"))
                                                        .get("bytes"))
                                        .intValue()
                                > 0);
                bad(() -> Connectors.save(store, db, actor, map()));
                env.setProperty("CONNECTOR_ALLOWED_ORIGINS", "http://remote.invalid");
                bad(
                        () ->
                                Connectors.save(
                                        store,
                                        db,
                                        actor,
                                        map(
                                                "alias",
                                                "remote",
                                                "origin",
                                                "http://remote.invalid",
                                                "token",
                                                "test-token")));
                env.setProperty("CONNECTOR_ALLOWED_ORIGINS", "https://approved.invalid");
                Operations.form(
                        store,
                        db,
                        actor,
                        Map.of(
                                "operation",
                                new String[] {"connector"},
                                "alias",
                                new String[] {"secure"},
                                "origin",
                                new String[] {"https://approved.invalid"},
                                "token",
                                new String[] {"synthetic-token"}));
                bad(
                        () ->
                                Operations.form(
                                        store,
                                        db,
                                        actor,
                                        Map.of("operation", new String[] {"notification", "job"})));
                env.setProperty("CONNECTOR_ALLOWED_ORIGINS", origin);
                for (var change :
                        List.of(
                                map("alias", "INVALID"),
                                map("origin", "https://not-allowed.invalid"),
                                map("token", "bad token"))) {
                    var invalid = new LinkedHashMap<>(connector);
                    invalid.putAll(change);
                    bad(() -> Connectors.save(store, db, actor, invalid));
                }
                bad(() -> Connectors.read(store, db, map()));
                for (String path : List.of("relative", "//elsewhere", "/x#fragment", "/x\\y"))
                    bad(() -> Connectors.read(store, db, map("alias", "sample", "path", path)));
                bad(() -> Connectors.read(store, db, map("alias", "missing", "path", "/")));
                env.setProperty("CONNECTOR_ALLOWED_ORIGINS", "");
                bad(() -> Connectors.read(store, db, map("alias", "sample", "path", "/")));
                env.setProperty("CONNECTOR_ALLOWED_ORIGINS", origin);
                WorkJobs.enqueue(
                        store,
                        db,
                        actor,
                        map(
                                "kind",
                                "connector.read",
                                "key",
                                "connector",
                                "payload",
                                map("alias", "sample", "path", "/resource")));
                var migration =
                        TransferJobs.create(
                                db,
                                map(
                                        "source",
                                        origin,
                                        "tables",
                                        map(
                                                "incident",
                                                map(
                                                        "target",
                                                        "incident",
                                                        "fields",
                                                        map(
                                                                "short_description",
                                                                "short_description"),
                                                        "values",
                                                        map()))),
                                actor,
                                origin);
                WorkJobs.enqueue(
                        store,
                        db,
                        actor,
                        map(
                                "kind",
                                "migration.step",
                                "key",
                                "migration",
                                "payload",
                                map(
                                        "migration_id",
                                        migration.get("job_id"),
                                        "token",
                                        "synthetic-token")));
                db.connection.commit();
            }
            var worker = new WorkWorker(store);
            status.set(503);
            assertTrue(worker.runOne(System.currentTimeMillis()));
            try (var db = store.open()) {
                assertEquals(
                        1,
                        db.count(
                                "SELECT COUNT(*) FROM os_work WHERE state='queued' AND"
                                    + " error='EXECUTION_FAILED'"));
                db.update("UPDATE os_work SET available_at=0");
                db.connection.commit();
            }
            status.set(200);
            assertTrue(worker.runOne(System.currentTimeMillis()));
            assertTrue(worker.runOne(System.currentTimeMillis()));
            try (var db = store.open()) {
                bind(db);
                assertEquals(2, db.count("SELECT COUNT(*) FROM os_work WHERE state='succeeded'"));
                String id = record(db, "incident", "Summary grounded in this record");
                var request = map("table", "incident", "record_id", id, "allow_external", true,"preview_token","pending");
                bad(() -> ProviderAssist.summarize(store, db, actor, request));
                env.setProperty("AI_ENABLED", "true");
                bad(() -> ProviderAssist.summarize(store, db, actor, map()));
                var denied = new LinkedHashMap<>(request);
                denied.put("allow_external", false);
                bad(() -> ProviderAssist.summarize(store, db, actor, denied));
                bad(() -> ProviderAssist.summarize(store, db, actor, request)); // No model.
                env.setProperty("AI_MODEL", "synthetic-model");
                bad(() -> ProviderAssist.summarize(store, db, actor, request)); // No alias.
                env.setProperty("AI_CONNECTOR_ALIAS", "sample");
                env.setProperty("CONNECTOR_ALLOWED_ORIGINS", "");
                bad(() -> ProviderAssist.summarize(store, db, actor, request));
                env.setProperty("CONNECTOR_ALLOWED_ORIGINS", origin);
                env.setProperty("AI_ALLOWED_FIELDS", "not_present");
                bad(() -> ProviderAssist.summarize(store, db, actor, request));
                env.setProperty("AI_ALLOWED_FIELDS", "short_description,not_present");
                String beforePreview = captured.get();
                var preview = ProviderAssist.preview(store, db, actor, map("table","incident","record_id",id));
                assertEquals(beforePreview, captured.get());
                assertTrue(preview.containsKey("preview_token"), "Execution must be bound to the reviewed preview");
                AiConsentAcceptance.run(store,db,actor,id,request,preview,captured);
                request.put("preview_token",ProviderAssist.preview(store,db,actor,map("table","incident","record_id",id)).get("preview_token"));
                var draft =
                        object(Operations.execute(store, db, actor, "assist", "", "POST", request));
                assertEquals(preview.get("content"), object(objects(object(JSON.readValue(captured.get(),Object.class)).get("messages")).getLast()).get("content"));
                assertEquals("Grounded draft", draft.get("draft"));
                assertEquals(true, draft.get("requires_review"));
                assertEquals(0, draft.get("actions_executed"));
                assertTrue(captured.get().contains("Summary grounded"));
                assertFalse(captured.get().contains("assigned_to"));
                assertFalse(captured.get().contains("tools" + "\":"));
                assertEquals(
                        0,
                        ((Number) Service.record(db, "incident", id).get("revision")).intValue());
                assertFalse(
                        JSON.writeValueAsString(SecurityEvents.page(db, Map.of()))
                                .contains("Grounded draft"));
                var record = Service.record(db, "incident", id);
                object(record.get("data")).put("short_description", "x".repeat(16001));
                Service.save(db, record);
                bad(() -> ProviderAssist.summarize(store, db, actor, request));
                db.connection.commit();
            }
            status.set(503);
            bad(() -> ProviderAssist.execute(origin, "synthetic-token", map()));
            status.set(200);
            for (String malformed :
                    List.of(
                            "not json",
                            "[]",
                            "{}",
                            "{\"choices\":[]}",
                            "{\"choices\":[{\"message\":{\"tool_calls\":[],\"content\":\"Do"
                                    + " something\"}}]}",
                            "{\"choices\":[{\"message\":{\"content\":\"\"}}]}")) {
                reply.set(malformed);
                bad(() -> ProviderAssist.execute(origin, "synthetic-token", map()));
            }
            reply.set("x".repeat(65537));
            bad(() -> ProviderAssist.execute(origin, "synthetic-token", map()));
            Thread.currentThread().interrupt();
            try {
                bad(() -> ProviderAssist.execute(origin, "synthetic-token", map()));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        } finally {
            server.stop(0);
        }
        bad(() -> ProviderAssist.execute(origin, "synthetic-token", map()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void leasesRetriesCancellationAndWorkerCleanupSurviveRestart(boolean postgres)
            throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var env = fixture.configuration(postgres);
        var store = new Store(env);
        var worker = new WorkWorker(store);
        long now = System.currentTimeMillis();
        long restartAt;
        String actor, broken;
        try (var db = store.open()) {
            actor = bind(db);
            broken =
                    WorkJobs.enqueue(
                                    store,
                                    db,
                                    actor,
                                    map("kind", "notification", "key", "broken", "payload", map()))
                            .get("job_id")
                            .toString();
            db.update("UPDATE os_work SET envelope='damaged' WHERE job_id=?", broken);
            db.connection.commit();
        }
        for (int i = 0; i < 5; i++) assertTrue(worker.runOne(now + 1000000L * (i + 1)));
        try (var db = store.open()) {
            bind(db);
            assertEquals(
                    "failed",
                    db.one("SELECT state FROM os_work WHERE job_id=?", broken).get("state"));
            WorkJobs.control(db, actor, broken, "retry");
            WorkJobs.control(db, actor, broken, "cancel");
            String expired =
                    WorkJobs.enqueue(
                                    store,
                                    db,
                                    actor,
                                    map("kind", "notification", "key", "expired", "payload", map()))
                            .get("job_id")
                            .toString();
            db.update(
                    "UPDATE os_work SET state='processing',attempts=5,available_at=0 WHERE"
                            + " job_id=?",
                    expired);
            assertNull(WorkJobs.claim(db, now));
            assertEquals(
                    "failed",
                    db.one("SELECT state FROM os_work WHERE job_id=?", expired).get("state"));
            WorkJobs.control(db, actor, expired, "cancel");
            WorkJobs.enqueue(
                    store,
                    db,
                    actor,
                    map(
                            "kind",
                            "notification",
                            "key",
                            "restart",
                            "payload",
                            map("username", "admin", "message", "Exactly one inbox insertion")));
            restartAt=System.currentTimeMillis();
            var old = WorkJobs.claim(db, restartAt + 1000);
            assertNotNull(old);
            db.connection.commit();
        }
        assertTrue(worker.runClaimed(map("job_id", "absent", "lease", "absent"), restartAt));
        // The first lease is abandoned. A new worker reclaims it after the durable deadline.
        assertTrue(new WorkWorker(new Store(env)).runOne(restartAt + 300000));
        try (var db = store.open()) {
            bind(db);
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_notice WHERE message='Exactly one inbox insertion'"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_notice_target WHERE kind='work'"));
            db.update("UPDATE os_notice SET acknowledged=1,created_at=0");
            WorkJobs.enqueue(
                    store,
                    db,
                    actor,
                    map("kind", "notification", "key", "unknown-kind", "payload", map()));
            db.update("UPDATE os_work SET kind='unknown' WHERE dedupe='unknown-kind'");
            db.connection.commit();
        }
        assertTrue(worker.runOne(restartAt + 400000));
        for (String mutation : List.of("active=0", "role='reader'")) {
            try (var db = store.open()) {
                bind(db);
                WorkJobs.enqueue(
                        store,
                        db,
                        actor,
                        map("kind", "notification", "key", mutation, "payload", map()));
                db.update("UPDATE os_user SET " + mutation + " WHERE user_id=?", actor);
                db.connection.commit();
            }
            assertTrue(worker.runOne(restartAt + 500000));
            try (var db = store.open()) {
                db.update("UPDATE os_user SET active=1,role='admin' WHERE user_id=?", actor);
                db.connection.commit();
            }
        }
        try (var db = store.open()) {
            bind(db);
            WorkJobs.enqueue(
                    store,
                    db,
                    actor,
                    map("kind", "notification", "key", "removed-owner", "payload", map()));
            db.update("UPDATE os_work SET owner_id='removed' WHERE dedupe='removed-owner'");
            db.connection.commit();
        }
        assertTrue(worker.runOne(restartAt + 600000));
        env.setProperty("WORK_ENABLED", "true");
        worker.tick();
        try (var db = store.open()) {
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_notice WHERE acknowledged=1"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_notice n JOIN os_notice_target t ON t.notice_id=n.notice_id JOIN os_work w ON w.job_id=t.target_id WHERE n.acknowledged=0 AND w.dedupe='unknown-kind'"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_notice"));
            db.update("DROP TABLE os_work");
            db.connection.commit();
        }
        assertThrows(
                java.sql.SQLException.class,
                () -> worker.runClaimed(map("job_id", "absent", "lease", "absent"), restartAt));
        worker.tick(); // Failure stays contained and does not expose transport/credential details.
    }
}
