package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseExpansionAcceptance.cmd;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.auth;
import static org.junit.jupiter.api.Assertions.*;

final class ProviderRecoveryAcceptance {
  private ProviderRecoveryAcceptance() {}

  static void awaitJournal(WorkflowSupport h, String job, boolean recovered) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      try (var db = h.store.open()) {
        var row =
            db.one(
                "SELECT w.state,j.recovered_by FROM os_work w JOIN os_connector_worker j ON"
                    + " j.job_id=w.job_id WHERE w.job_id=?",
                job);
        if (row != null
            && (recovered
                ? "worker-west".equals(row.get("recovered_by"))
                : "failed".equals(row.get("state")))) return;
      }
      Thread.sleep(100);
    }
    fail("Timed out waiting for the worker journal");
  }

  static void run(WorkflowSupport h) throws Exception {
    h.account("PackageAuthor", "admin");
    try (var endpoint = new ConnectorActionsAcceptance.Endpoint(h)) {
      var alias = endpoint.provider.alias;
      var spec = ConnectorActionsAcceptance.spec(alias, 0, "POST");
      spec.put("name", "Dispatch replacement unit");
      String action =
          ConnectorActionsAcceptance.result(
                  h.json("POST", ConnectorActionsAcceptance.API, spec, auth("PackageAuthor")), 201)
              .get("action_id")
              .toString();
      cmd(
          h,
          "PackageAuthor",
          "packages",
          "history_contract",
          "action_id",
          action,
          "alias",
          alias,
          "path",
          "/history",
          "revision",
          0);
      for (String invalid : java.util.List.of("/history/", "/history?unscoped=true"))
        EnterpriseExpansionAcceptance.denied(
            h,
            "admin",
            "packages",
            "history_contract",
            "action_id",
            action,
            "alias",
            alias,
            "path",
            invalid,
            "revision",
            1);
      var auditCredential = new java.util.concurrent.atomic.AtomicReference<String>();
      var history = new java.util.concurrent.atomic.AtomicReference<>("unavailable");
      endpoint.provider.server.createContext(
          "/history",
          x -> {
            auditCredential.set(x.getRequestHeaders().getFirst("Authorization"));
            String job = endpoint.key.get();
            var entries = new java.util.ArrayList<>();
            for (int i = 0; i < endpoint.calls.get(); i++)
              entries.add(map("event_id", "effect-" + i, "correlation_id", job));
            if (history.get().equals("extra"))
              entries.add(map("event_id", "unexpected", "correlation_id", job));
            if (history.get().equals("foreign"))
              entries.add(map("event_id", "foreign", "correlation_id", id()));
            var bytes =
                JSON.writeValueAsBytes(
                    map(
                        "complete",
                        !history.get().equals("unavailable"),
                        "correlation_id",
                        history.get().equals("unrelated") ? id() : job,
                        "events",
                        entries));
            x.sendResponseHeaders(200, bytes.length);
            x.getResponseBody().write(bytes);
            x.close();
          });
      var preview = ConnectorActionsAcceptance.preview(h, action, id());
      String job = ConnectorActionsAcceptance.queue(h, action, preview);
      try (var db = h.store.open()) {
        PlatformFoundationTest.bind(db);
        ProviderHistory.pin(db, action, job);
        assertEquals(1, EnterpriseData.get(db, "job-history-contract", job).get("revision"));
      }
      endpoint.provider.settings.put("CONNECTOR_WORKER_ID", "worker-east");
      endpoint.mode.set("close");
      try (var east =
          new ConnectorRestartQualification(h, endpoint.provider.origin(), "worker-east")) {
        awaitJournal(h, job, false);
      }
      assertEquals(1, endpoint.calls.get());
      try (var db = h.store.open()) {
        String actor = PlatformFoundationTest.bind(db);
        Connectors.save(
            h.store,
            db,
            actor,
            map(
                "alias",
                alias,
                "origin",
                endpoint.provider.origin(),
                "token",
                "rotated-history-fixture"));
        db.connection.commit();
      }
      var restarted = new Store(h.environment);
      new WorkWorker(restarted).runOne(System.currentTimeMillis() + 60000);
      assertEquals(1, endpoint.calls.get());
      EnterpriseExpansionAcceptance.denied(h, "admin", "packages", "verify_effect", "job_id", job);
      for (String invalid : java.util.List.of("unrelated", "foreign", "extra")) {
        history.set(invalid);
        EnterpriseExpansionAcceptance.denied(
            h, "admin", "packages", "verify_effect", "job_id", job);
      }
      assertEquals("Bearer rotated-history-fixture", auditCredential.get());
      history.set("complete");
      assertFalse(ConnectorFailover.recover(restarted, System.currentTimeMillis()));
      endpoint.provider.settings.put("CONNECTOR_WORKER_ID", "worker-west");
      try (var west =
          new ConnectorRestartQualification(h, endpoint.provider.origin(), "worker-west")) {
        awaitJournal(h, job, true);
      }
      assertFalse(ConnectorFailover.recover(restarted, System.currentTimeMillis()));
      var journal = cmd(h, "admin", "packages", "worker_evidence", "job_id", job);
      assertEquals("worker-east", object(journal.get("worker")).get("executor"));
      assertEquals("worker-west", object(journal.get("worker")).get("recovered_by"));
      assertEquals(1, object(journal.get("contract")).get("write_revision"));
      for (int i = 0; i < 2; i++) {
        var result = cmd(h, "admin", "packages", "verify_effect", "job_id", job);
        assertEquals("effect-0", object(result.get("definition")).get("event"));
      }
      assertEquals(1, endpoint.calls.get());
      try (var db = h.store.open()) {
        assertEquals(
            "applied",
            db.one("SELECT decision FROM os_connector_resolution WHERE job_id=?", job)
                .get("decision"));
      }
    }
  }
}
