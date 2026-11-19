# Ten enterprise workflows

This pass extends OpenService from `c44d554`. The comparison uses the ServiceNow Brazil GA family, reviewed on November 19, 2026. A single published approval workflow can now authorize a directory change, verify it at the destination, and carry its receipt into an employee transfer or an agent plan.

| Priority | Increment and acceptance boundary | Primary workstream |
| --- | --- | --- |
| 1 | Publish reusable approval and parallel-work stages, pin running cases, enforce deadlines and cancellation | Workflows and approvals |
| 2 | Type and verify Entra group membership and Jira issue changes, including recovery | Integrations |
| 3 | Collect cloud and cluster inventory, reconcile observed dependencies and retain manual overrides | CMDB and discovery |
| 4 | Correlate signals into an incident, track impacted services and recovery evidence | Operations |
| 5 | Recheck held invoice lines against later receipts and export eligible charges; reconcile and reclaim seats | Assets |
| 6 | Bind an employee change to approved downstream provisioning and reversal evidence | Employee service |
| 7 | Link customer repair work to dispatch, offline visit evidence and customer resolution | Field and customer service |
| 8 | Tie findings to controls, expiring exceptions and verified closure | Security and risk |
| 9 | Model applications and capability dependencies, compare retirement plans and track realized savings | Portfolio |
| 10 | Authorize a bounded multi-system plan, stop future writes and evaluate independently of live execution | AI |

The compatibility ledger maps these changes to OS-PORT-01 (workflows and incidents), OS-PORT-02 (discovery and assets), OS-PORT-03 (customer and field service), OS-PORT-04 (HR), OS-PORT-05 (security), OS-PORT-06 (portfolio), OS-PORT-07 (automation and AI) and OS-PORT-09 (integrations). OS-PORT-08 industry applications were outside these ten requests. The walkthrough records what each workflow can demonstrate locally and what still requires implementation or a live tenant.

## Operator walkthrough

All new commands use the existing enterprise workspace HTML form and JSON route, `POST /api/openservice/enterprise/{area}/{subject}`. A run, application, template or scenario ID is a 32-digit hexadecimal identifier. The returned revision identifies the next editable version. Administer the common workspaces from **Enterprise workspaces**. A requester can open **Workflow requests** directly.

