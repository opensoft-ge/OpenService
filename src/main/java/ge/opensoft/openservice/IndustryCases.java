package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class IndustryCases {
  private IndustryCases() {}

  enum Program {
    insurance("adjust","approve","deny"),
    dispute("investigate", "uphold", "decline"),
    turnaround("decontaminate", "release", "quarantine"),
    permit("inspect", "issue", "deny");
    final String review, accept, decline;

    Program(String review, String accept, String decline) {
      this.review = review;
      this.accept = accept;
      this.decline = decline;
    }
  }

  static boolean member(Store.Db db, Object team) throws Exception {
    return db.count(
            "SELECT COUNT(*) FROM os_group_member WHERE group_id=? AND user_id=?",
            team,
            db.viewerId)
        > 0;
  }

  static Object run(Store.Db db, String op, Map<String, Object> b) throws Exception {
    if (op.equals("register_disputable_transaction")) {
      String user = id(b, "user_id"),
          ref = text(b, "reference", 120),
          currency = text(b, "currency", 3);
      var amount = money(b.get("amount"));
      Service.account(db, user);
      require(
          currency.matches("[A-Z]{3}") && amount.signum() > 0,
          "Provide a posted amount and currency");
      return ServiceAssurance.once(
          db,
          "bank-transaction",
          key(ref),
          map(
              "reference",
              ref,
              "holder",
              user,
              "amount",
              amount.toPlainString(),
              "currency",
              currency));
    }
    if (op.equals("equipment_readiness")) {
      String ci = id(b, "record_id");
      Service.readRecord(db, "cmdb_ci", ci);
      return get(db, "equipment-readiness", ci);
    }
    if (op.equals("industry_case") || op.equals("advance_industry_case")) {
      String id = id(b, "new_id");
      var row = get(db, "industry-case", id);
      var data = object(row.get("definition"));
      boolean team = member(db, data.get("team"));
      if (!db.viewerId.equals(data.get("requester")) && !team)
        throw new Failure(404, "Case unavailable");
      if (op.equals("industry_case")) return row;
      require(
          team && !db.viewerId.equals(data.get("requester")),
          "Independent fulfillment team member required");
      String state = data.get("state").toString();
      var program = Program.valueOf(data.get("program").toString());
      String decision;
      if (state.equals("submitted")) decision = choice(b, "decision", program.review);
      else {
        require(state.equals(program.review), "Case is already closed");
        require(!db.viewerId.equals(data.get("reviewer")), "Independent final reviewer required");
        decision = choice(b, "decision", program.accept, program.decline);
      }
      var history = new ArrayList<>(objects(data.get("history")));
      history.add(
          map(
              "actor",
              db.viewerId,
              "decision",
              decision,
              "at",
              now(),
              "evidence",
              text(b, "evidence", 1200)));
      data.putAll(map("state", decision, "reviewer", db.viewerId, "history", history));
      if (!decision.equals(program.review)) {
        data.put("closed", true);
        var details = object(data.get("details"));
        if (program == Program.permit && decision.equals("issue"))
          data.put("permit_number", "PERMIT-" + id);
        if (program == Program.dispute && decision.equals("uphold")) {
          var adjustment =
              map(
                  "case",
                  id,
                  "reference",
                  data.get("reference"),
                  "amount",
                  details.get("amount"),
                  "currency",
                  details.get("currency"),
                  "status",
                  "approved_for_posting");
          ServiceAssurance.once(db, "dispute-adjustment", id, adjustment);
          data.put("adjustment", adjustment);
        }
        if (program == Program.insurance && decision.equals("approve"))
          data.put("adjustment",map("amount",details.get("payable"),"currency",details.get("currency"),
              "status","approved_benefit"));
        if (program == Program.turnaround) {
          String ci = details.get("equipment").toString();
          var tracker = get(db, "equipment-readiness", ci);
          require(
              id.equals(object(tracker.get("definition")).get("case")),
              "Equipment is assigned to another turnaround");
          put(
              db,
              "equipment-readiness",
              ci,
              tracker.get("revision"),
              map("case", id, "status", decision, "verified", now()));
        }
      }
      return put(db, "industry-case", id, b.get("revision"), data);
    }

    String kind = choice(b, "kind", "dispute", "turnaround", "permit","insurance");
    if (op.equals("case_program")) {
      String team = id(b, "group_id");
      CatalogueRouting.group(db, team);
      return put(db, "case-program", kind, b.get("revision"), map("team", team));
    }
    if (op.equals("industry_queue")) {
      var rows = new ArrayList<>();
      for (var row : list(db, "industry-case")) {
        var data = object(row.get("definition"));
        if (kind.equals(data.get("program"))
            && !data.containsKey("closed")
            && member(db, data.get("team")))
          rows.add(
              map(
                  "case",
                  row.get("subject"),
                  "reference",
                  data.get("reference"),
                  "state",
                  data.get("state")));
      }
      return map("cases", rows);
    }
    var program = get(db, "case-program", kind);
    var team = definition(db, "case-program", kind).get("team");
    String id = id(b, "new_id"), ref = text(b, "reference", 120);
    var details = details(db, kind, object(b.get("manifest")));
    String dedup = key(kind, db.viewerId, ref);
    var receipt = object(get(db, "industry-submission", dedup).get("definition"));
    if (!receipt.isEmpty()) {
      require(
          details.equals(receipt.get("details")),
          "Submission reference already has different details");
      return get(db, "industry-case", receipt.get("case").toString());
    }
    if (kind.equals("dispute")) {
      var transaction = object(get(db, "bank-transaction", key(ref)).get("definition"));
      if (!db.viewerId.equals(transaction.get("holder")))
        throw new Failure(404, "Posted transaction unavailable");
      require(
          transaction.get("currency").equals(details.get("currency"))
              && money(details.get("amount")).compareTo(money(transaction.get("amount"))) <= 0,
          "Dispute exceeds the posted transaction or uses another currency");
    }
    if (kind.equals("turnaround")) {
      String ci = details.get("equipment").toString();
      var tracker = get(db, "equipment-readiness", ci);
      require(
          !"in_turnaround".equals(object(tracker.get("definition")).get("status")),
          "Equipment already has an open turnaround");
      put(
          db,
          "equipment-readiness",
          ci,
          tracker.get("revision"),
          map("case", id, "status", "in_turnaround"));
    }
    put(db, "industry-submission", dedup, 0, map("case", id, "details", details));
    return put(
        db,
        "industry-case",
        id,
        0,
        map(
            "program",
            kind,
            "case",
            id,
            "team",
            team,
            "program_revision",
            program.get("revision"),
            "requester",
            db.viewerId,
            "reference",
            ref,
            "details",
            details,
            "state",
            "submitted",
            "history",
            List.of()));
  }

  static Map<String, Object> details(Store.Db db, String kind, Map<String, Object> manifest)
      throws Exception {
    return switch (Program.valueOf(kind)) {
      case insurance -> ClaimPayments.loss(db,manifest);
      case dispute -> {
        keys(manifest, "amount", "currency", "reason");
        String currency = text(manifest, "currency", 3);
        var amount = money(manifest.get("amount"));
        require(
            currency.matches("[A-Z]{3}") && amount.signum() > 0,
            "Choose a positive disputed amount and currency");
        yield map(
            "amount",
            amount.toPlainString(),
            "currency",
            currency,
            "reason",
            text(manifest, "reason", 400));
      }
      case turnaround -> {
        keys(manifest, "equipment", "site");
        String ci = id(manifest, "equipment");
        Service.readRecord(db, "cmdb_ci", ci);
        yield map("equipment", ci, "site", text(manifest, "site", 120));
      }
      case permit -> {
        keys(manifest, "site", "activity");
        yield map("site", text(manifest, "site", 120), "activity", text(manifest, "activity", 300));
      }
    };
  }
}
