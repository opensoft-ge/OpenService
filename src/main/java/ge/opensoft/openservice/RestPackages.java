package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class RestPackages {
    private RestPackages() {}

    static String field(Object value) {
        String name = WorkJobs.text(value, 64);
        require(name.matches("[a-zA-Z_][a-zA-Z0-9_]*"), "Use a JSON field name");
        return name;
    }

    static Object command(Store store, Store.Db db, String op, Map<String, Object> b)
            throws Exception {
        String pkg = id(b, "package_id");
        if (op.equals("rest_define")) {
            var spec = object(b.get("manifest"));
            keys(spec, "method", "path", "auth", "inputs", "outputs", "items", "next");
            String method = choice(spec, "method", "GET", "POST", "PUT", "PATCH", "DELETE");
            require("bearer".equals(spec.get("auth")), "Choose bearer authentication");
            Connectors.path(spec.get("path"));
            var inputs = CatalogueQuestions.validate(spec.get("inputs"));
            require(
                    inputs.stream().noneMatch(f -> f.containsKey("when")),
                    "Use unconditional action inputs");
            var outputs = object(spec.get("outputs"));
            require(outputs.size() <= 32, "Limit output fields to 32");
            outputs.forEach(
                    (k, v) -> {
                        field(k);
                        require(
                                Set.of("string", "boolean", "number").contains(v),
                                "Choose a scalar output type");
                    });
            if (method.equals("GET")) {
                field(spec.get("items"));
                field(spec.get("next"));
                require(!outputs.isEmpty(), "Declare import outputs");
            } else
                require(
                        outputs.isEmpty()
                                && "".equals(spec.get("items"))
                                && "".equals(spec.get("next")),
                        "Write receipts are tracked by the connector job");
            return put(
                    db,
                    "rest-package",
                    pkg,
                    b.get("revision"),
                    map("title", text(b, "title", 160), "manifest", spec));
        }
        String env = choice(b, "environment", "test", "production"), binding = key(pkg, env);
        if (op.equals("rest_bind")) {
            var current = get(db, "rest-binding", binding);
            ProblemInvestigations.revision(b.get("revision"), current.get("revision"));
            var release = get(db, "rest-package", pkg);
            var data = definition(db, "rest-package", pkg);
            var spec = object(data.get("manifest"));
            String alias = text(b, "alias", 80);
            var connection = ConnectorConsole.page(db, alias);
            var installed =
                    map(
                            "spec",
                            spec,
                            "alias",
                            alias,
                            "origin",
                            connection.get("origin"),
                            "package_revision",
                            release.get("revision"),
                            "environment",
                            env);
            var previous = object(current.get("definition"));
            if (spec.get("method").equals("GET") && previous.containsKey("action")) {
                var action = ConnectorActions.find(db, previous.get("action").toString());
                ConnectorActions.save(
                        db,
                        action.get("action_id").toString(),
                        map(
                                "name",
                                action.get("name"),
                                "alias",
                                action.get("alias"),
                                "path",
                                action.get("path"),
                                "method",
                                action.get("method"),
                                "inputs",
                                action.get("inputs"),
                                "active",
                                false,
                                "revision",
                                action.get("revision")));
            }
            if (!spec.get("method").equals("GET")) {
                String action = Objects.toString(previous.get("action"), "");
                var result =
                        ConnectorActions.save(
                                db,
                                action,
                                map(
                                        "name",
                                        data.get("title") + " (" + env + ")",
                                        "alias",
                                        alias,
                                        "path",
                                        spec.get("path"),
                                        "method",
                                        spec.get("method"),
                                        "inputs",
                                        spec.get("inputs"),
                                        "active",
                                        true,
                                        "revision",
                                        action.isEmpty()
                                                ? 0
                                                : ConnectorActions.find(db, action)
                                                        .get("revision")));
                installed.put("action", result.get("action_id"));
            }
            return put(db, "rest-binding", binding, b.get("revision"), installed);
        }
        var install = definition(db, "rest-binding", binding);
        var connection = ConnectorConsole.page(db, install.get("alias").toString());
        if (!connection.get("origin").equals(install.get("origin")))
            throw new Failure(
                    409, "Review and rebind the package connection before running this action");
        var spec = object(install.get("spec"));
        var inputs = object(b.get("manifest"));
        if (!op.equals("rest_read")) {
            require(install.containsKey("action"), "Choose a write action");
            String action = install.get("action").toString();
            String token = id(b, "new_id");
            if (op.equals("rest_preview"))
                return ConnectorActions.preview(
                        store, db, action, map("values", inputs, "key", token));
            return ConnectorActions.queue(
                    store,
                    db,
                    action,
                    map(
                            "values",
                            inputs,
                            "key",
                            token,
                            "state",
                            b.get("state"),
                            "preview_token",
                            b.get("preview_token"),
                            "confirm",
                            b.get("confirm")));
        }
        require("GET".equals(spec.get("method")), "Choose an import action");
        CatalogueQuestions.snapshot(db, map("questions", spec.get("inputs")), inputs);
        String path = query(spec,inputs);
        String base = java.net.URI.create(path).getRawPath();
        var seen = new HashSet<String>();
        var results = new ArrayList<>();
        var outputs = object(spec.get("outputs"));
        for (; ; ) {
            require(seen.size() < 20 && seen.add(path), "Pagination limit or cycle encountered");
            var reply =
                    Connectors.fetch(store, db, map("alias", install.get("alias"), "path", path));
            var page = object(JSON.readValue((byte[]) reply.get("content"), Object.class));
            for (Object raw : batch(page.get(spec.get("items")), 250)) {
                require(results.size() < 1000, "Import exceeds 1000 rows");
                results.add(project(outputs, raw));
            }
            Object next = page.get(spec.get("next"));
            if (next == null || "".equals(next)) break;
            path = Connectors.path(next);
            require(
                    base.equals(java.net.URI.create(path).getRawPath()),
                    "Pagination must stay on the installed endpoint");
        }
        var snapshot =
                map(
                        "rows",
                        results,
                        "pages",
                        seen.size(),
                        "environment",
                        env,
                        "package_revision",
                        install.get("package_revision"),
                        "imported_at",
                        now());
        save(db, "rest-import", binding, get(db, "rest-import", binding).get("revision"), snapshot);
        return snapshot;
    }
    static String query(Map<String,Object> spec,Map<String,Object> inputs){
        String path = Connectors.path(spec.get("path"));
        for (var e : new TreeMap<>(inputs).entrySet())
            path +=
                    (path.contains("?") ? "&" : "?")
                            + e.getKey()
                            + "="
                            + java.net.URLEncoder.encode(
                                    e.getValue().toString(),
                                    java.nio.charset.StandardCharsets.UTF_8);
        return path;
    }
    static Map<String,Object> project(Map<String,Object> outputs,Object raw){
                var item = object(raw);
                var projected = map();
                for (var e : outputs.entrySet()) {
                    Object v = item.get(e.getKey());
                    boolean valid =
                            switch (e.getValue().toString()) {
                                case "string" -> v instanceof String s && s.length() <= 2000;
                                case "boolean" -> v instanceof Boolean;
                                default -> v instanceof Number;
                            };
                    require(valid, "Response does not match the declared output");
                    projected.put(e.getKey(), v);
                }
        return projected;
    }
}
