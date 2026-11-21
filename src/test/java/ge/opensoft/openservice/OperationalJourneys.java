package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseAcceptance.uid;
import static ge.opensoft.openservice.EnterpriseExpansionAcceptance.*;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;

final class OperationalJourneys {
  static void governance(WorkflowSupport h) throws Exception {
    h.account("Lawyer", "agent");
    h.account("Assessor", "agent");
    h.account("Vendor", "requester");
    String supplier = "Fixture supplier",
        legal = uid(h, "Lawyer"),
        reviewer = uid(h, "Assessor"),
        contact = uid(h, "Vendor"),
        finance = uid(h, "admin"),
        app = id(),
        assessment = id();
    cmd(
        h,
        "admin",
        "suppliers",
        "request",
        "supplier",
        supplier,
        "security_id",
        reviewer,
        "legal_id",
        legal,
        "finance_id",
        finance);
    cmd(
        h,
        "Assessor",
        "suppliers",
        "assess",
        "supplier",
        supplier,
        "expires_on",
        "2099-01-01",
        "decision",
        "pass",
        "note",
        "Reviewed source evidence",
        "revision",
        1);
    cmd(
        h,
        "Lawyer",
        "suppliers",
        "offer_terms",
        "supplier",
        supplier,
        "user_id",
        contact,
        "currency",
        "USD",
        "effective",
        "2026-01-01",
        "notice",
        "2098-01-01",
        "manifest",
        map("Router", "45"),
        "note",
        "Support obligations",
        "revision",
        0);
    denied(h, "Assessor", "suppliers", "supplier_offer", "supplier", supplier);
    assertTrue(
        cmd(h, "Vendor", "suppliers", "supplier_offer", "supplier", supplier)
            .containsKey("proposal"));
    cmd(
        h,
        "Vendor",
        "suppliers",
        "supplier_response",
        "supplier",
        supplier,
        "decision",
        "accepted",
        "note",
        "Reviewed offer",
        "revision",
        1);
    cmd(
        h,
        "admin",
        "suppliers",
        "sign_terms",
        "supplier",
        supplier,
        "reference",
        "signature-A",
        "revision",
        2);
    cmd(
        h,
        "Lawyer",
        "suppliers",
        "offer_terms",
        "supplier",
        supplier,
        "user_id",
        contact,
        "currency",
        "USD",
        "effective",
        "2027-01-01",
        "notice",
        "2098-01-01",
        "manifest",
        map("Router", "42"),
        "note",
        "New price",
        "revision",
        3);
    cmd(
        h,
        "Vendor",
        "suppliers",
        "supplier_response",
        "supplier",
        supplier,
        "decision",
        "accepted",
        "note",
        "Accepted amendment",
        "revision",
        4);
    cmd(
        h,
        "admin",
        "suppliers",
        "sign_terms",
        "supplier",
        supplier,
        "reference",
        "signature-B",
        "revision",
        5);
    try (var db = h.store.open()) {
      PlatformFoundationTest.bind(db);
      seed(db, "business-app", app, map("title", "Employee directory", "owner", legal));
      for (var entry : Map.of("2026-12-31", "45.00", "2027-01-01", "42.00").entrySet()) {
        var invoice =
            map(
                "supplier",
                supplier,
                "currency",
                "USD",
                "lines",
                map("Router", map("unit_cost", "45")));
        SupplierContracts.match(db, invoice, entry.getKey());
        assertEquals(
            entry.getValue(),
            object(object(invoice.get("lines")).get("Router")).get("contract_unit_cost"));
      }
      db.connection.commit();
    }
    cmd(
        h,
        "Lawyer",
        "suppliers",
        "privacy_screen",
        "assessment_id",
        assessment,
        "supplier",
        supplier,
        "application_id",
        app,
        "user_id",
        contact,
        "categories",
        "Employee records",
        "purpose",
        "Payroll",
        "days",
        30,
        "question",
        "Where is it stored?",
        "risk",
        "sensitive",
        "revision",
        0);
    var view = cmd(h, "Vendor", "suppliers", "privacy_question", "assessment_id", assessment);
    assertFalse(view.containsKey("purpose"));
    cmd(
        h,
        "Vendor",
        "suppliers",
        "privacy_answer",
        "assessment_id",
        assessment,
        "answer",
        "Dedicated region",
        "revision",
        1);
    denied(
        h,
        "Lawyer",
        "suppliers",
        "privacy_decide",
        "assessment_id",
        assessment,
        "decision",
        "approved",
        "owner_id",
        legal,
        "note",
        "self-review",
        "revision",
        2);
    cmd(
        h,
        "Assessor",
        "suppliers",
        "privacy_decide",
        "assessment_id",
        assessment,
        "decision",
        "remediation",
        "owner_id",
        legal,
        "note",
        "Remove legacy copies",
        "revision",
        2);
    cmd(
        h,
        "Lawyer",
        "suppliers",
        "privacy_correct",
        "assessment_id",
        assessment,
        "evidence",
        "Copies retired",
        "revision",
        3);
    cmd(
        h,
        "Assessor",
        "suppliers",
        "privacy_decide",
        "assessment_id",
        assessment,
        "decision",
        "approved",
        "owner_id",
        legal,
        "note",
        "Evidence verified",
        "revision",
        4);
    cmd(
        h,
        "Lawyer",
        "suppliers",
        "privacy_screen",
        "assessment_id",
        assessment,
        "supplier",
        supplier,
        "application_id",
        app,
        "user_id",
        contact,
        "categories",
        "Employee records",
        "purpose",
        "Benefits",
        "days",
        45,
        "question",
        "Which benefits records?",
        "risk",
        "sensitive",
        "revision",
        5);
    assertEquals(
        "Payroll",
        object(
                cmd(
                        h,
                        "Assessor",
                        "suppliers",
                        "privacy_history",
                        "assessment_id",
                        assessment,
                        "revision",
                        5)
                    .get("assessment"))
            .get("purpose"));
  }

