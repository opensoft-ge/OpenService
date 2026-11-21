package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class EmployeeOutcomes {
    private EmployeeOutcomes() {}

    static Object command(Store.Db db, String op, Map<String, Object> b) throws Exception {
        String suite = id(b, "new_id");
        if (op.equals("holdout")) {
            var cases = new ArrayList<>();
            var seen = new HashSet<>();
            for (Object raw : batch(b.get("cases"), 100)) {
                var test = object(raw);
                keys(
                        test,
                        "conversation",
                        "employee",
                        "expected",
                        "offering_id",
                        "article_id",
                        "group_id",
                        "forbidden_articles");
                String conversation = id(test, "conversation"), employee = id(test, "employee");
                var account = AgentPlans.account(db, employee);
                require(
                        "requester".equals(account.get("role"))
                                && seen.add(conversation)
                                && get(db, "employee-conversation", conversation)
                                        .get("revision")
                                        .equals(0),
                        "Choose unique, unused employee conversation identifiers");
                String expected = choice(test, "expected", "submitted", "handed_off");
                if (expected.equals("submitted")) {
                    Catalogue.find(db, id(test, "offering_id"), true);
                    id(test, "article_id");
                } else CatalogueRouting.group(db, id(test, "group_id"));
                for (var article : batch(test.get("forbidden_articles"), 32))
                    EnterpriseData.id(article);
                cases.add(test);
            }
            require(!cases.isEmpty(), "Include at least one held-out job");
            return put(
                    db,
                    "employee-holdout",
                    suite,
                    0,
                    map("cases", cases, "created", now(), "by", db.viewerId));
        }
        var results = new ArrayList<>();
        var latencies = new ArrayList<Long>();
        int successes = 0,
                grounded = 0,
                groundingTrials = 0,
                handoffs = 0,
                routed = 0,
                forbidden = 0;
        for (var test : objects(definition(db, "employee-holdout", suite).get("cases"))) {
            String conversation = test.get("conversation").toString();
            var session = object(get(db, "employee-conversation", conversation).get("definition"));
            boolean owned = test.get("employee").equals(session.get("owner"));
            var citation = object(session.getOrDefault("citation", map()));
            Object article = citation.get("article_id");
            boolean disclosed =
                    (article != null
                                    && ((List<?>) test.get("forbidden_articles")).contains(article))
                            || !Collections.disjoint(
                                    (List<?>) session.getOrDefault("shown_articles", List.of()),
                                    (List<?>) test.get("forbidden_articles"));
            if (disclosed) forbidden++;
            boolean groundedCase = false, done = false;
            Object request = session.get("request_id");
            if ("submitted".equals(test.get("expected"))) {
                groundingTrials++;
                groundedCase =
                        owned
                                && test.get("article_id").equals(article)
                                && test.get("offering_id").equals(citation.get("offering_id"));
                if (groundedCase) grounded++;
                done =
                        groundedCase
                                && "submitted".equals(session.get("state"))
                                && db.count(
                                                "SELECT COUNT(*) FROM os_catalog_order WHERE"
                                                        + " request_id=? AND user_id=? AND"
                                                        + " offering_id=? AND order_key=?",
                                                request,
                                                test.get("employee"),
                                                test.get("offering_id"),
                                                conversation)
                                        == 1;
            } else {
                handoffs++;
                done =
                        owned
                                && "handed_off".equals(session.get("state"))
                                && db.count(
                                                "SELECT COUNT(*) FROM os_fulfilment WHERE"
                                                        + " request_id=? AND group_id=?",
                                                request,
                                                test.get("group_id"))
                                        == 1;
                if (done) routed++;
            }
            if (session.containsKey("latency_ms"))
                latencies.add(
                        ((Number) session.get("latency_ms")).longValue()
                                + ((Number) session.getOrDefault("action_latency_ms", 0))
                                        .longValue());
            if (done && !disclosed) successes++;
            results.add(
                    map(
                            "conversation",
                            conversation,
                            "expected",
                            test.get("expected"),
                            "actual",
                            session.getOrDefault("state", "unattempted"),
                            "verified",
                            done,
                            "grounded",
                            groundedCase,
                            "forbidden_citation",
                            disclosed));
        }
        Collections.sort(latencies);
        return map(
                "cases",
                results,
                "completed_eligible_requests",
                results.stream().map(Values::object).filter(c->"submitted".equals(c.get("expected")) && Boolean.TRUE.equals(c.get("verified")) && !Boolean.TRUE.equals(c.get("forbidden_citation"))).count(),
                "verified_successes",
                successes,
                "total",
                results.size(),
                "grounded",
                grounded,
                "grounding_trials",
                groundingTrials,
                "correct_handoff_owner",
                routed,
                "handoff_trials",
                handoffs,
                "unauthorized_citations",
                forbidden,
                "p95_processing_ms",
                latencies.isEmpty()
                        ? null
                        : latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1),
                "model_cost_usd",
                0,
                "mode",
                "local intent matching");
    }
}
