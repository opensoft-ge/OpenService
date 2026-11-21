package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class DataQuality {
  private DataQuality() {}

  static Object configure(
      Store.Db db, String product, Map<String, Object> publication, Map<String, Object> body)
      throws Exception {
    var spec = JourneyForms.definition(db, "data-product", product);
    var checks = new TreeMap<String, Object>();
    for (var rule : object(body.get("manifest")).entrySet()) {
      require(((List<?>) spec.get("fields")).contains(rule.getKey()), "Choose a published field");
      var allowed = new TreeSet<String>();
      for (Object value : batch(rule.getValue(), 32)) allowed.add(WorkJobs.text(value, 160));
      checks.put(rule.getKey(), List.copyOf(allowed));
    }
    require(!checks.isEmpty(), "Add at least one required field");
    return DeliveryWorkspace.put(
        db,
        "data-quality-rules",
        product,
        body.get("revision"),
        map("product_revision", publication.get("revision"), "checks", checks));
  }

  static Object check(
      Store.Db db,
      String product,
      Map<String, Object> publication,
      Map<String, Object> schema,
      Map<String, Object> source,
      Map<String, Object> data,
      Map<String, Object> body)
      throws Exception {
    String checkId = JourneyForms.id(body, "check_id"), id = JourneyForms.id(body, "record_id");
    String ref = DeliveryWorkspace.key(product, checkId);
    var prior = get(db, "data-quality-observation", ref);
    var observation = object(prior.get("definition"));
    var spec = object(publication.get("definition"));
    var rules = get(db, "data-quality-rules", product);
    boolean recorded = !observation.isEmpty();
    Map<String, Object> checks;
    if (recorded) {
      require(
          id.equals(observation.get("source_record"))
              && spec.get("table").equals(observation.get("source_table")),
          "Quality observation applies to another source");
      checks = object(observation.get("rules"));
    } else {
      var policy = object(rules.get("definition"));
      require(
          publication.get("revision").equals(policy.get("product_revision")),
          "Publish quality rules for this product revision");
      checks = object(policy.get("checks"));
    }
    var failures = new TreeMap<String, String>();
    for (var rule : checks.entrySet()) {
      String field = rule.getKey();
      if (!((List<?>) spec.get("fields")).contains(field)
          || !FieldAccess.allowed(db, schema, field))
        throw new Failure(404, "Quality observation unavailable");
      String value = Objects.toString(data.get(field), "").strip();
      if (value.isEmpty()) failures.put(field, "missing");
      else if (!((List<?>) rule.getValue()).isEmpty()
          && !((List<?>) rule.getValue()).contains(value))
        failures.put(field, "outside_allowed_values");
    }
    if (recorded) return prior;
    return DeliveryWorkspace.put(
        db,
        "data-quality-observation",
        ref,
        0,
        map(
            "product",
            product,
            "product_revision",
            publication.get("revision"),
            "source_table",
            spec.get("table"),
            "source_record",
            id,
            "source_revision",
            source.get("revision"),
            "rule_revision",
            rules.get("revision"),
            "rules",
            checks,
            "checked_at",
            now(),
            "outcome",
            failures.isEmpty() ? "passed" : "failed",
            "failures",
            failures));
  }
}
