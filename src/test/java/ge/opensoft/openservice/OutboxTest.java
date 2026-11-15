package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

class OutboxTest {
    @TempDir Path root;

    Store store(boolean postgres) throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        return new Store(fixture.configuration(postgres));
    }

    static MockEnvironment mail(MailSink sink) {
        return new MockEnvironment()
                .withProperty("DELIVERY_ENABLED", "true")
                .withProperty("PUBLIC_BASE_URL", "https://example.invalid")
                .withProperty("MAIL_HOST", "localhost")
                .withProperty("MAIL_PORT", Integer.toString(sink.server.getLocalPort()))
                .withProperty("MAIL_FROM", "accounts@example.invalid")
                .withProperty("MAIL_STARTTLS", "false")
                .withProperty("MAIL_ALLOW_INSECURE_LOCAL", "true");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transactionsLeasesRetriesAndRestartKeepDeliveryDurable(boolean postgres) throws Exception {
        var store = store(postgres);
        long now = System.currentTimeMillis();
        String id;
        try (var db = store.open()) {
            Outbox.enqueue(
                    db, store.secret, "test", "rollback", map("path", "/test"), now + 600_000);
            db.connection.rollback();
            assertTrue(Outbox.list(db).isEmpty());
            id =
                    Outbox.enqueue(
                            db,
                            store.secret,
                            "test",
                            "stable",
                            map("path", "/test"),
                            now + 600_000);
            assertEquals(
                    id,
                    Outbox.enqueue(
                            db,
                            store.secret,
                            "test",
                            "stable",
                            map("path", "/test"),
                            now + 600_000));
            db.connection.commit();
        }
        Map<String, Object> old;
        try (var db = store.open()) {
            old = Outbox.claim(db, now + 1000);
            assertNotNull(old);
            assertFalse(old.get("envelope").toString().contains("/test"));
            assertEquals(
                    map("path", "/test"),
                    Outbox.decrypt(store.secret, old.get("envelope").toString()));
            assertNull(Outbox.claim(db, now + 1001));
            db.connection.commit();
        }
        try (var db = store.open()) {
            var recovered = Outbox.claim(db, now + 61_001);
            assertNotNull(recovered);
            assertEquals(2, recovered.get("attempts"));
            Outbox.finish(db, old, "delivered", "", now);
            assertEquals(
                    "processing",
                    db.one("SELECT state FROM os_outbox WHERE job_id=?", id).get("state"));
            Outbox.finish(db, recovered, "queued", "DELIVERY_FAILED", now);
            for (int attempt = 3; attempt <= 5; attempt++) {
                var job = Outbox.claim(db, now + attempt * 20_000);
                assertNotNull(job);
                Outbox.finish(db, job, "queued", "DELIVERY_FAILED", now);
            }
            assertEquals("failed", Outbox.list(db).getFirst().get("state"));
            String admin = RecordAccess.owner(db, "admin");
            assertThrows(Failure.class, () -> Outbox.retry(db, "missing", id));
            assertThrows(Failure.class, () -> Outbox.retry(db, admin, "missing"));
            Outbox.retry(db, admin, id);
            var job = Outbox.claim(db, now + 100_000);
            Outbox.finish(db, job, "delivered", "", now);
            assertEquals(
                    "",
                    db.one("SELECT envelope FROM os_outbox WHERE job_id=?", id).get("envelope"));
            Outbox.enqueue(db, store.secret, "test", "expired", map(), now - 1);
            assertNull(Outbox.claim(db, now + 100_000));
            assertEquals(
                    1,
                    db.count(
                            "SELECT COUNT(*) FROM os_outbox WHERE state='cancelled' AND"
                                + " envelope=''"));
            assertFalse(JSON.writeValueAsString(Outbox.list(db)).contains("envelope"));
            db.connection.commit();
        }
        new DeliveryWorker(store, (DeliveryWorker.Sender) null).tick();
        new DeliveryWorker(store, new MockEnvironment()).tick();
        assertFalse(new DeliveryWorker(store, (a, b) -> fail()).runOne(now + 100_000));
    }

    @Test
    void encryptionDetectsWrongKeysTamperingAndMalformedPayloads() throws Exception {
        String envelope = Outbox.encrypt("secret", "{}");
        assertEquals(map(), Outbox.decrypt("secret", envelope));
        assertThrows(Exception.class, () -> Outbox.decrypt("other", envelope));
        assertThrows(Exception.class, () -> Outbox.decrypt("secret", "malformed"));
        assertThrows(Exception.class, () -> Outbox.decrypt("secret", "!.!"));
        assertThrows(
                Exception.class,
                () -> Outbox.decrypt("secret", Outbox.encrypt("secret", "bad json")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentWorkersAndRepeatedCrashesHaveBoundedClaims(boolean postgres) throws Exception {
        var store = store(postgres);
        long now = System.currentTimeMillis();
        String job;
        try (var db = store.open()) {
            job = Outbox.enqueue(db, store.secret, "test", "concurrent", map(), now + 3_600_000);
            db.connection.commit();
        }
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++)
                results.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    try (var db = store.open()) {
                                        boolean claimed = Outbox.claim(db, now + 1000) != null;
                                        db.connection.commit();
                                        return claimed;
                                    }
                                }));
            start.countDown();
            assertNotEquals(
                    results.get(0).get(20, TimeUnit.SECONDS),
                    results.get(1).get(20, TimeUnit.SECONDS));
        }
        // Reopen the actual store each time; simulate a crash after committing a lease.
        for (int attempt = 2; attempt <= 5; attempt++) {
            try (var db = new Store(store.environment).open()) {
                assertEquals(attempt, Outbox.claim(db, now + attempt * 61_000).get("attempts"));
                db.connection.commit();
            }
        }
        try (var db = store.open()) {
            assertNull(Outbox.claim(db, now + 6 * 61_000));
            var failed = db.one("SELECT state,error FROM os_outbox WHERE job_id=?", job);
            assertEquals(map("state", "failed", "error", "LEASE_EXHAUSTED"), failed);
            Outbox.retry(db, RecordAccess.owner(db, "admin"), job);
            assertEquals(1, Outbox.claim(db, now + 7 * 61_000).get("attempts"));
        }
    }

    @Test
    void smtpUsesStableMessageIdsAndRejectsInsecureNonlocalConfiguration() throws Exception {
        try (var sink = new MailSink()) {
            var env = mail(sink);
            var sender = new SmtpDelivery(env);
            sender.send("stable", map("email", "recipient@example.invalid", "path", "/join/test"));
            String message = sink.receive();
            assertTrue(message.contains("Message-ID: <stable@openservice.invalid>"));
            assertTrue(message.contains("https://example.invalid/join/test"));
            env.withProperty("MAIL_STARTTLS", "true")
                    .withProperty("MAIL_USERNAME", "test")
                    .withProperty("MAIL_PASSWORD", "test");
            assertDoesNotThrow(() -> new SmtpDelivery(env));
            env.withProperty("MAIL_STARTTLS", "false").withProperty("MAIL_HOST", "example.invalid");
            assertThrows(IllegalArgumentException.class, () -> new SmtpDelivery(env));
            env.withProperty("MAIL_HOST", "localhost")
                    .withProperty("MAIL_ALLOW_INSECURE_LOCAL", "false");
            assertThrows(IllegalArgumentException.class, () -> new SmtpDelivery(env));
            for (String url :
                    List.of(
                            "https://example.invalid/",
                            "http://localhost:8080",
                            "http://127.0.0.1",
                            "http://[::1]:80"))
                assertNotNull(
                        SmtpDelivery.base(
                                env.withProperty("PUBLIC_BASE_URL", url)
                                        .withProperty("MAIL_ALLOW_INSECURE_LOCAL", "true")));
            for (String url :
                    List.of(
                            "",
                            "http://example.invalid",
                            "ftp://localhost",
                            "https://user@example.invalid",
                            "https://example.invalid/x",
                            "https://example.invalid?q=x",
                            "https://example.invalid#x",
                            "https:/invalid"))
                assertThrows(
                        IllegalArgumentException.class,
                        () -> SmtpDelivery.base(env.withProperty("PUBLIC_BASE_URL", url)));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            SmtpDelivery.base(
                                    env.withProperty("PUBLIC_BASE_URL", "http://localhost")
                                            .withProperty("MAIL_ALLOW_INSECURE_LOCAL", "false")));
        }
    }
}
