package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

final class ApprovalResubmissionAcceptance {
    static void run(WorkflowSupport h) throws Exception {
        h.account("ResubmitReviewer", "agent");
        String record = h.add("incident").get("sys_id").toString();
        String path = "/api/openservice/records/incident/" + record + "/approvals";
        String first = ApprovalAcceptance.create(h, path, "ResubmitReviewer", "admin");
        assertEquals(
                200,
                h.json(
                                "POST",
                                path + "/" + first + "/withdraw",
                                map("comment", "Reconsider assignment"),
                                auth("admin"))
                        .statusCode());
        Map<String, Object> previous;
        try (var db = h.store.open()) {
            previous = db.one("SELECT * FROM os_approval WHERE approval_id=?", first);
        }
        String second = ApprovalAcceptance.create(h, path, "ResubmitReviewer", "admin");
        assertNotEquals(first, second);
        assertEquals(
                409,
                h.json("POST", path, ApprovalAcceptance.proposal("ResubmitReviewer"), auth("admin"))
                        .statusCode());
        assertEquals(
                409,
                h.json(
                                "POST",
                                path + "/" + first + "/decision",
                                map("decision", "approved", "comment", ""),
                                auth("ResubmitReviewer"))
                        .statusCode());
        assertEquals(
                200,
                h.json(
                                "POST",
                                path + "/" + second + "/decision",
                                map("decision", "rejected", "comment", "Not ready"),
                                auth("ResubmitReviewer"))
                        .statusCode());
        assertEquals(
                409,
                h.json("POST", path, ApprovalAcceptance.proposal("ResubmitReviewer"), auth("admin"))
                        .statusCode());
        try (var db = h.store.open()) {
            assertEquals(previous, db.one("SELECT * FROM os_approval WHERE approval_id=?", first));
            assertEquals(2, db.count("SELECT COUNT(*) FROM os_approval WHERE record_id=?", record));
            assertEquals(
                    0,
                    ((Number) Service.record(db, "incident", record).get("revision")).intValue());
        }
        String other = h.add("incident").get("sys_id").toString();
        String otherPath = path.replace(record, other);
        String old = ApprovalAcceptance.create(h, otherPath, "ResubmitReviewer", "admin");
        assertEquals(
                200,
                h.json(
                                "POST",
                                otherPath + "/" + old + "/withdraw",
                                map("comment", "Wait"),
                                auth("admin"))
                        .statusCode());
        try (var db = h.store.open()) {
            db.update("UPDATE os_user SET active=0 WHERE name='ResubmitReviewer'");
            db.connection.commit();
        }
        assertEquals(
                400,
                h.json(
                                "POST",
                                otherPath,
                                ApprovalAcceptance.proposal("ResubmitReviewer"),
                                auth("admin"))
                        .statusCode());
        try (var db = h.store.open()) {
            db.update("UPDATE os_user SET active=1 WHERE name='ResubmitReviewer'");
            db.connection.commit();
        }
        String fresh = ApprovalAcceptance.create(h, otherPath, "ResubmitReviewer", "admin");
        assertEquals(
                200,
                h.json(
                                "POST",
                                otherPath + "/" + fresh + "/decision",
                                map("decision", "approved", "comment", "Ready now"),
                                auth("ResubmitReviewer"))
                        .statusCode());
        assertEquals(
                409,
                h.json(
                                "POST",
                                otherPath,
                                ApprovalAcceptance.proposal("ResubmitReviewer"),
                                auth("admin"))
                        .statusCode());
        var rows = objects(parsed(h.get(otherPath, auth("admin"))).get("result"));
        assertEquals(
                List.of("approved", "withdrawn"), rows.stream().map(r -> r.get("status")).toList());
    }
}
