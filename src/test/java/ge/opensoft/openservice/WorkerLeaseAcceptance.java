package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseExpansionAcceptance.cmd;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class WorkerLeaseAcceptance {
  private WorkerLeaseAcceptance() {}

  static void manual(WorkflowSupport h, boolean usingHistory) throws Exception {
    try (var endpoint = new ConnectorActionsAcceptance.Endpoint(h)) {
      var settings = endpoint.provider.settings;
      settings.put("CONNECTOR_WORKER_ID", "primary");
      String action = ConnectorActionsAcceptance.create(h, endpoint.provider.alias);
      cmd(
          h,
          "admin",
          "packages",
          "history_contract",
          "action_id",
          action,
          "alias",
          endpoint.provider.alias,
          "path",
          "/audit",
          "revision",
          0);
      String job =
          ConnectorActionsAcceptance.queue(
              h, action, ConnectorActionsAcceptance.preview(h, action, id()));
      endpoint.mode.set("close");
      assertTrue(new WorkWorker(h.store).runOne(System.currentTimeMillis() + 1000));
      endpoint.provider.server.createContext(
          "/audit/" + job,
          exchange -> {
            var response =
                JSON.writeValueAsBytes(
                    map(
                        "complete",
                        true,
                        "correlation_id",
                        job,
                        "events",
                        List.of(map("correlation_id", job, "event_id", "audit-123"))));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
          });
      if (usingHistory) {
        cmd(h, "admin", "packages", "verify_effect", "job_id", job);
      } else {
        try (var db = h.store.open()) {
          PlatformFoundationTest.bind(db);
          ConnectorWrites.reconcile(
              db, job, map("decision", "applied", "note", "Provider operator verified the effect"));
          db.connection.commit();
        }
      }
      try (var db = h.store.open()) {
        db.update(
            "UPDATE os_user SET auth_revision=auth_revision+1 WHERE user_id="
                + "(SELECT owner_id FROM os_work WHERE job_id=?)",
            job);
        db.connection.commit();
      }
      settings.put("CONNECTOR_WORKER_ID", "backup");
      assertTrue(ConnectorFailover.recover(h.store, System.currentTimeMillis()));
      assertFalse(ConnectorFailover.recover(h.store, System.currentTimeMillis() + 61000));
      try (var db = h.store.open()) {
        assertEquals(
            "manual",
            db.one("SELECT recovered_by FROM os_connector_worker WHERE job_id=?", job)
                .get("recovered_by"));
      }
      assertEquals(1, endpoint.calls.get());
    }
  }

  static void run(WorkflowSupport h) throws Exception {
    try (var provider = new ConnectorActionsAcceptance.Endpoint(h)) {
      var settings = provider.provider.settings;
      settings.put("CONNECTOR_WORKER_ID", "private-a");
      String action = ConnectorActionsAcceptance.create(h, provider.provider.alias);
      cmd(
          h,
          "admin",
          "packages",
          "history_contract",
          "action_id",
          action,
          "alias",
          provider.provider.alias,
          "path",
          "/audit",
          "revision",
          0);
      String job =
          ConnectorActionsAcceptance.queue(
              h, action, ConnectorActionsAcceptance.preview(h, action, id()));
      var complete = new AtomicBoolean(false);
      provider.provider.server.createContext(
          "/audit/" + job,
          x -> {
            var records = new ArrayList<>();
            for (int i = 0; i < provider.calls.get(); i++)
              records.add(map("event_id", "write-" + i, "correlation_id", job));
            var evidence =
                JSON.writeValueAsBytes(
                    map("correlation_id", job, "complete", complete.get(), "events", records));
            x.sendResponseHeaders(200, evidence.length);
            x.getResponseBody().write(evidence);
            x.close();
          });
      long at = System.currentTimeMillis() + 1000;
      Map<String, Object> leased;
      try (var db = h.store.open()) {
        leased = WorkJobs.claim(db, at);
        assertEquals(job, leased.get("job_id"));
        ConnectorFailover.claim(h.store, db, leased);
        db.connection.commit();
      }
      provider.mode.set("close");
      try (var db = h.store.open()) {
        PlatformFoundationTest.bind(db);
        var payload = Outbox.decrypt(h.store.secret, leased.get("envelope").toString());
        assertThrows(Failure.class, () -> ConnectorWrites.execute(h.store, db, leased, payload));
      }
      // The remote effect survived. No terminal status was committed on the original connection.
      settings.put("CONNECTOR_WORKER_ID", "private-b");
      long takeover = at + 121000;
      assertTrue(new WorkWorker(h.store).runOne(takeover));
      assertEquals(1, provider.calls.get());
      assertTrue(ConnectorFailover.recover(h.store, takeover));
      assertFalse(ConnectorFailover.recover(h.store, takeover + 1));
      try (var db = h.store.open()) {
        var row =
            db.one(
                "SELECT executor,recovered_by,retry_at FROM os_connector_worker WHERE job_id=?",
                job);
        assertEquals("private-a", row.get("executor"));
        assertEquals("", row.get("recovered_by"));
        assertTrue(((Number) row.get("retry_at")).longValue() > takeover);
      }
      complete.set(true);
      try (var db = h.store.open()) {
        db.update(
            "UPDATE os_user SET active=0 WHERE user_id=(SELECT owner_id FROM os_work WHERE"
                + " job_id=?)",
            job);
        db.connection.commit();
      }
      assertTrue(ConnectorFailover.recover(h.store, takeover + 61000));
      try (var db = h.store.open()) {
        assertEquals(
            "",
            db.one("SELECT recovered_by FROM os_connector_worker WHERE job_id=?", job)
                .get("recovered_by"));
        db.update(
            "UPDATE os_user SET active=1 WHERE user_id=(SELECT owner_id FROM os_work WHERE"
                + " job_id=?)",
            job);
        db.connection.commit();
      }
      assertTrue(ConnectorFailover.recover(h.store, takeover + 122000));
      var result = cmd(h, "admin", "packages", "worker_evidence", "job_id", job);
      assertEquals("private-b", object(result.get("worker")).get("recovered_by"));
      assertEquals(
          "write-0", object(object(result.get("evidence")).get("definition")).get("event"));
      assertEquals(1, provider.calls.get());
    }
  }
}
