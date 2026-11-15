package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

class AccessModeTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void accessModeUpgradeRollbackRestartAndConcurrentAdministratorProtection(boolean postgres)
            throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var env = fixture.configuration(postgres);
        var original = new Store(env);
        String actor, reader, other, hash;
        try (var db = original.open()) {
            actor = RecordAccess.owner(db, "admin");
            Service.createAccount(
                    db,
                    map(
                            "name",
                            "ApiReader",
                            "role",
                            "reader",
                            "password",
                            WorkflowSupport.PASSWORD));
            Service.createAccount(
                    db,
                    map(
                            "name",
                            "OtherAdmin",
                            "role",
                            "admin",
                            "password",
                            WorkflowSupport.PASSWORD));
            reader = RecordAccess.owner(db, "ApiReader");
            other = RecordAccess.owner(db, "OtherAdmin");
            db.update("UPDATE os_user SET password_reset_required=1 WHERE user_id=?", reader);
            hash =
                    db.one("SELECT password FROM os_user WHERE user_id=?", reader)
                            .get("password")
                            .toString();
            db.update("ALTER TABLE os_user DROP COLUMN api_only");
            db.update("DELETE FROM os_migration WHERE version>=18");
            db.connection.commit();
        }
        var store = new Store(env);
        try (var db = store.open()) {
            assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
            assertEquals(map("api_only", false), AccessMode.state(db, reader));
            assertEquals(map("required", true), PasswordReset.state(db, reader));
            assertEquals(
                    hash,
                    db.one("SELECT password FROM os_user WHERE user_id=?", reader).get("password"));
            assertEquals(
                    403,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            AccessMode.change(
                                                    db, reader, reader, map("api_only", true)))
                            .status);
            AccessMode.change(db, actor, reader, map("api_only", true));
            db.connection.rollback();
            assertEquals(map("api_only", false), AccessMode.state(db, reader));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_security_event"));
            AccessMode.change(db, actor, reader, map("api_only", true));
            Service.changeActive(db, actor, reader, map("active", false));
            db.connection.commit();
        }
        var restarted = new Store(env);
        try (var db = restarted.open()) {
            assertEquals(map("api_only", true), AccessMode.state(db, reader));
            assertEquals(false, Service.account(db, reader).get("active"));
            Service.changeActive(db, actor, reader, map("active", true));
            db.connection.commit();
            assertEquals(map("api_only", true), AccessMode.state(db, reader));
            assertNull(Authentication.verify(db, "ApiReader", WorkflowSupport.PASSWORD, "browser"));
            assertNotNull(
                    Authentication.verify(db, "ApiReader", WorkflowSupport.PASSWORD, "basic"));
        }
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<Integer>>();
            for (String target : List.of(actor, other))
                results.add(
                        workers.submit(
                                () -> {
                                    start.await();
                                    try (var db = restarted.open()) {
                                        try {
                                            AccessMode.change(
                                                    db, actor, target, map("api_only", true));
                                            db.connection.commit();
                                            return 200;
                                        } catch (Failure failure) {
                                            return failure.status;
                                        }
                                    }
                                }));
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var result : results) statuses.add(result.get(30, TimeUnit.SECONDS));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
        }
        try (var db = restarted.open()) {
            assertEquals(
                    1,
                    db.count(
                            "SELECT COUNT(*) FROM os_user WHERE role='admin' AND active=1 AND"
                                + " api_only=0"));
            String browserAdmin =
                    db.one("SELECT user_id FROM os_user WHERE role='admin' AND api_only=0")
                            .get("user_id")
                            .toString();
            assertEquals(
                    409,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            Service.changeRole(
                                                    db, actor, browserAdmin, map("role", "reader")))
                            .status);
            assertEquals(
                    409,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            Service.changeActive(
                                                    db, actor, browserAdmin, map("active", false)))
                            .status);
        }
    }
}
