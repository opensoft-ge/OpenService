package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class PrivacyAssessments {
  private PrivacyAssessments() {}

  static Object command(Store.Db db, String op, Map<String, Object> b) throws Exception {
    String id = id(b, "assessment_id");
    var row = get(db, "privacy-assessment", id);
    var data = object(row.get("definition"));
    if (op.equals("privacy_screen")) {
      require(data.isEmpty() || db.viewerId.equals(data.get("owner")), "Assessment owner required");
      String supplier = text(b, "supplier", 160), app = id(b, "application_id");
      var vendor = definition(db, "managed-supplier", SupplierGovernance.identity(supplier));
      var application = definition(db, "business-app", app);
      require(
          "admin".equals(db.viewerRole) || db.viewerId.equals(application.get("owner")),
          "Application owner required");
      String reviewer = vendor.get("security").toString(), contact = id(b, "user_id");
      AgentPlans.account(db, contact);
      require(
          new HashSet<>(List.of(db.viewerId, reviewer, contact)).size() == 3,
          "Assign independent participants");
      data =
          map(
              "owner",
              db.viewerId,
              "application",
              app,
              "supplier",
              supplier,
              "reviewer",
              reviewer,
              "contact",
              contact,
              "categories",
              text(b, "categories", 400),
              "purpose",
              text(b, "purpose", 1000),
              "retention_days",
              integer(b.get("days"), 1, 36500),
              "question",
              text(b, "question", 500),
              "sensitivity",
              choice(b, "risk", "ordinary", "sensitive"),
              "state",
              "supplier_review");
    } else if (op.equals("privacy_question") || op.equals("privacy_answer")) {
      require(db.viewerId.equals(data.get("contact")), "Assigned supplier contact required");
      if (op.equals("privacy_question"))
        return map(
            "question",
            data.get("question"),
            "revision",
            row.get("revision"),
            "state",
            data.get("state"));
      require("supplier_review".equals(data.get("state")), "Question already answered");
      data.putAll(
          map(
              "answer",
              text(b, "answer", 1800),
              "state",
              "sensitive".equals(data.get("sensitivity")) ? "independent_review" : "screened"));
    } else if (op.equals("privacy_decide")) {
      require(
          db.viewerId.equals(data.get("reviewer"))
              && "independent_review".equals(data.get("state")),
          "Assigned independent review required");
      String decision = choice(b, "decision", "approved", "remediation");
      data.putAll(
          map("state", decision, "reason", text(b, "note", 1200), "decided_by", db.viewerId));
      if (decision.equals("remediation")) {
        String owner = DutyRosters.responder(db, b.get("owner_id"));
        data.put("corrective_owner", owner);
        CustomerImpact.notify(db, owner, "A privacy assessment requires corrective work: " + id);
      }
    } else if (op.equals("privacy_correct")) {
      require(
          "remediation".equals(data.get("state"))
              && db.viewerId.equals(data.get("corrective_owner")),
          "Assigned corrective owner required");
      data.putAll(map("state", "independent_review", "correction", text(b, "evidence", 2000)));
    } else {
      require(
          db.viewerId.equals(data.get("owner"))
              || db.viewerId.equals(data.get("reviewer"))
              || "admin".equals(db.viewerRole),
          "Privacy review access required");
      return definition(db, "privacy-snapshot", key(id, b.get("revision")));
    }
    var saved = put(db, "privacy-assessment", id, b.get("revision"), data);
    save(
        db,
        "privacy-snapshot",
        key(id, saved.get("revision")),
        0,
        map("by", db.viewerId, "at", now(), "assessment", data));
    return op.equals("privacy_answer") ? map("state", data.get("state")) : saved;
  }
}
