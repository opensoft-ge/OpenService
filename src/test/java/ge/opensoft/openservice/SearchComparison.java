package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseAcceptance.uid;
import static ge.opensoft.openservice.EnterpriseExpansionAcceptance.*;
import static ge.opensoft.openservice.Values.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;

final class SearchComparison {
  private SearchComparison() {}

  static void run(WorkflowSupport h) throws Exception {
    run(h,false);
  }

  static void run(WorkflowSupport h,boolean expanded) throws Exception {
    h.account("SearchEmployee", "requester");
    String employee = uid(h, "SearchEmployee"),
        group = h.add("sys_user_group").get("sys_id").toString();
    var employees=new ArrayList<String>();employees.add(employee);
    if(expanded)for(String name:List.of("ProcurementEmployee","HREmployee")){
      h.account(name,"requester");employees.add(uid(h,name));
    }
    var intents = new LinkedHashMap<String, String>();
    var offers = new HashMap<String, String>();
    var articles = new HashMap<String, String>();
    var titles =
        Map.of(
            "vpn",
            "VPN access",
            "leave",
            "Parental leave",
            "purchase",
            "Purchase order",
            "private",
            "Salary adjustment");
    // Source documents are fixed separately from the question corpus and its expected actions.
    var documents =
        Map.of(
            "vpn",
            "Remote working requires an encrypted tunnel to the internal network or intranet when"
                + " offsite.",
            "leave",
            "Adoption, maternity, paternity and birth qualify for time off for bonding with a"
                + " newborn.",
            "purchase",
            "Buy equipment and supplies through procurement. Attach a supplier quotation to the"
                + " requisition.",
            "private",
            "Private compensation review, salary adjustment and payroll decisions.");
    if(expanded){
      titles=new LinkedHashMap<>(titles);documents=new LinkedHashMap<>(documents);
      titles.putAll(Map.of("repair","Equipment repair","contract","Contract review","benefits","Dependent enrollment"));
      documents.putAll(Map.of("repair","Send broken equipment to the depot for diagnosis and a replacement unit.",
         "contract","Legal reviews vendor clauses, liability, indemnity and renewal agreements.",
         "benefits","Enroll an eligible dependent or spouse after marriage for insurance coverage."));
    }
    final var sources=documents;
    var remoteAccess = new java.util.concurrent.atomic.AtomicBoolean(true);
    var questions = Files.readAllLines(Path.of(expanded?"src/test/resources/department-search-holdout.tsv":"src/test/resources/employee-search-holdout.tsv"));
    assertEquals(expanded?35:100, new HashSet<>(questions).size());
    try (var provider = new RecordQuestionsAcceptance.Provider(h.store, h.environment)) {
      for (var entry : titles.entrySet()) {
        String label = entry.getKey(), title = entry.getValue();
        String item = ServiceDeliveryAcceptance.offering(h, title), article, intent = id();
        try (var db = h.store.open()) {
          String admin = PlatformFoundationTest.bind(db);
          article =
              KnowledgeSearchAcceptance.article(
                  db, admin, title + " policy", "Policy for " + title);

          db.connection.commit();
        }
        cmd(
            h,
            "admin",
            "assistant",
            "intent",
            "new_id",
            intent,
            "terms",
            title,
            "article_id",
            article,
            "offering_id",
            item,
            "group_id",
            group,
            "revision",
            0);
        intents.put(label, intent);
        offers.put(label, item);
        articles.put(label, article);
        provider.server.createContext(
            "/policy/" + label,
            x -> {
              var bytes =
                  JSON.writeValueAsBytes(
                      map(
                          "title",
                          title,
                          "body",
                          sources.get(label),
                          "version",
                          "v1",
                          "allowed_groups",
                          remoteAccess.get() ? List.of("employees") : List.of()));
              x.sendResponseHeaders(200, bytes.length);
              x.getResponseBody().write(bytes);
              x.close();
            });
      }
      try (var db = h.store.open()) {
        String admin = PlatformFoundationTest.bind(db);
        db.update(
            "INSERT INTO os_record_access(record_id,visibility,owner_id) VALUES(?,?,?) ON"
                + " CONFLICT(record_id) DO UPDATE SET visibility=excluded.visibility",
            articles.get("private"),
            "private",
            admin);
        db.connection.commit();
      }
      cmd(h, "admin", "assistant", "fallback", "group_id", group, "revision", 0);
      var report = map();
      for (String mode : expanded ? List.of("native","external","weighted") : List.of("native", "external")) {
        if (mode.equals("external")) {
          try (var db = h.store.open()) {
            for(String person:employees)db.update("INSERT INTO os_group_member(group_id,user_id) VALUES(?,?)", group, person);
            db.connection.commit();
          }
          for (var entry : intents.entrySet())
            cmd(
                h,
                "admin",
                "assistant",
                "index_external",
                "intent_id",
                entry.getValue(),
                "alias",
                provider.alias,
                "path",
                "/policy/" + entry.getKey(),
                "bindings",
                map("employees", group),
                "revision",
                0);
        }
        String suite = id();
        var cases = new ArrayList<Map<String, Object>>();
        for (String line : questions) {
          String label = line.split("\t")[0];
          cases.add(
              map(
                  "conversation",
                  id(),
                  "employee",
                  employees.get(cases.size()%employees.size()),
                  "expected",
                  label.equals("handoff") ? "handed_off" : "submitted",
                  "offering_id",
                  offers.getOrDefault(label, ""),
                  "article_id",
                  articles.getOrDefault(label, ""),
                  "group_id",
                  group,
                  "forbidden_articles",
                  List.of(articles.get("private"))));
        }
        cmd(h, "admin", "assistant", "holdout", "new_id", suite, "cases", cases);
        var failures = new ArrayList<>();
        try (var db = h.store.open()) {
          db.viewerId = employee;
          db.viewerRole = "requester";
          for (int i = 0; i < questions.size(); i++) {
            var pair = questions.get(i).split("\t", 2);
            db.viewerId=cases.get(i).get("employee").toString();
            String session = cases.get(i).get("conversation").toString();
            var matches =
                object(
                    ServiceSearch.command(
                        h.store,
                        db,
                        mode.equals("weighted")?"weighted_search":"search_services",
                        map("new_id", session, "question", pair[1])));
            var suggestions = objects(matches.get("suggestions"));
            String actual = "handoff";
            if (!suggestions.isEmpty()
                && (suggestions.size() == 1
                    || !suggestions.get(0).get("score").equals(suggestions.get(1).get("score")))) {
              Object chosen = suggestions.getFirst().get("intent_id");
              actual =
                  intents.entrySet().stream()
                      .filter(e -> e.getValue().equals(chosen))
                      .findFirst()
                      .orElseThrow()
                      .getKey();
              ServiceSearch.command(
                  h.store, db, "choose_service", map("new_id", session, "intent_id", chosen));
              EmployeeAssistant.command(
                  h.store,
                  db,
                  "confirm_order",
                  map(
                      "new_id",
                      session,
                      "confirm",
                      true,
                      "manifest",
                      map(),
                      "details",
                      "Employee confirmed"));
            } else
              EmployeeAssistant.command(
                  h.store,
                  db,
                  "handoff",
                  map("new_id", session, "details", "Route to the service desk"));
            if (!actual.equals(pair[0]))
              failures.add(map("question", pair[1], "expected", pair[0], "actual", actual));
          }
          db.connection.commit();
        }
        var scores = cmd(h, "admin", "assistant", "score", "new_id", suite);
        assertEquals(0, scores.get("unauthorized_citations"));
        scores.put("failures", failures);
        report.put(mode, scores);
      }
      String session = id();
      cmd(
          h,
          "SearchEmployee",
          "assistant",
          "search_services",
          "new_id",
          session,
          "question",
          "encrypted tunnel");
      cmd(
          h,
          "SearchEmployee",
          "assistant",
          "choose_service",
          "new_id",
          session,
          "intent_id",
          intents.get("vpn"));
      var before=cmd(h,"SearchEmployee","assistant","resume_search","new_id",session);
      assertEquals(true,before.get("can_confirm"));assertEquals(1,objects(before.get("suggestions")).size());
      String otherSession=id();
      cmd(h,"SearchEmployee","assistant","weighted_search","new_id",otherSession,"question","encrypted tunnel");
      var alternatives=cmd(h,"SearchEmployee","assistant","resume_search","new_id",otherSession);
      assertEquals("clarification_required",alternatives.get("state"));
      assertFalse(objects(alternatives.get("suggestions")).isEmpty());
      remoteAccess.set(false);
      denied(
          h,
          "SearchEmployee",
          "assistant",
          "confirm_order",
          "new_id",
          session,
          "confirm",
          true,
          "manifest",
          map(),
          "details",
          "verify current source permissions");
      var withdrawn=cmd(h,"SearchEmployee","assistant","resume_search","new_id",session);
      assertEquals("handoff_required",withdrawn.get("state"));
      assertEquals(List.of(),withdrawn.get("suggestions"));
      assertEquals(false,withdrawn.get("can_confirm"));
      assertFalse(withdrawn.containsKey("shown_articles"));
      try (var db = h.store.open()) {
        var stored = object(EnterpriseData.get(db, "employee-conversation", session).get("definition"));
        assertTrue(((List<?>) stored.get("shown_articles")).contains(object(objects(before.get("suggestions")).getFirst().get("citation")).get("article_id")));
        assertFalse(stored.containsKey("citation"));
      }
      assertEquals("handoff_required",cmd(h,"SearchEmployee","assistant","resume_search","new_id",session).get("state"));
      assertTrue(objects(cmd(h,"SearchEmployee","assistant","resume_search","new_id",otherSession).get("suggestions")).isEmpty());
      remoteAccess.set(true);
      try (var db = h.store.open()) {
        db.update("DELETE FROM os_group_member WHERE group_id=? AND user_id=?", group, employee);
        db.connection.commit();
      }
      denied(
          h,
          "SearchEmployee",
          "assistant",
          "confirm_order",
          "new_id",
          session,
          "confirm",
          true,
          "manifest",
          map(),
          "details",
          "try again");
      var refreshed =
          cmd(
              h,
              "SearchEmployee",
              "assistant",
              "search_services",
              "new_id",
              id(),
              "question",
              "encrypted tunnel");
      assertTrue(objects(refreshed.get("suggestions")).isEmpty());
      try (var db = h.store.open()) {
        Files.writeString(
            Path.of("target", (expanded?"department-search-":"policy-search-") + (db.postgres ? "postgres" : "sqlite") + ".json"),
            JSON.writeValueAsString(report));
      }
    }
  }
}
