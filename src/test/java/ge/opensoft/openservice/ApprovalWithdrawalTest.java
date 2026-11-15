package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

class ApprovalWithdrawalTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void withdrawalRollbackRestartAndRacingDecisionStayAtomic(boolean postgres) throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var env = fixture.configuration(postgres);
        var store = new Store(env);
        String record, approval;
        try (var db = store.open()) {
            Service.createAccount(
                    db,
                    map("name", "Reviewer", "role", "agent", "password", WorkflowSupport.PASSWORD));
            var admin = ApprovalTest.as(db, "admin");
            record =
                    Service.create(
                                    db,
                                    "incident",
                                    map("short_description", "Withdrawal race"),
                                    "admin")
                            .get("sys_id")
                            .toString();
            approval =
                    Approvals.request(
                                    db,
                                    "incident",
                                    record,
                                    admin,
                                    ApprovalAcceptance.proposal("Reviewer"))
                            .get("approval_id")
                            .toString();
            db.connection.commit();
            Approvals.withdraw(db, "incident", record, approval, admin, map("comment", "Rollback"));
            db.connection.rollback();
            assertEquals(
                    "requested",
                    db.one("SELECT status FROM os_approval WHERE approval_id=?", approval)
                            .get("status"));
            assertEquals(
                    0, db.count("SELECT COUNT(*) FROM os_event WHERE action='approval.withdrawn'"));
            db.update("UPDATE os_user SET auth_revision=auth_revision+1 WHERE name='admin'");
            db.connection.commit();
            assertEquals(
                    401,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            Approvals.withdraw(
                                                    db,
                                                    "incident",
                                                    record,
                                                    approval,
                                                    admin,
                                                    map("comment", "Stale actor")))
                            .status);
        }
        var restarted = new Store(env);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<Integer>>();
            for (boolean withdraw : List.of(false, true)) {
                results.add(
                        workers.submit(
                                () -> {
                                    start.await();
                                    try (var db = restarted.open()) {
                                        try {
                                            var actor =
                                                    ApprovalTest.as(
                                                            db, withdraw ? "admin" : "Reviewer");
                                            if (withdraw)
                                                Approvals.withdraw(
                                                        db,
                                                        "incident",
                                                        record,
                                                        approval,
                                                        actor,
                                                        map("comment", "No longer needed"));
                                            else
                                                Approvals.decide(
                                                        db,
                                                        "incident",
                                                        record,
                                                        approval,
                                                        actor,
                                                        map(
                                                                "decision",
                                                                "approved",
                                                                "comment",
                                                                "Reviewed"));
                                            db.connection.commit();
                                            return 200;
                                        } catch (Failure e) {
                                            return e.status;
                                        }
                                    }
                                }));
            }
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var result : results) statuses.add(result.get(30, TimeUnit.SECONDS));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
        }
        try (var db = new Store(env).open()) {
            assertTrue(
                    Set.of("approved", "withdrawn")
                            .contains(
                                    db.one(
                                                    "SELECT status FROM os_approval WHERE"
                                                        + " approval_id=?",
                                                    approval)
                                            .get("status")));
            assertEquals(
                    1,
                    db.count(
                            "SELECT COUNT(*) FROM os_event WHERE action IN"
                                + " ('approval.withdrawn','approval.decided')"));
            var item = Service.record(db, "incident", record);
            assertEquals(0, ((Number) item.get("revision")).intValue());
            assertEquals("1", object(item.get("data")).get("state"));
        }
    }
}
