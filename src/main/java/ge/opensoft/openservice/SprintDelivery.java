package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class SprintDelivery {
  private SprintDelivery() {}

  static Object schedule(Store.Db db, Map<String, Object> b) throws Exception {
    String epic = id(b, "epic_id"), sprint = id(b, "sprint_id");
    var funding = definition(db, "delivery-epic", epic);
    var from = java.time.LocalDate.parse(ProjectDelivery.date(b.get("start_on")));
    var to = java.time.LocalDate.parse(ProjectDelivery.date(b.get("finish_on")));
    String quarter = from.getYear() + "-Q" + ((from.getMonthValue() - 1) / 3 + 1);
    require(
        !to.isBefore(from)
            && !to.isAfter(from.plusDays(30))
            && to.getYear() == from.getYear()
            && (to.getMonthValue() - 1) / 3 == (from.getMonthValue() - 1) / 3,
        "Use a sprint of up to 31 days in the same quarter");
    var points = map();
    var assumptions = map();
    var people = new HashSet<String>();
    for (var team : object(b.get("manifest")).entrySet()) {
      String group = EnterpriseData.id(team.getKey());
      int rate = integer(team.getValue(), 1, 4800), total = 0;
      var calendar = QuarterFunding.capacity(db, group, quarter);
      for (var member : calendar.entrySet()) {
        require(people.add(member.getKey()), "A person belongs to multiple selected teams");
        var worker = object(member.getValue());
        require(
            Boolean.TRUE.equals(worker.get("calendar_known")), "Configure each member's calendar");
        var roster = object(worker.get("calendar"));
        for (var day = from; !day.isAfter(to); day = day.plusDays(1)) {
          int available =
              ((Number)
                      object(roster.get("exceptions"))
                          .getOrDefault(
                              day.toString(),
                              ((List<?>) roster.get("week"))
                                  .get(day.getDayOfWeek().getValue() - 1)))
                  .intValue();
          int bookedHundredths = 0;
          for (var reservation : objects(worker.get("reservations"))) {
            var booking = object(reservation.get("booking"));
            if (!funding.get("project").equals(reservation.get("project"))
                && day.getDayOfWeek().getValue() <= 5
                && day.toString().compareTo(booking.get("start_on").toString()) >= 0
                && day.toString().compareTo(booking.get("finish_on").toString()) <= 0)
              bookedHundredths += 480 * ((Number) booking.get("percent")).intValue();
          }
          total +=
              Math.max(
                  0, available - bookedHundredths / 100 - (bookedHundredths % 100 == 0 ? 0 : 1));
        }
      }
      points.put(group, Math.min(10000, total / rate));
      assumptions.put(
          group, map("minutes_per_point", rate, "available_minutes", total, "members", calendar));
    }
    for (var row : list(db, "delivery-sprint")) {
      var other = object(row.get("definition"));
      if ("active".equals(other.get("state"))
          && other.containsKey("finish_on")
          && other.get("finish_on").toString().compareTo(from.toString()) >= 0
          && other.get("start_on").toString().compareTo(to.toString()) <= 0)
        require(
            Collections.disjoint((List<?>) other.get("people"), people),
            "A team member is already committed to an overlapping sprint");
    }
    var body = new LinkedHashMap<>(b);
    body.put("manifest", points);
    var saved = object(command(db, "commit_sprint", body));
    var data = object(saved.get("definition"));
    data.putAll(
        map(
            "start_on",
            from.toString(),
            "finish_on",
            to.toString(),
            "assumptions",
            assumptions,
            "people",
            people));
    return put(db, "delivery-sprint", sprint, saved.get("revision"), data);
  }

  static Object command(Store.Db db, String op, Map<String, Object> b) throws Exception {
    String epic = id(b, "epic_id");
    if (op.equals("funded_epic")) {
      String goal = id(b, "goal_id"), project = id(b, "project_id");
      var funding = definition(db, "funding-goal", goal);
      require(
          money(object(funding.get("allocations")).getOrDefault(project, "0")).signum() > 0,
          "Choose a funded project");
      return put(
          db,
          "delivery-epic",
          epic,
          0,
          map(
              "title",
              text(b, "title", 160),
              "project",
              project,
              "goal",
              goal,
              "funding_plan",
              funding.get("approved_plan")));
    }
    var scope = definition(db, "delivery-epic", epic);
    if (op.equals("delivery_story")) {
      String team = id(b, "group_id");
      CatalogueRouting.group(db, team);
      var deps = new TreeSet<String>();
      for (Object ref : batch(b.get("entries"), 32)) {
        String dep = EnterpriseData.id(ref);
        require(
            epic.equals(definition(db, "delivery-story", dep).get("epic")),
            "Choose a predecessor in this epic");
        deps.add(dep);
      }
      return put(
          db,
          "delivery-story",
          id(b, "story_id"),
          0,
          map(
              "epic",
              epic,
              "team",
              team,
              "title",
              text(b, "title", 160),
              "points",
              integer(b.get("quantity"), 1, 100),
              "dependencies",
              deps,
              "state",
              "backlog"));
    }
    if (op.equals("commit_sprint")) {
      String sprint = id(b, "sprint_id");
      var capacity = object(b.get("manifest"));
      for (var entry : capacity.entrySet()) {
        CatalogueRouting.group(db, EnterpriseData.id(entry.getKey()));
        integer(entry.getValue(), 1, 10000);
      }
      var planned = map();
      var totals = new HashMap<String, Integer>();
      for (var entry : batch(b.get("entries"), 100)) {
        String story = EnterpriseData.id(entry);
        var row = get(db, "delivery-story", story);
        var data = definition(db, "delivery-story", story);
        require(
            epic.equals(data.get("epic")) && "backlog".equals(data.get("state")),
            "Choose available epic stories");
        String team = data.get("team").toString();
        int points = ((Number) data.get("points")).intValue();
        require(
            totals.merge(team, points, Integer::sum) <= integer(capacity.get(team), 1, 10000),
            "Team capacity exceeded");
        planned.put(story, new LinkedHashMap<>(data));
        data.putAll(map("state", "committed", "sprint", sprint));
        save(db, "delivery-story", story, row.get("revision"), data);
      }
      require(!planned.isEmpty(), "Select stories for the sprint");
      return put(
          db,
          "delivery-sprint",
          sprint,
          0,
          map(
              "epic",
              epic,
              "title",
              text(b, "title", 160),
              "capacity",
              capacity,
              "commitment",
              planned,
              "state",
              "active"));
    }
    String sprint = id(b, "sprint_id");
    var row = get(db, "delivery-sprint", sprint);
    var data = definition(db, "delivery-sprint", sprint);
    require(epic.equals(data.get("epic")), "Sprint belongs to another epic");
    if (op.equals("ship_story")) {
      String story = id(b, "story_id"), release = id(b, "release_id");
      var item = definition(db, "delivery-story", story);
      require(
          sprint.equals(item.get("sprint")) && "committed".equals(item.get("state")),
          "Choose a committed story");
      for (Object dep : (List<?>) item.get("dependencies"))
        require(
            "shipped".equals(definition(db, "delivery-story", dep.toString()).get("state")),
            "A predecessor is still open");
      var deployment = definition(db, "pipeline-release", release);
      require("deployed".equals(deployment.get("state")), "Choose a successful deployment");
      Service.readRecord(db, "change_request", deployment.get("change_id").toString());
      item.putAll(
          map(
              "state",
              "shipped",
              "release",
              release,
              "change",
              deployment.get("change_id"),
              "artifact",
              deployment.get("artifact")));
      return put(db, "delivery-story", story, b.get("revision"), item);
    }
    if ("closed".equals(data.get("state"))) return data;
    var outcomes = map();
    for (String story : object(data.get("commitment")).keySet()) {
      var current = get(db, "delivery-story", story);
      var item = object(current.get("definition"));
      outcomes.put(story, new LinkedHashMap<>(item));
      if (op.equals("close_sprint") && "committed".equals(item.get("state"))) {
        item.put("state", "backlog");
        item.remove("sprint");
        save(db, "delivery-story", story, current.get("revision"), item);
      }
    }
    if (op.equals("sprint_report"))
      return map("funding", scope, "plan", data, "outcomes", outcomes);
    data.putAll(map("state", "closed", "outcomes", outcomes, "funding", scope, "closed_at", now()));
    return put(db, "delivery-sprint", sprint, b.get("revision"), data);
  }
}
