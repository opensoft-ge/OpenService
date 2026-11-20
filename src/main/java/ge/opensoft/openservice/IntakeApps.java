package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class IntakeApps {
    private IntakeApps() {}

    static boolean team(Store.Db db, Map<String, Object> app) throws Exception {
        return "admin".equals(db.viewerRole)
                || ("agent".equals(db.viewerRole)
                        && Fulfilment.member(db, app.get("group").toString()));
    }

    static Object command(Store.Db db, String op, Map<String, Object> b) throws Exception {
        if (Set.of("case", "note").contains(op)) {
            String id = id(b, "case_id");
            var row = get(db, "intake-case", id);
            var data = definition(db, "intake-case", id);
            boolean staff = team(db, data);
            require(staff || db.viewerId.equals(data.get("requester")), "Case unavailable");
            if (op.equals("note")) {
                require(staff, "Assigned fulfillment team required");
                var notes = new ArrayList<Object>((List<?>) data.get("notes"));
                notes.add(map("by", db.viewerId, "text", text(b, "note", 2000), "at", now()));
                data.put("notes", notes);
                return put(db, "intake-case", id, b.get("revision"), data);
            }
            if (!staff) {
                data.remove("notes");
                data.remove("private_answers");
            }
            return map("revision", row.get("revision"), "case", data);
        }
        String app = id(b, "app_id");
        var release = get(db, "intake-release", app);
        var draft = get(db, "intake-draft", app);
        var active = object(release.get("definition"));
        if (op.equals("queue")) {
            var rows = new ArrayList<Object>();
            for (var row : list(db, "intake-case")) {
                var data = object(row.get("definition"));
                if (app.equals(data.get("app"))
                        && (db.viewerId.equals(data.get("requester")) || team(db, data)))
                    rows.add(
                            map(
                                    "case_id",
                                    row.get("subject"),
                                    "version",
                                    data.get("version"),
                                    "created",
                                    data.get("created")));
            }
            return map("cases", rows);
        }
        if (op.equals("build")) {
            var fields = new LinkedHashMap<String, Object>();
            for (Object item : batch(b.get("fields"), 32)) {
                var field = object(item);
                keys(field, "name", "label", "visibility");
                String name = text(field, "name", 64);
                require(
                        name.matches("[a-z][a-z0-9_]*") && !fields.containsKey(name),
                        "Use unique lowercase field names");
                fields.put(
                        name,
                        map(
                                "label",
                                text(field, "label", 100),
                                "visibility",
                                choice(field, "visibility", "requester", "confidential")));
            }
            require(!fields.isEmpty(), "Define at least one field");
            String group = id(b, "group_id"), design = id(b, "design_id");
            Service.readRecord(db, "sys_user_group", group);
            var flow = definition(db, "decision-release", design);
            return put(
                    db,
                    "intake-draft",
                    app,
                    b.get("revision"),
                    map(
                            "title",
                            text(b, "title", 160),
                            "group",
                            group,
                            "fields",
                            fields,
                            "design",
                            design,
                            "decision_version",
                            get(db, "decision-release", design).get("revision"),
                            "workflow",
                            flow,
                            "base",
                            object(draft.get("definition"))
                                    .getOrDefault("base", release.get("revision")),
                            "author",
                            db.viewerId));
        }
        if (op.equals("compare")) return map("development", draft, "test", release);
        if (Set.of("promote", "rebase").contains(op)) {
            var data = definition(db, "intake-draft", app);
            ProblemInvestigations.revision(b.get("revision"), draft.get("revision"));
            ProblemInvestigations.revision(b.get("release_revision"), release.get("revision"));
            if (op.equals("rebase")) {
                require(Boolean.TRUE.equals(b.get("confirm")), "Review both configurations first");
                data.put("base", release.get("revision"));
                data.put("author", db.viewerId);
                return put(db, "intake-draft", app, b.get("revision"), data);
            }
            require(
                    !db.viewerId.equals(data.get("author")),
                    "A second administrator must promote the draft");
            ProblemInvestigations.revision(data.get("base"), release.get("revision"));
            data.put("promoted_by", db.viewerId);
            if (!active.isEmpty())
                save(db, "intake-history", key(app, release.get("revision")), 0, active);
            return put(db, "intake-release", app, release.get("revision"), data);
        }
        if (op.equals("rollback")) {
            int version = ((Number) release.get("revision")).intValue();
            require(version > 1, "Choose an app with a previous test release");
            var prior = definition(db, "intake-history", key(app, version - 1));
            save(db, "intake-history", key(app, version), 0, active);
            return put(db, "intake-release", app, b.get("revision"), prior);
        }
        require(!active.isEmpty(), "Choose a published intake app");
        if (op.equals("form"))
            return map(
                    "title",
                    active.get("title"),
                    "fields",
                    active.get("fields"),
                    "revision",
                    release.get("revision"));
        require(
                Set.of("admin", "agent", "requester").contains(db.viewerRole),
                "Intake submission permission required");
        ProblemInvestigations.revision(b.get("revision"), release.get("revision"));
        var fields = object(active.get("fields"));
        var visible = map();
        var confidential = map();
        var seen = new HashSet<String>();
        for (Object item : batch(b.get("entries"), 32)) {
            var entry = object(item);
            keys(entry, "name", "value");
            String name = text(entry, "name", 64);
            require(fields.containsKey(name) && seen.add(name), "Choose a defined field only once");
            var target =
                    "confidential".equals(object(fields.get(name)).get("visibility"))
                            ? confidential
                            : visible;
            target.put(name, text(entry, "value", 2000));
        }
        require(seen.equals(fields.keySet()), "Complete every intake field");
        String id = id(b, "new_id");
        DecisionFlows.start(
                db,
                active.get("design").toString(),
                active.get("decision_version"),
                object(active.get("workflow")),
                id,
                db.viewerId,
                b);
        save(
                db,
                "intake-case",
                id,
                0,
                map(
                        "app",
                        app,
                        "version",
                        release.get("revision"),
                        "title",
                        active.get("title"),
                        "fields",
                        fields,
                        "group",
                        active.get("group"),
                        "requester",
                        db.viewerId,
                        "created",
                        now(),
                        "answers",
                        visible,
                        "private_answers",
                        confidential,
                        "notes",
                        List.of(),
                        "run_id",
                        id));
        return map("case_id", id, "version", release.get("revision"));
    }
}
