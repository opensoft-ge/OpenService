package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

final class ServiceAssurance {
  private ServiceAssurance() {}

  static final List<Entry> COMMANDS =
      List.of(
          new Entry(
              "licenses",
              "license_fact",
              "Import a dated publisher entitlement, credit or installation",
              "product reference effective manifest",
              "admin"),
          new Entry(
              "licenses",
              "license_position",
              "Freeze the license position for a reporting date",
              "product effective new_id",
              "admin"),
          new Entry(
              "releases",
              "track_delivery",
              "Associate a production pipeline with a delivery team",
              "group_id release_id",
              "admin"),
          new Entry(
              "releases",
              "delivery_event",
              "Attach source evidence to a deployment",
              "group_id release_id kind reference observed_at",
              "admin"),
          new Entry(
              "releases",
              "delivery_performance",
              "Calculate delivery metrics and inspect contributing records",
              "group_id start end",
              "admin"),
          new Entry(
              "mapping",
              "metric_baseline",
              "Train hourly metric ranges",
              "objective_id samples revision",
              "admin"),
          new Entry(
              "mapping",
              "metric_sample",
              "Classify service measurements",
              "objective_id observed_at manifest",
              "admin"),
          new Entry(
              "mapping",
              "metric_status",
              "Inspect latest service measurements and collector freshness",
              "objective_id",
              "admin"),
          new Entry(
              "mapping",
              "metric_score",
              "Score labelled observations against stored classifications",
              "objective_id samples",
              "admin"),
          new Entry(
              "continuity",
              "business_tolerance",
              "Define business dependencies, fallback and disruption tolerance",
              "plan_id manifest revision",
              "admin"),
          new Entry(
              "continuity",
              "business_impact",
              "Record observed customer disruption and backlog",
              "exercise_id restored_at quantity evidence revision",
              "admin"),
          new Entry(
              "packages",
              "worker_evidence",
              "Show the original worker, pinned connection and recovery evidence",
              "job_id",
              "admin"),
          new Entry(
              "releases",
              "propose_fix",
              "Connect a recurring problem and its workaround to a release",
              "problem_id release_id objective_id minutes",
              "admin"),
          new Entry(
              "releases",
              "verify_fix",
              "Check deployment, health and recurrence",
              "problem_id release_id",
              "admin"),
          new Entry(
              "assistant",
              "resume_search",
              "Refresh permissions on the pending suggestion",
              "new_id",
              "all"),
          new Entry(
              "assistant",
              "weighted_search",
              "Find eligible offerings using weighted search",
              "new_id question",
              "all"),
          new Entry(
              "mapping",
              "intune_authority",
              "Choose a cloud source keyed by Entra device ID",
              "alias source revision",
              "admin"),
          new Entry(
              "mapping",
              "intune_import",
              "Start or resume a Microsoft Graph device inventory",
              "alias new_id",
              "admin"),
          new Entry(
              "mapping",
              "intune_bind",
              "Reconcile a managed device with its verified component",
              "alias reference source resource_id revision",
              "admin"),
          new Entry(
              "mapping",
              "intune_device",
              "Inspect the current managed device and its source facts",
              "alias reference",
              "admin"),
          new Entry(
              "designer",
              "exception_requisition",
              "Record supplier lines on an open purchase request",
              "record_id supplier manifest revision",
              "admin"),
          new Entry(
              "designer",
              "exception_design",
              "Compose risk-based review workflows and a purchasing action",
              "design_id bindings action_id revision",
              "admin"),
          new Entry(
              "designer",
              "start_exception",
              "Start a supplier review for the submitted purchase lines",
              "design_id new_id user_id record_id amount risk location",
              "admin"),
          new Entry(
              "designer",
              "advance_exception",
              "Resume fulfillment using the original purchase inputs",
              "new_id",
              "admin"),
          new Entry(
              "designer",
              "cancel_exception",
              "Stop remaining work and assign reversal review",
              "new_id owner_id note",
              "admin"),
          new Entry(
              "sales",
              "backup_path",
              "Reserve independent standby capacity for this circuit",
              "bundle_id location entries revision",
              "admin"),
          new Entry(
              "sales",
              "network_alarm",
              "Process an alarm against both service paths",
              "port_id reference state revision",
              "admin"),
          new Entry(
              "sales",
              "circuit_probe",
              "Bind a site transaction objective",
              "bundle_id location objective_id revision",
              "admin"),
          new Entry(
              "sales",
              "assure_site",
              "Confirm end-to-end restoration of an affected site",
              "bundle_id location objective_id",
              "admin"));

  static Map<String, Object> once(Store.Db db, String area, String key, Map<String, Object> data)
      throws Exception {
    var row = get(db, area, key);
    if (row.get("revision").equals(0)) return put(db, area, key, 0, data);
    require(
        data.equals(row.get("definition")), "The evidence reference already has a different value");
    return row;
  }

  static Object run(Store store, Store.Db db, String op, Map<String, Object> b) throws Exception {
    return switch (op) {
      case "license_fact", "license_position" -> PublisherPosition.run(db, op, b);
      case "track_delivery", "delivery_event", "delivery_performance" ->
          DeliveryPerformance.run(db, op, b);
      case "metric_status", "metric_baseline", "metric_sample", "metric_score" ->
          MetricAnomalies.run(store, db, op, b);
      case "business_tolerance", "business_impact" -> BusinessTolerance.run(db, op, b);
      case "worker_evidence" -> ConnectorFailover.evidence(db, id(b, "job_id"));
      case "propose_fix", "verify_fix" -> PermanentFixes.run(db, op, b);
      case "resume_search", "weighted_search" -> EmployeeSearchReview.run(store, db, op, b);
      case "intune_authority", "intune_import", "intune_bind", "intune_device" ->
          IntuneInventory.run(store, db, op, b);
      case "exception_requisition",
              "exception_design",
              "start_exception",
              "advance_exception",
              "cancel_exception" ->
          SupplierExceptions.run(store, db, op, b);
      case "circuit_probe", "backup_path", "network_alarm", "assure_site" ->
          CircuitAssurance.run(store, db, op, b);
      default -> throw new Failure(404, "Unknown operation");
    };
  }
}
