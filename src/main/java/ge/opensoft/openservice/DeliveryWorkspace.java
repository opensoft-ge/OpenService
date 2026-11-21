package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class DeliveryWorkspace {
    private DeliveryWorkspace() {}

    record Entry(String area, String operation, String title, String fields, String audience) {
        boolean available(Store.Db db) {
            return switch (audience) {
                case "admin" -> "admin".equals(db.viewerRole);
                case "staff" -> Set.of("admin", "agent").contains(db.viewerRole);
                default -> true;
            };
        }

        JourneyForms.Operation descriptor() {
            return new JourneyForms.Operation(area, operation, title, fields);
        }
    }

    static final Map<String, String> AREAS =
            Map.ofEntries(
                    Map.entry("walkup","Walk-up service desks"),Map.entry("guides","Catalog order guides"),Map.entry("assignment","Desk assignment"),Map.entry("impact","Customer service impact"),Map.entry("licenses","Software seat recovery"),Map.entry("assistant","Employee request assistant"),Map.entry("access", "Access policies"),
                    Map.entry("duty", "On-call scheduling"),
                    Map.entry("mapping", "Federated service maps"),
                    Map.entry("sales", "Sales and installation"),
                    Map.entry("routing", "Territory dispatch"),
                    Map.entry("finance", "Project spend"),
                    Map.entry("workplace", "Employee lifecycle"),
                    Map.entry("audits", "Audit engagements"),
                    Map.entry("desk", "Service desk evaluations"),
                    Map.entry("designer", "Decision workflows"),
                    Map.entry("packages", "Connector packages"),
                    Map.entry("apps", "Intake applications"),
                    Map.entry("suppliers", "Supplier governance"),
                    Map.entry("releases", "Deployment changes"),
                    Map.entry("mining", "Case process mining"),
                    Map.entry("continuity", "Recovery exercises"));
    static final List<Entry> COMMANDS =
            List.of(
                    new Entry(
                            "designer",
                            "path",
                            "Define a decision row and its reusable team steps",
                            "design_id title row_id condition operand steps days revision",
                            "admin"),
                    new Entry(
                            "designer",
                            "remove",
                            "Remove a decision row",
                            "design_id row_id revision",
                            "admin"),
                    new Entry(
                            "designer",
                            "test",
                            "Test inputs against an expected decision row",
                            "design_id row_id amount risk location revision",
                            "admin"),
                    new Entry(
                            "designer",
                            "publish",
                            "Publish a fully tested decision table",
                            "design_id revision",
                            "admin"),
                    new Entry(
                            "designer",
                            "start",
                            "Route intake using the published decision table",
                            "design_id user_id new_id amount risk location revision",
                            "admin"),
                    new Entry(
                            "designer",
                            "inspect",
                            "Inspect rules, pinned steps and test evidence",
                            "design_id",
                            "admin"),
                    new Entry(
                            "apps",
                            "build",
                            "Design intake fields and assign the fulfillment team",
                            "app_id title group_id fields design_id revision",
                            "admin"),
                    new Entry(
                            "apps",
                            "compare",
                            "Compare a development draft with the test release",
                            "app_id",
                            "admin"),
                    new Entry(
                            "apps",
                            "rebase",
                            "Accept the current test version as the development base",
                            "app_id revision release_revision confirm",
                            "admin"),
                    new Entry(
                            "apps",
                            "promote",
                            "Promote a reviewed draft to test",
                            "app_id revision release_revision",
                            "admin"),
                    new Entry(
                            "apps",
                            "rollback",
                            "Restore the previous test configuration",
                            "app_id revision",
                            "admin"),
                    new Entry(
                            "apps",
                            "form",
                            "Open the published intake form definition",
                            "app_id",
                            "all"),
                    new Entry(
                            "apps",
                            "submit",
                            "Submit an intake and start its pinned workflow",
                            "app_id new_id entries amount risk location revision",
                            "all"),
                    new Entry("apps", "case", "Read an authorized intake case", "case_id", "all"),
                    new Entry(
                            "apps",
                            "note",
                            "Add a confidential fulfillment note",
                            "case_id note revision",
                            "staff"),
                    new Entry(
                            "apps",
                            "queue",
                            "List my submitted or assigned cases",
                            "app_id",
                            "all"),
                    new Entry(
                            "suppliers",
                            "request",
                            "Register a supplier and its security, legal and finance owners",
                            "supplier security_id legal_id finance_id",
                            "admin"),
                    new Entry(
                            "suppliers",
                            "assess",
                            "Record a supplier security assessment",
                            "supplier expires_on decision note revision",
                            "staff"),
                    new Entry(
                            "suppliers",
                            "contract",
                            "Record the signed contract and its obligations",
                            "supplier reference note revision",
                            "staff"),
                    new Entry(
                            "suppliers",
                            "exception",
                            "Grant a dated purchase exception",
                            "supplier expires_on note revision",
                            "staff"),
                    new Entry(
                            "suppliers",
                            "release",
                            "Approve the supplier order and reserve its project budget",
                            "supplier order_id project_id revision",
                            "admin"),
                    new Entry(
                            "suppliers",
                            "trace",
                            "Trace the supplier, orders, invoices and commitments",
                            "supplier",
                            "admin"),
                    new Entry(
                            "mining",
                            "compare",
                            "Compare state paths over equal observation windows",
                            "table query before_start after_start days hours completed_state",
                            "staff"),
                    new Entry(
                            "mapping",
                            "schedule_collect",
                            "Schedule a complete inventory from an approved connector",
                            "source alias path minutes active revision",
                            "admin"),
                    new Entry(
                            "mapping",
                            "prefer",
                            "Choose the authoritative source for a component field",
                            "component field source resource_id revision",
                            "admin"),
                    new Entry(
                            "mapping",
                            "facts",
                            "Resolve component fields and inspect their origin",
                            "component",
                            "admin"),
                    new Entry(
                            "mapping",
                            "failed_collect",
                            "Record a failed refresh without replacing inventory",
                            "source sequence note",
                            "admin"),
                    new Entry(
                            "mapping",
                            "collection",
                            "Inspect last good inventory, failed refresh and retirements",
                            "source",
                            "admin"),
                    new Entry(
                            "packages",
                            "import",
                            "Import a portable action manifest",
                            "package_id manifest revision",
                            "admin"),
                    new Entry(
                            "packages",
                            "export",
                            "Export a portable action manifest",
                            "package_id",
                            "staff"),
                    new Entry(
                            "packages",
                            "pack",
                            "Publish an action manifest",
                            "package_id title kind revision",
                            "staff"),
                    new Entry(
                            "packages",
                            "install",
                            "Install a package version in an environment",
                            "package_id environment alias oauth target argument expected expires"
                                    + " revision package_revision",
                            "admin"),
                    new Entry(
                            "packages",
                            "inspect",
                            "Inspect a package and its installed binding",
                            "package_id environment",
                            "admin"),
                    new Entry(
                            "packages",
                            "read",
                            "Run the installed GitHub issue reader",
                            "package_id environment path revision",
                            "admin"),
                    new Entry(
                            "releases",
                            "policy",
                            "Authorize a release pipeline for a service and risk threshold",
                            "policy_id title service_ci pipeline_id limit revision",
                            "admin"),
                    new Entry(
                            "releases",
                            "submit",
                            "Register a deployment artifact, risk and test evidence",
                            "policy_id new_id artifact evidence rollback impact_score likelihood",
                            "staff"),
                    new Entry(
                            "releases",
                            "authorize",
                            "Review a release that exceeded its policy",
                            "release_id revision note",
                            "admin"),
                    new Entry(
                            "releases",
                            "outcome",
                            "Record the deployment result and open failure investigations",
                            "release_id revision result reference",
                            "staff"),
                    new Entry(
                            "releases",
                            "recover",
                            "Record rollback and service recovery evidence",
                            "release_id revision rollback evidence",
                            "admin"),
                    new Entry(
                            "releases",
                            "inspect",
                            "Inspect the release and its linked records",
                            "release_id",
                            "staff"),
                    new Entry(
                            "sales",
                            "bundle",
                            "Combine offered site installations with priced equipment and recurring"
                                    + " charges",
                            "new_id title entries",
                            "staff"),
                    new Entry(
                            "sales",
                            "order_sites",
                            "Accept the quote and place each site order",
                            "bundle_id revision",
                            "all"),
                    new Entry(
                            "sales",
                            "sites",
                            "Inspect each site order and active service",
                            "bundle_id",
                            "all"),
                    new Entry(
                            "sales",
                            "amend_site",
                            "Quote an additional charge for one ordered site",
                            "bundle_id site product_id option frequency quantity revision",
                            "staff"),
                    new Entry(
                            "sales",
                            "accept_site_change",
                            "Accept the pending amendment for this site",
                            "bundle_id site revision",
                            "all"),
                    new Entry(
                            "continuity",
                            "plan",
                            "Set service recovery objectives and exercise tasks",
                            "plan_id title component service_id roster_id rto_minutes rpo_minutes"
                                    + " steps revision",
                            "admin"),
                    new Entry(
                            "continuity",
                            "exercise",
                            "Start an exercise using the current dependency map and on-call team",
                            "plan_id new_id revision",
                            "admin"),
                    new Entry(
                            "continuity",
                            "task",
                            "Record completion of an assigned exercise task",
                            "exercise_id step note revision",
                            "staff"),
                    new Entry(
                            "continuity",
                            "measure",
                            "Record observed restoration and latest recoverable data",
                            "exercise_id restored_at restore_point evidence revision",
                            "admin"),
                    new Entry(
                            "continuity",
                            "correct",
                            "Record corrective work against a missed objective",
                            "exercise_id note revision",
                            "staff"),
                    new Entry(
                            "continuity",
                            "retest",
                            "Verify corrective work against a later exercise",
                            "exercise_id later_id revision",
                            "admin"),
                    new Entry(
                            "continuity",
                            "inspect",
                            "Inspect an assigned exercise and its evidence",
                            "exercise_id",
                            "staff"),
                    new Entry("desk","claim","Claim an investigation with an unavailable owner","investigation_id revision note","admin"),
                    new Entry("desk","reviewer","Assign a human administrator to investigate my agent exceptions","reviewer_id revision","admin"),
                    new Entry(
                            "desk",
                            "investigations",
                            "List open, reviewed, resumed or unassigned investigations",
                            "state",
                            "admin"),
                    new Entry(
                            "desk",
                            "handoff_review",
                            "Record the assigned investigator’s reconciliation evidence",
                            "investigation_id revision note",
                            "admin"),
                    new Entry(
                            "desk",
                            "handoff_resume",
                            "Review outstanding native steps under a fresh grant",
                            "investigation_id new_id revision confirm",
                            "admin"),
                    new Entry(
                            "access",
                            "action",
                            "Define an environment-specific reusable action",
                            "action_id environment kind alias oauth target argument expected"
                                    + " expires revision",
                            "admin"),
                    new Entry(
                            "access",
                            "status",
                            "Inspect action permissions, expiry and destination evidence",
                            "action_id environment",
                            "admin"),
                    new Entry(
                            "access",
                            "design",
                            "Design standard and privileged access paths",
                            "policy_id title group_id action_id environment days revision",
                            "admin"),
                    new Entry(
                            "access",
                            "procedure",
                            "Publish a reusable team step",
                            "procedure_id title kind group_id revision",
                            "admin"),
                    new Entry(
                            "access",
                            "extend",
                            "Insert a versioned team step on selected paths",
                            "policy_id procedure_id level revision",
                            "admin"),
                    new Entry(
                            "access",
                            "simulate",
                            "Test both access paths before publishing",
                            "policy_id revision",
                            "admin"),
                    new Entry(
                            "access",
                            "activate",
                            "Publish both tested access paths",
                            "policy_id revision",
                            "admin"),
                    new Entry("access", "catalog", "List published policies", "environment", "all"),
                    new Entry(
                            "access",
                            "apply",
                            "Request the published access level",
                            "policy_id level new_id revision",
                            "all"),
                    new Entry(
                            "access",
                            "qualify",
                            "Record the observed test outcome for an action",
                            "action_id run_id step environment",
                            "admin"),
                    new Entry(
                            "duty",
                            "roster",
                            "Configure primary and backup rotation",
                            "roster_id title anchor zone hours people minutes revision",
                            "admin"),
                    new Entry(
                            "duty",
                            "override",
                            "Schedule a holiday or shift swap",
                            "roster_id start end primary backup note revision",
                            "admin"),
                    new Entry(
                            "duty",
                            "team",
                            "Page a roster for open critical incidents assigned to a group",
                            "group_id roster_id revision",
                            "admin"),
                    new Entry(
                            "duty",
                            "watch",
                            "Page this roster for major incidents from an inventory source",
                            "source roster_id revision",
                            "admin"),
                    new Entry("duty", "who", "Look up on-call coverage", "roster_id at", "staff"),
                    new Entry(
                            "duty",
                            "page",
                            "Page the duty team for a critical incident",
                            "roster_id incident_id",
                            "admin"),
                    new Entry("duty", "ack", "Acknowledge your page", "incident_id", "staff"),
                    new Entry(
                            "duty",
                            "handoff",
                            "Hand incident duty to another responder",
                            "incident_id responder_id note revision",
                            "staff"),
                    new Entry(
                            "duty",
                            "history",
                            "Inspect incident paging history",
                            "incident_id",
                            "staff"),
                    new Entry(
                            "mapping",
                            "reconcile",
                            "Reconcile matching pod addresses and database ARNs from reviewed"
                                    + " sources",
                            "source peer",
                            "admin"),
                    new Entry(
                            "mapping",
                            "bind",
                            "Link an observed resource to a canonical component",
                            "source resource_id component revision",
                            "admin"),
                    new Entry(
                            "mapping",
                            "link",
                            "Connect two canonical components",
                            "component dependency revision",
                            "admin"),
                    new Entry(
                            "mapping",
                            "map",
                            "Trace a service across inventory sources",
                            "source service_id",
                            "admin"),
                    new Entry(
                            "mapping",
                            "outage",
                            "Trace impacted services and related incidents",
                            "component",
                            "admin"),
                    new Entry(
                            "sales",
                            "offer",
                            "Publish a product with installation options",
                            "product_id title currency price options limit revision",
                            "admin"),
                    new Entry(
                            "sales",
                            "opportunity",
                            "Open a customer opportunity",
                            "new_id account_id entitlement_id title",
                            "staff"),
                    new Entry(
                            "sales",
                            "quote",
                            "Price a revision for the customer",
                            "opportunity_id product_id option quantity discount revision",
                            "staff"),
                    new Entry(
                            "sales",
                            "approve",
                            "Approve a discounted quote",
                            "opportunity_id revision",
                            "admin"),
                    new Entry(
                            "sales",
                            "accept",
                            "Accept the exact offered quote",
                            "opportunity_id revision",
                            "all"),
                    new Entry(
                            "sales",
                            "install",
                            "Complete installation and activate product support",
                            "opportunity_id asset_id revision",
                            "staff"),
                    new Entry(
                            "sales",
                            "inspect",
                            "See the opportunity, quote and installation",
                            "opportunity_id",
                            "all"),
                    new Entry(
                            "routing",
                            "optimize",
                            "Compare a dispatch day with a proposed route",
                            "new_id territory date crews jobs travel",
                            "admin"),
                    new Entry(
                            "routing",
                            "replan",
                            "Replan a day after a crew callout",
                            "plan_id absent revision",
                            "admin"),
                    new Entry(
                            "routing",
                            "publish",
                            "Publish a reviewed day to native dispatch",
                            "plan_id revision confirm",
                            "admin"),
                    new Entry(
                            "routing",
                            "inspect",
                            "Review original and proposed route metrics",
                            "plan_id",
                            "admin"),
                    new Entry(
                            "finance",
                            "budget",
                            "Set project funding and currency",
                            "project_id currency amount revision",
                            "admin"),
                    new Entry(
                            "finance",
                            "reserve",
                            "Reserve budget for an approved purchase",
                            "project_id order_id revision",
                            "admin"),
                    new Entry(
                            "finance",
                            "post",
                            "Match a ledger posting or credit to an export",
                            "project_id invoice_id batch_id reference kind amount original",
                            "admin"),
                    new Entry(
                            "finance",
                            "import",
                            "Import a batch of ledger transactions",
                            "project_id entries",
                            "admin"),
                    new Entry(
                            "finance",
                            "balance",
                            "Inspect commitments, posted spend and forecast",
                            "project_id",
                            "admin"),
                    new Entry(
                            "workplace",
                            "schedule",
                            "Schedule hire, move or departure work",
                            "case_id type effective space access_team equipment_team workplace_team"
                                    + " revision",
                            "admin"),
                    new Entry(
                            "workplace",
                            "move",
                            "Reschedule work and space reservation",
                            "case_id effective revision",
                            "admin"),
                    new Entry(
                            "workplace",
                            "complete",
                            "Record a team task outcome",
                            "case_id stream note revision",
                            "staff"),
                    new Entry(
                            "workplace",
                            "cancel",
                            "Cancel open work and release the reservation",
                            "case_id note revision",
                            "admin"),
                    new Entry(
                            "workplace",
                            "readiness",
                            "Review readiness and reversal items",
                            "case_id",
                            "staff"),
                    new Entry(
                            "audits",
                            "control",
                            "Register or revise a framework control",
                            "control_id title framework owner_id revision",
                            "admin"),
                    new Entry(
                            "audits",
                            "engage",
                            "Open an engagement and select controls",
                            "new_id title controls",
                            "admin"),
                    new Entry(
                            "audits",
                            "request",
                            "Request sampled evidence from an owner",
                            "engagement_id evidence_id owner_id controls samples revision",
                            "admin"),
                    new Entry(
                            "audits",
                            "submit",
                            "Submit provenance and evidence for review",
                            "engagement_id evidence_id source note revision",
                            "staff"),
                    new Entry(
                            "audits",
                            "capture",
                            "Submit a frozen text evidence exhibit",
                            "engagement_id evidence_id source note content revision",
                            "staff"),
                    new Entry(
                            "audits",
                            "package",
                            "Download the sealed evidence as JSON text with its SHA-256 digest",
                            "engagement_id",
                            "admin"),
                    new Entry(
                            "audits",
                            "approve",
                            "Approve submitted evidence",
                            "engagement_id evidence_id revision",
                            "admin"),
                    new Entry(
                            "audits",
                            "test",
                            "Record a control test against approved evidence",
                            "engagement_id control_id evidence_id result owner_id due note"
                                    + " revision",
                            "admin"),
                    new Entry(
                            "audits",
                            "remediate",
                            "Submit remediation for independent retest",
                            "engagement_id control_id note revision",
                            "staff"),
                    new Entry(
                            "audits",
                            "close",
                            "Seal the engagement and evidence package",
                            "engagement_id revision",
                            "admin"),
                    new Entry(
                            "audits",
                            "inspect",
                            "Review permitted engagement evidence",
                            "engagement_id",
                            "staff"),
                    new Entry(
                            "desk",
                            "preview_job",
                            "Review the service desk agent context",
                            "new_id plan_id revision job goal",
                            "admin"),
                    new Entry(
                            "desk",
                            "propose_job",
                            "Ask the model for a service desk decision",
                            "new_id plan_id revision job goal preview_token confirm",
                            "admin"),
                    new Entry(
                            "desk",
                            "approve_job",
                            "Approve execution, stop, or handoff",
                            "new_id plan_id revision job goal confirm",
                            "admin"),
                    new Entry(
                            "desk",
                            "finish_review",
                            "Record the operator outcome of a model handoff",
                            "new_id note revision",
                            "admin"),
                    new Entry(
                            "desk",
                            "bind_access",
                            "Bind a verified directory result to its access request",
                            "new_id run_id",
                            "admin"),
                    new Entry(
                            "desk",
                            "recover",
                            "Review remaining steps of a failed native plan",
                            "new_id failed_id",
                            "admin"),
                    new Entry(
                            "desk",
                            "register",
                            "Register reviewed plans for held-out outcome evaluation",
                            "new_id triage access recovery",
                            "admin"),
                    new Entry(
                            "desk",
                            "preview",
                            "Preview service-desk outcome trials",
                            "new_id",
                            "admin"),
                    new Entry(
                            "desk",
                            "evaluate",
                            "Run the reviewed model on service-desk cases",
                            "new_id preview_token confirm",
                            "admin"),
                    new Entry(
                            "desk",
                            "report",
                            "Inspect case outcomes and failures",
                            "new_id",
                            "admin"));

    static Map<String, Object> page(Store.Db db, String area, String subject) throws Exception {
        require("global".equals(subject), "Choose the shared workspace");
        var forms =
                java.util.stream.Stream.of(COMMANDS, ServiceDelivery.COMMANDS, EnterpriseOperations.COMMANDS, ServiceExpansion.COMMANDS, IndustryWorkspaces.COMMANDS, ServiceAssurance.COMMANDS).flatMap(List::stream)
                        .filter(entry -> entry.area.equals(area) && entry.available(db))
                        .map(entry -> entry.descriptor().form())
                        .toList();
        if (forms.isEmpty()) throw new Failure(403, "Workspace access required");
        return map(
                "title",
                AREAS.get(area),
                "description",
                "Select an operation to review or update a record.",
                "data",
                workspaceData(db, area),
                "forms",
                forms);
    }

    static Map<String, Object> workspaceData(Store.Db db, String area) throws Exception {
        var data = map();
        if ("admin".equals(db.viewerRole)) {
            for (String section :
                    switch (area) {
                        case "designer" ->
                                List.of("decision-draft", "decision-release", "access-procedure","record-continuation","supplier-flow","exception-design","exception-run");
                        case "packages" -> List.of("action-package", "package-install");
                        case "apps" -> List.of("intake-draft", "intake-release");
                        case "suppliers" -> List.of("managed-supplier", "supplier-purchase");
                        case "releases" -> List.of("release-policy", "pipeline-release","problem-fix","delivery-team");
                        case "mining" -> List.of("improvement");
                        case "guides" -> List.of("order-guide","guide-order");
                        case "assignment" -> List.of("desk-capacity","desk-offer");
                        case "impact" -> List.of("site-monitor","site-outage");
                        case "licenses" -> List.of("seat-notice","publisher-position");
                        case "assistant" -> List.of("employee-intent");
                        case "continuity" -> List.of("continuity-plan", "recovery-exercise");
                        case "access" ->
                                List.of(
                                        "library-action",
                                        "access-design",
                                        "access-release",
                                        "action-evidence");
                        case "duty" ->
                                List.of("duty-roster", "duty-watch", "duty-team", "incident-page");
                        case "mapping" -> List.of("component-alias", "component-link","intune-run","metric-baseline");
                        case "sales" -> List.of("sale-product", "opportunity","circuit-interruption");
                        case "routing" -> List.of("territory-plan");
                        case "finance" ->
                                List.of(
                                        "project-funding",
                                        "purchase-reservation",
                                        "posted-expense");
                        case "workplace" -> List.of("workplace-event");
                        case "audits" -> List.of("audit-control", "audit-engagement");
                        case "walkup" -> List.of("walkup-location");
                        default -> List.of("desk-suite", "desk-report", "desk-resolution");
                    }) {
                var rows = new ArrayList<Map<String, Object>>();
                for (var row : list(db, section)) {
                    var item = object(row.get("definition"));
                    if (area.equals("desk")
                            && item.containsKey("owner")
                            && !db.viewerId.equals(item.get("owner"))) continue;
                    rows.add(
                            map(
                                    "id",
                                    row.get("subject"),
                                    "title",
                                    item.getOrDefault("title", section),
                                    "state",
                                    item.getOrDefault("follow_up", item.getOrDefault("state", "")),
                                    "revision",
                                    row.get("revision")));
                }
                data.put(section, rows);
            }
        } else if(area.equals("guides")) {
            var guides=new ArrayList<Object>();
            for(var row:list(db,"order-guide"))guides.add(map("guide_id",row.get("subject"),"title",object(row.get("definition")).get("title"),"revision",row.get("revision")));
            data.put("guides",guides);
            var orders=new ArrayList<Object>();
            for(var row:list(db,"guide-order")){
                var order=object(row.get("definition"));
                if(db.viewerId.equals(order.get("owner")) || db.viewerId.equals(order.get("employee")))orders.add(map("order_id",row.get("subject"),"title",order.get("title"),"state",order.get("state")));
            }
            data.put("my_orders",orders);
        } else if (area.equals("walkup")) {
            data.put("locations",list(db,"walkup-location"));
            var mine=new ArrayList<>();
            for(var row:list(db,"walkup-visit")) if(db.viewerId.equals(object(row.get("definition")).get("employee"))) mine.add(row);
            data.put("my_visits",mine);
        } else if(area.equals("suppliers")) {
            var assigned=new ArrayList<>();
            for(var row:list(db,"supplier-agreement")) {
                var item=object(object(row.get("definition")).get("proposal"));
                if(db.viewerId.equals(item.get("contact"))) assigned.add(map("supplier",definition(db,"managed-supplier",row.get("subject").toString()).get("title"),"revision",row.get("revision"),"state",item.get("state")));
            }
            for(var row:list(db,"privacy-assessment")) if(db.viewerId.equals(object(row.get("definition")).get("contact"))) assigned.add(map("assessment_id",row.get("subject"),"revision",row.get("revision")));
            data.put("my_supplier_work",assigned);
        } else if (area.equals("apps")) {
            var published = new ArrayList<Object>();
            for (var row : list(db, "intake-release"))
                published.add(
                        map(
                                "app_id",
                                row.get("subject"),
                                "title",
                                object(row.get("definition")).get("title"),
                                "revision",
                                row.get("revision")));
            data.put("apps", published);
        } else if (Set.of("mapping","designer","guides","assignment","impact","licenses","assistant","releases", "continuity", "mining", "suppliers", "packages")
                .contains(area)) {
            // Record access is evaluated by the selected operation.
        } else if (area.equals("sales")) {
            var rows = new ArrayList<Map<String, Object>>();
            for (var row : list(db, "opportunity")) {
                var sale = object(row.get("definition"));
                try {
                    SalesOrders.account(db, sale.get("account").toString());
                } catch (Failure denied) {
                    continue;
                }
                rows.add(
                        map(
                                "opportunity_id",
                                row.get("subject"),
                                "title",
                                sale.get("title"),
                                "state",
                                sale.get("state"),
                                "revision",
                                row.get("revision")));
            }
            data.put("opportunities", rows);
        } else if (area.equals("access")) {
            data.put(
                    "published",
                    AccessPolicies.run(null, db, "catalog", map("environment", "production")));
            data.put("test", AccessPolicies.run(null, db, "catalog", map("environment", "test")));
        } else if (area.equals("duty")) {
            var pages = new ArrayList<Object>();
            for (var row : list(db, "incident-page")) {
                var page = object(row.get("definition"));
                if (!List.of(page.get("primary"), page.get("backup")).contains(db.viewerId))
                    continue;
                try {
                    Service.readRecord(db, "incident", row.get("subject").toString());
                } catch (Failure denied) {
                    continue;
                }
                pages.add(map("incident_id", row.get("subject"), "state", page.get("state")));
            }
            data.put("my_pages", pages);
        } else {
            boolean hr = area.equals("workplace");
            var mine = new ArrayList<Object>();
            for (var row : list(db, hr ? "workplace-event" : "audit-engagement")) {
                var fields = map(hr ? "case_id" : "engagement_id", row.get("subject"));
                try {
                    if (hr) WorkplaceEvents.run(null, db, "readiness", fields);
                    else AuditEngagements.run(null, db, "inspect", fields);
                } catch (Failure denied) {
                    continue;
                }
                mine.add(fields);
            }
            data.put("my_assignments", mine);
        }
        if(area.equals("designer"))data.put("pending_lists",ListApprovals.pending(db));
        return data;
    }

    static Object command(Store store, Store.Db db, String area, Map<String, Object> b)
            throws Exception {
        var entry =
                java.util.stream.Stream.of(COMMANDS, ServiceDelivery.COMMANDS, EnterpriseOperations.COMMANDS, ServiceExpansion.COMMANDS, IndustryWorkspaces.COMMANDS, ServiceAssurance.COMMANDS).flatMap(List::stream)
                        .filter(
                                e ->
                                        e.area.equals(area)
                                                && e.operation.equals(b.get("operation"))
                                                && e.available(db))
                        .findFirst()
                        .orElseThrow(() -> new Failure(403, "Operation unavailable"));
        var fields = new HashSet<>(entry.descriptor().names());
        fields.add("operation");
        require(fields.equals(b.keySet()), "Provide the displayed operation fields");
        lock(db);
        if (ServiceAssurance.COMMANDS.contains(entry)) return ServiceAssurance.run(store,db,entry.operation(),b);
        if (IndustryWorkspaces.COMMANDS.contains(entry)) return IndustryWorkspaces.command(store,db,entry.operation(),b);
        if (ServiceExpansion.COMMANDS.contains(entry)) return ServiceExpansion.command(store,db,entry.operation(),b);
        if (EnterpriseOperations.COMMANDS.contains(entry)) return EnterpriseOperations.command(store,db,entry.operation(),b);
        if (ServiceDelivery.COMMANDS.contains(entry)) return ServiceDelivery.command(store,db,entry,b);
        return switch (area) {
            case "designer" -> DecisionFlows.command(db, entry.operation, b);
            case "packages" -> ActionPackages.command(store, db, entry.operation, b);
            case "apps" -> IntakeApps.command(db, entry.operation, b);
            case "suppliers" -> SupplierGovernance.command(db, entry.operation, b);
            case "releases" -> PipelineReleases.command(db, entry.operation, b);
            case "mining" -> CaseMining.compare(db, b);
            case "continuity" -> RecoveryExercises.command(db, entry.operation, b);
            case "access" -> AccessPolicies.run(store, db, entry.operation, b);
            case "duty" -> DutyRosters.run(store, db, entry.operation, b);
            case "mapping" ->
                    entry.operation.equals("schedule_collect")
                            ? InventorySchedules.configure(db, b)
                            : Set.of("facts", "prefer", "collection", "failed_collect")
                                            .contains(entry.operation)
                                    ? InventoryEvidence.command(db, entry.operation, b)
                                    : FederatedMaps.run(store, db, entry.operation, b);
            case "sales" ->
                    Set.of("bundle", "order_sites", "sites", "amend_site", "accept_site_change")
                                    .contains(entry.operation)
                            ? SiteOrders.command(store, db, entry.operation, b)
                            : SalesOrders.run(store, db, entry.operation, b);
            case "routing" -> TerritoryRoutes.run(store, db, entry.operation, b);
            case "finance" -> ProjectSpend.run(store, db, entry.operation, b);
            case "workplace" -> WorkplaceEvents.run(store, db, entry.operation, b);
            case "audits" -> AuditEngagements.run(store, db, entry.operation, b);
            default ->
                    Set.of("claim","reviewer","investigations", "handoff_review", "handoff_resume")
                                    .contains(entry.operation)
                            ? AgentInvestigations.command(store, db, entry.operation, b)
                            : DeskTrials.run(store, db, entry.operation, b);
        };
    }

    static Map<String, Object> put(
            Store.Db db, String area, String key, Object revision, Map<String, Object> data)
            throws Exception {
        save(db, area, key, revision, data);
        return get(db, area, key);
    }

    static String choice(Map<String, Object> b, String field, String... values) {
        String value = text(b, field, 120);
        require(List.of(values).contains(value), "Unsupported " + field);
        return value;
    }

    static String key(Object... values) {
        return ConnectorSynchronization.hash(List.of(values));
    }
}
