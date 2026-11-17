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

## Round 6 — versioned knowledge feedback and editorial review

Selected `OS-N17B-06`, workstream 9 / OS-PARITY-04. Published articles have immutable version snapshots and search, but no reader feedback or editorial follow-up. Add a dedicated published article page with one personal helpful/not-helpful response and optional details per user/version. Administrators review current feedback revisions in an article-specific queue; changed feedback reopens review, identical retries retain an existing decision. Public views disclose only the current user's response; editorial notes remain administrator-only. Reject stale publication submissions and hide retired, deleted, private/group or unpublished articles. Review history is not an article publication or automatic content edit.

Compared feedback (9), full read-policy configuration (4) and personal notification controls (6). Choose this planned complete requester → editor journey now, with no further primary WS9 feature in this batch. Acceptance covers isolated responses, role/CSRF/input boundaries, real public-snapshot separation, stale versions/reviews, both feedback values, retries and edits, retained review through restart, upgrade rollback, bounded administration and both-theme requester/admin browser journeys. No public comment threads, star ratings, task assignment, notifications, automated article generation or full feedback-history pagination claim.

Official evidence retrieved 2026-11-17: Brazil [knowledge feedback](https://www.servicenow.com/docs/r/servicenow-platform/knowledge-management/r_KnowledgeFeedback.html) and [actionable feedback](https://www.servicenow.com/docs/r/servicenow-platform/knowledge-management/configure-act-know-feedback-properties.html), updated 2026-09-10. Merge configurable negative-feedback tasks, editor assignment and feedback-history filtering/pagination into existing missing P2 knowledge/fulfilment work, dependent on scoped knowledge administration and durable workflow. Acceptance must route feedback to an eligible editor without granting draft access to the reporter and preserve the reviewed publication version. This manual review slice is not that automated workflow. Next research: consistent table-read access policy.

Both database acceptance regressions failed with 404 before implementation. Focused SQLite/PostgreSQL, browser and upgrade checks pass. The requester browser submits literal markup safely, the administrator records a private follow-up, and the requester sees reviewed status without that internal note. Upgrade checks exercise rollback and repeated startup with retained review contents.

QA R6-1: after the initial full pass, a boundary browser regression using a long unbroken published title reproduced horizontal overflow. Apply the existing wrapping utility to knowledge/article/review headings and retain the long-title regression. This repairs presentation within the same feature; it is not counted as an additional feature. The final source is re-frozen and fully reverified after the repair.

Inspected final long-title mobile-dark and administrator-light screenshots: wrapping now remains within the viewport. Final accumulated-change review rechecked publication isolation, personal response projection, editorial-only notes, strict input/CSRF, revision and duplicate submission behavior, transactional upgrades, all download/connector/report boundaries and previous approval/consent repairs. No new or reopened defect remained and this review required no further repair.

Final gates: 799 tests, zero failures/errors/skips; whole-project Java 7236/7236 lines, 4049/4049 branches, 433/433 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| KnowledgeFeedback.java | 19/19 | 38/38 | 4/4 |
| Migrations.java | 194/194 | 104/104 | 3/3 |
| WebController.java | 2269/2269 | 1277/1277 | 41/41 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Add versioned knowledge feedback and private editorial review`. No push.

## Round 7 — configurable table-read boundaries

Selected `OS-N17B-07`, workstream 4 / OS-PARITY-08. Table configuration accepts create/update/delete policies but cannot install a read policy, and generic record/query surfaces lack a shared read guard. Add optional `record_roles.read` over the four existing roles, requiring administrator access to remain. Enforce current read policy before record and field serialization, selection, exports, analytics, attachments, navigation and current-record audit projections. Role/policy changes take effect on the next request. A user without access to the default incident table receives a usable account/knowledge landing page. Public published knowledge remains a separate deliberate publication grant. Without retained table classification, deleted-record history is restricted to administrators; retained evidence is not deleted.

Compared permissions (4), personal notification controls (6) and event-to-case correlation (13). Select the permission prerequisite before adding more cross-record journeys. Acceptance: administrator configuration preview/install; API/UI read denial and allowed restoration; no table/count/attachment disclosure; record/field/requester boundaries remain cumulative; current role changes; invalid policy rejection; deleted-record audit exclusion; restart and both-theme keyboard accessibility. No inherited/custom roles, tenant isolation, deny-administrator configuration, scripted ACLs or vendor ACL wire compatibility claim.

Official evidence retrieved 2026-11-17: Brazil [ACL types](https://www.servicenow.com/docs/r/platform-security/access-control/acl-rule-types.html), updated 2026-09-10, documents separate table and field checks. [Access Management release notes](https://www.servicenow.com/docs/r/release-notes/access-management-rn.html), same update, describe access analysis for agentic assets and strict denial of nonexistent roles. Merge agent/tool access simulation and unknown-role checks into existing missing WS4/15 P1/P2 policy work, dependent on scoped roles/tool registry; acceptance must explain the actual current principal and fail closed on unknown authority. The page also contains future-labelled Brazil Patch 5 material, which is not treated as currently qualified behavior. Next research: personal notification acknowledgment controls.

Both database regressions first returned 400 for an unsupported read operation. The new supported configuration path and focused SQLite/PostgreSQL/browser journeys now pass, including immediate revoke/restore, role changes, file/report exclusion, empty permitted-table selection and the usable restricted landing page.

QA R7-1: full contract fixtures reproduced an added empty `field_rules` property in legacy schema responses. Preserve absence of optional rules while filtering configured fields, without changing the historical fixtures. Extend acceptance for cumulative field denial, deliberately published knowledge despite draft-table denial, and safe sanitization if a record disappears after an audit event is fetched. Re-freeze and rerun all gates after repair.

Inspected final desktop-light/mobile-dark restricted-workspace screenshots. Final accumulated-change review rechecked configured policy validation, current principal binding, shared SQL filtering for records/attachments/audit/counts/reference validation, field projection, requester/private/group constraints, retained administrator evidence, deliberate publication grants, legacy schema shape and earlier feedback/connector/approval repairs. No new or reopened defect remained and this review required no further repair.

Final gates: 802 tests, zero failures/errors/skips; whole-project Java 7254/7254 lines, 4069/4069 branches, 435/435 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| Configuration.java | 167/167 | 103/103 | 5/5 |
| FieldAccess.java | 36/36 | 26/26 | 7/7 |
| RecordAccess.java | 52/52 | 40/40 | 6/6 |
| RecordPolicy.java | 7/7 | 8/8 | 3/3 |
| Service.java | 487/487 | 274/274 | 35/35 |
| WebController.java | 2273/2273 | 1285/1285 | 42/42 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Enforce configured table read permissions across data surfaces`. No push.

## Round 8 — acknowledge a reviewed notification page

Selected `OS-N17B-08`, workstream 6 / OS-PARITY-02. Personal history has filters and single-message acknowledgment, but no bounded multi-message action. Add `acknowledge_many` for 1–100 distinct own notification IDs and a browser action containing only unread IDs displayed on the reviewed page. New arrivals and messages on other pages stay unread even when search pagination changes. Validate the complete batch before updating; missing/foreign IDs fail atomically, and repeating a valid batch is harmless. Preserve browser filters and the independent delivery preference.

Compared personal notification controls (6), event escalation (13) and migration cancellation (11). Choose the planned small but complete personal-history operation after cross-product permission work. Acceptance: exact displayed window, concurrent later arrival, own-account isolation, 100-item boundary, invalid/duplicate IDs, atomic rollback, idempotent retry, persistence, CSRF/method/query validation and keyboard operation in both themes. Acknowledgment is a user action, not proof that someone read or acted on the messages. No deletion, global-history sweep, scheduled digests or delivery-channel preferences are claimed.

Research retrieved 2026-11-17: official Brazil [portal notification controls](https://www.servicenow.com/docs/r/employee-service-management/employee-experience-foundation/portal-notif-config-form.html) and [Next Experience notification preferences](https://www.servicenow.com/docs/r/platform-administration/advanced-notification-prefrences.html), updated 2026-09-10. Merge category/channel schedules, digests and permission-aware AI preference search into existing missing P2 WS6/15 notification work, dependent on delivery channels, a preference model and scoped search. Acceptance must suppress disabled/scheduled channels without altering security-email delivery and must never expose another user's preferences. The older generic release-notes URL returned 404; no additional release-specific capability is inferred. Next research: reviewed event-to-incident escalation.

The pre-implementation request failed with 400 on both databases. Focused SQLite/PostgreSQL/browser tests now pass, including a 100-item page, a later arrival left unread, whole-batch rejection and rollback, harmless repeated submission and keyboard submission with preserved filters.

Inspected final desktop-light/mobile-dark screenshots. The final accumulated-change review checked authenticated ownership, complete-batch validation before mutation, retry/cleanup semantics, later arrivals, transaction rollback on invalid return filters, CSRF, escaped content, no delivery-preference mutation and earlier table-read/attachment/approval/AI-consent repairs. No new or reopened finding or repair was required.

Final gates: 805 tests, zero failures/errors/skips; whole-project Java 7265/7265 lines, 4089/4089 branches, 436/436 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| Notifications.java | 36/36 | 32/32 | 4/4 |
| WebController.java | 2275/2275 | 1287/1287 | 42/42 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Acknowledge only the displayed personal notification page`. No push.

## Round 9 — reviewed event-to-incident escalation

Selected `OS-N17B-09`, workstream 13 / OS-PARITY-05. Operational events can be searched and acknowledged/resolved, but cannot create a correlated service case. Add administrator review of the current event and creation of one linked incident, with an optimistic fingerprint over the event details. Create the incident privately to the administrator and other administrators; an operator must deliberately review sharing before a team sees event data. Repeated creation returns the same currently accessible case. Later event changes do not overwrite its snapshot or synchronize lifecycle states. A deleted linked case remains unavailable rather than silently creating a replacement.

Compared event escalation (13), durable migration cancellation (11) and richer types (12). Choose the event-to-follower case journey before another platform data refinement. Acceptance: review/current-event conflicts, private defaults, field/write authority, same-event idempotency and concurrent calls, retained linkage/restart, resolved event rejection, independent lifecycles, HTTP/CSRF boundaries, upgrade rollback and both-theme keyboard browser escalation. No automatic correlation rules, security-incident product, automatic remediation, bidirectional state synchronization or custom mapping designer claim.

Official evidence retrieved 2026-11-17: Brazil [alert information and incident creation](https://www.servicenow.com/docs/r/it-operations-management/event-management/t_EMViewAlert.html) and [alert correlation rules](https://www.servicenow.com/docs/r/it-operations-management/event-management/t_EMConfigureAnEventCorrelationRule.html), updated 2026-09-10. The separate create-incident article returned 404, so its cached text is not treated as current confirmation. Merge configurable primary/secondary grouping, maintenance/flapping handling and separately governed lifecycle synchronization into existing missing P2 operations work, dependent on event rules, reliable history and explicit change authority. Acceptance must preserve correlation identities and prevent grouped/private events from widening access. Next research: import progress and cancellation controls.

The first test fixture used the wrong existing event-ingestion route. Correct it to `/api/openservice/operations/events`, then rerun against the prior controller: both databases now demonstrate the intended missing event-case route (404), after successful real ingestion. QA R9-1: the new browser form inherited the GET query string, causing the strict write endpoint to reject submission. Set an explicit query-free form action and retain the browser create/revisit journey as regression coverage. Transactional upgrade and simultaneous direct escalation checks also exercise the durable single-case correlation.

QA R9-2: the earlier read-policy qualification still used present-tense wording after round 7 delivered the supported configuration path. Mark that paragraph historical and explicitly link its superseding evidence; preserve the original checkpoint limitations. No production behavior changes for this documentation repair.

Inspected final desktop-light and mobile-dark screenshots. The final accumulated review rechecked current asset/case access, private initial visibility, strict routes and CSRF, event-review invalidation, repeated escalation, deleted links, concurrent creation and migration rollback. It also revisited earlier catalogue, connector, hold, report, publication, table-read, notification, AI-consent, approval and database-acquisition boundaries against the fixed base. R9-1 and R9-2 remain repaired; this final review found no new/reopened issue and required no further repair.

Final gates: 810 tests, zero failures/errors/skips; whole-project Java 7283/7283 lines, 4113/4113 branches, 438/438 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| EventCases.java | 11/11 | 12/12 | 2/2 |
| Migrations.java | 197/197 | 106/106 | 3/3 |
| WebController.java | 2279/2279 | 1297/1297 | 42/42 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Create one reviewed private incident from an operational event`. No push.
