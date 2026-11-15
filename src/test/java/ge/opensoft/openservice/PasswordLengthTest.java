package ge.opensoft.openservice;

import static org.junit.jupiter.api.Assertions.*;

import ge.opensoft.openservice.Values.Failure;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

class PasswordLengthTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bootstrapRejectsShortNewPasswordsButNeverRevalidatesExistingCredentials(boolean postgres)
            throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var env = fixture.configuration(postgres).withProperty("ADMIN_PASSWORD", "🌟".repeat(14));
        assertEquals(400, assertThrows(Failure.class, () -> new Store(env)).status);
        var store = new Store(env.withProperty("ADMIN_PASSWORD", "🌟".repeat(15)));
        String hash;
        try (var db = store.open()) {
            hash =
                    db.one("SELECT password FROM os_user WHERE name='admin'")
                            .get("password")
                            .toString();
            assertTrue(Passwords.matches(hash, "🌟".repeat(15)));
            assertEquals(Migrations.VERSION, db.count("SELECT COUNT(*) FROM os_migration"));
        }
        new Store(env.withProperty("ADMIN_PASSWORD", "legacyshort"));
        try (var db = store.open()) {
            assertEquals(
                    hash,
                    db.one("SELECT password FROM os_user WHERE name='admin'").get("password"));
        }
    }
}
