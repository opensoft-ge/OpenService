package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class ActionPackages {
    private ActionPackages() {}

    static Map<String, Object> contract(String kind, String title) {
        var contract =
                switch (kind) {
                    case "github_issue" ->
                            map(
                                    "inputs",
                                    map("path", "github_issue_path"),
                                    "outputs",
                                    map(
                                            "title",
                                            "text",
                                            "issue_id",
                                            "text",
                                            "updated_at",
                                            "timestamp"),
                                    "permission",
                                    "Issues: read",
                                    "credential",
                                    "bearer");
                    case "jira_transition" ->
                            map(
                                    "inputs",
                                    map(
                                            "issue",
                                            "issue_key",
                                            "transition",
                                            "numeric_id",
                                            "expected",
                                            "status_id"),
                                    "outputs",
                                    map("changed", "boolean", "verified_at", "timestamp"),
                                    "permission",
                                    "Browse Projects and Transition Issues",
                                    "credential",
                                    "bearer or OAuth");
                    default ->
                            map(
                                    "inputs",
                                    map("group", "uuid", "member", "employee directory uuid"),
                                    "outputs",
                                    map("changed", "boolean", "verified_at", "timestamp"),
                                    "permission",
                                    "GroupMember.ReadWrite.All",
                                    "credential",
                                    "bearer or OAuth");
                };
        contract.putAll(map("title", title, "kind", kind));
        return contract;
    }

    static Object command(Store store, Store.Db db, String op, Map<String, Object> b)
            throws Exception {
        String id = id(b, "package_id");
        if (Set.of("pack", "import").contains(op)) {
            var fields = b;
            if (op.equals("import")) {
                var packet = object(b.get("manifest"));
                keys(packet, "format", "package_id", "action");
                require(
                        "openservice.action.v1".equals(packet.get("format"))
                                && id.equals(packet.get("package_id")),
                        "Choose a matching action package");
                fields = object(packet.get("action"));
            }
            String kind =
                    choice(
                            fields,
                            "kind",
                            "entra_add",
                            "entra_remove",
                            "jira_transition",
                            "github_issue");
            var prior = object(get(db, "action-package", id).get("definition"));
            require(
                    "admin".equals(db.viewerRole)
                            || db.viewerId.equals(prior.getOrDefault("author", db.viewerId)),
                    "Package author required");
            require(
                    kind.equals(prior.getOrDefault("kind", kind)),
                    "Publish a different action under a new package ID");
            var contract = contract(kind, text(fields, "title", 120));
            if (op.equals("import"))
                require(
                        contract.equals(fields),
                        "Package must match the supported typed action, permissions and credential"
                            + " contract");
            contract.put("author", db.viewerId);
            return put(db, "action-package", id, b.get("revision"), contract);
        }
        var manifest = get(db, "action-package", id);
        var spec = definition(db, "action-package", id);
        if (op.equals("export")) {
            var action = new LinkedHashMap<>(spec);
            action.remove("author");
            return map("format", "openservice.action.v1", "package_id", id, "action", action);
        }
        String env = AccessPolicies.environment(b), key = key(id, env);
        var binding = get(db, "package-install", key);
        if (op.equals("inspect")) return map("manifest", manifest, "installation", binding);
        if (op.equals("install")) {
            ProblemInvestigations.revision(b.get("package_revision"), manifest.get("revision"));
            String alias = text(b, "alias", 80);
            var connector = ConnectorConsole.page(db, alias);
            String kind = spec.get("kind").toString();
            if (!kind.equals("github_issue")) {
                var request = new LinkedHashMap<>(b);
                request.putAll(
                        map(
                                "kind",
                                kind,
                                "action_id",
                                id,
                                "revision",
                                get(db, "library-action", key).get("revision")));
                AccessPolicies.run(store, db, "action", request);
            } else {
                require(
                        "alias".equals(b.get("oauth")),
                        "GitHub reader requires a bound bearer credential");
                var origin = get(db, "deployment-origin", key(alias));
                var saved = object(origin.get("definition"));
                require(
                        env.equals(saved.getOrDefault("environment", env)),
                        "Credential belongs to another environment");
                if (saved.isEmpty())
                    save(db, "deployment-origin", key(alias), 0, map("environment", env));
            }
            long expiry = ChangeScheduling.instant(b.get("expires"));
            require(expiry > System.currentTimeMillis(), "Authorization has expired");
            return put(
                    db,
                    "package-install",
                    key,
                    b.get("revision"),
                    map(
                            "manifest",
                            spec,
                            "version",
                            manifest.get("revision"),
                            "alias",
                            alias,
                            "review",
                            connector.get("review"),
                            "expires",
                            expiry,
                            "environment",
                            env,
                            "action_id",
                            id));
        }
        var cfg = definition(db, "package-install", key);
        ProblemInvestigations.revision(b.get("revision"), binding.get("revision"));
        require(
                "github_issue".equals(object(cfg.get("manifest")).get("kind")),
                "Run write actions through an approved access workflow");
        require(
                ((Number) cfg.get("expires")).longValue() > System.currentTimeMillis(),
                "Renew this installation");
        String path = text(b, "path", 240);
        IssueAdapters.validatePath("github", path);
        var usage = get(db, "package-read-window", key);
        long minute = System.currentTimeMillis() / 60000;
        var meter = object(usage.get("definition"));
        int used =
                Objects.equals(minute, ((Number) meter.getOrDefault("minute", 0)).longValue())
                        ? ((Number) meter.get("count")).intValue()
                        : 0;
        if (used >= 30) throw new Failure(429, "Package read quota reached; retry next minute");
        var reply =
                Connectors.fetch(
                        store,
                        db,
                        map("alias", cfg.get("alias"), "review", cfg.get("review"), "path", path));
        Map<String, Object> issue;
        try {
            issue = object(JSON.readValue((byte[]) reply.get("content"), Object.class));
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new Failure(502, "Invalid GitHub issue response");
        }
        var parsed = IssueAdapters.source("github", path, issue, "read");
        save(
                db,
                "package-read-window",
                key,
                usage.get("revision"),
                map("minute", minute, "count", used + 1));
        return map(
                "issue_id",
                parsed.get("id"),
                "title",
                object(parsed.get("values")).get("short_description"),
                "updated_at",
                parsed.get("version"));
    }
}
