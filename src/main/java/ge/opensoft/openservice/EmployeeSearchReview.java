package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

final class EmployeeSearchReview {
  private EmployeeSearchReview() {}

  static Object run(Store store, Store.Db db, String op, Map<String, Object> b) throws Exception {
    if (op.equals("weighted_search")) return ServiceSearch.command(store, db, op, b);
    String id = id(b, "new_id");
    var row = get(db, "employee-conversation", id);
    var session = new LinkedHashMap<>(object(row.get("definition")));
    require(db.viewerId.equals(session.get("owner")), "Conversation unavailable");
    if (!Set.of("confirmation_required", "clarification_required").contains(session.get("state")))
      return map("state", session.get("state"), "request_id", session.get("request_id"));
    var suggestions = new ArrayList<Map<String, Object>>();
    boolean pending = "confirmation_required".equals(session.get("state"));
    for (Object key :
        pending
            ? List.of(session.get("intent"))
            : (List<?>) session.getOrDefault("choices", List.of())) {
      String intent = key.toString();
      var spec = get(db, "employee-intent", intent);
      if (spec.get("revision").equals(0)) continue;
      try {
        var cite = EmployeeAssistant.sources(store, db, object(spec.get("definition")));
        if (!pending
            || Objects.equals(session.get("version"), spec.get("revision"))
                && cite.equals(session.get("citation")))
          suggestions.add(map("intent_id", intent, "citation", cite));
      } catch (Failure failure) {
        if (failure.status != 403
            && failure.status != 404
            && !"Policy access required".equals(failure.getMessage())) throw failure;
      }
    }
    // Retain article identifiers for the holdout auditor after the employee loses access.
    var history =
        new LinkedHashSet<Object>((List<?>) session.getOrDefault("shown_articles", List.of()));
    history.add(object(session.getOrDefault("citation", map())).get("article_id"));
    for (var suggestion : suggestions)
      history.add(object(suggestion.get("citation")).get("article_id"));
    history.remove(null);
    session.put("shown_articles", List.copyOf(history));
    if (suggestions.isEmpty()) {
      session.keySet().removeAll(Set.of("intent", "version", "citation", "choices", "group"));
      session.put("state", "handoff_required");
      session.put("grounded_matches", 0);
      var fallback = object(get(db, "assistant-desk", "default").get("definition"));
      if (fallback.containsKey("group")) session.put("group", fallback.get("group"));
    } else if (!pending) {
      session.put("choices", suggestions.stream().map(s -> s.get("intent_id")).toList());
    }
    if (!session.equals(row.get("definition")))
      save(db, "employee-conversation", id, row.get("revision"), session);
    return map(
        "state",
        session.get("state"),
        "suggestions",
        suggestions,
        "can_confirm",
        pending && !suggestions.isEmpty());
  }

  static void weight(List<Map<String, Object>> ranked) {
    // Weight rarer matching terms more heavily, giving extra weight to the offering and policy
    // titles.
    var frequency = new HashMap<String, Integer>();
    for (var match : ranked)
      for (Object word : (Set<?>) match.get("matched"))
        frequency.merge(word.toString(), 1, Integer::sum);
    for (var match : ranked) {
      var cite = object(match.get("citation"));
      var titles = ServiceSearch.words(cite.get("catalog_name") + " " + cite.get("policy_title"));
      int score = 0;
      for (Object raw : (Set<?>) match.remove("matched")) {
        String word = raw.toString();
        score +=
            (titles.contains(word) ? 3 : 1)
                * (int) (100 * (1 + Math.log((ranked.size() + 1.0) / (frequency.get(word) + 1.0))));
      }
      match.put("score", score);
    }
  }
}
