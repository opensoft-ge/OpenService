package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class EmployeeAssistant {
    private EmployeeAssistant() {}

    static String normalized(String question) {
        return " "
                + question.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip()
                + " ";
    }

    static Map<String, Object> sources(Store store, Store.Db db, Map<String, Object> intent) throws Exception {
        var offering = Catalogue.find(db, intent.get("offering").toString(), false);
        var policy =
                object(KnowledgeFeedback.page(db, intent.get("article").toString()).get("article"));
        var schema = Service.schema(db, "kb_knowledge");
        RecordPolicy.read(db, schema);
        require(
                FieldAccess.allowed(db, schema, "short_description")
                        && FieldAccess.allowed(db, schema, "text"),
                "Policy access required");
        var citation=map(
                "offering_id",
                offering.get("offering_id"),
                "offering_revision",
                offering.get("revision"),
                "catalog_name",
                offering.get("name"),
                "questions",
                offering.get("questions"),
                "article_id",
                policy.get("article_id"),
                "policy_version",
                policy.get("version"),
                "policy_title",
                policy.get("title"));
        ExternalPolicies.cite(store,db,intent,citation);
        return citation;
    }

    static Object command(Store store, Store.Db db, String op, Map<String, Object> b)
            throws Exception {
        long actionStart = System.nanoTime();
        if (op.equals("fallback")) {
            String group = id(b, "group_id");
            CatalogueRouting.group(db, group);
            return put(db, "assistant-desk", "default", b.get("revision"), map("group", group));
        }
        String id = id(b, "new_id");
        if (op.equals("intent")) {
            var terms = new TreeSet<String>();
            for (String term : text(b, "terms", 400).split(",")) {
                String clean = normalized(term).strip();
                require(clean.length() >= 2, "Use specific intent terms");
                terms.add(clean);
            }
            String group = id(b, "group_id");
            CatalogueRouting.group(db, group);
            var intent =
                    map(
                            "terms",
                            terms,
                            "group",
                            group,
                            "article",
                            id(b, "article_id"),
                            "offering",
                            id(b, "offering_id"));
            sources(store, db, intent);
            return put(db, "employee-intent", id, b.get("revision"), intent);
        }
        var row = get(db, "employee-conversation", id);
        var session = object(row.get("definition"));
        if (op.equals("ask")) {
            long start = System.nanoTime();
            String question = text(b, "question", 500);
            require(session.isEmpty(), "Choose a new conversation key");
            var matches = new ArrayList<Map<String, Object>>();
            var normalized = normalized(question);
            var groups = new TreeSet<String>();
            for (var entry : list(db, "employee-intent")) {
                var intent = object(entry.get("definition"));
                if (((List<?>) intent.get("terms"))
                        .stream().noneMatch(term -> normalized.contains(" " + term + " ")))
                    continue;
                Map<String, Object> citation;
                try {
                    citation = sources(store, db, intent);
                } catch (Failure denied) {
                    if (denied.status == 403
                            || denied.status == 404
                            || denied.getMessage().equals("Policy access required")) continue;
                    throw denied;
                }
                groups.add(intent.get("group").toString());
                matches.add(
                        map(
                                "intent",
                                entry.get("subject"),
                                "version",
                                entry.get("revision"),
                                "citation",
                                citation));
            }
            session =
                    map(
                            "owner",
                            db.viewerId,
                            "question",
                            question,
                            "state",
                            matches.size() == 1 ? "confirmation_required" : "handoff_required",
                            "grounded_matches",
                            matches.size(),
                            "model_cost",
                            0);
            if (matches.size() == 1) session.putAll(matches.getFirst());
            if (groups.size() == 1) session.put("group", groups.first());
            else {
                var fallback = object(get(db, "assistant-desk", "default").get("definition"));
                if (!fallback.isEmpty()) session.put("group", fallback.get("group"));
            }
            session.put("latency_ms", (System.nanoTime() - start) / 1000000);
            save(db, "employee-conversation", id, 0, session);
            return session;
        }
        require(db.viewerId.equals(session.get("owner")), "Conversation unavailable");
        if (op.equals("handoff")) {
            if ("handed_off".equals(session.get("state")))
                return map("request_id", session.get("request_id"));
            require(
                    !session.containsKey("request_id") && session.containsKey("group"),
                    "Configure a service desk for this handoff");
            var request =
                    Requests.create(
                            db,
                            actor(db),
                            map(
                                    "short_description",
                                    "Employee service question",
                                    "description",
                                    session.get("question") + "\n" + text(b, "details", 1200)));
            CatalogueRouting.create(db, map("fulfilment_group_id", session.get("group")), request);
            session.putAll(map("state", "handed_off", "request_id", request.get("sys_id")));
        } else {
            require(Boolean.TRUE.equals(b.get("confirm")), "Confirm this request");
            var values = object(b.get("manifest"));
            String details = text(b, "details", 2000);
            String accepted = key(new TreeMap<>(values), details);
            if ("submitted".equals(session.get("state"))) {
                require(
                        accepted.equals(session.get("accepted")),
                        "The original confirmation differs");
                return map("request_id", session.get("request_id"));
            }
            require(
                    "confirmation_required".equals(session.get("state")),
                    "Resolve the ambiguous request with a person");
            String intentId = session.get("intent").toString();
            var intent = definition(db, "employee-intent", intentId);
            ProblemInvestigations.revision(
                    session.get("version"), get(db, "employee-intent", intentId).get("revision"));
            var citation = sources(store, db, intent);
            require(
                    citation.equals(session.get("citation")),
                    "Policy or offering changed. Review a new suggestion.");
            var request =
                    Catalogue.order(
                            db,
                            intent.get("offering").toString(),
                            map(
                                    "revision",
                                    citation.get("offering_revision"),
                                    "key",
                                    id,
                                    "answers",
                                    values,
                                    "details",
                                    details));
            session.putAll(
                    map(
                            "state",
                            "submitted",
                            "accepted",
                            accepted,
                            "request_id",
                            request.get("sys_id")));
        }
        session.put("action_latency_ms", (System.nanoTime() - actionStart) / 1000000);
        save(db, "employee-conversation", id, row.get("revision"), session);
        return map("request_id", session.get("request_id"), "state", session.get("state"));
    }
}
