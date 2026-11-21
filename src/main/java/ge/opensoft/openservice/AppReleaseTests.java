package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class AppReleaseTests {
    private AppReleaseTests() {}

    static Object configure(Store.Db db, Map<String, Object> b) throws Exception {
        String app = id(b, "app_id");
        var users = map();
        for (String kind : List.of("requester", "reviewer", "outsider")) {
            String user = id(b, kind);
            require(!users.containsValue(user), "Choose three distinct test identities");
            users.put(kind, user);
        }
        return put(
                db,
                "app-release-test",
                app,
                b.get("revision"),
                map("users", users, "field", text(b, "private_field", 64)));
    }

    static Object promote(Store.Db db, Map<String, Object> b) throws Exception {
        definition(db, "app-release-test", id(b, "app_id"));
        return IntakeApps.command(db, "promote", b);
    }

    static void verify(Store.Db db, String app, Map<String, Object> draft) throws Exception {
        var test = object(get(db, "app-release-test", app).get("definition"));
        if (test.isEmpty()) return;
        String field = test.get("field").toString();
        require(
                "confidential"
                        .equals(object(object(draft.get("fields")).get(field)).get("visibility")),
                "Release exposes a protected field");
        var users = object(test.get("users"));
        var point = db.connection.setSavepoint();
        String admin = db.viewerId, role = db.viewerRole, probe = Values.id();
        try {
            save(
                    db,
                    "intake-case",
                    probe,
                    0,
                    map(
                            "app",
                            app,
                            "requester",
                            users.get("requester"),
                            "group",
                            draft.get("group"),
                            "answers",
                            map(),
                            "private_answers",
                            map(field, "sample"),
                            "notes",
                            List.of()));
            for (var entry : users.entrySet()) {
                db.viewerId = entry.getValue().toString();
                db.viewerRole = AgentPlans.account(db, db.viewerId).get("role").toString();
                boolean allowed = true, visible = false;
                try {
                    var view = object(IntakeApps.command(db, "case", map("case_id", probe)));
                    visible = object(view.get("case")).containsKey("private_answers");
                } catch (Failure denied) {
                    if (!"Case unavailable".equals(denied.getMessage())) throw denied;
                    allowed = false;
                }
                require(
                        allowed == !entry.getKey().equals("outsider")
                                && visible == entry.getKey().equals("reviewer"),
                        "Release failed the " + entry.getKey() + " access test");
            }
        } finally {
            db.viewerId = admin;
            db.viewerRole = role;
            db.connection.rollback(point);
            db.connection.releaseSavepoint(point);
        }
    }
}
