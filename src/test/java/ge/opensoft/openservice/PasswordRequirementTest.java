package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

class PasswordRequirementTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void requiredReplacementSurvivesUpgradeRestartAndSerializesAuditedChanges(boolean postgres)
            throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var env = fixture.configuration(postgres);
        var store = new Store(env);
        String admin, target, hash;
        try (var db = store.open()) {
            admin = RecordAccess.owner(db, "admin");
            Service.createAccount(
                    db,
                    map(
                            "name",
                            "Required",
                            "role",
                            "reader",
                            "password",
                            WorkflowSupport.PASSWORD));
            target = RecordAccess.owner(db, "Required");
            hash =
                    db.one("SELECT password FROM os_user WHERE user_id=?", target)
                            .get("password")
                            .toString();
            db.update("ALTER TABLE os_user DROP COLUMN password_reset_required");
            db.update("DELETE FROM os_migration WHERE version>=17");
            db.connection.commit();
        }
        var upgraded = new Store(env);
        try (var db = upgraded.open()) {
            assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
            assertEquals(map("required", false), PasswordReset.state(db, target));
            assertEquals(
                    hash,
                    db.one("SELECT password FROM os_user WHERE user_id=?", target).get("password"));
            assertEquals(
                    403,
                    assertThrows(Failure.class, () -> PasswordReset.require(db, target, target))
                            .status);
            PasswordReset.require(db, admin, target);
            db.connection.rollback();
            assertEquals(map("required", false), PasswordReset.state(db, target));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_security_event"));
        }
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<Map<String, Object>>>();
            for (int i = 0; i < 2; i++)
                results.add(
                        workers.submit(
                                () -> {
                                    start.await();
                                    try (var db = upgraded.open()) {
                                        var result = PasswordReset.require(db, admin, target);
                                        db.connection.commit();
                                        return result;
                                    }
                                }));
            start.countDown();
            for (var result : results)
                assertEquals(map("required", true), result.get(30, TimeUnit.SECONDS));
        }
        var reopened = new Store(env);
        try (var db = reopened.open()) {
            assertEquals(map("required", true), PasswordReset.state(db, target));
            assertEquals(
                    1,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                + " action='account.password_change_required'"));
            assertEquals(
                    1,
                    ((Number)
                                    db.one(
                                                    "SELECT auth_revision FROM os_user WHERE"
                                                        + " user_id=?",
                                                    target)
                                            .get("auth_revision"))
                            .intValue());
            var caller = db.one("SELECT * FROM os_user WHERE user_id=?", target);
            Service.changePassword(
                    db,
                    caller,
                    map(
                            "current_password",
                            WorkflowSupport.PASSWORD,
                            "new_password",
                            "changed-required-password"));
            db.connection.rollback();
            assertEquals(map("required", true), PasswordReset.state(db, target));
            assertEquals(
                    hash,
                    db.one("SELECT password FROM os_user WHERE user_id=?", target).get("password"));
            Service.changePassword(
                    db,
                    caller,
                    map(
                            "current_password",
                            WorkflowSupport.PASSWORD,
                            "new_password",
                            "changed-required-password"));
            db.connection.commit();
            assertEquals(map("required", false), PasswordReset.state(db, target));
            Service.changeActive(db, admin, target, map("active", false));
            PasswordReset.require(db, admin, target);
            db.connection.commit();
            assertEquals(map("required", true), PasswordReset.state(db, target));
            assertNull(Authentication.verify(db, "Required", "changed-required-password", "basic"));
            Service.changeActive(db, admin, target, map("active", true));
            db.connection.commit();
            assertEquals(map("required", true), PasswordReset.state(db, target));
        }
    }
}
