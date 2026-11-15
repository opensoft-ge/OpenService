package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

class ApprovalTest {
    @TempDir Path root;

    static Map<String, Object> as(Store.Db db, String name) throws Exception {
        var actor = db.one("SELECT * FROM os_user WHERE name=?", name);
        db.viewerId = actor.get("user_id").toString();
        db.viewerRole = actor.get("role").toString();
        return actor;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void upgradeRollbackRestartAndConcurrentReviewAreAtomic(boolean postgres) throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var env = fixture.configuration(postgres);
        var original = new Store(env);
        String record;
        try (var db = original.open()) {
            Service.createAccount(
                    db,
                    map("name", "Reviewer", "role", "agent", "password", WorkflowSupport.PASSWORD));
            as(db, "admin");
            record =
                    Service.create(
                                    db,
                                    "incident",
                                    map("short_description", "Review fixture"),
                                    "admin")
                            .get("sys_id")
                            .toString();
            db.update("DROP TABLE os_approval");
            db.update("DELETE FROM os_migration WHERE version>=19");
            db.connection.commit();
        }
        var store = new Store(env);
        try (var db = store.open()) {
            assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
            var actor = as(db, "admin");
            Approvals.request(
                    db, "incident", record, actor, ApprovalAcceptance.proposal("Reviewer"));
            db.connection.rollback();
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_approval"));
            assertEquals(
                    0, db.count("SELECT COUNT(*) FROM os_event WHERE action LIKE 'approval.%'"));
        }
        var ids = new CopyOnWriteArrayList<String>();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++)
                results.add(
                        workers.submit(
                                () -> {
                                    start.await();
                                    try (var db = store.open()) {
                                        try {
                                            var result =
                                                    Approvals.request(
                                                            db,
                                                            "incident",
                                                            record,
                                                            as(db, "admin"),
                                                            ApprovalAcceptance.proposal(
                                                                    "Reviewer"));
                                            db.connection.commit();
                                            ids.add(result.get("approval_id").toString());
                                            return 201;
                                        } catch (Failure error) {
                                            return error.status;
                                        }
                                    }
                                }));
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var result : results) statuses.add(result.get(30, TimeUnit.SECONDS));
            Collections.sort(statuses);
            assertEquals(List.of(201, 409), statuses);
        }
        String id = ids.getFirst();
        var restarted = new Store(env);
        try (var db = restarted.open()) {
            var reviewer = as(db, "Reviewer");
            Approvals.decide(
                    db,
                    "incident",
                    record,
                    id,
                    reviewer,
                    map("decision", "approved", "comment", "rollback"));
            db.connection.rollback();
            assertEquals(
                    "requested",
                    db.one("SELECT status FROM os_approval WHERE approval_id=?", id).get("status"));
            assertEquals(
                    0, db.count("SELECT COUNT(*) FROM os_event WHERE action='approval.decided'"));
            db.update("UPDATE os_user SET auth_revision=auth_revision+1 WHERE name='Reviewer'");
            db.connection.commit();
            assertEquals(
                    401,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            Approvals.decide(
                                                    db,
                                                    "incident",
                                                    record,
                                                    id,
                                                    reviewer,
                                                    map("decision", "approved", "comment", "")))
                            .status);
            assertEquals(
                    401,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            Approvals.request(
                                                    db,
                                                    "incident",
                                                    record,
                                                    reviewer,
                                                    ApprovalAcceptance.proposal("admin")))
                            .status);
        }
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<Integer>>();
            for (String decision : List.of("approved", "rejected"))
                results.add(
                        workers.submit(
                                () -> {
                                    start.await();
                                    try (var db = restarted.open()) {
                                        try {
                                            Approvals.decide(
                                                    db,
                                                    "incident",
                                                    record,
                                                    id,
                                                    as(db, "Reviewer"),
                                                    map(
                                                            "decision",
                                                            decision,
                                                            "comment",
                                                            "Concurrent decision"));
                                            db.connection.commit();
                                            return 200;
                                        } catch (Failure error) {
                                            return error.status;
                                        }
                                    }
                                }));
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var result : results) statuses.add(result.get(30, TimeUnit.SECONDS));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
        }
        try (var db = new Store(env).open()) {
            as(db, "admin");
            var rows = objects(Approvals.page(db, "incident", record, Map.of()).get("result"));
            assertEquals(1, rows.size());
            assertNotEquals("requested", rows.getFirst().get("status"));
            assertEquals(
                    1, db.count("SELECT COUNT(*) FROM os_event WHERE action='approval.requested'"));
            assertEquals(
                    1, db.count("SELECT COUNT(*) FROM os_event WHERE action='approval.decided'"));
            assertEquals(
                    0,
                    ((Number) Service.record(db, "incident", record).get("revision")).intValue());
            assertEquals(
                    1,
                    ((Number)
                                    db.one(
                                                    "SELECT decision_revision FROM os_approval"
                                                        + " WHERE approval_id=?",
                                                    id)
                                            .get("decision_revision"))
                            .intValue());
        }
    }
}
