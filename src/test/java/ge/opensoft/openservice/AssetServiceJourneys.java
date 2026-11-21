package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseAcceptance.uid;
import static ge.opensoft.openservice.EnterpriseExpansionAcceptance.*;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;

final class AssetServiceJourneys {
  static void certificate(WorkflowSupport h) throws Exception {
    h.account("CertOwner", "agent");
    var proc =
        new ProcessBuilder("python3", "scripts/test-certificate-deployment.py")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    var fixture = object(JSON.readValue(proc.getInputStream().readAllBytes(), Object.class));
    assertEquals(0, proc.waitFor());
    var urls = (List<?>) fixture.get("endpoints");
    String old = ((List<?>) fixture.get("initial")).getFirst().toString(),
        fresh = ((List<?>) fixture.get("complete")).getFirst().toString();
    String fingerprint =
        CertificateRenewals.certificate(map("certificate", old)).get("sha256").toString();
    String ci = h.add("cmdb_ci").get("sys_id").toString(), owner = uid(h, "CertOwner");
    for (String source : List.of("cloud", "inventory"))
      for (Object url : urls)
        cmd(
            h,
            "admin",
            "mapping",
            "discover_certificate",
            "certificate",
            old,
            "endpoint",
            url,
            "source",
            source,
            "service_ci",
            ci,
            "owner_id",
            owner);
    cmd(
        h,
        "CertOwner",
        "mapping",
        "renew_certificate",
        "fingerprint",
        fingerprint,
        "certificate",
        fresh,
        "revision",
        4);
    var partial = (List<?>) fixture.get("partial");
    for (int i = 0; i < 2; i++)
      cmd(
          h,
          "admin",
          "mapping",
          "verify_certificate",
          "fingerprint",
          fingerprint,
          "endpoint",
          urls.get(i),
          "certificate",
          partial.get(i),
          "observed_at",
          Instant.ofEpochMilli(System.currentTimeMillis()).toString(),
          "revision",
          5 + i);
    assertEquals(
        "verifying",
        object(
                cmd(h, "admin", "mapping", "certificate_status", "fingerprint", fingerprint)
                    .get("definition"))
            .get("state"));
    var closed =
        object(
            cmd(
                    h,
                    "admin",
                    "mapping",
                    "verify_certificate",
                    "fingerprint",
                    fingerprint,
                    "endpoint",
                    urls.get(1),
                    "certificate",
                    fresh,
                    "observed_at",
                    Instant.ofEpochMilli(System.currentTimeMillis()).toString(),
                    "revision",
                    7)
                .get("definition"));
    assertEquals("verified", closed.get("state"));
    try (var db = h.store.open()) {
      assertEquals(
          "complete",
          object(Service.record(db, "sc_request", closed.get("request").toString()).get("data"))
              .get("state"));
    }
  }

