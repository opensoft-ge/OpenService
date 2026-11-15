package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

class AdminAllSessionsTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void removalAndAuditAreAtomicWithoutChangingAccountSecurity(boolean postgres) throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var env = fixture.configuration(postgres);
        var store = new Store(env);
        String target, admin;
        try (var db = store.open()) {
            admin = RecordAccess.owner(db, "admin");
            Service.createAccount(
                    db,
                    map("name", "Target", "password", WorkflowSupport.PASSWORD, "role", "reader"));
            var user = db.one("SELECT * FROM os_user WHERE name='Target'");
            target = user.get("user_id").toString();
            BrowserSessions.register(db, user, "first", System.currentTimeMillis());
            BrowserSessions.register(db, user, "second", System.currentTimeMillis());
            db.update(
                    "UPDATE os_browser_session SET auth_revision=999 WHERE session_hash=?",
                    Passwords.tokenHash("second"));
            db.connection.commit();
            assertEquals(
                    403,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            AdminSessions.revokeAll(
                                                    db,
                                                    target,
                                                    target,
                                                    "first",
                                                    map("reason", "test")))
                            .status);
            assertEquals(
                    map("removed_count", 2, "current", true),
                    AdminSessions.revokeAll(db, admin, target, "first", map("reason", "test")));
            assertEquals(user, db.one("SELECT * FROM os_user WHERE user_id=?", target));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_browser_session"));
            db.connection.rollback();
            assertEquals(2, db.count("SELECT COUNT(*) FROM os_browser_session"));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_security_event"));
            AdminSessions.revokeAll(db, admin, target, "first", map("reason", "test"));
            db.connection.commit();
        }
        try (var db = new Store(env).open()) {
            assertEquals(
                    map("removed_count", 0, "current", false),
                    AdminSessions.revokeAll(db, admin, target, "first", map("reason", "repeat")));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_security_event"));
        }
    }
}
