package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class RegulatoryNotices {
  private RegulatoryNotices() {}

  static Object run(Store.Db db, String op, Map<String, Object> b) throws Exception {
    if (op.equals("notice_assignments")) {
      var mine = new ArrayList<>();
      for (var row : list(db, "regulatory-notice")) {
        var notice = object(row.get("definition"));
        for (var entry : object(notice.get("impacts")).entrySet()) {
          var item = object(entry.getValue());
          var terms = object(item.get("terms"));
          if (db.viewerId.equals(terms.get("owner")) || db.viewerId.equals(terms.get("tester")))
            mine.add(
                map(
                    "notice_id",
                    row.get("subject"),
                    "target",
                    entry.getKey(),
                    "item",
                    item,
                    "revision",
                    row.get("revision")));
        }
      }
      return mine;
    }
    String id = id(b, "notice_id");
    var row = get(db, "regulatory-notice", id);
    var data = object(row.get("definition"));
    var impacts = object(data.getOrDefault("impacts", map()));
    if (op.equals("notice_revision")) {
      require(
          Objects.equals(data.getOrDefault("owner", db.viewerId), db.viewerId),
          "Notice owner required");
      var next = map();
      for (var raw : batch(b.get("entries"), 64)) {
        var entry = object(raw);
        keys(entry, "area", "subject", "requirement", "owner", "tester");
        String area =
            choice(entry, "area", "managed-supplier", "business-app", "audit-control", "policy");
        String subject = text(entry, "subject", 160);
        if (area.equals("policy")) {
          Service.readRecord(db, "kb_knowledge", subject);
        } else definition(db, area, subject);
        String owner = id(entry, "owner"), tester = id(entry, "tester");
        AgentPlans.account(db, owner);
        AgentPlans.account(db, tester);
        require(!owner.equals(tester), "Choose an independent tester");
        String target = key(area, subject);
        var terms =
            map(
                "area",
                area,
                "subject",
                subject,
                "requirement",
                text(entry, "requirement", 1600),
                "owner",
                owner,
                "tester",
                tester,
                "effective",
                ProjectDelivery.date(b.get("effective")));
        var prior = object(impacts.getOrDefault(target, map()));
        require(
            next.put(
                    target,
                    terms.equals(prior.get("terms"))
                        ? prior
                        : map("state", "assessment", "terms", terms))
                == null,
            "Duplicate impact target");
      }
      require(!next.isEmpty(), "Provide affected targets");
      data =
          map(
              "owner",
              db.viewerId,
              "title",
              text(b, "title", 200),
              "source",
              text(b, "reference", 400),
              "effective",
              ProjectDelivery.date(b.get("effective")),
              "impacts",
              next);
    } else if (op.equals("notice_history")) {
      var old = definition(db, "notice-snapshot", key(id, b.get("revision")));
      require(db.viewerRole.equals("admin"), "Audit access required");
      return old;
    } else {
      String target = text(b, "target", 128);
      var item = object(impacts.getOrDefault(target, map()));
      require(!item.isEmpty(), "Unknown impact target");
      var terms = object(item.get("terms"));
      String expected =
          op.equals("assess_impact")
              ? "assessment"
              : op.equals("remediate_impact") ? "remediation" : "testing";
      require(
          db.viewerId.equals(terms.get(expected.equals("testing") ? "tester" : "owner"))
              && expected.equals(item.get("state")),
          "Assigned owner and current stage required");
      String decision = choice(b, "decision", "affected", "unaffected", "complete", "pass", "fail");
      String state =
          switch (expected) {
            case "assessment" -> {
              require(Set.of("affected", "unaffected").contains(decision), "Assess impact");
              yield decision.equals("affected") ? "remediation" : "testing";
            }
            case "remediation" -> {
              require(decision.equals("complete"), "Submit completed work");
              yield "testing";
            }
            default -> {
              require(Set.of("pass", "fail").contains(decision), "Test the control");
              yield decision.equals("pass") ? "verified" : "remediation";
            }
          };
      item.put(
          expected,
          map(
              "decision",
              decision,
              "by",
              db.viewerId,
              "at",
              now(),
              "evidence",
              text(b, "evidence", 2000)));
      item.put("state", state);
    }
    var result = put(db, "regulatory-notice", id, b.get("revision"), data);
    save(db, "notice-snapshot", key(id, result.get("revision")), 0, data);
    // Owners receive only the affected item. The complete audit record belongs to the
    // administrator.
    return db.viewerRole.equals("admin")
        ? result
        : map("revision", result.get("revision"), "impact", impacts.get(b.get("target")));
  }
}