  static void objective(WorkflowSupport h) throws Exception {
    ServiceDeliveryAcceptance.installedSites(h);
    String roster = id(), objective = id(), admin = uid(h, "admin");
    h.account("Backup", "agent");
    String backup = uid(h, "Backup");
    try (var db = h.store.open()) {
      PlatformFoundationTest.bind(db);
      seed(
          db,
          "duty-roster",
          roster,
          map(
              "zone",
              "UTC",
              "anchor",
              System.currentTimeMillis() - 3600000,
              "hours",
              24,
              "overrides",
              List.of(),
              "people",
              List.of(admin, backup),
              "minutes",
              5));
      db.connection.commit();
    }
    cmd(
        h,
        "admin",
        "mapping",
        "define_objective",
        "objective_id",
        objective,
        "source",
        "prod",
        "resource_id",
        "db",
        "location",
        "East",
        "backup_location",
        "West",
        "minutes",
        10,
        "target_bps",
        9990,
        "roster_id",
        roster);
    long now = System.currentTimeMillis(), interval = 600000L, bucket = now - now % interval;
    String before = Instant.ofEpochMilli(bucket - interval + 1).toString(),
        current = Instant.ofEpochMilli(bucket).toString();
    cmd(
        h,
        "admin",
        "mapping",
        "probe_identity",
        "objective_id",
        objective,
        "location",
        "West",
        "user_id",
        backup,
        "revision",
        0);
    denied(
        h,
        "Backup",
        "mapping",
        "probe_sample",
        "objective_id",
        objective,
        "location",
        "East",
        "result",
        "down",
        "observed_at",
        before);
    for (String site : List.of("East", "West"))
      cmd(
          h,
          site.equals("West") ? "Backup" : "admin",
          "mapping",
          "probe_sample",
          "objective_id",
          objective,
          "location",
          site,
          "result",
          "down",
          "observed_at",
          before);
    var replay =
        cmd(
            h,
            "admin",
            "mapping",
            "probe_sample",
            "objective_id",
            objective,
            "location",
            "East",
            "result",
            "down",
            "observed_at",
            before);
    assertEquals(true, replay.get("replayed"));
    cmd(
        h,
        "admin",
        "mapping",
        "probe_sample",
        "objective_id",
        objective,
        "location",
        "East",
        "result",
        "probe_error",
        "observed_at",
        current);
    assertEquals(
        "unknown",
        cmd(
                h,
                "Backup",
                "mapping",
                "probe_sample",
                "objective_id",
                objective,
                "location",
                "West",
                "result",
                "down",
                "observed_at",
                current)
            .get("state"));
    var report = cmd(h, "admin", "mapping", "objective_report", "objective_id", objective);
    assertEquals(interval, ((Number) report.get("consumed_ms")).longValue());
    try (var db = h.store.open()) {
      var customerOutage =
          object(EnterpriseData.list(db, "site-outage").getFirst().get("definition"));
      assertEquals(2, objects(customerOutage.get("sites")).size());
      assertEquals(1, EnterpriseData.list(db, "service-episode").size());
      assertEquals(
          admin,
          object(EnterpriseData.list(db, "incident-page").getFirst().get("definition"))
              .get("primary"));
    }
  }

