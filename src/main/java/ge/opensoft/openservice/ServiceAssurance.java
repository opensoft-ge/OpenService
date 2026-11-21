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
          new Entry("apps","register_claim_policy","Register a holder's insurance coverage and deductible","policy_id user_id manifest","admin"),
          new Entry("apps","import_case_payment","Import an identified payment from a finance statement","source reference manifest","admin"),
          new Entry("apps","reconcile_case_payment","Match a posted payment to an approved dispute or claim","case_id source reference revision","admin"),
          new Entry("topology","major_review_version","Retrieve a specific major incident report revision","incident_id revision","staff"),
          new Entry("apps","data_quality_rules","Set record quality rules for a published projection","new_id manifest revision","admin"),
          new Entry("apps","check_data_quality","Check a source revision and retain its quality evidence","new_id record_id check_id","all"),
          new Entry("apps","store_opening_plan","Configure a store checklist and safety team","site_id group_id safety_group_id title entries revision","admin"),
          new Entry("apps","start_store_opening","Start the store opening for a date","site_id effective","staff"),
          new Entry("apps","store_opening_status","Review store readiness, hazards and evidence","site_id effective","staff"),
          new Entry("apps","record_store_check","Record a passed check or report a hazard","site_id effective check_name decision evidence action_id revision","staff"),
          new Entry("apps","correct_store_hazard","Record corrective work and request a retest","site_id effective check_name evidence action_id revision","staff"),
          new Entry("apps","verify_store_hazard","Independently retest and release or reject the correction","site_id effective check_name decision evidence action_id revision","staff"),
          new Entry("apps","robot_pool","Configure an unattended permit robot pool","pool_id group_id revision","admin"),
          new Entry("apps","queue_robot_permit","Authorize unattended filing of this permit","pool_id job_id reference manifest","all"),
          new Entry("apps","claim_robot_job","Claim pending permit work for this robot","pool_id","staff"),
          new Entry("apps","execute_robot_job","Run the exact authorized permit job","pool_id job_id lease_id","staff"),
          new Entry("apps","robot_job_status","Inspect my permit automation receipt","pool_id job_id","all"),
          new Entry("apps","cancel_robot_job","Withdraw my pending permit automation","pool_id job_id","all"),
          new Entry("mapping","backfill_transaction_sample","Attach a delayed transaction measurement to a prior metric interval","objective_id location observed_at manifest","staff"),
          new Entry("mapping","review_metric_sample","Review late customer transaction evidence","objective_id observed_at","admin"),
          new Entry("mapping","reviewed_metric_score","Score observations including reviewed customer evidence","objective_id samples","admin"),
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
              "Submit a dispute, insurance loss, equipment turnaround or permit application",
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
      case "store_opening_plan","start_store_opening","store_opening_status","record_store_check",
          "correct_store_hazard","verify_store_hazard" -> StoreOpening.run(db,op,b);
      case "register_claim_policy","import_case_payment","reconcile_case_payment" -> ClaimPayments.run(db,op,b);
      case "major_review_version" -> {
        String incident=id(b,"incident_id");
        WorkTasks.record(db,"incident",incident);
        var report=db.one("SELECT * FROM os_major_report WHERE incident_id=? AND revision=?",incident,integer(b.get("revision"),1,Integer.MAX_VALUE));
        if (report==null) throw new Failure(404,"Major incident report revision unavailable");
        yield report;
      }
      case "robot_pool","queue_robot_permit","claim_robot_job","execute_robot_job","robot_job_status",
          "cancel_robot_job" -> RobotPools.run(db,op,b);
      case "backfill_transaction_sample" -> TransactionChecks.run(store,db,op,b);
      case "review_metric_sample","reviewed_metric_score" -> MetricAnomalies.run(store,db,op,b);
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
        var journal=definition(db, "workflow-attempt", attempt);
        journal.put("uncertain_write",get(db,"workflow-uncertain-write",attempt).get("definition"));
        journal.put("readback",get(db,"workflow-readback",attempt).get("definition"));
        yield journal;
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
      case "data_quality_rules","check_data_quality","publish_data_product", "read_data_product" -> DataProducts.run(db, op, b);
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
