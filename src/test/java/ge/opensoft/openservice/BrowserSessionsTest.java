package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class BrowserSessionsTest {
    @TempDir Path root;

    @Test
    void expiryIdentityAndClockBoundsFailClosed() throws Exception {
        var store = new Store(new MockEnvironment()
                .withProperty("DATABASE_URL", "sqlite:///" + root.resolve("sessions.db"))
                .withProperty("SECRET_KEY", "session-boundary-test-secret")
                .withProperty("ADMIN_PASSWORD", WorkflowSupport.PASSWORD));
        try (var db = store.open()) {
            var user = db.one("SELECT * FROM os_user WHERE name='admin'");
            long issued = 100000000L;
            BrowserSessions.register(db, user, "first", issued);
            db.connection.commit();
            assertTrue(BrowserSessions.touch(db, user, "first", issued));
            assertFalse(BrowserSessions.touch(db, user, "missing", issued));
            assertFalse(BrowserSessions.touch(db, map("user_id", "other", "auth_revision", 0), "first", issued));
            assertFalse(BrowserSessions.touch(db, map("user_id", user.get("user_id"), "auth_revision", 1), "first", issued));
            assertFalse(BrowserSessions.touch(db, user, "first", issued - 1));
            assertTrue(objects(BrowserSessions.mine(db, user, "first", issued - 1).get("result")).isEmpty());
            assertTrue(BrowserSessions.touch(db, user, "first", issued + BrowserSessions.IDLE_MILLIS - 1));
            assertTrue(BrowserSessions.touch(db, user, "first", issued + BrowserSessions.IDLE_MILLIS - 2), "An earlier request arriving second must not revoke the session");
            assertEquals(issued + BrowserSessions.IDLE_MILLIS - 1,
                    ((Number) db.one("SELECT last_seen FROM os_browser_session").get("last_seen")).longValue(), "Activity never moves backwards");
            assertFalse(BrowserSessions.touch(db, user, "first", issued + 2 * BrowserSessions.IDLE_MILLIS - 1));
            assertTrue(objects(BrowserSessions.mine(db, user, "first", issued + 2 * BrowserSessions.IDLE_MILLIS - 1).get("result")).isEmpty());
            db.update("UPDATE os_browser_session SET last_seen=?", issued + SessionLifetime.MAX_AGE_MILLIS - 1);
            db.connection.commit();
            assertTrue(BrowserSessions.touch(db, user, "first", issued + SessionLifetime.MAX_AGE_MILLIS - 1));
            assertFalse(BrowserSessions.touch(db, user, "first", issued + SessionLifetime.MAX_AGE_MILLIS));
            assertTrue(objects(BrowserSessions.mine(db, user, "first", issued + SessionLifetime.MAX_AGE_MILLIS).get("result")).isEmpty());
            BrowserSessions.remove(db, "first");
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_browser_session"));
            BrowserSessions.register(db, user, "rollback", issued);
            db.connection.commit();
            String selected = db.one("SELECT session_id FROM os_browser_session").get("session_id").toString();
            assertTrue(BrowserSessions.revoke(db, user, selected, "rollback"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_security_event WHERE action='account.session_revoked'"));
            db.connection.rollback();
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_browser_session"));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_security_event WHERE action='account.session_revoked'"));
        }
    }
}
