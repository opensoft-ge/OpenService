package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class PortableApps {
    private PortableApps() {}

    static Map<String, Object> export(Store.Db db, String app) throws Exception {
        var release = definition(db, "intake-release", app);
        var fields = new ArrayList<>();
        object(release.get("fields"))
                .forEach(
                        (name, raw) -> {
                            var field = new LinkedHashMap<>(object(raw));
                            field.put("name", name);
                            fields.add(field);
                        });
        var workflow = object(release.get("workflow"));
        var artifact=map(
                "format",
                "openservice.intake.v1",
                "title",
                release.get("title"),
                "fields",
                fields,
                "workflow",
                map(
                        "days",
                        workflow.get("days"),
                        "rows",
                        workflow.get("rows"),
                        "tests",
                        workflow.get("tests")),
                "source_group",
                release.get("group"),
                "source_app",
                app,
                "source_version",
                get(db, "intake-release", app).get("revision"),
                "roles",
                List.of("requester", "fulfillment_group"));
        var view=object(get(db,"guided-workspace",app).get("definition"));
        if(!view.isEmpty())artifact.put("workspace",view);
        return artifact;
    }

    static List<Object> semantic(List<Map<String, Object>> rows) {
        var result = new ArrayList<Object>();
        for (var row : rows) {
            var path = new LinkedHashMap<>(row);
            var steps = new ArrayList<>();
            for (var raw : objects(path.get("steps"))) {
                var step = new LinkedHashMap<>(raw);
                step.remove("procedure");
                step.remove("procedure_revision");
                steps.add(step);
            }
            path.put("steps", steps);
            result.add(path);
        }
        return result;
    }

    static Object command(Store store, Store.Db db, String op, Map<String, Object> b)
            throws Exception {
        String app = id(b, "app_id");
        if (op.equals("package_app")) return export(db, app);
        var pkg = new LinkedHashMap<>(object(b.get("manifest")));
        var workspace=object(pkg.getOrDefault("workspace",map()));
        pkg.remove("workspace");
        keys(
                pkg,
                "format",
                "title",
                "fields",
                "workflow",
                "source_group",
                "source_app",
                "source_version",
                "roles");
        require(
                "openservice.intake.v1".equals(pkg.get("format"))
                        && List.of("requester", "fulfillment_group").equals(pkg.get("roles")),
                "Unsupported application package");
        String group = id(b, "group_id"), design = id(b, "design_id");
        Service.readRecord(db, "sys_user_group", group);
        var workflow = object(pkg.get("workflow"));
        var bindings = object(b.get("bindings"));
        var rows = new ArrayList<Map<String, Object>>();
        for (var item : batch(workflow.get("rows"), 32)) {
            var path = new LinkedHashMap<>(object(item));
            var steps = new ArrayList<>();
            for (var raw : batch(path.get("steps"), 24)) {
                var step = new LinkedHashMap<>(object(raw));
                if (step.containsKey("procedure")) {
                    String original = id(step, "group"),
                            local =
                                    original.equals(pkg.get("source_group"))
                                            ? group
                                            : EnterpriseData.id(bindings.get(original));
                    CatalogueRouting.group(db, local);
                    step.put("group", local);
                    step.put(
                            "procedure",
                            key("portable-procedure", design, id(step, "procedure"))
                                    .substring(0, 32));
                    step.remove("procedure_revision");
                }
                steps.add(step);
            }
            path.put("steps", steps);
            rows.add(path);
        }
        if (op.equals("install_dependency")) {
            require(
                    get(db, "decision-release", design).get("revision").equals(0)
                            && get(db, "decision-draft", design).get("revision").equals(0),
                    "Choose an unused local design identifier");
            require(!rows.isEmpty(), "Supply a published decision table");
            int rev = 0;
            for (var path : rows) {
                var refs = new ArrayList<>();
                for (var step : objects(path.get("steps"))) {
                    if ("response".equals(step.get("kind")))
                        refs.add(map("response", step.get("title"), "stage", step.get("stage")));
                    else {
                        String proc = id(step, "procedure");
                        var existing = object(get(db, "access-procedure", proc).get("definition"));
                        var expected =
                                map(
                                        "kind",
                                        step.get("kind"),
                                        "title",
                                        step.get("title"),
                                        "group",
                                        step.get("group"));
                        require(
                                existing.isEmpty() || existing.equals(expected),
                                "Local procedure conflict");
                        if (existing.isEmpty())
                            AccessPolicies.run(
                                    store,
                                    db,
                                    "procedure",
                                    map(
                                            "procedure_id",
                                            proc,
                                            "group_id",
                                            step.get("group"),
                                            "title",
                                            step.get("title"),
                                            "kind",
                                            step.get("kind"),
                                            "revision",
                                            0));
                        refs.add(map("procedure_id", proc, "stage", step.get("stage")));
                    }
                }
                DecisionFlows.command(
                        db,
                        "path",
                        map(
                                "design_id",
                                design,
                                "row_id",
                                path.get("id"),
                                "condition",
                                path.get("condition"),
                                "operand",
                                path.get("operand"),
                                "title",
                                pkg.get("title"),
                                "days",
                                workflow.get("days"),
                                "steps",
                                refs,
                                "revision",
                                rev++));
            }
            for (var path : rows) {
                var test = object(object(workflow.get("tests")).get(path.get("id")));
                DecisionFlows.command(
                        db,
                        "test",
                        map(
                                "design_id",
                                design,
                                "row_id",
                                path.get("id"),
                                "amount",
                                test.get("amount"),
                                "risk",
                                test.get("risk"),
                                "location",
                                test.get("location"),
                                "revision",
                                rev++));
            }
            return DecisionFlows.command(db, "publish", map("design_id", design, "revision", rev));
        }
        var flow = object(get(db, "decision-release", design).get("definition"));
        var diff = map();
        var incoming = map("days", workflow.get("days"), "rows", semantic(rows));
        var local =
                map(
                        "days",
                        flow.get("days"),
                        "rows",
                        semantic(objects(flow.getOrDefault("rows", List.of()))));
        if (!incoming.equals(local))
            diff.put("workflow_dependency", map("incoming", incoming, "destination", local));
        var release = get(db, "intake-release", app);
        var active = object(release.get("definition"));
        var existing = active.isEmpty() ? List.of() : export(db, app).get("fields");
        if (!pkg.get("fields").equals(existing))
            diff.put("form_fields", map("incoming", pkg.get("fields"), "destination", existing));
        for(var field:objects(pkg.get("fields"))){
            var prior=object(object(active.getOrDefault("fields",map())).getOrDefault(field.get("name"),map()));
            require(!"confidential".equals(prior.get("visibility")) || "confidential".equals(field.get("visibility")),"Release exposes a confidential field");
        }
        var resolved=map();
        if(!workspace.isEmpty()){
            keys(workspace,"title","fields","steps");
            var names=new HashSet<>();for(var field:objects(pkg.get("fields")))names.add(field.get("name"));
            var columns=batch(workspace.get("fields"),32);require(names.containsAll(columns),"Workspace references unknown fields");
            var stages=new ArrayList<>();
            for(Object raw:batch(workspace.get("steps"),24)){
                var stage=object(raw);String original=id(stage,"group");
                String localGroup=original.equals(pkg.get("source_group"))?group:EnterpriseData.id(bindings.get(original));
                CatalogueRouting.group(db,localGroup);
                stages.add(map("id",id(stage,"id"),"revision",integer(stage.get("revision"),1,Integer.MAX_VALUE),"group",localGroup,"title",text(stage,"title",160),"instruction",text(stage,"instruction",1200)));
            }
            require(!stages.isEmpty(),"Provide a review stage");
            resolved=map("title",text(workspace,"title",160),"fields",columns,"steps",stages);
        }
        if(!resolved.equals(get(db,"guided-workspace",app).get("definition")))diff.put("workspace",resolved);
        if (op.equals("compare_package"))
            return map(
                    "changes",
                    diff,
                    "release_revision",
                    release.get("revision"),
                    "draft_revision",
                    get(db, "intake-draft", app).get("revision"),
                    "destination_group",
                    group,
                    "destination_design",
                    design);
        require(
                !diff.containsKey("workflow_dependency"),
                "Install or resolve the destination decision dependency first");
        ProblemInvestigations.revision(b.get("release_revision"), release.get("revision"));
        var draft=object(IntakeApps.command(
                db,
                "build",
                map(
                        "app_id",
                        app,
                        "title",
                        text(pkg, "title", 160),
                        "group_id",
                        group,
                        "design_id",
                        design,
                        "fields",
                        pkg.get("fields"),
                        "reviewed_base",
                        release.get("revision"),
                        "revision",
                        b.get("revision"))));
        if(resolved.isEmpty() && !diff.containsKey("workspace"))return draft;
        var data=object(draft.get("definition"));data.put("workspace",resolved);
        return put(db,"intake-draft",app,draft.get("revision"),data);
    }
}
