package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseAcceptance.*;
import static ge.opensoft.openservice.TenJourneysAcceptance.post;
import static ge.opensoft.openservice.Values.*;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;

final class ExpansionBoundaryAcceptance {
    private ExpansionBoundaryAcceptance() {}

    static Map<String, Object> with(Map<String, Object> source, Object... pairs) {
        var body = new LinkedHashMap<>(source);
        body.putAll(map(pairs));
        return body;
    }

    static void designer(WorkflowSupport h) throws Exception {
        try (var db = h.store.open()) {
            var admin = PlatformFoundationTest.bind(db);
            assertThrows(
                    Failure.class,
                    () -> WorkflowStudio.startVersion(db, id(), id(), admin, 1, map("days", 1)));
        }
        var workflowPage = map("title", "Decision workflows");
        var intakePage = map("title", "Intake applications");
        var step =
                map(
                        "designer_rows",
                        "steps",
                        "operation",
                        "path",
                        "kind_1",
                        "response",
                        "item_1",
                        "Confirm?",
                        "stage_1",
                        "1");
        assertThrows(Failure.class, () -> DesignInputs.read(intakePage, new LinkedHashMap<>(step)));
        assertThrows(
                Failure.class, () -> DesignInputs.read(workflowPage, with(step, "steps", "[]")));
        assertThrows(
                Failure.class,
                () -> DesignInputs.read(workflowPage, with(step, "kind_1", "unknown")));
        assertThrows(
                Failure.class,
                () -> DesignInputs.read(workflowPage, with(step, "operation", "test")));
        assertThrows(
                Failure.class,
                () ->
                        DesignInputs.read(
                                intakePage,
                                map("designer_rows", "fields", "operation", "promote")));
        assertThrows(
                Failure.class,
                () ->
                        DesignInputs.read(
                                workflowPage,
                                map("designer_rows", "fields", "operation", "build")));
        assertThrows(
                Failure.class,
                () ->
                        DesignInputs.read(
                                workflowPage,
                                with(step, "item_1", "", "stage_1", "1", "kind_1", "unknown")));
        var fields =
                map(
                        "designer_rows",
                        "fields",
                        "operation",
                        "build",
                        "name_1",
                        "",
                        "label_1",
                        "Missing name",
                        "visibility_1",
                        "confidential");
        DesignInputs.read(intakePage, fields);
        assertTrue(fields.get("fields").toString().contains("Missing name"));
        DesignInputs.read(workflowPage, step);
        assertTrue(step.get("steps").toString().contains("Confirm?"));
        String design = id();
        var row =
                map(
                        "operation",
                        "path",
                        "design_id",
                        design,
                        "title",
                        "Risk routing",
                        "row_id",
                        "high",
                        "condition",
                        "risk",
                        "operand",
                        "high",
                        "days",
                        1,
                        "revision",
                        0,
                        "steps",
                        List.of(map("response", "Confirm identity", "stage", 1)));
        for (Object invalid :
                List.of(
                        List.of(),
                        List.of(map("response", "Invalid stage", "stage", 2)),
                        List.of(
                                map("response", "One", "stage", 1),
                                map("response", "Two", "stage", 2),
                                map("response", "Out of order", "stage", 1))))
            bad(h, "designer", "global", with(row, "steps", invalid), 400);
        post(h, "designer", row);
        var test =
                map(
                        "operation",
                        "test",
                        "design_id",
                        design,
                        "row_id",
                        "high",
                        "amount",
                        "2",
                        "risk",
                        "high",
                        "location",
                        "HQ",
                        "revision",
                        1);
        bad(h, "designer", "global", with(test, "risk", "low"), 400);
        post(h, "designer", test);
        bad(
                h,
                "designer",
                "global",
                map("operation", "publish", "design_id", design, "revision", 2),
                400);
        var fallback =
                with(
                        row,
                        "row_id",
                        "other",
                        "condition",
                        "default",
                        "operand",
                        "any",
                        "revision",
                        2);
        post(h, "designer", fallback);
        bad(h, "designer", "global", with(fallback, "row_id", "another", "revision", 3), 400);
        post(h, "designer", with(fallback, "revision", 3));
        var remove =
                map("operation", "remove", "design_id", design, "row_id", "missing", "revision", 4);
        bad(h, "designer", "global", remove, 400);
        post(h, "designer", with(remove, "row_id", "high"));
        var inspect = post(h, "designer", map("operation", "inspect", "design_id", design));
        assertEquals(
                1,
                objects(object(object(inspect.get("draft")).get("definition")).get("rows")).size());
        bad(
                h,
                "designer",
                "global",
                map("operation", "publish", "design_id", id(), "revision", 0),
                400);
        try (var db = h.store.open()) {
            PlatformFoundationTest.bind(db);
            String full = id();
            var rows = new ArrayList<>();
            for (int i = 0; i < 25; i++) rows.add(map("id", "row-" + i));
            EnterpriseData.save(db, "decision-draft", full, 0, map("rows", rows));
            assertThrows(
                    Failure.class,
                    () ->
                            DecisionFlows.command(
                                    db, "path", with(row, "design_id", full, "revision", 1)));
        }
    }

