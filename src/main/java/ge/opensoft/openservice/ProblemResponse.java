package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.sql.SQLException;
import java.util.*;

/** A delivery identifies one linked incident and one published analysis revision. */
final class ProblemResponse {
  private ProblemResponse() {}

  static Map<String, Object> view(Store.Db db, String problem) throws SQLException {
    var investigation = ProblemInvestigations.problem(db, problem);
    var analysis = object(investigation.get("analysis"));
    boolean notes = RecordCollaboration.allowed(db, "incident", "notes");
    var schema = Service.schema(db, "incident");
    boolean canManage = RecordPolicy.allows(Service.schema(db, "problem"), "update", db.viewerRole);
    boolean canReadTasks =
        FieldAccess.allowed(db, schema, "description") && FieldAccess.allowed(db, schema, "state");
    var guidance =
        map(
            "published",
            analysis.get("published"),
            "revision",
            analysis.get("revision"),
            "workaround",
            Integer.valueOf(1).equals(analysis.get("published")) ? analysis.get("workaround") : "");
    var rows = new ArrayList<Map<String, Object>>();
    var totals = new LinkedHashMap<String, Integer>();
    var incidents =
        notes ? objects(investigation.get("incidents")) : List.<Map<String, Object>>of();
    var ids = incidents.stream().map(row -> row.get("sys_id")).toList();
    String visible = " IN (''" + ",?".repeat(ids.size()) + ")";
    var args = new ArrayList<>(List.of((Object) problem, analysis.get("revision")));
    args.addAll(ids);
    var deliveries = new HashMap<Object, Map<String, Object>>();
    for (var row :
        db.all(
            "SELECT incident_id,sent_at,followup_task,COALESCE(u.name,'Former staff member') AS"
                + " sender FROM os_problem_delivery d LEFT JOIN os_user u ON u.user_id=d.sent_by"
                + " WHERE problem_id=? AND edition=? AND incident_id"
                + visible,
            args.toArray())) deliveries.put(row.get("incident_id"), row);
    var histories = new HashMap<Object, List<Map<String, Object>>>();
    args.remove(1);
    for (var row :
        db.all(
            "SELECT"
                + " recent.incident_id,recent.edition,recent.revision,recent.outcome,recent.feedback_text,recent.actor,recent.recorded,COALESCE(u.name,'Former"
                + " staff member') AS author FROM (SELECT f.*,ROW_NUMBER() OVER(PARTITION BY"
                + " incident_id ORDER BY edition DESC,revision DESC) AS position FROM"
                + " os_problem_feedback f WHERE problem_id=? AND incident_id"
                + visible
                + ") recent LEFT JOIN os_user u ON u.user_id=recent.actor WHERE position<=25 ORDER"
                + " BY recent.incident_id,recent.edition DESC,recent.revision DESC",
            args.toArray()))
      histories.computeIfAbsent(row.get("incident_id"), key -> new ArrayList<>()).add(row);
    for (var incident : incidents) {
      var id = incident.get("sys_id");
      var delivery = deliveries.get(id);
      var history = histories.getOrDefault(id, List.of());
      var current =
          history.stream()
              .filter(f -> f.get("edition").equals(analysis.get("revision")))
              .findFirst()
              .orElse(null);
      String status =
          delivery == null
              ? "unsent"
              : current == null ? "awaiting_feedback" : current.get("outcome").toString();
      totals.merge(status, 1, Integer::sum);
      rows.add(
          map(
              "incident",
              incident,
              "status",
              status,
              "delivered",
              delivery != null,
              "feedback_revision",
              current == null ? 0 : current.get("revision"),
              "history",
              history,
              "delivery",
              delivery == null
                  ? null
                  : map("sender", delivery.get("sender"), "sent_at", delivery.get("sent_at")),
              "followup_task",
              delivery != null && canReadTasks ? delivery.get("followup_task") : null));
    }
    var article =
        db.one(
            "SELECT article_id,edition,draft_revision FROM os_problem_article WHERE problem_id=?",
            problem);
    if (article != null) {
      try {
        var draft = Service.readRecord(db, "kb_knowledge", article.get("article_id").toString());
        article.put(
            "title",
            FieldAccess.serialize(db, draft).getOrDefault("short_description", "Known error"));
      } catch (Failure denied) {
        article = map("restricted", true);
      }
    }
    var groups =
        RecordPolicy.allows(Service.schema(db, "sys_user_group"), "read", db.viewerRole)
            ? Service.page(db, "sys_user_group", Map.of("sysparm_limit", "100"), "").rows()
            : List.of();
    return map(
        "groups",
        groups,
        "problem",
        investigation.get("problem"),
        "analysis",
        guidance,
        "rows",
        rows,
        "totals",
        totals,
        "article",
        article,
        "can_manage",
        canManage,
        "can_read_tasks",
        canReadTasks,
        "editions",
        canManage
            ? db.all(
                "SELECT edition,workaround,recorded FROM os_problem_guidance WHERE problem_id=?"
                    + " ORDER BY edition DESC LIMIT 25",
                problem)
            : List.of(),
        "can_feedback",
        notes && RecordPolicy.allows(schema, "update", db.viewerRole));
  }

