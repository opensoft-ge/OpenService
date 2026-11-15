package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

class ProvisioningPasswordTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void provisioningAndRequirementShareOneTransaction(boolean postgres) throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var store = new Store(fixture.configuration(postgres));
        try (var db = store.open()) {
            String actor = RecordAccess.owner(db, "admin");
            var body =
                    map(
                            "name",
                            "ProvisionAtomic",
                            "role",
                            "reader",
                            "password",
                            WorkflowSupport.PASSWORD,
                            "require_password_change",
                            true);
            Service.createManagedAccount(db, body, actor);
            String target = RecordAccess.owner(db, "ProvisionAtomic");
            assertEquals(map("required", true), PasswordReset.state(db, target));
            assertEquals(true, body.get("require_password_change"));
            db.connection.rollback();
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_user WHERE name='ProvisionAtomic'"));
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_security_event"));
            Service.createAccount(
                    db,
                    map(
                            "name",
                            "InvitationCredential",
                            "role",
                            "reader",
                            "password",
                            WorkflowSupport.PASSWORD));
            String recipient = RecordAccess.owner(db, "InvitationCredential");
            assertEquals(map("required", false), PasswordReset.state(db, recipient));
            db.connection.commit();
            assertEquals(
                    403,
                    assertThrows(
                                    Failure.class,
                                    () -> Service.createManagedAccount(db, body, recipient))
                            .status);
            assertEquals(0, db.count("SELECT COUNT(*) FROM os_user WHERE name='ProvisionAtomic'"));
        }
    }
}
