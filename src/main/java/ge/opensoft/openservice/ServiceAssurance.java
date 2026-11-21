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
              "apps",
              "register_disputable_transaction",
              "Register a posted transaction for its account holder",
              "reference user_id amount currency",
              "admin"),
          new Entry(
              "apps",
              "equipment_readiness",
              "Inspect operational readiness for a visible device",
              "record_id",
              "staff"),
          new Entry(
              "apps",
              "open_attended_batch",
              "Review or resume an attended case-entry batch",
              "new_id entries",
              "all"),
          new Entry(
              "apps",
              "attended_batch_status",
              "Inspect receipts for my automation batch",
              "new_id",
              "all"),
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
              "packages",
              "provider_attempt",
              "Inspect the exact provider attempt used for audit qualification",
              "run_id step sequence",
              "admin"),
          new Entry(
              "mapping",
              "metric_corroborated_baseline",
              "Train hourly ranges with corroborated paging",
              "objective_id samples revision",
              "admin"),
          new Entry(
              "assistant",
              "policy_search",
              "Search eligible policies and service terminology",
              "new_id question",
              "all"),
          new Entry(
              "assistant",
              "search_dictionary",
              "Publish equivalent terms for service search",
              "entries revision",
              "admin"),
          new Entry(
              "apps",
              "case_program",
              "Set the fulfillment team for an industry service",
              "kind group_id revision",
              "admin"),
          new Entry(
              "apps",
              "file_industry_case",
              "Submit a dispute, equipment turnaround or permit application",
              "kind new_id reference manifest",
              "all"),
          new Entry(
              "apps", "industry_case", "View a case and its decision history", "new_id", "all"),
          new Entry(
              "apps", "industry_queue", "My team's outstanding industry cases", "kind", "staff"),
          new Entry(
              "apps",
              "advance_industry_case",
              "Record a case decision and its evidence",
              "new_id decision evidence revision",
              "staff"),
          new Entry(
              "apps",
              "publish_data_product",
              "Publish a scoped live data product",
              "new_id title table fields group_id revision",
              "admin"),
          new Entry(
              "apps",
              "read_data_product",
              "Read permitted fields from a data product",
              "new_id record_id",
              "all"),
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
      case "metric_status",
              "metric_corroborated_baseline",
              "metric_baseline",
              "metric_sample",
              "metric_score" ->
          MetricAnomalies.run(store, db, op, b);
      case "business_tolerance", "business_impact" -> BusinessTolerance.run(db, op, b);
      case "register_disputable_transaction", "equipment_readiness" -> IndustryCases.run(db, op, b);
      case "open_attended_batch", "attended_batch_status" -> AttendedBatches.run(db, op, b);
      case "provider_attempt" -> {
        String attempt =
            ConnectorSynchronization.hash(
                List.of(
                    id(b, "run_id"),
                    integer(b.get("step"), 1, 100),
                    integer(b.get("sequence"), 0, Integer.MAX_VALUE)));
        yield definition(db, "workflow-attempt", attempt);
      }
      case "worker_evidence" -> ConnectorFailover.evidence(db, id(b, "job_id"));
      case "propose_fix", "verify_fix" -> PermanentFixes.run(db, op, b);
      case "case_program",
              "file_industry_case",
              "industry_case",
              "industry_queue",
              "advance_industry_case" ->
          IndustryCases.run(db, op, b);
      case "policy_search" -> ServiceSearch.command(store, db, op, b);
      case "search_dictionary" -> PolicyTerms.publish(db, b);
      case "publish_data_product", "read_data_product" -> DataProducts.run(db, op, b);
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
