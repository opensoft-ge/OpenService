package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.key;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.definition;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ServiceAssuranceTest {
  @TempDir Path root;
  IndustryWorkflowsTest h = new IndustryWorkflowsTest();

  void open(boolean pg) throws Exception {
    h.root = root;
    h.open(pg);
  }

  @AfterEach
  void close() throws Exception {
    h.close();
  }

  Map<String, Object> cmd(String area, String op, Object... body) throws Exception {
    return object(h.run(area, op, body));
  }

  Map<String, Object> def(Map<String, Object> row) {
    return object(row.get("definition"));
  }

  String stamp(long ms) {
    return Instant.ofEpochMilli(ms).toString();
  }

  void fact(String reference, String day, Map<String, Object> manifest) throws Exception {
    cmd(
        "licenses",
        "license_fact",
        "product",
        "Editor",
        "reference",
        reference,
        "effective",
        day,
        "manifest",
        manifest);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false,true})
  void repeatedSingleSignalConfirmsOnlyWithinCurrentBaseline(boolean pg) throws Exception {
    open(pg);
    String objective=objective();
    long cadence=60000, now=System.currentTimeMillis()/cadence*cadence-10*cadence, day=86400000L;
    var spec=definition(h.db,"service-objective",objective);spec.put("interval",(int)cadence);h.seed("service-objective",objective,spec);
    var train=new ArrayList<>();
    for(int slot=0;slot<24;slot++) for(int d=1;d<=3;d++)
      train.add(map("observed_at",stamp(now/day*day-d*day+slot*3600000L),"metrics",metrics(100,30,1)));
    cmd("mapping","metric_corroborated_baseline","objective_id",objective,"samples",train,"revision",0);
    for(int i=0;i<6;i++){
      if(i==2) cmd("mapping","metric_corroborated_baseline","objective_id",objective,"samples",train,"revision",1);
      var input=switch(i){case 0 -> map("latency_ms",150);case 3 -> metrics(100,40,1);case 4 -> metrics(110,40,1);case 5 -> metrics(100,30,4);default -> metrics(110,30,1);};
      var row=def(cmd("mapping","metric_sample","objective_id",objective,"observed_at",stamp(now+i*cadence),"manifest",input));
      assertEquals(List.of("unknown","down","unknown","unknown","down","down").get(i),row.get("state"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans={false,true})
  void currentCustomerProbeEvidenceDetectsAnInRangeFailure(boolean pg) throws Exception {
    open(pg);
    String objective=objective();
    long cadence=60000L,day=86400000L, now=System.currentTimeMillis()/cadence*cadence;
    var train=new ArrayList<>();
    for (int hour=0;hour<24;hour++) for(int d=1;d<=3;d++)
      train.add(map("observed_at",stamp(now/day*day-d*day+hour*3600000L),"metrics",metrics(100,30,2)));
    cmd("mapping","metric_corroborated_baseline","objective_id",objective,"samples",train,"revision",0);
    h.seed("transaction-rule",objective,map("expected","Checkout complete","maximum_ms",2000));
    var outcomes=new ArrayList<>();
    for(int i=0;i<5;i++) {
      long at=now-(4-i)*cadence;
      for(String site:List.of("east","west")){
        int version=i==0?0:1;
        String result=i==1 && site.equals("west")?"probe_error":i==2?"up":"down";
        h.db.update("INSERT INTO os_transaction_sample(objective,location,bucket,detail) VALUES(?,?,?,?)",
            objective,site,at,JSON.writeValueAsString(map("rule_revision",version,"result",result)));
      }
      var raw=i==4?map():metrics(98,24,1);
      Object[] input={"objective_id",objective,"observed_at",stamp(at),"manifest",raw};
      var row=def(cmd("mapping","metric_sample",input));
      assertEquals(List.of("up","up","up","down","unknown").get(i),row.get("state"));
      assertEquals(List.of(0,1,0,2,0).get(i),object(row.get("transaction_evidence")).size());
      assertEquals(row,def(cmd("mapping","metric_sample",input)));
      outcomes.add(map("observed_at",stamp(at),"state",row.get("state")));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false,true})
  void namedUserRightsChargeOnceAcrossDevices(boolean pg) throws Exception {
    open(pg);
    String x=h.record("cmdb_ci"), y=h.record("cmdb_ci"), z=h.record("cmdb_ci");
    fact("grant","2025-01-01",map("kind","rights","metric","named_user","contract","annual","quantity",1,"covers",List.of("A","B")));
    for(String ci:List.of(x,y,z))
      fact(ci,"2025-01-02",map("kind","installation","ci",ci,"edition",ci.equals(x)?"A":"B","last_used","2025-01-01","user_id",ci.equals(z)?h.b:h.a));
    String snapshot=id();
    Object[] args={"product","Editor","effective","2025-01-31","new_id",snapshot};
    var report=def(cmd("licenses","license_position",args));
    assertEquals("named_user",report.get("metric"));
    assertEquals(3,object(report.get("installations")).size());
    assertEquals(2,object(report.get("consumption_by_edition")).size());
    assertEquals(1,object(report.get("assignments")).size());
    assertEquals(1,((List<?>)report.get("shortfall")).size());
    assertThrows(Failure.class,()->fact("invalid","2025-02-01",map("kind","rights","metric","installation")));
    fact("renew","2025-02-01",map("kind","rights","metric","named_user","contract","annual","quantity",1,"covers",List.of("A","B")));
    assertEquals(report,def(cmd("licenses","license_position",args)));
    var after=def(cmd("licenses","license_position","product","Editor","effective","2025-02-01","new_id",id()));
    assertTrue(((List<?>)after.get("shortfall")).isEmpty());
    fact("owner-conflict","2025-01-02",map("kind","installation","ci",x,"edition","A","last_used","2025-01-01","user_id",h.b));
    assertThrows(Failure.class,()->cmd("licenses","license_position","product","Editor","effective","2025-02-01","new_id",id()));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false,true})
  void cannotRelabelHistoricalInstallationRights(boolean pg) throws Exception {
    open(pg);
    var grant=map("kind","rights","contract","one","quantity",1,"covers",List.of("A"));
    fact("first","2025-01-01",grant);
    grant.put("metric","named_user");
    assertThrows(Failure.class,()->fact("change","2025-01-02",grant));
    grant.put("metric","installation");
    fact("second","2025-01-02",grant);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void publisherMonthEndSurvivesLaterCredit(boolean pg) throws Exception {
    open(pg);
    var devices = new ArrayList<>(List.of(h.record("cmdb_ci"), h.record("cmdb_ci")));
    Collections.sort(devices);
    // The narrow edition arrives after the flexible one, requiring reassignment in the first month.
    String a = devices.getFirst(), b = devices.getLast(), c = h.record("cmdb_ci");
    String date = "2025-05-31", snap = id();
    fact(
        "flex",
        "2025-01-01",
        map("kind", "rights", "contract", "A", "covers", List.of("Pro 2", "Std 1"), "quantity", 1));
    fact(
        "std",
        "2025-01-01",
        map("kind", "rights", "contract", "B", "covers", List.of("Std 1"), "quantity", 1));
    fact("a", date, map("kind", "installation", "ci", a, "edition", "Std 1", "last_used", date));
    fact("b", date, map("kind", "installation", "ci", b, "edition", "Pro 2", "last_used", date));
    fact("c", date, map("kind", "installation", "ci", c, "edition", "Ultra", "last_used", date));
    var report =
        def(
            cmd(
                "licenses",
                "license_position",
                "product",
                "Editor",
                "effective",
                date,
                "new_id",
                snap));
    assertEquals(map(a, "B", b, "A"), report.get("assignments"));
    assertEquals(List.of(c), report.get("shortfall"));
    assertEquals(0, report.get("surplus"));
    fact(
        "credit",
        "2025-06-01",
        map("kind", "rights", "contract", "B", "covers", List.of("Std 1"), "quantity", -1));
    assertEquals(
        report,
        def(
            cmd(
                "licenses",
                "license_position",
                "product",
                "Editor",
                "effective",
                date,
                "new_id",
                snap)));
    var june =
        def(
            cmd(
                "licenses",
                "license_position",
                "product",
                "Editor",
                "effective",
                "2025-06-01",
                "new_id",
                id()));
    assertEquals(2, ((List<?>) june.get("shortfall")).size());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void intuneSnapshotsKeepIdentityAcrossRenamesAndIncompletePages(boolean pg) throws Exception {
    open(pg);
    String alias = "fleet",
        source = IntuneInventory.source(alias),
        run = id(),
        a = "managed-a",
        b = "managed-b";
    var db = h.db;
    var first =
        map("id", a, "deviceName", "desktop", "operatingSystem", "Windows", "serialNumber", "old");
    IntuneInventory.accept(
        db,
        run,
        alias,
        1,
        map(
            "value",
            List.of(first),
            "@odata.nextLink",
            IntuneInventory.ORIGIN + IntuneInventory.PATH + "?$skiptoken=1"));
    assertEquals(0, get(db, "service-map", source).get("revision"));
    var reimage =
        map("id", b, "deviceName", "desktop", "operatingSystem", "Windows", "serialNumber", "new");
    IntuneInventory.accept(db, run, alias, 1, map("value", List.of(reimage)));
    var map = object(definition(db, "service-map", source).get("resources"));
    assertNotEquals(object(map.get(a)).get("ci_id"), object(map.get(b)).get("ci_id"));
    String cloud = "cloud";
    ServiceTopology.inventory(
        db,
        cloud,
        map(
            "sequence",
            1,
            "resources",
            List.of(
                map("id", "vm", "type", "server", "title", "cloud host", "depends_on", List.of())),
            "services",
            List.of()));
    String ci =
        object(object(definition(db, "service-map", cloud).get("resources")).get("vm"))
            .get("ci_id")
            .toString();
    cmd(
        "mapping",
        "intune_bind",
        "alias",
        alias,
        "reference",
        a,
        "source",
        cloud,
        "resource_id",
        "vm",
        "revision",
        0);
    first.put("deviceName", "new-hostname");
    IntuneInventory.accept(db, id(), alias, 2, map("value", List.of(first)));
    assertTrue(IntuneInventory.observes(db, ci));
    var item = cmd("mapping", "intune_device", "alias", alias, "reference", a);
    assertEquals(ci, object(item.get("observation")).get("ci_id"));
    assertEquals("new-hostname", object(item.get("facts")).get("hostname"));
    String collector = id();
    EndpointCollectors.run(
        db,
        "enroll_endpoint",
        map(
            "collector_id",
            collector,
            "user_id",
            h.a,
            "source",
            cloud,
            "resource_id",
            "vm",
            "minutes",
            5,
            "platform",
            "Windows"));
    ServiceTopology.inventory(
        db, cloud, map("sequence", 2, "resources", List.of(), "services", List.of()));
    assertEquals("enrolled", definition(db, "endpoint-enrollment", collector).get("state"));
    assertThrows(
        Failure.class,
        () ->
            IntuneInventory.continuation(
                "https://example.com/v1.0/deviceManagement/managedDevices?$skip=1"));
    IntuneInventory.accept(db, id(), alias, 2, map("value", List.of()));
    assertFalse(IntuneInventory.observes(db, ci));
    assertEquals("retired", definition(db, "endpoint-enrollment", collector).get("state"));
    assertNotNull(Service.readRecord(db, "cmdb_ci", ci));
  }

  String objective() throws Exception {
    String roster = id(), obj = id();
    ServiceTopology.inventory(
        h.db,
        "cloud",
        map(
            "sequence",
            1,
            "resources",
            List.of(
                map(
                    "id",
                    "app",
                    "type",
                    "application",
                    "title",
                    "Checkout",
                    "depends_on",
                    List.of())),
            "services",
            List.of(map("id", "shop", "title", "Shop", "roots", List.of("app")))));
    h.seed(
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
            List.of(h.admin, h.a),
            "minutes",
            5));
    cmd(
        "mapping",
        "define_objective",
        "objective_id",
        obj,
        "source",
        "cloud",
        "resource_id",
        "app",
        "location",
        "east",
        "backup_location",
        "west",
        "roster_id",
        roster,
        "minutes",
        1,
        "target_bps",
        9900);
    return obj;
  }

  Map<String, Object> metrics(int latency, int sat, int err) {
    return map("latency_ms", latency, "saturation_pct", sat, "error_pct", err);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void scoreUnseenMeasurementsAndPageOnce(boolean pg) throws Exception {
    open(pg);
    String obj = objective();
    long day = 86400000L, at = System.currentTimeMillis() / day * day - 2 * day;
    assertEquals(true, cmd("mapping", "metric_status", "objective_id", obj).get("stale"));
    var train = new ArrayList<>();
    for (int i = 1; i <= 3; i++)
      train.add(map("observed_at", stamp(at - i * day), "metrics", metrics(100 * i, 20 * i, i)));
    cmd("mapping", "metric_baseline", "objective_id", obj, "samples", train, "revision", 0);
    var labels = new ArrayList<>();
    var states = List.of("up", "down", "unknown", "down", "up");
    var measurements =
        List.of(
            metrics(280, 55, 1),
            metrics(1000, 80, 6),
            map(),
            metrics(1100, 80, 5),
            metrics(200, 30, 1));
    for (int i = 0; i < states.size(); i++) {
      String time = stamp(at + i * 10000L);
      labels.add(map("observed_at", time, "state", states.get(i)));
      var result =
          cmd(
              "mapping",
              "metric_sample",
              "objective_id",
              obj,
              "observed_at",
              time,
              "manifest",
              measurements.get(i));
      assertEquals(states.get(i), def(result).get("state"));
      assertEquals(
          result,
          cmd(
              "mapping",
              "metric_sample",
              "objective_id",
              obj,
              "observed_at",
              time,
              "manifest",
              measurements.get(i)));
    }
    var score = cmd("mapping", "metric_score", "objective_id", obj, "samples", labels);
    assertEquals(0, score.get("false_alarms"));
    assertEquals(0, score.get("missed_degradations"));
    assertEquals(1, score.get("unknown"));
    assertEquals(1, list(h.db, "service-episode").size());
    assertEquals(1, list(h.db, "incident-page").size());
    var incomplete =
        def(
            cmd(
                "mapping",
                "metric_sample",
                "objective_id",
                obj,
                "observed_at",
                stamp(at + 51000),
                "manifest",
                map("latency_ms", 150)));
    assertEquals("unknown", incomplete.get("state"));
    assertThrows(
        Failure.class,
        () ->
            cmd(
                "mapping",
                "metric_sample",
                "objective_id",
                obj,
                "observed_at",
                stamp(at + 51000),
                "manifest",
                map("latency_ms", 151)));
    var truth =
        List.of(
            map("observed_at", stamp(at), "state", "down"),
            map("observed_at", stamp(at + 10000), "state", "up"));
    var disagreement = cmd("mapping", "metric_score", "objective_id", obj, "samples", truth);
    assertEquals(1, disagreement.get("false_alarms"));
    assertEquals(1, disagreement.get("missed_degradations"));
    var status = cmd("mapping", "metric_status", "objective_id", obj);
    assertEquals("unknown", status.get("state"));
    assertEquals(true, status.get("stale"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void deliveryMeasuresLateEvidence(boolean pg) throws Exception {
    open(pg);
    String release = id(), change = h.record("change_request"), inc = h.record("incident");
    long at = Instant.parse("2025-01-05T12:00:00Z").toEpochMilli();
    h.seed(
        "pipeline-release",
        release,
        map(
            "change_id",
            change,
            "reported_at",
            "2025-01-05 12:00:00",
            "deployment",
            "deploy:2",
            "state",
            "deployed"));
    cmd("releases", "track_delivery", "group_id", h.team, "release_id", release);
    for (var e :
        List.of(
            List.of("commit", "sha1", at - 600000), List.of("pull_request", "pr/9", at - 300000))) {
      cmd(
          "releases",
          "delivery_event",
          "group_id",
          h.team,
          "release_id",
          release,
          "kind",
          e.get(0),
          "reference",
          e.get(1),
          "observed_at",
          stamp((Long) e.get(2)));
    }
    Object[] window = {
      "group_id", h.team, "start", stamp(at - 86400000L), "end", stamp(at + 86400000L)
    };
    var prior = cmd("releases", "delivery_performance", window);
    assertEquals(600000.0, prior.get("mean_lead_ms"));
    assertEquals(0, prior.get("failed"));
    for (int i = 0; i < 2; i++) {
      cmd(
          "releases",
          "delivery_event",
          "group_id",
          h.team,
          "release_id",
          release,
          "kind",
          "incident",
          "reference",
          inc,
          "observed_at",
          stamp(at + 10000));
      cmd(
          "releases",
          "delivery_event",
          "group_id",
          h.team,
          "release_id",
          release,
          "kind",
          "restored",
          "reference",
          "incident-recovered",
          "observed_at",
          stamp(at + 50000));
    }
    var report = cmd("releases", "delivery_performance", window);
    assertEquals(1, report.get("deployments"));
    assertEquals(1.0, report.get("failure_rate"));
    assertEquals(40000.0, report.get("mean_recovery_ms"));
    assertEquals(List.of("build"), objects(report.get("releases")).getFirst().get("missing"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void permanentFixRequiresBoardAndContinuousHealth(boolean pg) throws Exception {
    open(pg);
    String objective = objective(),
        ci = definition(h.db, "service-objective", objective).get("ci").toString(),
        release = id(),
        problem = h.record("problem"),
        change = h.record("change_request");
    long at = System.currentTimeMillis() / 60000 * 60000 - 600000;
    for (int i = 0; i < 3; i++) {
      String incident = h.record("incident");
      h.db.update(
          "INSERT INTO os_problem_link(incident_id,problem_id) VALUES(?,?)", incident, problem);
      h.seed("service-episode", id(), map("ci", ci, "incident", incident, "began", at - 100000));
    }
    h.db.update(
        "INSERT INTO os_problem_analysis(problem_id,revision,cause,workaround,published)"
            + " VALUES(?,1,'queue stuck','restart consumer',1)",
        problem);
    var pipe =
        map(
            "state",
            "awaiting_review",
            "change_id",
            change,
            "artifact",
            "sha-1",
            "policy",
            map("ci", ci));
    h.seed("pipeline-release", release, pipe);
    Object[] proposal = {
      "problem_id", problem, "release_id", release, "objective_id", objective, "minutes", 2
    };
    var insufficient = h.db.connection.setSavepoint();
    h.db.update(
        "DELETE FROM os_problem_link WHERE incident_id=(SELECT MIN(incident_id) FROM"
            + " os_problem_link WHERE problem_id=?)",
        problem);
    assertThrows(Failure.class, () -> cmd("releases", "propose_fix", proposal));
    h.db.connection.rollback(insufficient);
    h.db.connection.releaseSavepoint(insufficient);
    var snapshot = h.db.connection.setSavepoint();
    var early = Service.readRecord(h.db, "problem", problem);
    object(early.get("data")).put("state", "closed");
    Service.save(h.db, early);
    assertThrows(Failure.class, () -> cmd("releases", "propose_fix", proposal));
    h.db.connection.rollback(snapshot);
    h.db.connection.releaseSavepoint(snapshot);
    h.seed("service-episode", id(), map("ci", id(), "incident", h.record("incident"), "began", at));
    cmd(
        "releases",
        "propose_fix",
        "problem_id",
        problem,
        "release_id",
        release,
        "objective_id",
        objective,
        "minutes",
        2);
    assertEquals(1, cmd("releases", "propose_fix", proposal).get("revision"));
    pipe.put("state", "failed");
    h.seed("pipeline-release", release, pipe);
    Object[] args = {"problem_id", problem, "release_id", release};
    assertEquals(
        "awaiting_successful_board_release", cmd("releases", "verify_fix", args).get("state"));
    pipe.putAll(
        map(
            "state",
            "deployed",
            "deployment",
            "build-13",
            "reported_at",
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(at))));
    h.seed("pipeline-release", release, pipe);
    assertEquals(
        "awaiting_successful_board_release", cmd("releases", "verify_fix", args).get("state"));
    h.seed("cab-window", release, map("state", "approved", "artifact", "different"));
    assertEquals(
        "awaiting_successful_board_release", cmd("releases", "verify_fix", args).get("state"));
    h.seed("cab-window", release, map("state", "approved", "artifact", "sha-1"));
    assertEquals("insufficient_health_evidence", cmd("releases", "verify_fix", args).get("state"));
    for (int i = 1; i <= 2; i++)
      for (String location : List.of("east", "west"))
        h.db.update(
            "INSERT INTO os_synthetic_sample(objective,bucket,location,result) VALUES(?,?,?,'up')",
            objective,
            at + i * 60000,
            location);
    for (String opened : List.of("2020-01-01 00:00:00", "2090-01-01 00:00:00")) {
      String linked = h.record("incident");
      h.db.update(
          "INSERT INTO os_problem_link(incident_id,problem_id) VALUES(?,?)", linked, problem);
      h.db.update("UPDATE os_record SET created=? WHERE sys_id=?", opened, linked);
    }
    String outside = h.record("incident");
    h.db.update(
        "INSERT INTO os_problem_link(incident_id,problem_id) VALUES(?,?)", outside, problem);
    h.seed("service-episode", id(), map("ci", ci, "incident", outside, "began", at + 180000));
    var nativeProblem = Service.readRecord(h.db, "problem", problem);
    for (String state : List.of("assess", "fix"))
      nativeProblem =
          Service.update(
              h.db,
              "problem",
              nativeProblem,
              map("state", state),
              "admin",
              "admin",
              nativeProblem.get("revision").toString());
    final var unverified = nativeProblem;
    assertThrows(
        Failure.class,
        () ->
            Service.update(
                h.db,
                "problem",
                unverified,
                map("state", "closed"),
                "admin",
                "admin",
                unverified.get("revision").toString()));
    assertEquals("verified", def(cmd("releases", "verify_fix", args)).get("state"));
    assertEquals("verified", def(cmd("releases", "verify_fix", args)).get("state"));
    var details = ProblemInvestigations.problem(h.db, problem);
    assertTrue(details.containsKey("verification"));
    h.as(h.a);
    assertFalse(ProblemInvestigations.problem(h.db, problem).containsKey("verification"));
    h.as(h.admin);
    nativeProblem = Service.readRecord(h.db, "problem", problem);
    Service.update(
        h.db,
        "problem",
        nativeProblem,
        map("state", "closed"),
        "admin",
        "admin",
        nativeProblem.get("revision").toString());
    String late = h.record("incident");
    h.db.update("INSERT INTO os_problem_link(incident_id,problem_id) VALUES(?,?)", late, problem);
    h.db.update("UPDATE os_record SET created=? WHERE sys_id=?", pipe.get("reported_at"), late);
    assertFalse(
        PermanentFixes.canClose(h.db, problem, definition(h.db, "problem-fix-current", problem)));
    assertEquals(List.of(late), def(cmd("releases", "verify_fix", args)).get("recurrence"));
    assertEquals("recurrence", def(cmd("releases", "verify_fix", args)).get("state"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void businessBacklogSurvivesTechnicalRecovery(boolean pg) throws Exception {
    open(pg);
    String objective = objective(),
        plan = id(),
        exercise = id(),
        roster = definition(h.db, "service-objective", objective).get("roster").toString();
    cmd(
        "continuity",
        "plan",
        "plan_id",
        plan,
        "title",
        "Checkout recovery",
        "component",
        ServiceTopology.identity("cloud", "app"),
        "service_id",
        "cloud :: shop",
        "roster_id",
        roster,
        "rto_minutes",
        30,
        "rpo_minutes",
        10,
        "steps",
        List.of(map("title", "Reopen checkout", "owner_id", h.admin)),
        "revision",
        0);
    String app = id(), site = h.record("cmdb_ci");
    h.seed("business-app", app, map("title", "Checkout"));
    h.seed("managed-supplier", SupplierGovernance.identity("Carrier"), map("owner", h.a));
    cmd(
        "continuity",
        "business_tolerance",
        "plan_id",
        plan,
        "revision",
        1,
        "manifest",
        map(
            "applications",
            List.of(app),
            "people",
            List.of(h.a),
            "sites",
            List.of(site),
            "suppliers",
            List.of("Carrier"),
            "fallback",
            "Take orders by phone",
            "maximum_minutes",
            30,
            "maximum_backlog",
            10));
    var edited =
        cmd(
            "continuity",
            "plan",
            "plan_id",
            plan,
            "title",
            "Revised steps",
            "component",
            ServiceTopology.identity("cloud", "app"),
            "service_id",
            "cloud :: shop",
            "roster_id",
            roster,
            "rto_minutes",
            30,
            "rpo_minutes",
            10,
            "steps",
            List.of(map("title", "Reopen checkout", "owner_id", h.admin)),
            "revision",
            2);
    assertTrue(def(edited).containsKey("business"));
    String later = id();
    for (String key : List.of(exercise, later)) {
      var started =
          def(cmd("continuity", "exercise", "plan_id", plan, "new_id", key, "revision", 3));
      String time = stamp(((Number) started.get("start")).longValue());
      cmd(
          "continuity",
          "task",
          "exercise_id",
          key,
          "step",
          1,
          "note",
          "Service is reachable",
          "revision",
          1);
      cmd(
          "continuity",
          "business_impact",
          "exercise_id",
          key,
          "quantity",
          key.equals(exercise) ? 25 : 4,
          "restored_at",
          time,
          "evidence",
          "Measured backlog",
          "revision",
          2);
      var result =
          def(
              cmd(
                  "continuity",
                  "measure",
                  "exercise_id",
                  key,
                  "restored_at",
                  time,
                  "restore_point",
                  time,
                  "evidence",
                  "Checkout passed",
                  "revision",
                  3));
      assertEquals(key.equals(exercise) ? "needs_correction" : "passed", result.get("state"));
      if (key.equals(exercise)) {
        h.as(result.get("owner").toString());
        cmd(
            "continuity",
            "correct",
            "exercise_id",
            key,
            "note",
            "Increase manual order capacity",
            "revision",
            4);
        h.as(h.admin);
      }
    }
    var verified =
        def(cmd("continuity", "retest", "exercise_id", exercise, "later_id", later, "revision", 5));
    assertEquals("verified", verified.get("state"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void composedExceptionPinsReviewAndResumesPurchases(boolean pg) throws Exception {
    open(pg);
    String design = id(),
        review = id(),
        action = id(),
        run = id(),
        source = h.record("sc_request"),
        supplier = "Vendor";
    var step =
        map(
            "kind",
            "team_approval",
            "title",
            "Legal",
            "group",
            h.team,
            "stage",
            1,
            "state",
            "pending");
    var parallel = new LinkedHashMap<>(step);
    parallel.putAll(map("title", "Security", "group", h.second));
    h.seed(
        "decision-release",
        review,
        map(
            "title",
            "Dual review",
            "publisher",
            h.admin,
            "publisher_revision",
            0,
            "days",
            2,
            "rows",
            List.of(
                map(
                    "id",
                    "default",
                    "condition",
                    "default",
                    "operand",
                    "default",
                    "steps",
                    List.of(step, parallel)))));
    h.seed(
        "purchase-action",
        action,
        map(
            "supplier",
            supplier,
            "currency",
            "USD",
            "bindings",
            map(
                "model",
                "model",
                "quantity",
                "quantity",
                "unit_cost",
                "unit_cost",
                "months",
                "months",
                "salvage",
                "salvage")));
    cmd(
        "designer",
        "exception_requisition",
        "record_id",
        source,
        "supplier",
        supplier,
        "manifest",
        map("PC", map("quantity", 1, "unit_cost", "10.00", "months", 24, "salvage", "0.00")),
        "revision",
        0);
    cmd(
        "designer",
        "exception_design",
        "design_id",
        design,
        "bindings",
        map("low", review, "medium", review, "high", review),
        "action_id",
        action,
        "revision",
        0);
    Object[] startInputs = {
      "design_id",
      design,
      "new_id",
      run,
      "user_id",
      h.c,
      "record_id",
      source,
      "amount",
      "11",
      "risk",
      "high",
      "location",
      "Tbilisi"
    };
    assertThrows(Failure.class, () -> cmd("designer", "start_exception", startInputs));
    assertEquals(0, get(h.db, "exception-started", source).get("revision"));
    cmd(
        "designer",
        "start_exception",
        "design_id",
        design,
        "new_id",
        run,
        "user_id",
        h.c,
        "record_id",
        source,
        "amount",
        "10",
        "risk",
        "high",
        "location",
        "Tbilisi");
    assertThrows(Failure.class, () -> cmd("designer", "advance_exception", "new_id", run));
    for (int i = 1; i <= 2; i++) {
      h.as(i == 1 ? h.a : h.b);
      WorkflowStudio.command(
          h.store,
          h.db,
          "advance",
          map("run_id", run, "step", i, "revision", i, "note", "Approved"));
    }
    h.as(h.admin);
    assertTrue(SupplierExceptions.resumeReady(h.store, h.db));
    assertEquals("waiting_for_contract", definition(h.db, "exception-run", run).get("state"));
    assertFalse(SupplierExceptions.resumeReady(h.store, h.db));
    h.seed(
        "supplier-agreement",
        SupplierGovernance.identity(supplier),
        map(
            "signed",
            List.of(
                map(
                    "version",
                    1,
                    "effective",
                    "2024-01-01",
                    "currency",
                    "USD",
                    "prices",
                    map("PC", "10")))));
    h.seed("purchase-action-hold", action, map("held", true));
    assertTrue(SupplierExceptions.resumeReady(h.store, h.db));
    assertEquals("needs_retry", definition(h.db, "exception-run", run).get("state"));
    h.seed("purchase-action", action, map("supplier", "Changed"));
    h.seed("purchase-action-hold", action, map("held", false));
    var agreement = definition(h.db, "supplier-agreement", SupplierGovernance.identity(supplier));
    agreement.put("decision", map("kind", "terminate", "effective", "2024-02-01", "version", 1));
    h.seed("supplier-agreement", SupplierGovernance.identity(supplier), agreement);
    var denied = def(cmd("designer", "advance_exception", "new_id", run));
    assertEquals("needs_retry", denied.get("state"));
    assertTrue(list(h.db, "purchase").isEmpty());
    agreement.remove("decision");
    h.seed("supplier-agreement", SupplierGovernance.identity(supplier), agreement);
    var result = def(cmd("designer", "advance_exception", "new_id", run));
    assertEquals("ordered", result.get("state"));
    String order = object(object(result.get("lines")).get("PC")).get("order").toString();
    assertEquals(supplier, definition(h.db, "purchase", order).get("supplier"));
    assertEquals(1, object(definition(h.db, "purchase", order).get("signed_terms")).get("version"));
    var cancelled =
        def(
            cmd(
                "designer",
                "cancel_exception",
                "new_id",
                run,
                "owner_id",
                h.a,
                "note",
                "Rejected by customer"));
    assertEquals("reversal_review", object(object(cancelled.get("lines")).get("PC")).get("state"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sharedAlarmProtectsBackupAndRestoresOnlyVerifiedSite(boolean pg) throws Exception {
    open(pg);
    String objective = objective(),
        port = id(),
        standby = id(),
        account = h.account(),
        otherAccount = h.account(),
        other = h.user("otherCustomer", "requester");
    String ent = id(),
        east = id(),
        west = id(),
        eastKey = key(east, "East"),
        westKey = key(west, "West");
    ServiceClocks.calendar(
        h.db, h.admin, map("name", "support", "calendar", PlatformFoundationTest.calendar()));
    cmd(
        "mapping",
        "transaction_rule",
        "objective_id",
        objective,
        "expected",
        "checkout ok",
        "limit",
        500,
        "revision",
        0);
    for (String link : List.of(port, standby))
      cmd(
          "sales",
          "network_port",
          "port_id",
          link,
          "service_ci",
          h.record("cmdb_ci"),
          "capacity",
          2,
          "title",
          "Fiber");
    for (String bundle : List.of(east, west)) {
      boolean primary = bundle.equals(east);
      String site = primary ? "East" : "West",
          key = primary ? eastKey : westKey,
          sale = id(),
          customer = primary ? h.c : other,
          acc = primary ? account : otherAccount,
          e = primary ? ent : id();
      h.db.update(
          "INSERT INTO os_customer_contact(user_id,account_id,active,revision) VALUES(?,?,1,1)",
          customer,
          acc);
      h.db.update(
          "INSERT INTO"
              + " os_customer_entitlement(entitlement_id,account_id,name,description,active,starts,ends,case_limit,revision)"
              + " VALUES(?,?,'Support','',1,0,?,10,1)",
          e,
          acc,
          System.currentTimeMillis() + 86400000L);
      h.seed(
          "opportunity",
          sale,
          map(
              "account",
              acc,
              "state",
              "installed",
              "entitlement",
              e,
              "order_id",
              id(),
              "asset",
              h.record("alm_asset")));
      h.seed(
          "site-bundle",
          bundle,
          map("state", "ordered", "account", acc, "sites", map(site, map("opportunity", sale))));
      cmd(
          "sales",
          "reserve_circuit",
          "bundle_id",
          bundle,
          "location",
          site,
          "quantity",
          1,
          "entries",
          List.of(port));
      cmd("sales", "activate_circuit", "bundle_id", bundle, "location", site, "revision", 1);
      cmd(
          "impact",
          "bind_site",
          "bundle_id",
          bundle,
          "site",
          site,
          "service",
          "cloud :: shop",
          "contact_id",
          customer,
          "entitlement_id",
          e,
          "calendar",
          "support",
          "minutes",
          60,
          "revision",
          0);
    }
    cmd(
        "sales",
        "backup_path",
        "bundle_id",
        west,
        "location",
        "West",
        "entries",
        List.of(standby),
        "revision",
        2);
    cmd(
        "sales",
        "circuit_probe",
        "bundle_id",
        east,
        "location",
        "East",
        "objective_id",
        objective,
        "revision",
        2);

    Object[] westBackup = {
      "bundle_id", west, "location", "West", "entries", List.of(standby), "revision", 2
    };
    assertEquals(3, cmd("sales", "backup_path", westBackup).get("revision"));
    for (var invalid : List.of(List.of(), List.of(standby, standby), List.of(port), List.of(id())))
      assertThrows(
          Failure.class,
          () ->
              cmd(
                  "sales",
                  "backup_path",
                  "bundle_id",
                  west,
                  "location",
                  "West",
                  "entries",
                  invalid,
                  "revision",
                  3));
    Object[] eastProbe = {
      "bundle_id", east, "location", "East", "objective_id", objective, "revision", 3
    };
    assertThrows(
        Failure.class,
        () ->
            cmd(
                "sales",
                "circuit_probe",
                "bundle_id",
                west,
                "location",
                "West",
                "objective_id",
                objective,
                "revision",
                3));
    cmd("sales", "circuit_probe", eastProbe);
    Object[] alarm = {"port_id", port, "reference", "carrier-7", "state", "down", "revision", 3};
    var receipt = def(cmd("sales", "network_alarm", alarm));
    assertEquals(map(eastKey, "interrupted", westKey, "backup"), receipt.get("sites"));
    assertEquals(receipt, def(cmd("sales", "network_alarm", alarm)));
    assertEquals(1, h.db.count("SELECT COUNT(*) FROM os_customer_case"));
    cmd(
        "sales",
        "network_alarm",
        "port_id",
        port,
        "reference",
        "carrier-8",
        "state",
        "down",
        "revision",
        4);
    assertEquals(1, h.db.count("SELECT COUNT(*) FROM os_sla"));
    var openIssue = definition(h.db, "circuit-interruption", eastKey);
    assertThrows(
        Failure.class,
        () -> cmd("impact", "recover", "new_id", openIssue.get("outage"), "note", "premature"));
    assertThrows(
        Failure.class,
        () ->
            cmd(
                "sales",
                "circuit_probe",
                "bundle_id",
                east,
                "location",
                "East",
                "objective_id",
                objective,
                "revision",
                4));
    assertThrows(
        Failure.class,
        () ->
            cmd(
                "sales",
                "assure_site",
                "bundle_id",
                east,
                "location",
                "East",
                "objective_id",
                objective));
    assertThrows(
        Failure.class,
        () ->
            cmd(
                "impact",
                "outage",
                "new_id",
                id(),
                "component",
                "unobserved",
                "title",
                "unknown source"));
    h.as(other);
    assertTrue(((List<?>) h.run("impact", "my_impact", "limit", 20)).isEmpty());
    h.as(h.c);
    assertEquals(1, ((List<?>) h.run("impact", "my_impact", "limit", 20)).size());
    h.as(h.admin);
    cmd(
        "sales",
        "network_alarm",
        "port_id",
        port,
        "reference",
        "carrier-9",
        "state",
        "up",
        "revision",
        4);
    Object[] verify = {"bundle_id", east, "location", "East", "objective_id", objective};
    assertThrows(Failure.class, () -> cmd("sales", "assure_site", verify));
    // Age the outage and port timestamps to the preceding probe interval.
    var issue = definition(h.db, "circuit-interruption", eastKey);
    long bucket = System.currentTimeMillis() / 60000 * 60000;
    bucket -= 60000;
    issue.put("opened", bucket - 60000);
    h.seed("circuit-interruption", eastKey, issue);
    var link = definition(h.db, "network-port", port);
    link.put("changed_at", bucket - 1000);
    h.seed("network-port", port, link);
    for (String location : List.of("east", "west"))
      h.db.update(
          "INSERT INTO os_transaction_sample(objective,location,bucket,detail) VALUES(?,?,?,?)",
          objective,
          location,
          bucket,
          JSON.writeValueAsString(map("result", "up", "rule_revision", 1)));
    h.db.update(
        "UPDATE os_transaction_sample SET detail=? WHERE objective=? AND location='east'",
        JSON.writeValueAsString(map("result", "down")),
        objective);
    assertThrows(Failure.class, () -> cmd("sales", "assure_site", verify));
    h.db.update(
        "UPDATE os_transaction_sample SET bucket=?,detail=? WHERE objective=? AND location='east'",
        bucket + 1,
        JSON.writeValueAsString(map("result", "up", "rule_revision", 1)),
        objective);
    assertThrows(Failure.class, () -> cmd("sales", "assure_site", verify));
    h.db.update(
        "UPDATE os_transaction_sample SET bucket=? WHERE objective=? AND location='east'",
        bucket,
        objective);
    cmd(
        "mapping",
        "transaction_rule",
        "objective_id",
        objective,
        "expected",
        "completed",
        "limit",
        250,
        "revision",
        1);
    assertThrows(Failure.class, () -> cmd("sales", "assure_site", verify));
    // Keep the old pair as evidence and submit the revised rule in the next interval.
    for (String location : List.of("east", "west"))
      h.db.update(
          "INSERT INTO os_transaction_sample(objective,location,bucket,detail) VALUES(?,?,?,?)",
          objective,
          location,
          bucket + 60000,
          JSON.writeValueAsString(map("result", "up", "rule_revision", 2)));
    var restored = def(cmd("sales", "assure_site", verify));
    assertEquals("restored", restored.get("state"));
    assertEquals(restored, def(cmd("sales", "assure_site", verify)));
    assertEquals("completed", h.db.one("SELECT state FROM os_sla").get("state"));
    cmd(
        "sales",
        "circuit_probe",
        "bundle_id",
        east,
        "location",
        "East",
        "objective_id",
        objective,
        "revision",
        4);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void automaticIntuneMatchNeverUsesRecycledHostname(boolean pg) throws Exception {
    open(pg);
    String directory = UUID.randomUUID().toString(), alias = "fleet", cloud = "directory";
    ServiceTopology.inventory(
        h.db,
        cloud,
        map(
            "sequence",
            1,
            "resources",
            List.of(
                map(
                    "id",
                    directory,
                    "type",
                    "host",
                    "title",
                    "before rename",
                    "depends_on",
                    List.of())),
            "services",
            List.of()));
    cmd("mapping", "intune_authority", "source", cloud, "alias", alias, "revision", 0);
    var fact =
        map(
            "id",
            "first",
            "deviceName",
            "after rename",
            "operatingSystem",
            "Windows",
            "azureADDeviceId",
            directory);
    String ci =
        object(object(definition(h.db, "service-map", cloud).get("resources")).get(directory))
            .get("ci_id")
            .toString();
    IntuneInventory.accept(h.db, id(), alias, 1, map("value", List.of(fact)));
    assertEquals(
        ci,
        object(
                cmd("mapping", "intune_device", "alias", alias, "reference", "first")
                    .get("observation"))
            .get("ci_id"));
    fact.put("id", "second");
    IntuneInventory.accept(h.db, id(), alias, 1, map("value", List.of(fact)));
    assertNotEquals(
        ci,
        object(
                cmd("mapping", "intune_device", "alias", alias, "reference", "second")
                    .get("observation"))
            .get("ci_id"));
    assertThrows(
        Failure.class, () -> cmd("mapping", "intune_device", "alias", alias, "reference", "first"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void expiredRightsAndProviderReallocationAreSeparateEvidence(boolean pg) throws Exception {
    open(pg);
    String license = id(), person = h.a, successor = h.b;
    var right =
        map(
            "kind",
            "rights",
            "contract",
            "Renewable",
            "covers",
            List.of("Editor Pro 1"),
            "quantity",
            2,
            "through",
            "2025-06-30");
    fact("purchase", "2025-01-01", right);
    fact("purchase", "2025-01-01", right);
    h.seed(
        "seat-receipt",
        id(),
        map(
            "product",
            "editor",
            "license",
            license,
            "user",
            person,
            "replacement",
            successor,
            "at",
            "2025-07-02 12:00:00"));
    var june =
        def(
            cmd(
                "licenses",
                "license_position",
                "product",
                "Editor",
                "effective",
                "2025-06-30",
                "new_id",
                id()));
    assertEquals(2, june.get("surplus"));
    assertTrue(objects(june.get("recovered_seats")).isEmpty());
    var july =
        def(
            cmd(
                "licenses",
                "license_position",
                "product",
                "Editor",
                "effective",
                "2025-07-31",
                "new_id",
                id()));
    assertEquals(0, july.get("surplus"));
    assertEquals(successor, objects(july.get("recovered_seats")).getFirst().get("reallocated_to"));
    var clash = new HashMap<>(right);
    clash.put("quantity", -1);
    assertThrows(Failure.class, () -> fact("purchase", "2025-01-01", clash));
    h.as(h.c);
    assertThrows(
        Failure.class,
        () ->
            cmd(
                "licenses",
                "license_position",
                "product",
                "Editor",
                "effective",
                "2025-07-31",
                "new_id",
                id()));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void graphImportResumesFromCommittedPageAndRejectsRebinding(boolean pg) throws Exception {
    open(pg);
    String run = id(), alias = "mdm", next = IntuneInventory.PATH + "?$skiptoken=next";
    h.db.update(
        "INSERT INTO os_connector(alias,origin,envelope,revision) VALUES(?,?,'fixture',1)",
        alias,
        IntuneInventory.ORIGIN);
    var stop = new java.util.concurrent.atomic.AtomicBoolean(true);
    var paths = new ArrayList<String>();
    try (var transport = org.mockito.Mockito.mockStatic(Connectors.class)) {
      transport
          .when(() -> Connectors.path(org.mockito.ArgumentMatchers.any()))
          .thenCallRealMethod();
      transport
          .when(
              () ->
                  Connectors.fetch(
                      org.mockito.ArgumentMatchers.eq(h.store),
                      org.mockito.ArgumentMatchers.eq(h.db),
                      org.mockito.ArgumentMatchers.any()))
          .thenAnswer(
              call -> {
                String path = object(call.getArgument(2)).get("path").toString();
                paths.add(path);
                if (path.equals(next) && stop.getAndSet(false))
                  throw new Failure(503, "Graph rate limited");
                var device =
                    map(
                        "id",
                        path.equals(next) ? "second" : "first",
                        "deviceName",
                        "laptop",
                        "operatingSystem",
                        "Windows");
                var response = map("value", List.of(device));
                if (!path.equals(next))
                  response.put("@odata.nextLink", IntuneInventory.ORIGIN + next);
                return map("content", JSON.writeValueAsBytes(response), "revision", 1);
              });
      Object[] args = {"alias", alias, "new_id", run};
      var started = def(cmd("mapping", "intune_import", args));
      assertEquals(next, started.get("next"));
      assertThrows(Failure.class, () -> cmd("mapping", "intune_import", args));
      assertEquals(0, get(h.db, "service-map", IntuneInventory.source(alias)).get("revision"));
      h.db.update("UPDATE os_connector SET revision=2 WHERE alias=?", alias);
      assertThrows(Failure.class, () -> cmd("mapping", "intune_import", args));
      h.db.update("UPDATE os_connector SET revision=1 WHERE alias=?", alias);
      var receipt = def(cmd("mapping", "intune_import", args));
      assertEquals(2, receipt.get("count"));
      assertEquals(receipt, def(cmd("mapping", "intune_import", args)));
      assertEquals(List.of(next, next), paths.subList(1, 3));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unavailableCustomerContractNeverStartsAClock(boolean pg) throws Exception {
    open(pg);
    String port = id(),
        ci = h.record("cmdb_ci"),
        bundle = id(),
        sale = id(),
        account = h.account(),
        circuit = key(bundle, "South");
    h.seed("opportunity", sale, map("account", account, "entitlement", id(), "case_id", id()));
    h.seed(
        "network-port",
        port,
        map("capacity", 1, "name", "Link", "ci", ci, "state", "up", "circuits", map(circuit, 1)));
    h.seed(
        "network-circuit",
        circuit,
        map(
            "opportunity",
            sale,
            "site",
            "South",
            "bundle",
            bundle,
            "state",
            "active",
            "path",
            List.of(port)));
    Object[] first = {"port_id", port, "reference", "n1", "state", "down", "revision", 1};
    assertEquals(
        "interrupted_without_contract_binding",
        object(def(cmd("sales", "network_alarm", first)).get("sites")).get(circuit));
    h.seed(
        "site-monitor",
        circuit,
        map("contact", h.c, "entitlement", id(), "site", "South", "service", "cloud :: edge"));
    for (int i = 2; i <= 3; i++) {
      var sites =
          object(
              def(cmd(
                      "sales",
                      "network_alarm",
                      "port_id",
                      port,
                      "reference",
                      "n" + i,
                      "state",
                      "down",
                      "revision",
                      2))
                  .get("sites"));
      assertEquals("interrupted_without_eligible_contact", sites.get(circuit));
    }
    assertTrue(list(h.db, "site-outage").isEmpty());
    assertEquals(0, h.db.count("SELECT COUNT(*) FROM os_sla"));
    assertEquals(0, h.db.count("SELECT COUNT(*) FROM os_customer_case"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void cancellationWhileReviewingStopsReviewAndReleasesPendingLines(boolean pg) throws Exception {
    open(pg);
    String run = id();
    h.seed(
        "exception-run",
        run,
        map("state", "review", "lines", map("Laptop", map("state", "reserved"))));
    h.seed(
        "workflow-run",
        run,
        map("state", "running", "steps", List.of(), "employee", h.c, "manager", h.a));
    Object[] args = {"new_id", run, "owner_id", h.a, "note", "No longer needed"};
    var row = cmd("designer", "cancel_exception", args);
    assertEquals("cancelled", def(row).get("state"));
    assertEquals("released", object(object(def(row).get("lines")).get("Laptop")).get("state"));
    assertEquals("stopped", definition(h.db, "workflow-run", run).get("state"));
    assertEquals(row, cmd("designer", "cancel_exception", args));
    assertEquals(row, cmd("designer", "advance_exception", "new_id", run));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void cohortExcludesOtherTeamsAndWaitsForAnActualDeployment(boolean pg) throws Exception {
    open(pg);
    long day = 86400000L, at = Instant.parse("2025-06-01T00:00:00Z").toEpochMilli();
    String release = id(), change = h.record("change_request");
    Object[] args = {"group_id", h.team, "start", stamp(at - day), "end", stamp(at + day)};
    h.seed(
        "pipeline-release",
        release,
        map("change_id", change, "policy", map(), "state", "authorized"));
    cmd("releases", "track_delivery", "group_id", h.team, "release_id", release);
    assertNull(cmd("releases", "delivery_performance", args).get("failure_rate"));
    assertThrows(
        Failure.class,
        () ->
            cmd(
                "releases",
                "delivery_event",
                "group_id",
                h.second,
                "release_id",
                release,
                "kind",
                "commit",
                "reference",
                "z",
                "observed_at",
                stamp(at)));
    h.seed(
        "pipeline-release",
        release,
        map(
            "change_id",
            change,
            "state",
            "recovered",
            "reported_at",
            "2025-06-01 00:00:00",
            "deployment",
            "deploy:A",
            "recovered_at",
            "2025-06-01 01:00:00"));
    cmd(
        "releases",
        "delivery_event",
        "group_id",
        h.team,
        "release_id",
        release,
        "kind",
        "commit",
        "reference",
        "late-commit",
        "observed_at",
        stamp(at + day));
    var result = cmd("releases", "delivery_performance", args);
    assertNull(result.get("mean_lead_ms"));
    assertEquals(3600000.0, result.get("mean_recovery_ms"));
    assertThrows(
        Failure.class,
        () ->
            cmd(
                "releases",
                "delivery_performance",
                "group_id",
                h.team,
                "start",
                stamp(at),
                "end",
                stamp(at)));
    assertEquals(
        0,
        cmd(
                "releases",
                "delivery_performance",
                "group_id",
                h.team,
                "start",
                stamp(at - day),
                "end",
                stamp(at))
            .get("deployments"));
    var pipeline = definition(h.db, "pipeline-release", release);
    pipeline.put("recovered_at", "2025-05-31 23:59:59");
    h.seed("pipeline-release", release, pipeline);
    for (String kind : List.of("revert", "restored"))
      cmd(
          "releases",
          "delivery_event",
          "group_id",
          h.team,
          "release_id",
          release,
          "kind",
          kind,
          "reference",
          "previous",
          "observed_at",
          stamp(at - 1000));
    assertNull(cmd("releases", "delivery_performance", args).get("mean_recovery_ms"));
    cmd(
        "releases",
        "delivery_event",
        "group_id",
        h.team,
        "release_id",
        release,
        "kind",
        "restored",
        "reference",
        "corrected",
        "observed_at",
        stamp(at + 5000));
    assertEquals(5000.0, cmd("releases", "delivery_performance", args).get("mean_recovery_ms"));
    assertEquals(1, result.get("failed"));
    assertEquals(
        0,
        cmd(
                "releases",
                "delivery_performance",
                "group_id",
                h.second,
                "start",
                stamp(at - day),
                "end",
                stamp(at + day))
            .get("deployments"));
    assertEquals(
        0,
        cmd(
                "releases",
                "delivery_performance",
                "group_id",
                h.team,
                "start",
                stamp(at + day),
                "end",
                stamp(at + 2 * day))
            .get("deployments"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void scheduledPurchasingSuspendsAfterRevocation(boolean pg) throws Exception {
    open(pg);
    String run = id(), owner = h.user("PurchasingAdmin", "admin");
    var admin = AgentPlans.account(h.db, owner);
    h.seed("workflow-run", run, map("state", "complete"));
    h.seed(
        "exception-run",
        run,
        map(
            "owner",
            owner,
            "owner_revision",
            admin.get("auth_revision"),
            "state",
            "review",
            "action",
            map("supplier", "Carrier")));
    h.db.update("UPDATE os_user SET auth_revision=auth_revision+1 WHERE user_id=?", owner);
    h.db.viewerId = null;
    h.db.viewerRole = null;
    assertTrue(SupplierExceptions.resumeReady(h.store, h.db));
    assertNull(h.db.viewerId);
    assertEquals("authority_review", definition(h.db, "exception-run", run).get("state"));
    assertTrue(list(h.db, "purchase").isEmpty());
    assertFalse(SupplierExceptions.resumeReady(h.store, h.db));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void twoDayMetricHoldoutScoresWholeIncidents(boolean pg) throws Exception {
    evaluateTwoDays(pg,false);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false,true})
  void corroboratedMetricHoldout(boolean pg) throws Exception {
    evaluateTwoDays(pg,true);
  }

  void evaluateTwoDays(boolean pg,boolean corroborate) throws Exception {
    open(pg);
    String objective = objective();
    var spec = definition(h.db, "service-objective", objective);
    spec.put("interval", 3600000);
    h.seed("service-objective", objective, spec);
    long hour = 3600000L, day = 24 * hour, start = System.currentTimeMillis() / day * day - 2 * day;
    var training = new ArrayList<>();
    for (int d = 1; d <= 6; d++)
      for (int slot = 0; slot < 24; slot++) {
        int peak = slot >= 16 && slot <= 19 ? 120 : 0;
        training.add(
            map(
                "observed_at",
                stamp(start - d * day + slot * hour),
                "metrics",
                metrics(90 + peak + d * 3, 25 + peak / 4 + d, 2)));
      }
    cmd(
        "mapping",
        corroborate ? "metric_corroborated_baseline" : "metric_baseline",
        "objective_id",
        objective,
        "samples",
        training,
        "revision",
        0);
    if (corroborate) cmd("mapping","transaction_rule","objective_id",objective,"expected","order receipt","limit",2000,"revision",0);
    var labels = new ArrayList<>();
    for (int i = 0; i < 48; i++) {
      int slot = i % 24, peak = slot >= 16 && slot <= 19 ? 120 : 0;
      boolean severe = i >= 30 && i <= 33, mild = i == 41, blind = i == 15 || i == 40;
      var reading =
          blind
              ? map()
              : metrics(
                  100 + peak + (severe ? 450 : i == 8 ? 80 : mild ? 5 : 0),
                  29 + peak / 4,
                  severe ? 8 : 1);
      String observed = stamp(start + i * hour);
      cmd(
          "mapping",
          "metric_sample",
          "objective_id",
          objective,
          "observed_at",
          observed,
          "manifest",
          reading);
      labels.add(
          map(
              "observed_at",
              observed,
              "state",
              blind ? "unknown" : severe || mild ? "down" : "up"));
    }
    Collections.reverse(labels);
    var report = cmd("mapping", "metric_score", "objective_id", objective, "samples", labels);
    assertEquals(48, report.get("observations"));
    assertEquals(corroborate ? 47 : 46, report.get("correct"));
    assertEquals(2, report.get("unknown"));
    assertEquals(2, report.get("expected_incidents"));
    assertEquals(1, report.get("missed_incidents"));
    assertEquals(corroborate ? 0 : 1, report.get("false_alarm_episodes"));
    java.nio.file.Files.writeString(
        Path.of("target", (corroborate ? "corroborated-metric-" : "service-metric-holdout-") + (pg ? "postgres" : "sqlite") + ".json"),
        JSON.writeValueAsString(report));
    if (corroborate) {
      // An additional synthetic checkout feed arrives after the metric-only decisions are frozen.
      // Hour 41 returns HTTP 200 with an invalid cart instead of the expected order receipt.
      for(int slot=40; slot<=42; slot++) for(String site:List.of("east","west")) {
        var sample=map("latency_ms",250,"status",200,"matched",slot!=41,"expected","order receipt","healthy",true);
        Object[] args={"objective_id",objective,"location",site,"observed_at",stamp(start+slot*hour),"manifest",sample};
        cmd("mapping","backfill_transaction_sample",args);
        assertEquals(true,cmd("mapping","backfill_transaction_sample",args).get("replayed"));
      }
      var reviewed=cmd("mapping","reviewed_metric_score","objective_id",objective,"samples",labels);
      assertEquals(48,reviewed.get("correct"));assertEquals(0,reviewed.get("missed_incidents"));
      assertEquals(0,reviewed.get("false_alarm_episodes"));assertEquals(2,reviewed.get("detected_episodes"));
      assertEquals(report,cmd("mapping","metric_score","objective_id",objective,"samples",labels));
      java.nio.file.Files.writeString(Path.of("target","late-probe-metric-"+(pg?"postgres":"sqlite")+".json"),
          JSON.writeValueAsString(reviewed));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void recurringIncidentDuringObservationKeepsFixUnverified(boolean pg) throws Exception {
    open(pg);
    String objective = objective(),
        problem = h.record("problem"),
        change = h.record("change_request"),
        release = id(),
        ci = definition(h.db, "service-objective", objective).get("ci").toString();
    long at = System.currentTimeMillis() / 60000 * 60000;
    String format = "yyyy-MM-dd HH:mm:ss";
    var date = java.time.format.DateTimeFormatter.ofPattern(format).withZone(ZoneOffset.UTC);
    h.seed(
        "problem-fix",
        key(problem, release),
        map("objective", objective, "minutes", 2, "baseline", List.of(), "change", change));
    var pipe =
        map(
            "policy",
            map("ci", ci),
            "artifact",
            "sha",
            "state",
            "deployed",
            "change_id",
            change,
            "deployment",
            "v2",
            "reported_at",
            date.format(Instant.ofEpochMilli(at)));
    h.seed("pipeline-release", release, pipe);
    h.seed("cab-window", release, map("state", "approved", "artifact", "sha"));
    Object[] args = {"problem_id", problem, "release_id", release};
    assertEquals("observing", cmd("releases", "verify_fix", args).get("state"));
    pipe.put("reported_at", date.format(Instant.ofEpochMilli(at - 600000)));
    h.seed("pipeline-release", release, pipe);
    String repeat = h.record("incident");
    h.seed("service-episode", id(), map("incident", repeat, "ci", ci, "began", at - 540000));
    var decision = def(cmd("releases", "verify_fix", args));
    assertEquals("recurrence", decision.get("state"));
    assertEquals(List.of(repeat), decision.get("recurrence"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void standbyReservationIncludesExistingTraffic(boolean pg) throws Exception {
    open(pg);
    String bundle = id(), port = id(), primary = id(), circuit = key(bundle, "site");
    h.seed(
        "network-circuit", circuit, map("state", "reserved", "path", List.of(primary), "units", 1));
    h.seed(
        "network-port",
        port,
        map("state", "up", "circuits", map("another-circuit", 1), "capacity", 1));
    assertThrows(
        Failure.class,
        () ->
            cmd(
                "sales",
                "backup_path",
                "bundle_id",
                bundle,
                "location",
                "site",
                "entries",
                List.of(port),
                "revision",
                1));
    assertFalse(definition(h.db, "network-circuit", circuit).containsKey("backup"));
    assertThrows(
        Failure.class, () -> ServiceAssurance.run(h.store, h.db, "unknown_operation", map()));
  }

  Map<String, Object> position(String product, String day, String snapshot) throws Exception {
    return def(
        cmd(
            "licenses",
            "license_position",
            "product",
            product,
            "effective",
            day,
            "new_id",
            snapshot));
  }

  @Test
  void publisherDistinguishesUnorderedChangesAndFrozenPositions() throws Exception {
    open(false);
    String ci = h.record("cmdb_ci"), date = "2025-01-10", snap = id();
    var added = map("kind", "installation", "ci", ci, "edition", "Standard", "last_used", date);
    var removed = map("kind", "removal", "ci", ci);
    var rights =
        map(
            "kind",
            "rights",
            "contract",
            "Publisher",
            "covers",
            List.of("Standard"),
            "quantity",
            0);
    assertThrows(Failure.class, () -> fact("zero", date, rights));
    rights.put("quantity", 1);
    rights.put("covers", List.of());
    assertThrows(Failure.class, () -> fact("none", date, rights));
    rights.put("covers", List.of("Standard"));
    rights.put("through", "2025-01-01");
    assertThrows(Failure.class, () -> fact("expired", date, rights));
    rights.remove("through");
    fact("paid", date, rights);
    added.put("last_used", "2025-01-11");
    assertThrows(Failure.class, () -> fact("future", date, added));
    added.put("last_used", date);
    fact("z-install", date, added);
    fact("a-removal", date, removed);
    assertThrows(Failure.class, () -> position("Editor", date, snap));
    removed.put("sequence", 2);
    fact("last", date, removed);
    fact("same-state", date, removed);
    assertEquals(1, position("Editor", date, snap).get("surplus"));
    assertThrows(Failure.class, () -> position("Other", date, snap));
    assertThrows(Failure.class, () -> position("Editor", "2025-01-11", snap));
    added.put("sequence", 3);
    fact("reinstalled", date, added);
    assertEquals(1, position("Editor", date, snap).get("surplus"));
    assertEquals(0, position("Editor", date, id()).get("surplus"));
    var laterUse = new LinkedHashMap<>(added);
    laterUse.put("last_used", "2025-01-11");
    fact("a-more-recent-use", "2025-01-11", laterUse);
    fact("z-stale-usage", "2025-01-11", added);
    assertEquals(
        "2025-01-11",
        object(object(position("Editor", "2025-01-11", id()).get("installations")).get(ci))
            .get("last_used"));
    rights.put("quantity", -2);
    fact("credit", "2025-01-11", rights);
    assertThrows(Failure.class, () -> position("Editor", "2025-01-11", id()));
    assertEquals(0, position("Other", date, id()).get("surplus"));
    h.seed("seat-receipt", id(), map("at", "", "license", id()));
    h.seed(
        "seat-receipt",
        id(),
        map("at", "2024-01-01 12:00:00", "license", id(), "product", "Other"));
    assertEquals(List.of(), position("Editor", date, id()).get("recovered_seats"));
  }

  @Test
  void intuneRejectsConflictsAndIncompletePaginationWithoutPublishing() throws Exception {
    open(false);
    String alias = "managed", source = IntuneInventory.source(alias), run = id();
    String url = IntuneInventory.ORIGIN + IntuneInventory.PATH;
    for (String malformed :
        List.of(
            url, url + "?x=1#other", IntuneInventory.ORIGIN + "/other?x=1", url + "?bad cursor"))
      assertThrows(Failure.class, () -> IntuneInventory.continuation(malformed));
    var machine = map("id", "one", "deviceName", "desk", "operatingSystem", "Windows");
    var page = map("value", List.of(machine, machine), "@odata.nextLink", url + "?cursor=1");
    IntuneInventory.accept(h.db, run, alias, 1, page);
    assertThrows(Failure.class, () -> IntuneInventory.accept(h.db, run, alias, 1, page));
    var changed = new LinkedHashMap<>(machine);
    changed.put("deviceName", "other");
    assertThrows(
        Failure.class,
        () -> IntuneInventory.accept(h.db, run, alias, 1, map("value", List.of(changed))));
    var many = new ArrayList<>();
    for (int n = 0; n < 200; n++)
      many.add(map("id", "device-" + n, "deviceName", "device", "operatingSystem", "Linux"));
    assertThrows(
        Failure.class, () -> IntuneInventory.accept(h.db, run, alias, 1, map("value", many)));
    for (int n = 2; n <= 200; n++)
      IntuneInventory.accept(
          h.db, run, alias, 1, map("value", List.of(), "@odata.nextLink", url + "?cursor=" + n));
    assertThrows(
        Failure.class,
        () ->
            IntuneInventory.accept(
                h.db,
                run,
                alias,
                1,
                map("value", List.of(), "@odata.nextLink", url + "?cursor=201")));
    assertEquals(0, get(h.db, "service-map", source).get("revision"));
    IntuneInventory.accept(h.db, run, alias, 1, map("value", List.of(machine)));
    assertThrows(
        Failure.class,
        () -> cmd("mapping", "intune_authority", "alias", alias, "source", source, "revision", 0));
    assertThrows(
        Failure.class,
        () ->
            cmd(
                "mapping",
                "intune_bind",
                "alias",
                alias,
                "reference",
                "one",
                "source",
                source,
                "resource_id",
                "one",
                "revision",
                0));
    assertEquals(1, object(definition(h.db, "intune-facts", source).get("devices")).size());
  }

  @Test
  void metricScoringCountsGapsAndStatusFollowsLatestObservation() throws Exception {
    open(false);
    String objective = objective();
    long day = 86400000L, current = System.currentTimeMillis() - 2000;
    var labels = new TreeMap<Long, MetricAnomalies.Classification>();
    labels.put(0L, new MetricAnomalies.Classification(true, false));
    labels.put(2L, new MetricAnomalies.Classification(true, false));
    assertEquals(new MetricAnomalies.EpisodeScore(2, 2), MetricAnomalies.episodes(labels, 1, true));
    labels.put(2L, new MetricAnomalies.Classification(true, true));
    assertEquals(new MetricAnomalies.EpisodeScore(2, 1), MetricAnomalies.episodes(labels, 1, true));
    for (var invalid : List.of(map("other", 1), map("latency_ms", 1)))
      assertThrows(Failure.class, () -> MetricAnomalies.readings(invalid, true));
    var train = new ArrayList<>();
    assertThrows(
        Failure.class,
        () ->
            cmd(
                "mapping",
                "metric_baseline",
                "objective_id",
                objective,
                "samples",
                train,
                "revision",
                0));
    for (int i = 1; i <= 3; i++)
      train.add(map("observed_at", stamp(current - day * i), "metrics", metrics(100, 20, 1)));
    assertThrows(
        Failure.class,
        () ->
            cmd(
                "mapping",
                "metric_baseline",
                "objective_id",
                objective,
                "samples",
                train.subList(0, 2),
                "revision",
                0));
    cmd("mapping", "metric_baseline", "objective_id", objective, "samples", train, "revision", 0);
    assertThrows(
        Failure.class,
        () ->
            cmd(
                "mapping",
                "metric_sample",
                "objective_id",
                objective,
                "observed_at",
                stamp(current - day),
                "manifest",
                metrics(100, 20, 1)));
    cmd(
        "mapping",
        "metric_sample",
        "objective_id",
        objective,
        "observed_at",
        stamp(current),
        "manifest",
        metrics(800, 20, 1));
    var active = cmd("mapping", "metric_status", "objective_id", objective);
    assertEquals("down", active.get("state"));
    assertNotNull(active.get("incident"));
    cmd(
        "mapping",
        "metric_sample",
        "objective_id",
        objective,
        "observed_at",
        stamp(current - 1),
        "manifest",
        metrics(50, 20, 1));
    var old =
        def(
            cmd(
                "mapping",
                "metric_sample",
                "objective_id",
                objective,
                "observed_at",
                stamp(current - 3600000),
                "manifest",
                metrics(50, 20, 1)));
    assertEquals("unknown", old.get("state"));
    assertEquals(active, cmd("mapping", "metric_status", "objective_id", objective));
    var label = map("observed_at", stamp(current - 1), "state", "unknown");
    assertEquals(
        0,
        cmd("mapping", "metric_score", "objective_id", objective, "samples", List.of(label))
            .get("correct"));
    assertThrows(
        Failure.class,
        () ->
            cmd(
                "mapping",
                "metric_score",
                "objective_id",
                objective,
                "samples",
                List.of(label, label)));
  }

  @Test
  void riskRoutesRequireTheMatchingDesignForm() {
    for (var form :
        List.of(
            map("title", "Other", "operation", "exception_design"),
            map("title", "Decision workflows", "operation", "other"),
            map(
                "title",
                "Decision workflows",
                "operation",
                "exception_design",
                "bindings",
                "duplicate"))) {
      var body = new LinkedHashMap<>(form);
      body.put("designer_rows", "risk_routes");
      assertThrows(Failure.class, () -> DesignInputs.read(map("title", form.get("title")), body));
    }
  }

  @Test
  void resumedSearchKeepsItsDecisionPendingWhenSourceIsUnavailable() throws Exception {
    open(false);
    String intent = id(), session = id(), article = id();
    h.seed("employee-intent", intent, map("article", article));
    var cite = map("article_id", article);
    var pending =
        map(
            "owner",
            h.c,
            "state",
            "confirmation_required",
            "intent",
            intent,
            "version",
            1,
            "citation",
            cite);
    h.seed("employee-conversation", session, pending);
    h.as(h.c);
    try (var access = org.mockito.Mockito.mockStatic(EmployeeAssistant.class)) {
      var remote =
          access.when(
              () ->
                  EmployeeAssistant.sources(
                      org.mockito.ArgumentMatchers.eq(h.store),
                      org.mockito.ArgumentMatchers.eq(h.db),
                      org.mockito.ArgumentMatchers.anyMap()));
      remote.thenThrow(new Failure(503, "Temporary upstream outage"));
      assertEquals(
          503,
          assertThrows(Failure.class, () -> cmd("assistant", "resume_search", "new_id", session))
              .status);
      assertEquals(pending, definition(h.db, "employee-conversation", session));
      remote.thenReturn(map("article_id", id()));
      assertEquals(false, cmd("assistant", "resume_search", "new_id", session).get("can_confirm"));
      assertFalse(definition(h.db, "employee-conversation", session).containsKey("group"));
      pending.put("version", 2);
      h.seed("employee-conversation", session, pending);
      remote.thenReturn(cite);
      assertEquals(false, cmd("assistant", "resume_search", "new_id", session).get("can_confirm"));
      for (int status : List.of(403, 404, 400)) {
        h.seed("employee-conversation", session, pending);
        remote.thenThrow(new Failure(status, "Policy access required"));
        assertEquals(
            "handoff_required", cmd("assistant", "resume_search", "new_id", session).get("state"));
      }
      pending.put("intent", id());
      h.seed("employee-conversation", session, pending);
      assertEquals(false, cmd("assistant", "resume_search", "new_id", session).get("can_confirm"));
    }
    for (int code : List.of(403, 404, 410))
      assertEquals(
          403,
          ExternalPolicies.accessFailure(new ServiceNowSource.SourceResponseFailure(code)).status);
    for (var err :
        List.of(
            new ServiceNowSource.SourceResponseFailure(503),
            new Failure(502, "offline"),
            new java.io.IOException()))
      assertEquals(503, ExternalPolicies.accessFailure(err).status);
  }

  @Test
  void deferredRetirementRequiresAnObservationOnTheSameCi() throws Exception {
    open(false);
    String ci = h.record("cmdb_ci"), other = h.record("cmdb_ci"), binding = id();
    h.seed("intune-binding", binding, map("ci", ci));
    assertFalse(IntuneInventory.observes(h.db, other));
    assertFalse(IntuneInventory.observes(h.db, ci));
    h.seed("discovered-ci", binding, map("ci_id", ci));
    assertTrue(IntuneInventory.observes(h.db, ci));

    String alias = "fleet",
        cloud = "aws",
        source = IntuneInventory.source(alias),
        dir = UUID.randomUUID().toString();
    ServiceTopology.inventory(
        h.db,
        cloud,
        map(
            "sequence",
            1,
            "resources",
            List.of(
                map(
                    "id",
                    dir,
                    "type",
                    "host",
                    "title",
                    "directory identifier",
                    "depends_on",
                    List.of())),
            "services",
            List.of()));
    cmd("mapping", "intune_authority", "alias", alias, "source", cloud, "revision", 0);
    var devices = new ArrayList<>();
    for (String ref : List.of("A", "B", "C", "D")) {
      var machine = map("id", ref, "operatingSystem", "Windows", "deviceName", "shared");
      machine.put(
          "azureADDeviceId",
          switch (ref) {
            case "A", "B" -> dir;
            case "C" -> "unspecified";
            default -> UUID.randomUUID().toString();
          });
      devices.add(machine);
    }
    IntuneInventory.accept(h.db, id(), alias, 1, map("value", devices));
    IntuneInventory.accept(h.db, id(), alias, 1, map("value", devices));
    for (Object d : devices)
      assertEquals(
          0,
          get(
                  h.db,
                  "intune-binding",
                  ServiceTopology.identity(source, object(d).get("id").toString()))
              .get("revision"));
  }

  @Test
  void purchasingRequiresAnOpenRequisitionWithBoundedLines() throws Exception {
    open(false);
    String request = h.record("sc_request"), run = id(), design = id();
    var many = map();
    for (int n = 0; n < 33; n++) many.put("model-" + n, map("quantity", 1, "unit_cost", "1"));
    for (var invalid : List.of(map(), many))
      assertThrows(
          Failure.class,
          () ->
              cmd(
                  "designer",
                  "exception_requisition",
                  "record_id",
                  request,
                  "supplier",
                  "Vendor",
                  "revision",
                  0,
                  "manifest",
                  invalid));
    h.seed(
        "exception-design",
        design,
        map(
            "routes",
            map("low", map()),
            "action",
            map(
                "supplier",
                "Vendor",
                "bindings",
                map("unit_cost", "unit_cost", "quantity", "quantity"))));
    Object[] start = {
      "design_id",
      design,
      "new_id",
      run,
      "user_id",
      h.c,
      "record_id",
      request,
      "risk",
      "low",
      "amount",
      "1",
      "location",
      "Tbilisi"
    };
    for (var invalid : List.of(map(), many)) {
      h.seed("exception-requisition", request, map("supplier", "Vendor", "lines", invalid));
      assertTrue(
          assertThrows(Failure.class, () -> cmd("designer", "start_exception", start))
              .getMessage()
              .contains("1–32"));
    }
    var row = Service.readRecord(h.db, "sc_request", request);
    object(row.get("data")).put("state", "complete");
    Service.save(h.db, row);
    assertThrows(
        Failure.class,
        () ->
            cmd(
                "designer",
                "exception_requisition",
                "record_id",
                request,
                "supplier",
                "Vendor",
                "revision",
                0,
                "manifest",
                map("PC", map())));
    assertThrows(Failure.class, () -> cmd("designer", "start_exception", start));
    assertThrows(Failure.class, () -> cmd("designer", "advance_exception", "new_id", run));
    h.seed("exception-run", run, map("state", "review"));
    assertFalse(SupplierExceptions.resumeReady(h.store, h.db));
    h.seed("exception-run", run, map("owner", h.admin, "state", "review"));
    h.seed("workflow-run", run, map("state", "running"));
    assertFalse(SupplierExceptions.resumeReady(h.store, h.db));
  }

  @Test
  void continuityChecksBothTimeAndCustomerImpact() throws Exception {
    open(false);
    String exercise = id();
    long now = System.currentTimeMillis() - 10000;
    assertTrue(BusinessTolerance.acceptable(map(), map()));
    var data = map("state", "passed", "spec", map(), "start", now - 120000);
    h.seed("recovery-exercise", exercise, data);
    Object[] impact = {
      "exercise_id",
      exercise,
      "restored_at",
      stamp(now),
      "quantity",
      1,
      "evidence",
      "backlog rechecked",
      "revision",
      1
    };
    assertThrows(Failure.class, () -> cmd("continuity", "business_impact", impact));
    data.put("state", "running");
    h.seed("recovery-exercise", exercise, data);
    assertThrows(Failure.class, () -> cmd("continuity", "business_impact", impact));
    data.put("spec", map("business", map("maximum_minutes", 1, "maximum_backlog", 3)));
    h.seed("recovery-exercise", exercise, data);
    impact[9] = 3;
    impact[3] = stamp(now - 130000);
    assertThrows(Failure.class, () -> cmd("continuity", "business_impact", impact));
    impact[3] = stamp(now);
    assertEquals(
        false,
        object(def(cmd("continuity", "business_impact", impact)).get("business_result"))
            .get("within_tolerance"));
  }

  @Test
  void workerClaimsOnlyFirstWritesWithPinnedHistory() throws Exception {
    open(false);
    ConnectorFailover.claim(h.store, h.db, null);
    assertFalse(ConnectorFailover.recover(h.store, System.currentTimeMillis()));
    ((org.springframework.mock.env.MockEnvironment) h.store.environment)
        .setProperty("CONNECTOR_WORKER_ID", "a");
    ConnectorFailover.claim(h.store, h.db, null);
    for (String kind : List.of("connector.write", "other"))
      ConnectorFailover.claim(h.store, h.db, map("job_id", id(), "kind", kind, "attempts", 1));
    assertEquals(0, h.db.count("SELECT COUNT(*) FROM os_connector_worker"));
  }

  @Test
  void purchasingRetrySkipsAlreadyOrderedLines() throws Exception {
    open(false);
    String run = id(), action = id(), existing = id();
    var configured =
        map(
            "supplier",
            "Vendor",
            "currency",
            "USD",
            "bindings",
            map(
                "model",
                "model",
                "quantity",
                "quantity",
                "unit_cost",
                "unit_cost",
                "salvage",
                "salvage",
                "months",
                "months"));
    var input =
        map("model", "Laptop", "quantity", 1, "unit_cost", "10", "months", 12, "salvage", "0");
    var order = new LinkedHashMap<>(input);
    order.putAll(
        map("key", existing, "operation", "order", "supplier", "Vendor", "currency", "USD"));
    AssetPlanning.command(h.db, order);
    h.seed(
        "supplier-agreement",
        SupplierGovernance.identity("Vendor"),
        map(
            "signed",
            List.of(
                map("version", 1, "effective", "2020-01-01", "prices", map(), "currency", "USD"))));
    var lines =
        map(
            "one",
            map("input", input, "state", "ordered", "order", existing),
            "two",
            map("input", input, "state", "failed"));
    h.seed(
        "exception-run",
        run,
        map("state", "needs_retry", "lines", lines, "action", configured, "action_id", action));
    h.seed("workflow-run", run, map("state", "complete"));
    var completed = def(cmd("designer", "advance_exception", "new_id", run));
    assertEquals("ordered", completed.get("state"));
    assertEquals(existing, object(object(completed.get("lines")).get("one")).get("order"));
    assertEquals(2, list(h.db, "purchase").size());
    assertEquals(1, get(h.db, "purchase", existing).get("revision"));
  }

  @Test
  void repairCanBeVerifiedOnIndependentStandbyAfterPrimaryRemainsDown() throws Exception {
    open(false);
    String bundle = id(),
        circuit = key(bundle, "Office"),
        primary = id(),
        backup = id(),
        objective = objective(),
        outage = id();
    var args = map("bundle_id", bundle, "location", "Office", "objective_id", objective);
    CircuitAssurance.notifyStandby(h.db, circuit, "backup");
    h.seed(
        "site-monitor", circuit, map("service", "cloud :: shop", "account", id(), "contact", h.c));
    CircuitAssurance.notifyStandby(h.db, circuit, "backup");
    assertEquals(0, get(h.db, "standby-status", circuit).get("revision"));
    h.seed("network-port", primary, map("state", "down", "circuits", map(), "capacity", 2));
    h.seed("network-port", backup, map("state", "down", "circuits", map(), "capacity", 2));
    h.seed(
        "network-circuit",
        circuit,
        map("state", "active", "path", List.of(primary), "units", 1, "objective", objective));
    Object[] path = {
      "bundle_id", bundle, "location", "Office", "entries", List.of(backup), "revision", 1
    };
    assertThrows(Failure.class, () -> cmd("sales", "backup_path", path));
    h.seed("network-port", backup, map("state", "up", "circuits", map(), "capacity", 2));
    cmd("sales", "backup_path", path);
    long bucket = System.currentTimeMillis() / 60000 * 60000;
    h.seed(
        "circuit-interruption",
        circuit,
        map(
            "state",
            "open",
            "opened",
            bucket - 60000,
            "outage",
            outage,
            "service",
            "cloud :: shop"));
    h.seed("site-outage", outage, map("state", "open", "sites", List.of(), "circuit", circuit));
    cmd(
        "mapping",
        "transaction_rule",
        "objective_id",
        objective,
        "revision",
        0,
        "expected",
        "Site available",
        "limit",
        500);
    for (String location : List.of("east", "west"))
      h.db.update(
          "INSERT INTO os_transaction_sample(objective,bucket,location,detail) VALUES(?,?,?,?)",
          objective,
          bucket,
          location,
          JSON.writeValueAsString(map("result", "up", "rule_revision", 1)));
    assertEquals(
        "restored",
        def(object(CircuitAssurance.run(h.store, h.db, "assure_site", args))).get("state"));
  }
}
