package ge.opensoft.openservice;

import static ge.opensoft.openservice.DeliveryWorkspace.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class ServiceDelivery {
    private ServiceDelivery() {}

    static final List<DeliveryWorkspace.Entry> COMMANDS =
            List.of(
                    new DeliveryWorkspace.Entry(
                            "designer",
                            "on_record",
                            "Start a typed subflow on record creation or state change",
                            "title table event state design_id bindings wait_event offering_id",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "designer",
                            "flow_event",
                            "Consume an external event for a record workflow",
                            "run_id event reference",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "designer",
                            "continue_record",
                            "Resume the approved provisioning step",
                            "run_id",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "designer",
                            "trigger",
                            "Configure a supplier assessment trigger",
                            "design_id risk location amount offering_id revision",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "designer",
                            "contract_event",
                            "Acknowledge a signed contract",
                            "design_id supplier reference",
                            "staff"),
                    new DeliveryWorkspace.Entry(
                            "designer",
                            "retry_provision",
                            "Provision the approved supplier request",
                            "design_id supplier",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "designer",
                            "flow_status",
                            "Inspect approval output and provisioning status",
                            "design_id supplier",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "guides",
                            "define",
                            "Publish an order guide",
                            "guide_id title entries revision",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "guides",
                            "preview",
                            "Choose items for a role and location",
                            "guide_id job_role location",
                            "all"),
                    new DeliveryWorkspace.Entry(
                            "guides",
                            "preview_for",
                            "Choose items for a managed employee",
                            "guide_id employee_id job_role location",
                            "all"),
                    new DeliveryWorkspace.Entry(
                            "guides",
                            "order_for",
                            "Order a guide for a managed employee",
                            "guide_id employee_id job_role location new_id manifest details"
                                    + " revision",
                            "all"),
                    new DeliveryWorkspace.Entry(
                            "guides",
                            "order",
                            "Order the selected items",
                            "guide_id job_role location new_id manifest details revision",
                            "all"),
                    new DeliveryWorkspace.Entry(
                            "guides", "track", "Track a guide order", "order_id", "all"),
                    new DeliveryWorkspace.Entry(
                            "guides",
                            "decide_item",
                            "Review an order guide item",
                            "order_id offering_id decision note revision",
                            "staff"),
                    new DeliveryWorkspace.Entry(
                            "guides",
                            "cancel",
                            "Cancel an order and review returns",
                            "order_id revision",
                            "all"),
                    new DeliveryWorkspace.Entry(
                            "guides",
                            "reserve_asset",
                            "Reserve an in-stock asset for this item",
                            "order_id offering_id asset_id revision",
                            "staff"),
                    new DeliveryWorkspace.Entry(
                            "guides",
                            "ship",
                            "Record a dispatched item",
                            "order_id offering_id reference revision",
                            "staff"),
                    new DeliveryWorkspace.Entry(
                            "packages",
                            "rest_define",
                            "Define a REST action package",
                            "package_id title manifest revision",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "packages",
                            "rest_bind",
                            "Install the action on a connection",
                            "package_id environment alias revision",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "packages",
                            "rest_read",
                            "Run the paginated import",
                            "package_id environment manifest",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "packages",
                            "rest_preview",
                            "Review a remote write",
                            "package_id environment manifest new_id",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "packages",
                            "rest_write",
                            "Queue the confirmed write",
                            "package_id environment manifest new_id state preview_token confirm",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "assignment",
                            "auto_route",
                            "Automatically offer newly created records",
                            "table skill minutes active revision",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "assignment",
                            "capacity",
                            "Set skills and capacity",
                            "agent_id skills capacity revision",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "assignment", "presence", "Set my availability", "active", "staff"),
                    new DeliveryWorkspace.Entry(
                            "assignment",
                            "route",
                            "Route a work record",
                            "table record_id skill due",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "assignment", "offers", "View my pending offers", "agent_id", "staff"),
                    new DeliveryWorkspace.Entry(
                            "assignment",
                            "accept",
                            "Accept an offer",
                            "table record_id revision",
                            "staff"),
                    new DeliveryWorkspace.Entry(
                            "assignment",
                            "decline",
                            "Decline an offer",
                            "table record_id revision",
                            "staff"),
                    new DeliveryWorkspace.Entry(
                            "assignment",
                            "override",
                            "Override assignment",
                            "table record_id agent_id note revision",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "assignment", "waiting", "Review pending work", "limit", "admin"),
                    new DeliveryWorkspace.Entry(
                            "apps",
                            "package_app",
                            "Export the published intake application",
                            "app_id",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "apps",
                            "compare_package",
                            "Preview incoming configuration and dependencies",
                            "app_id manifest group_id design_id bindings",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "apps",
                            "install_dependency",
                            "Install the package workflow and test its routes with destination"
                                    + " teams",
                            "app_id manifest group_id design_id bindings",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "apps",
                            "stage_package",
                            "Stage the reviewed configuration for promotion",
                            "app_id manifest group_id design_id bindings revision release_revision",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "mapping", "health", "Prioritize map health issues", "days", "staff"),
                    new DeliveryWorkspace.Entry(
                            "mapping",
                            "owner",
                            "Set a service owner",
                            "service owner_id revision",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "mapping",
                            "preview_merge",
                            "Preview impact after component merge",
                            "component target",
                            "staff"),
                    new DeliveryWorkspace.Entry(
                            "mapping",
                            "merge",
                            "Apply the reviewed component merge",
                            "component target fingerprint",
                            "staff"),
                    new DeliveryWorkspace.Entry(
                            "impact",
                            "bind_site",
                            "Bind an installed customer site to a mapped service",
                            "bundle_id site service entitlement_id contact_id calendar minutes"
                                    + " revision",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "impact",
                            "outage",
                            "Open cases for affected sites",
                            "new_id component title",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "impact",
                            "recover",
                            "Notify affected customers of recovery",
                            "new_id note",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "impact",
                            "my_impact",
                            "Review site impact and contractual deadlines",
                            "limit",
                            "all"),
                    new DeliveryWorkspace.Entry(
                            "licenses",
                            "publisher",
                            "Bind the product usage report and subscriber readback",
                            "license_id alias usage_path subscriber_path revision",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "licenses",
                            "bind_removal",
                            "Select a provider action for seat removal",
                            "license_id action_id revision",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "licenses",
                            "preview_removal",
                            "Review a provider removal after notice",
                            "license_id user_id new_id",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "licenses",
                            "remove_at_provider",
                            "Queue the confirmed provider removal",
                            "license_id user_id new_id state preview_token confirm",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "licenses",
                            "import_usage",
                            "Import dated subscriber activity from the publisher",
                            "license_id revision",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "licenses",
                            "notice",
                            "Notify an inactive seat owner",
                            "product license_id user_id",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "licenses", "notices", "See my seat notices", "limit", "all"),
                    new DeliveryWorkspace.Entry(
                            "licenses",
                            "exception",
                            "Approve a dated retention exception",
                            "license_id user_id expires_on note revision",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "licenses",
                            "waitlist",
                            "Request a seat from the next release",
                            "license_id",
                            "all"),
                    new DeliveryWorkspace.Entry(
                            "licenses",
                            "verify_release",
                            "Verify provider removal and fulfill waiting demand",
                            "product license_id user_id usage_revision revision",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "licenses",
                            "record_credit",
                            "Link a verified product-only invoice credit",
                            "license_id reference",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "licenses",
                            "savings",
                            "Inspect verified recoveries and invoice credits",
                            "license_id",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "assistant",
                            "holdout",
                            "Register expected employee outcomes before running trials",
                            "new_id cases",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "assistant",
                            "score",
                            "Score held-out orders and service desk handoffs",
                            "new_id",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "assistant",
                            "fallback",
                            "Choose a desk for unmatched questions",
                            "group_id revision",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "assistant",
                            "intent",
                            "Bind policy and fulfillment to an employee intent",
                            "new_id terms article_id offering_id group_id revision",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "assistant",
                            "ask",
                            "Find a policy and eligible catalog item",
                            "question new_id",
                            "all"),
                    new DeliveryWorkspace.Entry(
                            "assistant",
                            "confirm_order",
                            "Confirm the cited request",
                            "new_id manifest details confirm",
                            "all"),
                    new DeliveryWorkspace.Entry(
                            "assistant",
                            "handoff",
                            "Send the question to service desk",
                            "new_id details",
                            "all"),
                    new DeliveryWorkspace.Entry(
                            "mining",
                            "baseline",
                            "Freeze a process baseline and assign an owner",
                            "new_id title owner_id table query before_start days hours"
                                    + " completed_state limit",
                            "staff"),
                    new DeliveryWorkspace.Entry(
                            "mining",
                            "publish_change",
                            "Publish the reviewed routing change",
                            "new_id design_id draft_revision revision",
                            "admin"),
                    new DeliveryWorkspace.Entry(
                            "mining",
                            "measure",
                            "Freeze the outcome comparison",
                            "new_id after_start revision",
                            "staff"),
                    new DeliveryWorkspace.Entry(
                            "mining", "review", "Reopen the frozen comparison", "new_id", "admin"));

    static Object command(
            Store store, Store.Db db, DeliveryWorkspace.Entry entry, Map<String, Object> body)
            throws Exception {
        return switch (entry.area()) {
            case "designer" ->
                    Set.of("on_record", "flow_event", "continue_record").contains(entry.operation())
                            ? RecordSubflows.command(store, db, entry.operation(), body)
                            : SupplierFlows.command(store, db, entry.operation(), body);
            case "guides" -> OrderGuides.command(store, db, entry.operation(), body);
            case "packages" -> RestPackages.command(store, db, entry.operation(), body);
            case "assignment" -> DeskAssignment.command(store, db, entry.operation(), body);
            case "apps" -> PortableApps.command(store, db, entry.operation(), body);
            case "mapping" -> MapHealth.command(store, db, entry.operation(), body);
            case "impact" -> CustomerImpact.command(store, db, entry.operation(), body);
            case "licenses" -> SeatRecovery.command(store, db, entry.operation(), body);
            case "assistant" ->
                    Set.of("score", "holdout").contains(entry.operation())
                            ? EmployeeOutcomes.command(db, entry.operation(), body)
                            : EmployeeAssistant.command(store, db, entry.operation(), body);
            case "mining" -> Improvements.command(store, db, entry.operation(), body);
            default -> throw new Failure(404, "Workspace unavailable");
        };
    }
}
