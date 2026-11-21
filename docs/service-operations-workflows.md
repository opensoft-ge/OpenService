# Service operations workflows

Open **Enterprise workspaces** for the administrative forms. Employees can open **Order guides**, **Request assistant**, **Service impact**, and **Software seats** directly from the sidebar. Each form has a JSON equivalent at `POST /api/openservice/enterprise/{area}/global`. The operation names below identify the corresponding forms. New object identifiers are 32 hexadecimal characters. Updates take the revision of the object being changed.

| Workflow | Operator and employee journey | Scope |
| --- | --- | --- |
| Record-triggered subflows | In `designer/on_record`, choose a table, creation or state-entry event, published decision, input bindings, external event name and provisioning offering. The background worker starts a pinned decision subflow. A callback supplies the run ID, event name and reference. Approval completion and callback receipt can arrive in either order. Provisioning uses the selected decision row and a durable order key. Operators can retry the final step. A supplier assessment can also start a flow and the legal owner's contract entry supplies the event. | Inputs support amount, risk and location from literals, record fields or one dictionary reference. Output is the selected decision row. Callbacks use authenticated OpenService operations. Legal records the signed contract reference. Electronic signature verification remains with the signing provider. |
| Catalog guides | Publish a guide from existing offerings. Set each item's job role and location or use `*`. Preview the combination, answer the individual questions in one browser form, and submit. Each child uses its offering’s eligibility rules and fulfillment team. A guide line can name an independent staff reviewer. That item waits for approval while other items proceed. A manager uses **Choose items for a managed employee** to preview the offerings both people can access. Checkout shows the employee selected in that preview. Both can follow the order. Staff can reserve an in-stock asset for one guide item. Dispatch issues reserved equipment to the employee through native asset custody. Cancellation releases outstanding guide reservations and flags shipments for return. | Each guide item creates a native service request. Unshipped stock can be released to other orders. Warehouse pick, carrier and return transport integrations are separate. |
| REST packages | In `packages`, use `rest_define` to publish the method, relative endpoint, bearer authentication, input questions and output schema. Use `rest_bind` to install it independently for test and production. Reads validate and project paginated rows, then save the completed import. Writes use native preview, confirmation, queued delivery and reconciliation. | Import supports same-endpoint next-page paths, up to 20 pages and 1,000 rows within workspace storage limits. The binding pins the destination origin. Rotating a credential at that origin preserves the binding. After changing an alias to a different origin, or upgrading a binding saved without its origin, review the destination and run `rest_bind` again. Writes deliver JSON and retain a receipt. Existing Entra and Jira qualification records distinguish fixtures from vendor destinations. |
| Desk assignment | In `assignment`, enable an automatic route by record table. Configure each agent's skills and capacity. Online agents see current offers in due-time order. Declines and offline status remove offers. Acceptance checks the record and capacity again under the transaction lock. Supervisors see candidate counts by reason and can override with a reason recorded on the work. | Supports incidents, service requests and customer cases. Active or breached native service clocks inform urgency. Capacity counts work owned through desk assignment. |
| Application promotion | Export a published intake app, copy its JSON to another installation and compare it there. Bind destination teams and a local decision ID. Install a missing dependency to rebuild procedures and replay the supplied decision tests. Stage the reviewed form and promote with a second administrator. Rollback restores the previous definition. Open cases retain the schema, confidential answers and team in force at submission. | Transfer includes form fields, visibility, roles and decision steps. Credentials stay in destination connection storage. The administrator transports the package. |
| CMDB remediation | Open `mapping/health` to locate possible duplicates, aged source observations and services missing ownership. Assign a service owner. Preview a component merge to see which services an outage would affect before and after the correction. Apply the reviewed fingerprint. Future inventories resolve source IDs through the saved aliases. | Duplicate suggestions use matching normalized titles. Administrators can review the whole map. Service owners can review merges confined to their services. Merging reconciles the service graph and retains native CI records and their provenance. |
| Proactive customer service | Bind each installed site to a mapped service, an account contact, its support entitlement and a contractual calendar and time budget. A monitored outage opens the customer workflow automatically. An operator can also open an outage for a component. The impact traversal selects affected sites, validates current customer eligibility, creates native cases and starts native resolution clocks. The same outage key can be replayed. Recovery publishes a reply on each case, settles its clock where possible and notifies eligible contacts. | Monitor episodes supply a stable outage key. A manual issuer supplies the key and customer-facing summary. Customers see the cases they are authorized to read. Sites with an unavailable contact or entitlement are recorded for operator review. |
| Software reclamation | Bind the entitlement to the publisher's usage and subscriber endpoints. Import dated activity, notify inactive seat owners, record dated exceptions and accept waiting requests. Optionally bind a reviewed removal action, preview it and queue it. Provider readback must confirm the exact user and product are unassigned before local release. A waiting employee can then receive the allocation. Purchasing can attribute a posted product-only credit and see totals by currency. | Inactivity uses the existing 90-day rule and a current publisher report. Reassignment fulfills the internal entitlement. Enabling the replacement user's account at the vendor depends on the configured provisioning process. |
| Employee request assistant | Bind intent phrases to a published policy, eligible catalog item and fulfillment group. Configure a default desk. Ask a question, follow citations, complete missing item questions and confirm once. A handoff creates a request with the original question and routes it to the assigned group. Register expected outcomes with `holdout` before the trial conversations, then `score` against the actual catalog orders and assignments. | The matcher uses configured phrases locally. The evaluation reports grounding, verified completion, handoff ownership, forbidden citations, processing latency and model cost. The local matcher incurs zero model charges. |
| Continual improvement | In `mining`, freeze the case-selection criteria, baseline histories, completion times and reopen rate. An administrator publishes a tested decision change and the initiative owner measures the outcome. After the observation window matures, measure the outcome against the frozen baseline. Another authorized reviewer can reopen the saved comparison. | Cohorts use equal UTC windows and follow-up horizons. Small completed samples are marked explicitly. The comparison is descriptive. Other changes during the observation window can also affect results. |