  static void portfolio(WorkflowSupport h) throws Exception {
    String goal = id(),
        project = id(),
        epic = id(),
        first = id(),
        second = id(),
        team = h.add("sys_user_group").get("sys_id").toString(),
        other = h.add("sys_user_group").get("sys_id").toString(),
        sprint = id(),
        next = id(),
        release = id(),
        change = h.add("change_request").get("sys_id").toString();
    try (var db = h.store.open()) {
      PlatformFoundationTest.bind(db);
      seed(
          db,
          "funding-goal",
          goal,
          map("allocations", map(project, "1000.00"), "approved_plan", id()));
      seed(
          db,
          "pipeline-release",
          release,
          map("state", "deployed", "change_id", change, "artifact", "release-42"));
      db.connection.commit();
    }
    cmd(
        h,
        "admin",
        "finance",
        "funded_epic",
        "epic_id",
        epic,
        "project_id",
        project,
        "goal_id",
        goal,
        "title",
        "Quarterly delivery");
    cmd(
        h,
        "admin",
        "finance",
        "delivery_story",
        "epic_id",
        epic,
        "story_id",
        first,
        "group_id",
        team,
        "title",
        "Prepare",
        "quantity",
        3,
        "entries",
        List.of());
    cmd(
        h,
        "admin",
        "finance",
        "delivery_story",
        "epic_id",
        epic,
        "story_id",
        second,
        "group_id",
        other,
        "title",
        "Rollout",
        "quantity",
        2,
        "entries",
        List.of(first));
    denied(
        h,
        "admin",
        "finance",
        "commit_sprint",
        "epic_id",
        epic,
        "sprint_id",
        sprint,
        "title",
        "First",
        "entries",
        List.of(first, second),
        "manifest",
        map(team, 3, other, 1));
    cmd(
        h,
        "admin",
        "finance",
        "commit_sprint",
        "epic_id",
        epic,
        "sprint_id",
        sprint,
        "title",
        "First",
        "entries",
        List.of(first, second),
        "manifest",
        map(team, 3, other, 2));
    denied(
        h,
        "admin",
        "finance",
        "ship_story",
        "epic_id",
        epic,
        "sprint_id",
        sprint,
        "story_id",
        second,
        "release_id",
        release,
        "revision",
        2);
    cmd(
        h,
        "admin",
        "finance",
        "ship_story",
        "epic_id",
        epic,
        "sprint_id",
        sprint,
        "story_id",
        first,
        "release_id",
        release,
        "revision",
        2);
    cmd(h, "admin", "finance", "close_sprint", "epic_id", epic, "sprint_id", sprint, "revision", 1);
    try (var db = h.store.open()) {
      String admin = PlatformFoundationTest.bind(db);
      db.update("INSERT INTO os_group_member(group_id,user_id) VALUES(?,?)", other, admin);
      seed(
          db,
          "staff-calendar",
          admin,
          map(
              "week",
              List.of(480, 480, 480, 480, 480, 0, 0),
              "exceptions",
              map("2027-01-04", 120),
              "currency",
              "USD",
              "rate",
              "50.00"));
      db.connection.commit();
    }
    denied(
        h,
        "admin",
        "finance",
        "schedule_sprint",
        "epic_id",
        epic,
        "sprint_id",
        next,
        "title",
        "Second",
        "entries",
        List.of(second),
        "manifest",
        map(other, 100),
        "start_on",
        "2027-01-04",
        "finish_on",
        "2027-01-04");
    var scheduled =
        object(
            cmd(
                    h,
                    "admin",
                    "finance",
                    "schedule_sprint",
                    "epic_id",
                    epic,
                    "sprint_id",
                    next,
                    "title",
                    "Second",
                    "entries",
                    List.of(second),
                    "manifest",
                    map(other, 60),
                    "start_on",
                    "2027-01-04",
                    "finish_on",
                    "2027-01-04")
                .get("definition"));
    assertEquals(2, object(scheduled.get("capacity")).get(other));
    var report = cmd(h, "admin", "finance", "sprint_report", "epic_id", epic, "sprint_id", sprint);
    assertEquals(2, object(report.get("commitment")).size());
    assertEquals(change, object(object(report.get("outcomes")).get(first)).get("change"));
    assertEquals("committed", object(object(report.get("outcomes")).get(second)).get("state"));
  }

