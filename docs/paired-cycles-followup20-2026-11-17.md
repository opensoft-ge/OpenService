# OpenService: second 20-round development and QA batch, 17 November 2026

Status: in progress. Feature baseline: `a143e653d6a738fa3ce3574f3f7d82c10518b39e`, clean `main`. Fixed QA base: locally recorded `origin/main` / merge base `e80a665f121e99489f480131a52ca777ec304282`; reviews include the previous batch's accumulated changes and repairs. No fetch, push, deployment or history rewrite. Each new round requires a bounded feature, meaningful acceptance, clean full verification, exact whole-touched-file coverage, an accumulated-change QA review and a local opendevbot commit without co-author trailers.

The latest 20 completed features covered 13 workstreams, no more than two each, largely completing browser journeys over existing engines. This batch favors additional service/business behavior and integration/governance prerequisites. Review the proposed slice at each selection and reallocate if actual implementation or dependencies contradict the plan. QA repairs do not count as extra features.

Stack reverified on 2026-11-17: [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html) identify stable 4.1.2 with Java support through 27; [Oracle downloads](https://www.oracle.com/java/technologies/downloads/) identify Java 27 as latest GA. Retain Java 27/Spring Boot 4.1.2. Oracle's current vendor patch listing is 27.0.1.0.3; local test JDK is 27.0.1.0.2 and pinned container vendor builds are qualified separately, not claimed identical to that Oracle patch. No preview stack selected.

## Planned allocation

| Workstream | Rounds | Intended outcome / remaining dependency |
|---|---|---|
| 1. Shared deployment | Deferred | Actual HTTPS/SMTP, permitted users and operating owner need a named deployment; local checks do not complete M1. |
| 2. Enterprise identity | Deferred | SSO/MFA and provider recovery require a coherent identity-provider contract and live qualification. |
| 3. Account lifecycle | Deferred | Existing profile/password/onboarding work is substantial; avoid another optional credential refinement ahead of business journeys. |
| 4. Permissions/isolation | 7 | Make table-read policy configurable and consistently enforced, or select a complete independently useful permission journey if architecture blocks it. |
| 5. Audit/governance | 4 | Attachment quarantine/hold with enforced download exclusion and audited release; manual control is not malware scanning. |
| 6. Jobs/notifications | 8, 15 | Safe bulk acknowledgment of a reviewed personal history window; record subscriptions with permission-aware notifications. |
| 7. Workflow/approvals | 13 | Decision reauthentication with unchanged separation-of-duties and revision boundaries. |
| 8. SLAs | 2, 14 | Start and operate a service clock from a record; distinguish response/resolution commitments or a similarly coherent clock-policy gap. |
| 9. Catalogue/knowledge | 1, 6 | Catalogue offering → owned request journey; scoped article feedback/review follow-up. |
| 10. Integrations | 3 | Save alias → queue a bounded connector probe → inspect receipt → retire alias through the browser. OAuth/federation remains open. |
| 11. Migration | 10 | Explicit migration stop/cancellation with stale worker rejection and honest resumption limits. Live conformance remains open. |
| 12. Data/configuration | 11, 16 | Additional validated reusable field types; reviewable configuration differences before promotion. |
| 13. Assets/operations | 9, 17 | Operational event → correlated case; edit dependency relationships with current access and graph checks. |
| 14. Reporting/search | 5, 18 | Permission-aware report CSV export; bounded time-trend analysis over complete permitted selections. |
| 15. Governed AI | 12, 19 | Grounded published-knowledge assistance; reviewed AI request drafting with explicit execution authority. Provider quality/cost/latency evaluation remains separate. |
| 16. Production/release | 20 | Requalify final package, schema upgrades/restores, workers, scans and bounded load; select a demonstrated reliability gap when found. |

Twenty intended rounds across 13 workstreams; at most two per primary workstream and no consecutive repeats. Rebalance at rounds 5, 10, 15 and 20. Prerequisite dependencies, acceptance and exclusions are pinned in each selected round before editing.

## Round 1 — catalogue offering to owned request

Selected `OS-N17B-01`, workstream 9 / OS-PARITY-04. Current requester intake accepts free text but has no reusable catalogue offerings or order path. Add administrator-managed active/inactive offerings with optimistic revision checks, a browse/detail/order journey, and an owned service request containing an immutable copy of the reviewed offering text. Preserve current request permissions. Bound offerings and order input; reuse a client order key to prevent duplicate requests and reject changed-payload reuse. Retired/changed offerings cannot create new orders. No cart, pricing, variable designer, catalogue-specific eligibility, request-for-another-user or automated fulfilment claim.

Candidates compared: catalogue intake (9), record clock launch (8), and connector probe/retirement (10). Catalogue supplies a prerequisite for reusable fulfilment and governed request drafting; choose it first. Acceptance covers admin lifecycle, optimistic conflicts, requester ownership, strict inputs/roles/CSRF, inactive items, idempotent retry/conflict, source edits after ordering, rollback, persistence and an accessible both-theme keyboard browser journey.

Research retrieved 2026-11-17: official Brazil [catalogue item form](https://www.servicenow.com/docs/r/servicenow-platform/service-catalog/catalog-item-form.html), [Catalog Builder](https://www.servicenow.com/docs/r/servicenow-platform/service-catalog/create-item-cat-builder.html) and [user criteria](https://www.servicenow.com/docs/r/servicenow-platform/service-catalog/t_CreateAUserCriteriaRecord.html), updated 2026-09-10. Active offerings and reviewed ordering inform this original native contract; no vendor wire compatibility or live conformance is claimed. Merge catalogue-specific eligibility, variable definitions and fulfilment orchestration into the existing missing P1 catalogue backlog: depend on a criteria engine and durable fulfilment; acceptance must deny both browsing and direct ordering after eligibility revocation. Next research area: SLA clocks and schedules.

Both database HTTP regressions failed with 404 before implementation. QA R1-1: the catalogue capacity fixture polluted a later browser test; restore its added rows. The requester journey also needs its requester landing page rather than the staff-login helper. QA R1-2: simultaneous direct service calls reproduced a PostgreSQL idempotency race outside the already serialized HTTP dispatcher. Serialize catalogue mutations with the existing transaction lock; retain a two-caller regression on both databases. Upgrade tests exercise schema rollback, repeat startup, credential preservation and retained orders. These repairs count within this feature, not as additional features.

Inspected desktop-light and mobile-dark requester screenshots; automated accessibility and keyboard checks passed. The final accumulated-change review covered route/method/CSRF boundaries, offering edits, concurrent retries, migration rollback, prior AI-consent and approval repairs, field/record serialization, search filters and escaped templates. No new or reopened finding remained after the two documented repairs.

Final gates: 780 tests, zero failures/errors/skips; whole-project Java 7139/7139 lines, 3921/3921 branches, 418/418 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| Catalogue.java | 36/36 | 40/40 | 4/4 |
| Migrations.java | 187/187 | 100/100 | 3/3 |
| WebController.java | 2236/2236 | 1231/1231 | 41/41 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Add reviewed catalogue offerings and owned service requests`. No push.

## Round 2 — operate service clocks from a record

Selected `OS-N17B-02`, workstream 8 / OS-PARITY-02. The clock engine supports calendars, durable ticks and terminal states, but record pages do not offer a start/control journey. Add a link from an existing record to current clock history and administrator start/pause/resume/complete/cancel forms. Readers can inspect clocks only for currently visible records; controls must verify that the submitted clock belongs to that exact table/record. Preserve existing calendar snapshots, business-time calculation and worker behavior. No conditional SLA attachment, automatic workflow completion, retroactive repair or response/resolution policy claim.

Compared record clocks (8), connector operations (10) and attachment holds (5). Record clocks complete an existing service-owner journey without another catalogue refinement. Acceptance: protected forms, staff versus admin authority, missing/foreign clocks, inactive terminal rejection, strict input, hidden record denial, persisted state and both-theme desktop/mobile keyboard accessibility.

Official evidence retrieved 2026-11-17: Brazil [SLA definitions](https://www.servicenow.com/docs/r/it-service-management/service-level-management/t_CreateAnSLADefinition.html) and [SLA repair](https://www.servicenow.com/docs/r/it-service-management/service-level-management/c_RepairSLAs.html), updated 2026-09-10. Vendor definitions distinguish start/pause/stop/reset conditions; repair reconstructs timing from task history with limits on related-field history. Merge historical SLA reconstruction and before/after repair evidence into existing missing P2 SLA diagnostics, dependent on complete task/clock event history. This native manual-clock slice does not implement vendor repair. Next research: connection aliases and reusable integration execution.

Both database regressions returned 404 before implementation. Initial focused checks exposed a test request missing CSRF before the intended method check; the valid-CSRF PUT now exercises method rejection. Added a visible disabled-worker status while preserving manual clock controls. No claim of active SLA monitoring is made when automatic processing is disabled.

Inspected both-theme screenshots after the final changes; the narrow table is in a labelled, keyboard-focusable horizontal scroll region. Final accumulated review covered current visibility before clock retrieval, record-bound controls, administrator/CSRF checks, terminal behavior, calendar snapshots, worker-disabled wording and all earlier repairs. No new/reopened finding or further repair was required.

Final gates: 783 tests, zero failures/errors/skips; whole-project Java 7157/7157 lines, 3931/3931 branches, 420/420 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| RecordClocks.java | 13/13 | 6/6 | 2/2 |
| WebController.java | 2241/2241 | 1235/1235 | 41/41 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Start and manage service clocks from their records`. No push.

## Round 3 — test and retire a reviewed connector

Selected `OS-N17B-03`, workstream 10 / OS-PARITY-03. Operations can save credentials and the API can enqueue read work, but administrators lack an alias-specific test/receipt/retirement browser journey. Add metadata-only connector detail, protected queued GET tests and reasoned retirement. Bind form review and queued tests to the complete current configuration, including credential envelope, so rotation or retire/recreate cannot silently change a reviewed destination. Preserve existing deployment origin restrictions, bounded HTTP reads, durable job authority/retries and sanitized receipts. Legacy API jobs without an optional review fingerprint retain their documented dynamic-alias behavior.

Compared integrations (10), attachment holds (5) and report exports (14). Choose the integration journey next to complete a reusable administrator operation without another SLA refinement. Acceptance: admin-only access, CSRF/strict form input, no secrets/provider bodies in views or receipts, idempotent queue submission, real local HTTP worker execution, stale-form rejection, worker rejection after retire/recreate and accessible both-theme browser save → test → receipt → retire. No OAuth, external vault, caller credentials, two-way synchronization or vendor template designer claim.

Official evidence retrieved 2026-11-17: Brazil Workflow Data Fabric [test a connection alias](https://www.servicenow.com/docs/r/integrate-applications/integration-hub/test-alias-configuration-template.html), Integration Hub Starter, and [multiple connections](https://www.servicenow.com/docs/r/integrate-applications/integration-hub/support-multiple-connections.html), updated 2026-09-10. Merge environment-specific connection overrides and execution-time alias evidence into existing missing P2 connector/configuration work, dependent on versioned action definitions and promotion bindings. Acceptance must show the actual selected connection and require destination configuration after promotion without transferring credentials. Next research: attachment quarantine and release governance.

Both database route regressions returned 404 before implementation. Focused SQLite/PostgreSQL/browser tests pass, including a real loopback HTTP GET through the durable worker, receipt non-disclosure and a stale queued test cancelled without a second outbound call after alias retirement/recreation. The browser journey saves its own alias, tests it, reads the successful receipt and retires it. Tests use synthetic local credentials/data only.

Both-theme screenshots were inspected after final verification. Accumulated-change QA rechecked administrator and CSRF boundaries, allowed destinations, relative-path validation, no credential/provider-body disclosure, queued configuration identity, alias replacement, worker authority, prior catalogue concurrency and record-clock controls. No new/reopened defect or further repair was required.

Final gates: 786 tests, zero failures/errors/skips; whole-project Java 7168/7168 lines, 3949/3949 branches, 424/424 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| ConnectorConsole.java | 8/8 | 6/6 | 2/2 |
| Connectors.java | 57/57 | 24/24 | 5/5 |
| WebController.java | 2246/2246 | 1239/1239 | 41/41 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Test reviewed connector configurations and retire aliases safely`. No push.
