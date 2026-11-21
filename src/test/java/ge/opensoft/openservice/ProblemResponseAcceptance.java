package ge.opensoft.openservice;

import static ge.opensoft.openservice.ProblemInvestigationsAcceptance.*;
import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.WorkflowSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;

final class ProblemResponseAcceptance {
  private final WorkflowSupport h;
  private final String problem, api, incident, hidden;

  private ProblemResponseAcceptance(WorkflowSupport h) throws Exception {
    this.h = h;
    problem = h.add("problem").get("sys_id").toString();
    api = "/api/openservice/problem/" + problem + "/response";
    incident = h.add("incident").get("sys_id").toString();
    hidden = h.add("incident").get("sys_id").toString();
    h.account("DeskAgent", "agent");
    h.account("DeskOther", "agent");
    h.account("DeskReader", "reader");
    attach(incident);
    attach(hidden);
    access(
        h, "incident", hidden, "private", KnowledgeReviewsAcceptance.accountId(h.store, "admin"));
  }

  void attach(String id) throws Exception {
    var data = link(h, id, "link");
    data.put("problem_id", problem);
    result(h.json("POST", api("incident", id), data, auth("admin")));
  }

  Map<String, Object> send(String user, int status, Object... fields) throws Exception {
    var res = h.json("POST", api, map(fields), auth(user));
    assertEquals(status, res.statusCode(), html(res));
    return status == 200 ? object(parsed(res).get("result")) : map();
  }

  Map<String, Object> show(String user) throws Exception {
    return result(h.get(api, auth(user)));
  }

  static Map<String, Object> row(Map<String, Object> view, String id) {
    return objects(view.get("rows")).stream()
        .filter(v -> id.equals(object(v.get("incident")).get("sys_id")))
        .findFirst()
        .orElseThrow();
  }

  void analysis(boolean publish, String text) throws Exception {
    var b = notes(h, problem, publish);
    b.put("workaround", text);
    result(h.json("POST", api("problem", problem), b, auth("admin")));
  }

  Map<String, Object> feedback(
      String user, int status, int edition, int expected, String outcome, String note)
      throws Exception {
    return send(
        user,
        status,
        "operation",
        "feedback",
        "revision",
        edition,
        "incident_id",
        incident,
        "feedback_revision",
        expected,
        "outcome",
        outcome,
        "detail",
        note);
  }

  Map<String, Object> follow(int status, int expected, String group) throws Exception {
    return send(
        "DeskAgent",
        status,
        "operation",
        "followup",
        "revision",
        1,
        "incident_id",
        incident,
        "feedback_revision",
        expected,
        "group_id",
        group);
  }

  static void delivery(WorkflowSupport h) throws Exception {
    new ProblemResponseAcceptance(h).desk();
  }

