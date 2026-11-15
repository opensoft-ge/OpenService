package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

class ApprovalResubmissionTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void upgradeRollbackRestartAndDuplicateSubmissionPreserveHistory(boolean postgres)
            throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var env = fixture.configuration(postgres);
        var store = new Store(env);
        String record, old;
        List<Map<String, Object>> original;
        try (var db = store.open()) {
            Service.createAccount(
                    db,
                    map("name", "Reviewer", "role", "agent", "password", WorkflowSupport.PASSWORD));
            var admin = ApprovalTest.as(db, "admin");
            record =
                    Service.create(
                                    db,
                                    "incident",
                                    map("short_description", "Resubmit migration"),
                                    "admin")
                            .get("sys_id")
                            .toString();
            old =
                    Approvals.request(
                                    db,
                                    "incident",
                                    record,
                                    admin,
                                    ApprovalAcceptance.proposal("Reviewer"))
                            .get("approval_id")
                            .toString();
            Approvals.withdraw(
                    db, "incident", record, old, admin, map("comment", "Try again later"));
            original = db.all("SELECT * FROM os_approval ORDER BY sequence");
            db.update("DROP INDEX ix_os_approval_proposal");
            db.update(
                    "CREATE UNIQUE INDEX old_review_unique ON"
                        + " os_approval(record_id,record_revision,schema_version,target_state,approver_id)");
            db.update("DELETE FROM os_migration WHERE version=20");
            db.update("CREATE TABLE approval_upgrade_block(value INTEGER)");
            db.update("CREATE INDEX ix_os_approval_proposal ON approval_upgrade_block(value)");
            db.connection.commit();
        }
        assertThrows(Exception.class, () -> new Store(env));
        try (var db = store.open()) {
            assertEquals(original, db.all("SELECT * FROM os_approval ORDER BY sequence"));
            assertEquals(19, db.count("SELECT COUNT(*) FROM os_migration"));
            db.update("DROP TABLE approval_upgrade_block");
            db.connection.commit();
        }
        var upgraded = new Store(env);
        try (var db = upgraded.open()) {
            assertEquals(original, db.all("SELECT * FROM os_approval ORDER BY sequence"));
            assertEquals(20, db.count("SELECT COUNT(*) FROM os_migration"));
            var admin = ApprovalTest.as(db, "admin");
            Approvals.request(
                    db, "incident", record, admin, ApprovalAcceptance.proposal("Reviewer"));
            db.connection.rollback();
            assertEquals(original, db.all("SELECT * FROM os_approval ORDER BY sequence"));
            assertEquals(
                    1, db.count("SELECT COUNT(*) FROM os_event WHERE action='approval.requested'"));
        }
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++)
                results.add(
                        workers.submit(
                                () -> {
                                    start.await();
                                    try (var db = upgraded.open()) {
                                        try {
                                            var admin = ApprovalTest.as(db, "admin");
                                            Approvals.request(
                                                    db,
                                                    "incident",
                                                    record,
                                                    admin,
                                                    ApprovalAcceptance.proposal("Reviewer"));
                                            db.connection.commit();
                                            return 201;
                                        } catch (Failure f) {
                                            db.connection.rollback();
                                            return f.status;
                                        }
                                    }
                                }));
            start.countDown();
            var codes = new ArrayList<Integer>();
            for (var r : results) codes.add(r.get(15, TimeUnit.SECONDS));
            Collections.sort(codes);
            assertEquals(List.of(201, 409), codes);
        }
        var restarted = new Store(env);
        try (var db = restarted.open()) {
            var rows = db.all("SELECT * FROM os_approval ORDER BY sequence");
            assertEquals(2, rows.size());
            assertEquals(original.getFirst(), rows.getFirst());
            assertTrue(
                    ((Number) rows.getLast().get("sequence")).longValue()
                            > ((Number) rows.getFirst().get("sequence")).longValue());
            assertEquals(
                    2, db.count("SELECT COUNT(*) FROM os_event WHERE action='approval.requested'"));
            var reviewer = ApprovalTest.as(db, "Reviewer");
            Approvals.decide(
                    db,
                    "incident",
                    record,
                    rows.getLast().get("approval_id").toString(),
                    reviewer,
                    map("decision", "approved", "comment", ""));
            assertEquals(
                    "1", object(Service.record(db, "incident", record).get("data")).get("state"));
            db.connection.commit();
        }
    }
}
