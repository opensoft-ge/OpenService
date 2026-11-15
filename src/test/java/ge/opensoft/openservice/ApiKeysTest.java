package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.*;

class ApiKeysTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void credentialsAreBoundedPersistentAndFailClosed(boolean postgres) throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var env = fixture.configuration(postgres);
        var store = new Store(env);
        String target, admin, token, key;
        var good = map("label", "fixture", "table", "incident", "days", 1);
        try (var db = store.open()) {
            admin = RecordAccess.owner(db, "admin");
            Service.createAccount(
                    db,
                    map("name", "Machine", "role", "agent", "password", WorkflowSupport.PASSWORD));
            target = RecordAccess.owner(db, "Machine");
            db.update("DROP TABLE os_api_key");
            db.update("DELETE FROM os_migration WHERE version>=24");
            db.connection.commit();
            Migrations.apply(db);
            assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
            db.connection.rollback();
            assertEquals(23, db.count("SELECT COUNT(*) FROM os_migration"));
            Migrations.apply(db);
            db.connection.commit();
            assertEquals(target, RecordAccess.owner(db, "Machine"));
            assertEquals(
                    403,
                    assertThrows(Failure.class, () -> ApiKeys.issue(db, target, target, good))
                            .status);
            assertEquals(
                    404,
                    assertThrows(Failure.class, () -> ApiKeys.issue(db, admin, id(), good)).status);
            for (String change :
                    List.of(
                            "active=0,api_only=1",
                            "active=1,api_only=0",
                            "api_only=1,role='admin'",
                            "role='agent',password_reset_required=1")) {
                db.update("UPDATE os_user SET " + change + " WHERE user_id=?", target);
                assertEquals(
                        409,
                        assertThrows(Failure.class, () -> ApiKeys.issue(db, admin, target, good))
                                .status);
            }
            db.update("UPDATE os_user SET password_reset_required=0 WHERE user_id=?", target);
            for (Object bad :
                    List.of(
                            map(),
                            map("label", "x", "table", "incident", "days", 1, "extra", true),
                            List.of(),
                            map("label", 1, "table", "incident", "days", 1),
                            map("label", " ", "table", "incident", "days", 1),
                            map("label", "x".repeat(81), "table", "incident", "days", 1),
                            map("label", "x", "table", 1, "days", 1),
                            map("label", "x", "table", "incident", "days", "1"),
                            map("label", "x", "table", "incident", "days", 0),
                            map("label", "x", "table", "incident", "days", 91)))
                assertEquals(
                        400,
                        assertThrows(Failure.class, () -> ApiKeys.issue(db, admin, target, bad))
                                .status);
            assertEquals(
                    404,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            ApiKeys.issue(
                                                    db,
                                                    admin,
                                                    target,
                                                    map(
                                                            "label", "x", "table", "absent", "days",
                                                            1)))
                            .status);
            var issued = ApiKeys.issue(db, admin, target, good);
            token = issued.get("token").toString();
            key = issued.get("key_id").toString();
            assertEquals(
                    64,
                    db.one("SELECT token_hash FROM os_api_key WHERE key_id=?", key)
                            .get("token_hash")
                            .toString()
                            .length());
            assertFalse(db.all("SELECT * FROM os_api_key").toString().contains(token));
            assertEquals(
                    target,
                    ApiKeys.authenticate(db, "Bearer " + token, "/api/now/table/incident", "GET")
                            .get("user_id"));
            db.connection.commit();
        }
        try (var db = new Store(env).open()) {
            assertEquals(1, ApiKeys.list(db, admin, target).size());
            db.update("UPDATE os_api_key SET expires_at=0 WHERE key_id=?", key);
            assertEquals(
                    401,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            ApiKeys.authenticate(
                                                    db,
                                                    "Bearer " + token,
                                                    "/api/now/table/incident",
                                                    "GET"))
                            .status);
            db.connection.rollback();
            Service.revokeSessions(db, target);
            assertEquals(
                    401,
                    assertThrows(
                                    Failure.class,
                                    () ->
                                            ApiKeys.authenticate(
                                                    db,
                                                    "Bearer " + token,
                                                    "/api/now/table/incident",
                                                    "GET"))
                            .status);
            db.connection.rollback();
            for (int i = 1; i < 20; i++) ApiKeys.issue(db, admin, target, good);
            assertEquals(
                    409,
                    assertThrows(Failure.class, () -> ApiKeys.issue(db, admin, target, good))
                            .status);
            assertEquals(true, ApiKeys.revoke(db, admin, target, key).get("revoked"));
            ApiKeys.issue(
                    db,
                    admin,
                    target,
                    map("label", "x".repeat(80), "table", "incident", "days", 90));
            for (String bad : List.of("0", "91", "01", "1.5", "", "-1"))
                assertEquals(400, assertThrows(Failure.class, () -> ApiKeys.days(bad)).status);
            for (int days : List.of(1, 9, 10, 89, 90)) assertEquals(days, ApiKeys.days("" + days));
            db.connection.rollback();
            assertEquals(1, ApiKeys.list(db, admin, target).size());
        }
    }
}
