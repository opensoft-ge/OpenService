package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

final class SupplierContracts {
  private SupplierContracts() {}

  static Map<String, Object> terms(Store.Db db, String supplier, String day) throws Exception {
    var row =
        object(
            get(db, "supplier-agreement", SupplierGovernance.identity(supplier)).get("definition"));
    Map<String, Object> effective = map();
    for (var item : objects(row.getOrDefault("signed", List.of())))
      if (item.get("effective").toString().compareTo(day) <= 0) effective = item;
    return effective;
  }

  static void validateOrder(Store.Db db, Map<String, Object> order, String day) throws Exception {
    var id = SupplierGovernance.identity(order.get("supplier"));
    var contract = object(get(db, "supplier-agreement", id).get("definition"));
    var signed = terms(db, order.get("supplier").toString(), day);
    if (signed.isEmpty()) {
      require(
          objects(contract.getOrDefault("signed", List.of())).isEmpty(),
          "Signed contract is not yet effective");
      return;
    }
    var notice = object(contract.getOrDefault("decision", map()));
    require(
        !("terminate".equals(notice.get("kind"))
            && signed.get("version").equals(notice.get("version"))
            && day.compareTo(notice.get("effective").toString()) >= 0),
        "The supplier contract was terminated");
    for (var entry : ProjectSpend.lines(order).entrySet()) {
      Object price = object(signed.get("prices")).get(entry.getKey());
      if (price != null)
        require(
            signed.get("currency").equals(order.get("currency"))
                && money(price).equals(money(object(entry.getValue()).get("unit_cost"))),
            "Purchase price differs from signed terms");
    }
    order.put("signed_terms", signed);
  }

  static void match(Store.Db db, Map<String, Object> invoice, String day) throws Exception {
    var signed = terms(db, invoice.get("supplier").toString(), day);
    if (signed.isEmpty()) return;
    invoice.put("contract_terms", signed);
    invoice.put("pricing_date", day);
    for (var entry : object(invoice.get("lines")).entrySet()) {
      var price = object(signed.get("prices")).get(entry.getKey());
      if (price != null && signed.get("currency").equals(invoice.get("currency"))) {
        var line = object(entry.getValue());
        line.put("contract_unit_cost", price);
        line.put(
            "contract_unit_variance",
            money(line.get("unit_cost")).subtract(money(price)).toPlainString());
      }
    }
  }

  static Object command(Store.Db db, String op, Map<String, Object> b) throws Exception {
    String supplier = text(b, "supplier", 160), id = SupplierGovernance.identity(supplier);
    var managed = definition(db, "managed-supplier", id);
    var data = object(get(db, "supplier-agreement", id).get("definition"));
    var proposal = object(data.getOrDefault("proposal", map()));
    if (op.equals("supplier_offer")) {
      require(db.viewerId.equals(proposal.get("contact")), "Assigned supplier contact required");
      return map(
          "proposal", proposal, "revision", get(db, "supplier-agreement", id).get("revision"));
    }
    if (op.equals("offer_terms")) {
      require(db.viewerId.equals(managed.get("legal")), "Assigned legal owner required");
      var assessment = object(managed.getOrDefault("assessment", map()));
      require(
          "pass".equals(assessment.get("decision"))
              && SupplierGovernance.valid(assessment.get("expiry")),
          "Current supplier approval required");
      var prices = map();
      for (var entry : object(b.get("manifest")).entrySet())
        prices.put(WorkJobs.text(entry.getKey(), 160), money(entry.getValue()).toPlainString());
      require(!prices.isEmpty(), "Provide contract prices");
      String effective = ProjectDelivery.date(b.get("effective")),
          notice = ProjectDelivery.date(b.get("notice"));
      var previous = objects(data.getOrDefault("signed", List.of()));
      require(
          previous.isEmpty()
              || effective.compareTo(previous.getLast().get("effective").toString()) > 0,
          "Amendments must start after the prior version");
      String contact = id(b, "user_id");
      AgentPlans.account(db, contact);
      require(
          !Set.of(managed.get("legal"), managed.get("finance")).contains(contact),
          "Choose an independent supplier contact");
      data.put(
          "proposal",
          map(
              "version",
              ((Number) b.get("revision")).intValue() + 1,
              "prices",
              prices,
              "currency",
              CloudSpend.currency(b),
              "effective",
              effective,
              "notice",
              notice,
              "obligations",
              text(b, "note", 2000),
              "contact",
              contact,
              "legal",
              db.viewerId,
              "state",
              "offered"));
    } else if (op.equals("supplier_response")) {
      require(
          db.viewerId.equals(proposal.get("contact")) && "offered".equals(proposal.get("state")),
          "Choose your pending supplier offer");
      proposal.put("state", choice(b, "decision", "accepted", "changes_requested"));
      proposal.put("response", text(b, "note", 2000));
      proposal.put("responded_at", now());
    } else if (op.equals("sign_terms")) {
      require(
          db.viewerId.equals(managed.get("finance")) && "accepted".equals(proposal.get("state")),
          "Finance must approve the supplier-accepted version");
      proposal.putAll(
          map(
              "state",
              "signed",
              "finance",
              db.viewerId,
              "signed_at",
              now(),
              "signature_reference",
              text(b, "reference", 400)));
      var signed = new ArrayList<>(objects(data.getOrDefault("signed", List.of())));
      signed.add(new LinkedHashMap<>(proposal));
      data.put("signed", signed);
      managed.put(
          "contract",
          map(
              "reference",
              proposal.get("signature_reference"),
              "note",
              proposal.get("obligations"),
              "version",
              proposal.get("version"),
              "by",
              db.viewerId));
      save(db, "managed-supplier", id, get(db, "managed-supplier", id).get("revision"), managed);
    } else if (op.equals("contract_decision")) {
      require(
          db.viewerId.equals(managed.get("legal"))
              && !objects(data.getOrDefault("signed", List.of())).isEmpty(),
          "Choose a signed agreement you own");
      var current = objects(data.get("signed")).getLast();
      require(
          !LocalDate.now(ZoneOffset.UTC).isAfter(LocalDate.parse(current.get("notice").toString())),
          "Notice deadline passed");
      String effective = ProjectDelivery.date(b.get("effective"));
      require(
          effective.compareTo(LocalDate.now(ZoneOffset.UTC).toString()) >= 0,
          "Choose a current or future decision date");
      data.put(
          "decision",
          map(
              "effective",
              effective,
              "kind",
              choice(b, "decision", "renew", "terminate"),
              "by",
              db.viewerId,
              "at",
              now(),
              "version",
              current.get("version"),
              "note",
              text(b, "note", 1200)));
    } else {
      require(
          "admin".equals(db.viewerRole) || db.viewerId.equals(managed.get("legal")),
          "Contract review access required");
      var history = new ArrayList<>();
      int count = ((Number) get(db, "supplier-agreement", id).get("revision")).intValue();
      for (int version = 0; version < count; version++)
        history.add(definition(db, "contract-history", key(id, version)));
      return map("agreement", data, "history", history);
    }
    save(db, "supplier-agreement", id, b.get("revision"), data);
    save(
        db,
        "contract-history",
        key(id, b.get("revision")),
        0,
        map(
            "operation",
            op,
            "by",
            db.viewerId,
            "at",
            now(),
            "proposal",
            data.get("proposal"),
            "decision",
            data.getOrDefault("decision", map())));
    if (op.equals("supplier_response")) return map("state", proposal.get("state"));
    return data;
  }
}
