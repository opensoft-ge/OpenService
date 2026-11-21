package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class DesignInputs {
    private DesignInputs() {}

    static String take(Map<String, Object> body, String key) {
        return Objects.toString(body.remove(key), "").strip();
    }

    static void read(Map<String, Object> page, Map<String, Object> body) {
        String rows = take(body, "designer_rows");
        if(rows.equals("bindings") && "Decision workflows".equals(page.get("title")) && "purchase_action".equals(body.get("operation"))) {
            require(!body.containsKey(rows),"Submit the named bindings once");
            var bindings=map();
            for(String key:List.of("model","quantity","unit_cost","months","salvage"))
                bindings.put(key,take(body,"bind_"+key));
            body.put("bindings",JSON.writeValueAsString(bindings));
            return;
        }
        if(rows.equals("risk_routes")){
            require("Decision workflows".equals(page.get("title"))&&"exception_design".equals(body.get("operation"))&&!body.containsKey("bindings"),"Choose the supplier exception design form");
            var routes=map();for(String risk:List.of("low","medium","high"))routes.put(risk,EnterpriseData.id(take(body,"route_"+risk)));
            body.put("bindings",JSON.writeValueAsString(routes));return;
        }
        boolean steps =
                rows.equals("steps")
                        && "Decision workflows".equals(page.get("title"))
                        && "path".equals(body.get("operation"));
        boolean fields =
                rows.equals("fields")
                        && "Intake applications".equals(page.get("title"))
                        && "build".equals(body.get("operation"));
        boolean approval =
                rows.equals("entries")
                        && "Decision workflows".equals(page.get("title"))
                        && "list_policy".equals(body.get("operation"));
        require(steps || fields || approval, "Choose a supported design form");
        require(!body.containsKey(rows), "Submit only one set of rows");
        var items = new ArrayList<Map<String, Object>>();
        for (int i = 1; i <= (steps ? 24 : 32); i++) {
            if (steps) {
                String kind = take(body, "kind_" + i),
                        item = take(body, "item_" + i),
                        stage = take(body, "stage_" + i);
                if (item.isEmpty() && stage.isEmpty()) continue;
                require(
                        Set.of("procedure_id", "response").contains(kind),
                        "Choose a workflow step type");
                items.add(map(kind, item, "stage", Service.number(stage, 1, 24)));
            } else if (approval) {
                String item = take(body, "item_" + i),
                        owner = take(body, "owner_" + i),
                        action = take(body, "action_" + i),
                        offering = take(body, "offering_" + i),
                        answer = take(body, "answer_" + i),
                        input = take(body, "input_" + i);
                if (item.isEmpty()
                        && owner.isEmpty() && action.isEmpty()
                        && offering.isEmpty()
                        && answer.isEmpty()
                        && input.isEmpty()) continue;
                require(
                        answer.isEmpty() == input.isEmpty(),
                        "Choose both fields for an answer mapping");
                var rule = map("item", item, "owner_id", owner, "offering_id", offering);
                if (!action.isEmpty()) rule.put("action_id",action);
                if (!answer.isEmpty()) rule.put("answers_from", map(answer, input));
                items.add(rule);
            } else {
                String name = take(body, "name_" + i),
                        label = take(body, "label_" + i),
                        visibility = take(body, "visibility_" + i);
                String reference = take(body, "reference_" + i);
                if (name.isEmpty() && label.isEmpty()) continue;
                var spec = map("name", name, "label", label, "visibility", visibility);
                if (!reference.isEmpty()) spec.put("reference_table", reference);
                items.add(spec);
            }
        }
        body.put(rows, JSON.writeValueAsString(items));
    }
}
