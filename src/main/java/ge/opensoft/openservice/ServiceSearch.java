package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class ServiceSearch {
    private ServiceSearch() {}

    static Set<String> words(String text) {
        var tokens = new HashSet<>(List.of(EmployeeAssistant.normalized(text).strip().split(" +")));
        tokens.removeAll(
                Set.of(
                        "i", "a", "to", "an", "is", "can", "my", "me", "for", "need", "please",
                        "the", "how", "what", "do", "of", "it", "and", "from", "get", "have",
                        "would", "like", "with", "request", "service"));
        tokens.remove("");
        return tokens;
    }

    static Object command(Store store, Store.Db db, String op, Map<String, Object> b) throws Exception {
        String id = id(b, "new_id");
        var row = get(db, "employee-conversation", id);
        var session = object(row.get("definition"));
        if (op.equals("choose_service")) {
            require(
                    db.viewerId.equals(session.get("owner"))
                            && "clarification_required".equals(session.get("state")),
                    "Choose an open conversation");
            String intentId = id(b, "intent_id");
            require(
                    ((List<?>) session.get("choices")).contains(intentId),
                    "Choose one of the suggested services");
            var intent = definition(db, "employee-intent", intentId);
            var citation = EmployeeAssistant.sources(store, db, intent);
            session.putAll(
                    map(
                            "intent",
                            intentId,
                            "version",
                            get(db, "employee-intent", intentId).get("revision"),
                            "group",
                            intent.get("group"),
                            "citation",
                            citation,
                            "state",
                            "confirmation_required"));
            session.remove("choices");
            save(db, "employee-conversation", id, row.get("revision"), session);
            session.remove("shown_articles");
            return session;
        }
        require(session.isEmpty(), "Start with a new conversation identifier");
        long start = System.nanoTime();
        String question = text(b, "question", 500);
        var terms = words(question);
        var ranked = new ArrayList<Map<String, Object>>();
        for (var entry : list(db, "employee-intent")) {
            var intent = object(entry.get("definition"));
            Map<String, Object> citation;
            try {
                citation = EmployeeAssistant.sources(store, db, intent);
            } catch (Failure e) {
                if (e.status == 403
                        || e.status == 404
                        || "Policy access required".equals(e.getMessage())) continue;
                throw e;
            }
            var tokens =
                    words(
                            citation.get("catalog_name")
                                    + " "
                                    + citation.get("policy_title")
                                    + " "
                                    + String.join(
                                            " ",
                                            ((List<?>) intent.get("terms"))
                                                    .stream().map(Object::toString).toList()));
            if(citation.containsKey("external")) {
                var external=object(citation.get("external"));
                tokens.addAll(words(external.get("title")+" "+external.get("body")));
            }
            tokens.retainAll(terms);
            if (!tokens.isEmpty())
                ranked.add(
                        map(
                                "intent_id",
                                entry.get("subject"),
                                "score",
                                tokens.size(),"matched",tokens,
                                "citation",
                                citation));
        }
        if(op.equals("weighted_search"))EmployeeSearchReview.weight(ranked);
        else for(var match:ranked)match.remove("matched");
        ranked.sort(Comparator.comparingInt(m -> -(Integer) m.get("score")));
        var suggestions = ranked.stream().limit(5).toList();
        var data =
                map(
                        "owner",
                        db.viewerId,
                        "question",
                        question,
                        "state",
                        suggestions.isEmpty() ? "handoff_required" : "clarification_required",
                        "choices",
                        suggestions.stream().map(s -> s.get("intent_id")).toList(),
                        "shown_articles",
                        suggestions.stream()
                                .map(s -> object(s.get("citation")).get("article_id"))
                                .toList(),
                        "grounded_matches",
                        ranked.size(),
                        "latency_ms",
                        (System.nanoTime() - start) / 1000000,
                        "model_cost",
                        0);
        var desk = object(get(db, "assistant-desk", "default").get("definition"));
        if (desk.containsKey("group")) data.put("group", desk.get("group"));
        save(db, "employee-conversation", id, 0, data);
        return map(
                "conversation",
                id,
                "state",
                data.get("state"),
                "suggestions",
                suggestions,
                "latency_ms",
                data.get("latency_ms"),
                "model_cost",
                0);
    }
}
