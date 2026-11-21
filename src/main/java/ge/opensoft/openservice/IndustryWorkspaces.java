package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class IndustryWorkspaces {
  private IndustryWorkspaces() {}

  static final List<Entry> COMMANDS =
      List.of(
          new Entry(
              "packages",
              "history_contract",
              "Register provider audit collection",
              "action_id alias path revision",
              "admin"),
          new Entry(
              "packages",
              "verify_effect",
              "Verify job against provider history",
              "job_id",
              "admin"),
          new Entry(
              "apps",
              "git_status",
              "Check deployment of a reviewed commit",
              "app_id commit digest",
              "admin"),
          new Entry(
              "apps",
              "git_stage",
              "Stage a reviewed Git artifact",
              "app_id commit digest group_id design_id bindings manifest revision release_revision",
              "admin"),
          new Entry(
              "apps",
              "git_publish",
              "Publish and record the reviewed artifact",
              "app_id commit digest",
              "admin"),
          new Entry(
              "apps",
              "review_stage",
              "Publish a reusable review stage",
              "stage_id group_id title note revision",
              "admin"),
          new Entry(
              "apps",
              "compose_workspace",
              "Bind fields and review stages",
              "app_id title fields steps revision",
              "admin"),
          new Entry("apps", "guided_queue", "My assigned review queue", "app_id", "staff"),
          new Entry(
              "apps",
              "bind_case_order",
              "Link an approved purchasing record",
              "case_id order_id revision",
              "admin"),
          new Entry("apps", "guide_case", "Open guided case", "case_id", "all"),
          new Entry(
              "apps",
              "advance_stage",
              "Advance or pause review",
              "case_id decision note revision",
              "staff"),
          new Entry(
              "mapping",
              "enroll_endpoint",
              "Bind endpoint identity to discovered component",
              "collector_id source resource_id platform user_id minutes",
              "admin"),
          new Entry(
              "mapping",
              "endpoint_report",
              "Report endpoint inventory",
              "collector_id sequence manifest",
              "staff"),
          new Entry(
              "mapping",
              "endpoint_status",
              "Review endpoint and cloud observations",
              "collector_id",
              "admin"),
          new Entry(
              "mapping",
              "endpoint_seat",
              "Allocate a seat to this endpoint owner",
              "collector_id license_id user_id revision",
              "admin"),
          new Entry(
              "mapping",
              "retire_endpoint",
              "Retire endpoint and release its seat",
              "collector_id note revision",
              "admin"),
          new Entry(
              "assistant",
              "confluence_reader",
              "Bind delegated Confluence identity",
              "cloud_id user_id alias account_id revision",
              "admin"),
          new Entry(
              "assistant",
              "index_confluence",
              "Index a Confluence page",
              "cloud_id intent_id page_id group_id alias revision",
              "admin"),
          new Entry(
              "assistant",
              "refresh_confluence",
              "Refresh indexed Confluence policy and groups",
              "intent_id",
              "admin"),
          new Entry(
              "mapping",
              "transaction_rule",
              "Set checkout latency and content objective",
              "objective_id expected limit revision",
              "admin"),
          new Entry(
              "mapping",
              "transaction_sample",
              "Report measured checkout response",
              "objective_id location observed_at manifest",
              "staff"),
          new Entry(
              "mapping",
              "transaction_evidence",
              "Inspect checkout measurements",
              "objective_id",
              "admin"),
          new Entry(
              "routing",
              "contract_company",
              "Approve a contractor company and dispatcher",
              "group_id user_id active revision",
              "admin"),
          new Entry(
              "routing",
              "offer_repair",
              "Offer or reassign an entitled repair",
              "task_id case_id asset_id entries expires note revision",
              "admin"),
          new Entry(
              "routing", "contract_queue", "Review available offers and assignments", "", "all"),
          new Entry(
              "routing",
              "accept_repair",
              "Accept an unexpired repair offer",
              "task_id group_id revision",
              "all"),
          new Entry(
              "routing",
              "assign_repair",
              "Assign a contracted technician",
              "task_id technician_id revision",
              "all"),
          new Entry(
              "routing", "repair_packet", "Download assigned repair information", "task_id", "all"),
          new Entry(
              "routing",
              "repair_sync",
              "Sync offline contractor completion",
              "task_id new_id amount asset_id note revision",
              "all"),
          new Entry(
              "routing",
              "sign_repair",
              "Sign completed repair",
              "task_id reference revision",
              "all"),
          new Entry(
              "routing",
              "settle_repair",
              "Match a contractor invoice",
              "task_id reference revision",
              "admin"),
          new Entry(
              "sales",
              "network_port",
              "Register network capacity",
              "port_id service_ci capacity title",
              "admin"),
          new Entry(
              "sales",
              "reserve_circuit",
              "Reserve an ordered service path",
              "bundle_id location quantity entries",
              "admin"),
          new Entry(
              "sales",
              "activate_circuit",
              "Activate ordered circuit",
              "bundle_id location revision",
              "admin"),
          new Entry(
              "sales",
              "circuit_status",
              "Inspect site service path",
              "bundle_id location",
              "staff"),
          new Entry(
              "sales",
              "port_condition",
              "Set port condition and trace customer impact",
              "port_id state revision",
              "admin"),
          new Entry(
              "sales",
              "issue_warranty",
              "Register dealer warranty terms",
              "asset_id start_on finish_on user_id owner_id finance_id currency amount revision",
              "admin"),
          new Entry("sales", "my_claims", "My dealer warranty claims", "", "all"),
          new Entry(
              "sales",
              "file_claim",
              "File dealer claim",
              "asset_id reference effective note",
              "all"),
          new Entry(
              "sales", "claim_status", "View assigned warranty claim", "asset_id reference", "all"),
          new Entry(
              "sales",
              "authorize_repair",
              "Authorize a dealer repair",
              "asset_id reference note revision",
              "staff"),
          new Entry(
              "sales",
              "submit_repair_cost",
              "Submit parts and labor",
              "asset_id reference parts labor returned_asset revision",
              "all"),
          new Entry(
              "sales",
              "adjudicate_claim",
              "Approve adjusted warranty claim",
              "asset_id reference amount note revision",
              "staff"),
          new Entry(
              "sales",
              "credit_claim",
              "Issue dealer credit",
              "asset_id reference revision",
              "staff"),
          new Entry(
              "audits",
              "notice_revision",
              "Publish a revised regulatory notice",
              "notice_id title reference effective entries revision",
              "admin"),
          new Entry(
              "audits",
              "notice_history",
              "Reproduce reviewed revision",
              "notice_id revision",
              "admin"),
          new Entry("audits", "notice_assignments", "View my regulatory assignments", "", "staff"),
          new Entry(
              "audits",
              "assess_impact",
              "assess impact",
              "notice_id target decision evidence revision",
              "staff"),
          new Entry(
              "audits",
              "remediate_impact",
              "remediate impact",
              "notice_id target decision evidence revision",
              "staff"),
          new Entry(
              "audits",
              "test_impact",
              "test impact",
              "notice_id target decision evidence revision",
              "staff"));

  static Object command(Store store, Store.Db db, String op, Map<String, Object> b)
      throws Exception {
    return switch (op) {
      case "history_contract", "verify_effect" -> ProviderHistory.run(store, db, op, b);
      case "git_stage", "git_publish", "git_status" -> GitReleases.run(store, db, op, b);
      case "review_stage",
              "compose_workspace",
              "guided_queue",
              "bind_case_order",
              "guide_case",
              "advance_stage" ->
          GuidedWorkspaces.run(db, op, b);
      case "enroll_endpoint",
              "endpoint_report",
              "endpoint_status",
              "endpoint_seat",
              "retire_endpoint" ->
          EndpointCollectors.run(db, op, b);
      case "confluence_reader", "index_confluence", "refresh_confluence" ->
          ConfluencePolicies.configure(store, db, op, b);
      case "transaction_rule", "transaction_sample", "transaction_evidence" ->
          TransactionChecks.run(store, db, op, b);
      case "contract_company",
              "offer_repair",
              "contract_queue",
              "accept_repair",
              "assign_repair",
              "repair_packet",
              "repair_sync",
              "sign_repair",
              "settle_repair" ->
          ContractorRepairs.run(db, op, b);
      case "network_port",
              "reserve_circuit",
              "activate_circuit",
              "circuit_status",
              "port_condition" ->
          NetworkCircuits.run(db, op, b);
      case "my_claims",
              "issue_warranty",
              "file_claim",
              "claim_status",
              "authorize_repair",
              "submit_repair_cost",
              "adjudicate_claim",
              "credit_claim" ->
          WarrantyClaims.run(db, op, b);
      case "notice_revision",
              "notice_history",
              "notice_assignments",
              "assess_impact",
              "remediate_impact",
              "test_impact" ->
          RegulatoryNotices.run(db, op, b);
      default -> throw new Failure(404, "Unknown workspace command");
    };
  }
}