  static void orders(WorkflowSupport h) throws Exception {
    var inventory = AssetCustodyAcceptance.setup(h);
    String action = id(),
        policy = id(),
        run = id(),
        offering = ServiceDeliveryAcceptance.offering(h, "New hardware"),
        approver = uid(h, "AssetKeeper"),
        request,
        model = "Field laptop";
    try (var db = h.store.open()) {
      PlatformFoundationTest.bind(db);
      request =
          Requests.create(db, "admin", map("short_description", "Hardware requisition"))
              .get("sys_id")
              .toString();
      db.connection.commit();
    }
    var bindings =
        map(
            "model",
            "product",
            "quantity",
            "units",
            "months",
            "life",
            "unit_cost",
            "price",
            "salvage",
            "residual");
    cmd(
        h,
        "admin",
        "designer",
        "purchase_action",
        "action_id",
        action,
        "supplier",
        "Original supplier",
        "currency",
        "USD",
        "bindings",
        bindings,
        "revision",
        0);
    var rules = new ArrayList<>();
    var inputs = map();
    for (String label : List.of("A", "B", "C")) {
      rules.add(
          map("item", label, "owner_id", approver, "offering_id", offering, "action_id", action));
      inputs.put(
          label, map("product", model, "units", 2, "life", 24, "price", "100", "residual", "0"));
    }
    cmd(
        h,
        "admin",
        "designer",
        "list_policy",
        "policy_id",
        policy,
        "title",
        "Hardware approval",
        "entries",
        rules,
        "revision",
        0);
    cmd(
        h,
        "admin",
        "designer",
        "start_list",
        "new_id",
        run,
        "policy_id",
        policy,
        "source",
        "requisition",
        "record_id",
        request,
        "manifest",
        inputs);
    cmd(
        h,
        "AssetKeeper",
        "designer",
        "list_decision",
        "new_id",
        run,
        "decision",
        "approved",
        "revision",
        1);
    for (int i = 0; i < 2; i++)
      cmd(
          h,
          "admin",
          "designer",
          "fulfill_line",
          "new_id",
          run,
          "item",
          i == 0 ? "A" : "B",
          "manifest",
          map(),
          "revision",
          i + 2);
    cmd(
        h,
        "admin",
        "designer",
        "hold_purchase_action",
        "action_id",
        action,
        "active",
        true,
        "note",
        "Supplier review",
        "revision",
        0);
    var failed =
        object(
            cmd(
                    h,
                    "admin",
                    "designer",
                    "fulfill_line",
                    "new_id",
                    run,
                    "item",
                    "C",
                    "manifest",
                    map(),
                    "revision",
                    4)
                .get("definition"));
    assertTrue(object(object(failed.get("lines")).get("C")).containsKey("error"));
    cmd(
        h,
        "admin",
        "designer",
        "purchase_action",
        "action_id",
        action,
        "supplier",
        "New supplier",
        "currency",
        "EUR",
        "bindings",
        bindings,
        "revision",
        1);
    cmd(
        h,
        "admin",
        "designer",
        "hold_purchase_action",
        "action_id",
        action,
        "active",
        false,
        "note",
        "Review cleared",
        "revision",
        1);
    var done =
        object(
            cmd(
                    h,
                    "admin",
                    "designer",
                    "fulfill_line",
                    "new_id",
                    run,
                    "item",
                    "C",
                    "manifest",
                    map(),
                    "revision",
                    5)
                .get("definition"));
    assertEquals("complete", done.get("state"));
    String order = object(object(done.get("lines")).get("C")).get("receipt").toString();
    try (var db = h.store.open()) {
      PlatformFoundationTest.bind(db);
      assertEquals(3, EnterpriseData.list(db, "purchase").size());
      assertEquals(
          "Original supplier", JourneyForms.definition(db, "purchase", order).get("supplier"));
      AssetPlanning.command(db, map("operation", "approve", "order_id", order, "revision", 1));
      AssetPlanning.command(
          db,
          map(
              "operation",
              "receive",
              "order_id",
              order,
              "revision",
              2,
              "asset_id",
              inventory[0],
              "stockroom_id",
              inventory[1],
              "received_on",
              LocalDate.now().toString()));
      assertEquals(
          1, ((List<?>) JourneyForms.definition(db, "purchase", order).get("received")).size());
    }
  }