| Area | Demonstration | Evidence boundary |
| --- | --- | --- |
| Workflow studio | Bind an employee to a manager, publish a template, start it and publish another version. The open request retains its original steps and deadline. Number stages consecutively. Steps in the same stage can finish independently. A manager approves an `approval` step, nominated teams handle `work` or `team_approval`, and cancellation stops pending work. | Titles and definitions use structured forms. Conditions, arbitrary scripts and a canvas designer remain open. |
| Integrations | Publish `entra_add` / `entra_remove` with a group UUID, or `jira_label` with an issue key and label. Each action carries a reviewed connector alias and an OAuth schedule or explicit alias credential. Manager approval precedes writes. The operator can recheck an uncertain outcome and rearm an observed discrepancy. | Graph direct group membership and Jira Cloud issue labels only. Reconciliation reads the destination. Vendor certification and live tenant qualification remain open. |
| Service topology | Run `python3 scripts/collect-service-inventory.py --sequence 1 aws --profile QA --region eu-west-1` or `python3 scripts/collect-service-inventory.py --sequence 1 kubernetes --context QA --namespace apps`. The complete stdout JSON is the inventory command. Serve the snapshot from an authorized endpoint and configure a recurring integration with kind `topology` and the matching source. Re-import and inspect the native CI ID, manual label and services affected by a dependency. | AWS load balancers, target groups and registered targets. Kubernetes Services and selector-matched Pods within one namespace. CLI collectors require explicit account/context. Incoming snapshots support up to 200 resources, 200 services and the existing 64,000-character document limit. |
| Operations | Send time-stamped up/down observations from two monitors. Repeated event IDs retain their original meaning. One resource episode links a native incident, outage and service-impact snapshot. Declare it major, assign command, publish its impact, then link an approved change and review restoration after all monitors recover. | Correlation groups observations of one source resource. Root-cause inference across multiple resources and paging-service delivery remain open. |
| Assets | Match an invoice before all lines arrive, reconcile again after receipt, and download each payable CSV batch. A second administrator can approve the exact disputed price. Import publisher subscriber activity, inspect the local entitlement candidates, and release a confirmed inactive seat against both current revisions. | Each payable unit is exported once. The finance team imports the resulting batch into its ledger. The usage rule requires explicit subscriber activity older than 90 days from a publisher report dated within seven days. Local seat release has no vendor-side deprovisioning effect. |
| Employee service | Schedule a transfer, propose its exact department and in-stock equipment, then have the assigned manager approve those terms. Apply the new department and equipment issuance atomically. After cancellation, undo them against recorded revisions and initiate a reviewed inverse directory workflow for confirmed membership changes. | A changed equipment custodian or employee profile requires reconciliation. Other HCM or payroll products and uncertain remote effects require human review. |
| Field and customer service | Register a customer's installed asset, reserve a qualified crew for an entitled case task and download the self-contained phone form. In airplane mode record minutes, parts and notes. Upload the resulting file with an optional photo, finish the visit checklist and send the customer a resolution to accept or reject. | Manual upload after reconnection. The downloaded form runs without network access. A repeat sync is deduplicated and a reassigned worksheet is flagged. |
| Security and risk | Bind an incident control to its team's scanner finding. Request a dated exception and have the independent owner decide. Assess the latest observation after completed remediation. The view reports expired exceptions and reopened findings. | Per-incident control assurance with scan IDs and decision history. Cross-framework attestations, continuous third-party feeds and evidence packages remain open. |
| Portfolio | Register applications with annual run costs, owners, capabilities and dependencies. Compare two retirement sets against saved staffing and financial assumptions. Inspect surviving consumers, blocked capacity, funding after transition costs and forecast savings. Approve a feasible option, reserve its applications against competing decisions, record decommissioning evidence after consumers migrate, and post actual savings by unique accounting reference. | Scenario assumptions and deltas are retained. Actual savings are operator-entered. Enterprise ledger imports and product-wide optimization remain open. |
| AI | Preview and consent to the planning payload. A configured model selects existing action ordinals. Review the proposed effects, delegate to an API-only identity and queue them. The worker rechecks authorizations and records each destination result. Evaluate the delegate and run five separate synthetic planning cases. | Measures proposal correctness, stop and scope rejection, provider latency and token-derived cost when metering and prices are supplied. Synthetic planning evaluation never calls an action destination. Broader model quality requires representative service-desk traffic. |

## Example workflow definition

```json
[
  {"stage":1,"kind":"approval","title":"Manager approves access"},
  {"stage":2,"kind":"entra_add","title":"Add employee to the support team","target":"00000000-0000-4000-8000-000000000001","alias":"qa-entra","oauth":"alias"},
  {"stage":3,"kind":"jira_label","title":"Mark the handoff ready","issue":"OPS-42","label":"access-ready","alias":"qa-jira","oauth":"alias"}
]
```

Connector execution uses `CONNECTOR_WRITES_ENABLED`. Agent execution also uses `AI_AGENT_EXECUTION_ENABLED`; background processing uses `WORK_ENABLED`. Grant the connector only the permissions required for its approved targets. Each alias binds its reviewed origin and credential revision. A destination change must be reviewed before further execution.

## Acceptance and recovery

| Journey | Regression exercised |
| --- | --- |
| Request and fulfillment | Publish a later template while an employee request is running. Approve parallel tasks independently and check the pinned manager, signing authority and deadline before acting. |
| Connector write and recovery | Verify the destination after a write and after an interrupted reply. Check the original attempt before retrying, honor throttling and reject a changed authorization. |
| Discovery and incidents | Submit the same snapshot and alert twice, reject a conflicting identity and preserve the previous dependency map after a failed import. Recover a two-monitor outage only after both monitors report up. |
| Finance and employee service | Reconcile a partial receipt without double payment, require an independent price decision, and check the activity date before seat release. Test both successful transfer reversal and a conflicting change in custody. |
| Customer and field repair | Capture a signed worksheet in a disconnected Chromium session. Replay the same submission after completion and flag a superseded assignment. Carry evidence from the completed visit to the entitled customer for review. |
| Controls and investment | Check independent exception decisions, expiry and later scan evidence. Reopen the original investment comparison, reject a competing retirement, and prevent reuse of a realized-savings reference. |
| Agents | Validate only authorized, ordered choices, revoke or stop future execution, and measure the model through five independent prompts. Plan evaluation uses synthetic work. |

The acceptance tests run through the enterprise endpoints and their backing stores. The offline test opens a downloaded document in Chromium.