  private static Map<String, Object> latest(
      Store.Db db, String problem, Object incident, Object edition) throws SQLException {
    return db.one(
        "SELECT revision,outcome,feedback_text,actor FROM os_problem_feedback WHERE problem_id=?"
            + " AND incident_id=? AND edition=? ORDER BY revision DESC LIMIT 1",
        problem,
        incident,
        edition);
  }

  static void act(Store.Db db, String problem, Map<String, Object> body) throws SQLException {
    // Link changes and edits to problem analysis take the same transaction lock.
    if (db.postgres) db.all("SELECT pg_advisory_xact_lock(18439,4)");
    var record = ProblemInvestigations.record(db, "problem", problem);
    var analysis = ProblemInvestigations.analysis(db, problem);
    ProblemInvestigations.revision(body.get("revision"), analysis.get("revision"));
    require(
        Integer.valueOf(1).equals(analysis.get("published")), "Publish a current workaround first");
    String op = WorkJobs.text(body.get("operation"), 30);
    if (op.equals("followup")) {
      require(
          body.keySet()
              .equals(
                  Set.of("operation", "revision", "incident_id", "feedback_revision", "group_id")),
          "Choose the incident, outcome revision and team");
      followup(db, problem, analysis.get("revision"), body);
      return;
    }
    if (op.equals("feedback")) {
      require(
          body.keySet()
              .equals(
                  Set.of(
                      "operation",
                      "revision",
                      "incident_id",
                      "feedback_revision",
                      "outcome",
                      "detail")),
          "Provide the incident and feedback fields");
      feedback(db, problem, analysis.get("revision"), body);
      return;
    }
    RecordPolicy.require(Service.schema(db, "problem"), "update", db.viewerRole);
    require(
        body.keySet().equals(Set.of("operation", "revision")),
        "Provide the operation and analysis revision");
    switch (op) {
      case "communicate" -> communicate(db, problem, analysis);
      case "draft", "refresh" -> knownError(db, record, analysis, op);
      default -> throw new Failure(400, "Unknown problem response operation");
    }
  }

  private static void communicate(Store.Db db, String problem, Map<String, Object> analysis)
      throws SQLException {
    var edition = analysis.get("revision");
    var schema = Service.schema(db, "incident");
    RecordPolicy.require(schema, "read", db.viewerRole);
    RecordPolicy.require(schema, "update", db.viewerRole);
    requireNotes(db);
    var incidents = objects(ProblemInvestigations.problem(db, problem).get("incidents"));
    String actor = Service.account(db, db.viewerId).get("name").toString();
    if (!incidents.isEmpty())
      db.update(
          "INSERT INTO os_problem_guidance(problem_id,edition,workaround,recorded) VALUES(?,?,?,?)"
              + " ON CONFLICT(problem_id,edition) DO NOTHING",
          problem,
          edition,
          analysis.get("workaround"),
          now());
    for (var incident : incidents) {
      String id = incident.get("sys_id").toString();
      if (db.update(
              "INSERT INTO os_problem_delivery(problem_id,incident_id,edition,sent_at,sent_by)"
                  + " VALUES(?,?,?,?,?) ON CONFLICT(problem_id,incident_id,edition) DO NOTHING",
              problem,
              id,
              edition,
              now(),
              db.viewerId)
          == 0) continue;
      var target = Service.readRecord(db, "incident", id);
      // Followers can lose access to the source problem independently of this incident.
      Service.audit(
          db,
          target,
          actor,
          "note",
          map(
              "body",
              "A workaround is available. Open the related problem investigation for the current"
                  + " guidance."));
    }
  }

  private static void requireNotes(Store.Db db) throws SQLException {
    if (!RecordCollaboration.allowed(db, "incident", "notes"))
      throw new Failure(403, "Incident work note access required");
  }

  private static void feedback(
      Store.Db db, String problem, Object edition, Map<String, Object> body) throws SQLException {
    String id = JourneyForms.id(body, "incident_id");
    var incident = Service.readRecord(db, "incident", id);
    RecordPolicy.require(Service.schema(db, "incident"), "update", db.viewerRole);
    requireNotes(db);
    require(
        db.count(
                "SELECT COUNT(*) FROM os_problem_delivery d JOIN os_problem_link l ON"
                    + " l.incident_id=d.incident_id AND l.problem_id=d.problem_id WHERE"
                    + " d.problem_id=? AND d.incident_id=? AND d.edition=?",
                problem,
                id,
                edition)
            == 1,
        "Deliver this workaround to the linked incident first");
    String outcome = DeliveryWorkspace.choice(body, "outcome", "effective", "ineffective");
    String detail = WorkJobs.text(body.get("detail"), 1000);
    int expected = EnterpriseData.integer(body.get("feedback_revision"), 0, Integer.MAX_VALUE - 1);
    var current = latest(db, problem, id, edition);
    int revision = current == null ? 0 : ((Number) current.get("revision")).intValue();
    if (revision == expected + 1
        && current.get("actor").equals(db.viewerId)
        && current.get("outcome").equals(outcome)
        && current.get("feedback_text").equals(detail)) return;
    ProblemInvestigations.revision(expected, revision);
    db.update(
        "INSERT INTO"
            + " os_problem_feedback(problem_id,incident_id,edition,revision,outcome,feedback_text,actor,recorded)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        problem,
        id,
        edition,
        revision + 1,
        outcome,
        detail,
        db.viewerId,
        now());
    Service.audit(
        db,
        incident,
        Service.account(db, db.viewerId).get("name").toString(),
        "note",
        map(
            "body",
            "The service desk recorded a workaround outcome in the related problem"
                + " investigation."));
  }

