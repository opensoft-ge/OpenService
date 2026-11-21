package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class ProviderHistory {
  private ProviderHistory() {}

  static Object run(Store store, Store.Db db, String op, Map<String, Object> b) throws Exception {
    if (op.equals("history_contract")) {
      String action = id(b, "action_id");
      var spec = ConnectorActions.find(db, action);
      String path = Connectors.path(text(b, "path", 400));
      require(!path.contains("?") && !path.endsWith("/"), "Choose a history collection path");
      String alias = text(b, "alias", 80);
      var connection = ConnectorConsole.page(db, alias);
      require(
          connection
              .get("origin")
              .equals(ConnectorConsole.page(db, spec.get("alias").toString()).get("origin")),
          "History must use the write provider");
      return put(
          db,
          "history-contract",
          action,
          b.get("revision"),
          map(
              "alias",
              alias,
              "origin",
              connection.get("origin"),
              "path",
              path,
              "action_revision",
              spec.get("revision")));
    }
    String job = id(b, "job_id");
    var contract = definition(db, "job-history-contract", job);
    var prior = get(db, "job-history-evidence", job);
    if (!prior.get("revision").equals(0)) return prior;
    var terminal = object(ConnectorWrites.reconciliation(db, job).get("job"));
    require(
        Set.of("succeeded", "failed", "cancelled").contains(terminal.get("state")),
        "Wait for the write to stop");
    String alias = contract.get("alias").toString();
    require(
        contract.get("origin").equals(ConnectorConsole.page(db, alias).get("origin")),
        "History provider changed");
    var response =
        Connectors.fetch(store, db, map("alias", alias, "path", contract.get("path") + "/" + job));
    byte[] bytes = (byte[]) response.get("content");
    var result = object(JSON.readValue(bytes, Object.class));
    require(
        Boolean.TRUE.equals(result.get("complete")) && job.equals(result.get("correlation_id")),
        "Incomplete provider history");
    var ids = new TreeSet<String>();
    for (Object raw : batch(result.get("events"), 100)) {
      var event = object(raw);
      require(job.equals(event.get("correlation_id")), "Unrelated provider event");
      ids.add(text(event, "event_id", 160));
    }
    require(ids.size() == 1, "Exactly one provider effect must be established");
    String note = "Provider event " + ids.first() + " for " + job;
    ConnectorWrites.reconcile(db, job, map("decision", "applied", "note", note));
    return put(
        db,
        "job-history-evidence",
        job,
        0,
        map(
            "event",
            ids.first(),
            "verified",
            now(),
            "origin",
            contract.get("origin"),
            "sha256",
            org.apache.commons.codec.digest.DigestUtils.sha256Hex(bytes),
            "connection_revision",
            response.get("revision")));
  }

  static void pin(Store.Db db, String action, String job) throws Exception {
    var row = get(db, "history-contract", action);
    var binding = object(row.get("definition"));
    if (binding.isEmpty() || !get(db, "job-history-contract", job).get("revision").equals(0))
      return;
    ProblemInvestigations.revision(
        binding.get("action_revision"), ConnectorActions.find(db, action).get("revision"));
    var snapshot = new LinkedHashMap<>(binding);
    String writeAlias=ConnectorActions.find(db,action).get("alias").toString();
    snapshot.put("write_alias",writeAlias);
    snapshot.put("write_revision",ConnectorConsole.page(db,writeAlias).get("revision"));
    save(db, "job-history-contract", job, 0, snapshot);
  }
}
