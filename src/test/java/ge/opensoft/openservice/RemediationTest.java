package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RemediationTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
  final ServiceAssuranceTest service = new ServiceAssuranceTest();
  final IndustryWorkflowsTest h = service.h;

  void open(boolean pg) throws Exception {
    service.root = root;
    service.open(pg);
  }

  @AfterEach
  void close() throws Exception {
    service.close();
  }

  Map<String, Object> apps(String op, Object... args) throws Exception {
    return service.cmd("apps", op, args);
  }

  Map<String, Object> factPosition(String product, String day, String snapshot) throws Exception {
    return service.def(
        service.cmd(
            "licenses",
            "license_position",
            "product",
            product,
            "effective",
            day,
            "new_id",
            snapshot));
  }

  void fact(String product, String ref, String day, Object... values) throws Exception {
    service.cmd(
        "licenses",
        "license_fact",
        "product",
        product,
        "reference",
        ref,
        "effective",
        day,
        "manifest",
        map(values));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void perCoreRightsSplitAcrossContractsAndPreservePhysicalCounts(boolean pg) throws Exception {
    open(pg);
    String p = "Database VM", a = h.record("cmdb_ci"), b = h.record("cmdb_ci");
    fact(
        p,
        "hardware",
        "2026-01-01",
        "kind",
        "rights",
        "contract",
        "A",
        "quantity",
        6,
        "covers",
        List.of("Pro", "Basic"),
        "metric",
        "per_core",
        "minimum_cores",
        4);
    fact(
        p,
        "basic",
        "2026-01-01",
        "kind",
        "rights",
        "contract",
        "B",
        "quantity",
        3,
        "covers",
        List.of("Basic"));
    fact(
        p,
        "vm-a",
        "2026-01-01",
        "kind",
        "installation",
        "ci",
        a,
        "edition",
        "Basic",
        "last_used",
        "2026-01-01",
        "cores",
        2);
    fact(
        p,
        "vm-b",
        "2026-01-02",
        "kind",
        "installation",
        "ci",
        b,
        "edition",
        "Pro",
        "last_used",
        "2026-01-01",
        "cores",
        6);
    String snapshot = id();
    var report = factPosition(p, "2026-01-31", snapshot);
    assertEquals(4, report.get("minimum_cores"));
    assertEquals(10, report.get("required_units"));
    assertEquals(9, report.get("covered_units"));
    assertEquals(1, report.get("shortfall_units"));
    assertEquals(2, object(object(report.get("installations")).get(a)).get("cores"));
    fact(
        p,
        "late purchase",
        "2026-01-20",
        "kind",
        "rights",
        "contract",
        "B",
        "quantity",
        1,
        "covers",
        List.of("Basic"));
    assertEquals(report, factPosition(p, "2026-01-31", snapshot));
    var closed = factPosition(p, "2026-01-31", id());
    assertEquals(0, closed.get("shortfall_units"));
    assertEquals(map("B", 4), object(closed.get("allocations")).get(a));
    assertEquals(map("A", 6), object(closed.get("allocations")).get(b));
    assertThrows(
        Failure.class,
        () -> fact(p, "relabel", "2026-01-21", "kind", "rights", "minimum_cores", 5));
    assertThrows(
        Failure.class,
        () ->
            fact(
                p,
                "invalid",
                "2026-01-20",
                "kind",
                "installation",
                "ci",
                b,
                "edition",
                "Pro",
                "last_used",
                "2026-01-01",
                "cores",
                0));
    fact(
        p,
        "ambiguous",
        "2026-01-02",
        "kind",
        "installation",
        "ci",
        b,
        "edition",
        "Pro",
        "last_used",
        "2026-01-01",
        "cores",
        5);
    assertThrows(Failure.class, () -> factPosition(p, "2026-01-31", id()));
    fact(
        "named",
        "grant",
        "2026-01-01",
        "kind",
        "rights",
        "contract",
        "N",
        "quantity",
        1,
        "covers",
        List.of("Pro"),
        "metric",
        "named_user");
    assertThrows(Failure.class, () -> fact("named", "wrong min", "2026-01-01", "minimum_cores", 4));
    assertThrows(Failure.class, () -> fact(p, "invalid session", "2026-01-01", "kind", "session"));
  }

  @Test
  void constrainedConsumerCanDisplaceAFlexibleAllocationInBlocks() {
    var r =
        MeteredLicenses.allocate(
            new TreeMap<>(Map.of("first", 60000, "last", 50000)),
            Map.of("first", Set.of("B"), "last", Set.of("P")),
            new TreeMap<>(Map.of("a", 50000, "b", 60000)),
            Map.of("a", List.of("B", "P"), "b", List.of("B")));
    assertEquals(110000, r.get("covered_units"));
    assertEquals(Map.of(), r.get("uncovered"));
  }

  void session(String ref, String who, String edition, String start, String end) throws Exception {
    fact(
        "CAD",
        ref,
        start.substring(0, 10),
        "kind",
        "session",
        "user_id",
        who,
        "edition",
        edition,
        "start",
        start,
        "end",
        end);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void concurrentUserPeakUsesHalfOpenIntervalsAcrossMidnight(boolean pg) throws Exception {
    open(pg);
    fact(
        "CAD",
        "grant",
        "2026-01-01",
        "kind",
        "rights",
        "metric",
        "concurrent_user",
        "contract",
        "Seats",
        "quantity",
        1,
        "covers",
        List.of("Basic", "Pro"));
    session("old", h.c, "Basic", "2026-01-01T01:00:00Z", "2026-01-01T02:00:00Z");
    session("A", h.a, "Pro", "2026-01-01T23:00:00Z", "2026-01-02T09:00:00Z");
    session("B", h.a, "Basic", "2026-01-02T00:00:00Z", "2026-01-02T10:00:00Z");
    session("C", h.b, "Pro", "2026-01-02T09:00:00Z", "2026-01-03T00:00:00Z");
    session("C", h.b, "Pro", "2026-01-02T09:00:00Z", "2026-01-03T00:00:00Z");
    session("D", h.c, "Pro", "2026-01-02T10:00:00Z", "2026-01-02T10:30:00Z");
    String snap = id();
    var result = factPosition("CAD", "2026-01-02", snap);
    assertEquals(2, result.get("peak_users"));
    assertEquals("2026-01-02T09:00:00Z", result.get("observed_at"));
    assertEquals(1, result.get("shortfall_units"));
    assertEquals(2, object(result.get("active_editions")).size());
    assertEquals(0, factPosition("CAD", "2026-01-03", id()).get("peak_users"));
    fact(
        "CAD",
        "backdated",
        "2026-01-01",
        "kind",
        "rights",
        "contract",
        "Seats",
        "quantity",
        1,
        "covers",
        List.of("Pro", "Basic"));
    assertEquals(result, factPosition("CAD", "2026-01-02", snap));
    var sufficient = factPosition("CAD", "2026-01-02", id());
    assertEquals(0, sufficient.get("shortfall_units"));
    assertEquals(2, sufficient.get("peak_users"));
    assertThrows(
        Failure.class,
        () -> session("invalid", h.a, "Pro", "2026-01-02T12:00:00Z", "2026-01-02T12:00:00Z"));
    assertThrows(
        Failure.class,
        () ->
            fact(
                "CAD",
                "wrong day",
                "2026-01-01",
                "kind",
                "session",
                "user_id",
                h.a,
                "edition",
                "Pro",
                "start",
                "2026-01-02T10:00:00Z",
                "end",
                "2026-01-02T11:00:00Z"));
    assertThrows(
        Failure.class, () -> fact("CAD", "wrong kind", "2026-01-02", "kind", "installation"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void lateProbeReviewKeepsOriginalDecisionAndSeparatesRetrospectiveScores(boolean pg)
      throws Exception {
    open(pg);
    String objective = service.objective();
    long cadence = 60000,
        day = 86400000,
        now = System.currentTimeMillis() / cadence * cadence - cadence;
    var samples = new ArrayList<>();
    for (int i = 1; i <= 4; i++)
      samples.add(
          map(
              "observed_at",
              service.stamp(now - day * i),
              "metrics",
              service.metrics(90 + i * 5, 30, 1)));
    service.cmd(
        "mapping",
        "metric_corroborated_baseline",
        "objective_id",
        objective,
        "samples",
        samples,
        "revision",
        0);
    service.cmd(
        "mapping",
        "transaction_rule",
        "objective_id",
        objective,
        "expected",
        "checkout",
        "limit",
        2000,
        "revision",
        0);
    Object[] reading = {
      "objective_id",
      objective,
      "observed_at",
      service.stamp(now),
      "manifest",
      service.metrics(102, 30, 1)
    };
    var original = service.cmd("mapping", "metric_sample", reading);
    Object[] review = {"objective_id", objective, "observed_at", service.stamp(now)};
    Object[] score = {
      "objective_id",
      objective,
      "samples",
      List.of(map("observed_at", service.stamp(now), "state", "down"))
    };
    assertEquals(1, service.cmd("mapping", "reviewed_metric_score", score).get("missed_incidents"));
    for (String place : List.of("east", "west")) {
      assertThrows(Failure.class, () -> service.cmd("mapping", "review_metric_sample", review));
      service.cmd(
          "mapping",
          "transaction_sample",
          "objective_id",
          objective,
          "location",
          place,
          "observed_at",
          service.stamp(now),
          "manifest",
          map(
              "matched",
              false,
              "expected",
              "checkout",
              "status",
              200,
              "healthy",
              true,
              "latency_ms",
              560));
    }
    var metric = service.def(original);
    long bucket = ((Number) metric.get("probe_bucket")).longValue();
    Object rule = metric.get("transaction_rule_revision");
    for (var difference : List.of(map("objective", id()), map("probe_bucket", bucket-cadence),
                                 map("transaction_rule_revision", 76))) {
      var sample = new LinkedHashMap<>(metric);
      sample.putAll(difference);
      String ref = id();
      h.seed("metric-observation",ref,sample);
      MetricAnomalies.reviewLateProbes(h.db,objective,bucket,rule);
      assertEquals(0,get(h.db,"metric-impact-review",ref).get("revision"));
    }
    String current = DeliveryWorkspace.key(objective,now);
    assertEquals("late_probes",service.def(get(h.db,"metric-impact-review",current)).get("source"));
    // A restored database can contain the measurements before the automated review existed.
    h.db.update("DELETE FROM os_enterprise_document WHERE area=? AND subject=?", "metric-impact-review", current);
    var result = service.cmd("mapping", "review_metric_sample", review);
    assertEquals("operator",service.def(result).get("source"));
    for (var invalid : List.of(map("location","unregistered"),map("observed_at",service.stamp(now-8*day)),
                                map("observed_at",service.stamp(now-day)))) {
      var body=map("objective_id",objective,"location","east","observed_at",service.stamp(now-cadence),
        "manifest",map("matched",false,"expected","checkout","status",200,"healthy",true,"latency_ms",500));
      body.putAll(invalid);
      assertThrows(Failure.class,()->TransactionChecks.run(h.store,h.db,"backfill_transaction_sample",body));
    }
    var evidence = service.def(result);
    assertEquals("up", evidence.get("original_state"));
    assertEquals("down", evidence.get("state"));
    assertEquals(Set.of("east", "west"), object(evidence.get("transaction_evidence")).keySet());
    assertEquals(result, service.cmd("mapping", "review_metric_sample", review));
    assertEquals(original, service.cmd("mapping", "metric_sample", reading));
    assertEquals(1, service.cmd("mapping", "metric_score", score).get("missed_incidents"));
    assertEquals(0, service.cmd("mapping", "reviewed_metric_score", score).get("missed_incidents"));
    long other = now - cadence;
    h.seed("metric-observation", DeliveryWorkspace.key(objective, other), map("state", "up"));
    assertThrows(
        Failure.class,
        () ->
            service.cmd(
                "mapping",
                "review_metric_sample",
                "objective_id",
                objective,
                "observed_at",
                service.stamp(other)));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void pooledRobotsFenceExpiredWorkersAndKeepRequesterAuthority(boolean pg) throws Exception {
    open(pg);
    String pool = id(), job = id(), other = id(), foreign = id();
    apps("case_program", "kind", "permit", "group_id", h.team, "revision", 0);
    apps("robot_pool", "pool_id", pool, "group_id", h.team, "revision", 0);
    apps("robot_pool", "pool_id", other, "group_id", h.second, "revision", 0);
    Object[] details = {
      "pool_id",
      pool,
      "job_id",
      job,
      "reference",
      "P1",
      "manifest",
      map("site", "East", "activity", "Fair")
    };
    h.as(h.c);
    var pending = apps("queue_robot_permit", details);
    assertEquals(pending, apps("queue_robot_permit", details));
    assertThrows(
        Failure.class,
        () ->
            apps(
                "queue_robot_permit",
                "pool_id",
                pool,
                "job_id",
                job,
                "reference",
                "P1",
                "manifest",
                map("site", "West", "activity", "Fair")));
    assertThrows(
        Failure.class,
        () ->
            apps(
                "queue_robot_permit",
                "pool_id",
                pool,
                "job_id",
                job,
                "reference",
                "P2",
                "manifest",
                map("site", "East", "activity", "Fair")));
    assertThrows(
        Failure.class,
        () ->
            apps(
                "queue_robot_permit",
                "pool_id",
                other,
                "job_id",
                job,
                "reference",
                "P1",
                "manifest",
                map("site", "East", "activity", "Fair")));
    h.as(h.a);
    assertThrows(Failure.class, () -> apps("queue_robot_permit", details));
    assertThrows(Failure.class, () -> apps("robot_job_status", "pool_id", pool, "job_id", job));
    assertThrows(Failure.class, () -> apps("claim_robot_job", "pool_id", other));
    var t = apps("claim_robot_job", "pool_id", pool);
    assertFalse(t.containsKey("manifest"));
    assertEquals(t, apps("claim_robot_job", "pool_id", pool));
    h.as(h.b);
    assertEquals("idle", apps("claim_robot_job", "pool_id", pool).get("state"));
    Object[] aLease = {"pool_id", pool, "job_id", job, "lease_id", t.get("lease")};
    assertThrows(Failure.class, () -> apps("execute_robot_job", aLease));
    h.as(h.a);
    assertThrows(
        Failure.class,
        () -> apps("execute_robot_job", "pool_id", pool, "job_id", job, "lease_id", id()));
    var expired = JourneyForms.definition(h.db, "permit-robot-job", job);
    expired.put("until", 0);
    h.seed("permit-robot-job", job, expired);
    assertThrows(Failure.class, () -> apps("execute_robot_job", aLease));
    h.as(h.b);
    t = apps("claim_robot_job", "pool_id", pool);
    Object[] bLease = {"pool_id", pool, "job_id", job, "lease_id", t.get("lease")};
    var done = apps("execute_robot_job", bLease);
    assertEquals("done", done.get("state"));
    assertEquals(2, done.get("attempts"));
    assertEquals(done, apps("execute_robot_job", bLease));
    assertEquals(h.b, h.db.viewerId);
    h.as(h.c);
    assertEquals(done, apps("robot_job_status", "pool_id", pool, "job_id", job));
    assertEquals(done, apps("cancel_robot_job", "pool_id", pool, "job_id", job));
    assertEquals(1, apps("industry_case", "new_id", done.get("case")).get("revision"));
    assertThrows(Failure.class, () -> apps("robot_job_status", "pool_id", other, "job_id", job));
    apps(
        "queue_robot_permit",
        "pool_id",
        other,
        "job_id",
        foreign,
        "reference",
        "P1",
        "manifest",
        map("site", "West", "activity", "Fair"));
    h.as(h.a);
    assertEquals("idle", apps("claim_robot_job", "pool_id", pool).get("state"));
    h.as(h.b);
    var ft = apps("claim_robot_job", "pool_id", other);
    var conflict =
        apps("execute_robot_job", "pool_id", other, "job_id", foreign, "lease_id", ft.get("lease"));
    assertEquals("review", conflict.get("state"));
    assertEquals(h.b, h.db.viewerId);
    String stopped = id();
    h.as(h.c);
    apps(
        "queue_robot_permit",
        "pool_id",
        pool,
        "job_id",
        stopped,
        "reference",
        "stop",
        "manifest",
        map("site", "A", "activity", "fair"));
    h.as(h.b);
    var st = apps("claim_robot_job", "pool_id", pool);
    h.as(h.c);
    assertEquals(
        "cancelled", apps("cancel_robot_job", "pool_id", pool, "job_id", stopped).get("state"));
    h.as(h.b);
    assertThrows(
        Failure.class,
        () ->
            apps(
                "execute_robot_job",
                "pool_id",
                pool,
                "job_id",
                stopped,
                "lease_id",
                st.get("lease")));
    String inactive = id();
    h.as(h.c);
    apps(
        "queue_robot_permit",
        "pool_id",
        pool,
        "job_id",
        inactive,
        "reference",
        "inactive",
        "manifest",
        map("site", "A", "activity", "fair"));
    h.as(h.b);
    var it = apps("claim_robot_job", "pool_id", pool);
    h.db.update("UPDATE os_user SET active=0 WHERE user_id=?", h.c);
    assertEquals(
        "review",
        apps("execute_robot_job", "pool_id", pool, "job_id", inactive, "lease_id", it.get("lease"))
            .get("state"));
  }

  Map<String, Object> opening(String site, String op, Object... fields) throws Exception {
    var body = new ArrayList<Object>(List.of("site_id", site, "effective", "2026-11-21"));
    body.addAll(List.of(fields));
    return apps(op, body.toArray());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void storeCannotOpenUntilSafetyIndependentlyVerifiesCorrectiveWork(boolean pg) throws Exception {
    open(pg);
    String site = id();
    Object[] plan = {
      "site_id",
      site,
      "group_id",
      h.team,
      "safety_group_id",
      h.second,
      "title",
      "East shop",
      "entries",
      List.of("Emergency exit", "Till"),
      "revision",
      0
    };
    apps("store_opening_plan", plan);
    assertThrows(
        Failure.class,
        () ->
            apps(
                "store_opening_plan",
                "site_id",
                site,
                "group_id",
                h.team,
                "safety_group_id",
                h.second,
                "title",
                "East",
                "entries",
                List.of(),
                "revision",
                1));
    assertThrows(
        Failure.class,
        () ->
            apps(
                "store_opening_plan",
                "site_id",
                site,
                "group_id",
                h.team,
                "safety_group_id",
                h.second,
                "title",
                "East",
                "entries",
                List.of("Till", "Till"),
                "revision",
                1));
    h.as(h.admin);
    assertThrows(Failure.class, () -> opening(site, "start_store_opening"));
    h.as(h.a);
    var opened = opening(site, "start_store_opening");
    assertEquals(opened, opening(site, "start_store_opening"));
    Object[] exit = {
      "check_name",
      "Emergency exit",
      "decision",
      "hazard",
      "evidence",
      "Exit obstructed",
      "action_id",
      id(),
      "revision",
      1
    };
    var blocked = opening(site, "record_store_check", exit);
    assertEquals("blocked", service.def(blocked).get("state"));
    assertEquals(blocked, opening(site, "record_store_check", exit));
    assertThrows(
        Failure.class,
        () ->
            opening(
                site,
                "record_store_check",
                "check_name",
                "Emergency exit",
                "decision",
                "pass",
                "evidence",
                "Other",
                "action_id",
                exit[7],
                "revision",
                2));
    assertThrows(
        Failure.class,
        () ->
            opening(
                site,
                "record_store_check",
                "check_name",
                "Emergency exit",
                "decision",
                "pass",
                "evidence",
                "Other",
                "action_id",
                id(),
                "revision",
                2));
    assertThrows(
        Failure.class,
        () ->
            opening(
                site,
                "correct_store_hazard",
                "check_name",
                "X",
                "evidence",
                "Work",
                "action_id",
                id(),
                "revision",
                2));
    assertThrows(
        Failure.class,
        () ->
            opening(
                site,
                "correct_store_hazard",
                "check_name",
                "Till",
                "evidence",
                "Work",
                "action_id",
                id(),
                "revision",
                2));
    h.as(h.b);
    opening(
        site,
        "correct_store_hazard",
        "check_name",
        "Emergency exit",
        "evidence",
        "Removed pallet",
        "action_id",
        id(),
        "revision",
        2);
    assertThrows(
        Failure.class,
        () ->
            opening(
                site,
                "verify_store_hazard",
                "check_name",
                "Emergency exit",
                "decision",
                "release",
                "evidence",
                "self",
                "action_id",
                id(),
                "revision",
                3));
    h.as(h.admin);
    String safety = h.user("SafetyReviewer", "agent");
    h.db.update("INSERT INTO os_group_member(group_id,user_id) VALUES(?,?)", h.second, safety);
    h.as(safety);
    assertThrows(
        Failure.class,
        () ->
            opening(
                site,
                "record_store_check",
                "check_name",
                "Till",
                "decision",
                "pass",
                "evidence",
                "Ready",
                "action_id",
                id(),
                "revision",
                3));
    assertThrows(
        Failure.class,
        () ->
            opening(
                site,
                "verify_store_hazard",
                "check_name",
                "Till",
                "decision",
                "release",
                "evidence",
                "No pending test",
                "action_id",
                id(),
                "revision",
                3));
    opening(
        site,
        "verify_store_hazard",
        "check_name",
        "Emergency exit",
        "decision",
        "reject",
        "evidence",
        "Door jams",
        "action_id",
        id(),
        "revision",
        3);
    h.as(h.a);
    opening(
        site,
        "correct_store_hazard",
        "check_name",
        "Emergency exit",
        "evidence",
        "Repaired latch",
        "action_id",
        id(),
        "revision",
        4);
    h.as(safety);
    var cleared =
        opening(
            site,
            "verify_store_hazard",
            "check_name",
            "Emergency exit",
            "decision",
            "release",
            "evidence",
            "Functional test",
            "action_id",
            id(),
            "revision",
            5);
    assertEquals("pending", service.def(cleared).get("state"));
    h.as(h.a);
    var ready =
        opening(
            site,
            "record_store_check",
            "check_name",
            "Till",
            "decision",
            "pass",
            "evidence",
            "Counted",
            "action_id",
            id(),
            "revision",
            6);
    assertEquals("ready", service.def(ready).get("state"));
    assertEquals(6, ((List<?>) service.def(ready).get("history")).size());
    assertEquals(ready, opening(site, "store_opening_status"));
    h.as(h.admin);
    assertThrows(Failure.class, () -> opening(site, "store_opening_status"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void qualityEvidenceSurvivesEditsAndHonorsCurrentPermission(boolean pg) throws Exception {
    open(pg);
    String product = id(),
        record = h.record("incident"),
        other = h.record("incident"),
        check = id();
    apps(
        "publish_data_product",
        "new_id",
        product,
        "title",
        "Quality",
        "group_id",
        h.team,
        "table",
        "incident",
        "fields",
        List.of("short_description", "description"),
        "revision",
        0);
    for (var invalid : List.of(map(), map("private", List.of())))
      assertThrows(
          Failure.class,
          () -> apps("data_quality_rules", "new_id", product, "manifest", invalid, "revision", 0));
    h.as(h.a);
    Object[] old = {"new_id", product, "record_id", record, "check_id", check};
    assertThrows(Failure.class, () -> apps("check_data_quality", old));
    h.as(h.admin);
    apps(
        "data_quality_rules",
        "new_id",
        product,
        "manifest",
        map("short_description", List.of("Ready"), "description", List.of()),
        "revision",
        0);
    h.as(h.a);
    var failed = apps("check_data_quality", old);
    assertEquals("failed", service.def(failed).get("outcome"));
    String movedCheck = id();
    var movedObservation = new LinkedHashMap<>(service.def(failed));
    movedObservation.put("source_table","retired_source");
    h.seed("data-quality-observation",DeliveryWorkspace.key(product,movedCheck),movedObservation);
    assertThrows(Failure.class,()->apps("check_data_quality","new_id",product,"record_id",record,"check_id",movedCheck));
    assertEquals(
        map("short_description", "outside_allowed_values", "description", "missing"),
        service.def(failed).get("failures"));
    assertThrows(
        Failure.class,
        () -> apps("check_data_quality", "new_id", product, "record_id", other, "check_id", check));
    h.as(h.admin);
    var src = Service.readRecord(h.db, "incident", record);
    Service.update(
        h.db,
        "incident",
        src,
        map("short_description", "Ready", "description", "Checked"),
        "admin",
        "admin",
        src.get("revision").toString());
    h.as(h.a);
    assertEquals(failed, apps("check_data_quality", old));
    var passed =
        apps("check_data_quality", "new_id", product, "record_id", record, "check_id", id());
    assertEquals("passed", service.def(passed).get("outcome"));
    assertNotEquals(
        service.def(failed).get("source_revision"), service.def(passed).get("source_revision"));
    var schema = Service.schema(h.db, "incident");
    schema.put(
        "field_rules", map("description", map("type", "string", "read_roles", List.of("admin"))));
    h.db.update(
        "UPDATE os_schema SET definition="
            + (pg ? "CAST(? AS JSON)" : "?")
            + " WHERE name='incident'",
        JSON.writeValueAsString(schema));
    assertThrows(Failure.class, () -> apps("check_data_quality", old));
    h.as(h.admin);
    apps(
        "publish_data_product",
        "new_id",
        product,
        "title",
        "Quality",
        "group_id",
        h.team,
        "table",
        "incident",
        "fields",
        List.of("short_description"),
        "revision",
        1);
    h.as(h.a);
    assertThrows(Failure.class, () -> apps("check_data_quality", old));
    h.as(h.admin);
    apps(
        "publish_data_product",
        "new_id",
        product,
        "title",
        "Assets",
        "group_id",
        h.team,
        "table",
        "cmdb_ci",
        "fields",
        List.of("short_description"),
        "revision",
        2);
    String ci = h.record("cmdb_ci");
    h.as(h.a);
    assertThrows(
        Failure.class,
        () -> apps("check_data_quality", "new_id", product, "record_id", ci, "check_id", check));
  }

  Map<String, Object> major(String record, String op, Object... values) throws Exception {
    var data = map(values);
    data.putAll(
        map(
            "operation",
            op,
            "revision",
            MajorIncidents.major(h.db, record).get("revision"),
            "record_revision",
            Service.readRecord(h.db, "incident", record).get("revision")));
    return MajorIncidents.command(h.db, record, data);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void majorReportAmendmentsAndMigrationPreserveOnlyObservedHistory(boolean pg) throws Exception {
    open(pg);
    String incident = h.record("incident");
    major(incident, "propose", "reason", "Multiple customers", "impact", "Unavailable");
    major(
        incident,
        "accept",
        "reason",
        "Declared",
        "commander",
        "reviewer",
        "impact",
        "Checkout unavailable");
    h.as(h.a);
    major(incident, "restore", "outcome", "Transactions succeeded");
    var reviewed =
        major(
            incident,
            "review",
            "cause",
            "Timeout",
            "lessons",
            "Monitor checkout",
            "actions",
            "Test failover");
    var frozen = objects(reviewed.get("review_versions")).getFirst();
    var amended =
        major(
            incident,
            "review",
            "cause",
            "Pool exhausted",
            "lessons",
            "Measure saturation",
            "actions",
            "Increase pool");
    var versions = objects(amended.get("review_versions"));
    assertEquals(2, versions.size());
    assertEquals(frozen, versions.getLast());
    assertEquals(h.a, frozen.get("reviewer"));
    Object[] at = {"incident_id", incident, "revision", frozen.get("revision")};
    assertEquals("Timeout", service.cmd("topology", "major_review_version", at).get("cause"));
    assertThrows(
        Failure.class,
        () ->
            service.cmd(
                "topology", "major_review_version", "incident_id", incident, "revision", 50));
    h.as(h.c);
    assertThrows(Failure.class, () -> service.cmd("topology", "major_review_version", at));
    h.as(h.admin);
    major(incident, "close", "reason", "Verified");
    h.db.update("DROP TABLE os_major_report");
    h.db.update("DROP TABLE os_problem_feedback");
    h.db.update("DROP TABLE os_problem_delivery");
    h.db.update("DROP TABLE os_problem_article");
    h.db.update("DROP TABLE os_problem_guidance");
    h.db.update("DELETE FROM os_migration WHERE version>=103");
    Migrations.apply(h.db);
    var retained = h.db.all("SELECT * FROM os_major_report WHERE incident_id=?", incident);
    assertEquals(1, retained.size());
    assertEquals("migration_baseline", retained.getFirst().get("source"));
    assertNull(retained.getFirst().get("reviewer"));
    assertEquals("Pool exhausted", retained.getFirst().get("cause"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void insuranceAndDisputesReconcileOnlyIndependentMatchingStatementEntries(boolean pg)
      throws Exception {
    open(pg);
    String policy = id(), claim = id();
    var terms =
        map(
            "start",
            "2026-01-01",
            "end",
            "2026-12-31",
            "perils",
            List.of("water"),
            "currency",
            "USD",
            "deductible",
            "100",
            "limit",
            "400");
    for (var change :
        List.of(
            map("start", "2027-01-01"),
            map("perils", List.of()),
            map("limit", "0"),
            map("currency", "xx"))) {
      var bad = new LinkedHashMap<>(terms);
      bad.putAll(change);
      assertThrows(
          Failure.class,
          () ->
              apps("register_claim_policy", "policy_id", policy, "user_id", h.c, "manifest", bad));
    }
    apps("case_program", "kind", "insurance", "group_id", h.team, "revision", 0);
    apps("register_claim_policy", "policy_id", policy, "user_id", h.c, "manifest", terms);
    var loss =
        map(
            "policy_id",
            policy,
            "loss_date",
            "2026-01-01",
            "peril",
            "water",
            "amount",
            "550",
            "currency",
            "USD",
            "reason",
            "Damage");
    assertThrows(
        Failure.class,
        () ->
            apps(
                "file_industry_case",
                "kind",
                "insurance",
                "new_id",
                claim,
                "reference",
                "L1",
                "manifest",
                loss));
    h.as(h.c);
    for (var change :
        List.of(
            map("loss_date", "2025-12-31"),
            map("loss_date", "2027-01-01"),
            map("currency", "EUR"),
            map("amount", "0"),
            map("peril", "wind"))) {
      var bad = new LinkedHashMap<>(loss);
      bad.putAll(change);
      assertThrows(
          Failure.class,
          () ->
              apps(
                  "file_industry_case",
                  "kind",
                  "insurance",
                  "new_id",
                  claim,
                  "reference",
                  "L1",
                  "manifest",
                  bad));
    }
    apps(
        "file_industry_case",
        "kind",
        "insurance",
        "new_id",
        claim,
        "reference",
        "L1",
        "manifest",
        loss);
    String denied=id();
    apps("file_industry_case","kind","insurance","new_id",denied,"reference","denied-loss","manifest",loss);
    h.as(h.a);
    apps("advance_industry_case","new_id",denied,"decision","adjust","evidence","Reviewed loss","revision",1);
    h.as(h.b);
    var deniedResult=service.def(apps("advance_industry_case","new_id",denied,"decision","deny",
        "evidence","Independent adjuster rejected the loss","revision",2));
    assertEquals("deny",deniedResult.get("state"));
    assertFalse(deniedResult.containsKey("adjustment"));
    h.as(h.a);
    apps(
        "advance_industry_case",
        "new_id",
        claim,
        "decision",
        "adjust",
        "evidence",
        "Loss verified",
        "revision",
        1);
    h.as(h.b);
    var approved =
        apps(
            "advance_industry_case",
            "new_id",
            claim,
            "decision",
            "approve",
            "evidence",
            "Independent review",
            "revision",
            2);
    assertEquals("400.00", object(service.def(approved).get("adjustment")).get("amount"));
    h.as(h.admin);
    var line =
        map(
            "case_id",
            claim,
            "user_id",
            h.c,
            "effective",
            "2026-02-01",
            "amount",
            "400",
            "currency",
            "USD");
    var invalid = new LinkedHashMap<>(line);
    invalid.put("amount", "0");
    assertThrows(
        Failure.class,
        () ->
            apps(
                "import_case_payment",
                "source",
                "Ledger",
                "reference",
                "Zero",
                "manifest",
                invalid));
    for (var change :
        List.of(
            map("case_id", id()),
            map("user_id", h.a),
            map("amount", "401"),
            map("currency", "EUR"))) {
      var bad = new LinkedHashMap<>(line);
      bad.putAll(change);
      String key = id();
      apps("import_case_payment", "source", "Ledger", "reference", key, "manifest", bad);
      assertThrows(
          Failure.class,
          () ->
              apps(
                  "reconcile_case_payment",
                  "source",
                  "Ledger",
                  "reference",
                  key,
                  "case_id",
                  claim,
                  "revision",
                  3));
    }
    for (String ref : List.of("P1", "P2"))
      apps("import_case_payment", "source", "Ledger", "reference", ref, "manifest", line);
    Object[] bind = {"source", "Ledger", "reference", "P1", "case_id", claim, "revision", 3};
    for (String restricted : List.of(h.b, h.c)) {
      h.db.update("UPDATE os_user SET role='admin' WHERE user_id=?", restricted);
      h.as(restricted);
      assertThrows(Failure.class, () -> apps("reconcile_case_payment", bind));
    }
    h.as(h.admin);
    var reconciled = apps("reconcile_case_payment", bind);
    assertEquals(reconciled, apps("reconcile_case_payment", bind));
    assertThrows(
        Failure.class,
        () ->
            apps(
                "reconcile_case_payment",
                "source",
                "Ledger",
                "reference",
                "P2",
                "case_id",
                claim,
                "revision",
                4));
    h.db.update("UPDATE os_user SET role='requester' WHERE user_id=?", h.c);
    h.as(h.c);
    assertEquals(
        "P1",
        object(service.def(apps("industry_case", "new_id", claim)).get("settlement"))
            .get("reference"));
  }
}
