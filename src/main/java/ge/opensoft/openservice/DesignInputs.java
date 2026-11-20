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
        boolean steps =
                rows.equals("steps")
                        && "Decision workflows".equals(page.get("title"))
                        && "path".equals(body.get("operation"));
        boolean fields =
                rows.equals("fields")
                        && "Intake applications".equals(page.get("title"))
                        && "build".equals(body.get("operation"));
        require(steps || fields, "Choose a supported design form");
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
            } else {
                String name = take(body, "name_" + i),
                        label = take(body, "label_" + i),
                        visibility = take(body, "visibility_" + i);
                if (name.isEmpty() && label.isEmpty()) continue;
                items.add(map("name", name, "label", label, "visibility", visibility));
            }
        }
        body.put(rows, JSON.writeValueAsString(items));
    }
}
