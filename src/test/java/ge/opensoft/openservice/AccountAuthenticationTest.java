package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Map;

class AccountAuthenticationTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void freshAuthorityAndRetainedIdentitySurviveReinitialization(boolean postgres)
            throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var env = fixture.configuration(postgres);
        var store = new Store(env);
        String admin, target;
        try (var db = store.open()) {
            admin = RecordAccess.owner(db, "admin");
            Service.createAccount(
                    db,
                    map(
                            "name",
                            "HistoryUser",
                            "password",
                            WorkflowSupport.PASSWORD,
                            "role",
                            "reader"));
            target = RecordAccess.owner(db, "HistoryUser");
            AuthenticationEvents.record(
                    db, "HistoryUser", Passwords.tokenHash("HistoryUser"), "browser", "failed");
            db.connection.commit();
            assertEquals(
                    403,
                    assertThrows(
                                    Failure.class,
                                    () -> AccountAuthentication.page(db, target, target, Map.of()))
                            .status);
            assertEquals(
                    404,
                    assertThrows(
                                    Failure.class,
                                    () -> AccountAuthentication.page(db, admin, id(), Map.of()))
                            .status);
            assertEquals(
                    1,
                    objects(AccountAuthentication.page(db, admin, target, Map.of()).get("result"))
                            .size());
        }
        try (var db = new Store(env).open()) {
            var history = AccountAuthentication.page(db, admin, target, Map.of());
            assertEquals(target, object(history.get("account")).get("user_id"));
            assertEquals("failed", objects(history.get("result")).getFirst().get("outcome"));
            db.update("UPDATE os_user SET active=0 WHERE user_id=?", admin);
            assertEquals(
                    403,
                    assertThrows(
                                    Failure.class,
                                    () -> AccountAuthentication.page(db, admin, target, Map.of()))
                            .status);
        }
    }
}
