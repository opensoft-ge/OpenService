package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

class AdminSessionRevocationTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void auditAndRemovalRollBackTogetherAndRequireAnAdministrator(boolean postgres)
            throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var store = new Store(fixture.configuration(postgres));
        try (var db = store.open()) {
            String actor = RecordAccess.owner(db, "admin");
            Service.createAccount(
                    db,
                    map("name", "Target", "password", WorkflowSupport.PASSWORD, "role", "reader"));
            var user = db.one("SELECT * FROM os_user WHERE name='Target'");
            String target = user.get("user_id").toString();
            BrowserSessions.register(db, user, "target-cookie", System.currentTimeMillis());
            String session =
                    db.one("SELECT session_id FROM os_browser_session")
                            .get("session_id")
                            .toString();
            db.connection.commit();
            assertEquals(
                    403,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            AdminSessions.revoke(
                                                    db,
                                                    target,
                                                    target,
                                                    session,
                                                    "",
                                                    map("reason", "self")))
                            .status);
            assertTrue(
                    AdminSessions.revoke(
                            db,
                            actor,
                            target,
                            session,
                            "target-cookie",
                            map("reason", "Support request")));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_browser_session"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_security_event"));
            db.connection.rollback();
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_browser_session"));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_security_event"));
        }
    }
}
