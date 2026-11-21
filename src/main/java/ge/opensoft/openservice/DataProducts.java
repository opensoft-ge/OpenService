package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class DataProducts {
  private DataProducts() {}

  static Object run(Store.Db db, String operation, Map<String, Object> body) throws Exception {
    String product = JourneyForms.id(body, "new_id");
    if (operation.equals("publish_data_product")) {
      String table = JourneyForms.text(body, "table", 80);
      String group = JourneyForms.id(body, "group_id");
      CatalogueRouting.group(db, group);
      var schema = object(Service.schema(db, table).get("fields"));
      var selection = new LinkedHashSet<String>();
      for (Object raw : batch(body.get("fields"), 32)) {
        String field = WorkJobs.text(raw, 80);
        require(schema.containsKey(field) && selection.add(field), "Choose distinct source fields");
      }
      require(!selection.isEmpty(), "Choose at least one field");
      return DeliveryWorkspace.put(
          db,
          "data-product",
          product,
          body.get("revision"),
          map(
              "title",
              JourneyForms.text(body, "title", 160),
              "table",
              table,
              "fields",
              List.copyOf(selection),
              "group",
              group));
    }
    var current = get(db, "data-product", product);
    var spec = object(current.get("definition"));
    if (operation.equals("data_quality_rules")) return DataQuality.configure(db,product,current,body);
    if (db.count(
            "SELECT COUNT(*) FROM os_group_member WHERE group_id=? AND user_id=?",
            spec.get("group"),
            db.viewerId)
        == 0) throw new Failure(404, "Data product unavailable");
    String table = spec.get("table").toString(), id = JourneyForms.id(body, "record_id");
    var source = Service.readRecord(db, table, id);
    var schema = Service.schema(db, table);
    var visible = FieldAccess.visible(db, schema, object(source.get("data")));
    visible.keySet().retainAll(object(schema.get("fields")).keySet());
    visible.keySet().retainAll((List<?>) spec.get("fields"));
    if (operation.equals("check_data_quality")) return DataQuality.check(db,product,current,schema,source,visible,body);
    return map(
        "product",
        product,
        "product_revision",
        current.get("revision"),
        "source_table",
        table,
        "source_record",
        id,
        "source_revision",
        source.get("revision"),
        "fields",
        visible);
  }
}
