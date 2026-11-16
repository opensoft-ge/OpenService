package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class ApprovalDelegationAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        for (String user : List.of("FirstReviewer", "ReplacementReviewer", "OtherReviewer"))
            h.account(user, "agent");
        h.account("IneligibleReviewer", "reader");
        String record = h.add("incident").get("sys_id").toString();
        String path = "/api/openservice/records/incident/" + record + "/approvals";
        String approval = ApprovalAcceptance.create(h, path, "FirstReviewer", "admin"),
                endpoint = path + "/" + approval + "/delegate";
        assertEquals(
                403,
                h.json(
                                "POST",
                                endpoint,
                                map("approver", "ReplacementReviewer"),
                                auth("OtherReviewer"))
                        .statusCode());
        assertEquals(400, h.json("POST", endpoint, map(), auth("FirstReviewer")).statusCode());
        for (String invalid :
                List.of("MissingReviewer", "IneligibleReviewer", "admin", "FirstReviewer"))
            assertEquals(
                    400,
                    h.json("POST", endpoint, map("approver", invalid), auth("FirstReviewer"))
                            .statusCode());
        assertEquals(
                404,
                h.json(
                                "POST",
                                path + "/" + id() + "/delegate",
                                map("approver", "ReplacementReviewer"),
                                auth("admin"))
                        .statusCode());
        String duplicate = ApprovalAcceptance.create(h, path, "ReplacementReviewer", "admin");
        assertEquals(
                409,
                h.json("POST", endpoint, map("approver", "ReplacementReviewer"), auth("admin"))
                        .statusCode());
        assertEquals(
                200,
                h.json(
                                "POST",
                                path + "/" + duplicate + "/withdraw",
                                map("comment", "Remove duplicate"),
                                auth("admin"))
                        .statusCode());
        try (var db = h.store.open()) {
            db.update(
                    "INSERT INTO os_record_access(record_id,visibility,owner_id,group_id)"
                        + " VALUES(?,'private',?,'')",
                    record,
                    RecordAccess.owner(db, "admin"));
            db.connection.commit();
        }
        assertEquals(
                404,
                h.json("POST", endpoint, map("approver", "ReplacementReviewer"), auth("admin"))
                        .statusCode());
        try (var db = h.store.open()) {
            db.update("DELETE FROM os_record_access WHERE record_id=?", record);
            db.connection.commit();
        }
        assertEquals(
                200,
                h.json(
                                "POST",
                                endpoint,
                                map("approver", "ReplacementReviewer"),
                                auth("FirstReviewer"))
                        .statusCode());
        assertEquals(
                403,
                h.json(
                                "POST",
                                path + "/" + approval + "/decision",
                                map("decision", "approved", "comment", ""),
                                auth("FirstReviewer"))
                        .statusCode());
        String browser = "/record/incident/" + record + "/approvals/" + approval + "/delegate";
        assertEquals(
                302,
                h.protectedForm(browser, map("approver", "OtherReviewer"), auth("admin"))
                        .statusCode());
        assertEquals(
                200,
                h.json(
                                "POST",
                                path + "/" + approval + "/decision",
                                map("decision", "approved", "comment", ""),
                                auth("OtherReviewer"))
                        .statusCode());
        assertEquals(
                409,
                h.json("POST", endpoint, map("approver", "ReplacementReviewer"), auth("admin"))
                        .statusCode());
        String fresh = ApprovalAcceptance.create(h, path, "FirstReviewer", "admin");
        assertEquals(
                200,
                h.json(
                                "PATCH",
                                "/api/now/table/incident/" + record,
                                map("short_description", "Changed proposal"),
                                auth("admin"))
                        .statusCode());
        assertEquals(
                409,
                h.json(
                                "POST",
                                path + "/" + fresh + "/delegate",
                                map("approver", "ReplacementReviewer"),
                                auth("admin"))
                        .statusCode());
        try (var db = h.store.open()) {
            assertEquals(
                    2,
                    db.count(
                            "SELECT COUNT(*) FROM os_event WHERE action='approval.delegated' AND"
                                + " record_id=?",
                            record));
            assertEquals(
                    RecordAccess.owner(db, "admin"),
                    db.one("SELECT requested_by FROM os_approval WHERE approval_id=?", approval)
                            .get("requested_by"));
        }
    }
}