    static void apps(WorkflowSupport h) throws Exception {
        String app = id(), group = h.add("sys_user_group").get("sys_id").toString(), design = id();
        h.account("NonMember", "agent");
        h.account("IntakeOwner", "requester");
        var build =
                map(
                        "operation",
                        "build",
                        "app_id",
                        app,
                        "title",
                        "Intake",
                        "group_id",
                        group,
                        "design_id",
                        design,
                        "revision",
                        0);
        var f = map("name", "title", "label", "Title", "visibility", "requester");
        for (var fields : List.of(List.of(), List.of(f, f), List.of(with(f, "name", "field!"))))
            bad(h, "apps", "global", with(build, "fields", fields), 400);
        bad(h, "apps", "global", map("operation", "form", "app_id", app), 400);
        bad(h, "apps", "global", map("operation", "rollback", "app_id", app, "revision", 0), 400);
        assertTrue(
                post(h, "apps", map("operation", "compare", "app_id", app))
                        .containsKey("development"));
        try (var db = h.store.open()) {
            String admin = PlatformFoundationTest.bind(db);
            var owner = RecordAccess.owner(db, "IntakeOwner");
            var release =
                    map(
                            "title",
                            "Intake",
                            "group",
                            group,
                            "fields",
                            map("title", f),
                            "workflow",
                            map());
            EnterpriseData.save(db, "intake-release", app, 0, release);
            String item = id();
            EnterpriseData.save(
                    db,
                    "intake-case",
                    item,
                    0,
                    map(
                            "app",
                            app,
                            "requester",
                            owner,
                            "group",
                            group,
                            "created",
                            now(),
                            "version",
                            1));
            db.viewerId = RecordAccess.owner(db, "NonMember");
            db.viewerRole = "agent";
            assertTrue(
                    objects(
                                    object(IntakeApps.command(db, "queue", map("app_id", app)))
                                            .get("cases"))
                            .isEmpty());
            assertFalse(IntakeApps.team(db, map("group", group)));
            db.viewerRole = "requester";
            db.viewerId = owner;
            assertEquals(
                    1,
                    objects(
                                    object(IntakeApps.command(db, "queue", map("app_id", app)))
                                            .get("cases"))
                            .size());
            assertThrows(Failure.class, () -> IntakeApps.command(db, "note", map("case_id", item)));
            assertTrue(
                    objects(
                                    object(IntakeApps.command(db, "queue", map("app_id", id())))
                                            .get("cases"))
                            .isEmpty());
            db.viewerRole = "admin";
            db.viewerId = admin;
            assertTrue(IntakeApps.team(db, map("group", group)));
            var submit = map("app_id", app, "revision", 1);
            var answer = map("name", "title", "value", "case");
            for (var entries :
                    List.of(
                            List.of(answer, answer),
                            List.of(with(answer, "name", "unknown")),
                            List.of()))
                assertThrows(
                        Failure.class,
                        () -> IntakeApps.command(db, "submit", with(submit, "entries", entries)));
            db.viewerRole = "reader";
            assertThrows(Failure.class, () -> IntakeApps.command(db, "submit", submit));
        }
    }

