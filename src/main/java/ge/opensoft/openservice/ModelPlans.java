package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;

/** A provider may propose native updates. Only the ordinary plan approval can schedule them. */
final class ModelPlans {
    private ModelPlans() {}

    static Object propose(Store store, Store.Db db, Map<String,Object> body) throws Exception {
        Service.requireAccountAdministrator(db, db.viewerId);
        boolean preview = "preview_model".equals(body.get("operation"));
        var expected = new HashSet<>(Set.of("operation","delegate_id","key","goal","grant_revision"));
        if (!preview) expected.addAll(Set.of("preview_token","confirm"));
        require(body.keySet().equals(expected), "Provide the fields for this planning operation");
        String delegate = EnterpriseData.id(body.get("delegate_id"));
        String key = EnterpriseData.id(body.get("key"));
        ProblemInvestigations.revision(0, get(db, "agent-plan", key).get("revision"));
        var grant = AgentPlans.grant(db, delegate);
        ProblemInvestigations.revision(body.get("grant_revision"), grant.get("revision"));
        var targets = objects(object(grant.get("definition")).get("targets"));
        var options = new ArrayList<Map<String,Object>>();
        for (var target : targets) {
            AgentPlans.record(db, target);
            options.add(map("target", options.size(), "table", target.get("table"), "fields", target.get("fields")));
        }
        var prompt = map("goal", WorkJobs.text(body.get("goal"), 2000), "targets", options);
        String model = store.environment.getProperty("AI_MODEL", "");
        String alias = store.environment.getProperty("AI_CONNECTOR_ALIAS", "");
        String state = AiConsent.state(store, db, db.viewerId, "agent-model-plan", key,
                map("prompt", prompt, "delegate", delegate, "grant", grant));
        if (preview) {
            var review = AiConsent.issue(store, state);
            review.putAll(map("provider_alias", alias, "model", model, "payload", prompt));
            return review;
        }
        require(Boolean.TRUE.equals(body.get("confirm")), "Confirm sending the reviewed planning goal");
        require(store.environment.getProperty("AI_ENABLED", Boolean.class, false), "AI assistance is disabled");
        AiConsent.verify(store, body.get("preview_token"), state);
        require(!model.isBlank(), "Configure a planning model");
        var connector = db.one("SELECT * FROM os_connector WHERE alias=?", alias);
        require(connector != null, "Configure a planning connector");
        String origin = connector.get("origin").toString();
        require(Arrays.asList(store.environment.getProperty("CONNECTOR_ALLOWED_ORIGINS","").split(",")).contains(origin),
                "Planning provider origin is unavailable");
        String token = Outbox.decrypt(store.secret, connector.get("envelope").toString()).get("token").toString();
        long started=System.nanoTime();
        var response = ProviderAssist.executeMetered(origin, token, map("model", model, "store", false,
                "max_completion_tokens", 800, "messages", List.of(
                map("role","system","content","Propose native record updates for the goal. For a managed sc_request the only allowed patch is state=fulfilling to claim fulfilment. Return only a JSON array of objects with target (integer index) and patch (field values). Use only the listed targets and fields. If the goal cannot be satisfied return []."),
                map("role","user","content", JSON.writeValueAsString(prompt)))));
        long latency=(System.nanoTime()-started)/1000000;
        String reply=response.get("text").toString();
        Object proposed;
        try {
            proposed = JSON.readValue(reply, Object.class);
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new Failure(502, "The planning provider returned invalid JSON");
        }
        var steps = new ArrayList<Map<String,Object>>();
        for (Object value : EnterpriseData.batch(proposed, 10)) {
            var step = object(value);
            keys(step, "target", "patch");
            var target = targets.get(integer(step.get("target"), 0, targets.size() - 1));
            steps.add(map("table", target.get("table"), "record_id", target.get("record_id"), "patch", step.get("patch")));
        }
        AgentPlans.command(store, db, map("operation","review","key",key,"delegate_id",delegate,
                "grant_revision",grant.get("revision"),"steps",steps));
        save(db,"plan-meter",key,0,PlanEvaluations.meter(store,response.get("usage"),latency));
        SecurityEvents.record(db, db.viewerId, db.viewerId, "ai.plan_drafted", map("plan",key,"provider",alias,"model",model));
        return map("plan", key, "state", "reviewed", "step_count", steps.size());
    }
}
