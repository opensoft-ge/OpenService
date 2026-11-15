package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

class PasswordExclusionTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void upgradeRollbackConcurrencyAndBootstrapHonorPolicy(boolean postgres) throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var env = fixture.configuration(postgres);
        var initial = new Store(env);
        String actor, hash;
        try (var db = initial.open()) {
            var user = db.one("SELECT * FROM os_user WHERE name='admin'");
            actor = user.get("user_id").toString();
            hash = user.get("password").toString();
            db.update("DROP TABLE os_password_exclusion");
            db.update("DELETE FROM os_migration WHERE version=16");
            db.connection.commit();
        }
        var store = new Store(env);
        String id;
        var body = map("label", "Existing bootstrap value", "password", "migration-test-password");
        try (var db = store.open()) {
            assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
            assertEquals(
                    hash,
                    db.one("SELECT password FROM os_user WHERE user_id=?", actor).get("password"));
            PasswordExclusions.add(db, actor, body);
            db.connection.rollback();
            assertEquals(0L, db.count("SELECT COUNT(*) FROM os_password_exclusion"));
            assertEquals(0L, db.count("SELECT COUNT(*) FROM os_security_event"));
            id = PasswordExclusions.add(db, actor, body).get("exclusion_id").toString();
            db.connection.commit();
            PasswordExclusions.remove(db, actor, id);
            db.connection.rollback();
            assertEquals(1L, db.count("SELECT COUNT(*) FROM os_password_exclusion"));
            assertEquals(1L, db.count("SELECT COUNT(*) FROM os_security_event"));
            assertThrows(
                    Failure.class, () -> PasswordExclusions.check(db, "migration-test-password"));
            PasswordExclusions.check(db, "MIGRATION-TEST-PASSWORD");
            PasswordExclusions.check(db, "prefix-migration-test-password-suffix");
            assertNotNull(Authentication.verify(db, "admin", "migration-test-password", "basic"));
            Service.createAccount(
                    db,
                    map(
                            "name",
                            "NoPolicyRights",
                            "password",
                            WorkflowSupport.PASSWORD,
                            "role",
                            "reader"));
            db.connection.commit();
            String reader = RecordAccess.owner(db, "NoPolicyRights");
            assertEquals(
                    403,
                    assertThrows(Failure.class, () -> PasswordExclusions.add(db, reader, body))
                            .status);
            assertEquals(
                    403,
                    assertThrows(Failure.class, () -> PasswordExclusions.remove(db, reader, id))
                            .status);
            db.connection.rollback();
        }
        new Store(
                env); // An excluded existing password still authenticates and does not break
                      // restart.
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++)
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    try (var db = store.open()) {
                                        try {
                                            PasswordExclusions.add(
                                                    db,
                                                    actor,
                                                    map(
                                                            "label",
                                                            "Concurrent",
                                                            "password",
                                                            "parallel-excluded-password"));
                                            db.connection.commit();
                                            return 201;
                                        } catch (Failure f) {
                                            db.connection.rollback();
                                            return f.status;
                                        }
                                    }
                                }));
            start.countDown();
            var statuses =
                    List.of(
                            futures.get(0).get(30, TimeUnit.SECONDS),
                            futures.get(1).get(30, TimeUnit.SECONDS));
            assertEquals(1L, statuses.stream().filter(s -> s == 201).count());
            assertEquals(1L, statuses.stream().filter(s -> s == 409).count());
        }
        try (var db = store.open()) {
            assertEquals(2L, db.count("SELECT COUNT(*) FROM os_password_exclusion"));
            assertEquals(
                    2L,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                + " action='policy.password_excluded'"));
            db.update("DELETE FROM os_user WHERE name='admin'");
            db.connection.commit();
        }
        assertEquals(400, assertThrows(Failure.class, () -> new Store(env)).status);
        var restored = new Store(env.withProperty("ADMIN_PASSWORD", "replacement-admin-unique"));
        try (var db = restored.open()) {
            assertTrue(
                    Passwords.matches(
                            db.one("SELECT password FROM os_user WHERE name='admin'")
                                    .get("password")
                                    .toString(),
                            "replacement-admin-unique"));
            assertEquals(2L, db.count("SELECT COUNT(*) FROM os_password_exclusion"));
        }
    }
}