    static void continuity(WorkflowSupport h) throws Exception {
        h.account("ExerciseWorker", "agent");
        h.account("Outside", "agent");
        try (var db = h.store.open()) {
            String admin = PlatformFoundationTest.bind(db),
                    worker = RecordAccess.owner(db, "ExerciseWorker");
            String initial = id(), later = id(), plan = id();
            long start = 1000000L;
            var spec = map("rto", 1, "rpo", 1);
            var exercise =
                    map(
                            "state",
                            "running",
                            "steps",
                            List.of(map("state", "pending", "owner", worker)),
                            "duty",
                            map("primary", admin),
                            "spec",
                            spec,
                            "plan",
                            plan,
                            "version",
                            1,
                            "start",
                            start);
            EnterpriseData.save(db, "recovery-exercise", initial, 0, exercise);
            var input = map("exercise_id", initial, "step", 1, "revision", 1, "note", "done");
            db.viewerId = RecordAccess.owner(db, "Outside");
            db.viewerRole = "agent";
            assertThrows(Failure.class, () -> RecoveryExercises.command(db, "inspect", input));
            db.viewerId = worker;
            assertNotNull(RecoveryExercises.command(db, "inspect", input));
            assertThrows(Failure.class, () -> RecoveryExercises.command(db, "measure", input));
            RecoveryExercises.command(db, "task", input);
            assertThrows(
                    Failure.class,
                    () -> RecoveryExercises.command(db, "task", with(input, "revision", 2)));
            db.viewerId = admin;
            db.viewerRole = "admin";
            var measure =
                    with(
                            input,
                            "revision",
                            2,
                            "restored_at",
                            Instant.ofEpochMilli(start - 1).toString(),
                            "restore_point",
                            Instant.ofEpochMilli(start).toString(),
                            "evidence",
                            "probe");
            assertThrows(Failure.class, () -> RecoveryExercises.command(db, "measure", measure));
            RecoveryExercises.command(
                    db,
                    "measure",
                    with(measure, "restored_at", Instant.ofEpochMilli(start + 61000).toString()));
            assertThrows(Failure.class, () -> RecoveryExercises.command(db, "task", input));
            assertThrows(Failure.class, () -> RecoveryExercises.command(db, "measure", measure));
            db.viewerId = worker;
            db.viewerRole = "agent";
            assertThrows(Failure.class, () -> RecoveryExercises.command(db, "correct", input));
            db.viewerId = admin;
            db.viewerRole = "admin";
            RecoveryExercises.command(db, "correct", with(input, "revision", 3));
            assertThrows(Failure.class, () -> RecoveryExercises.command(db, "correct", input));
            var retest = with(input, "revision", 4, "later_id", later);
            int revision = 0;
            for (var invalid :
                    List.of(
                            map("plan", id()),
                            map("version", 2),
                            map("state", "running"),
                            map("start", start))) {
                var trial =
                        map(
                                "state",
                                "passed",
                                "plan",
                                plan,
                                "version",
                                1,
                                "start",
                                System.currentTimeMillis() + 1000);
                trial.putAll(invalid);
                EnterpriseData.save(db, "recovery-exercise", later, revision++, trial);
                assertThrows(Failure.class, () -> RecoveryExercises.command(db, "retest", retest));
            }
            EnterpriseData.save(
                    db,
                    "recovery-exercise",
                    later,
                    revision,
                    map(
                            "state",
                            "passed",
                            "plan",
                            plan,
                            "version",
                            1,
                            "start",
                            System.currentTimeMillis() + 1000));
            RecoveryExercises.command(db, "retest", retest);
            assertThrows(Failure.class, () -> RecoveryExercises.command(db, "retest", retest));
        }
    }
}