  void desk() throws Exception {
    assertEquals(403, h.get(api, auth("DeskReader")).statusCode());
    assertEquals(401, h.get(api, null).statusCode());
    assertEquals(405, h.json("PUT", api, map(), auth("admin")).statusCode());
    assertEquals(400, h.get(api + "?extra=x", auth("admin")).statusCode());
    assertEquals(1, objects(show("DeskAgent").get("rows")).size());
    assertEquals("unsent", row(show("admin"), incident).get("status"));
    send("admin", 400, "operation", "communicate", "revision", 0);
    analysis(true, "Private root-cause guidance");
    send("admin", 400, "operation", "unknown", "revision", 1);
    send("admin", 400, "operation", "communicate", "revision", 1, "extra", true);
    send("admin", 400, "operation", "feedback", "revision", 1);
    send("admin", 400, "operation", "followup", "revision", 1);
    send("admin", 409, "operation", "communicate", "revision", 0);
    feedback("DeskAgent", 400, 1, 0, "effective", "Not sent yet");
    String group = WorkTasksAcceptance.setup(h);
    follow(400, 0, group);
    h.account("DeskFollower", "agent");
    result(
        h.json(
            "POST",
            RecordCollaborationAcceptance.route(incident),
            RecordCollaborationAcceptance.subscription(0, List.of("notes")),
            auth("DeskFollower")));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(() -> send("DeskAgent", 200, "operation", "communicate", "revision", 1));
      var second =
          pool.submit(() -> send("DeskOther", 200, "operation", "communicate", "revision", 1));
      for (var f : List.of(first, second))
        assertEquals("awaiting_feedback", row(f.get(), incident).get("status"));
    }
    assertEquals(1, RecordCollaborationAcceptance.notices(h, "DeskFollower").size());
    assertEquals("unsent", row(show("admin"), hidden).get("status"));
    try (var db = h.store.open()) {
      assertEquals(
          1,
          db.count("SELECT COUNT(*) FROM os_event WHERE record_id=? AND action='note'", incident));
      assertFalse(
          db.one("SELECT detail FROM os_event WHERE record_id=? AND action='note'", incident)
              .toString()
              .contains("Private root-cause"));
    }
    follow(400, 0, group);
    String late = h.add("incident").get("sys_id").toString();
    attach(late);
    assertEquals("unsent", row(show("DeskAgent"), late).get("status"));
    send("admin", 200, "operation", "communicate", "revision", 1);
    assertEquals("awaiting_feedback", row(show("DeskAgent"), late).get("status"));
    feedback("DeskAgent", 200, 1, 0, "effective", "Initially recovered");
    follow(400, 1, group);
    feedback("DeskAgent", 200, 1, 0, "effective", "Initially recovered");
    feedback("DeskOther", 409, 1, 0, "effective", "Initially recovered");
    feedback("DeskAgent", 409, 1, 0, "ineffective", "Initially recovered");
    feedback("DeskAgent", 409, 1, 0, "effective", "Different evidence");
    feedback("DeskAgent", 200, 1, 1, "ineffective", "Failed again");
    follow(409, 1, group);
    follow(409, 2, group);
    WorkTasksAcceptance.configure(h, incident);
    String task;
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> follow(200, 2, group));
      var second = pool.submit(() -> follow(200, 2, group));
      task = row(first.get(), incident).get("followup_task").toString();
      assertEquals(task, row(second.get(), incident).get("followup_task"));
    }
    assertEquals(task, row(follow(200, 2, group), incident).get("followup_task"));
    String otherGroup = h.add("sys_user_group").get("sys_id").toString();
    follow(400, 2, otherGroup);
    assertEquals(1, objects(WorkTasksAcceptance.page(h, incident).get("tasks")).size());
    WorkTasksAcceptance.state(h, incident, "2", 200);
    WorkTasksAcceptance.state(h, incident, "6", 409);
    WorkTasksAcceptance.act(h, incident, task, "claim", "TaskOne", "");
    WorkTasksAcceptance.act(h, incident, task, "complete", "TaskOne", "Recovered after repair");
    assertEquals(
        "complete",
        WorkTasksAcceptance.task(WorkTasksAcceptance.page(h, incident), task).get("state"));
    WorkTasksAcceptance.state(h, incident, "6", 200);
    Map<String, Object> priorIncident;
    try (var db = h.store.open()) {
      priorIncident = Service.schema(db, "incident");
    }
    for (String field : List.of("description", "state")) {
      var hide = new LinkedHashMap<>(priorIncident);
      hide.put("field_rules", map(field, map("type", "string", "read_roles", List.of("admin"))));
      ReferencePickerAcceptance.writeSchema(h, "incident", hide);
      try {
        var desk = show("DeskAgent");
        assertEquals(false, desk.get("can_read_tasks"));
        assertNull(row(desk, incident).get("followup_task"));
        assertFalse(object(row(desk, incident).get("delivery")).containsKey("followup_task"));
      } finally {
        ReferencePickerAcceptance.writeSchema(h, "incident", priorIncident);
      }
    }
    analysis(false, "Draft guidance");
    assertEquals("", object(show("DeskAgent").get("analysis")).get("workaround"));
    feedback("DeskAgent", 409, 1, 2, "effective", "Old workaround");
    send("admin", 400, "operation", "communicate", "revision", 2);
    analysis(true, "New published guidance");
    send("admin", 200, "operation", "communicate", "revision", 3);
    new Store(h.environment);
    var editions = objects(show("admin").get("editions"));
    assertEquals(List.of(3, 1), editions.stream().map(e -> e.get("edition")).toList());
    assertEquals("Private root-cause guidance", editions.getLast().get("workaround"));
    Map<String, Object> src;
    try (var db = h.store.open()) {
      src = Service.schema(db, "problem");
    }
    var managed = new LinkedHashMap<>(src);
    managed.put("record_roles", map("update", List.of("admin")));
    ReferencePickerAcceptance.writeSchema(h, "problem", managed);
    try {
      var readonly = show("DeskAgent");
      assertTrue(objects(readonly.get("editions")).isEmpty());
      assertFalse(object(readonly.get("analysis")).containsKey("cause"));
      send("DeskAgent", 403, "operation", "communicate", "revision", 3);
    } finally {
      ReferencePickerAcceptance.writeSchema(h, "problem", src);
    }
    var changed = row(show("admin"), incident);
    assertEquals(2, objects(changed.get("history")).size());
    assertEquals(0, changed.get("feedback_revision"));
    assertEquals("awaiting_feedback", changed.get("status"));
    var un = link(h, incident, "unlink");
    result(h.json("POST", api("incident", incident), un, auth("admin")));
    feedback("DeskAgent", 400, 3, 0, "effective", "Removed association");
    assertFalse(show("DeskAgent").toString().contains("Failed again"));
    Map<String, Object> original;
    try (var db = h.store.open()) {
      original = Service.schema(db, "incident");
    }
    var restricted = new LinkedHashMap<>(original);
    restricted.put(
        "field_rules", map("work_notes", map("type", "string", "read_roles", List.of("admin"))));
    ReferencePickerAcceptance.writeSchema(h, "incident", restricted);
    try {
      assertTrue(objects(show("DeskAgent").get("rows")).isEmpty());
      send("DeskAgent", 403, "operation", "communicate", "revision", 3);
    } finally {
      ReferencePickerAcceptance.writeSchema(h, "incident", original);
    }
    restricted = new LinkedHashMap<>(original);
    restricted.put("record_roles", map("update", List.of("admin")));
    ReferencePickerAcceptance.writeSchema(h, "incident", restricted);
    try {
      assertEquals(false, show("DeskAgent").get("can_feedback"));
    } finally {
      ReferencePickerAcceptance.writeSchema(h, "incident", original);
    }
    access(h, "incident", late, "private", KnowledgeReviewsAcceptance.accountId(h.store, "admin"));
    assertTrue(
        objects(send("DeskAgent", 200, "operation", "communicate", "revision", 3).get("rows"))
            .isEmpty());
    try (var db = h.store.open()) {
      original = Service.schema(db, "sys_user_group");
    }
    restricted = new LinkedHashMap<>(original);
    restricted.put("record_roles", map("read", List.of("admin")));
    ReferencePickerAcceptance.writeSchema(h, "sys_user_group", restricted);
    try {
      assertTrue(objects(show("DeskAgent").get("groups")).isEmpty());
    } finally {
      ReferencePickerAcceptance.writeSchema(h, "sys_user_group", original);
    }
  }

  static void knowledge(WorkflowSupport h) throws Exception {
    new ProblemResponseAcceptance(h).article();
  }

  void article() throws Exception {
    analysis(true, "Reset the affected component");
    send("DeskAgent", 400, "operation", "refresh", "revision", 1);
    var link = object(send("DeskAgent", 200, "operation", "draft", "revision", 1).get("article"));
    String id = link.get("article_id").toString();
    assertEquals(
        id,
        object(send("DeskAgent", 200, "operation", "draft", "revision", 1).get("article"))
            .get("article_id"));
    send("DeskAgent", 200, "operation", "refresh", "revision", 1);
    assertEquals(map("restricted", true), show("DeskOther").get("article"));
    assertEquals(404, h.get("/api/openservice/knowledge/" + id, auth("DeskReader")).statusCode());
    assertFalse(show("DeskOther").toString().contains(id));
    analysis(true, "Replacement instructions");
    var refreshed =
        object(send("DeskAgent", 200, "operation", "refresh", "revision", 2).get("article"));
    assertEquals(2, refreshed.get("edition"));
    assertEquals(id, refreshed.get("article_id"));
    var edit =
        h.json(
            "PATCH",
            "/api/now/table/kb_knowledge/" + id,
            map("text", "Reviewed independent wording"),
            auth("admin"));
    assertEquals(200, edit.statusCode(), html(edit));
    analysis(true, "Another revision");
    send("DeskAgent", 409, "operation", "refresh", "revision", 3);
    access(h, "kb_knowledge", id, "workspace", "");
    send("admin", 400, "operation", "refresh", "revision", 3);
    h.account("DeskEmployee", "requester");
    String search = "/api/openservice/knowledge?q=Reviewed%20independent%20wording";
    assertEquals(0, parsed(h.get(search, auth("DeskEmployee"))).get("total"));
    h.account("KnowledgeReviewer", "admin");
    KnowledgeReviewsAcceptance.act(
        h,
        id,
        "admin",
        "configure",
        map("reviewer_id", KnowledgeReviewsAcceptance.accountId(h.store, "KnowledgeReviewer")));
    assertEquals(409, KnowledgeReviewsAcceptance.direct(h, id, "admin", "publish"));
    KnowledgeReviewsAcceptance.approve(h, id, "publish");
    KnowledgeReviewsAcceptance.act(h, id, "admin", "apply", map());
    assertEquals(
        "Reviewed independent wording",
        object(result(h.get("/api/openservice/knowledge/" + id, auth("DeskReader"))).get("article"))
            .get("content"));
    var found = parsed(h.get(search, auth("DeskEmployee")));
    assertEquals(1, found.get("total"));
    assertEquals(id, objects(found.get("result")).getFirst().get("article_id"));
    var rating =
        h.json(
            "POST",
            "/api/openservice/knowledge/" + id + "/feedback",
            map("version", 1, "helpful", true, "details", "Restored access without a new incident"),
            auth("DeskEmployee"));
    assertEquals(200, rating.statusCode(), html(rating));
    var review = result(h.get("/api/openservice/knowledge-feedback/" + id, auth("admin")));
    assertTrue(review.toString().contains("Restored access without a new incident"));
    access(
        h,
        "kb_knowledge",
        id,
        "private",
        KnowledgeReviewsAcceptance.accountId(h.store, "DeskAgent"));
    assertEquals(0, parsed(h.get(search, auth("DeskEmployee"))).get("total"));
    send("admin", 400, "operation", "refresh", "revision", 3);
    assertEquals(
        204,
        h.json("DELETE", "/api/now/table/kb_knowledge/" + id, map(), auth("admin")).statusCode());
    assertNull(show("admin").get("article"));
  }
}