  static void desk(WorkflowSupport h) throws Exception {
    var assets = AssetCustodyAcceptance.setup(h);
    AssetCustodyAcceptance.receive(h, assets);
    AssetCustodyAcceptance.issue(h, assets[0]);
    String spare = AssetCustodyAcceptance.create(h, "SPARE-2"),
        visit = id(),
        site = "Main",
        other = "Annex";
    AssetCustodyAcceptance.receive(h, new String[] {spare, assets[1], ""});
    for (String desk : List.of(site, other))
      cmd(
          h,
          "admin",
          "walkup",
          "open_desk",
          "location",
          desk,
          "minutes",
          30,
          "capacity",
          1,
          "revision",
          0);
    String slot =
        Instant.ofEpochMilli((System.currentTimeMillis() / 1800000 + 2) * 1800000).toString();
    cmd(
        h,
        "AssetUser",
        "walkup",
        "join_desk",
        "visit_id",
        id(),
        "location",
        site,
        "mode",
        "appointment",
        "start",
        slot,
        "note",
        "Reserved visit");
    denied(
        h,
        "AssetOther",
        "walkup",
        "join_desk",
        "visit_id",
        id(),
        "location",
        site,
        "mode",
        "appointment",
        "start",
        slot,
        "note",
        "Double booking");
    cmd(
        h,
        "AssetUser",
        "walkup",
        "join_desk",
        "visit_id",
        visit,
        "location",
        site,
        "mode",
        "walk_in",
        "start",
        "",
        "note",
        "Swap laptop");
    cmd(
        h,
        "AssetKeeper",
        "walkup",
        "desk_transfer",
        "visit_id",
        visit,
        "location",
        other,
        "revision",
        1);
    String winner;
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var attempts = new LinkedHashMap<String, java.util.concurrent.Future<Integer>>();
      for (String name : List.of("AssetKeeper", "AssetOther"))
        attempts.put(
            name,
            pool.submit(
                () -> {
                  start.await();
                  return h.json(
                          "POST",
                          EnterpriseAcceptance.api("walkup", "global"),
                          map("operation", "desk_claim", "visit_id", visit, "revision", 2),
                          WorkflowSupport.auth(name))
                      .statusCode();
                }));
      start.countDown();
      int first = attempts.get("AssetKeeper").get(), second = attempts.get("AssetOther").get();
      assertTrue(
          first == 200 && Set.of(400, 409).contains(second)
              || second == 200 && Set.of(400, 409).contains(first));
      winner = first == 200 ? "AssetKeeper" : "AssetOther";
    }
    for (var pair : List.of(List.of(spare, spare), List.of(spare, assets[0])))
      denied(
          h,
          winner,
          "walkup",
          "desk_complete",
          "visit_id",
          visit,
          "old_asset",
          pair.get(0),
          "new_asset",
          pair.get(1),
          "stockroom_id",
          assets[1],
          "revision",
          3);
    for (int i = 0; i < 2; i++)
      cmd(
          h,
          winner,
          "walkup",
          "desk_complete",
          "visit_id",
          visit,
          "old_asset",
          assets[0],
          "new_asset",
          spare,
          "stockroom_id",
          assets[1],
          "revision",
          3);
    assertEquals(
        1, cmd(h, "AssetKeeper", "walkup", "desk_status", "location", other).get("completed"));
    for (var pair : List.of(List.of(spare, assets[0]), List.of(assets[0], assets[0])))
      denied(
          h,
          winner,
          "walkup",
          "desk_complete",
          "visit_id",
          visit,
          "old_asset",
          pair.get(0),
          "new_asset",
          pair.get(1),
          "stockroom_id",
          assets[1],
          "revision",
          3);
    assertEquals(1, object(cmd(h, "admin", "walkup", "desk_compare").get(other)).get("completed"));
    assertEquals(
        "completed",
        object(cmd(h, "AssetUser", "walkup", "my_visit", "visit_id", visit).get("definition"))
            .get("state"));
    denied(h, "AssetOther", "walkup", "my_visit", "visit_id", visit);
    try (var db = h.store.open()) {
      assertEquals("in_stock", AssetCustody.latest(db, assets[0]).get("state"));
      assertEquals(
          RecordAccess.owner(db, "AssetUser"), AssetCustody.latest(db, spare).get("assignee_id"));
    }
  }
}