  static void maintenance(WorkflowSupport h) throws Exception {
    var customer = CustomerCasesAcceptance.setup(h);
    var depot = AssetCustodyAcceptance.setup(h);
    var entitlement = CustomerCasesAcceptance.entitlement(h, customer[0], 2);
    String parent =
        CustomerCasesAcceptance.create(
                h,
                entitlement,
                CustomerCasesAcceptance.body(h, entitlement, "CustomerOne"),
                "CustomerOne")
            .get("sys_id")
            .toString();
    String
        start =
            LocalDate.now(ZoneOffset.UTC)
                .with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY))
                .atTime(9, 0)
                .toInstant(ZoneOffset.UTC)
                .toString(),
        tech = uid(h, "CustomerAgent");
    String plan = id(), other = id(), empty = h.add("stockroom").get("sys_id").toString();
    ChangeSchedulingAcceptance.calendar(
        h, "FieldDay", "UTC", "08:00", "18:00", List.of(1, 2, 3, 4, 5), List.of());
    FieldDispatchAcceptance.available(h, "CustomerAgent", true);
    AssetCustodyAcceptance.receive(h, depot);
    for (String which : List.of(plan, other))
      cmd(
          h,
          "admin",
          "routing",
          "maintenance_plan",
          "plan_id",
          which,
          "case_id",
          parent,
          "group_id",
          customer[2],
          "stockroom_id",
          which.equals(plan) ? depot[1] : empty,
          "model",
          "Field laptop",
          "location",
          which.equals(plan) ? "Site A" : "Site B",
          "skills",
          List.of(),
          "technician_id",
          tech,
          "start",
          start,
          "days",
          7,
          "minutes",
          60,
          "revision",
          0);
    try (var db = h.store.open()) {
      long tick = Instant.parse(start).minusSeconds(3600).toEpochMilli();
      PreventiveMaintenance.tick(db, tick);
      PreventiveMaintenance.tick(db, tick);
      assertEquals(2, EnterpriseData.list(db, "maintenance-job").size());
      var sandbox = db.connection.setSavepoint();
      try {
        PlatformFoundationTest.bind(db);
        db.update("DELETE FROM os_customer_case WHERE record_id=?", parent);
        assertThrows(
            Failure.class,
            () ->
                PreventiveMaintenance.command(
                    db,
                    "maintenance_plan",
                    map(
                        "plan_id",
                        id(),
                        "case_id",
                        parent,
                        "group_id",
                        customer[2],
                        "stockroom_id",
                        depot[1])));
      } finally {
        db.connection.rollback(sandbox);
      }
      sandbox = db.connection.setSavepoint();
      try {
        var spec = JourneyForms.definition(db, "maintenance-plan", plan);
        long later = Instant.parse(start).plusSeconds(86400L * 90).toEpochMilli();
        String extra = id();
        spec.put("next", later);
        EnterpriseData.save(db, "maintenance-plan", extra, 0, spec);
        PreventiveMaintenance.tick(db, later - 3600000);
        var deferred =
            JourneyForms.definition(db, "maintenance-job", DeliveryWorkspace.key(extra, later));
        assertEquals("needs_parts", deferred.get("state"));
        assertFalse(deferred.get("reason").toString().isBlank());
      } finally {
        db.connection.rollback(sandbox);
      }
      db.connection.commit();
    }
    var status = cmd(h, "admin", "routing", "maintenance_status", "plan_id", plan, "start", start);
    var job = object(status.get("job"));
    assertEquals("reserved", job.get("state"), job.toString());
    assertEquals(
        "shortage",
        object(
                cmd(h, "admin", "routing", "maintenance_status", "plan_id", other, "start", start)
                    .get("job"))
            .get("state"));
    denied(
        h,
        "admin",
        "routing",
        "maintenance_dispatch",
        "plan_id",
        plan,
        "start",
        start,
        "new_start",
        start);
    String task = job.get("task").toString();
    try (var db = h.store.open()) {
      db.viewerId = tech;
      db.viewerRole = "agent";
      FieldDispatch.command(
          db,
          "customer_case",
          parent,
          task,
          map(
              "operation",
              "accept",
              "record_revision",
              CustomerResolutions.record(db, parent).get("revision"),
              "task_revision",
              WorkTasks.task(db, parent, task).get("revision"),
              "revision",
              1,
              "reason",
              "Attending"));
      DepotTransfers.command(
          db, "pickup_serial", map("task_id", task, "asset_id", depot[0], "revision", 1));
      String packet =
          new String(
              OfflineWorksheet.download(h.store, db, task),
              java.nio.charset.StandardCharsets.UTF_8);
      byte[] report =
          (packet.lines().findFirst().orElseThrow()
                  + "\r\nminutes,45\r\ninstalled_serial,"
                  + depot[0]
                  + "\r\nevidence_note,Installed replacement\r\n")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      var synced = OfflineWorksheet.sync(h.store, db, task, report, "", new byte[0]);
      assertEquals(synced, OfflineWorksheet.sync(h.store, db, task, report, "", new byte[0]));
      assertEquals(4, ((Number) AssetCustody.latest(db, depot[0]).get("revision")).intValue());
      db.connection.commit();
    }
  }
}
