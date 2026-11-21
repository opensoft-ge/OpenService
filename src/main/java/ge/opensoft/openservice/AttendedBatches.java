package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class AttendedBatches {
  private AttendedBatches() {}

  static Object run(Store.Db db, String operation, Map<String, Object> b) throws Exception {
    String id = id(b, "new_id"), batchKey = key(db.viewerId, id);
    if (operation.equals("open_attended_batch")) {
      var rows = new ArrayList<>();
      var references = new HashSet<String>();
      for (Object raw : batch(b.get("entries"), 100)) {
        var row = object(raw);
        String kind = choice(row, "kind", "dispute", "turnaround", "permit"),
            reference = text(row, "reference", 120);
        definition(db, "case-program", kind);
        String receipt = key(kind, db.viewerId, reference);
        require(references.add(receipt), "Duplicate case reference in batch");
        var details = IndustryCases.details(db, kind, object(row.get("manifest")));
        rows.add(
            map(
                "kind",
                kind,
                "reference",
                reference,
                "receipt",
                receipt,
                "digest",
                ConnectorSynchronization.hash(new TreeMap<>(details))));
      }
      require(!rows.isEmpty(), "Supply at least one case row");
      ServiceAssurance.once(db, "attended-batch", batchKey, map("rows", rows));
    }
    var rows = objects(definition(db, "attended-batch", batchKey).get("rows"));
    var status = new ArrayList<>();
    int remaining = 0, conflicts = 0;
    for (var row : rows) {
      var receipt =
          object(get(db, "industry-submission", row.get("receipt").toString()).get("definition"));
      var result = map("kind", row.get("kind"), "reference", row.get("reference"));
      if (receipt.isEmpty()) {
        result.put("status", "pending");
        remaining++;
      } else if (row.get("digest")
          .equals(ConnectorSynchronization.hash(new TreeMap<>(object(receipt.get("details")))))) {
        result.putAll(map("status", "received", "case", receipt.get("case")));
      } else {
        result.put("status", "conflict");
        conflicts++;
      }
      status.add(result);
    }
    return map(
        "batch",
        id,
        "received",
        rows.size() - remaining - conflicts,
        "pending",
        remaining,
        "conflicts",
        conflicts,
        "rows",
        status);
  }
}
