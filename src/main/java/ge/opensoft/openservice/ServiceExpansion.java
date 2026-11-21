package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class ServiceExpansion {
  private ServiceExpansion() {}

  static final List<Entry> COMMANDS =
      List.of(
          new Entry(
              "packages",
              "rejected_changes",
              "Review rejected change rows",
              "package_id environment",
              "admin"),
          new Entry(
              "packages",
              "correct_change",
              "Correct and replay a rejected change",
              "package_id environment cursor manifest",
              "admin"),
          new Entry(
              "designer",
              "hold_purchase_action",
              "Pause or resume purchase order creation",
              "action_id active note revision",
              "admin"),
          new Entry(
              "designer",
              "purchase_action",
              "Publish a typed purchase order action",
              "action_id supplier currency bindings revision",
              "admin"),
          new Entry(
              "mapping",
              "define_objective",
              "Set a two-location availability objective",
              "objective_id source resource_id location backup_location roster_id minutes"
                  + " target_bps",
              "admin"),
          new Entry(
              "mapping",
              "probe_identity",
              "Assign an independent location identity",
              "objective_id location user_id revision",
              "admin"),
          new Entry(
              "mapping",
              "probe_sample",
              "Submit a location observation",
              "objective_id location result observed_at",
              "staff"),
          new Entry(
              "mapping",
              "objective_report",
              "Inspect the 30-day error budget and samples",
              "objective_id",
              "admin"),
          new Entry(
              "assistant",
              "index_external",
              "Refresh an external policy and group permissions",
              "intent_id alias path bindings revision",
              "admin"),
          new Entry(
              "suppliers",
              "offer_terms",
              "Offer dated commercial terms",
              "supplier user_id currency effective notice note manifest revision",
              "staff"),
          new Entry(
              "suppliers",
              "supplier_response",
              "Respond to a contract offer",
              "supplier decision note revision",
              "all"),
          new Entry(
              "suppliers", "supplier_offer", "Read my pending contract offer", "supplier", "all"),
          new Entry(
              "suppliers",
              "sign_terms",
              "Approve and file the supplier-accepted terms",
              "supplier reference revision",
              "staff"),
          new Entry(
              "suppliers",
              "contract_decision",
              "Record a renewal or termination notice",
              "supplier decision effective note revision",
              "staff"),
          new Entry(
              "suppliers",
              "agreement_history",
              "Inspect the agreement and signed versions",
              "supplier",
              "staff"),
          new Entry(
              "finance",
              "funded_epic",
              "Create an epic against approved funding",
              "epic_id goal_id project_id title",
              "admin"),
          new Entry(
              "finance",
              "delivery_story",
              "Add a team story and its predecessors",
              "epic_id story_id group_id title quantity entries",
              "admin"),
          new Entry(
              "finance",
              "schedule_sprint",
              "Plan using team calendars and project reservations",
              "epic_id sprint_id title start_on finish_on manifest entries",
              "admin"),
          new Entry(
              "finance",
              "commit_sprint",
              "Commit stories against team capacity",
              "epic_id sprint_id title manifest entries",
              "admin"),
          new Entry(
              "finance",
              "ship_story",
              "Verify the deployment that delivered a story",
              "epic_id sprint_id story_id release_id revision",
              "admin"),
          new Entry(
              "finance",
              "close_sprint",
              "Close a sprint and return unfinished work",
              "epic_id sprint_id revision",
              "admin"),
          new Entry(
              "finance",
              "sprint_report",
              "Compare the commitment and delivered work",
              "epic_id sprint_id",
              "admin"),
          new Entry(
              "suppliers",
              "privacy_screen",
              "Screen a processing activity",
              "assessment_id supplier application_id user_id categories purpose days question risk"
                  + " revision",
              "all"),
          new Entry(
              "suppliers",
              "privacy_question",
              "View my assigned privacy question",
              "assessment_id",
              "all"),
          new Entry(
              "suppliers",
              "privacy_answer",
              "Submit my supplier answer",
              "assessment_id answer revision",
              "all"),
          new Entry(
              "suppliers",
              "privacy_decide",
              "Record an independent privacy decision",
              "assessment_id decision owner_id note revision",
              "staff"),
          new Entry(
              "suppliers",
              "privacy_correct",
              "Submit corrective evidence for review",
              "assessment_id evidence revision",
              "staff"),
          new Entry(
              "suppliers",
              "privacy_history",
              "Open an assessment revision",
              "assessment_id revision",
              "staff"),
          new Entry(
              "mapping",
              "discover_certificate",
              "Register a certificate observation",
              "certificate endpoint source service_ci owner_id",
              "admin"),
          new Entry(
              "mapping",
              "renew_certificate",
              "Register a replacement for verification",
              "fingerprint certificate revision",
              "staff"),
          new Entry(
              "mapping",
              "verify_certificate",
              "Verify a deployed endpoint certificate",
              "fingerprint endpoint observed_at certificate revision",
              "admin"),
          new Entry(
              "mapping",
              "certificate_status",
              "Review service and endpoint renewal evidence",
              "fingerprint",
              "admin"),
          new Entry(
              "routing",
              "maintenance_plan",
              "Schedule recurrent maintenance at a site",
              "plan_id case_id group_id stockroom_id model location skills technician_id start days"
                  + " minutes revision",
              "admin"),
          new Entry(
              "routing",
              "maintenance_status",
              "Inspect scheduled work and installation status",
              "plan_id start",
              "staff"),
          new Entry(
              "routing",
              "maintenance_dispatch",
              "Source a part and schedule the maintenance visit",
              "plan_id start new_start",
              "staff"),
          new Entry(
              "walkup",
              "open_desk",
              "Set desk capacity and appointment interval",
              "location capacity minutes revision",
              "admin"),
          new Entry("walkup", "desk_compare", "Compare all desk waits and outcomes", "", "staff"),
          new Entry(
              "walkup", "desk_status", "View the desk queue and wait times", "location", "staff"),
          new Entry(
              "walkup",
              "join_desk",
              "Book an appointment or join the walk-in queue",
              "visit_id location mode start note",
              "all"),
          new Entry("walkup", "my_visit", "Read my visit and equipment receipt", "visit_id", "all"),
          new Entry(
              "walkup", "desk_arrival", "Check in for my appointment", "visit_id revision", "all"),
          new Entry(
              "walkup",
              "desk_no_show",
              "Record a missed appointment",
              "visit_id revision",
              "staff"),
          new Entry(
              "walkup",
              "desk_transfer",
              "Transfer a waiting visit",
              "visit_id location revision",
              "staff"),
          new Entry("walkup", "desk_claim", "Claim a waiting visit", "visit_id revision", "staff"),
          new Entry(
              "walkup",
              "desk_complete",
              "Complete the laptop swap and issue a receipt",
              "visit_id old_asset new_asset stockroom_id revision",
              "staff"));

  static Object command(Store store, Store.Db db, String op, Map<String, Object> b)
      throws Exception {
    return switch (op) {
      case "rejected_changes" -> ImportReceipts.rejected(db, b);
      case "correct_change" -> ImportReceipts.repair(db, b);
      case "hold_purchase_action" -> PurchasingActions.hold(db, b);
      case "purchase_action" -> PurchasingActions.publish(db, b);
      case "define_objective", "probe_identity", "probe_sample", "objective_report" ->
          ReliabilityObjectives.run(store, db, op, b);
      case "index_external" -> ExternalPolicies.refresh(store, db, b);
      case "offer_terms",
              "supplier_response",
              "supplier_offer",
              "sign_terms",
              "contract_decision",
              "agreement_history" ->
          SupplierContracts.command(db, op, b);
      case "schedule_sprint" -> SprintDelivery.schedule(db, b);
      case "funded_epic",
              "delivery_story",
              "commit_sprint",
              "ship_story",
              "close_sprint",
              "sprint_report" ->
          SprintDelivery.command(db, op, b);
      case "privacy_screen",
              "privacy_question",
              "privacy_answer",
              "privacy_decide",
              "privacy_correct",
              "privacy_history" ->
          PrivacyAssessments.command(db, op, b);
      case "discover_certificate",
              "renew_certificate",
              "verify_certificate",
              "certificate_status" ->
          CertificateRenewals.command(db, op, b);
      case "maintenance_plan", "maintenance_status", "maintenance_dispatch" ->
          PreventiveMaintenance.command(db, op, b);
      case "open_desk",
              "desk_compare",
              "desk_status",
              "join_desk",
              "my_visit",
              "desk_arrival",
              "desk_no_show",
              "desk_transfer",
              "desk_claim",
              "desk_complete" ->
          WalkupDesk.command(db, op, b);
      default -> throw new Failure(404, "Unknown workspace operation");
    };
  }
}
