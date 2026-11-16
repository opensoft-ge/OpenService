package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.*;
import java.util.*;

class PlatformFoundationTest {
    @TempDir Path root;

    Store store(boolean postgres) throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        return new Store(
                fixture.configuration(postgres)
                        .withProperty("DELIVERY_ENABLED", "true")
                        .withProperty("PUBLIC_BASE_URL", "https://example.invalid"));
    }

    static String bind(Store.Db db) throws Exception {
        db.viewerId = RecordAccess.owner(db, "admin");
        db.viewerRole = "admin";
        return db.viewerId;
    }

    static String record(Store.Db db, String table, String title) throws Exception {
        return Service.create(db, table, map("short_description", title), "admin")
                .get("sys_id")
                .toString();
    }

    static void bad(org.junit.jupiter.api.function.Executable call) {
        assertThrows(Failure.class, call);
    }

    static Map<String, Object> calendar() {
        return map(
                "zone",
                "Europe/London",
                "start",
                "09:00",
                "end",
                "17:00",
                "days",
                List.of(1, 2, 3, 4, 5),
                "holidays",
                List.of("2026-11-16"));
    }

    static long at(String instant) {
        return Instant.parse(instant).toEpochMilli();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void registeredEmailRequiresBothProofsAndPreservesOldAddressUntilCompletion(boolean postgres)
            throws Exception {
        var store = store(postgres);
        try (var db = store.open()) {
            String actor = bind(db);
            db.update(
                    "UPDATE os_user SET email='old@example.invalid',email_verified=1 WHERE"
                            + " user_id=?",
                    actor);
            db.connection.commit();
            for (boolean oldFirst : List.of(false, true)) {
                var caller = db.one("SELECT * FROM os_user WHERE user_id=?", actor);
                String previous = caller.get("email").toString();
                String next = oldFirst ? "third@example.invalid" : "second@example.invalid";
                EmailEnrollment.request(
                        store,
                        db,
                        caller,
                        map("email", next, "current_password", "migration-test-password"));
                var messages =
                        db.all(
                                "SELECT envelope FROM os_outbox WHERE state='queued' ORDER BY"
                                        + " created_at,job_id");
                String oldToken = "", newToken = "";
                for (var message : messages) {
                    var payload = Outbox.decrypt(store.secret, message.get("envelope").toString());
                    String token = payload.get("path").toString().substring(14);
                    assertTrue(EmailOnboarding.valid(db, "email.enrollment", payload));
                    if (payload.get("email").equals(previous)) oldToken = token;
                    else newToken = token;
                }
                assertFalse(oldToken.isEmpty());
                assertFalse(newToken.isEmpty());
                db.connection.commit();
                String first = oldFirst ? oldToken : newToken,
                        second = oldFirst ? newToken : oldToken;
                EmailEnrollment.confirm(db, first);
                assertEquals(
                        previous,
                        db.one("SELECT email FROM os_user WHERE user_id=?", actor).get("email"));
                bad(() -> EmailEnrollment.confirm(db, first));
                db.connection.commit();
                EmailEnrollment.confirm(db, second);
                assertEquals(
                        next,
                        db.one("SELECT email FROM os_user WHERE user_id=?", actor).get("email"));
                bad(() -> EmailEnrollment.confirm(db, second));
                db.update("DELETE FROM os_outbox");
                db.connection.commit();
            }
            assertEquals(
                    2,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                    + " action='account.email_change_proof'"));
            assertEquals(
                    2,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                    + " action='account.email_enrollment_confirmed'"));
            var caller = db.one("SELECT * FROM os_user WHERE user_id=?", actor);
            bad(
                    () ->
                            EmailEnrollment.request(
                                    store,
                                    db,
                                    caller,
                                    map(
                                            "email",
                                            "third@example.invalid",
                                            "current_password",
                                            "migration-test-password")));
        }
    }

    @Test
    void newHashesNormalizeUnicodeWithoutChangingLegacyAuthentication() throws Exception {
        String composed = "café-password-long", decomposed = "cafe\u0301-password-long";
        String hash = Passwords.hash(composed);
        assertTrue(hash.startsWith("nfc$"));
        assertTrue(Passwords.matches(hash, decomposed));
        String decomposedHash = Passwords.hash(decomposed);
        assertTrue(Passwords.matches(decomposedHash, composed));
        assertTrue(Passwords.matches(hash.substring(4), composed));
        assertFalse(Passwords.matches(hash.substring(4), decomposed));
        assertEquals(composed, Passwords.requireNewPassword(decomposed));
        bad(() -> Passwords.requireNewPassword(5));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void normalizedPasswordsReauthenticateConsistentlyAndEmailClaimsCannotRace(boolean postgres)
            throws Exception {
        var store = store(postgres);
        try (var db = store.open()) {
            String actor = bind(db),
                    composed = "é".repeat(1024),
                    decomposed = "e\u0301".repeat(1024);
            db.update(
                    "UPDATE os_user SET password=? WHERE user_id=?",
                    Passwords.hash(composed),
                    actor);
            db.connection.commit();
            var caller = Authentication.verify(db, "admin", decomposed, "unicode-boundary");
            assertNotNull(caller);
            EmailEnrollment.request(
                    store,
                    db,
                    caller,
                    map("email", "claimed@example.invalid", "current_password", decomposed));
            String token =
                    Outbox.decrypt(
                                    store.secret,
                                    db.one("SELECT envelope FROM os_outbox")
                                            .get("envelope")
                                            .toString())
                            .get("path")
                            .toString()
                            .substring(14);
            Service.createAccount(
                    db,
                    map("name", "Other", "role", "reader", "password", WorkflowSupport.PASSWORD));
            db.update(
                    "UPDATE os_user SET email='claimed@example.invalid',email_verified=1 WHERE"
                        + " name='Other'");
            db.connection.commit();
            assertEquals(
                    409,
                    assertThrows(Failure.class, () -> EmailEnrollment.confirm(db, token)).status);
            assertEquals(
                    "", db.one("SELECT email FROM os_user WHERE user_id=?", actor).get("email"));
            EmailEnrollment.cancel(db, actor);
            assertTrue(
                    RecoveryLinks.issue(
                                    db,
                                    caller,
                                    RecordAccess.owner(db, "Other"),
                                    map("current_password", decomposed))
                            .containsKey("reset_path"));
            assertEquals(
                    400,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            Service.changePassword(
                                                    db,
                                                    caller,
                                                    map(
                                                            "current_password",
                                                            decomposed,
                                                            "new_password",
                                                            composed)))
                            .status);
            Service.changePassword(
                    db,
                    caller,
                    map(
                            "current_password",
                            decomposed,
                            "new_password",
                            "A different safe passphrase"));
            PasswordExclusions.add(
                    db, actor, map("label", "Normalized boundary", "password", decomposed));
            bad(() -> PasswordExclusions.check(db, composed));
            db.update("UPDATE os_user SET active=0 WHERE user_id=?", actor);
            db.connection.commit();
            assertEquals(
                    401,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            EmailEnrollment.request(
                                                    store,
                                                    db,
                                                    caller,
                                                    map(
                                                            "email",
                                                            "inactive@example.invalid",
                                                            "current_password",
                                                            "A different safe passphrase")))
                            .status);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void durableWorkIsEncryptedIdempotentRestartSafeAndRevocable(boolean postgres)
            throws Exception {
        var store = store(postgres);
        String actor, first, second;
        try (var db = store.open()) {
            actor = bind(db);
            var body =
                    map(
                            "kind",
                            "notification",
                            "key",
                            "first",
                            "payload",
                            map("username", "admin", "message", "Private business message"));
            first = WorkJobs.enqueue(store, db, actor, body).get("job_id").toString();
            assertEquals(first, WorkJobs.enqueue(store, db, actor, body).get("job_id"));
            body.put("payload", map("username", "admin", "message", "Different"));
            bad(() -> WorkJobs.enqueue(store, db, actor, body));
            assertFalse(
                    db.one("SELECT envelope FROM os_work WHERE job_id=?", first)
                            .get("envelope")
                            .toString()
                            .contains("Private"));
            assertFalse(JSON.writeValueAsString(WorkJobs.list(db, actor)).contains("Private"));
            bad(() -> WorkJobs.enqueue(store, db, actor, map()));
            bad(
                    () ->
                            WorkJobs.enqueue(
                                    store,
                                    db,
                                    actor,
                                    map("kind", "execute.shell", "key", "bad", "payload", map())));
            bad(
                    () ->
                            WorkJobs.enqueue(
                                    store,
                                    db,
                                    actor,
                                    map(
                                            "kind",
                                            "notification",
                                            "key",
                                            "huge",
                                            "payload",
                                            map("text", "x".repeat(16001)))));
            db.connection.commit();
        }
        var worker = new WorkWorker(store);
        assertTrue(worker.runOne(System.currentTimeMillis()));
        assertFalse(worker.runOne(System.currentTimeMillis()));
        try (var db = store.open()) {
            bind(db);
            assertEquals(1, Notifications.list(db, actor).size());
            assertEquals(
                    "succeeded",
                    db.one("SELECT state FROM os_work WHERE job_id=?", first).get("state"));
            assertEquals(
                    "",
                    db.one("SELECT envelope FROM os_work WHERE job_id=?", first).get("envelope"));
            Notifications.update(db, actor, map("acknowledge", first));
            assertEquals(
                    1,
                    ((Number) Notifications.list(db, actor).getFirst().get("acknowledged"))
                            .intValue());
            bad(() -> Notifications.update(db, "missing", map("acknowledge", first)));
            Notifications.update(db, actor, map("enabled", false));
            assertEquals(
                    false,
                    Notifications.deliver(
                                    db, id(), map("username", "admin", "message", "Suppressed"), 0)
                            .get("delivered"));
            Notifications.update(db, actor, map("enabled", true));
            db.update("UPDATE os_user SET active=0 WHERE user_id=?", actor);
            assertEquals(
                    false,
                    Notifications.deliver(
                                    db, id(), map("username", "admin", "message", "Inactive"), 0)
                            .get("delivered"));
            db.update("UPDATE os_user SET active=1 WHERE user_id=?", actor);
            bad(
                    () ->
                            Notifications.deliver(
                                    db,
                                    id(),
                                    map("username", "absent", "message", "No recipient"),
                                    0));
            bad(() -> Notifications.deliver(db, id(), map(), 0));
            bad(() -> Notifications.update(db, actor, map()));
            bad(() -> Notifications.update(db, actor, map("enabled", "true")));
            Notifications.deliver(
                    db, first, map("username", "admin", "message", "Deduplicated"), 0);
            assertEquals(1, Notifications.list(db, actor).size());
            second =
                    WorkJobs.enqueue(
                                    store,
                                    db,
                                    actor,
                                    map(
                                            "kind",
                                            "notification",
                                            "key",
                                            "second",
                                            "payload",
                                            map(
                                                    "username",
                                                    "admin",
                                                    "message",
                                                    "Cannot run after password change")))
                            .get("job_id")
                            .toString();
            db.update("UPDATE os_user SET auth_revision=auth_revision+1 WHERE user_id=?", actor);
            db.connection.commit();
        }
        assertTrue(worker.runOne(System.currentTimeMillis()));
        try (var db = store.open()) {
            bind(db);
            assertEquals(
                    "cancelled",
                    db.one("SELECT state FROM os_work WHERE job_id=?", second).get("state"));
            bad(() -> WorkJobs.control(db, actor, first, "retry"));
            bad(() -> WorkJobs.control(db, actor, "missing", "retry"));
            bad(() -> WorkJobs.control(db, actor, first, "invalid"));
            String queued =
                    WorkJobs.enqueue(
                                    store,
                                    db,
                                    actor,
                                    map("kind", "notification", "key", "cancel", "payload", map()))
                            .get("job_id")
                            .toString();
            WorkJobs.control(db, actor, queued, "retry");
            WorkJobs.control(db, actor, queued, "cancel");
            db.update("UPDATE os_work SET created_at=0");
            WorkJobs.control(db, actor, "all", "cleanup");
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_work"));
            db.connection.commit();
        }
        worker.tick(); // Disabled by default.
    }

    @Test
    void textAndCalendarBoundariesAndDstUseBusinessTime() {
        for (Object input : List.of(3, "", " ", "\0", "x".repeat(6)))
            bad(() -> WorkJobs.text(input, 5));
        assertEquals("Valid", WorkJobs.text("Valid", 5));
        var c = calendar();
        assertEquals(c, BusinessCalendar.validate(c));
        assertEquals(0, BusinessCalendar.elapsed(c, 5, 4));
        assertEquals(0, BusinessCalendar.elapsed(c, 5, 5));
        long friday = at("2026-11-13T16:00:00Z"), tuesday = at("2026-11-17T10:00:00Z");
        assertEquals(120 * 60000L, BusinessCalendar.elapsed(c, friday, tuesday));
        assertEquals(
                0,
                BusinessCalendar.elapsed(
                        c, at("2026-11-17T01:00:00Z"), at("2026-11-17T08:00:00Z")));
        bad(() -> BusinessCalendar.elapsed(c, 0, at("2030-01-01T00:00:00Z")));
        var dst =
                map(
                        "zone",
                        "America/New_York",
                        "start",
                        "00:00",
                        "end",
                        "04:00",
                        "days",
                        List.of(7),
                        "holidays",
                        List.of());
        BusinessCalendar.validate(dst);
        assertEquals(
                3 * 3600000L,
                BusinessCalendar.elapsed(
                        dst, at("2026-03-08T05:00:00Z"), at("2026-03-08T08:00:00Z")));
        assertEquals(
                5 * 3600000L,
                BusinessCalendar.elapsed(
                        dst, at("2026-11-01T04:00:00Z"), at("2026-11-01T09:00:00Z")));
        bad(() -> BusinessCalendar.validate(map()));
        for (var change :
                List.of(
                        map("zone", "Not/AZone"),
                        map("start", "9:00"),
                        map("end", "x"),
                        map("start", "25:00"),
                        map("end", "09:00"),
                        map("days", "Monday"),
                        map("days", List.of()),
                        map("days", List.of(1, 2, 3, 4, 5, 6, 7, 1)),
                        map("days", List.of("1")),
                        map("days", List.of(0)),
                        map("days", List.of(8)),
                        map("holidays", "none"),
                        map("holidays", Collections.nCopies(1001, "2026-11-16")),
                        map("holidays", List.of("not-a-date")))) {
            var invalid = new LinkedHashMap<>(c);
            invalid.putAll(change);
            bad(() -> BusinessCalendar.validate(invalid));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void persistedClocksPauseResumeBreachAndFinishAcrossRestarts(boolean postgres)
            throws Exception {
        var store = store(postgres);
        String actor, id;
        long start = at("2026-11-13T16:00:00Z");
        try (var db = store.open()) {
            actor = bind(db);
            String record = record(db, "incident", "Business clock");
            ServiceClocks.calendar(db, actor, map("name", "Office", "calendar", calendar()));
            ServiceClocks.calendar(db, actor, map("name", "Office", "calendar", calendar()));
            bad(() -> ServiceClocks.calendar(db, actor, map()));
            bad(() -> ServiceClocks.start(db, actor, map(), start));
            for (Object minutes : List.of("1", 0, 525601))
                bad(
                        () ->
                                ServiceClocks.start(
                                        db,
                                        actor,
                                        map(
                                                "table",
                                                "incident",
                                                "record_id",
                                                record,
                                                "calendar",
                                                "Office",
                                                "minutes",
                                                minutes),
                                        start));
            bad(
                    () ->
                            ServiceClocks.start(
                                    db,
                                    actor,
                                    map(
                                            "table",
                                            "incident",
                                            "record_id",
                                            record,
                                            "calendar",
                                            "absent",
                                            "minutes",
                                            1),
                                    start));
            id =
                    ServiceClocks.start(
                                    db,
                                    actor,
                                    map(
                                            "table",
                                            "incident",
                                            "record_id",
                                            record,
                                            "calendar",
                                            "Office",
                                            "minutes",
                                            120),
                                    start)
                            .get("sla_id")
                            .toString();
            ServiceClocks.tick(db, start - 1);
            ServiceClocks.control(db, actor, id, "pause", at("2026-11-13T16:30:00Z"));
            assertEquals(
                    90 * 60000L,
                    ((Number)
                                    db.one("SELECT remaining_ms FROM os_sla WHERE sla_id=?", id)
                                            .get("remaining_ms"))
                            .longValue());
            ServiceClocks.tick(db, at("2026-11-17T09:30:00Z"));
            ServiceClocks.advance(
                    db,
                    db.one("SELECT * FROM os_sla WHERE sla_id=?", id),
                    at("2026-11-17T09:30:00Z"));
            ServiceClocks.control(db, actor, id, "resume", at("2026-11-17T09:30:00Z"));
            bad(() -> ServiceClocks.control(db, actor, id, "invalid", start));
            bad(() -> ServiceClocks.control(db, actor, "missing", "pause", start));
            db.connection.commit();
        }
        try (var db = store.open()) {
            bind(db);
            ServiceClocks.tick(db, at("2026-11-17T11:00:00Z"));
            assertEquals(
                    "breached", db.one("SELECT state FROM os_sla WHERE sla_id=?", id).get("state"));
            ServiceClocks.tick(db, at("2026-11-17T12:00:00Z"));
            assertEquals(1, Notifications.list(db, actor).size());
            bad(() -> ServiceClocks.control(db, actor, id, "complete", start));
            String record = record(db, "incident", "Second clock");
            String complete =
                    ServiceClocks.start(
                                    db,
                                    actor,
                                    map(
                                            "table",
                                            "incident",
                                            "record_id",
                                            record,
                                            "calendar",
                                            "Office",
                                            "minutes",
                                            1),
                                    start)
                            .get("sla_id")
                            .toString();
            ServiceClocks.control(db, actor, complete, "complete", start - 100);
            assertEquals(
                    "completed",
                    db.one("SELECT state FROM os_sla WHERE sla_id=?", complete).get("state"));
            String boundary =
                    ServiceClocks.start(
                                    db,
                                    actor,
                                    map(
                                            "table",
                                            "incident",
                                            "record_id",
                                            record,
                                            "calendar",
                                            "Office",
                                            "minutes",
                                            1),
                                    start)
                            .get("sla_id")
                            .toString();
            ServiceClocks.control(db, actor, boundary, "pause", start + 60000);
            assertEquals(
                    "breached",
                    db.one("SELECT state FROM os_sla WHERE sla_id=?", boundary).get("state"));
            String removed =
                    ServiceClocks.start(
                                    db,
                                    actor,
                                    map(
                                            "table",
                                            "incident",
                                            "record_id",
                                            record,
                                            "calendar",
                                            "Office",
                                            "minutes",
                                            1),
                                    start)
                            .get("sla_id")
                            .toString();
            db.update("UPDATE os_sla SET owner_id='removed' WHERE sla_id=?", removed);
            ServiceClocks.tick(db, start + 60000);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void knowledgePublishesSnapshotsAndDependenciesDoNotTraverseHiddenAssets(boolean postgres)
            throws Exception {
        var store = store(postgres);
        try (var db = store.open()) {
            String actor = bind(db), article = record(db, "kb_knowledge", "Public guidance");
            bad(() -> PublishedKnowledge.publish(db, actor, article));
            var item = Service.record(db, "kb_knowledge", article);
            object(item.get("data")).put("text", "Approved instructions");
            Service.save(db, item);
            assertEquals(1, PublishedKnowledge.publish(db, actor, article).get("version"));
            object(item.get("data")).put("text", "Unpublished draft");
            Service.save(db, item);
            assertEquals(
                    "Approved instructions", PublishedKnowledge.list(db).getFirst().get("content"));
            assertEquals(2, PublishedKnowledge.publish(db, actor, article).get("version"));
            assertEquals(1, PublishedKnowledge.list(db).size());
            PublishedKnowledge.retire(db, actor, article);
            assertTrue(PublishedKnowledge.list(db).isEmpty());
            bad(() -> PublishedKnowledge.retire(db, actor, "missing"));
            RecordAccess.change(db, item, actor, map("visibility", "private", "owner_id", actor));
            bad(() -> PublishedKnowledge.publish(db, actor, article));
            String a = record(db, "cmdb_ci", "Database"),
                    b = record(db, "cmdb_ci", "Service"),
                    c = record(db, "cmdb_ci", "Portal");
            AssetOperations.link(db, actor, map("dependent", b, "dependency", a, "remove", false));
            AssetOperations.link(db, actor, map("dependent", c, "dependency", b, "remove", false));
            AssetOperations.link(db, actor, map("dependent", a, "dependency", c, "remove", false));
            assertEquals(2, ((List<?>) AssetOperations.impact(db, a).get("impacted")).size());
            Service.createAccount(
                    db,
                    map("name", "Reader", "role", "reader", "password", WorkflowSupport.PASSWORD));
            RecordAccess.change(
                    db,
                    Service.record(db, "cmdb_ci", b),
                    actor,
                    map("visibility", "private", "owner_id", actor));
            db.viewerId = RecordAccess.owner(db, "Reader");
            db.viewerRole = "reader";
            assertEquals(0, ((List<?>) AssetOperations.impact(db, a).get("impacted")).size());
            bad(
                    () ->
                            AssetOperations.link(
                                    db,
                                    db.viewerId,
                                    map("dependent", b, "dependency", a, "remove", true)));
            bind(db);
            AssetOperations.link(db, actor, map("dependent", a, "dependency", c, "remove", true));
            bad(() -> AssetOperations.link(db, actor, map()));
            bad(
                    () ->
                            AssetOperations.link(
                                    db,
                                    actor,
                                    map("dependent", a, "dependency", a, "remove", false)));
            bad(
                    () ->
                            AssetOperations.link(
                                    db,
                                    actor,
                                    map("dependent", b, "dependency", a, "remove", "false")));
            var event =
                    map(
                            "key",
                            "monitor-42",
                            "asset_id",
                            a,
                            "severity",
                            "critical",
                            "summary",
                            "Health check failed");
            assertEquals(
                    1,
                    ((Number) AssetOperations.event(db, actor, event, 100).get("occurrences"))
                            .intValue());
            assertEquals(
                    2,
                    ((Number) AssetOperations.event(db, actor, event, 200).get("occurrences"))
                            .intValue());
            event.put("asset_id", b);
            bad(() -> AssetOperations.event(db, actor, event, 300));
            event.put("severity", "unknown");
            bad(() -> AssetOperations.event(db, actor, event, 300));
            bad(() -> AssetOperations.event(db, actor, map(), 300));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void boundedQueuesGraphsAndUnverifiedAddressChangesFailSafely(boolean postgres)
            throws Exception {
        var store = store(postgres);
        try (var db = store.open()) {
            String actor = bind(db);
            db.update(
                    "UPDATE os_user SET email='legacy@example.invalid',email_verified=0 WHERE"
                            + " user_id=?",
                    actor);
            db.connection.commit();
            var caller = db.one("SELECT * FROM os_user WHERE user_id=?", actor);
            assertEquals(
                    409,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            EmailEnrollment.request(
                                                    store,
                                                    db,
                                                    caller,
                                                    map(
                                                            "email",
                                                            "next@example.invalid",
                                                            "current_password",
                                                            "migration-test-password")))
                            .status);
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_outbox"));
            String source = record(db, "cmdb_ci", "Large impact root");
            for (int i = 0; i < 101; i++) {
                String dependent = record(db, "cmdb_ci", "Dependent " + i);
                AssetOperations.link(
                        db,
                        actor,
                        map("dependent", dependent, "dependency", source, "remove", false));
            }
            assertEquals(true, AssetOperations.impact(db, source).get("truncated"));
            db.update(
                    "WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<5000)"
                        + " INSERT INTO"
                        + " os_work(job_id,owner_id,auth_revision,kind,dedupe,fingerprint,envelope,state,attempts,available_at,created_at,lease,error,result)"
                        + " SELECT CAST(x AS VARCHAR(32)),?,0,'notification',CAST(x AS"
                        + " VARCHAR(80)),'hash','envelope','queued',0,0,0,'','','' FROM n",
                    actor);
            bad(
                    () ->
                            WorkJobs.enqueue(
                                    store,
                                    db,
                                    actor,
                                    map(
                                            "kind",
                                            "notification",
                                            "key",
                                            "overflow",
                                            "payload",
                                            map())));
        }
    }
}