- [Workflow policies and scheduled imports](../src/test/java/ge/opensoft/openservice/TenPolicyAcceptance.java)
- [Directory writes and readback](../src/test/java/ge/opensoft/openservice/TenConnectorAcceptance.java)
- [Interrupted executions and retries](../src/test/java/ge/opensoft/openservice/TenRecoveryAcceptance.java)
- [Incidents, finance, and delegation](../src/test/java/ge/opensoft/openservice/TenJourneysAcceptance.java)
- [HR, repair, assurance and portfolio](../src/test/java/ge/opensoft/openservice/TenBusinessAcceptance.java)
- [Offline phone browser test](../src/test/java/ge/opensoft/openservice/PhoneFormTest.java)

## Product and protocol references

The comparison targets the ServiceNow Brazil release family. The walkthrough describes native OpenService behavior. Vendor-instance conformance, individual Store app versions and a suite-wide parity percentage remain unmeasured. The public topic pages for the three comparisons below identified the Australia family when reviewed.

- Workflow Studio supports generating user and group approvals. [Approval flows](https://www.servicenow.com/docs/r/servicenow-platform/approvals/c_GeneApprovalsUsingWorkflows.html)
- Service Mapping links affected configuration items to alerts. [Service Mapping with Event Management](https://www.servicenow.com/docs/r/it-operations-management/event-management/t_EMGetBaselineServiceMapping.html)
- Application rationalization evaluates whether to invest, sustain, migrate or retire. [Application rationalization](https://www.servicenow.com/docs/r/application-portfolio-management/eaw-work-with-app-rat.html)

The remaining references identify the release target and destination protocols.

- [ServiceNow family release notes](https://www.servicenow.com/docs/r/release-notes/family-release-notes.html)
- [Microsoft Graph group membership](https://learn.microsoft.com/en-us/graph/api/group-post-members?view=graph-rest-1.0)
- [Jira Cloud issue REST operations](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issues/)
- [AWS target group registration and health](https://docs.aws.amazon.com/cli/latest/reference/elbv2/describe-target-health.html)
- [Kubernetes Service selectors](https://kubernetes.io/docs/concepts/services-networking/service/)

## Reproduce the verification

Run `sh scripts/verify.sh` for the full Java and browser gates, and `python3 scripts/test_collect_service_inventory.py` for the read-only inventory CLI fixtures. The focused Java and offline browser acceptance command is `./mvnw '-Dtest=WorkflowTest#ten*,PostgresWorkflowTest#ten*,PhoneFormTest' test`. PostgreSQL acceptance uses the repository's configured local PostgreSQL binaries.


## Verification results

On November 19, 2026, the clean repository verification passed. The review base was `c44d554`. The 13 new database-backed workflows run on both supported databases. A separate browser test uses the self-contained phone form while offline.

| Scope | Measured result |
| --- | --- |
| Maven acceptance and regression | 1,790 tests across 46 suites, zero failures, errors or skips. The prior baseline recorded 1,763 tests. |
| Database acceptance | 548 workflow tests passed on SQLite and 548 on PostgreSQL. |
| Chromium | All 159 tests in the main browser suite passed, plus the separate offline phone test. |
| Whole Java project | 12,293/12,293 executable lines, 12,626/12,626 branches, 1,296/1,296 methods. |
| All 21 changed Java production files | 4,465/4,465 executable lines, 3,856/3,856 branches, 163/163 methods. |
| All four production browser scripts | 163/163 lines, 83/83 branch outcomes and 26/26 functions. The phone form accounts for 25 lines, eight branch outcomes and four functions. |
| Inventory collector | All three CLI fixture tests passed. Python coverage measured 70/70 statements and 18/18 branches. Function counts are unavailable in this report format. |

QA repaired worksheet replay after completion, stale transfer and retirement decisions, and cross-plan savings double counting. The final review and clean verification found no additional defects in the reviewed scope. Java and browser reports are generated at `target/site/jacoco/jacoco.xml` and `target/browser-coverage.json`. To reproduce the collector measurement with coverage.py installed, run `coverage run --branch scripts/test_collect_service_inventory.py` and `coverage report --include='*/collect-service-inventory.py'`.

Entra, Jira and model calls used local protocol fixtures. AWS and Kubernetes tests exercised the collector against recorded input shapes and simulated command output. Live tenant permissions, service limits, production volumes and model performance on representative service-desk traffic remain unverified.
