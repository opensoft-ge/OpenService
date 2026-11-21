package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class EnterpriseOperations {
    private EnterpriseOperations() {}

    static final List<Entry> COMMANDS =
            List.of(
                    new Entry(
                            "packages",
                            "schedule_delta",
                            "Schedule a checkpointed import",
                            "package_id environment cursor_input cursor_field cursor manifest"
                                    + " minutes active revision",
                            "admin"),
                    new Entry(
                            "packages",
                            "read_change",
                            "Read an imported provider record",
                            "package_id environment cursor",
                            "admin"),
                    new Entry(
                            "packages",
                            "delta_status",
                            "Review the durable import checkpoint",
                            "package_id environment",
                            "admin"),
                    new Entry(
                            "designer",
                            "list_policy",
                            "Publish a reusable item approval policy",
                            "policy_id title entries revision",
                            "admin"),
                    new Entry(
                            "designer",
                            "start_list",
                            "Start a record list approval",
                            "new_id policy_id source record_id manifest",
                            "admin"),
                    new Entry(
                            "designer",
                            "list_decision",
                            "Decide your assigned list items",
                            "new_id decision revision",
                            "staff"),
                    new Entry(
                            "designer",
                            "review_list",
                            "Review your assigned approval inputs",
                            "new_id",
                            "staff"),
                    new Entry(
                            "designer",
                            "list_status",
                            "Inspect the list inputs and receipts",
                            "new_id",
                            "admin"),
                    new Entry(
                            "designer",
                            "fulfill_line",
                            "Fulfill one approved list item",
                            "new_id item manifest revision",
                            "admin"),
                    new Entry(
                            "apps",
                            "tested_promote",
                            "Promote the tested app release",
                            "app_id revision release_revision",
                            "admin"),
                    new Entry(
                            "apps",
                            "release_test",
                            "Configure release access tests",
                            "app_id requester reviewer outsider private_field revision",
                            "admin"),
                    new Entry(
                            "releases",
                            "cab_schedule",
                            "Propose a release window",
                            "release_id roster_id start end note revision",
                            "admin"),
                    new Entry(
                            "releases",
                            "cab_agenda",
                            "Review conflicts and on-call coverage",
                            "release_id",
                            "admin"),
                    new Entry(
                            "releases",
                            "cab_history",
                            "Review the board decision trail",
                            "release_id",
                            "admin"),
                    new Entry(
                            "releases",
                            "cab_approve",
                            "Approve the release window",
                            "release_id note revision",
                            "admin"),
                    new Entry(
                            "releases",
                            "deployment_gate",
                            "Verify the artifact and window before deployment",
                            "release_id artifact",
                            "staff"),
                    new Entry(
                            "releases",
                            "checked_outcome",
                            "Submit the scheduled artifact and deployment result",
                            "release_id revision artifact result reference",
                            "staff"),
                    new Entry(
                            "mapping",
                            "cloud_import",
                            "Import AWS usage charges and credits",
                            "account month currency source entries",
                            "admin"),
                    new Entry(
                            "mapping",
                            "cloud_budget",
                            "Set a monthly cloud service budget",
                            "account month currency service owner_id amount revision",
                            "admin"),
                    new Entry(
                            "mapping",
                            "cloud_variance",
                            "Compare monthly spend by resource",
                            "account month previous_month currency service",
                            "staff"),
                    new Entry(
                            "mapping",
                            "cloud_cost",
                            "Review service charges against budget",
                            "account month currency service",
                            "staff"),
                    new Entry(
                            "assistant",
                            "search_services",
                            "Search policies and eligible services",
                            "new_id question",
                            "all"),
                    new Entry(
                            "assistant",
                            "choose_service",
                            "Clarify which service to request",
                            "new_id intent_id",
                            "all"),
                    new Entry(
                            "routing",
                            "source_parts",
                            "Source serialized stock or propose a later visit",
                            "task_id model entries start minutes days",
                            "staff"),
                    new Entry(
                            "routing",
                            "reserve_serial",
                            "Reserve a serialized part for a visit",
                            "task_id asset_id stockroom_id revision",
                            "staff"),
                    new Entry(
                            "routing",
                            "release_serial",
                            "Release a reserved part",
                            "task_id asset_id revision",
                            "staff"),
                    new Entry(
                            "routing",
                            "pickup_serial",
                            "Issue reserved equipment to the technician",
                            "task_id asset_id revision",
                            "staff"),
                    new Entry(
                            "routing",
                            "return_serial",
                            "Receive replaced equipment at a depot",
                            "task_id asset_id stockroom_id",
                            "staff"),
                    new Entry(
                            "workplace",
                            "agreement_template",
                            "Publish a location-specific employee agreement",
                            "location body days revision",
                            "admin"),
                    new Entry(
                            "workplace",
                            "transfer_agreement",
                            "Select the location for a transfer agreement",
                            "case_id location revision",
                            "admin"),
                    new Entry(
                            "workplace",
                            "my_agreement",
                            "Read your current agreement",
                            "case_id",
                            "all"),
                    new Entry(
                            "workplace",
                            "sign_agreement",
                            "Sign the reviewed agreement",
                            "case_id document_id digest revision",
                            "all"),
                    new Entry(
                            "workplace",
                            "review_agreement",
                            "Review a retained agreement",
                            "case_id document_id",
                            "staff"),
                    new Entry(
                            "workplace",
                            "agreement_hold",
                            "Change a case retention hold",
                            "case_id active note revision",
                            "admin"),
                    new Entry(
                            "finance",
                            "fund_goal",
                            "Open a quarterly funding goal",
                            "goal_id title quarter amount currency",
                            "admin"),
                    new Entry(
                            "finance",
                            "funding_option",
                            "Prepare a funding option",
                            "goal_id plan_id title entries",
                            "admin"),
                    new Entry(
                            "finance",
                            "book_labor",
                            "Post delivered labor at the effective calendar rate",
                            "goal_id project_id user_id new_id day minutes",
                            "admin"),
                    new Entry(
                            "finance",
                            "compare_funding",
                            "Compare the frozen assumptions of two options",
                            "goal_id plan_id other_id",
                            "admin"),
                    new Entry(
                            "finance",
                            "view_funding",
                            "Inspect an immutable funding option",
                            "goal_id plan_id",
                            "admin"),
                    new Entry(
                            "finance",
                            "approve_funding",
                            "Approve allocations into project budgets",
                            "goal_id plan_id revision",
                            "admin"),
                    new Entry(
                            "audits",
                            "monitor_grant",
                            "Schedule an independent-approval control",
                            "control_id run_id owner_id minutes",
                            "admin"),
                    new Entry(
                            "audits",
                            "monitor_policy",
                            "Monitor all completed grants using this privileged workflow",
                            "control_id run_id owner_id minutes",
                            "admin"),
                    new Entry(
                            "audits",
                            "indicator_history",
                            "Review indicator evidence and remediation",
                            "control_id",
                            "staff"),
                    new Entry(
                            "audits",
                            "retest_indicator",
                            "Independently retest a failed grant control",
                            "control_id revision",
                            "admin"));

    static Object command(Store store, Store.Db db, String op, Map<String, Object> b)
            throws Exception {
        return switch (op) {
            case "schedule_delta" -> IncrementalImports.configure(db, b);
            case "read_change" -> IncrementalImports.read(db, b);
            case "delta_status" -> IncrementalImports.status(db, b);
            case "list_policy",
                            "start_list",
                            "list_decision",
                            "review_list",
                            "list_status",
                            "fulfill_line" ->
                    ListApprovals.command(db, op, b);
            case "tested_promote" -> AppReleaseTests.promote(db, b);
            case "release_test" -> AppReleaseTests.configure(db, b);
            case "cab_schedule", "cab_agenda", "cab_history", "cab_approve" ->
                    ChangeBoard.command(db, op, b);
            case "checked_outcome", "deployment_gate" -> PipelineReleases.command(db, op, b);
            case "cloud_variance" -> CloudSpend.compare(db, b);
            case "cloud_import", "cloud_budget", "cloud_cost" -> CloudSpend.command(db, op, b);
            case "search_services", "choose_service" -> ServiceSearch.command(store, db, op, b);
            case "source_parts" -> DepotTransfers.source(db, b);
            case "reserve_serial", "release_serial", "pickup_serial", "return_serial" ->
                    DepotTransfers.command(db, op, b);
            case "agreement_template",
                            "transfer_agreement",
                            "my_agreement",
                            "sign_agreement",
                            "review_agreement",
                            "agreement_hold" ->
                    EmployeeAgreements.command(db, op, b);
            case "fund_goal",
                            "funding_option",
                            "book_labor",
                            "compare_funding",
                            "view_funding",
                            "approve_funding" ->
                    QuarterFunding.command(store, db, op, b);
            case "monitor_grant", "monitor_policy", "indicator_history", "retest_indicator" ->
                    GrantIndicators.command(db, op, b);
            default -> throw new Failure(404, "Unknown enterprise operation");
        };
    }
}
