package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class ApprovalWithdrawalAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("WithdrawRequester", "agent");
        h.account("WithdrawReviewer", "agent");
        h.account("WithdrawOther", "agent");
        h.account("WithdrawReader", "reader");
        String record = h.add("incident").get("sys_id").toString();
        String path = "/api/openservice/records/incident/" + record + "/approvals";
        String approval =
                ApprovalAcceptance.create(h, path, "WithdrawReviewer", "WithdrawRequester");
        String action = path + "/" + approval + "/withdraw";
        assertEquals(405, h.get(action, auth("admin")).statusCode());
        assertEquals(401, h.json("POST", action, map("comment", "Cancel"), null).statusCode());
        for (String user : List.of("WithdrawReviewer", "WithdrawOther", "WithdrawReader"))
            assertEquals(
                    403, h.json("POST", action, map("comment", "Cancel"), auth(user)).statusCode());
        for (Object body :
                List.of(
                        map(),
                        map("comment", "Valid", "extra", true),
                        List.of(),
                        map("comment", null),
                        map("comment", ""),
                        map("comment", " "),
                        map("comment", "x".repeat(2001)),
                        map("comment", "bad\0text")))
            assertEquals(400, h.json("POST", action, body, auth("WithdrawRequester")).statusCode());
        assertEquals(
                400,
                h.json("POST", action + "?x=y", map("comment", "Cancel"), auth("admin"))
                        .statusCode());
        assertEquals(
                404,
                h.json(
                                "POST",
                                action.replace(approval, id()),
                                map("comment", "Cancel"),
                                auth("admin"))
                        .statusCode());
        Map<String, Object> original;
        try (var db = h.store.open()) {
            original = Service.schema(db, "incident");
        }
        try {
            var restricted = new LinkedHashMap<>(original);
            restricted.put("record_roles", map("update", List.of("admin")));
            ApprovalAcceptance.writeSchema(h, restricted);
            assertEquals(
                    403,
                    h.json("POST", action, map("comment", "Cancel"), auth("WithdrawRequester"))
                            .statusCode());
            restricted.remove("record_roles");
            restricted.put(
                    "field_rules",
                    map("state", map("type", "string", "read_roles", List.of("admin"))));
            ApprovalAcceptance.writeSchema(h, restricted);
            assertEquals(
                    403,
                    h.json("POST", action, map("comment", "Cancel"), auth("WithdrawRequester"))
                            .statusCode());
        } finally {
            ApprovalAcceptance.writeSchema(h, original);
        }
        try (var db = h.store.open()) {
            RecordAccess.change(
                    db,
                    Service.record(db, "incident", record),
                    RecordAccess.owner(db, "admin"),
                    map("visibility", "private", "owner_id", RecordAccess.owner(db, "admin")));
            db.connection.commit();
        }
        assertEquals(
                404,
                h.json("POST", action, map("comment", "Cancel"), auth("WithdrawRequester"))
                        .statusCode());
        try (var db = h.store.open()) {
            RecordAccess.change(
                    db,
                    Service.record(db, "incident", record),
                    RecordAccess.owner(db, "admin"),
                    map("visibility", "workspace", "owner_id", ""));
            db.connection.commit();
        }
        assertEquals(
                200,
                h.json(
                                "PATCH",
                                "/api/now/table/incident/" + record,
                                map("short_description", "Changed proposal"),
                                auth("admin"))
                        .statusCode());
        var response =
                h.json(
                        "POST",
                        action,
                        map("comment", "😀".repeat(2000)),
                        auth("WithdrawRequester"));
        assertEquals(200, response.statusCode(), html(response));
        var result = object(parsed(response).get("result"));
        assertEquals("withdrawn", result.get("status"));
        assertEquals(false, result.get("current"));
        assertEquals("😀".repeat(2000), result.get("comment"));
        assertFalse(result.get("decided_at").toString().isBlank());
        assertEquals(0, parsed(h.get(path, auth("admin"))).get("pending_count"));
        assertEquals(0, ApprovalInboxAcceptance.rows(h, "", "WithdrawReviewer").size());
        assertEquals(
                1, ApprovalInboxAcceptance.rows(h, "?status=withdrawn", "WithdrawReviewer").size());
        assertEquals(
                409, h.json("POST", action, map("comment", "Again"), auth("admin")).statusCode());
        assertEquals(
                409,
                h.json(
                                "POST",
                                action.replace("/withdraw", "/decision"),
                                map("decision", "approved", "comment", ""),
                                auth("WithdrawReviewer"))
                        .statusCode());
        var item =
                object(
                        parsed(h.get("/api/now/table/incident/" + record, auth("admin")))
                                .get("result"));
        assertEquals("1", item.get("state"));
        new Store(h.environment);
        assertEquals(
                1, ApprovalInboxAcceptance.rows(h, "?status=withdrawn", "WithdrawReviewer").size());
        String second = h.add("incident").get("sys_id").toString();
        String secondPath = path.replace(record, second);
        String secondApproval =
                ApprovalAcceptance.create(h, secondPath, "WithdrawReviewer", "WithdrawRequester");
        String browser =
                (secondPath + "/" + secondApproval + "/withdraw")
                        .replace("/api/openservice/records/", "/record/");
        String csrf = h.login("admin");
        assertEquals(
                403, h.form(browser, map("withdrawal_reason", "Missing CSRF"), null).statusCode());
        assertEquals(
                400,
                h.form(
                                browser,
                                map(
                                        "csrf",
                                        csrf,
                                        "withdrawal_reason",
                                        "Valid",
                                        "extra",
                                        "Unexpected"),
                                null)
                        .statusCode());
        assertEquals(
                404,
                h.json(
                                "POST",
                                action.replace(approval, secondApproval),
                                map("comment", "Mismatch"),
                                auth("admin"))
                        .statusCode());
        assertEquals(
                200,
                h.json(
                                "POST",
                                secondPath + "/" + secondApproval + "/withdraw",
                                map("comment", "Administrator closes obsolete work"),
                                auth("admin"))
                        .statusCode());
        try (var db = h.store.open()) {
            assertEquals(
                    2, db.count("SELECT COUNT(*) FROM os_event WHERE action='approval.withdrawn'"));
            assertEquals(
                    1,
                    ((Number) Service.record(db, "incident", record).get("revision")).intValue());
        }
    }
}