  private static void followup(
      Store.Db db, String problem, Object edition, Map<String, Object> body) throws SQLException {
    String id = JourneyForms.id(body, "incident_id"), group = JourneyForms.id(body, "group_id");
    var incident = WorkTasks.record(db, "incident", id);
    RecordPolicy.require(Service.schema(db, "incident"), "update", db.viewerRole);
    requireNotes(db);
    Service.readRecord(db, "sys_user_group", group);
    var delivered =
        db.one(
            "SELECT d.followup_task,d.followup_group FROM os_problem_delivery d JOIN"
                + " os_problem_link l ON l.incident_id=d.incident_id AND l.problem_id=d.problem_id"
                + " WHERE d.problem_id=? AND d.incident_id=? AND d.edition=?",
            problem,
            id,
            edition);
    require(delivered != null, "Deliver this workaround to the linked incident first");
    if (delivered.get("followup_task") != null) {
      require(
          group.equals(delivered.get("followup_group")),
          "A follow-up has already been assigned to another team");
      return;
    }
    var result = latest(db, problem, id, edition);
    require(
        result != null && "ineffective".equals(result.get("outcome")),
        "Record an ineffective workaround before assigning follow-up");
    ProblemInvestigations.revision(body.get("feedback_revision"), result.get("revision"));
    var work =
        WorkTasks.command(
            db,
            "incident",
            id,
            map(
                "operation",
                "create",
                "revision",
                WorkTasks.plan(db, id).get("revision"),
                "record_revision",
                incident.get("revision"),
                "summary",
                "Investigate unsuccessful workaround",
                "details",
                "Review the current workaround and outcome in the related problem investigation.",
                "group_id",
                group,
                "required",
                true,
                "predecessors",
                List.of()));
    var task = objects(work.get("tasks")).getLast();
    db.update(
        "UPDATE os_problem_delivery SET followup_task=?,followup_group=? WHERE problem_id=? AND"
            + " incident_id=? AND edition=?",
        task.get("task_id"),
        group,
        problem,
        id,
        edition);
  }

  private static void knownError(
      Store.Db db, Map<String, Object> record, Map<String, Object> analysis, String operation)
      throws SQLException {
    String problem = record.get("sys_id").toString();
    var link =
        db.one(
            "SELECT article_id,edition,draft_revision FROM os_problem_article WHERE problem_id=?",
            problem);
    String actor = Service.account(db, db.viewerId).get("name").toString();
    Map<String, Object> article;
    if (link == null) {
      require(operation.equals("draft"), "Create a known error draft first");
      article =
          Service.create(db, "kb_knowledge", map("short_description", "Known error draft"), actor);
      db.update(
          "INSERT INTO os_record_access(record_id,visibility,owner_id,group_id)"
              + " VALUES(?,'private',?,'')",
          article.get("sys_id"),
          db.viewerId);
    } else {
      String id = link.get("article_id").toString();
      article = Service.record(db, "kb_knowledge", id);
      if (operation.equals("draft") || link.get("edition").equals(analysis.get("revision"))) return;
      var access = RecordAccess.status(db, id);
      require(
          "private".equals(access.get("visibility"))
              && db.count("SELECT COUNT(*) FROM os_publication WHERE article_id=?", id) == 0,
          "Refresh only a private draft that has never been published");
      ProblemInvestigations.revision(link.get("draft_revision"), article.get("revision"));
    }
    var title = FieldAccess.serialize(db, record).getOrDefault("short_description", "Problem");
    var content =
        map(
            "short_description",
            "Known error: " + title,
            "text",
            "Root cause\n"
                + analysis.get("cause")
                + "\n\nWorkaround\n"
                + analysis.get("workaround"));
    String id = article.get("sys_id").toString();
    Service.update(
        db,
        "kb_knowledge",
        article,
        content,
        actor,
        db.viewerRole,
        article.get("revision").toString());
    db.update(
        "INSERT INTO os_problem_article(problem_id,article_id,edition,draft_revision)"
            + " VALUES(?,?,?,?) ON CONFLICT(problem_id) DO UPDATE SET"
            + " edition=excluded.edition,draft_revision=excluded.draft_revision",
        problem,
        id,
        analysis.get("revision"),
        article.get("revision"));
    Service.audit(
        db, record, actor, "problem.known_error_drafted", map("edition", analysis.get("revision")));
  }
}
