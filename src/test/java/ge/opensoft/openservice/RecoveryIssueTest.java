package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

class RecoveryIssueTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rechecksCallerAndDoesNotCommitBusinessMutations(boolean postgres) throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var store = new Store(fixture.configuration(postgres));
        try (var db = store.open()) {
            var caller = db.one("SELECT * FROM os_user WHERE name='admin'");
            String actor = caller.get("user_id").toString();
            var body = map("current_password", "migration-test-password");
            caller.put("auth_revision", -1L);
            assertEquals(
                    401,
                    assertThrows(Failure.class, () -> RecoveryLinks.issue(db, caller, actor, body))
                            .status);
            db.connection.rollback();
            assertEquals(0L, db.count("SELECT COUNT(*) FROM os_recovery"));
            caller.put("auth_revision", 0L);
            var first = RecoveryLinks.issue(db, caller, actor, body);
            db.connection.commit();
            String token = first.get("reset_path").toString().substring(9);
            RecoveryLinks.issue(db, caller, actor, body);
            db.connection.rollback();
            assertEquals("admin", RecoveryLinks.describe(db, token).get("name"));
            assertEquals(
                    1L,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                + " action='account.recovery_issued'"));
            assertEquals(
                    3L,
                    db.count(
                            "SELECT COUNT(*) FROM os_auth_event WHERE mechanism='recovery-issue'"
                                + " AND outcome='succeeded'"));
            db.update("UPDATE os_user SET active=0 WHERE user_id=?", actor);
            db.connection.commit();
            assertEquals(
                    403,
                    assertThrows(Failure.class, () -> RecoveryLinks.issue(db, caller, actor, body))
                            .status);
            db.connection.rollback();
            db.update("UPDATE os_user SET active=1,role='reader' WHERE user_id=?", actor);
            db.connection.commit();
            assertEquals(
                    403,
                    assertThrows(Failure.class, () -> RecoveryLinks.issue(db, caller, actor, body))
                            .status);
        }
    }
}
