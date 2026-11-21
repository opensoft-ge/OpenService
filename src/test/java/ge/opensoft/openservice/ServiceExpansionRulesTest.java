package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.definition;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class ServiceExpansionRulesTest {
  @TempDir Path dir;
  Store store;
  Store.Db db;
  String admin, lawyer, vendor, reviewer;

  @BeforeEach
  void open() throws Exception {
    var setup = new MigrationsTest();
    setup.root = dir;
    store = new Store(setup.configuration(false));
    db = store.open();
    admin = PlatformFoundationTest.bind(db);
    lawyer = account("Legal", "agent");
    vendor = account("Supplier", "requester");
    reviewer = account("Privacy", "agent");
  }

  String account(String name, String role) throws Exception {
    Service.createAccount(
        db, map("name", name, "role", role, "password", WorkflowSupport.PASSWORD));
    return RecordAccess.owner(db, name);
  }

  void as(String id) throws Exception {
    db.viewerId = id;
    db.viewerRole = Service.account(db, id).get("role").toString();
  }

  @AfterEach
  void close() throws Exception {
    db.close();
  }

  void write(String area, String id, Map<String, Object> data) throws Exception {
    save(db, area, id, get(db, area, id).get("revision"), data);
  }

  void fails(Executable attempt) throws Exception {
    var point = db.connection.setSavepoint();
    try {
      assertThrows(Failure.class, attempt);
    } finally {
      db.connection.rollback(point);
      db.connection.releaseSavepoint(point);
    }
  }

  static Map<String, Object> with(Map<String, Object> original, Object... fields) {
    var copy = new LinkedHashMap<>(original);
    copy.putAll(map(fields));
    return copy;
  }

  @Test
  void rejectRowsRepairOnlyOnceAndRetainLegacyPayloads() throws Exception {
    String pkg = id();
    var body = map("package_id", pkg, "environment", "test", "cursor", "1");
    String binding = ImportReceipts.binding(body);
    var outputs = map("event", "string", "count", "number");
    write("rest-binding", binding, map("spec", map("outputs", outputs)));
    write("incremental-import", binding, map("binding_revision", 1, "field", "event"));
    var original = map("event", "1", "count", "broken");
    assertTrue(ImportReceipts.accept(db, binding, "event", outputs, original).inserted());
    assertEquals(1, ((List<?>) ImportReceipts.rejected(db, body)).size());
    fails(() -> ImportReceipts.read(db, body));
    fails(() -> ImportReceipts.read(db, with(body, "cursor", "absent")));
    var fixed = map("event", "1", "count", 7);
    fails(() -> ImportReceipts.repair(db, with(body, "manifest", with(fixed, "event", "2"))));
    assertEquals(fixed, ImportReceipts.repair(db, with(body, "manifest", fixed)));
    fails(() -> ImportReceipts.repair(db, with(body, "manifest", fixed)));
    assertFalse(ImportReceipts.accept(db, binding, "event", outputs, original).inserted());
    assertFalse(
        ImportReceipts.accept(db, binding, "event", outputs, with(fixed, "extra", "ignored"))
            .inserted());
    fails(() -> ImportReceipts.accept(db, binding, "event", outputs, with(fixed, "count", 9)));
    assertEquals(fixed, ImportReceipts.read(db, body));
    fails(() -> ServiceExpansion.command(store, db, "incorrect", map()));
  }

  @Test
  void procurementAndInvoicePricingRespectEffectiveDatesAndTermination() throws Exception {
    String supplier = "Network supply", identity = SupplierGovernance.identity(supplier);
    var offer =
        map(
            "supplier",
            supplier,
            "user_id",
            vendor,
            "effective",
            "2026-02-01",
            "notice",
            "2098-01-01",
            "currency",
            "USD",
            "note",
            "Installation obligations",
            "manifest",
            map("Router", "12.25"),
            "revision",
            0);
    var order =
        map("supplier", supplier, "model", "Router", "currency", "USD", "unit_cost", "12.25");
    SupplierContracts.validateOrder(db, order, "2026-01-01");
    var managed = map("legal", lawyer, "finance", admin, "security", reviewer);
    write("managed-supplier", identity, managed);
    as(lawyer);
    fails(() -> SupplierContracts.command(db, "offer_terms", offer));
    managed.put("assessment", map("decision", "pass", "expiry", "2000-01-01"));
    write("managed-supplier", identity, managed);
    fails(() -> SupplierContracts.command(db, "offer_terms", offer));
    managed.put("assessment", map("decision", "pass", "expiry", "2099-01-01"));
    write("managed-supplier", identity, managed);
    fails(() -> SupplierContracts.command(db, "offer_terms", with(offer, "manifest", map())));
    fails(() -> SupplierContracts.command(db, "offer_terms", with(offer, "user_id", admin)));
    var notice =
        map(
            "supplier",
            supplier,
            "effective",
            "2090-01-01",
            "note",
            "End term",
            "decision",
            "terminate",
            "revision",
            3);
    fails(() -> SupplierContracts.command(db, "contract_decision", notice));
    SupplierContracts.command(db, "offer_terms", offer);
    var reply =
        map(
            "supplier",
            supplier,
            "decision",
            "accepted",
            "note",
            "Reviewed supplier terms",
            "revision",
            1);
    fails(() -> SupplierContracts.command(db, "supplier_response", reply));
    var signature = map("supplier", supplier, "reference", "sha256:test", "revision", 2);
    fails(() -> SupplierContracts.command(db, "sign_terms", signature));
    as(admin);
    fails(() -> SupplierContracts.command(db, "sign_terms", signature));
    as(vendor);
    SupplierContracts.command(db, "supplier_response", reply);
    fails(() -> SupplierContracts.command(db, "supplier_response", reply));
    as(admin);
    SupplierContracts.command(db, "sign_terms", signature);
    fails(() -> SupplierContracts.command(db, "contract_decision", notice));
    as(lawyer);
    fails(() -> SupplierContracts.command(db, "offer_terms", with(offer, "revision", 3)));
    fails(
        () ->
            SupplierContracts.command(
                db, "contract_decision", with(notice, "effective", "2001-01-01")));
    SupplierContracts.validateOrder(db, order, "2026-05-01");
    SupplierContracts.command(db, "contract_decision", notice);
    fails(() -> SupplierContracts.validateOrder(db, order, "2026-01-01"));
    SupplierContracts.validateOrder(db, order, "2026-05-01");
    assertTrue(order.containsKey("signed_terms"));
    SupplierContracts.validateOrder(db, with(order, "model", "Cable"), "2028-01-01");
    fails(() -> SupplierContracts.validateOrder(db, with(order, "currency", "EUR"), "2027-01-01"));
    fails(() -> SupplierContracts.validateOrder(db, with(order, "unit_cost", "33"), "2027-01-01"));
    fails(() -> SupplierContracts.validateOrder(db, order, "2090-01-01"));
    var invoice =
        map(
            "lines",
            map("Cable", map("unit_cost", "25"), "Router", map("unit_cost", "13")),
            "currency",
            "EUR",
            "supplier",
            supplier);
    SupplierContracts.match(db, invoice, "2026-01-01");
    assertFalse(invoice.containsKey("pricing_date"));
    SupplierContracts.match(db, invoice, "2026-05-01");
    assertFalse(
        object(object(invoice.get("lines")).get("Router")).containsKey("contract_unit_cost"));
    assertEquals(
        4,
        objects(object(SupplierContracts.command(db, "agreement_history", offer)).get("history"))
            .size());
    as(admin);
    assertTrue(
        object(SupplierContracts.command(db, "agreement_history", offer)).containsKey("history"));
    as(vendor);
    fails(() -> SupplierContracts.command(db, "agreement_history", offer));
    as(lawyer);
    SupplierContracts.command(
        db,
        "offer_terms",
        with(offer, "revision", 4, "effective", "2091-01-01", "notice", "2000-01-01"));
    as(vendor);
    SupplierContracts.command(db, "supplier_response", with(reply, "revision", 5));
    as(admin);
    SupplierContracts.command(db, "sign_terms", with(signature, "revision", 6));
    SupplierContracts.validateOrder(db, order, "2091-05-01");
    as(lawyer);
    fails(() -> SupplierContracts.command(db, "contract_decision", with(notice, "revision", 7)));
  }

  @Test
  void privacyBoundariesAndSnapshotsSurviveRescreening() throws Exception {
    String app = id(), assessment = id(), supplier = "Processor";
    write("managed-supplier", SupplierGovernance.identity(supplier), map("security", reviewer));
    write("business-app", app, map("owner", lawyer));
    var b =
        map(
            "assessment_id",
            assessment,
            "supplier",
            supplier,
            "application_id",
            app,
            "user_id",
            vendor,
            "category",
            "HR",
            "categories",
            "HR",
            "purpose",
            "Payroll",
            "days",
            30,
            "question",
            "Storage location?",
            "risk",
            "ordinary",
            "revision",
            0);
    as(vendor);
    fails(() -> PrivacyAssessments.command(db, "privacy_screen", b));
    as(lawyer);
    fails(() -> PrivacyAssessments.command(db, "privacy_screen", with(b, "user_id", lawyer)));
    PrivacyAssessments.command(db, "privacy_screen", b);
    as(admin);
    fails(() -> PrivacyAssessments.command(db, "privacy_screen", with(b, "revision", 1)));
    as(vendor);
    PrivacyAssessments.command(
        db, "privacy_answer", with(b, "revision", 1, "answer", "Within region"));
    fails(
        () ->
            PrivacyAssessments.command(
                db, "privacy_answer", with(b, "revision", 2, "answer", "Again")));
    fails(() -> PrivacyAssessments.command(db, "privacy_history", with(b, "revision", 2)));
    as(reviewer);
    fails(() -> PrivacyAssessments.command(db, "privacy_decide", b));
    as(admin);
    assertTrue(
        object(PrivacyAssessments.command(db, "privacy_history", with(b, "revision", 1)))
            .containsKey("assessment"));
    PrivacyAssessments.command(db, "privacy_screen", with(b, "assessment_id", id()));
    as(lawyer);
    assertEquals(
        "screened",
        object(
                object(PrivacyAssessments.command(db, "privacy_history", with(b, "revision", 2)))
                    .get("assessment"))
            .get("state"));
    fails(() -> PrivacyAssessments.command(db, "privacy_correct", b));
    PrivacyAssessments.command(db, "privacy_screen", with(b, "revision", 2, "risk", "sensitive"));
    as(vendor);
    PrivacyAssessments.command(
        db, "privacy_answer", with(b, "revision", 3, "answer", "Outside region"));
    as(reviewer);
    PrivacyAssessments.command(
        db,
        "privacy_decide",
        with(
            b,
            "revision",
            4,
            "decision",
            "remediation",
            "owner_id",
            lawyer,
            "note",
            "Correct storage location"));
    fails(() -> PrivacyAssessments.command(db, "privacy_correct", b));
  }

  @Test
  void deskAppointmentsRequireOwnershipCapacityAndMatchingReplays() throws Exception {
    long slot = (System.currentTimeMillis() / 1800000 + 2) * 1800000;
    WalkupDesk.command(
        db, "open_desk", map("location", "Main", "capacity", 1, "minutes", 30, "revision", 0));
    var book =
        map(
            "visit_id",
            id(),
            "location",
            "Main",
            "mode",
            "appointment",
            "start",
            Instant.ofEpochMilli(slot).toString(),
            "note",
            "Laptop");
    var receipt = WalkupDesk.command(db, "join_desk", book);
    assertEquals(receipt, WalkupDesk.command(db, "join_desk", book));
    for (var bad :
        List.of(
            with(book, "location", "Elsewhere"),
            with(book, "mode", "walk_in"),
            with(book, "start", "now"),
            with(book, "note", "Other equipment")))
      fails(() -> WalkupDesk.command(db, "join_desk", bad));
    as(vendor);
    fails(() -> WalkupDesk.command(db, "join_desk", book));
    fails(() -> WalkupDesk.command(db, "desk_arrival", book));
    as(admin);
    fails(
        () ->
            WalkupDesk.command(
                db,
                "join_desk",
                with(
                    book,
                    "visit_id",
                    id(),
                    "start",
                    Instant.ofEpochMilli(slot + 1000).toString())));
    fails(
        () ->
            WalkupDesk.command(
                db, "join_desk", with(book, "visit_id", id(), "start", "2000-01-01T00:00:00Z")));
    fails(() -> WalkupDesk.command(db, "desk_no_show", book));
    WalkupDesk.command(db, "desk_arrival", with(book, "revision", 1));
    fails(() -> WalkupDesk.command(db, "desk_arrival", book));
    fails(() -> WalkupDesk.command(db, "desk_no_show", book));
    var other = with(book, "visit_id", id(), "mode", "walk_in", "start", "now");
    WalkupDesk.command(db, "join_desk", other);
    WalkupDesk.command(db, "desk_claim", with(book, "revision", 2));
    var claimed = definition(db, "walkup-visit", book.get("visit_id").toString());
    var legacy = id();
    write("walkup-visit", legacy, with(claimed, "state", "assigned"));
    fails(
        () ->
            WalkupDesk.command(
                db,
                "desk_complete",
                map("visit_id", legacy, "old_asset", id(), "new_asset", id(), "revision", 1)));
    fails(() -> WalkupDesk.command(db, "desk_claim", with(other, "revision", 1)));
    var missed =
        with(book, "visit_id", id(), "start", Instant.ofEpochMilli(slot + 1800000).toString());
    WalkupDesk.command(db, "join_desk", missed);
    var data = definition(db, "walkup-visit", missed.get("visit_id").toString());
    data.put("slot", slot - 4 * 1800000);
    write("walkup-visit", missed.get("visit_id").toString(), data);
    WalkupDesk.command(db, "desk_no_show", with(missed, "revision", 2));
    WalkupDesk.command(db, "join_desk", with(missed, "visit_id", id()));
    assertEquals(1, object(WalkupDesk.command(db, "desk_status", book)).get("no_shows"));
  }

  @Test
  void calendarSchedulingHonorsReservationsAndOverlappingCommitments() throws Exception {
    String group = PlatformFoundationTest.record(db, "sys_user_group", "Capacity team"),
        second = PlatformFoundationTest.record(db, "sys_user_group", "Other team"),
        goal = id(),
        project = id(),
        epic = id(),
        story = id(),
        sprint = id();
    var scope = map("epic_id", epic, "project_id", project, "goal_id", goal, "title", "Rollout");
    write("funding-goal", goal, map("allocations", map(), "approved_plan", id()));
    fails(() -> SprintDelivery.command(db, "funded_epic", scope));
    write("funding-goal", goal, map("allocations", map(project, "25"), "approved_plan", id()));
    SprintDelivery.command(db, "funded_epic", scope);
    var entry =
        map(
            "epic_id",
            epic,
            "story_id",
            story,
            "group_id",
            group,
            "title",
            "Build",
            "quantity",
            1,
            "entries",
            List.of());
    SprintDelivery.command(db, "delivery_story", entry);
    var plan =
        map(
            "epic_id",
            epic,
            "sprint_id",
            sprint,
            "title",
            "Sprint",
            "start_on",
            "2027-01-04",
            "finish_on",
            "2027-01-10",
            "manifest",
            map(group, 60),
            "entries",
            List.of(story));
    for (var dates :
        List.of(
            List.of("2027-02-01", "2027-01-31"),
            List.of("2027-01-01", "2027-02-02"),
            List.of("2027-12-31", "2028-01-01"),
            List.of("2027-03-31", "2027-04-01")))
      fails(
          () ->
              SprintDelivery.schedule(
                  db, with(plan, "start_on", dates.get(0), "finish_on", dates.get(1))));
    db.update(
        "INSERT INTO os_group_member(group_id,user_id) VALUES(?,?),(?,?)",
        group,
        admin,
        second,
        admin);
    fails(() -> SprintDelivery.schedule(db, plan));
    write(
        "staff-calendar",
        admin,
        map("week", List.of(480, 480, 480, 480, 480, 0, 0), "exceptions", map()));
    fails(() -> SprintDelivery.schedule(db, with(plan, "manifest", map(group, 60, second, 60))));
    var reservation =
        map("user_id", admin, "percent", 1, "start_on", "2027-01-06", "finish_on", "2027-01-07");
    write("portfolio", project, map("reservations", List.of(reservation)));
    write(
        "portfolio",
        id(),
        map("reservations", List.of(reservation, with(reservation, "percent", 5))));
    for (var other :
        List.of(
            map("state", "active"),
            map(
                "state",
                "active",
                "start_on",
                "2027-01-01",
                "finish_on",
                "2027-01-03",
                "people",
                List.of(admin)),
            map(
                "state",
                "active",
                "start_on",
                "2027-01-11",
                "finish_on",
                "2027-01-21",
                "people",
                List.of(admin)),
            map(
                "state",
                "active",
                "start_on",
                "2027-01-06",
                "finish_on",
                "2027-01-07",
                "people",
                List.of(vendor)))) write("delivery-sprint", id(), other);
    String collision = id();
    write(
        "delivery-sprint",
        collision,
        map(
            "state",
            "active",
            "start_on",
            "2027-01-06",
            "finish_on",
            "2027-01-10",
            "people",
            List.of(admin)));
    fails(() -> SprintDelivery.schedule(db, plan));
    write("delivery-sprint", collision, map("state", "closed"));
    var committed = object(object(SprintDelivery.schedule(db, plan)).get("definition"));
    assertEquals(39, object(committed.get("capacity")).get(group));
    assertTrue(object(SprintDelivery.command(db, "sprint_report", plan)).containsKey("outcomes"));
    fails(
        () ->
            SprintDelivery.command(
                db, "commit_sprint", with(plan, "sprint_id", id(), "entries", List.of())));
    fails(() -> SprintDelivery.command(db, "commit_sprint", with(plan, "sprint_id", id())));
    var deploy =
        map(
            "epic_id",
            epic,
            "sprint_id",
            sprint,
            "story_id",
            story,
            "release_id",
            id(),
            "revision",
            2);
    var item = definition(db, "delivery-story", story);
    write("delivery-story", story, with(item, "sprint", id()));
    fails(() -> SprintDelivery.command(db, "ship_story", deploy));
    write("delivery-story", story, with(item, "state", "backlog"));
    fails(() -> SprintDelivery.command(db, "ship_story", deploy));
    write("delivery-story", story, with(item, "epic", id()));
    fails(() -> SprintDelivery.command(db, "commit_sprint", with(plan, "sprint_id", id())));
  }

  @Test
  void locatorFailuresAndMissingSamplesDoNotCountAsDowntime() throws Exception {
    String objective = id(), roster = id();
    ServiceTopology.inventory(
        db,
        "cloud",
        map(
            "sequence",
            1,
            "resources",
            List.of(
                map("id", "db", "type", "database", "title", "Storage", "depends_on", List.of())),
            "services",
            List.of(map("id", "billing", "title", "Billing", "roots", List.of("db")))));
    write(
        "duty-roster",
        roster,
        map(
            "zone",
            "UTC",
            "anchor",
            0,
            "hours",
            24,
            "overrides",
            List.of(),
            "people",
            List.of(admin, reviewer),
            "minutes",
            5));
    var settings =
        map(
            "objective_id",
            objective,
            "source",
            "cloud",
            "resource_id",
            "db",
            "location",
            "A",
            "backup_location",
            "B",
            "roster_id",
            roster,
            "minutes",
            5,
            "target_bps",
            9950);
    fails(
        () ->
            ReliabilityObjectives.run(
                store, db, "define_objective", with(settings, "backup_location", "A")));
    ReliabilityObjectives.run(store, db, "define_objective", settings);
    var spec = definition(db, "service-objective", objective);
    long now = System.currentTimeMillis(), step = 300000, bucket = now - now % step;
    var sample =
        map(
            "objective_id",
            objective,
            "location",
            "A",
            "result",
            "up",
            "observed_at",
            Instant.ofEpochMilli(bucket - step + 1).toString());
    ReliabilityObjectives.run(
        store, db, "probe_identity", with(sample, "user_id", lawyer, "revision", 0));
    fails(
        () ->
            ReliabilityObjectives.run(
                store,
                db,
                "probe_identity",
                with(sample, "location", "B", "user_id", lawyer, "revision", 0)));
    fails(() -> ReliabilityObjectives.run(store, db, "probe_sample", sample));
    as(lawyer);
    fails(
        () ->
            ReliabilityObjectives.run(
                store,
                db,
                "probe_sample",
                with(sample, "observed_at", Instant.ofEpochMilli(bucket - 3 * step).toString())));
    ReliabilityObjectives.run(store, db, "probe_sample", sample);
    as(admin);
    ReliabilityObjectives.run(
        store, db, "probe_sample", with(sample, "location", "B", "result", "probe_error"));
    assertEquals(0L, ReliabilityObjectives.report(db, objective, spec, now).get("consumed_ms"));
    as(lawyer);
    ReliabilityObjectives.run(
        store,
        db,
        "probe_sample",
        with(sample, "observed_at", Instant.ofEpochMilli(bucket).toString()));
    var report = ReliabilityObjectives.report(db, objective, spec, bucket + step);
    assertEquals(30L * 86400000 - step, report.get("unknown_ms"));
    assertTrue(ReliabilityObjectives.review(db, "another-ci").isEmpty());
    assertEquals(1, ReliabilityObjectives.review(db, spec.get("ci").toString()).size());
    as(admin);
    ReliabilityObjectives.run(
        store,
        db,
        "probe_sample",
        with(sample, "location", "B", "observed_at", Instant.ofEpochMilli(bucket).toString()));
    ReliabilityObjectives.signal(store, db, objective, spec, bucket - step, "down");
    assertTrue(list(db, "incident-page").isEmpty());
    String identity =
        ServiceTopology.identity(spec.get("source").toString(), spec.get("resource").toString());
    var event =
        map(
            "id",
            DeliveryWorkspace.key(objective, bucket),
            "monitor",
            objective,
            "observed_at",
            Instant.ofEpochMilli(bucket).toString(),
            "state",
            "down");
    var receipt = ConnectorSynchronization.hash(List.of(identity, objective, event.get("id")));
    var monitors = map(objective, with(event, "state", "up"));
    write("monitor-state", identity, map("monitors", monitors));
    ReliabilityObjectives.signal(store, db, objective, spec, bucket, "down");
    monitors.put(
        objective,
        with(event, "state", "up", "observed_at", Instant.ofEpochMilli(bucket - step).toString()));
    // An already-recorded delivery can arrive again after the episode was cleared.
    write("signal-receipt", receipt, event);
    write("monitor-state", identity, map("monitors", monitors));
    ReliabilityObjectives.signal(store, db, objective, spec, bucket, "down");
    write("monitor-state", identity, map("monitors", monitors, "episode", id()));
    ReliabilityObjectives.signal(store, db, objective, spec, bucket, "down");
    assertTrue(list(db, "incident-page").isEmpty());
  }

  @Test
  void expiredMaintenanceAuthorityAndRetryablePlanErrors() throws Exception {
    var plan = map("next", 0, "owner", vendor, "case", id(), "group", id(), "days", 7);
    String id = id();
    write("maintenance-plan", id, plan);
    PreventiveMaintenance.tick(db, 100);
    assertEquals(1, get(db, "maintenance-plan", id).get("revision"));
    write("maintenance-plan", id, with(plan, "owner", admin));
    PreventiveMaintenance.tick(db, 100);
    assertTrue(definition(db, "maintenance-plan", id).containsKey("error"));
    int failed = (int) get(db, "maintenance-plan", id).get("revision");
    PreventiveMaintenance.tick(db, 100);
    assertEquals(failed, get(db, "maintenance-plan", id).get("revision"));
    write("maintenance-job", DeliveryWorkspace.key(id, 0L), map("state", "needs_parts"));
    PreventiveMaintenance.tick(db, 100);
    assertFalse(definition(db, "maintenance-plan", id).containsKey("error"));
  }

  @Test
  void externalSourceChangesFailClosedUntilReindexed() throws Exception {
    db.connection.setAutoCommit(true);
    try (var provider = new RecordQuestionsAcceptance.Provider(store, store.environment)) {
      db.connection.setAutoCommit(false);
      try {
        String member = PlatformFoundationTest.record(db, "sys_user_group", "Employees");
        String other = PlatformFoundationTest.record(db, "sys_user_group", "Contractors");
        db.update("INSERT INTO os_group_member(group_id,user_id) VALUES(?,?)", member, admin);
        var intent = map("article", id(), "offering", id());
        String id = id();
        write("employee-intent", id, intent);
        var original =
            map(
                "title",
                "Current policy",
                "body",
                "Reviewed policy text",
                "version",
                "4",
                "allowed_groups",
                List.of("unmapped", "contractors", "employees"));
        var live = new java.util.concurrent.atomic.AtomicReference<>(original);
        provider.server.createContext(
            "/policy",
            exchange -> {
              var bytes = JSON.writeValueAsBytes(live.get());
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
              exchange.close();
            });
        ExternalPolicies.refresh(
            store,
            db,
            map(
                "intent_id",
                id,
                "alias",
                provider.alias,
                "path",
                "/policy",
                "bindings",
                map("employees", member, "contractors", other),
                "revision",
                0));
        var citation = map();
        ExternalPolicies.cite(store, db, intent, citation);
        assertEquals("4", object(citation.get("external")).get("version"));
        for (var change :
            List.of(
                with(original, "title", "Revised"),
                with(original, "version", "5"),
                with(original, "body", "Obsolete"),
                with(original, "allowed_groups", List.of("unknown", "contractors")))) {
          live.set(change);
          var hidden = map();
          fails(() -> ExternalPolicies.cite(store, db, intent, hidden));
          assertTrue(hidden.isEmpty());
        }
        live.set(original);
        db.update(
            "UPDATE os_connector SET origin=? WHERE alias=?",
            "https://changed.example",
            provider.alias);
        fails(() -> ExternalPolicies.cite(store, db, intent, map()));
        db.update("DELETE FROM os_connector WHERE alias=?", provider.alias);
        fails(() -> ExternalPolicies.cite(store, db, intent, map()));
      } finally {
        db.connection.rollback();
        db.connection.setAutoCommit(true);
      }
    } finally {
      db.connection.setAutoCommit(false);
    }
  }

  String issueCertificate(String alias, String start, int validity) throws Exception {
    String tool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    String keyfile = dir.resolve("fixture.p12").toString();
    var generate =
        new ProcessBuilder(
                tool,
                "-genkeypair",
                "-keyalg",
                "EC",
                "-alias",
                alias,
                "-keystore",
                keyfile,
                "-storepass",
                "fixture-only",
                "-dname",
                "CN=localhost",
                "-startdate",
                start,
                "-validity",
                String.valueOf(validity))
            .redirectErrorStream(true)
            .start();
    String diagnostic = new String(generate.getInputStream().readAllBytes());
    assertEquals(0, generate.waitFor(), diagnostic);
    var export =
        new ProcessBuilder(
                tool,
                "-exportcert",
                "-alias",
                alias,
                "-keystore",
                keyfile,
                "-storepass",
                "fixture-only")
            .start();
    var der = export.getInputStream().readAllBytes();
    assertEquals(0, export.waitFor());
    return Base64.getEncoder().encodeToString(der);
  }

  Map<String, Object> certCall(String op, Map<String, Object> b) throws Exception {
    String fingerprint = b.getOrDefault("fingerprint", "").toString();
    return object(
        CertificateRenewals.command(
            db,
            op,
            with(b, "revision", get(db, "certificate-renewal", fingerprint).get("revision"))));
  }

  @Test
  void certificateRechecksDetectDriftAndPreservePriorClosedRequests() throws Exception {
    String old = issueCertificate("old", "-1d", 31),
        fresh = issueCertificate("fresh", "-1d", 365),
        future = issueCertificate("future", "+5d", 60),
        expired = issueCertificate("expired", "-15d", 2);
    String ci = PlatformFoundationTest.record(db, "cmdb_ci", "Mail"), site = "https://mail.example";
    var discovery =
        map(
            "service_ci",
            ci,
            "owner_id",
            lawyer,
            "endpoint",
            site,
            "source",
            "cloud",
            "certificate",
            old);
    var cert = CertificateRenewals.certificate(discovery);
    String fp = cert.get("sha256").toString();
    var verify = map("fingerprint", fp, "endpoint", site, "certificate", fresh);
    fails(() -> CertificateRenewals.certificate(map("certificate", "!")));
    byte[] der = Base64.getDecoder().decode(old);
    fails(
        () ->
            CertificateRenewals.certificate(
                map(
                    "certificate",
                    Base64.getEncoder().encodeToString(Arrays.copyOf(der, der.length + 2)))));
    certCall("discover_certificate", discovery);
    certCall("discover_certificate", discovery);
    var oldRecord = definition(db, "certificate-renewal", fp);
    oldRecord.remove("request");
    write("certificate-renewal", fp, oldRecord);
    assertTrue(
        object(certCall("discover_certificate", discovery).get("definition"))
            .containsKey("request"));
    fails(() -> certCall("renew_certificate", verify));
    fails(() -> certCall("verify_certificate", verify));
    as(lawyer);
    for (String invalid : List.of(old, future, expired))
      fails(() -> certCall("renew_certificate", with(verify, "certificate", invalid)));
    certCall("renew_certificate", verify);
    fails(() -> certCall("renew_certificate", verify));
    fails(() -> certCall("verify_certificate", verify));
    as(admin);
    fails(
        () -> certCall("verify_certificate", with(verify, "observed_at", "2020-01-01T00:00:00Z")));
    Instant first = Instant.ofEpochMilli(System.currentTimeMillis());
    String at = first.toString();
    // Reserve three distinct millisecond timestamps before submitting the observations.
    while (Instant.now().isBefore(first.plusMillis(2))) Thread.sleep(1);
    var complete =
        object(certCall("verify_certificate", with(verify, "observed_at", at)).get("definition"));
    String original = complete.get("request").toString();
    fails(() -> certCall("verify_certificate", with(verify, "observed_at", at)));
    certCall("discover_certificate", with(discovery, "source", "inventory"));
    var reopened =
        object(
            certCall(
                    "verify_certificate",
                    with(
                        verify,
                        "certificate",
                        old,
                        "observed_at",
                        first.plusMillis(1).toString()))
                .get("definition"));
    assertNotEquals(original, reopened.get("request"));
    assertEquals(original, objects(reopened.get("prior_requests")).getFirst().get("request"));
    certCall("verify_certificate", with(verify, "observed_at", first.plusMillis(2).toString()));
    assertEquals(
        "verifying",
        object(
                certCall(
                        "discover_certificate",
                        with(discovery, "endpoint", "https://secondary.example"))
                    .get("definition"))
            .get("state"));
    var later = with(discovery, "certificate", fresh);
    var futureInventory = object(certCall("discover_certificate", later).get("definition"));
    assertFalse(futureInventory.containsKey("request"));
    as(lawyer);
    certCall(
        "renew_certificate",
        with(
            verify,
            "fingerprint",
            object(futureInventory.get("certificate")).get("sha256"),
            "certificate",
            old));
  }

  @Test
  void invoiceMatchAndDesignFormsRejectAmbiguousInputs() throws Exception {
    String supplier = "Invoice supplier", order = id();
    write(
        "purchase",
        order,
        map(
            "state",
            "approved",
            "supplier",
            supplier,
            "currency",
            "USD",
            "model",
            "Desk",
            "quantity",
            2,
            "unit_cost",
            "123",
            "received",
            List.of("asset")));
    var terms =
        map(
            "version",
            1,
            "currency",
            "USD",
            "effective",
            "2020-01-01",
            "prices",
            map("Desk", "120.00"));
    write(
        "supplier-agreement", SupplierGovernance.identity(supplier), map("signed", List.of(terms)));
    var input =
        map(
            "operation",
            "dated_invoice",
            "invoice",
            "INV-1",
            "order",
            order,
            "pricing_date",
            "2026-11-01",
            "currency",
            "USD",
            "lines",
            List.of(map("model", "Desk", "quantity", 1, "unit_cost", "121")));
    var receipt = object(PurchasingControls.command(db, input));
    var line = object(object(receipt.get("lines")).get("Desk"));
    assertEquals("1.00", line.get("contract_unit_variance"));
    assertEquals("2026-11-01", receipt.get("pricing_date"));
    String action = id();
    for (var op : List.of("list_policy", "purchase_action")) {
      var body = map("operation", op, "designer_rows", "bindings", "bindings", map());
      fails(() -> DesignInputs.read(map("title", "Decision workflows"), body));
    }
    fails(
        () ->
            DesignInputs.read(
                map("title", "Intake applications"),
                map("operation", "purchase_action", "designer_rows", "bindings")));
    var form = map("operation", "list_policy", "designer_rows", "entries", "action_1", action);
    DesignInputs.read(map("title", "Decision workflows"), form);
    assertEquals(
        action,
        object(((List<?>) JSON.readValue(form.get("entries").toString(), Object.class)).getFirst())
            .get("action_id"));
    var policy = id();
    write(
        "list-policy",
        policy,
        map("bindings", map("Desk", map("owner", lawyer, "offering", "", "action_id", action))));
    fails(
        () ->
            ListApprovals.command(
                db,
                "start_list",
                map(
                    "new_id",
                    id(),
                    "record_id",
                    order,
                    "source",
                    "purchase",
                    "policy_id",
                    policy)));
    assertTrue(
        ((List<?>)
                ServiceExpansion.command(
                    store, db, "rejected_changes", map("package_id", id(), "environment", "test")))
            .isEmpty());
  }
}
