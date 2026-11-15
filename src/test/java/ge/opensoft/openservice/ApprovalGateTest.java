package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.*;

class ApprovalGateTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void gatedWritesRollbackAndMissingTargetsFail(boolean postgres) throws Exception {
        var fixture = new MigrationsTest();
        fixture.root = root;
        var env = fixture.configuration(postgres);
        var store = new Store(env);
        String id;
        try (var db = store.open()) {
            Service.createAccount(
                    db,
                    map("name", "Reviewer", "role", "agent", "password", WorkflowSupport.PASSWORD));
            var actor = ApprovalTest.as(db, "admin");
            Configuration.install(
                    db,
                    ConfigurationAcceptance.bundle(
                            "incident",
                            ApprovalGateAcceptance.definition(Service.schema(db, "incident"), 1)),
                    actor.get("user_id").toString(),
                    false);
            var record =
                    Service.create(
                            db, "incident", map("short_description", "Atomic gate"), "admin");
            id = record.get("sys_id").toString();
            String approval =
                    Approvals.request(
                                    db,
                                    "incident",
                                    id,
                                    actor,
                                    ApprovalAcceptance.proposal("Reviewer"))
                            .get("approval_id")
                            .toString();
            Approvals.decide(
                    db,
                    "incident",
                    id,
                    approval,
                    ApprovalTest.as(db, "Reviewer"),
                    map("decision", "approved", "comment", ""));
            db.connection.commit();
            ApprovalTest.as(db, "admin");
            Service.update(
                    db,
                    "incident",
                    Service.record(db, "incident", id),
                    map("state", "2"),
                    "admin",
                    "admin",
                    "0");
            db.connection.rollback();
            assertEquals("1", object(Service.record(db, "incident", id).get("data")).get("state"));
            assertEquals(
                    0,
                    db.count(
                            "SELECT COUNT(*) FROM os_event WHERE record_id=? AND action='updated'",
                            id));
            var missing = new LinkedHashMap<>(record);
            missing.put("sys_id", Values.id());
            missing.put("data", map("state", "2"));
            assertEquals(404, assertThrows(Failure.class, () -> Service.save(db, missing)).status);
        }
        try (var db = new Store(env).open()) {
            ApprovalTest.as(db, "admin");
            Service.update(
                    db,
                    "incident",
                    Service.record(db, "incident", id),
                    map("state", "2"),
                    "admin",
                    "admin",
                    "0");
            db.connection.commit();
            assertEquals("2", object(Service.record(db, "incident", id).get("data")).get("state"));
        }
    }
}
