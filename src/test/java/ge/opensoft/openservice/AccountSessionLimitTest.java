package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.*;

class AccountSessionLimitTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void migrationDefaultsRollbackAndPersistentPolicies(boolean postgres) throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var env = fixture.configuration(postgres);
        var store = new Store(env);
        String target, admin;
        try (var db = store.open()) {
            admin = RecordAccess.owner(db, "admin");
            Service.createAccount(
                    db,
                    map(
                            "name",
                            "LimitTarget",
                            "password",
                            WorkflowSupport.PASSWORD,
                            "role",
                            "reader"));
            target = RecordAccess.owner(db, "LimitTarget");
            db.update("ALTER TABLE os_user DROP COLUMN browser_limit");
            db.update("DELETE FROM os_migration WHERE version>=23");
            db.connection.commit();
            Migrations.apply(db);
            assertEquals(
                    map("maximum", 0, "effective_maximum", 17),
                    AccountSessionLimit.state(db, target, 17));
            db.connection.rollback();
            assertEquals(22, db.count("SELECT COUNT(*) FROM os_migration"));
        }
        try (var db = new Store(env).open()) {
            assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
            var before = db.one("SELECT * FROM os_user WHERE user_id=?", target);
            assertEquals(
                    403,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            AccountSessionLimit.change(
                                                    db, target, target, map("maximum", 1), 10))
                            .status);
            for (Object value : Arrays.asList(null, true, "1", 1.5, -1, 101, Long.MAX_VALUE))
                assertEquals(
                        400,
                        assertThrows(
                                        Failure.class,
                                        () ->
                                                AccountSessionLimit.change(
                                                        db,
                                                        admin,
                                                        target,
                                                        map("maximum", value),
                                                        10))
                                .status);
            for (Object body : List.of(map(), map("maximum", 1, "extra", 1), List.of()))
                assertEquals(
                        400,
                        assertThrows(
                                        Failure.class,
                                        () ->
                                                AccountSessionLimit.change(
                                                        db, admin, target, body, 10))
                                .status);
            assertEquals(
                    404,
                    assertThrows(Failure.class, () -> AccountSessionLimit.state(db, id(), 10))
                            .status);
            assertEquals(
                    map("maximum", 1, "effective_maximum", 1),
                    AccountSessionLimit.change(db, admin, target, map("maximum", 1), 10));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_security_event"));
            db.connection.rollback();
            assertEquals(before, db.one("SELECT * FROM os_user WHERE user_id=?", target));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_security_event"));
            AccountSessionLimit.change(db, admin, target, map("maximum", 100), 10);
            AccountSessionLimit.change(db, admin, target, map("maximum", 100), 10);
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_security_event"));
            assertEquals(
                    before.get("auth_revision"),
                    db.one("SELECT auth_revision FROM os_user WHERE user_id=?", target)
                            .get("auth_revision"));
            db.connection.commit();
        }
        try (var db = new Store(env).open()) {
            assertEquals(100, AccountSessionLimit.effective(db, target, 17));
            AccountSessionLimit.change(db, admin, target, map("maximum", 0), 17);
            assertEquals(17, AccountSessionLimit.effective(db, target, 17));
            assertEquals(10, AccountSessionLimit.effective(db, admin, 10));
            for (String input : List.of("", " 1", "01", "-1", "101", "1.0", "1000"))
                assertEquals(
                        400,
                        assertThrows(Failure.class, () -> AccountSessionLimit.form(input)).status);
            for (int value : new int[] {0, 1, 10, 100})
                assertEquals(value, AccountSessionLimit.form("" + value));
            db.connection.commit();
            assertThrows(
                    java.sql.SQLException.class,
                    () ->
                            db.update(
                                    "UPDATE os_user SET browser_limit=101 WHERE user_id=?",
                                    target));
            db.connection.rollback();
        }
    }
}
