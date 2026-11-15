package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

class EmailEnrollmentTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidationUpgradeAndConcurrentConfirmation(boolean postgres) throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var env =
                fixture.configuration(postgres)
                        .withProperty("DELIVERY_ENABLED", "true")
                        .withProperty("PUBLIC_BASE_URL", "https://example.invalid");
        var store = new Store(env);
        String actor, hash;
        try (var db = store.open()) {
            actor = RecordAccess.owner(db, "admin");
            hash =
                    db.one("SELECT password FROM os_user WHERE user_id=?", actor)
                            .get("password")
                            .toString();
            db.update("DROP TABLE os_email_enrollment");
            db.update("DELETE FROM os_migration WHERE version=15");
            db.connection.commit();
        }
        store = new Store(env);
        var payload =
                map(
                        "email",
                        "proof@example.invalid",
                        "current_password",
                        "migration-test-password");
        String token;
        try (var db = store.open()) {
            assertEquals(
                    hash,
                    db.one("SELECT password FROM os_user WHERE user_id=?", actor).get("password"));
            assertEquals(15L, db.count("SELECT COUNT(*) FROM os_migration"));
            var caller = db.one("SELECT * FROM os_user WHERE user_id=?", actor);
            var job = EmailEnrollment.request(store, db, caller, payload).get("delivery_id");
            token =
                    Outbox.decrypt(
                                    store.secret,
                                    db.one("SELECT envelope FROM os_outbox WHERE job_id=?", job)
                                            .get("envelope")
                                            .toString())
                            .get("path")
                            .toString()
                            .substring(14);
            assertThrows(Failure.class, () -> EmailEnrollment.describe(db, Passwords.token()));
            db.connection.commit();
        }
        try (var db = store.open()) {
            EmailEnrollment.confirm(db, token);
            db.connection.rollback();
            assertEquals(
                    "", db.one("SELECT email FROM os_user WHERE user_id=?", actor).get("email"));
            assertNotNull(EmailEnrollment.describe(db, token));
            db.update("UPDATE os_email_enrollment SET expires_at=0 WHERE user_id=?", actor);
            assertThrows(Failure.class, () -> EmailEnrollment.describe(db, token));
            db.connection.rollback();
            for (String mutation :
                    List.of(
                            "active=0",
                            "auth_revision=auth_revision+1",
                            "email='taken@example.invalid'")) {
                db.update("UPDATE os_user SET " + mutation + " WHERE user_id=?", actor);
                assertThrows(Failure.class, () -> EmailEnrollment.describe(db, token));
                db.connection.rollback();
            }
            Service.createAccount(
                    db,
                    map("name", "Other", "role", "reader", "password", WorkflowSupport.PASSWORD));
            db.update(
                    "UPDATE os_user SET email='proof@example.invalid',email_verified=1 WHERE"
                        + " name='Other'");
            assertEquals(
                    409,
                    assertThrows(Failure.class, () -> EmailEnrollment.confirm(db, token)).status);
            db.connection.rollback();
            var caller = db.one("SELECT * FROM os_user WHERE user_id=?", actor);
            db.update("UPDATE os_user SET active=0 WHERE user_id=?", actor);
            final Store selectedStore = store;
            assertEquals(
                    401,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            EmailEnrollment.request(
                                                    selectedStore, db, caller, payload))
                            .status);
            db.connection.rollback();
            // Simulate a revoked authenticated caller who knows the unchanged password.
            caller.put("auth_revision", -1L);
            assertEquals(
                    401,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            EmailEnrollment.request(
                                                    selectedStore, db, caller, payload))
                            .status);
            db.connection.rollback();
        }
        // Reopen before simultaneous consumption; both connections see the same persisted request.
        final Store reopened = new Store(env);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                results.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    try (var db = reopened.open()) {
                                        try {
                                            EmailEnrollment.confirm(db, token);
                                            db.connection.commit();
                                            return 200;
                                        } catch (Failure failure) {
                                            db.connection.rollback();
                                            return failure.status;
                                        }
                                    }
                                }));
            start.countDown();
            var codes =
                    List.of(
                            results.get(0).get(30, TimeUnit.SECONDS),
                            results.get(1).get(30, TimeUnit.SECONDS));
            assertEquals(1L, codes.stream().filter(c -> c == 200).count());
            assertEquals(1L, codes.stream().filter(c -> c == 400).count());
        }
        try (var db = reopened.open()) {
            assertEquals(
                    "proof@example.invalid",
                    db.one("SELECT email FROM os_user WHERE user_id=?", actor).get("email"));
            assertEquals(0L, db.count("SELECT COUNT(*) FROM os_email_enrollment"));
            assertEquals(
                    1L,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                + " action='account.email_enrollment_confirmed'"));
        }
    }
}
