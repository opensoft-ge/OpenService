package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

/** Typed purchase actions snapshot supplier, currency and field bindings with each approval run. */
final class PurchasingActions {
  private PurchasingActions() {}

  static Object publish(Store.Db db, Map<String, Object> b) throws Exception {
    var fields = object(b.get("bindings"));
    keys(fields, "model", "quantity", "unit_cost", "months", "salvage");
    for (Object name : fields.values()) RestPackages.field(name);
    return put(
        db,
        "purchase-action",
        id(b, "action_id"),
        b.get("revision"),
        map(
            "supplier",
            text(b, "supplier", 160),
            "currency",
            CloudSpend.currency(b),
            "bindings",
            fields,
            "input",
            "approved purchase line",
            "output",
            "purchase.order_id",
            "on_failure",
            "retry_line"));
  }

  static Object hold(Store.Db db, Map<String, Object> b) throws Exception {
    String action = id(b, "action_id");
    definition(db, "purchase-action", action);
    require(b.get("active") instanceof Boolean, "Choose whether the hold is active");
    return put(
        db,
        "purchase-action-hold",
        action,
        b.get("revision"),
        map("held", b.get("active"), "reason", text(b, "note", 400), "by", db.viewerId));
  }

  static String order(Store.Db db, String run, String label, Map<String, Object> line)
      throws Exception {
    var hold =
        object(get(db, "purchase-action-hold", line.get("action_id").toString()).get("definition"));
    require(!Boolean.TRUE.equals(hold.get("held")), "Purchasing paused for review");
    var action = object(line.get("action"));
    var input = object(line.get("input"));
    String orderId = key("purchase-action", run, label).substring(0, 32);
    var order =
        map(
            "operation",
            "order",
            "key",
            orderId,
            "supplier",
            action.get("supplier"),
            "currency",
            action.get("currency"));
    for (var field : object(action.get("bindings")).entrySet())
      order.put(field.getKey(), input.get(field.getValue()));
    AssetPlanning.command(db, order);
    return orderId;
  }
}
