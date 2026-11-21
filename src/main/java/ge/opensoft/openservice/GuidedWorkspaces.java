package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class GuidedWorkspaces {
  private GuidedWorkspaces() {}

  static Object run(Store.Db db, String op, Map<String, Object> b) throws Exception {
    if (op.equals("review_stage")) {
      String group = id(b, "group_id");
      CatalogueRouting.group(db, group);
      return put(
          db,
          "guided-stage",
          id(b, "stage_id"),
          b.get("revision"),
          map(
              "title",
              text(b, "title", 160),
              "group",
              group,
              "instruction",
              text(b, "note", 1200)));
    }
    if (op.equals("compose_workspace")) {
      String app = id(b, "app_id");
      var release = definition(db, "intake-release", app);
      var stages = new ArrayList<>();
      for (Object raw : batch(b.get("steps"), 24)) {
        String stage = EnterpriseData.id(raw);
        stages.add(getStage(db, stage));
      }
      require(!stages.isEmpty(), "Choose review stages");
      var components = new ArrayList<>();
      for (Object raw : batch(b.get("fields"), 32)) {
        String name = WorkJobs.text(raw, 64);
        require(
            object(release.get("fields")).containsKey(name) && !components.contains(name),
            "Choose distinct app fields");
        components.add(name);
      }
      return put(
          db,
          "guided-workspace",
          app,
          b.get("revision"),
          map("steps", stages, "fields", components, "title", text(b, "title", 160)));
    }
    if (op.equals("guided_queue")) {
      String app = id(b, "app_id");
      var queue = new ArrayList<>();
      for (var row : list(db, "guided-case")) {
        var data = object(row.get("definition"));
        if (!app.equals(data.get("app"))) continue;
        var stage = current(data);
        if (!stage.isEmpty() && permitted(db, stage))
          queue.add(
              map(
                  "case_id",
                  row.get("subject"),
                  "stage",
                  stage,
                  "revision",
                  row.get("revision"),
                  "status",
                  data.get("state")));
      }
      return queue;
    }
    String id = id(b, "case_id");
    var intake = definition(db, "intake-case", id);
    String app = intake.get("app").toString();
    var row = get(db, "guided-case", id);
    var data = object(row.get("definition"));
    if (data.isEmpty()) {
      require(
          op.equals("guide_case")
              && (db.viewerId.equals(intake.get("requester")) || IntakeApps.team(db, intake)),
          "Select an accessible case");
      var view = definition(db, "guided-workspace", app);
      data =
          map(
              "app",
              app,
              "steps",
              view.get("steps"),
              "fields",
              view.get("fields"),
              "position",
              0,
              "state",
              "review",
              "history",
              List.of());
      row = put(db, "guided-case", id, 0, data);
    }
    if (op.equals("bind_case_order")) {
      String order = id(b, "order_id");
      definition(db, "purchase", order);
      var linked = new ArrayList<Object>((List<?>) data.getOrDefault("orders", List.of()));
      if (!linked.contains(order)) linked.add(order);
      data.put("orders", linked);
      return put(db, "guided-case", id, b.get("revision"), data).get("revision");
    }
    var stage = current(data);
    boolean reviewer = !stage.isEmpty() && permitted(db, stage);
    if (op.equals("advance_stage")) {
      require(reviewer, "Current review team required");
      String action = choice(b, "decision", "complete", "wait", "resume");
      require(
          !("waiting".equals(data.get("state")) ^ action.equals("resume")),
          "Resume a waiting stage first");
      var events = new ArrayList<>(objects(data.get("history")));
      events.add(
          map(
              "stage",
              data.get("position"),
              "action",
              action,
              "note",
              text(b, "note", 1200),
              "by",
              db.viewerId,
              "at",
              now()));
      data.put("history", events);
      if (action.equals("complete"))
        data.put("position", ((Number) data.get("position")).intValue() + 1);
      data.put(
          "state",
          current(data).isEmpty() ? "complete" : action.equals("wait") ? "waiting" : "review");
      return put(db, "guided-case", id, b.get("revision"), data).get("revision");
    }
    require(
        reviewer || IntakeApps.team(db, intake) || db.viewerId.equals(intake.get("requester")),
        "Workspace unavailable");
    var fields = map();
    // The underlying case projection controls confidential fields and referenced record visibility.
    if (IntakeApps.team(db, intake) || db.viewerId.equals(intake.get("requester"))) {
      var visible = object(object(IntakeApps.command(db, "case", map("case_id", id))).get("case"));
      for (String source : List.of("answers", "private_answers")) {
        var answers = object(visible.getOrDefault(source, map()));
        for (Object name : (List<?>) data.get("fields"))
          if (answers.containsKey(name)) fields.put(name.toString(), answers.get(name));
      }
    }
    if (!reviewer && !IntakeApps.team(db, intake) && !stage.isEmpty())
      stage = map("title", stage.get("title"), "group", stage.get("group"), "instruction", "");
    return map(
        "revision",
        row.get("revision"),
        "state",
        data.get("state"),
        "stage",
        stage,
        "can_advance",
        reviewer,
        "fields",
        fields,
        "orders",
        db.viewerRole.equals("admin") ? data.getOrDefault("orders", List.of()) : List.of(),
        "history",
        reviewer || IntakeApps.team(db, intake) ? data.get("history") : List.of());
  }

  static Map<String, Object> getStage(Store.Db db, String stage) throws Exception {
    var result = new LinkedHashMap<>(definition(db, "guided-stage", stage));
    result.put("id", stage);
    result.put("revision", get(db, "guided-stage", stage).get("revision"));
    return result;
  }

  static Map<String, Object> current(Map<String, Object> data) {
    var steps = objects(data.get("steps"));
    int pos = ((Number) data.get("position")).intValue();
    return pos < steps.size() ? steps.get(pos) : map();
  }

  static boolean permitted(Store.Db db, Map<String, Object> stage) throws Exception {
    return "admin".equals(db.viewerRole)
        || ("agent".equals(db.viewerRole) && Fulfilment.member(db, stage.get("group").toString()));
  }
}
