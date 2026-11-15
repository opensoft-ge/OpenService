package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

class BasicAccessTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void migrationRollbackAndConcurrentChannelChangesRemainSafe(boolean postgres) throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var env = fixture.configuration(postgres);
        var store = new Store(env);
        String admin, target;
        try (var db = store.open()) {
            admin = RecordAccess.owner(db, "admin");
            Service.createAccount(
                    db,
                    map(
                            "name",
                            "PolicyTarget",
                            "password",
                            WorkflowSupport.PASSWORD,
                            "role",
                            "reader"));
            target = RecordAccess.owner(db, "PolicyTarget");
            db.update("ALTER TABLE os_user DROP COLUMN allow_basic");
            db.update("DELETE FROM os_migration WHERE version>=22");
            db.connection.commit();
            Migrations.apply(db);
            assertEquals(map("allow_basic", true), BasicAccess.state(db, target));
            db.connection.rollback();
            assertEquals(21, db.count("SELECT COUNT(*) FROM os_migration"));
        }
        store = new Store(env);
        try (var db = store.open()) {
            assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
            var before = db.one("SELECT * FROM os_user WHERE user_id=?", target);
            assertEquals(
                    403,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            BasicAccess.change(
                                                    db, target, target, map("allow_basic", false)))
                            .status);
            BasicAccess.change(db, admin, target, map("allow_basic", false));
            assertEquals(
                    401,
                    assertThrows(Failure.class, () -> Authentication.requireCurrent(db, before))
                            .status);
            db.connection.rollback();
            assertEquals(map("allow_basic", true), BasicAccess.state(db, target));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_security_event"));
            Authentication.requireCurrent(db, before);
            db.connection.commit();
        }
        var restarted = new Store(env);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Future<Integer>>();
            for (boolean disableBasic : List.of(false, true)) {
                tasks.add(
                        workers.submit(
                                () -> {
                                    start.await();
                                    try (var db = restarted.open()) {
                                        try {
                                            if (disableBasic)
                                                BasicAccess.change(
                                                        db,
                                                        admin,
                                                        target,
                                                        map("allow_basic", false));
                                            else
                                                AccessMode.change(
                                                        db, admin, target, map("api_only", true));
                                            db.connection.commit();
                                            return 200;
                                        } catch (Failure failure) {
                                            return failure.status;
                                        }
                                    }
                                }));
            }
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var result : tasks) statuses.add(result.get(30, TimeUnit.SECONDS));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
        }
        try (var db = restarted.open()) {
            assertEquals(
                    0, db.count("SELECT COUNT(*) FROM os_user WHERE allow_basic=0 AND api_only=1"));
            // Disabling Basic for the sole browser administrator still permits form login.
            BasicAccess.change(db, admin, admin, map("allow_basic", false));
            db.connection.commit();
            assertNotNull(Authentication.verify(db, "admin", env.getProperty("ADMIN_PASSWORD"), "browser"));
            assertNull(Authentication.verify(db, "admin", env.getProperty("ADMIN_PASSWORD"), "basic"));
        }
    }
}
