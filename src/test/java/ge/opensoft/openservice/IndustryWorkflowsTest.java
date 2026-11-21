package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.definition;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IndustryWorkflowsTest {
  @TempDir Path root;
  Store store;
  Store.Db db;
  String admin, a, b, c, team, second;

  void open(boolean pg) throws Exception {
    var migration = new MigrationsTest();
    migration.root = root;
    store = new Store(migration.configuration(pg));
    db = store.open();
    admin = PlatformFoundationTest.bind(db);
    a = user("reviewer", "agent");
    b = user("technician", "agent");
    c = user("customer", "requester");
    team = record("sys_user_group");
    second = record("sys_user_group");
    db.update(
        "INSERT INTO os_group_member(group_id,user_id) VALUES(?,?),(?,?),(?,?)",
        team,
        a,
        team,
        b,
        second,
        b);
  }

  String record(String table) throws Exception {
    return PlatformFoundationTest.record(db, table, "Fixture " + id());
  }

  String user(String name, String role) throws Exception {
    Service.createAccount(
        db, map("name", name, "role", role, "password", WorkflowSupport.PASSWORD));
    return RecordAccess.owner(db, name);
  }

  void as(String user) throws Exception {
    db.viewerId = user;
    db.viewerRole = Service.account(db, user).get("role").toString();
  }

  @AfterEach
  void close() throws Exception {
    if (db != null) db.close();
  }

  Object run(String area, String op, Object... parts) throws Exception {
    var body = map(parts);
    body.put("operation", op);
    var point = db.connection.setSavepoint();
    try {
      return DeliveryWorkspace.command(store, db, area, body);
    } catch (Exception failure) {
      db.connection.rollback(point);
      throw failure;
    } finally {
      db.connection.releaseSavepoint(point);
    }
  }

  void seed(String area, String subject, Map<String, Object> data) throws Exception {
    save(db, area, subject, get(db, area, subject).get("revision"), data);
  }

  String account() throws Exception {
    String account = id();
    db.update(
        "INSERT INTO os_customer_account(account_id,name,active,group_id,revision)"
            + " VALUES(?,?,1,?,1)",
        account,
        "Industrial customer",
        team);
    return account;
  }

  @ParameterizedTest
  @ValueSource(booleans={false,true})
  void attendedBatchResumesByBusinessReceiptAndFlagsConflicts(boolean pg) throws Exception {
    open(pg);
    run("apps","case_program","kind","permit","group_id",team,"revision",0);as(c);
    String batch=id();
    var detail=map("site","Building B","activity","Temporary works");
    var other=map("site","Building C","activity","Temporary works");
    var first=map("kind","permit","reference","1","manifest",detail);
    var second=map("kind","permit","reference","2","manifest",other);
    for(var bad:List.of(List.of(),List.of(first,first)))
      assertThrows(Failure.class,()->run("apps","open_attended_batch","new_id",batch,"entries",bad));
    Object[] args={"new_id",batch,"entries",List.of(first,second)};
    assertEquals(2,object(run("apps","open_attended_batch",args)).get("pending"));
    run("apps","file_industry_case","new_id",id(),"kind","permit","reference","1","manifest",detail);
    var resumed=object(run("apps","open_attended_batch",args));assertEquals(1,resumed.get("received"));assertEquals(1,resumed.get("pending"));
    run("apps","file_industry_case","new_id",id(),"kind","permit","reference","2","manifest",detail);
    var report=object(run("apps","attended_batch_status","new_id",batch));assertEquals(1,report.get("conflicts"));
    assertThrows(Failure.class,()->run("apps","open_attended_batch","new_id",batch,"entries",List.of(first)));
    as(a);
    assertThrows(Failure.class,()->run("apps","attended_batch_status","new_id",batch));
  }
  @ParameterizedTest
  @ValueSource(booleans={false,true})
  void industryCasesRequireIndependentDecisionsAndIsolateSubmitters(boolean pg) throws Exception {
    open(pg);
    String equipment=record("cmdb_ci");
    db.update("INSERT INTO os_requester_record(record_id,user_id) VALUES(?,?)",equipment,c);
    for (var program:IndustryCases.Program.values()) {
      as(admin);
      String kind=program.name(), ticket=id(), ref="external-"+kind;
      run("apps","case_program","kind",kind,"group_id",team,"revision",0);
      Map<String,Object> input=switch(program) {
        case dispute -> map("amount","240.50","currency","USD","reason","Duplicate debit");
        case turnaround -> map("equipment",equipment,"site","Warehouse A");
        case permit -> map("site","Block 50","activity","Outdoor event");
      };
      if(program==IndustryCases.Program.dispute)
        run("apps","register_disputable_transaction","reference",ref,"user_id",c,"amount","250","currency","USD");
      as(c);
      Object[] filing={"kind",kind,"new_id",ticket,"reference",ref,"manifest",input};
      var created=object(run("apps","file_industry_case",filing));
      assertEquals(created,run("apps","file_industry_case","kind",kind,"new_id",id(),"reference",ref,"manifest",input));
      if(program==IndustryCases.Program.turnaround) {
        assertThrows(Failure.class,()->run("apps","file_industry_case","kind",kind,"new_id",id(),"reference","duplicate-equipment","manifest",input));
        as(a);assertEquals("in_turnaround",object(object(run("apps","equipment_readiness","record_id",equipment)).get("definition")).get("status"));as(c);
      }
      var conflict=new LinkedHashMap<>(input);
      conflict.put(switch(program){case dispute -> "reason"; case turnaround,permit -> "site";},"Changed");
      assertThrows(Failure.class,()->run("apps","file_industry_case","kind",kind,"new_id",id(),"reference",ref,"manifest",conflict));
      as(admin);
      assertThrows(Failure.class,()->run("apps","industry_case","new_id",ticket));
      assertEquals(List.of(),object(run("apps","industry_queue","kind",kind)).get("cases"));
      as(a);
      assertEquals(1,objects(object(run("apps","industry_queue","kind",kind)).get("cases")).size());
      run("apps","advance_industry_case","new_id",ticket,"decision",program.review,"evidence","Evidence 1","revision",1);
      assertThrows(Failure.class,()->run("apps","advance_industry_case","new_id",ticket,"decision",program.accept,"evidence","Cannot self-approve","revision",2));
      as(b);
      run("apps","advance_industry_case","new_id",ticket,"decision",program.accept,"evidence","Evidence 2","revision",2);
      assertThrows(Failure.class,()->run("apps","advance_industry_case","new_id",ticket,"decision",program.decline,"evidence","Already closed","revision",3));
      assertEquals(List.of(),object(run("apps","industry_queue","kind",kind)).get("cases"));
      as(c);
      var closed=object(object(run("apps","industry_case","new_id",ticket)).get("definition"));
      if(program==IndustryCases.Program.permit)assertEquals("PERMIT-"+ticket,closed.get("permit_number"));
      if(program==IndustryCases.Program.dispute)assertEquals("240.50",object(closed.get("adjustment")).get("amount"));
      if(program==IndustryCases.Program.turnaround){as(a);assertEquals("release",object(object(run("apps","equipment_readiness","record_id",equipment)).get("definition")).get("status"));}
      assertEquals(program.accept,closed.get("state"));assertEquals(2,objects(closed.get("history")).size());
    }
    as(admin);
    for(var bad:List.of(map("amount","0","currency","USD"),map("amount","1","currency","bad")))
      assertThrows(Failure.class,()->run("apps","register_disputable_transaction","reference","X","user_id",a,"amount",bad.get("amount"),"currency",bad.get("currency")));
    run("apps","register_disputable_transaction","reference","X","user_id",a,"amount","2","currency","USD");
    as(c);
    assertThrows(Failure.class,()->run("apps","file_industry_case","new_id",id(),"kind","dispute","reference","X","manifest",map("amount","1","currency","USD","reason","not mine")));
    as(a);String id=id();
    for(var bad:List.of(map("amount","3","currency","USD","reason","large"),map("amount","1","currency","EUR","reason","wrong currency")))
      assertThrows(Failure.class,()->run("apps","file_industry_case","new_id",id,"kind","dispute","reference","X","manifest",bad));
    assertThrows(Failure.class,()->run("apps","file_industry_case","kind","dispute","new_id",id,"reference","X","manifest",map("amount","0","currency","USD","reason","test")));
    assertThrows(Failure.class,()->run("apps","file_industry_case","kind","dispute","new_id",id,"reference","X","manifest",map("amount","1","currency","usd","reason","test")));
    run("apps","file_industry_case","kind","dispute","new_id",id,"reference","X","manifest",map("amount","1","currency","USD","reason","test"));
    assertThrows(Failure.class,()->run("apps","advance_industry_case","new_id",id,"decision","investigate","evidence","mine","revision",1));
    db.update("DELETE FROM os_group_member WHERE user_id=? AND group_id=?",a,team);
    assertThrows(Failure.class,()->run("apps","advance_industry_case","new_id",id,"decision","investigate","evidence","removed reviewer","revision",1));
  }

  @ParameterizedTest
  @ValueSource(booleans={false,true})
  void adverseCaseDecisionsDoNotCreateCreditsOrPermits(boolean pg) throws Exception {
    open(pg);
    for(String kind:List.of("dispute","permit")){
      as(admin);
      run("apps","case_program","kind",kind,"group_id",team,"revision",0);
      String id=id();var details=map("site","East","activity","Fair");
      if(kind.equals("dispute")){
        run("apps","register_disputable_transaction","reference","charge","user_id",c,"amount","3.00","currency","EUR");
        details=map("amount","3","currency","EUR","reason","Charged twice");
      }
      as(c);run("apps","file_industry_case","new_id",id,"reference","charge","kind",kind,"manifest",details);
      as(a);run("apps","advance_industry_case","new_id",id,"decision",kind.equals("dispute")?"investigate":"inspect","revision",1,"evidence","Reviewed");
      as(b);var decision=object(object(run("apps","advance_industry_case","new_id",id,"decision",kind.equals("dispute")?"decline":"deny","revision",2,"evidence","Denied")).get("definition"));
      assertFalse(decision.containsKey("adjustment")); assertFalse(decision.containsKey("permit_number"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans={false,true})
  void liveDataProductEnforcesConsumerRowFieldAndGroupRights(boolean pg) throws Exception {
    open(pg);
    String product=id(), source=record("cmdb_ci");
    for (var bad:List.of(List.of(),List.of("missing"),List.of("short_description","short_description"))) {
      assertThrows(Failure.class,()->run("apps","publish_data_product","new_id",product,"title","Fleet","table","cmdb_ci","fields",bad,"group_id",team,"revision",0));
    }
    run("apps","publish_data_product","new_id",product,"title","Fleet","table","cmdb_ci","fields",List.of("short_description"),"group_id",team,"revision",0);
    as(a);
    Object[] read={"new_id",product,"record_id",source};
    assertEquals(Set.of("short_description"),object(object(run("apps","read_data_product",read)).get("fields")).keySet());
    var schema=Service.schema(db,"cmdb_ci");
    schema.put("field_rules",map("short_description",map("type","string","read_roles",List.of("admin"))));
    db.update("UPDATE os_schema SET definition="+(db.postgres?"CAST(? AS JSON)":"?")+" WHERE name='cmdb_ci'",JSON.writeValueAsString(schema));
    assertEquals(map(),object(run("apps","read_data_product",read)).get("fields"));
    db.update("INSERT INTO os_record_access(record_id,visibility,owner_id) VALUES(?,?,?)",source,"private",b);
    assertThrows(Failure.class,()->run("apps","read_data_product",read));
    db.update("DELETE FROM os_group_member WHERE user_id=? AND group_id=?",b,team);as(b);
    assertThrows(Failure.class,()->run("apps","read_data_product",read));
    as(admin);
    for (var bad:List.of(List.of(List.of("one")),List.of(List.of("X","Y")),List.of(List.of("one","one"))))
      assertThrows(Failure.class,()->run("assistant","search_dictionary","entries",bad,"revision",0));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void regulatoryCorrectionReopensOnlyChangedRequirement(boolean pg) throws Exception {
    open(pg);
    String notice = id(), app = id(), control = id();
    seed("business-app", app, map("owner", a));
    seed("audit-control", control, map("owner", b));
    var impact =
        map(
            "area",
            "business-app",
            "subject",
            app,
            "requirement",
            "Review retention",
            "owner",
            a,
            "tester",
            b);
    var secondImpact =
        map(
            "area",
            "audit-control",
            "subject",
            control,
            "requirement",
            "Test signoff",
            "owner",
            a,
            "tester",
            b);
    String target = DeliveryWorkspace.key("business-app", app);
    run(
        "audits",
        "notice_revision",
        "notice_id",
        notice,
        "title",
        "Retention",
        "reference",
        "bulletin-1",
        "effective",
        "2028-01-01",
        "entries",
        List.of(impact, secondImpact),
        "revision",
        0);
    as(a);
    assertEquals(2, ((List<?>) run("audits", "notice_assignments")).size());
    for (var action :
        List.of(List.of("assess_impact", "affected"), List.of("remediate_impact", "complete"))) {
      run(
          "audits",
          action.get(0),
          "notice_id",
          notice,
          "target",
          target,
          "decision",
          action.get(1),
          "evidence",
          "review report",
          "revision",
          get(db, "regulatory-notice", notice).get("revision"));
    }
    assertThrows(
        Failure.class,
        () ->
            run(
                "audits",
                "test_impact",
                "notice_id",
                notice,
                "target",
                target,
                "decision",
                "pass",
                "evidence",
                "self review",
                "revision",
                3));
    as(b);
    run(
        "audits",
        "test_impact",
        "notice_id",
        notice,
        "target",
        target,
        "decision",
        "pass",
        "evidence",
        "independent report",
        "revision",
        3);
    as(admin);
    secondImpact.put("requirement", "Recheck signoff");
    run(
        "audits",
        "notice_revision",
        "notice_id",
        notice,
        "title",
        "Correction",
        "reference",
        "bulletin-2",
        "effective",
        "2028-01-01",
        "entries",
        List.of(impact, secondImpact),
        "revision",
        4);
    var current = object(run("audits", "notice_history", "notice_id", notice, "revision", 5));
    assertEquals("verified", object(object(current.get("impacts")).get(target)).get("state"));
    assertEquals(
        "Retention",
        object(run("audits", "notice_history", "notice_id", notice, "revision", 1)).get("title"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void networkContentionAndServiceImpact(boolean pg) throws Exception {
    open(pg);
    String ci = record("cmdb_ci"),
        account = account(),
        bundle = id(),
        left = id(),
        right = id(),
        shared = id(),
        full = id(),
        other = id();
    seed(
        "site-bundle",
        bundle,
        map(
            "state",
            "ordered",
            "account",
            account,
            "sites",
            map("West", map("opportunity", left), "East", map("opportunity", right))));
    for (String sale : List.of(left, right))
      seed(
          "opportunity",
          sale,
          map("order_id", id(), "entitlement", id(), "case_id", id(), "account", account));
    for (String port : List.of(shared, full, other))
      run(
          "sales",
          "network_port",
          "port_id",
          port,
          "service_ci",
          ci,
          "capacity",
          port.equals(shared) ? 2 : 1,
          "title",
          "Port " + port);
    run(
        "sales",
        "reserve_circuit",
        "bundle_id",
        bundle,
        "location",
        "West",
        "quantity",
        1,
        "entries",
        List.of(shared, full));
    assertThrows(
        Failure.class,
        () ->
            run(
                "sales",
                "reserve_circuit",
                "bundle_id",
                bundle,
                "location",
                "East",
                "quantity",
                1,
                "entries",
                List.of(shared, full)));
    run(
        "sales",
        "reserve_circuit",
        "bundle_id",
        bundle,
        "location",
        "East",
        "quantity",
        1,
        "entries",
        List.of(shared, other));
    run("sales", "activate_circuit", "bundle_id", bundle, "location", "West", "revision", 1);
    assertEquals(
        false,
        object(run("sales", "circuit_status", "bundle_id", bundle, "location", "East"))
            .get("available"));
    for (String state : List.of("down", "up")) {
      var result =
          object(
              run(
                  "sales",
                  "port_condition",
                  "port_id",
                  shared,
                  "state",
                  state,
                  "revision",
                  get(db, "network-port", shared).get("revision")));
      assertEquals(2, objects(result.get("circuits_on_port")).size());
      assertEquals(1, objects(result.get("affected_circuits")).size());
      assertEquals(
          state.equals("up"),
          object(run("sales", "circuit_status", "bundle_id", bundle, "location", "West"))
              .get("available"));
    }
    assertEquals(2, list(db, "port-event").size());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void dealerWarrantyAndCredit(boolean pg) throws Exception {
    open(pg);
    String asset =
        Service.create(
                db,
                "alm_asset",
                map(
                    "short_description",
                    "Warranty equipment",
                    "serial_number",
                    "SN-530",
                    "asset_tag",
                    "WA-530",
                    "model",
                    "Router"),
                "admin")
            .get("sys_id")
            .toString();
    run(
        "sales",
        "issue_warranty",
        "asset_id",
        asset,
        "start_on",
        "2025-01-01",
        "finish_on",
        "2026-12-31",
        "user_id",
        c,
        "owner_id",
        a,
        "finance_id",
        b,
        "currency",
        "USD",
        "amount",
        "400",
        "revision",
        0);
    as(c);
    for (int i = 0; i < 2; i++)
      run(
          "sales",
          "file_claim",
          "asset_id",
          asset,
          "reference",
          "dealer-1",
          "effective",
          "2026-01-12",
          "note",
          "Failed power supply");
    assertThrows(
        Failure.class,
        () ->
            run(
                "sales",
                "file_claim",
                "asset_id",
                asset,
                "reference",
                "dealer-2",
                "effective",
                "2024-12-31",
                "note",
                "Out of coverage"));
    assertThrows(
        Failure.class,
        () ->
            run(
                "sales",
                "file_claim",
                "asset_id",
                asset,
                "reference",
                "another ref",
                "effective",
                "2026-01-12",
                "note",
                "FAILED power supply"));
    as(a);
    run(
        "sales",
        "authorize_repair",
        "asset_id",
        asset,
        "reference",
        "dealer-1",
        "note",
        "Approved repair",
        "revision",
        1);
    as(c);
    run(
        "sales",
        "submit_repair_cost",
        "asset_id",
        asset,
        "reference",
        "dealer-1",
        "parts",
        "150",
        "labor",
        "30",
        "returned_asset",
        asset,
        "revision",
        2);
    as(a);
    assertThrows(
        Failure.class,
        () ->
            run(
                "sales",
                "adjudicate_claim",
                "asset_id",
                asset,
                "reference",
                "dealer-1",
                "amount",
                "160",
                "note",
                "Received",
                "revision",
                3));
    db.update(
        "INSERT INTO os_asset_movement(record_id,revision,state,stockroom_id,assignee_id,detail)"
            + " VALUES(?,1,'in_stock',?,'',?)",
        asset,
        id(),
        JSON.writeValueAsString(map("operation", "return")));
    run(
        "sales",
        "adjudicate_claim",
        "asset_id",
        asset,
        "reference",
        "dealer-1",
        "amount",
        "160",
        "note",
        "Adjusted labor",
        "revision",
        3);
    as(b);
    for (int i = 0; i < 2; i++)
      run("sales", "credit_claim", "asset_id", asset, "reference", "dealer-1", "revision", 4);
    assertEquals(1, list(db, "dealer-credit").size());
    assertTrue(((List<?>) run("sales", "my_claims")).isEmpty());
    as(c);
    var mine = objects(run("sales", "my_claims"));
    assertEquals("dealer-1", mine.getFirst().get("reference"));
    assertEquals(asset, mine.getFirst().get("asset_id"));
    assertEquals(
        "credited",
        object(
                object(run("sales", "claim_status", "asset_id", asset, "reference", "dealer-1"))
                    .get("definition"))
            .get("state"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void workspaceSharesStageAndKeepsConfidentialAnswersPrivate(boolean pg) throws Exception {
    open(pg);
    String review = id(), legal = id(), lastCase = "";
    run(
        "apps",
        "review_stage",
        "stage_id",
        review,
        "group_id",
        team,
        "title",
        "Procurement",
        "note",
        "Check order",
        "revision",
        0);
    run(
        "apps",
        "review_stage",
        "stage_id",
        legal,
        "group_id",
        second,
        "title",
        "Legal",
        "note",
        "Check terms",
        "revision",
        0);
    for (String name : List.of("Supplier exception", "HR exception")) {
      as(admin);
      String app = id(), ticket = id();
      lastCase = ticket;
      var fields =
          map(
              "reason",
              map("visibility", "requester"),
              "salary",
              map("visibility", "confidential"));
      seed("intake-release", app, map("fields", fields));
      seed(
          "intake-case",
          ticket,
          map(
              "app",
              app,
              "requester",
              c,
              "group",
              team,
              "fields",
              fields,
              "answers",
              map("reason", "Transfer"),
              "private_answers",
              map("salary", "Restricted")));
      run(
          "apps",
          "compose_workspace",
          "app_id",
          app,
          "title",
          name,
          "fields",
          List.of("reason", "salary"),
          "steps",
          List.of(review, legal),
          "revision",
          0);
      as(c);
      var view = object(run("apps", "guide_case", "case_id", ticket));
      assertFalse(object(view.get("fields")).containsKey("salary"));
      as(a);
      assertEquals(1, ((List<?>) run("apps", "guided_queue", "app_id", app)).size());
      for (String step : List.of("wait", "resume", "complete"))
        run(
            "apps",
            "advance_stage",
            "case_id",
            ticket,
            "decision",
            step,
            "note",
            "Review record",
            "revision",
            get(db, "guided-case", ticket).get("revision"));
      as(b);
      run(
          "apps",
          "advance_stage",
          "case_id",
          ticket,
          "decision",
          "complete",
          "note",
          "Reviewed",
          "revision",
          4);
    }
    assertEquals("complete", definition(db, "guided-case", lastCase).get("state"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void endpointReportsShareNativeIdentityAndRetirementReleasesSeat(boolean pg) throws Exception {
    open(pg);
    String collector = id(), license = id();
    ServiceTopology.inventory(
        db,
        "cloud",
        map(
            "sequence",
            1,
            "resources",
            List.of(
                map(
                    "id",
                    "machine",
                    "title",
                    "Cloud machine",
                    "type",
                    "host",
                    "depends_on",
                    List.of())),
            "services",
            List.of(map("id", "checkout", "title", "Checkout", "roots", List.of("machine")))));
    run(
        "mapping",
        "enroll_endpoint",
        "collector_id",
        collector,
        "source",
        "cloud",
        "resource_id",
        "machine",
        "platform",
        "Windows",
        "user_id",
        a,
        "minutes",
        5);
    var manifest =
        map(
            "platform",
            "Windows",
            "hostname",
            "machine.example",
            "machine",
            "AMD64",
            "software",
            List.of("CAD"));
    as(b);
    assertThrows(
        Failure.class,
        () ->
            run(
                "mapping",
                "endpoint_report",
                "collector_id",
                collector,
                "sequence",
                1,
                "manifest",
                manifest));
    as(a);
    for (int i = 0; i < 2; i++)
      run(
          "mapping",
          "endpoint_report",
          "collector_id",
          collector,
          "sequence",
          1,
          "manifest",
          manifest);
    run(
        "mapping",
        "endpoint_report",
        "collector_id",
        collector,
        "sequence",
        2,
        "manifest",
        manifest);
    assertThrows(
        Failure.class,
        () ->
            run(
                "mapping",
                "endpoint_report",
                "collector_id",
                collector,
                "sequence",
                1,
                "manifest",
                manifest));
    as(admin);
    var status = object(run("mapping", "endpoint_status", "collector_id", collector));
    assertEquals(
        object(status.get("cloud_facts")).get("ci_id"),
        object(object(status.get("endpoint_facts")).get("definition")).get("ci"));
    assertEquals(false, status.get("stale"));
    var stale = definition(db, "endpoint-observation", collector);
    stale.put("received", 1);
    seed("endpoint-observation", collector, stale);
    assertEquals(
        true, object(run("mapping", "endpoint_status", "collector_id", collector)).get("stale"));
    AssetPlanning.command(
        db,
        map(
            "operation",
            "license",
            "key",
            license,
            "product",
            "CAD",
            "seats",
            1,
            "expires_on",
            "2090-01-01"));
    run(
        "mapping",
        "endpoint_seat",
        "collector_id",
        collector,
        "license_id",
        license,
        "user_id",
        b,
        "revision",
        1);
    assertThrows(
        Failure.class,
        () ->
            run(
                "mapping",
                "endpoint_seat",
                "collector_id",
                collector,
                "license_id",
                license,
                "user_id",
                b,
                "revision",
                2));
    ServiceTopology.inventory(
        db, "cloud", map("sequence", 2, "services", List.of(), "resources", List.of()));
    assertEquals("retired", definition(db, "endpoint-enrollment", collector).get("state"));
    assertTrue(((List<?>) definition(db, "entitlement", license).get("users")).isEmpty());
    assertEquals(
        0, object(definition(db, "service-map", "endpoint-" + collector).get("resources")).size());
    as(a);
    assertThrows(
        Failure.class,
        () ->
            run(
                "mapping",
                "endpoint_report",
                "collector_id",
                collector,
                "sequence",
                2,
                "manifest",
                manifest));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void contractorOfferTransferOfflineReplayAndSettlement(boolean pg) throws Exception {
    open(pg);
    db.customerIntake = true;
    String account = account(),
        parent = record("customer_case"),
        ent = id(),
        task = id(),
        asset = record("alm_asset"),
        depot = record("stockroom");
    db.customerIntake = false;
    db.update(
        "INSERT INTO os_customer_contact(user_id,account_id,active,revision) VALUES(?,?,1,1)",
        c,
        account);
    db.update(
        "INSERT INTO"
            + " os_customer_entitlement(entitlement_id,account_id,name,description,active,starts,ends,case_limit,revision)"
            + " VALUES(?,?,?, '',1,0,?,10,1)",
        ent,
        account,
        "Service contract",
        System.currentTimeMillis() + 86400000L);
    db.update(
        "INSERT INTO"
            + " os_customer_case(record_id,account_id,user_id,entitlement_id,account_revision,contact_revision,entitlement_revision,account_name,entitlement_name,intake_key,fingerprint,created)"
            + " VALUES(?,?,?,?,1,1,1,'Company','Service',?,?,?)",
        parent,
        account,
        c,
        ent,
        id(),
        id(),
        now());
    db.update("INSERT INTO os_task_plan(parent_id,revision,close_at) VALUES(?,1,'7')", parent);
    db.update(
        "INSERT INTO"
            + " os_case_task(task_id,parent_id,ordinal,summary,details,group_id,assignee_id,state,required,predecessors,outcome,note,revision)"
            + " VALUES(?,?,1,'repair','',?,'','queued',1,'[]','','',1)",
        task,
        parent,
        team);
    db.update(
        "INSERT INTO os_asset_movement(record_id,revision,state,stockroom_id,assignee_id,detail)"
            + " VALUES(?,1,'issued','',?,'{}')",
        asset,
        c);
    seed("installation-account", asset, map("account", account));
    for (var pair : List.of(List.of(team, a), List.of(second, b)))
      run(
          "routing",
          "contract_company",
          "group_id",
          pair.get(0),
          "user_id",
          pair.get(1),
          "active",
          true,
          "revision",
          0);
    String replacement = id();
    seed("depot-transfer", id(), map("task", task, "state", "reserved", "asset", replacement));
    seed("depot-transfer", id(), map("task", task, "state", "returned", "asset", id()));
    seed("depot-transfer", id(), map("task", id(), "state", "picked_up", "asset", id()));
    String expires = Instant.ofEpochSecond(Instant.now().getEpochSecond() + 600).toString();
    run(
        "routing",
        "offer_repair",
        "task_id",
        task,
        "case_id",
        parent,
        "asset_id",
        asset,
        "entries",
        List.of(team, second),
        "expires",
        expires,
        "note",
        "Site entrance B",
        "revision",
        0);
    assertThrows(Failure.class, () -> run("routing", "repair_packet", "task_id", id()));
    var offer =
        map(
            "task_id",
            task,
            "case_id",
            parent,
            "asset_id",
            asset,
            "entries",
            List.of(team),
            "expires",
            expires,
            "note",
            "Review",
            "revision",
            1);
    for (String condition :
        List.of(
            "inactive",
            "future",
            "ended",
            "no-entitlement",
            "no-case",
            "wrong-site",
            "wrong-serial",
            "no-companies",
            "expired")) {
      var rollback = db.connection.setSavepoint();
      var changed = new LinkedHashMap<>(offer);
      if (condition.equals("no-entitlement") && pg) {
        assertThrows(
            java.sql.SQLException.class,
            () ->
                db.update(
                    "UPDATE os_customer_case SET entitlement_id=? WHERE record_id=?",
                    id(),
                    parent));
        db.connection.rollback(rollback);
        db.connection.releaseSavepoint(rollback);
        continue;
      }
      switch (condition) {
        case "inactive" ->
            db.update("UPDATE os_customer_entitlement SET active=0 WHERE entitlement_id=?", ent);
        case "future" ->
            db.update(
                "UPDATE os_customer_entitlement SET starts=? WHERE entitlement_id=?",
                System.currentTimeMillis() + 86400000L,
                ent);
        case "ended" ->
            db.update("UPDATE os_customer_entitlement SET ends=1 WHERE entitlement_id=?", ent);
        case "no-entitlement" ->
            db.update(
                "UPDATE os_customer_case SET entitlement_id=? WHERE record_id=?", id(), parent);
        case "no-case" -> db.update("DELETE FROM os_customer_case WHERE record_id=?", parent);
        case "wrong-site" -> seed("installation-account", asset, map("account", id()));
        case "wrong-serial" ->
            db.update("UPDATE os_asset_movement SET assignee_id=? WHERE record_id=?", a, asset);
        case "no-companies" -> changed.put("entries", List.of());
        case "expired" -> changed.put("expires", "2020-01-01T00:00:00Z");
      }
      assertThrows(
          Failure.class, () -> ContractorRepairs.run(db, "offer_repair", changed), condition);
      db.connection.rollback(rollback);
      db.connection.releaseSavepoint(rollback);
    }
    var original = definition(db, "contract-repair", task);
    var point = db.connection.setSavepoint();
    original.put("expires", 1L);
    seed("contract-repair", task, original);
    as(a);
    assertThrows(
        Failure.class,
        () -> run("routing", "accept_repair", "task_id", task, "group_id", team, "revision", 2));
    db.connection.rollback(point);
    db.connection.releaseSavepoint(point);
    as(admin);
    assertEquals(
        "offered",
        object(object(run("routing", "repair_packet", "task_id", task)).get("job")).get("state"));
    as(c);
    assertTrue(((List<?>) run("routing", "contract_queue")).isEmpty());
    assertThrows(
        Failure.class,
        () -> run("routing", "accept_repair", "task_id", task, "group_id", team, "revision", 1));
    as(a);
    assertEquals(1, ((List<?>) run("routing", "contract_queue")).size());
    run("routing", "accept_repair", "task_id", task, "group_id", team, "revision", 1);
    as(b);
    assertTrue(((List<?>) run("routing", "contract_queue")).isEmpty());
    assertThrows(
        Failure.class,
        () -> run("routing", "assign_repair", "task_id", task, "technician_id", b, "revision", 2));
    assertThrows(
        Failure.class,
        () -> run("routing", "accept_repair", "task_id", task, "group_id", second, "revision", 1));
    as(admin);
    run(
        "routing",
        "offer_repair",
        "task_id",
        task,
        "case_id",
        parent,
        "asset_id",
        asset,
        "entries",
        List.of(second),
        "expires",
        expires,
        "note",
        "Previous crew missed visit",
        "revision",
        2);
    as(a);
    assertThrows(Failure.class, () -> run("routing", "repair_packet", "task_id", task));
    as(b);
    var guard = db.connection.setSavepoint();
    seed("contract-company", second, map("dispatcher", b, "approved", false));
    assertThrows(
        Failure.class,
        () -> run("routing", "accept_repair", "task_id", task, "group_id", second, "revision", 3));
    assertTrue(((List<?>) run("routing", "contract_queue")).isEmpty());
    db.connection.rollback(guard);
    db.update("DELETE FROM os_group_member WHERE group_id=? AND user_id=?", second, b);
    assertThrows(
        Failure.class,
        () -> run("routing", "accept_repair", "task_id", task, "group_id", second, "revision", 3));
    db.connection.rollback(guard);
    db.connection.releaseSavepoint(guard);
    assertThrows(
        Failure.class,
        () -> run("routing", "accept_repair", "task_id", task, "group_id", team, "revision", 3));
    run("routing", "accept_repair", "task_id", task, "group_id", second, "revision", 3);
    assertThrows(
        Failure.class,
        () -> run("routing", "assign_repair", "task_id", task, "technician_id", a, "revision", 4));
    var separateTech = db.connection.setSavepoint();
    db.update("INSERT INTO os_group_member(group_id,user_id) VALUES(?,?)", second, a);
    run("routing", "assign_repair", "task_id", task, "technician_id", a, "revision", 4);
    assertEquals(
        "assigned",
        object(object(run("routing", "repair_packet", "task_id", task)).get("job")).get("state"));
    assertThrows(
        Failure.class,
        () -> run("routing", "assign_repair", "task_id", task, "technician_id", b, "revision", 5));
    assertThrows(
        Failure.class, () -> ContractorRepairs.run(db, "settle_repair", map("task_id", task)));
    assertThrows(
        Failure.class,
        () -> ContractorRepairs.run(db, "repair_sync", map("task_id", task, "asset_id", asset)));
    as(a);
    assertEquals(1, ((List<?>) run("routing", "contract_queue")).size());
    assertThrows(
        Failure.class,
        () -> ContractorRepairs.run(db, "repair_sync", map("task_id", task, "asset_id", id())));
    db.connection.rollback(separateTech);
    db.connection.releaseSavepoint(separateTech);
    as(b);
    run("routing", "assign_repair", "task_id", task, "technician_id", b, "revision", 4);
    assertEquals(
        List.of(replacement),
        object(object(run("routing", "repair_packet", "task_id", task)).get("job")).get("parts"));
    assertEquals(1, ((List<?>) run("routing", "contract_queue")).size());
    as(c);
    assertEquals(
        "assigned",
        object(object(run("routing", "repair_packet", "task_id", task)).get("job")).get("state"));
    assertThrows(
        Failure.class,
        () ->
            run(
                "routing",
                "sign_repair",
                "task_id",
                task,
                "reference",
                "premature",
                "revision",
                5));
    as(b);
    assertThrows(
        Failure.class,
        () -> run("routing", "repair_sync", "task_id", task, "asset_id", id(), "revision", 5));
    as(a);
    assertThrows(
        Failure.class,
        () -> run("routing", "repair_sync", "task_id", task, "asset_id", asset, "revision", 5));
    as(admin);
    assertThrows(
        Failure.class,
        () ->
            run(
                "routing",
                "settle_repair",
                "task_id",
                task,
                "reference",
                "before-work",
                "revision",
                5));
    as(b);
    String packet = id();
    for (int i = 0; i < 2; i++)
      run(
          "routing",
          "repair_sync",
          "task_id",
          task,
          "new_id",
          packet,
          "amount",
          "125.50",
          "asset_id",
          asset,
          "note",
          "Completed onsite",
          "revision",
          5);
    as(b);
    assertThrows(
        Failure.class,
        () ->
            run(
                "routing",
                "sign_repair",
                "task_id",
                task,
                "reference",
                "tech-signature",
                "revision",
                6));
    as(c);
    db.update("UPDATE os_customer_contact SET active=0 WHERE user_id=?", c);
    assertThrows(
        Failure.class,
        () ->
            run(
                "routing",
                "sign_repair",
                "task_id",
                task,
                "reference",
                "offboarded",
                "revision",
                6));
    db.update("UPDATE os_customer_contact SET active=1 WHERE user_id=?", c);
    run("routing", "sign_repair", "task_id", task, "reference", "customer-receipt", "revision", 6);
    as(admin);
    assertThrows(
        Failure.class,
        () ->
            run("routing", "settle_repair", "task_id", task, "reference", "INV-01", "revision", 7));
    db.update(
        "INSERT INTO os_asset_movement(record_id,revision,state,stockroom_id,assignee_id,detail)"
            + " VALUES(?,2,'in_stock',?,'','{}')",
        asset,
        depot);
    var missingDepot = db.connection.setSavepoint();
    db.update(
        "UPDATE os_asset_movement SET stockroom_id='' WHERE record_id=? AND revision=2", asset);
    assertThrows(
        Failure.class,
        () ->
            run("routing", "settle_repair", "task_id", task, "reference", "INV-01", "revision", 7));
    db.connection.rollback(missingDepot);
    db.connection.releaseSavepoint(missingDepot);
    for (var invalidReceipt :
        List.of(
            map("state", "returned", "returned_for", id()),
            map("state", "taken", "returned_for", task))) {
      seed("installation-account", asset, invalidReceipt);
      assertThrows(
          Failure.class,
          () ->
              run(
                  "routing",
                  "settle_repair",
                  "task_id",
                  task,
                  "reference",
                  "INV-01",
                  "revision",
                  7));
    }
    seed("installation-account", asset, map("state", "returned", "returned_for", task));
    for (int i = 0; i < 2; i++)
      run("routing", "settle_repair", "task_id", task, "reference", "INV-01", "revision", 7);
    assertEquals(1, list(db, "contract-invoice").size());
    assertEquals("complete", WorkTasks.task(db, parent, task).get("state"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void badCheckoutAndLocationFailureHaveDifferentBudgetEffects(boolean pg) throws Exception {
    open(pg);
    String objective = id(), roster = id();
    ServiceTopology.inventory(
        db,
        "cloud",
        map(
            "sequence",
            1,
            "resources",
            List.of(
                map(
                    "id",
                    "checkout",
                    "type",
                    "application",
                    "title",
                    "Payment checkout",
                    "depends_on",
                    List.of())),
            "services",
            List.of(map("id", "shop", "title", "Store", "roots", List.of("checkout")))));
    seed(
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
            List.of(admin, a),
            "minutes",
            5));
    run(
        "mapping",
        "define_objective",
        "objective_id",
        objective,
        "source",
        "cloud",
        "resource_id",
        "checkout",
        "location",
        "west",
        "backup_location",
        "east",
        "roster_id",
        roster,
        "minutes",
        5,
        "target_bps",
        9900);
    run(
        "mapping",
        "transaction_rule",
        "objective_id",
        objective,
        "expected",
        "Order ready",
        "limit",
        800,
        "revision",
        0);
    long now = System.currentTimeMillis(), bucket = now - now % 300000;
    var latency =
        map(
            "healthy",
            true,
            "matched",
            true,
            "latency_ms",
            12000,
            "status",
            200,
            "expected",
            "Order ready");
    var wrong =
        map(
            "healthy",
            true,
            "matched",
            false,
            "latency_ms",
            100,
            "status",
            200,
            "expected",
            "Order ready");
    for (Object location : List.of("west", "east")) {
      var raw = location.equals("west") ? latency : wrong;
      for (int i = 0; i < 2; i++)
        run(
            "mapping",
            "transaction_sample",
            "objective_id",
            objective,
            "location",
            location,
            "observed_at",
            Instant.ofEpochMilli(bucket - 300000).toString(),
            "manifest",
            raw);
      var probe = new LinkedHashMap<>(raw);
      probe.put("healthy", false);
      run(
          "mapping",
          "transaction_sample",
          "objective_id",
          objective,
          "location",
          location,
          "observed_at",
          Instant.ofEpochMilli(bucket).toString(),
          "manifest",
          probe);
    }
    assertEquals(
        4, ((List<?>) run("mapping", "transaction_evidence", "objective_id", objective)).size());
    assertEquals(
        300000L,
        ReliabilityObjectives.report(
                db, objective, definition(db, "service-objective", objective), bucket + 300000)
            .get("consumed_ms"));
    assertEquals(1, list(db, "incident-page").size());
    run(
        "mapping",
        "transaction_rule",
        "objective_id",
        objective,
        "expected",
        "New checkout receipt",
        "limit",
        500,
        "revision",
        1);
    String firstStamp = Instant.ofEpochMilli(bucket - 300000).toString();
    assertEquals(
        true,
        object(
                run(
                    "mapping",
                    "transaction_sample",
                    "objective_id",
                    objective,
                    "location",
                    "west",
                    "observed_at",
                    firstStamp,
                    "manifest",
                    latency))
            .get("replayed"));
    assertThrows(
        Failure.class,
        () ->
            run(
                "mapping",
                "transaction_sample",
                "objective_id",
                objective,
                "location",
                "west",
                "observed_at",
                firstStamp,
                "manifest",
                wrong));
    assertEquals(1, list(db, "incident-page").size());
    assertThrows(
        Failure.class,
        () ->
            run(
                "mapping",
                "probe_sample",
                "objective_id",
                objective,
                "location",
                "west",
                "observed_at",
                Instant.ofEpochMilli(bucket).toString(),
                "result",
                "up"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void delegatedConfluenceViewerMustSeeTheSamePageAtEveryTurn(boolean pg) throws Exception {
    open(pg);
    String cloud = java.util.UUID.randomUUID().toString(), intent = id();
    var intentData = map("article", id(), "offering", id());
    seed("employee-intent", intent, intentData);
    for (String alias : List.of("publisher", "delegated"))
      db.update(
          "INSERT INTO os_connector(alias,origin,envelope,revision)"
              + " VALUES(?,'https://api.atlassian.com','fixture',1)",
          alias);
    var account = new java.util.concurrent.atomic.AtomicReference<>("allowed-account");
    var groupID = new java.util.concurrent.atomic.AtomicReference<>("hr-readers");
    var pageID = new java.util.concurrent.atomic.AtomicReference<>("53");
    var space = new java.util.concurrent.atomic.AtomicReference<>("HR");
    var status = new java.util.concurrent.atomic.AtomicReference<>("current");
    var deleted = new java.util.concurrent.atomic.AtomicBoolean();
    try (var transport = org.mockito.Mockito.mockStatic(Connectors.class)) {
      transport
          .when(
              () ->
                  Connectors.fetch(
                      org.mockito.ArgumentMatchers.eq(store),
                      org.mockito.ArgumentMatchers.eq(db),
                      org.mockito.ArgumentMatchers.any()))
          .thenAnswer(
              call -> {
                var request = object(call.getArgument(2));
                String path = request.get("path").toString();
                assertTrue(path.startsWith("/ex/confluence/" + cloud + "/wiki/rest/api/"));
                Object payload;
                if (path.endsWith("user/current")) payload = map("accountId", account.get());
                else if (path.contains("restriction/byOperation/read"))
                  payload =
                      map(
                          "operation",
                          "read",
                          "restrictions",
                          map(
                              "group",
                              map(
                                  "start",
                                  0,
                                  "limit",
                                  100,
                                  "results",
                                  List.of(map("id", groupID.get())))));
                else {
                  if (deleted.get()) throw new Failure(404, "Page deleted");
                  payload =
                      map(
                          "id",
                          pageID.get(),
                          "status",
                          status.get(),
                          "title",
                          "Policy",
                          "space",
                          map("key", space.get()),
                          "ancestors",
                          List.of(map("id", "2")),
                          "version",
                          map("number", 3),
                          "body",
                          map("storage", map("value", "<p>Reviewed policy</p>")));
                }
                return map("content", JSON.writeValueAsBytes(payload));
              });
      run(
          "assistant",
          "confluence_reader",
          "cloud_id",
          cloud,
          "user_id",
          a,
          "alias",
          "delegated",
          "account_id",
          "allowed-account",
          "revision",
          0);
      run(
          "assistant",
          "index_confluence",
          "cloud_id",
          cloud,
          "intent_id",
          intent,
          "page_id",
          "53",
          "group_id",
          team,
          "alias",
          "publisher",
          "revision",
          0);
      as(a);
      var citation = map();
      ExternalPolicies.cite(store, db, intentData, citation);
      assertEquals(
          "Reviewed policy", object(citation.get("external")).get("body").toString().strip());
      as(c);
      assertThrows(Failure.class, () -> ExternalPolicies.cite(store, db, intentData, map()));
      as(a);
      account.set("other-account");
      assertThrows(Failure.class, () -> ExternalPolicies.cite(store, db, intentData, map()));
      account.set("allowed-account");
      space.set("PRIVATE");
      assertThrows(Failure.class, () -> ExternalPolicies.cite(store, db, intentData, map()));
      as(admin);
      var refreshed = object(run("assistant", "refresh_confluence", "intent_id", intent));
      assertEquals(List.of("hr-readers"), object(refreshed.get("definition")).get("read_groups"));
      assertEquals(refreshed, run("assistant", "refresh_confluence", "intent_id", intent));
      groupID.set("legal");
      run("assistant", "refresh_confluence", "intent_id", intent);
      as(a);
      pageID.set("54");
      assertThrows(Failure.class, () -> ExternalPolicies.cite(store, db, intentData, map()));
      pageID.set("53");
      ExternalPolicies.cite(store, db, intentData, map());
      status.set("archived");
      assertThrows(Failure.class, () -> ExternalPolicies.cite(store, db, intentData, map()));
      status.set("current");
      deleted.set(true);
      assertThrows(Failure.class, () -> ExternalPolicies.cite(store, db, intentData, map()));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void independentInstallationsPromoteTheSameArtifactAndRollback(boolean pg) throws Exception {
    open(pg);
    String sourceApp = id(), sourceGroup = team, sourceStage = id();
    var flow = map("days", 3, "rows", List.of(), "tests", map());
    var field = map("label", "Legal memo", "visibility", "confidential");
    seed(
        "intake-release",
        sourceApp,
        map("title", "Exception", "fields", map("memo", field), "group", team, "workflow", flow));
    run(
        "apps",
        "review_stage",
        "stage_id",
        sourceStage,
        "group_id",
        team,
        "title",
        "Legal review",
        "note",
        "Verify terms",
        "revision",
        0);
    run(
        "apps",
        "compose_workspace",
        "app_id",
        sourceApp,
        "title",
        "Exception desk",
        "fields",
        List.of("memo"),
        "steps",
        List.of(sourceStage),
        "revision",
        0);
    String crossTeam = id();
    run(
        "apps",
        "review_stage",
        "stage_id",
        crossTeam,
        "group_id",
        second,
        "title",
        "People operations",
        "note",
        "Check location",
        "revision",
        0);
    run(
        "apps",
        "compose_workspace",
        "app_id",
        sourceApp,
        "title",
        "Two teams",
        "fields",
        List.of("memo"),
        "steps",
        List.of(sourceStage, crossTeam),
        "revision",
        1);
    var pkg = PortableApps.export(db, sourceApp);
    assertTrue(pkg.containsKey("workspace"));
    db.close();
    db = null;
    for (String site : List.of("test", "production")) {
      var sub = new MigrationsTest();
      sub.root = java.nio.file.Files.createDirectories(root.resolve(site));
      store = new Store(sub.configuration(pg));
      db = store.open();
      admin = PlatformFoundationTest.bind(db);
      String publisher = user("Publisher", "admin"),
          employee = user("Applicant", "requester"),
          agent = user("Reviewer", "agent"),
          outside = user("Outsider", "requester");
      String group = record("sys_user_group"), design = id(), app = id();
      db.update("INSERT INTO os_group_member(group_id,user_id) VALUES(?,?)", group, agent);
      seed("decision-release", design, flow);
      run(
          "apps",
          "release_test",
          "app_id",
          app,
          "requester",
          employee,
          "reviewer",
          agent,
          "outsider",
          outside,
          "private_field",
          "memo",
          "revision",
          0);
      String ticket = id();
      seed(
          "intake-case",
          ticket,
          map(
              "app",
              app,
              "requester",
              employee,
              "group",
              group,
              "private_answers",
              map("memo", "retained")));
      db.update(
          "INSERT INTO os_connector(alias,origin,envelope,revision)"
              + " VALUES('local','https://local.invalid','local-envelope',1)");
      for (int i = 1; i <= 2; i++) {
        as(admin);
        var release = new LinkedHashMap<>(pkg);
        release.put("title", "Exception " + i);
        String digest =
            org.apache.commons.codec.digest.DigestUtils.sha256Hex(
                JSON.writeValueAsBytes(GitReleases.canonical(release)));
        String commit = String.valueOf(i).repeat(40);
        Object base = get(db, "intake-release", app).get("revision");
        run(
            "apps",
            "git_stage",
            "app_id",
            app,
            "commit",
            commit,
            "digest",
            digest,
            "group_id",
            group,
            "design_id",
            design,
            "bindings",
            map(second, group),
            "manifest",
            release,
            "revision",
            get(db, "intake-draft", app).get("revision"),
            "release_revision",
            base);
        assertThrows(
            Failure.class,
            () -> run("apps", "git_publish", "app_id", app, "commit", commit, "digest", digest));
        as(publisher);
        run("apps", "git_publish", "app_id", app, "commit", commit, "digest", digest);
        var status =
            object(run("apps", "git_status", "app_id", app, "commit", commit, "digest", digest));
        assertEquals(i, status.get("release_revision"));
      }
      var removing = new LinkedHashMap<>(pkg);
      removing.remove("workspace");
      var beforeRemoval = db.connection.setSavepoint();
      var clearing =
          object(
              PortableApps.command(
                  store,
                  db,
                  "stage_package",
                  map(
                      "app_id",
                      app,
                      "group_id",
                      group,
                      "design_id",
                      design,
                      "release_revision",
                      2,
                      "revision",
                      get(db, "intake-draft", app).get("revision"),
                      "manifest",
                      removing,
                      "bindings",
                      map(second, group))));
      assertEquals(map(), object(clearing.get("definition")).get("workspace"));
      db.connection.rollback(beforeRemoval);
      db.connection.releaseSavepoint(beforeRemoval);
      var invalidWorkspace = new LinkedHashMap<>(pkg);
      var emptyStages = new LinkedHashMap<>(object(pkg.get("workspace")));
      emptyStages.put("steps", List.of());
      invalidWorkspace.put("workspace", emptyStages);
      assertThrows(
          Failure.class,
          () ->
              PortableApps.command(
                  store,
                  db,
                  "compare_package",
                  map(
                      "app_id",
                      app,
                      "group_id",
                      group,
                      "design_id",
                      design,
                      "manifest",
                      invalidWorkspace,
                      "bindings",
                      map(second, group))));
      var bad = new LinkedHashMap<>(pkg);
      bad.put("fields", List.of(map("name", "memo", "label", "Public", "visibility", "requester")));
      assertThrows(
          Failure.class,
          () ->
              PortableApps.command(
                  store,
                  db,
                  "compare_package",
                  map(
                      "app_id",
                      app,
                      "group_id",
                      group,
                      "design_id",
                      design,
                      "manifest",
                      bad,
                      "bindings",
                      map(second, group))));
      run("apps", "rollback", "app_id", app, "revision", 2);
      assertEquals("Exception 1", definition(db, "intake-release", app).get("title"));
      assertEquals(
          "retained",
          object(definition(db, "intake-case", ticket).get("private_answers")).get("memo"));
      assertEquals(
          "local-envelope",
          db.one("SELECT envelope FROM os_connector WHERE alias='local'").get("envelope"));
      assertEquals(
          group,
          objects(definition(db, "guided-workspace", app).get("steps")).getFirst().get("group"));
      db.close();
      db = null;
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void guidedPagesRejectUnknownFieldsAndInaccessibleActions(boolean pg) throws Exception {
    open(pg);
    String app = id(), stage = id(), ticket = id(), order = id();
    seed("intake-release", app, map("fields", map("memo", map("visibility", "requester"))));
    run(
        "apps",
        "review_stage",
        "stage_id",
        stage,
        "group_id",
        second,
        "title",
        "Legal",
        "note",
        "Internal instructions",
        "revision",
        0);
    for (var columns : List.of(List.of("unknown"), List.of("memo", "memo")))
      assertThrows(
          Failure.class,
          () ->
              run(
                  "apps",
                  "compose_workspace",
                  "app_id",
                  app,
                  "title",
                  "Legal",
                  "fields",
                  columns,
                  "steps",
                  List.of(stage),
                  "revision",
                  0));
    assertThrows(
        Failure.class,
        () ->
            run(
                "apps",
                "compose_workspace",
                "app_id",
                app,
                "title",
                "Legal",
                "fields",
                List.of("memo"),
                "steps",
                List.of(),
                "revision",
                0));
    run(
        "apps",
        "compose_workspace",
        "app_id",
        app,
        "title",
        "Legal",
        "fields",
        List.of("memo"),
        "steps",
        List.of(stage),
        "revision",
        0);
    seed(
        "intake-case",
        ticket,
        map(
            "app",
            app,
            "group",
            team,
            "requester",
            c,
            "answers",
            map("memo", "Request"),
            "private_answers",
            map()));
    as(b);
    assertThrows(
        Failure.class,
        () ->
            run(
                "apps",
                "advance_stage",
                "case_id",
                ticket,
                "decision",
                "complete",
                "note",
                "Too early",
                "revision",
                0));
    String outsider = user("unassigned", "requester");
    as(outsider);
    assertThrows(Failure.class, () -> run("apps", "guide_case", "case_id", ticket));
    as(admin);
    run("apps", "guide_case", "case_id", ticket);
    as(outsider);
    assertThrows(Failure.class, () -> run("apps", "guide_case", "case_id", ticket));
    as(admin);
    seed("purchase", order, map("state", "approved"));
    for (int rev : List.of(1, 2))
      run("apps", "bind_case_order", "case_id", ticket, "order_id", order, "revision", rev);
    assertEquals(
        List.of(order), object(run("apps", "guide_case", "case_id", ticket)).get("orders"));
    as(a);
    assertTrue(((List<?>) run("apps", "guided_queue", "app_id", app)).isEmpty());
    assertThrows(
        Failure.class,
        () ->
            run(
                "apps",
                "advance_stage",
                "case_id",
                ticket,
                "decision",
                "complete",
                "note",
                "Not legal",
                "revision",
                3));
    as(b);
    assertThrows(
        Failure.class,
        () ->
            run(
                "apps",
                "advance_stage",
                "case_id",
                ticket,
                "decision",
                "resume",
                "note",
                "Not waiting",
                "revision",
                3));
    db.update("DELETE FROM os_group_member WHERE group_id=? AND user_id=?", team, b);
    var legal = object(run("apps", "guide_case", "case_id", ticket));
    assertTrue(object(legal.get("fields")).isEmpty());
    as(c);
    assertEquals(
        "",
        object(object(run("apps", "guide_case", "case_id", ticket)).get("stage"))
            .get("instruction"));
    as(b);
    run(
        "apps",
        "advance_stage",
        "case_id",
        ticket,
        "decision",
        "complete",
        "note",
        "Passed",
        "revision",
        3);
    as(c);
    assertEquals("complete", object(run("apps", "guide_case", "case_id", ticket)).get("state"));
    as(admin);
    assertTrue(((List<?>) run("apps", "guided_queue", "app_id", app)).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void portReservationReplayAndOutageRefuseChangesOfIdentity(boolean pg) throws Exception {
    open(pg);
    String account = account(), bundle = id(), site = id(), p = id(), ci = record("cmdb_ci");
    seed(
        "site-bundle",
        bundle,
        map(
            "state",
            "ordered",
            "account",
            account,
            "sites",
            map("Center", map("opportunity", site))));
    seed(
        "opportunity",
        site,
        map("account", account, "order_id", id(), "entitlement", id(), "case_id", id()));
    run(
        "sales",
        "network_port",
        "port_id",
        p,
        "title",
        "City exchange",
        "service_ci",
        ci,
        "capacity",
        4);
    var savepoint = db.connection.setSavepoint();
    run("sales", "port_condition", "port_id", p, "state", "down", "revision", 1);
    assertThrows(
        Failure.class,
        () ->
            run(
                "sales",
                "reserve_circuit",
                "bundle_id",
                bundle,
                "location",
                "Center",
                "quantity",
                1,
                "entries",
                List.of(p)));
    String outage = definition(db, "network-port", p).get("outage").toString();
    db.update(
        "UPDATE os_ci_outage SET begin_ms=? WHERE outage_id=?",
        System.currentTimeMillis() + 60000,
        outage);
    assertThrows(
        Failure.class,
        () -> run("sales", "port_condition", "port_id", p, "state", "up", "revision", 2));
    db.connection.rollback(savepoint);
    db.connection.releaseSavepoint(savepoint);
    for (var path : List.of(List.of(), List.of(p, p)))
      assertThrows(
          Failure.class,
          () ->
              run(
                  "sales",
                  "reserve_circuit",
                  "bundle_id",
                  bundle,
                  "location",
                  "Center",
                  "quantity",
                  1,
                  "entries",
                  path));
    assertThrows(
        Failure.class,
        () -> run("sales", "circuit_status", "bundle_id", bundle, "location", "Other"));
    assertThrows(
        Failure.class,
        () ->
            run(
                "sales",
                "activate_circuit",
                "bundle_id",
                bundle,
                "location",
                "Center",
                "revision",
                0));
    for (int i = 0; i < 2; i++)
      run(
          "sales",
          "reserve_circuit",
          "bundle_id",
          bundle,
          "location",
          "Center",
          "quantity",
          1,
          "entries",
          List.of(p));
    assertThrows(
        Failure.class,
        () ->
            run(
                "sales",
                "reserve_circuit",
                "bundle_id",
                bundle,
                "location",
                "Center",
                "quantity",
                2,
                "entries",
                List.of(p)));
    assertThrows(
        Failure.class,
        () ->
            run(
                "sales",
                "reserve_circuit",
                "bundle_id",
                bundle,
                "location",
                "Center",
                "quantity",
                1,
                "entries",
                List.of(id())));
    run("sales", "port_condition", "port_id", p, "state", "down", "revision", 2);
    run("sales", "port_condition", "port_id", p, "state", "down", "revision", 2);
    assertThrows(
        Failure.class,
        () ->
            run(
                "sales",
                "activate_circuit",
                "bundle_id",
                bundle,
                "location",
                "Center",
                "revision",
                1));
    run("sales", "port_condition", "port_id", p, "state", "up", "revision", 3);
    for (int i = 0; i < 2; i++)
      run("sales", "activate_circuit", "bundle_id", bundle, "location", "Center", "revision", 1);
    assertEquals(
        1, db.count("SELECT COUNT(*) FROM os_ci_outage WHERE record_id=? AND end_ms>0", ci));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void twoConnectionsCompeteForTheFinalPort(boolean pg) throws Exception {
    open(pg);
    String account = account(), bundle = id(), port = id(), left = id(), right = id();
    seed(
        "site-bundle",
        bundle,
        map(
            "state",
            "ordered",
            "account",
            account,
            "sites",
            map("West", map("opportunity", left), "East", map("opportunity", right))));
    for (String op : List.of(left, right))
      seed("opportunity", op, map("account", account, "order_id", id()));
    run(
        "sales",
        "network_port",
        "port_id",
        port,
        "title",
        "Last available port",
        "service_ci",
        record("cmdb_ci"),
        "capacity",
        1);
    db.connection.commit();
    db.close();
    db = null;
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (String site : List.of("West", "East"))
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  try (var tx = store.open()) {
                    PlatformFoundationTest.bind(tx);
                    try {
                      DeliveryWorkspace.command(
                          store,
                          tx,
                          "sales",
                          map(
                              "operation",
                              "reserve_circuit",
                              "bundle_id",
                              bundle,
                              "location",
                              site,
                              "quantity",
                              1,
                              "entries",
                              List.of(port)));
                      tx.connection.commit();
                      return true;
                    } catch (Failure rejected) {
                      tx.connection.rollback();
                      assertTrue(rejected.getMessage().contains("capacity"));
                      return false;
                    }
                  }
                }));
      start.countDown();
      assertNotEquals(futures.getFirst().get(), futures.getLast().get());
    }
    db = store.open();
    assertEquals(1, object(definition(db, "network-port", port).get("circuits")).size());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void staleAuthorityDefersAutomaticLicenseRelease(boolean pg) throws Exception {
    open(pg);
    String resource = "vm", collector = id(), license = id();
    var topology = map("service_roots", map(), "resources", map(), "services", map());
    seed("endpoint-cloud-binding", DeliveryWorkspace.key("unused", resource), map());
    seed(
        "endpoint-cloud-binding",
        ServiceTopology.identity("cloud", resource),
        map("collector", collector));
    var enrollment =
        map(
            "state",
            "enrolled",
            "owner",
            admin,
            "owner_revision",
            AgentPlans.account(db, admin).get("auth_revision"),
            "source",
            "cloud",
            "resource",
            resource,
            "seat",
            map("license", license, "user", b, "revision", 1));
    seed("entitlement", license, map("users", List.of(b)));
    seed("entitlement", license, map("users", List.of(b)));
    seed("endpoint-enrollment", collector, enrollment);
    EndpointCollectors.sourceRetired(db, "cloud", resource, topology);
    assertEquals(
        "retirement_review", definition(db, "endpoint-enrollment", collector).get("state"));
    assertThrows(
        Failure.class,
        () ->
            run(
                "mapping",
                "endpoint_report",
                "collector_id",
                collector,
                "manifest",
                map(),
                "sequence",
                1));
    run(
        "mapping",
        "retire_endpoint",
        "collector_id",
        collector,
        "revision",
        2,
        "note",
        "Asset owner confirmed retirement");
    assertEquals(
        true, definition(db, "endpoint-enrollment", collector).get("seat_requires_reconciliation"));
    assertEquals(List.of(b), definition(db, "entitlement", license).get("users"));
    EndpointCollectors.sourceRetired(db, "cloud", resource, topology);
    for (Object person : List.of(id(), admin)) {
      String other = id();
      seed(
          "endpoint-cloud-binding",
          ServiceTopology.identity("cloud", other),
          map("collector", other));
      seed(
          "endpoint-enrollment",
          other,
          map("state", "enrolled", "owner", person, "owner_revision", -1));
      EndpointCollectors.sourceRetired(db, "cloud", other, topology);
      assertEquals("retirement_review", definition(db, "endpoint-enrollment", other).get("state"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void warrantyPeriodAndClaimBoundaries(boolean pg) throws Exception {
    open(pg);
    String asset =
        Service.create(
                db,
                "alm_asset",
                map(
                    "short_description",
                    "Gateway",
                    "serial_number",
                    "GN-1",
                    "model",
                    "Gateway",
                    "asset_tag",
                    "Asset1"),
                "admin")
            .get("sys_id")
            .toString();
    var bind =
        map(
            "operation",
            "issue_warranty",
            "asset_id",
            asset,
            "user_id",
            c,
            "owner_id",
            a,
            "finance_id",
            b,
            "currency",
            "USD",
            "amount",
            "100",
            "start_on",
            "2024-01-01",
            "finish_on",
            "2023-12-31",
            "revision",
            0);
    assertThrows(Failure.class, () -> WarrantyClaims.run(db, "issue_warranty", bind));
    bind.put("finish_on", "2024-12-31");
    bind.put("finance_id", a);
    assertThrows(Failure.class, () -> WarrantyClaims.run(db, "issue_warranty", bind));
    bind.put("finance_id", b);
    WarrantyClaims.run(db, "issue_warranty", bind);
    bind.put("revision", 1);
    assertThrows(Failure.class, () -> WarrantyClaims.run(db, "issue_warranty", bind));
    bind.put("start_on", "2025-01-01");
    bind.put("finish_on", "2025-12-31");
    WarrantyClaims.run(db, "issue_warranty", bind);
    var claim =
        map(
            "asset_id",
            asset,
            "reference",
            "D-1",
            "effective",
            "2025-09-01",
            "note",
            "Motor failed");
    assertThrows(Failure.class, () -> WarrantyClaims.run(db, "file_claim", claim));
    as(c);
    WarrantyClaims.run(db, "file_claim", claim);
    claim.put("effective", "2025-09-02");
    assertThrows(Failure.class, () -> WarrantyClaims.run(db, "file_claim", claim));
    claim.put("effective", "2025-09-01");
    claim.put("note", "Unrelated");
    assertThrows(Failure.class, () -> WarrantyClaims.run(db, "file_claim", claim));
    claim.put("effective", "2090-01-01");
    assertThrows(Failure.class, () -> WarrantyClaims.run(db, "file_claim", claim));
    as(b);
    assertThrows(Failure.class, () -> WarrantyClaims.run(db, "authorize_repair", claim));
    assertThrows(Failure.class, () -> WarrantyClaims.run(db, "credit_claim", claim));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void confluencePermissionPagesMustBeCompleteAndBoundToProvider(boolean pg) throws Exception {
    open(pg);
    String cloud = java.util.UUID.randomUUID().toString();
    assertThrows(
        Failure.class, () -> ConfluencePolicies.read(store, db, "missing", cloud, "user/current"));
    db.update(
        "INSERT INTO os_connector(alias,origin,envelope,revision)"
            + " VALUES('repo','https://other.example','fixture',1)");
    assertThrows(
        Failure.class, () -> ConfluencePolicies.read(store, db, "repo", cloud, "user/current"));
    db.update("UPDATE os_connector SET origin='https://api.atlassian.com' WHERE alias='repo'");
    var scenario = new java.util.concurrent.atomic.AtomicReference<>("paged");
    try (var http = org.mockito.Mockito.mockStatic(Connectors.class)) {
      http.when(
              () ->
                  Connectors.fetch(
                      org.mockito.ArgumentMatchers.eq(store),
                      org.mockito.ArgumentMatchers.eq(db),
                      org.mockito.ArgumentMatchers.any()))
          .thenAnswer(
              call -> {
                String path = object(call.getArgument(2)).get("path").toString();
                int start = Integer.parseInt(path.substring(path.lastIndexOf('=') + 1));
                int count = scenario.get().equals("overflow") ? 100 : (start == 0 ? 1 : 0);
                var entries = new ArrayList<>();
                for (int i = 0; i < count; i++) entries.add(map("id", "group-" + (start + i)));
                var groups = map("start", start, "limit", 1, "results", entries);
                if (scenario.get().equals("no_limit")) groups.remove("limit");
                if (scenario.get().equals("wrong_offset")) groups.put("start", 5);
                if (scenario.get().equals("missing")) groups.remove("results");
                var response =
                    map(
                        "operation",
                        scenario.get().equals("wrong_operation") ? "update" : "read",
                        "restrictions",
                        map("group", groups));
                return map("content", JSON.writeValueAsBytes(response));
              });
      assertEquals(
          List.of("group-0"), ConfluencePolicies.readGroups(store, db, "repo", cloud, "8"));
      scenario.set("no_limit");
      assertEquals(
          List.of("group-0"), ConfluencePolicies.readGroups(store, db, "repo", cloud, "8"));
      for (var mode : List.of("wrong_offset", "missing", "wrong_operation", "overflow")) {
        scenario.set(mode);
        assertThrows(
            Failure.class, () -> ConfluencePolicies.readGroups(store, db, "repo", cloud, "8"));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void regulatoryPolicyCorrectionAndFailedRetestKeepIndependentEvidence(boolean pg)
      throws Exception {
    open(pg);
    String notice = id(), article = record("kb_knowledge");
    var affected =
        map(
            "area",
            "policy",
            "subject",
            article,
            "requirement",
            "Review notice",
            "owner",
            a,
            "tester",
            b);
    var command =
        map(
            "notice_id",
            notice,
            "title",
            "New policy",
            "reference",
            "2026/10",
            "effective",
            "2028-06-01",
            "entries",
            List.of(),
            "revision",
            0);
    assertThrows(Failure.class, () -> RegulatoryNotices.run(db, "notice_revision", command));
    command.put("entries", List.of(affected, affected));
    assertThrows(Failure.class, () -> RegulatoryNotices.run(db, "notice_revision", command));
    command.put("entries", List.of(affected));
    affected.put("tester", a);
    assertThrows(Failure.class, () -> RegulatoryNotices.run(db, "notice_revision", command));
    affected.put("tester", b);
    RegulatoryNotices.run(db, "notice_revision", command);
    as(admin);
    assertTrue(((List<?>) run("audits", "notice_assignments")).isEmpty());
    as(b);
    assertEquals(1, ((List<?>) run("audits", "notice_assignments")).size());
    String target = DeliveryWorkspace.key("policy", article);
    as(a);
    assertThrows(
        Failure.class,
        () ->
            run(
                "audits",
                "assess_impact",
                "notice_id",
                notice,
                "target",
                "unknown",
                "decision",
                "affected",
                "evidence",
                "Missing",
                "revision",
                1));
    run(
        "audits",
        "assess_impact",
        "notice_id",
        notice,
        "target",
        target,
        "decision",
        "unaffected",
        "evidence",
        "Reason recorded",
        "revision",
        1);
    assertThrows(
        Failure.class,
        () ->
            run(
                "audits",
                "assess_impact",
                "notice_id",
                notice,
                "target",
                target,
                "decision",
                "affected",
                "evidence",
                "Stale",
                "revision",
                2));
    as(b);
    run(
        "audits",
        "test_impact",
        "notice_id",
        notice,
        "target",
        target,
        "decision",
        "fail",
        "evidence",
        "Policy still applies",
        "revision",
        2);
    as(admin);
    var snapshot = object(run("audits", "notice_history", "notice_id", notice, "revision", 3));
    assertEquals("remediation", object(object(snapshot.get("impacts")).get(target)).get("state"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void invalidWorkspaceRequestsAndReleaseCoordinates(boolean pg) throws Exception {
    open(pg);
    String app = id();
    var invalid = map("app_id", app, "commit", "main", "digest", "a".repeat(64));
    assertThrows(Failure.class, () -> GitReleases.run(store, db, "git_status", invalid));
    invalid.put("commit", "b".repeat(40));
    invalid.put("digest", "not-a-digest");
    assertThrows(Failure.class, () -> GitReleases.run(store, db, "git_status", invalid));
    assertThrows(Failure.class, () -> IndustryWorkspaces.command(store, db, "invalid", map()));
    // A historical package may carry a guided view before a local view has been materialized.
    seed("intake-release", app, map("title", "Old"));
    seed("intake-release", app, map("title", "Current"));
    seed(
        "intake-history",
        DeliveryWorkspace.key(app, 1),
        map("title", "Old", "workspace", map("title", "Original view")));
    run("apps", "rollback", "app_id", app, "revision", 2);
    assertEquals("Original view", definition(db, "guided-workspace", app).get("title"));
    String legacy = id();
    seed("intake-release", legacy, map("title", "Original intake"));
    seed("intake-release", legacy, map("title", "Second release"));
    seed("intake-history", DeliveryWorkspace.key(legacy, 1), map("title", "Original intake"));
    run("apps", "rollback", "app_id", legacy, "revision", 2);
    assertEquals(0, get(db, "guided-workspace", legacy).get("revision"));
    assertEquals("Original intake", definition(db, "intake-release", legacy).get("title"));
    String intent = id(), article = id(), offering = id();
    seed("employee-intent", intent, map("article", article, "offering", offering));
    assertThrows(Failure.class, () -> run("assistant", "refresh_confluence", "intent_id", intent));
    seed("external-policy", DeliveryWorkspace.key(article, offering), map("kind", "confluence"));
    assertThrows(Failure.class, () -> run("assistant", "refresh_confluence", "intent_id", intent));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void checkoutAcceptsOnlyHealthyMatchingMeasurementsFromAssignedLocation(boolean pg)
      throws Exception {
    open(pg);
    String objective = id();
    seed(
        "service-objective",
        objective,
        map("owner", admin, "locations", List.of("east", "west"), "interval", 60000));
    run(
        "mapping",
        "transaction_rule",
        "objective_id",
        objective,
        "expected",
        "Success",
        "limit",
        250,
        "revision",
        0);
    seed("probe-identity", DeliveryWorkspace.key(objective, "east"), map("user", a));
    var stamp = Instant.ofEpochMilli(System.currentTimeMillis()).toString();
    var observation =
        map(
            "healthy",
            true,
            "matched",
            true,
            "status",
            200,
            "latency_ms",
            249,
            "expected",
            "Success");
    var sample =
        map(
            "objective_id",
            objective,
            "location",
            "east",
            "observed_at",
            stamp,
            "manifest",
            observation);
    assertThrows(
        Failure.class, () -> TransactionChecks.run(store, db, "transaction_sample", sample));
    as(a);
    for (var field : List.of("healthy", "matched", "expected")) {
      Object original = observation.put(field, 0);
      assertThrows(
          Failure.class, () -> TransactionChecks.run(store, db, "transaction_sample", sample));
      observation.put(field, original);
    }
    for (int status : List.of(0, 500, 200)) {
      var point = db.connection.setSavepoint();
      observation.put("status", status);
      TransactionChecks.run(store, db, "transaction_sample", sample);
      var row = db.one("SELECT detail FROM os_transaction_sample WHERE objective=?", objective);
      assertEquals(status == 200 ? "up" : "down", object(row.get("detail")).get("result"));
      db.connection.rollback(point);
      db.connection.releaseSavepoint(point);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void dealerCreditNeedsIndependentApprovalAndNewDepotReceipt(boolean pg) throws Exception {
    open(pg);
    String asset = record("alm_asset"), ref = "Repair 2", claim = DeliveryWorkspace.key(asset, ref);
    var terms = map("reviewer", a, "finance", b, "dealer", c, "limit", "60.00", "currency", "USD");
    var evidence =
        map(
            "dealer",
            c,
            "terms",
            terms,
            "state",
            "submitted",
            "failure",
            "2025-01-01",
            "symptom",
            "Failed motor",
            "total",
            "90.00",
            "returned_asset",
            asset,
            "authorized_custody_revision",
            2);
    seed("warranty-claim", claim, evidence);
    var message =
        map(
            "asset_id",
            asset,
            "reference",
            ref,
            "effective",
            "2025-01-01",
            "note",
            "Failed motor",
            "amount",
            "50",
            "revision",
            1);
    as(b);
    assertThrows(Failure.class, () -> WarrantyClaims.run(db, "file_claim", message));
    assertThrows(Failure.class, () -> WarrantyClaims.run(db, "adjudicate_claim", message));
    assertThrows(Failure.class, () -> WarrantyClaims.run(db, "submit_repair_cost", message));
    as(c);
    assertThrows(Failure.class, () -> WarrantyClaims.run(db, "submit_repair_cost", message));
    as(a);
    assertThrows(Failure.class, () -> WarrantyClaims.run(db, "authorize_repair", message));
    for (String amount : List.of("61", "91")) {
      message.put("amount", amount);
      assertThrows(Failure.class, () -> WarrantyClaims.run(db, "adjudicate_claim", message));
    }
    message.put("amount", "50");
    for (String operation : List.of("other", "return")) {
      db.update(
          "INSERT INTO os_asset_movement(record_id,revision,state,stockroom_id,assignee_id,detail)"
              + " VALUES(?,?,'in_stock',?,'',?)",
          asset,
          operation.equals("return") ? 2 : 1,
          id(),
          JSON.writeValueAsString(map("operation", operation)));
      assertThrows(Failure.class, () -> WarrantyClaims.run(db, "adjudicate_claim", message));
    }
    evidence.put("state", "filed");
    seed("warranty-claim", claim, evidence);
    assertThrows(Failure.class, () -> WarrantyClaims.run(db, "adjudicate_claim", message));
    assertTrue(list(db, "dealer-credit").isEmpty());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void recentBudgetExcludesOldOutageAndHalfReportedSample(boolean pg) throws Exception {
    open(pg);
    String id = id();
    long end = System.currentTimeMillis() / 60000 * 60000;
    var objective = map("interval", 60000, "target", 9900);
    for (String site : List.of("west", "east"))
      db.update(
          "INSERT INTO os_synthetic_sample(objective,bucket,location,result) VALUES(?,?,?,'down')",
          id,
          end - 7200000L,
          site);
    db.update(
        "INSERT INTO os_synthetic_sample(objective,bucket,location,result) VALUES(?,?,?,'up')",
        id,
        end - 60000L,
        "east");
    var report = ReliabilityObjectives.report(db, id, objective, end);
    assertNull(report.get("one_hour_burn_rate"));
    assertEquals(0L, report.get("one_hour_known_ms"));
    assertEquals(60000L, report.get("consumed_ms"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void inactiveAuthorityCannotRetireManagedMachinesAutomatically(boolean pg) throws Exception {
    open(pg);
    var topology = map("resources", map(), "services", map());
    String collector = id(), source = "assets";
    var binding = map("collector", collector);
    seed("endpoint-cloud-binding", ServiceTopology.identity(source, collector), binding);
    var enrollment =
        map(
            "state",
            "enrolled",
            "owner",
            admin,
            "owner_revision",
            AgentPlans.account(db, admin).get("auth_revision"));
    seed("endpoint-enrollment", collector, enrollment);
    for (String change : List.of("active=0", "role='agent'", "password_reset_required=1")) {
      var point = db.connection.setSavepoint();
      db.update("UPDATE os_user SET " + change + " WHERE user_id=?", admin);
      EndpointCollectors.sourceRetired(db, source, collector, topology);
      assertEquals(
          "retirement_review", definition(db, "endpoint-enrollment", collector).get("state"));
      db.connection.rollback(point);
      db.connection.releaseSavepoint(point);
    }
    EndpointCollectors.sourceRetired(db, source, collector, topology);
    assertEquals("retired", definition(db, "endpoint-enrollment", collector).get("state"));
  }
}
