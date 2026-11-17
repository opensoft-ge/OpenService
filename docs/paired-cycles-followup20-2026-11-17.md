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

## Round 4 — reviewed attachment holds and release

Selected `OS-N17B-04`, workstream 5 / OS-PARITY-08. Current attachment downloads enforce record visibility but have no hold/quarantine state. Add a manual administrator hold that blocks both browser and Attachment API file downloads, including for administrators. Record pages identify held files; metadata remains visible under the existing record boundary. Release requires a reason and review fingerprint tied to current bytes, metadata and hold revision. Preserve hold state across migration replacement of an existing attachment. Add a transactional schema upgrade with available-by-default legacy files. No automatic antivirus/privacy scanner, infected-file classification, quarantine retention or forensic-download exception is claimed; storage backups still contain held bytes.

Compared attachment governance (5), report export (14) and knowledge feedback (9). Choose this concrete containment control before additional reporting convenience. Acceptance: native upload → administrator hold → denied downloads for every role → stale-content release rejection → reviewed release, CSRF/roles/strict input, current record isolation, audit evidence, restart persistence and both-theme browser review. Verify upgrade rollback/repeat without byte loss.

Research retrieved 2026-11-17: official Brazil [attachment quarantine policies](https://www.servicenow.com/docs/r/platform-security/data-privacy-classic/attachment-quarantine-policies.html) and [Antivirus Scanning](https://www.servicenow.com/docs/r/platform-security/exploring-antivirus-scanning.html). Refresh existing OS-DISC-02 / WS5 P1 scanner/privacy backlog: pending, failed and infected scans need withheld downloads, safe release authority and durable evidence; dependencies include scanner integration, classification policy and operational ownership. The manual hold is only a prerequisite. Next research: authorized report export and spreadsheet safety.

The initial SQLite/PostgreSQL route regressions returned 404 before implementation. Focused HTTP/browser/upgrade tests then passed. Full acceptance also performs two real staged migrations: import → hold → changed source bytes → fresh synchronization → still denied download → stale-review rejection → current reviewed release. This confirms preservation through the actual importer, not only a direct database update. Migration upgrade tests verify rollback, repeated startup and retained file bytes.

Inspected desktop-light and mobile-dark review screenshots. The accumulated-change QA pass rechecked both file-download paths, metadata versus byte visibility, hold/release authority, CSRF, review fingerprints, serialized mutations, migration upsert behavior, current record boundaries, audit contents and earlier catalogue/connector/AI/approval repairs. No new or reopened defect or further repair was required. Manual holds do not block access to filesystem backups and do not establish malware safety.

Final gates: 791 tests, zero failures/errors/skips; whole-project Java 7193/7193 lines, 3975/3975 branches, 427/427 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| AttachmentHolds.java | 12/12 | 14/14 | 3/3 |
| Migrations.java | 191/191 | 102/102 | 3/3 |
| WebController.java | 2255/2255 | 1249/1249 | 41/41 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Add reviewed attachment holds across download paths`. No push.

## Round 5 — export current permitted report results

Selected `OS-N17B-05`, workstream 14 / OS-PARITY-07. Saved reports can be viewed, revised and copied, but have no CSV download. Add a browser link and native GET download that reruns the saved query under the downloader's current record/field permissions, never cached author results. Use fixed column names, exact decimal strings, UTF-8, correct quoting, empty nulls and literal protection for formula-like grouping labels. Downloads participate in existing per-viewer execution history. Sharing/permission revocation must affect every fresh download. No scheduled/email exports, XLSX, unrestricted raw-record export, independent export-role policy or universal spreadsheet interoperability claim.

Compared report export (14), article feedback (9) and complete read-policy configuration (4). Report export completes an existing analyst journey with a small, testable boundary; retain the broader policy work for round 7 rather than claiming a partial table-read implementation. Acceptance covers both download paths, admin/staff/requester visibility, sharing and field revocation, formula/control/full-width labels, Unicode, embedded quotes/newlines, negative numeric totals, empty selections, authentication, unsupported methods/query parameters, retained history and an actual keyboard-driven browser download in both themes.

Research retrieved 2026-11-17: official [Australia upgrade notes](https://www.servicenow.com/docs/r/delta-washingtondc-australia/australia-washingtondc-platformanalyticsexperience-release-notes.html) describe CSV/Excel visualization downloads; [Brazil Platform Analytics release notes](https://www.servicenow.com/docs/r/release-notes/platform-analytics-experience-rn.html), updated 2026-09-28, add export enablement/role policies and read-only Explore mode. Merge export-specific authority, controlled scheduling and temporary exploration into existing missing P2 reporting/governance work, dependent on scoped permissions and durable delivery. Acceptance must deny direct download after export-role revocation and ensure temporary exploration never changes a shared definition. [OWASP CSV Injection](https://community.owasp.org/attacks/CSV_Injection) informs literal-cell protection; spreadsheet applications may reinterpret modified/re-saved CSV, so this is not a cross-application safety guarantee. Next research: actionable knowledge feedback.

The pre-implementation regressions returned 404 on both databases. Both API/database journeys then passed, including requester exclusion, sharing and numeric/group-field permission revocation, literal spreadsheet labels and empty results. The first browser run exposed an assumed pre-existing reader fixture; create an isolated named reader and rerun the actual keyboard download successfully. This is a test-fixture correction, not a new product feature.

Checkpoint 5: five completed/intended slices span WS9, WS8, WS10, WS5 and WS14 once each. No workstream concentration exception is needed. Retain the planned feedback journey for WS9 and prioritize the broader WS4 read-policy contract next; release/provider qualifications remain explicitly deferred rather than labelled complete.

Inspected desktop-light and mobile-dark screenshots after the final source changes. The actual downloaded CSV contains the reader's permitted sum, not the private record or administrator total. The accumulated-change review covered both route forms, current sharing/field/record checks, personal execution history, CSV quoting and numeric serialization, Unicode/formula labels, cache/disposition headers, owner repair screens, attachment holds and earlier consent/approval/connector repairs. No new or reopened finding or further repair remained in the final review.

Final gates: 794 tests, zero failures/errors/skips; whole-project Java 7202/7202 lines, 3983/3983 branches, 429/429 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| ReportExport.java | 7/7 | 6/6 | 2/2 |
| WebController.java | 2257/2257 | 1251/1251 | 41/41 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Export saved reports using current viewer permissions`. No push.
