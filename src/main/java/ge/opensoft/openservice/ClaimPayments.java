package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.math.BigDecimal;
import java.util.*;

final class ClaimPayments {
  private ClaimPayments() {}

  static String currency(Map<String, Object> b) {
    String value = text(b, "currency", 3);
    require(value.matches("[A-Z]{3}"), "Use a three-letter currency");
    return value;
  }

  static Map<String, Object> loss(Store.Db db, Map<String, Object> input) throws Exception {
    keys(input, "policy_id", "loss_date", "peril", "amount", "currency", "reason");
    String policy = id(input, "policy_id");
    var spec = object(get(db, "insured-coverage", policy).get("definition"));
    if (!db.viewerId.equals(spec.get("holder"))) throw new Failure(404, "Policy unavailable");
    String day = ProjectDelivery.date(input.get("loss_date")), cause = text(input, "peril", 80);
    var amount = money(input.get("amount"));
    require(
        day.compareTo(spec.get("start").toString()) >= 0
            && day.compareTo(spec.get("end").toString()) <= 0,
        "Loss is outside the insured period");
    require(
        ((List<?>) spec.get("perils")).contains(cause)
            && currency(input).equals(spec.get("currency"))
            && amount.signum() > 0,
        "Loss does not match the recorded coverage");
    var benefit =
        amount
            .subtract(money(spec.get("deductible")))
            .max(BigDecimal.ZERO)
            .min(money(spec.get("limit")))
            .setScale(2);
    return map(
        "policy_id",
        policy,
        "loss_date",
        day,
        "peril",
        cause,
        "amount",
        amount.toPlainString(),
        "deductible",
        spec.get("deductible"),
        "limit",
        spec.get("limit"),
        "payable",
        benefit.toPlainString(),
        "currency",
        spec.get("currency"),
        "reason",
        text(input, "reason", 400));
  }

  static Object run(Store.Db db, String op, Map<String, Object> b) throws Exception {
    if (op.equals("register_claim_policy")) {
      String policy = id(b, "policy_id"), holder = id(b, "user_id");
      Service.account(db, holder);
      var terms = object(b.get("manifest"));
      keys(terms, "start", "end", "perils", "currency", "deductible", "limit");
      String first = ProjectDelivery.date(terms.get("start")),
          last = ProjectDelivery.date(terms.get("end"));
      var perils = new TreeSet<String>();
      for (Object peril : batch(terms.get("perils"), 32)) perils.add(WorkJobs.text(peril, 80));
      var limit = money(terms.get("limit"));
      require(
          first.compareTo(last) <= 0 && !perils.isEmpty() && limit.signum() > 0,
          "Choose a covered period, perils and positive limit");
      return ServiceAssurance.once(
          db,
          "insured-coverage",
          policy,
          map(
              "holder",
              holder,
              "start",
              first,
              "end",
              last,
              "perils",
              List.copyOf(perils),
              "currency",
              currency(terms),
              "deductible",
              money(terms.get("deductible")).toPlainString(),
              "limit",
              limit.toPlainString()));
    }
    String source = text(b, "source", 120),
        ref = text(b, "reference", 120),
        entry = key(source, ref);
    if (op.equals("import_case_payment")) {
      var data = object(b.get("manifest"));
      keys(data, "user_id", "currency", "amount", "effective", "case_id");
      String user = id(data, "user_id");
      Service.account(db, user);
      var amount = money(data.get("amount"));
      require(amount.signum() > 0, "Import a positive posted payment");
      return ServiceAssurance.once(
          db,
          "case-payment-entry",
          entry,
          map(
              "source",
              source,
              "reference",
              ref,
              "holder",
              user,
              "case",
              id(data, "case_id"),
              "currency",
              currency(data),
              "amount",
              amount.toPlainString(),
              "effective",
              ProjectDelivery.date(data.get("effective"))));
    }
    String caseId = id(b, "case_id");
    var row = get(db, "industry-case", caseId);
    var claim = definition(db, "industry-case", caseId);
    var posted = definition(db, "case-payment-entry", entry);
    var adjustment = object(claim.getOrDefault("adjustment", map()));
    require(
        !db.viewerId.equals(claim.get("reviewer")) && !db.viewerId.equals(claim.get("requester")),
        "Independent finance reviewer required");
    require(
        caseId.equals(posted.get("case"))
            && claim.get("requester").equals(posted.get("holder"))
            && posted.get("amount").equals(adjustment.get("amount"))
            && posted.get("currency").equals(adjustment.get("currency")),
        "Posted statement does not match the approved case payment");
    if (claim.containsKey("settlement")) {
      require(
          entry.equals(object(claim.get("settlement")).get("entry")),
          "A different payment is already reconciled");
      return row;
    }
    claim.put(
        "settlement",
        map(
            "entry",
            entry,
            "source",
            source,
            "reference",
            ref,
            "effective",
            posted.get("effective"),
            "actor",
            db.viewerId,
            "recorded",
            now(),
            "status",
            "matched_to_statement"));
    return put(db, "industry-case", caseId, b.get("revision"), claim);
  }
}
