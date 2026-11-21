package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

/**
 * Import ordered change feeds. Each successful poll commits the rows and high-water mark together.
 */
final class IncrementalImports {
    private IncrementalImports() {}

    static Object configure(Store.Db db, Map<String, Object> b) throws Exception {
        String pkg = id(b, "package_id"),
                env = choice(b, "environment", "test", "production"),
                binding = key(pkg, env);
        var installed = definition(db, "rest-binding", binding);
        var spec = object(installed.get("spec"));
        String field = RestPackages.field(b.get("cursor_field")),
                input = RestPackages.field(b.get("cursor_input"));
        require(
                "GET".equals(spec.get("method"))
                        && "string".equals(object(spec.get("outputs")).get(field)),
                "Declare a string cursor output on a read action");
        var inputs = new LinkedHashMap<>(object(b.get("manifest")));
        inputs.put(input, text(b, "cursor", 200));
        CatalogueQuestions.snapshot(db, map("questions", spec.get("inputs")), inputs);
        return put(
                db,
                "incremental-import",
                binding,
                b.get("revision"),
                map(
                        "owner",
                        db.viewerId,
                        "authority",
                        AgentPlans.account(db, db.viewerId).get("auth_revision"),
                        "package_id",
                        pkg,
                        "environment",
                        env,
                        "binding_revision",
                        get(db, "rest-binding", binding).get("revision"),
                        "field",
                        field,
                        "input",
                        input,
                        "values",
                        inputs,
                        "minutes",
                        integer(b.get("minutes"), 1, 10080),
                        "active",
                        b.get("active"),
                        "next_at",
                        0,
                        "state",
                        "configured"));
    }

    static Object read(Store.Db db, Map<String, Object> b) throws Exception {
        return ImportReceipts.read(db,b);
    }

    static Object status(Store.Db db, Map<String, Object> b) throws Exception {
        String binding = key(id(b, "package_id"), choice(b, "environment", "test", "production"));
        return get(db, "incremental-import", binding);
    }

    static void poll(Store store, long now) throws Exception {
        try (var db = store.open()) {
            lock(db);
            for (var row : list(db, "incremental-import")) {
                var data = object(row.get("definition"));
                if (!Boolean.TRUE.equals(data.get("active"))
                        || ((Number) data.get("next_at")).longValue() > now) continue;
                var point = db.connection.setSavepoint();
                String binding = row.get("subject").toString();
                db.viewerId = data.get("owner").toString();
                try {
                    var owner = AgentPlans.account(db, db.viewerId);
                    db.viewerRole = owner.get("role").toString();
                    require("admin".equals(db.viewerRole), "Import owner lost authority");
                    ProblemInvestigations.revision(
                            data.get("authority"), owner.get("auth_revision"));
                    ProblemInvestigations.revision(
                            data.get("binding_revision"),
                            get(db, "rest-binding", binding).get("revision"));
                    var values = new LinkedHashMap<>(object(data.get("values")));
                    var install = definition(db, "rest-binding", binding);
                    var spec = object(install.get("spec"));
                    require(
                            install.get("origin")
                                    .equals(
                                            ConnectorConsole.page(
                                                            db, install.get("alias").toString())
                                                    .get("origin")),
                            "Review the connection destination");
                    CatalogueQuestions.snapshot(db, map("questions", spec.get("inputs")), values);
                    String path =
                            data.containsKey("next_path")
                                    ? data.get("next_path").toString()
                                    : RestPackages.query(spec, values);
                    var response =
                            Connectors.fetch(
                                    store, db, map("alias", install.get("alias"), "path", path));
                    var page =
                            object(JSON.readValue((byte[]) response.get("content"), Object.class));
                    var rows = batch(page.get(spec.get("items")), 250);
                    var visited =
                            new ArrayList<Object>(
                                    (List<?>) data.getOrDefault("visited", List.of()));
                    visited.add(key(path));
                    String next = Objects.toString(page.get(spec.get("next")), "");
                    if (!next.isEmpty()) {
                        next = Connectors.path(next);
                        require(
                                java.net
                                                .URI
                                                .create(path)
                                                .getRawPath()
                                                .equals(java.net.URI.create(next).getRawPath())
                                        && visited.size() < 256
                                        && !visited.contains(next) && !visited.contains(key(next)),
                                "Invalid or cyclic next page");
                    }
                    for (Object raw : rows) {
                        var receipt = ImportReceipts.accept(db, binding, data.get("field").toString(), object(spec.get("outputs")), raw);
                        if(receipt.inserted()) values.put(data.get("input").toString(),receipt.cursor());
                    }
                    if (next.isEmpty()) {
                        data.remove("next_path");
                        data.remove("visited");
                    } else {
                        data.put("next_path", next);
                        data.put("visited", visited);
                    }
                    data.putAll(
                            map(
                                    "values",
                                    values,
                                    "state",
                                    "current",
                                    "last_success",
                                    now,
                                    "rows",
                                    rows.size()));
                } catch (Exception failure) {
                    db.connection.rollback(point);
                    data.put("state", "retry_pending");
                } finally {
                    db.connection.releaseSavepoint(point);
                }
                data.put(
                        "next_at",
                        data.containsKey("next_path") && "current".equals(data.get("state"))
                                ? now
                                : now + ((Number) data.get("minutes")).longValue() * 60000);
                db.update(
                        "UPDATE os_enterprise_document SET definition=?,revision=revision+1 WHERE"
                                + " area='incremental-import' AND subject=? AND revision=?",
                        JSON.writeValueAsString(data),
                        binding,
                        row.get("revision"));
                db.connection.commit();
                break;
            }
        }
    }
}
