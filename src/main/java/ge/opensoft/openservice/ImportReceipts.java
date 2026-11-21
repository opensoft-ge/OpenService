package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

/** The provider cursor identifies an immutable change even when successive pages overlap. */
final class ImportReceipts {
  private ImportReceipts() {}

  static String binding(Map<String, Object> body) {
    return key(id(body, "package_id"), choice(body, "environment", "test", "production"));
  }

  record Receipt(String cursor, boolean inserted) {}

  static Receipt accept(
      Store.Db db, String binding, String field, Map<String, Object> outputs, Object raw)
      throws Exception {
    String cursor = text(object(raw), field, 200), receipt = key(binding, cursor);
    String state = "accepted", error = "";
    Map<String, Object> record;
    try {
      record = RestPackages.project(outputs, raw);
    } catch (Failure invalid) {
      state = "rejected";
      error = invalid.getMessage();
      record = object(raw);
    }
    String payload = JSON.writeValueAsString(record);
    int inserted =
        db.update(
            "INSERT INTO"
                + " os_import_receipt(receipt,binding,cursor_value,state,payload,source_payload,error)"
                + " VALUES(?,?,?,?,?,?,?) ON CONFLICT(receipt) DO NOTHING",
            receipt,
            binding,
            cursor,
            state,
            payload,
            JSON.writeValueAsString(raw),
            error);
    if (inserted == 0) {
      var prior =
          db.one(
              "SELECT payload AS data,source_payload AS original FROM os_import_receipt WHERE"
                  + " receipt=?",
              receipt);
      require(
          object(prior.get("original")).equals(raw) || object(prior.get("data")).equals(record),
          "Provider reused a cursor for different data");
    }
    return new Receipt(cursor, inserted == 1);
  }

  static Object read(Store.Db db, Map<String, Object> body) throws Exception {
    var row =
        db.one(
            "SELECT payload AS data,state FROM os_import_receipt WHERE receipt=?",
            key(binding(body), text(body, "cursor", 200)));
    require(row != null && "accepted".equals(row.get("state")), "Accepted record is unavailable");
    return object(row.get("data"));
  }

  static Object rejected(Store.Db db, Map<String, Object> body) throws Exception {
    return db.all(
        "SELECT cursor_value,payload,error FROM os_import_receipt WHERE binding=? AND"
            + " state='rejected' ORDER BY cursor_value LIMIT 250",
        binding(body));
  }

  static Object repair(Store.Db db, Map<String, Object> body) throws Exception {
    String binding = binding(body), cursor = text(body, "cursor", 200);
    var schedule = definition(db, "incremental-import", binding);
    var install = get(db, "rest-binding", binding);
    ProblemInvestigations.revision(schedule.get("binding_revision"), install.get("revision"));
    var spec = object(object(install.get("definition")).get("spec"));
    var corrected = RestPackages.project(object(spec.get("outputs")), body.get("manifest"));
    require(cursor.equals(corrected.get(schedule.get("field"))), "Preserve the rejected cursor");
    int changed =
        db.update(
            "UPDATE os_import_receipt SET payload=?,state='accepted',error='' WHERE receipt=? AND"
                + " state='rejected'",
            JSON.writeValueAsString(corrected),
            key(binding, cursor));
    require(changed == 1, "Choose a rejected change");
    SecurityEvents.record(
        db,
        db.viewerId,
        db.viewerId,
        "import.corrected",
        map("binding", binding, "cursor", cursor));
    return corrected;
  }
}
