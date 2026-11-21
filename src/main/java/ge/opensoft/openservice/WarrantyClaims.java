package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class WarrantyClaims {
  private WarrantyClaims() {}

  static Object run(Store.Db db, String op, Map<String, Object> b) throws Exception {
    if (op.equals("my_claims")) {
      var claims = new ArrayList<>();
      for (var row : list(db, "warranty-claim")) {
        var claim = object(row.get("definition"));
        if (db.viewerId.equals(claim.get("dealer")))
          claims.add(
              map(
                  "reference",
                  claim.get("reference"),
                  "asset_id",
                  claim.get("asset"),
                  "serial",
                  object(claim.get("terms")).get("serial"),
                  "failure",
                  claim.get("failure"),
                  "state",
                  claim.get("state")));
      }
      return claims;
    }
    String asset = id(b, "asset_id");
    if (op.equals("issue_warranty")) {
      var hardware = AssetCustody.record(db, asset);
      String serial = text(object(hardware.get("data")), "serial_number", 160);
      String start = ProjectDelivery.date(b.get("start_on")),
          end = ProjectDelivery.date(b.get("finish_on"));
      require(start.compareTo(end) <= 0, "Invalid warranty interval");
      String dealer = id(b, "user_id"), reviewer = id(b, "owner_id"), finance = id(b, "finance_id");
      AgentPlans.account(db, dealer);
      DutyRosters.responder(db, reviewer);
      DutyRosters.responder(db, finance);
      require(
          Set.copyOf(List.of(dealer, reviewer, finance)).size() == 3,
          "Separate dealer, reviewer and finance roles");
      var previous = object(get(db, "warranty-register", asset).get("definition"));
      var terms = new ArrayList<>(objects(previous.getOrDefault("terms", List.of())));
      if (!terms.isEmpty())
        require(
            start.compareTo(terms.getLast().get("end").toString()) > 0, "Warranty periods overlap");
      terms.add(
          map(
              "start",
              start,
              "end",
              end,
              "serial",
              serial,
              "dealer",
              dealer,
              "reviewer",
              reviewer,
              "finance",
              finance,
              "currency",
              CloudSpend.currency(b),
              "limit",
              money(b.get("amount")).toPlainString()));
      return put(db, "warranty-register", asset, b.get("revision"), map("terms", terms));
    }
    String ref = text(b, "reference", 160), id = key(asset, ref);
    var row = get(db, "warranty-claim", id);
    var claim = object(row.get("definition"));
    if (op.equals("file_claim")) {
      String day = ProjectDelivery.date(b.get("effective"));
      String symptom = text(b, "note", 1200);
      require(
          day.compareTo(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()) <= 0,
          "Failure date is in the future");
      if (!claim.isEmpty()) {
        require(
            db.viewerId.equals(claim.get("dealer"))
                && day.equals(claim.get("failure"))
                && symptom.equals(claim.get("symptom")),
            "Claim identity conflict");
        return row;
      }
      var terms =
          objects(definition(db, "warranty-register", asset).get("terms")).stream()
              .filter(
                  v ->
                      db.viewerId.equals(v.get("dealer"))
                          && day.compareTo(v.get("start").toString()) >= 0
                          && day.compareTo(v.get("end").toString()) <= 0)
              .findFirst()
              .orElseThrow(() -> new Failure(403, "No eligible warranty"));
      String failureKey =
          key(asset, db.viewerId, day, symptom.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
      require(
          get(db, "warranty-failure", failureKey).get("revision").equals(0),
          "This dealer already reported the same failure under another reference");
      save(db, "warranty-failure", failureKey, 0, map("claim", id));
      return put(
          db,
          "warranty-claim",
          id,
          0,
          map(
              "dealer",
              db.viewerId,
              "terms",
              terms,
              "reference",
              ref,
              "asset",
              asset,
              "failure",
              day,
              "symptom",
              symptom,
              "state",
              "filed"));
    }
    var terms = object(claim.getOrDefault("terms", map()));
    require(
        List.of("dealer", "reviewer", "finance").stream()
            .anyMatch(k -> db.viewerId.equals(terms.get(k))),
        "Claim unavailable");
    if (op.equals("claim_status")) return row;
    if (op.equals("authorize_repair")) {
      require(
          db.viewerId.equals(terms.get("reviewer")) && "filed".equals(claim.get("state")),
          "Reviewer authorization required");
      claim.put("authorized_custody_revision", AssetCustody.latest(db, asset).get("revision"));
      claim.putAll(map("authorization", text(b, "note", 600), "state", "authorized"));
    } else if (op.equals("submit_repair_cost")) {
      require(
          db.viewerId.equals(terms.get("dealer")) && "authorized".equals(claim.get("state")),
          "Authorized dealer repair required");
      require(asset.equals(b.get("returned_asset")), "Return the registered serialized component");
      var parts = money(b.get("parts"));
      var labor = money(b.get("labor"));
      claim.putAll(
          map(
              "parts",
              parts.toPlainString(),
              "labor",
              labor.toPlainString(),
              "total",
              parts.add(labor).toPlainString(),
              "returned_asset",
              id(b, "returned_asset"),
              "state",
              "submitted"));
    } else if (op.equals("adjudicate_claim")) {
      require(
          db.viewerId.equals(terms.get("reviewer")) && "submitted".equals(claim.get("state")),
          "Submitted repair required");
      var amount = money(b.get("amount"));
      require(
          amount.compareTo(money(claim.get("total"))) <= 0
              && amount.compareTo(money(terms.get("limit"))) <= 0,
          "Credit exceeds claim or warranty");
      String returned = claim.get("returned_asset").toString();
      AssetCustody.record(db, returned);
      var movement = AssetCustody.latest(db, returned);
      require(
          "in_stock".equals(movement.get("state"))
              && "return".equals(object(movement.get("detail")).get("operation"))
              && ((Number) movement.get("revision")).intValue()
                  > ((Number) claim.get("authorized_custody_revision")).intValue(),
          "Receive failed component before approval");
      save(db, "warranty-return", key(returned, movement.get("revision")), 0, map("claim", id));
      claim.putAll(
          map(
              "approved",
              amount.toPlainString(),
              "reason",
              text(b, "note", 1200),
              "return_revision",
              movement.get("revision"),
              "state",
              "approved"));
    } else {
      require(db.viewerId.equals(terms.get("finance")), "Assigned finance owner required");
      if ("credited".equals(claim.get("state"))) return row;
      require("approved".equals(claim.get("state")), "Adjudication required");
      save(
          db,
          "dealer-credit",
          id,
          0,
          map(
              "dealer",
              claim.get("dealer"),
              "amount",
              claim.get("approved"),
              "currency",
              terms.get("currency"),
              "issued",
              now(),
              "by",
              db.viewerId));
      claim.putAll(map("credit", id, "state", "credited"));
    }
    return put(db, "warranty-claim", id, b.get("revision"), claim);
  }
}
