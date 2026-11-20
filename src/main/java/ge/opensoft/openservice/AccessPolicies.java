package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class AccessPolicies {
    private AccessPolicies() {}

    static boolean vendor(String kind, String host) {
        return kind.equals("jira_transition")
                ? host.endsWith(".atlassian.net")
                : host.equals("graph.microsoft.com");
    }

    static String qualification(String kind, String host) {
        return vendor(kind, host) ? "vendor_destination_verified" : "fixture_or_custom_endpoint";
    }

    static String environment(Map<String, Object> b) {
        return choice(b, "environment", "test", "production");
    }

    static Map<String, Object> step(Map<String, Object> action, boolean privileged) {
        String kind = action.get("kind").toString();
        var step =
                map(
                        "kind",
                        kind,
                        "title",
                        "Provision approved access",
                        "stage",
                        privileged ? 3 : 2,
                        "alias",
                        action.get("alias"),
                        "oauth",
                        action.get("oauth"));
        if (kind.equals("jira_transition"))
            step.putAll(
                    map(
                            "issue",
                            action.get("target"),
                            "transition",
                            action.get("argument"),
                            "status_id",
                            action.get("expected")));
        else step.put("target", privileged ? action.get("argument") : action.get("target"));
        return step;
    }

    static List<Map<String, Object>> path(
            Map<String, Object> policy, Map<String, Object> action, boolean privileged) {
        var steps = new ArrayList<Map<String, Object>>();
        steps.add(map("kind", "approval", "title", "Manager approval", "stage", 1));
        if (privileged)
            steps.add(
                    map(
                            "kind",
                            "team_approval",
                            "title",
                            "Privileged access approval",
                            "stage",
                            2,
                            "group",
                            policy.get("group")));
        for (var part : objects(policy.getOrDefault("procedures", List.of()))) {
            if (!Set.of("both", privileged ? "privileged" : "standard").contains(part.get("level")))
                continue;
            steps.add(
                    map(
                            "kind",
                            part.get("kind"),
                            "title",
                            part.get("title"),
                            "group",
                            part.get("group"),
                            "stage",
                            steps.size() + 1));
        }
        var provision = step(action, privileged);
        provision.put("stage", steps.size() + 1);
        steps.add(provision);
        return steps;
    }

    static Object run(Store store, Store.Db db, String op, Map<String, Object> b) throws Exception {
        if (op.equals("action")) {
            String action = id(b, "action_id"),
                    env = environment(b),
                    kind = choice(b, "kind", "entra_add", "entra_remove", "jira_transition");
            String alias = text(b, "alias", 80);
            var connector = ConnectorConsole.page(db, alias);
            String binding = key(alias);
            var old = object(get(db, "deployment-origin", binding).get("definition"));
            require(
                    env.equals(old.getOrDefault("environment", env)),
                    "This credential alias belongs to another environment");
            if (old.isEmpty()) save(db, "deployment-origin", binding, 0, map("environment", env));
            long expires = ChangeScheduling.instant(b.get("expires"));
            require(expires > System.currentTimeMillis(), "Authorization has expired");
            var spec =
                    map(
                            "kind",
                            kind,
                            "alias",
                            alias,
                            "oauth",
                            text(b, "oauth", 80),
                            "target",
                            text(b, "target", 80),
                            "argument",
                            text(b, "argument", 80),
                            "expected",
                            text(b, "expected", 80),
                            "expires",
                            expires,
                            "environment",
                            env,
                            "review",
                            connector.get("review"),
                            "permission",
                            kind.equals("jira_transition")
                                    ? "Browse Projects and Transition Issues"
                                    : "GroupMember.ReadWrite.All");
            for (boolean elevated : List.of(false, true))
                VerifiedActions.bind(db, step(spec, elevated));
            return put(db, "library-action", key(action, env), b.get("revision"), spec);
        }
        if (op.equals("status")) {
            String action = id(b, "action_id"), env = environment(b);
            var cfg = definition(db, "library-action", key(action, env));
            var report = new LinkedHashMap<>(cfg);
            report.put(
                    "authorization_expired",
                    ((Number) cfg.get("expires")).longValue() <= System.currentTimeMillis());
            if (!cfg.get("oauth").equals("alias")) {
                var token =
                        object(
                                get(db, "schedule-token", cfg.get("oauth").toString())
                                        .get("definition"));
                report.put("oauth_token_expires_at", token.get("expires"));
            }
            var evidence = new ArrayList<Object>();
            for (var row : list(db, "action-evidence")) {
                var item = object(row.get("definition"));
                var bound = object(item.get("binding"));
                if (action.equals(bound.get("action_id")) && env.equals(bound.get("environment")))
                    evidence.add(item);
            }
            report.put("evidence", evidence);
            return report;
        }
        if (op.equals("catalog")) {
            String env = environment(b);
            var result = new ArrayList<Map<String, Object>>();
            for (var row : list(db, "access-release")) {
                var release = object(row.get("definition"));
                if (env.equals(release.get("environment")))
                    result.add(
                            map(
                                    "policy_id",
                                    row.get("subject"),
                                    "title",
                                    release.get("title"),
                                    "revision",
                                    row.get("revision")));
            }
            return result;
        }
        if (op.equals("qualify")) {
            String env = environment(b), actionId = id(b, "action_id"), runId = id(b, "run_id");
            var binding = definition(db, "access-run", runId);
            require(
                    List.of(actionId, env)
                            .equals(List.of(binding.get("action_id"), binding.get("environment"))),
                    "Run belongs to a different action");
            var run = definition(db, "workflow-run", runId);
            var steps = objects(run.get("steps"));
            int index = integer(b.get("step"), 1, steps.size());
            var result = steps.get(index - 1);
            require(
                    WorkflowStudio.REMOTE.contains(result.get("kind"))
                            && "done".equals(result.get("state"))
                            && Boolean.TRUE.equals(result.get("changed")),
                    "An acknowledged and verified write is required");
            var connector = ConnectorConsole.page(db, result.get("alias").toString());
            require(
                    connector.get("review").equals(result.get("connector")),
                    "Connector changed since execution");
            String origin = connector.get("origin").toString();
            var current = definition(db, "library-action", key(actionId, env));
            ProblemInvestigations.revision(
                    binding.get("action_revision"),
                    get(db, "library-action", key(actionId, env)).get("revision"));
            require(
                    current.get("review").equals(result.get("connector")),
                    "Connector changed since execution");
            String host = java.net.URI.create(origin).getHost();
            String qualification = qualification(result.get("kind").toString(), host);
            String receipt = key(actionId, env, runId, index);
            if (get(db, "action-evidence", receipt).get("revision").equals(0))
                save(
                        db,
                        "action-evidence",
                        receipt,
                        0,
                        map(
                                "qualification",
                                qualification,
                                "origin",
                                origin,
                                "run",
                                runId,
                                "observed",
                                result.get("verified_at"),
                                "binding",
                                binding));
            return get(db, "action-evidence", receipt);
        }
        if (op.equals("procedure")) {
            String team = id(b, "group_id");
            Service.readRecord(db, "sys_user_group", team);
            return put(
                    db,
                    "access-procedure",
                    id(b, "procedure_id"),
                    b.get("revision"),
                    map(
                            "kind",
                            choice(b, "kind", "work", "team_approval"),
                            "title",
                            text(b, "title", 160),
                            "group",
                            team));
        }
        String policyId = id(b, "policy_id");
        if (op.equals("extend")) {
            String procedure = id(b, "procedure_id");
            var policy = definition(db, "access-design", policyId);
            var part = definition(db, "access-procedure", procedure);
            part.putAll(
                    map(
                            "procedure",
                            procedure,
                            "version",
                            get(db, "access-procedure", procedure).get("revision"),
                            "level",
                            choice(b, "level", "standard", "privileged", "both")));
            var parts = new ArrayList<>(objects(policy.getOrDefault("procedures", List.of())));
            require(parts.size() < 20, "A policy supports at most twenty additional procedures");
            parts.add(part);
            policy.put("procedures", parts);
            return put(db, "access-design", policyId, b.get("revision"), policy);
        }
        if (op.equals("design")) {
            String group = id(b, "group_id");
            Service.readRecord(db, "sys_user_group", group);
            String env = environment(b), action = id(b, "action_id");
            var library = get(db, "library-action", key(action, env));
            definition(db, "library-action", key(action, env));
            return put(
                    db,
                    "access-design",
                    policyId,
                    b.get("revision"),
                    map(
                            "title",
                            text(b, "title", 160),
                            "days",
                            integer(b.get("days"), 1, 365),
                            "group",
                            group,
                            "environment",
                            env,
                            "action_id",
                            action,
                            "action_revision",
                            library.get("revision")));
        }
        if (op.equals("apply")) {
            var release = definition(db, "access-release", policyId);
            ProblemInvestigations.revision(
                    b.get("revision"), get(db, "access-release", policyId).get("revision"));
            String level = choice(b, "level", "standard", "privileged"), runId = id(b, "new_id");
            lease(db, map("template", release.get(level)));
            var result =
                    WorkflowStudio.startAccess(
                            db,
                            map("template_id", release.get(level), "revision", 1, "new_id", runId),
                            db.viewerId);
            save(db, "access-run", runId, 0, release);
            return result;
        }
        var row = get(db, "access-design", policyId);
        ProblemInvestigations.revision(b.get("revision"), row.get("revision"));
        var policy = definition(db, "access-design", policyId);
        String actionKey = key(policy.get("action_id"), policy.get("environment"));
        var action = definition(db, "library-action", actionKey);
        ProblemInvestigations.revision(
                policy.get("action_revision"),
                get(db, "library-action", actionKey).get("revision"));
        require(
                ((Number) action.get("expires")).longValue() > System.currentTimeMillis(),
                "Renew action authorization");
        var preview =
                map(
                        "standard",
                        path(policy, action, false),
                        "privileged",
                        path(policy, action, true));
        for (boolean elevated : List.of(false, true))
            VerifiedActions.bind(db, step(action, elevated));
        String signature = key(policy, row.get("revision"), action, preview);
        if (op.equals("simulate")) {
            save(
                    db,
                    "access-simulation",
                    policyId,
                    get(db, "access-simulation", policyId).get("revision"),
                    map("signature", signature));
            return preview;
        }
        require(
                signature.equals(definition(db, "access-simulation", policyId).get("signature")),
                "Simulate both current access paths before publication");
        var release = new LinkedHashMap<>(policy);
        release.put("expires", action.get("expires"));
        release.put("signature", signature);
        var old = get(db, "access-release", policyId);
        if (signature.equals(object(old.get("definition")).get("signature"))) return old;
        for (String level : List.of("standard", "privileged")) {
            String template = Values.id();
            WorkflowStudio.publish(
                    db,
                    map(
                            "template_id",
                            template,
                            "days",
                            policy.get("days"),
                            "title",
                            policy.get("title") + " (" + level + ")",
                            "steps",
                            preview.get(level),
                            "revision",
                            0));
            save(
                    db,
                    "action-lease",
                    template,
                    0,
                    map(
                            "expires",
                            action.get("expires"),
                            "action",
                            actionKey,
                            "revision",
                            policy.get("action_revision")));
            release.put(level, template);
        }
        return put(db, "access-release", policyId, old.get("revision"), release);
    }

    static void lease(Store.Db db, Map<String, Object> run) throws Exception {
        var item =
                object(
                        get(db, "action-lease", Objects.toString(run.get("template"), ""))
                                .get("definition"));
        if (!item.isEmpty()) {
            require(
                    ((Number) item.get("expires")).longValue() > System.currentTimeMillis(),
                    "Renew and republish the access action");
            ProblemInvestigations.revision(
                    item.get("revision"),
                    get(db, "library-action", item.get("action").toString()).get("revision"));
        }
    }
}
