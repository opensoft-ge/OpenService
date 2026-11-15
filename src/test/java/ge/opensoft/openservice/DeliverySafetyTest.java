package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

class DeliverySafetyTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleLinksRevokedAccountsAndFailedDeliveryStayPrivate(boolean postgres) throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var env =
                fixture.configuration(postgres)
                        .withProperty("DELIVERY_ENABLED", "true")
                        .withProperty("PUBLIC_BASE_URL", "https://example.invalid");
        var store = new Store(env);
        String token, otherToken, admin, user;
        try (var db = store.open()) {
            admin = RecordAccess.owner(db, "admin");
            for (String name : List.of("First", "Second")) {
                var result =
                        EmailOnboarding.invite(
                                store,
                                db,
                                admin,
                                map(
                                        "name",
                                        name,
                                        "role",
                                        "reader",
                                        "email",
                                        "same@example.invalid"));
                assertFalse(result.containsKey("accept_path"));
            }
            var jobs = db.all("SELECT * FROM os_outbox ORDER BY created_at,job_id");
            var first = Outbox.decrypt(store.secret, jobs.getFirst().get("envelope").toString());
            var second = Outbox.decrypt(store.secret, jobs.getLast().get("envelope").toString());
            token = first.get("path").toString().substring(6);
            otherToken = second.get("path").toString().substring(6);
            assertFalse(
                    EmailOnboarding.valid(
                            db,
                            "email.invitation",
                            map("path", first.get("path"), "email", "wrong@example.invalid")));
            Invitations.accept(db, token, WorkflowSupport.PASSWORD);
            assertThrows(
                    Failure.class,
                    () -> Invitations.accept(db, otherToken, WorkflowSupport.PASSWORD));
            user =
                    db.one("SELECT user_id FROM os_user WHERE email='same@example.invalid'")
                            .get("user_id")
                            .toString();
            assertFalse(EmailOnboarding.valid(db, "unsupported", first));
            assertFalse(EmailOnboarding.valid(db, "email.invitation", first));
            db.connection.commit();
        }
        var worker = new DeliveryWorker(store, (id, value) -> fail("Stale link must not be sent"));
        assertTrue(worker.runOne(System.currentTimeMillis() + 100));
        // Revoke the second unused invitation before a restarted worker sees it.
        try (var db = store.open()) {
            db.update(
                    "UPDATE os_invitation SET state='revoked' WHERE token_hash=?",
                    Passwords.tokenHash(otherToken));
            db.connection.commit();
        }
        assertTrue(worker.runOne(System.currentTimeMillis() + 100));
        try (var db = store.open()) {
            var account = db.one("SELECT * FROM os_user WHERE user_id=?", user);
            var recovery = RecoveryLinks.email(db, account);
            String path = recovery.get("reset_path").toString();
            assertTrue(
                    EmailOnboarding.valid(
                            db,
                            "email.recovery",
                            map("path", path, "email", "same@example.invalid")));
            assertFalse(
                    EmailOnboarding.valid(
                            db,
                            "email.recovery",
                            map("path", path, "email", "wrong@example.invalid")));
            db.update("UPDATE os_user SET email_verified=0 WHERE user_id=?", user);
            assertFalse(
                    EmailOnboarding.valid(
                            db,
                            "email.recovery",
                            map("path", path, "email", "same@example.invalid")));
            db.update("UPDATE os_user SET email_verified=1 WHERE user_id=?", user);
            // Explicit expiry and account changes invalidate queued recovery.
            db.update("UPDATE os_recovery SET expires_at=0 WHERE user_id=?", user);
            assertFalse(
                    EmailOnboarding.valid(
                            db,
                            "email.recovery",
                            map("path", path, "email", "same@example.invalid")));
            Outbox.enqueue(
                    db,
                    store.secret,
                    "unknown",
                    "bad",
                    map("path", "bad"),
                    System.currentTimeMillis() + 60_000);
            db.update("UPDATE os_outbox SET envelope='broken' WHERE reference_id='bad'");
            db.connection.commit();
        }
        assertTrue(worker.runOne(System.currentTimeMillis() + 100));
        try (var db = store.open()) {
            assertEquals(
                    "DELIVERY_FAILED",
                    db.one("SELECT error FROM os_outbox WHERE reference_id='bad'").get("error"));
            db.update(
                    "UPDATE os_outbox SET available_at=0,envelope=? WHERE reference_id='bad'",
                    Outbox.encrypt(store.secret, JSON.writeValueAsString(map("path", "bad"))));
            db.connection.commit();
        }
        worker.tick();
        var broken = mock(Store.class);
        when(broken.open()).thenThrow(new SQLException("sensitive detail must not be logged"));
        assertDoesNotThrow(() -> new DeliveryWorker(broken, (a, b) -> {}).tick());
    }

    @Test
    void queuesAndRecoveryRequestsAreBoundedWithoutAccountEnumeration() throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var env = fixture.configuration(false);
        var store = new Store(env);
        for (Object invalid :
                Arrays.asList(null, 2, "", "a".repeat(255), "x@y", "x\n@example.invalid"))
            assertThrows(Failure.class, () -> EmailOnboarding.email(invalid));
        try (var db = store.open()) {
            EmailOnboarding.forgot(store, db, "unknown@example.invalid", "127.0.0.1");
            env.withProperty("DELIVERY_ENABLED", "true")
                    .withProperty("PUBLIC_BASE_URL", "https://example.invalid");
            EmailOnboarding.forgot(store, db, "unknown@example.invalid", "127.0.0.1");
            for (int i = 0; i < 10; i++)
                EmailOnboarding.forgot(store, db, "unknown" + i + "@example.invalid", "127.0.0.2");
            EmailOnboarding.forgot(store, db, "blocked@example.invalid", "127.0.0.2");
            assertEquals(11, db.count("SELECT COUNT(*) FROM os_recovery_budget"));
            for (int i = 11; i < 1000; i++)
                db.update(
                        "INSERT INTO os_recovery_budget VALUES(?,?,?,?)",
                        id(),
                        id(),
                        id(),
                        System.currentTimeMillis());
            EmailOnboarding.forgot(store, db, "global@example.invalid", "127.0.0.3");
            assertEquals(1000, db.count("SELECT COUNT(*) FROM os_recovery_budget"));
            db.update("UPDATE os_recovery_budget SET requested_at=0");
            for (int i = 0; i < 1000; i++)
                db.update(
                        "INSERT INTO os_outbox VALUES(?,?,?,?,'queued',0,0,?,?,'','')",
                        id(),
                        "email.invitation",
                        id(),
                        "ciphertext",
                        Long.MAX_VALUE,
                        System.currentTimeMillis());
            assertThrows(
                    Failure.class,
                    () ->
                            Outbox.enqueue(
                                    db,
                                    store.secret,
                                    "email.invitation",
                                    "overflow",
                                    map(),
                                    Long.MAX_VALUE));
            assertThrows(
                    Failure.class,
                    () ->
                            EmailOnboarding.invite(
                                    store,
                                    db,
                                    RecordAccess.owner(db, "admin"),
                                    map(
                                            "name",
                                            "Limit",
                                            "role",
                                            "reader",
                                            "email",
                                            "limit@example.invalid")));
            EmailOnboarding.forgot(store, db, "global@example.invalid", "127.0.0.3");
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_recovery_budget"));
        }
    }
}
