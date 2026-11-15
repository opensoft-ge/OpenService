package ge.opensoft.openservice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.List;

class SessionLimitTest {
    @TempDir Path root;

    @Test
    void configurationIsBoundedAndFailsAtStartup() throws Exception {
        assertEquals(10, SessionLimit.read(new MockEnvironment()));
        for (String raw : List.of("1", "100"))
            assertEquals(
                    Integer.parseInt(raw),
                    SessionLimit.read(
                            new MockEnvironment().withProperty("MAX_BROWSER_SESSIONS", raw)));
        for (String raw :
                List.of("", " ", "0", "101", "999", "1000", "01", "-1", "+2", "1.5", "ten")) {
            var invalid = new MockEnvironment().withProperty("MAX_BROWSER_SESSIONS", raw);
            assertTrue(
                    assertThrows(IllegalArgumentException.class, () -> new Store(invalid))
                            .getMessage()
                            .contains("MAX_BROWSER_SESSIONS"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void boundariesKeepTheCurrentCookieAndRollBackAuditWithEviction(boolean postgres)
            throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var store =
                new Store(
                        fixture.configuration(postgres).withProperty("MAX_BROWSER_SESSIONS", "1"));
        assertEquals(1, store.maxBrowserSessions);
        try (var db = store.open()) {
            var user = db.one("SELECT * FROM os_user WHERE name='admin'");
            long issued = System.currentTimeMillis();
            BrowserSessions.register(db, user, "current", issued);
            BrowserSessions.register(db, user, "other", issued);
            db.connection.commit();
            SessionLimit.enforce(db, user, "current", 1);
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_browser_session"));
            assertEquals(
                    Passwords.tokenHash("current"),
                    db.one("SELECT session_hash FROM os_browser_session").get("session_hash"));
            assertEquals(
                    1,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                + " action='account.sessions_limited'"));
            db.connection.rollback();
            assertEquals(2, db.count("SELECT COUNT(*) FROM os_browser_session"));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_security_event"));
            SessionLimit.enforce(db, user, "current", 1);
            for (int i = 0; i < 99; i++) BrowserSessions.register(db, user, "extra-" + i, issued);
            SessionLimit.enforce(db, user, "current", 100);
            assertEquals(100, db.count("SELECT COUNT(*) FROM os_browser_session"));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_security_event"));
            BrowserSessions.register(db, user, "overflow", issued);
            SessionLimit.enforce(db, user, "current", 100);
            assertEquals(100, db.count("SELECT COUNT(*) FROM os_browser_session"));
            assertEquals(
                    1,
                    db.count(
                            "SELECT COUNT(*) FROM os_browser_session WHERE session_hash=?",
                            Passwords.tokenHash("current")));
            assertEquals(2, db.count("SELECT COUNT(*) FROM os_security_event"));
            db.connection.commit();
        }
    }
}