## Authoring examples

A record trigger can read priority from the record and location from the referenced group. The source dictionary must type `assignment_group` as a reference.

```json
{
  "amount": {"field": "priority"},
  "risk": {"literal": "low"},
  "location": {"via": "assignment_group", "field": "short_description"}
}
```

A guide can put VPN access behind an independent reviewer while a laptop ships. For the VPN line, set `reviewer_id` to an active staff user's ID. The reviewer receives the guide and item identifiers and uses **Review an order guide item** to approve or reject the item with a reason. A new review starts at revision 1. An employee following the order sees each item's approval status. Cancellation withdraws pending reviews.

A REST import can project just two properties from a vendor response. The next-page property must contain a relative path to the same installed endpoint or an empty value at the end.

```json
{
  "auth": "bearer",
  "method": "GET",
  "path": "/subscriptions",
  "inputs": [],
  "outputs": {"name": "string", "active": "boolean"},
  "items": "results",
  "next": "next"
}
```

The seat publisher's usage response has `product`, `as_of`, and `observations`. Each observation contains an OpenService `user_id` and a `last_active` date. The subscriber path contains `{user_id}` and returns `product`, `user_id`, and a boolean `assigned`. A removal action accepts `product` and `user_id`. A connector or provider-side adapter can translate those identifiers. Only a successful provider read reporting `assigned: false` qualifies the release.

## Acceptance evidence

`ServiceDeliveryAcceptance` runs the service operations against SQLite and PostgreSQL. It exercises competing accepts, duplicate callbacks, a provisioning retry, a paginated loopback provider, independent installation databases, cross-customer visibility, immutable baseline review and held-out employee questions. `ServiceDeliveryBrowser` exercises the employee checkout and cited VPN request in Chromium. The cross-desk tests read both a running clock and a breached clock and verify customer-account scope. Connector fixtures interrupt a write after the provider records it, reopen the store, and check the remote outcome. Live Entra and Jira qualification requires authorized vendor tenants and is recorded separately from local results.

Run the two database suites and the browser journey together with the following command. Set `OPEN_SERVICE_TEST_POSTGRES_BIN` to the PostgreSQL binary directory on the test machine.

```sh
./mvnw '-Dtest=WorkflowTest#serviceDelivery*,PostgresWorkflowTest#serviceDelivery*,BrowserTest#serviceDelivery*' clean test
```

The employee-assistant holdout included one VPN order and three handoffs, including a question about confidential HR salary bands. Both database runs verified all four outcomes, grounded the one order in the expected policy and catalog item, routed all three handoffs to the expected team, and returned zero forbidden citations. A separate negative-control run marked the expected VPN citation as forbidden and verified that scoring rejected the outcome. Recorded p95 server processing time was 2 ms on SQLite and 35 ms on PostgreSQL. Both runs incurred $0 in model charges. These measurements cover four local phrase-matching conversations. The test writes the scored results to `target/employee-assistant-holdout-{sqlite,postgres}.json`.

### Implementation checkpoint on 20 November 2026

The full application suite passed 1,867 distinct tests, including 35 for this batch, with zero failures, errors or skips. The suite ran in six isolated partitions against the same production source. Focused reruns also exercised dependency-install conflicts, response-step portability, pagination limits and the employee holdout. The five collector tests passed. Chromium covered 163/163 measured JavaScript lines, 83/83 branches and 26/26 functions.

| JaCoCo scope | Executable lines | Branches | Methods |
| --- | ---: | ---: | ---: |
| Entire Java project | 17,330/17,342 | 14,850/14,997 | 1,492/1,492 |
| 27 changed Java files | 6,256/6,268 | 4,691/4,838 | 241/241 |

This checkpoint left 12 lines and 147 branches below the Java coverage gate. Provider qualification used local fixtures. Live Entra and Jira test-tenant evidence remains outstanding.

### QA follow-up on 20 November 2026

Review covered the enterprise branch from `e80a665` and the pending service operations changes. The first round fixed five defects. Identical guide orders now survive reordered JSON fields, and former license holders can rejoin the waitlist. Installed REST packages detect a change in destination origin. A late contract callback resumes an approved supplier flow. A credit against a missing invoice export returns a conflict that purchasing can act on.

The second round fixed two more defects. Recovery checks the customer’s current account and case permissions before sending a notice. A manager can now preview an order guide for a particular employee, see only their shared eligible items, and submit that selection through the browser. Regression checks also cover reopening a saved improvement without starting a fresh comparison.

The final regression ran all 1,885 distinct application tests in six clean partitions with identical final source and tests. It exercised SQLite, PostgreSQL and Chromium. All application tests and five collector tests passed with zero skips. The repository’s Java coverage check passed using only these six partitions. Browser coverage passed for all 163 measured lines, 83 branches and 26 functions.

| Final JaCoCo scope | Executable lines | Branches | Methods |
| --- | ---: | ---: | ---: |
| Entire Java project | 17,374/17,374 | 14,999/14,999 | 1,495/1,495 |
| 28 Java files changed in the service operations batch and QA | 6,310/6,310 | 4,898/4,898 | 252/252 |

Live Entra and Jira credential rotation, interrupted writes, and provider-side verification still require authorized test tenants.
