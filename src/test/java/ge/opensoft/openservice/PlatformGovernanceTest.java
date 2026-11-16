package ge.opensoft.openservice;

import static ge.opensoft.openservice.PlatformFoundationTest.*;
import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.*;

class PlatformGovernanceTest {
    @TempDir Path root;

    Store store(boolean pg) throws Exception {
        var f = new MigrationsTest();
        f.root = root;
        return new Store(f.configuration(pg));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void identitySynchronizationCannotTakeOverLocalUsersOrKeepRevokedMemberships(boolean pg)
            throws Exception {
        var store = store(pg);
        try (var db = store.open()) {
            String actor = bind(db),
                    a = record(db, "sys_user_group", "Support"),
                    b = record(db, "sys_user_group", "Escalation");
            var body =
                    map(
                            "source",
                            "example-idp",
                            "external_id",
                            "subject1",
                            "username",
                            "ExternalOne",
                            "role",
                            "agent",
                            "active",
                            true,
                            "groups",
                            List.of(a, b));
            String target =
                    object(IdentityProvisioning.synchronize(db, actor, body).get("account"))
                            .get("user_id")
                            .toString();
            assertEquals(2, GroupMembers.forUser(db, target).size());
            long revision =
                    ((Number)
                                    db.one(
                                                    "SELECT auth_revision FROM os_user WHERE"
                                                        + " user_id=?",
                                                    target)
                                            .get("auth_revision"))
                            .longValue();
            IdentityProvisioning.synchronize(db, actor, body);
            assertEquals(
                    revision,
                    ((Number)
                                    db.one(
                                                    "SELECT auth_revision FROM os_user WHERE"
                                                        + " user_id=?",
                                                    target)
                                            .get("auth_revision"))
                            .longValue());
            body.put("role", "reader");
            body.put("groups", List.of(a));
            IdentityProvisioning.synchronize(db, actor, body);
            assertEquals(1, GroupMembers.forUser(db, target).size());
            body.put("active", false);
            IdentityProvisioning.synchronize(db, actor, body);
            assertTrue(GroupMembers.forUser(db, target).isEmpty());
            assertEquals(false, Service.account(db, target).get("active"));
            body.put("active", true);
            IdentityProvisioning.synchronize(db, actor, body);
            assertEquals(1, GroupMembers.forUser(db, target).size());
            Service.remove(db, Service.record(db, "sys_user_group", a), "admin");
            assertTrue(GroupMembers.forUser(db, target).isEmpty());
            assertEquals(
                    1,
                    db.count(
                            "SELECT COUNT(*) FROM os_security_event WHERE"
                                + " action='account.group_deleted' AND target_id=?",
                            target));
            body.put("groups", List.of());
            var renamed = new LinkedHashMap<>(body);
            renamed.put("username", "OtherName");
            bad(() -> IdentityProvisioning.synchronize(db, actor, renamed));
            Service.changeRole(db, actor, target, map("role", "admin"));
            bad(() -> IdentityProvisioning.synchronize(db, actor, body));
            Service.changeRole(db, actor, target, map("role", "reader"));
            bad(() -> IdentityProvisioning.synchronize(db, actor, map()));
            for (var change :
                    List.of(
                            map("username", "invalid-name"),
                            map("role", "admin"),
                            map("active", "true"),
                            map("groups", "none"),
                            map("groups", Collections.nCopies(101, b)))) {
                var invalid = new LinkedHashMap<>(body);
                invalid.putAll(change);
                bad(() -> IdentityProvisioning.synchronize(db, actor, invalid));
            }
            db.update("UPDATE os_user SET password_reset_required=0 WHERE user_id=?", target);
            assertEquals(
                    false,
                    IdentityProvisioning.synchronize(db, actor, body)
                            .get("password_setup_required"));
            db.connection.commit();
            var takeover = new LinkedHashMap<>(body);
            takeover.put("external_id", "another-subject");
            bad(() -> IdentityProvisioning.synchronize(db, actor, takeover));
            assertEquals(1, db.count("SELECT COUNT(*) FROM os_identity"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void auditGrantsAreSeparateAndCheckpointsDetectChanges(boolean pg) throws Exception {
        var store = store(pg);
        try (var db = store.open()) {
            String actor = bind(db);
            var empty = AuditGovernance.checkpoint(db);
            assertEquals(0L, empty.get("count"));
            Service.createAccount(
                    db,
                    map("name", "Auditor", "role", "reader", "password", WorkflowSupport.PASSWORD));
            String auditor = RecordAccess.owner(db, "Auditor");
            bad(() -> AuditGovernance.requireViewer(db, auditor));
            AuditGovernance.requireViewer(db, actor);
            bad(() -> AuditGovernance.grant(db, actor, map()));
            bad(() -> AuditGovernance.grant(db, actor, map("user_id", auditor, "enabled", "true")));
            AuditGovernance.grant(db, actor, map("user_id", auditor, "enabled", true));
            AuditGovernance.requireViewer(db, auditor);
            bad(() -> Service.requireAccountAdministrator(db, auditor));
            var checkpoint = AuditGovernance.checkpoint(db);
            assertEquals(1L, checkpoint.get("count"));
            assertNotEquals(empty.get("hash"), checkpoint.get("hash"));
            assertEquals(checkpoint, AuditGovernance.checkpoint(db));
            db.update("UPDATE os_security_event SET action='altered'");
            assertNotEquals(checkpoint.get("hash"), AuditGovernance.checkpoint(db).get("hash"));
            AuditGovernance.grant(db, actor, map("user_id", auditor, "enabled", false));
            bad(() -> AuditGovernance.requireViewer(db, auditor));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void versionedPasswordPolicyScreensNormalizedValuesAndChangesAtomically(boolean pg)
            throws Exception {
        var store = store(pg);
        try (var db = store.open()) {
            String actor = bind(db),
                    password = "café-very-common-password",
                    decomposed = "cafe\u0301-very-common-password";
            assertEquals(15, PasswordPolicies.status(db).get("minimum_length"));
            var policy =
                    map(
                            "minimum_length",
                            20,
                            "source",
                            "Synthetic fixture corpus",
                            "version",
                            "2026-11",
                            "digests",
                            List.of(Passwords.tokenHash(password), Passwords.tokenHash(password)));
            PasswordPolicies.replace(db, actor, policy);
            assertEquals(1, ((Number) PasswordPolicies.status(db).get("digest_count")).intValue());
            bad(() -> PasswordExclusions.check(db, decomposed));
            bad(() -> PasswordExclusions.check(db, "still-too-short"));
            PasswordExclusions.check(db, "A separate long unique passphrase");
            db.connection.rollback();
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_password_corpus"));
            PasswordPolicies.replace(db, actor, policy);
            db.connection.commit();
            bad(() -> PasswordPolicies.replace(db, actor, map()));
            for (var change :
                    List.of(
                            map("minimum_length", "20"),
                            map("minimum_length", 14),
                            map("minimum_length", 129),
                            map("digests", "bad"),
                            map("digests", List.of()),
                            map("digests", Collections.nCopies(100001, "x")),
                            map("digests", List.of(5)),
                            map("digests", List.of("INVALID")))) {
                var invalid = new LinkedHashMap<>(policy);
                invalid.putAll(change);
                bad(() -> PasswordPolicies.replace(db, actor, invalid));
                assertEquals(1, db.count("SELECT COUNT(*) FROM os_password_corpus"));
            }
            PasswordExclusions.add(
                    db, actor, map("label", "Unicode duplicate", "password", decomposed));
            bad(() -> PasswordExclusions.check(db, password));
            policy.put("digests", List.of(Passwords.tokenHash("another compromised password")));
            PasswordPolicies.replace(db, actor, policy);
            bad(
                    () ->
                            PasswordExclusions.check(
                                    db,
                                    password)); // Manual exclusion still applies after corpus
                                                // rotation.
            assertTrue(Passwords.requireLength("nfc$", "e\u0301".repeat(1024)));
            assertFalse(Passwords.requireLength("legacy", "e\u0301".repeat(1024)));
            assertFalse(Passwords.requireLength("nfc$", "e".repeat(1025)));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sharedReportsReevaluateVisibilityAndPolicyBeforeReturningTotals(boolean pg)
            throws Exception {
        var store = store(pg);
        try (var db = store.open()) {
            String actor = bind(db);
            Service.createAccount(
                    db,
                    map(
                            "name",
                            "ReportReader",
                            "role",
                            "reader",
                            "password",
                            WorkflowSupport.PASSWORD));
            String reader = RecordAccess.owner(db, "ReportReader");
            var schema = Service.schema(db, "incident");
            object(schema.get("fields")).put("cost", "Cost");
            schema.put("field_rules", map("cost", map("type", "decimal")));
            db.update(
                    "UPDATE os_schema SET definition="
                            + db.jsonParameter()
                            + " WHERE name='incident'",
                    JSON.writeValueAsString(schema));
            String visible =
                    Service.create(
                                    db,
                                    "incident",
                                    map("short_description", "Shared", "cost", "10"),
                                    "admin")
                            .get("sys_id")
                            .toString();
            var hidden =
                    Service.create(
                            db,
                            "incident",
                            map("short_description", "Private", "cost", "90"),
                            "admin");
            RecordAccess.change(db, hidden, actor, map("visibility", "private", "owner_id", actor));
            var body =
                    map(
                            "name",
                            "Cost total",
                            "table",
                            "incident",
                            "shared",
                            true,
                            "query",
                            map("field", "cost"));
            String report = SavedReports.save(db, actor, body).get("report_id").toString();
            body.put("shared", false);
            String privateReport = SavedReports.save(db, actor, body).get("report_id").toString();
            assertEquals(2, SavedReports.list(db, actor).size());
            db.viewerId = reader;
            db.viewerRole = "reader";
            assertEquals(1, SavedReports.list(db, reader).size());
            assertEquals(
                    "10",
                    object(((List<?>) SavedReports.run(db, reader, report).get("rows")).getFirst())
                            .get("sum"));
            bad(() -> SavedReports.run(db, reader, privateReport));
            bad(() -> SavedReports.remove(db, reader, report));
            bind(db);
            SavedReports.remove(db, actor, privateReport);
            bad(() -> SavedReports.save(db, actor, map()));
            var invalid = new LinkedHashMap<>(body);
            invalid.put("shared", "true");
            bad(() -> SavedReports.save(db, actor, invalid));
            invalid.put("shared", false);
            invalid.put("query", map("field", 2));
            bad(() -> SavedReports.save(db, actor, invalid));
            for (int i = 1; i < 100; i++) SavedReports.save(db, actor, body);
            bad(() -> SavedReports.save(db, actor, body));
        }
    }
}
