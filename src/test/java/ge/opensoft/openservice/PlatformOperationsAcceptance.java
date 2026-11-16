package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class PlatformOperationsAcceptance {
    static final String ROOT = "/api/openservice/operations/";

    static Map<String, Object> post(WorkflowSupport h, String resource, Object value)
            throws Exception {
        var r = h.json("POST", ROOT + resource, value, auth("admin"));
        assertEquals(200, r.statusCode(), html(r));
        return object(parsed(r).get("result"));
    }

    static void run(WorkflowSupport h) throws Exception {
        h.account("PlatformReader", "reader");
        h.account("PlatformAgent", "agent");
        h.account("PlatformRequester", "requester");
        assertEquals(401, h.get(ROOT + "jobs", null).statusCode());
        for (String resource :
                List.of(
                        "jobs",
                        "connectors",
                        "calendars",
                        "clocks",
                        "events",
                        "qualification",
                        "password-policy")) {
            assertEquals(200, h.get(ROOT + resource, auth("admin")).statusCode(), resource);
            assertEquals(
                    403, h.get(ROOT + resource, auth("PlatformReader")).statusCode(), resource);
        }
        assertEquals(405, h.get(ROOT + "knowledge", auth("admin")).statusCode());
        assertEquals(400, h.json("DELETE", ROOT + "jobs", map(), auth("admin")).statusCode());
        assertEquals(
                405, h.json("POST", ROOT + "qualification", map(), auth("admin")).statusCode());
        assertEquals(405, h.json("PATCH", ROOT + "jobs", map(), auth("admin")).statusCode());
        assertEquals(400, h.get(ROOT + "jobs?extra=1", auth("admin")).statusCode());
        String job =
                post(
                                h,
                                "jobs",
                                map(
                                        "kind",
                                        "notification",
                                        "key",
                                        "acceptance",
                                        "payload",
                                        map(
                                                "username",
                                                "PlatformReader",
                                                "message",
                                                "Your request has been reviewed")))
                        .get("job_id")
                        .toString();
        try (var db = h.store.open()) {
            assertFalse(
                    db.one("SELECT envelope FROM os_work WHERE job_id=?", job)
                            .get("envelope")
                            .toString()
                            .contains("reviewed"));
        }
        assertTrue(new WorkWorker(h.store).runOne(System.currentTimeMillis()));
        var notices =
                objects(
                        parsed(h.get("/api/openservice/me/notifications", auth("PlatformReader")))
                                .get("result"));
        assertEquals(1, notices.size());
        assertEquals(
                0,
                objects(
                                parsed(
                                                h.get(
                                                        "/api/openservice/me/notifications",
                                                        auth("PlatformAgent")))
                                        .get("result"))
                        .size());
        assertEquals(
                404,
                h.json(
                                "POST",
                                "/api/openservice/me/notifications",
                                map("acknowledge", job),
                                auth("PlatformAgent"))
                        .statusCode());
        assertEquals(
                200,
                h.json(
                                "POST",
                                "/api/openservice/me/notifications",
                                map("acknowledge", job),
                                auth("PlatformReader"))
                        .statusCode());
        assertEquals(
                200,
                h.json(
                                "POST",
                                "/api/openservice/me/notifications",
                                map("enabled", false),
                                auth("PlatformRequester"))
                        .statusCode());
        assertEquals(200, h.get("/account/notifications", auth("PlatformReader")).statusCode());
        assertEquals(
                200,
                h.protectedForm(
                                "/account/notifications",
                                map("operation", "preference", "enabled", "true"),
                                auth("PlatformReader"))
                        .statusCode());
        assertEquals(
                200,
                h.protectedForm(
                                "/account/notifications",
                                map("notice_id", job),
                                auth("PlatformReader"))
                        .statusCode());
        assertEquals(200, h.get("/admin/operations", auth("admin")).statusCode());
        assertEquals(403, h.get("/admin/operations", auth("PlatformAgent")).statusCode());
        assertEquals(
                302,
                h.protectedForm(
                                "/admin/operations",
                                map(
                                        "operation",
                                        "notification",
                                        "username",
                                        "admin",
                                        "message",
                                        "A pending message"),
                                auth("admin"))
                        .statusCode());
        String pending;
        try (var db = h.store.open()) {
            pending =
                    db.one("SELECT job_id FROM os_work WHERE state='queued'")
                            .get("job_id")
                            .toString();
        }
        assertEquals(
                302,
                h.protectedForm(
                                "/admin/operations",
                                map("operation", "job", "job_id", pending, "action", "retry"),
                                auth("admin"))
                        .statusCode());
        post(h, "jobs/" + pending, map("operation", "cancel"));
        assertEquals(
                400,
                h.protectedForm(
                                "/admin/operations",
                                map("operation", "job", "job_id", pending),
                                auth("admin"))
                        .statusCode());
        assertEquals(
                400, h.json("POST", ROOT + "jobs/" + pending, map(), auth("admin")).statusCode());
        assertEquals(
                400,
                h.protectedForm("/admin/operations", map("operation", "unknown"), auth("admin"))
                        .statusCode());

        String incident = h.add("incident").get("sys_id").toString();
        post(h, "calendars", map("name", "office", "calendar", PlatformFoundationTest.calendar()));
        String clock =
                post(
                                h,
                                "clocks",
                                map(
                                        "table",
                                        "incident",
                                        "record_id",
                                        incident,
                                        "calendar",
                                        "office",
                                        "minutes",
                                        120))
                        .get("sla_id")
                        .toString();
        post(h, "clocks/" + clock, map("operation", "pause"));
        assertEquals(
                400,
                h.protectedForm(
                                "/admin/operations",
                                map("operation", "clock", "sla_id", clock),
                                auth("admin"))
                        .statusCode());
        assertEquals(
                400, h.json("POST", ROOT + "clocks/" + clock, map(), auth("admin")).statusCode());
        assertEquals(
                302,
                h.protectedForm(
                                "/admin/operations",
                                map("operation", "clock", "sla_id", clock, "action", "resume"),
                                auth("admin"))
                        .statusCode());
        assertEquals(200, h.get("/admin/operations", auth("admin")).statusCode());
        assertEquals(400, h.json("POST", ROOT + "connectors", map(), auth("admin")).statusCode());
        assertEquals(
                400,
                h.protectedForm(
                                "/admin/operations",
                                map(
                                        "operation",
                                        "connector",
                                        "alias",
                                        "local",
                                        "origin",
                                        "https://example.invalid",
                                        "token",
                                        "secret-token"),
                                auth("admin"))
                        .statusCode());
        String article = h.add("kb_knowledge").get("sys_id").toString();
        assertEquals(
                200,
                h.json(
                                "PATCH",
                                "/api/now/table/kb_knowledge/" + article,
                                map("text", "Published content"),
                                auth("admin"))
                        .statusCode());
        post(h, "knowledge/" + article, map("operation", "publish"));
        assertEquals(
                1,
                objects(
                                parsed(
                                                h.get(
                                                        "/api/openservice/knowledge",
                                                        auth("PlatformRequester")))
                                        .get("result"))
                        .size());
        assertTrue(
                html(h.get("/knowledge", auth("PlatformRequester"))).contains("Published content"));
        assertEquals(
                400,
                h.json("POST", ROOT + "knowledge/" + article, map(), auth("admin")).statusCode());
        assertEquals(
                400,
                h.json(
                                "POST",
                                ROOT + "knowledge/" + article,
                                map("operation", "invalid"),
                                auth("admin"))
                        .statusCode());
        post(h, "knowledge/" + article, map("operation", "retire"));
        assertEquals(
                400,
                h.json("POST", ROOT + "knowledge/" + article, map("operation", null), auth("admin"))
                        .statusCode());
        String a = h.add("cmdb_ci").get("sys_id").toString(),
                b = h.add("cmdb_ci").get("sys_id").toString();
        post(h, "dependencies", map("dependent", b, "dependency", a, "remove", false));
        assertEquals(200, h.get(ROOT + "dependencies/" + a, auth("PlatformReader")).statusCode());
        post(
                h,
                "events",
                map(
                        "key",
                        "api-monitor",
                        "asset_id",
                        a,
                        "severity",
                        "warning",
                        "summary",
                        "Synthetic outage"));
        assertEquals(200, h.get(ROOT + "references/cmdb_ci", auth("PlatformReader")).statusCode());
        assertEquals(400, h.get(ROOT + "references", auth("admin")).statusCode());

        String reader;
        try (var db = h.store.open()) {
            reader = RecordAccess.owner(db, "PlatformReader");
        }
        assertEquals(
                403,
                h.get("/api/openservice/security-events", auth("PlatformReader")).statusCode());
        post(h, "audit-access", map("user_id", reader, "enabled", true));
        assertEquals(200, h.get("/admin/security-events", auth("PlatformReader")).statusCode());
        assertEquals(
                405, h.json("POST", ROOT + "audit-checkpoint", map(), auth("admin")).statusCode());
        assertEquals(405, h.json("POST", ROOT + "references", map(), auth("admin")).statusCode());
        assertEquals(
                200,
                h.get("/api/openservice/security-events", auth("PlatformReader")).statusCode());
        assertEquals(200, h.get(ROOT + "audit-checkpoint", auth("PlatformReader")).statusCode());
        post(h, "audit-access", map("user_id", reader, "enabled", false));
        assertEquals(403, h.get(ROOT + "audit-checkpoint", auth("PlatformReader")).statusCode());
        var identity =
                post(
                        h,
                        "provisioning",
                        map(
                                "source",
                                "example-idp",
                                "external_id",
                                "subject-1",
                                "username",
                                "ExternalAccount",
                                "role",
                                "reader",
                                "active",
                                true,
                                "groups",
                                List.of()));
        assertTrue((Boolean) identity.get("password_setup_required"));
        post(
                h,
                "password-policy",
                map(
                        "minimum_length",
                        15,
                        "source",
                        "Synthetic regression corpus",
                        "version",
                        "1",
                        "digests",
                        List.of(Passwords.tokenHash("a-very-common-password"))));
        assertEquals(503, h.json("POST", ROOT + "assist", map(), auth("admin")).statusCode());

        // Saved reports are shared definitions, evaluated again against each reader's current
        // rights.
        String originalSchema;
        try (var db = h.store.open()) {
            db.viewerRole = "admin";
            db.viewerId = RecordAccess.owner(db, "admin");
            var schema = Service.schema(db, "incident");
            originalSchema = JSON.writeValueAsString(schema);
            object(schema.get("fields")).put("cost", "Cost");
            schema.put("field_rules", map("cost", map("type", "decimal")));
            db.update(
                    "UPDATE os_schema SET definition="
                            + db.jsonParameter()
                            + " WHERE name='incident'",
                    JSON.writeValueAsString(schema));
            db.connection.commit();
        }
        var report =
                post(
                        h,
                        "reports",
                        map(
                                "name",
                                "Shared costs",
                                "table",
                                "incident",
                                "shared",
                                true,
                                "query",
                                map("field", "cost", "q", "", "group_by", "")));
        String reportId = report.get("report_id").toString();
        assertEquals(200, h.get(ROOT + "reports", auth("PlatformReader")).statusCode());
        assertEquals(200, h.get(ROOT + "reports/" + reportId, auth("PlatformReader")).statusCode());
        assertEquals(
                404,
                h.json("DELETE", ROOT + "reports/" + reportId, map(), auth("PlatformAgent"))
                        .statusCode());
        assertEquals(
                200,
                h.json("DELETE", ROOT + "reports/" + reportId, map(), auth("admin")).statusCode());
        assertEquals(404, h.get(ROOT + "reports/" + reportId, auth("admin")).statusCode());
        try (var db = h.store.open()) {
            db.update(
                    "UPDATE os_schema SET definition="
                            + db.jsonParameter()
                            + " WHERE name='incident'",
                    originalSchema);
            db.connection.commit();
        }
    }
}
