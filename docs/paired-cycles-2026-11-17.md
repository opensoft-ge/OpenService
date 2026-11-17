# OpenService: 20 paired development and QA/QC rounds, 17 November 2026

Status: in progress. This report records actual implementation and validation; planned rows are not delivered features. Fixed batch review base: `e80a665f121e99489f480131a52ca777ec304282`, clean `main` at start. Each feature receives a local opendevbot commit only after acceptance, exact coverage and a clean accumulated-change QA review. No push or deployment is authorized by this batch.

## Baseline and selection

The preceding 20 feature rounds covered 13 workstreams with at most two features in each. Earlier credential-heavy work remains complete; additional credential refinements do not receive automatic priority. This batch favors complete requester and operator journeys, with AI grounded in existing permission and publication contracts. Candidates compared before round 1: requester knowledge search (9), version-bound AI consent (15), and holiday-aware service calendars (8). Knowledge search supplies a useful requester journey and a prerequisite for grounded conversational support.

Stack checked on 2026-11-17: [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html) report stable 4.1.2 and Java support through 27; [Oracle Java downloads](https://www.oracle.com/java/technologies/downloads/) identify Java 27 as the latest release. Retain the compatible Java 27 / Spring Boot 4.1.2 pins. Runtime qualification and exact patch evidence remain separate from this major-version compatibility check.

## Planned distribution

| Workstream | Planned rounds | Outcome / dependency or deferral |
|---|---|---|
| 1. Shared deployment | None | Actual proxy/TLS/mail/users/ownership require a selected deployed environment; keep M1 open. Local diagnostics already exist. |
| 2. Enterprise identity | None | SSO/MFA needs a coherent provider/recovery contract; existing provisioning is not SSO. Avoid pretending configuration alone qualifies enterprise identity. |
| 3. Account lifecycle | 9 | User profile editing with audited ownership boundaries; inspect existing account naming contracts first. |
| 4. Permissions | 7 | Usable delegated audit-viewer journey; preserve separation from administration. |
| 5. Audit/governance | 13 | Portable retained audit evidence and explicit verification limits. |
| 6. Jobs/notifications | 6, 19 | Search/paginate personal notification history; operators find actionable queued/failed work and recover it. |
| 7. Workflow/approvals | 5, 17 | Automatic review-assignment notices and actionable pending-approval diagnosis/reminder journeys. Individual delegation already exists; timed delegation remains open. |
| 8. SLAs | 3, 15 | Browser management of existing holiday-aware calendars and explainable business-time/deadline previews; preserve persisted clock semantics. |
| 9. Catalogue/knowledge | 1, 11 | Searchable requester publications and administrator version comparison. |
| 10. Integrations | None | Existing bounded connector runtime is retained. OAuth/federation needs a complete provider contract; another credential knob has lower value than the selected journeys. |
| 11. Migration | 10 | Usable reconciliation export/review, with source completeness and live cutover still unverified. |
| 12. Data/API | 12 | A real permission-aware reference picker using the existing search API. |
| 13. Assets/operations | 4, 16 | Find operational events and inspect affected visible services through the UI. |
| 14. Reporting/search | 8, 18 | Saved-report browser management and reuse of an authorized definition as an independently owned report. |
| 15. Governed AI | 2, 14 | Bind summary consent to reviewed content/configuration; a bounded grounded conversational support journey with explicit provider limits. |
| 16. Production/release | 20 | Investigate the existing SQLite contention finding, then qualify the changed runtime and release artifact. No unsupported HA/DR claim. |

Planned total: 20 rounds, 13 workstreams, maximum two rounds per workstream and no consecutive rounds in one workstream. Reassess implementation feasibility at each selection; record substitutions before editing, with concrete evidence. Rebalance after rounds 5, 10, 15 and 20. QA repairs belong to their feature round and do not inflate the feature count.

## Round 1 — requester knowledge search and pagination

Selected gap: `/knowledge` and `/api/openservice/knowledge` currently reject all query parameters and return only the latest 100 publications. Requesters cannot locate an older published answer. Implement literal title/body search and bounded pagination through both surfaces, retaining latest-publication, retirement, current visibility and deleted-record checks. Draft content must never enter results, counts or rendered HTML. Test anonymous/role boundaries, invalid and duplicate parameters, literal wildcard characters, empty/deep pages, visibility changes and light/dark keyboard navigation.

Evidence: [Knowledge Management Service Portal search](https://www.servicenow.com/docs/r/xanadu/servicenow-platform/knowledge-management/knowledge-service-portal-searching.html), retrieved 2026-11-17, explicitly Xanadu portal scope. This is a local OpenService search contract, not ServiceNow query/ranking compatibility. Discovery: [compare article versions](https://www.servicenow.com/docs/r/servicenow-platform/knowledge-management/compare-two-article-versions.html), Brazil page updated 2026-09-10, retrieved 2026-11-17, describes privileged comparison of selected article versions. Track version comparison in the existing knowledge area and planned round 11; no current implementation claim. Next research: AI data privacy and revision-bound consent.

Implemented `OS-N17-01` (workstream 9, OS-PARITY-04): literal current-publication title/body search, bounded pagination and requester browser controls. Draft edits, restrictions, retirement and deletion are excluded from both results and counts. API retains `result` and adds page metadata. No linguistic ranking or attachment search claim.

Final verification: 715 tests, zero failures/errors/skips; Java 6782/6782 lines, 3610/3610 branches, 392/392 methods. Touched Java: KnowledgeSearch 11/11 lines, 6/6 branches, 1/1 methods; PublishedKnowledge 51/51, 16/16, 6/6; WebController 2094/2094, 1067/1067, 41/41. Browser-script exact gates passed. Frozen source matched the final report. QA repair: inaccurate empty-search copy; a subsequent complete accumulated-change review against the fixed base found no new/reopened defects and made no repairs. No unresolved finding in this slice. Local commit: `Add searchable published knowledge for requesters` (this entry is included in that commit).

Baseline: `scripts/verify.sh` passed 712 tests without failures/errors/skips in 4m44s; Java 6771/6771 lines, 3604/3604 branches and 391/391 methods; browser scripts retained exact full coverage. Round 1 regression tests failed on both databases against the prior query-rejecting route. Targeted SQLite/PostgreSQL/browser acceptance then passed; desktop light and 390px dark screenshots were inspected. A misleading empty-search message was corrected before final review. Full regression/coverage passed in 4m42s. Existing calendars already support holidays; round 3 will complete their browser management rather than reimplement exclusion arithmetic.

## Round 2 — consent bound to an AI grounding preview

Selected `OS-N17-02`, workstream 15 / OS-PARITY-06. The existing preview displays a snapshot, but summary execution reselects current content and provider configuration with only a boolean consent flag. Bind execution to a ten-minute opaque authenticated preview token covering actor/auth revision, record/revision/content, model, provider revision/origin and egress/field configuration. Recheck current authority before sending; changed, expired or tampered previews must make no provider request. Keep explicit consent and read-only output. No one-time-use, redaction, model-quality or external-provider qualification claim.

Official research retrieved 2026-11-17: [Data Privacy configuration](https://www.servicenow.com/docs/r/platform-security/data-privacy-classic/configure-now-assist-data-privacy.html), Brazil updated 2026-09-10, covers sensitive-data protection before generative AI; [AI-agent access control](https://www.servicenow.com/docs/r/intelligent-experiences/aia-security-implementation.html), Brazil updated 2026-09-01, distinguishes invocation permissions and execution identities. These support the broader privacy/authority roadmap, not this OpenService token protocol. Merge with existing AI redaction and tool-authority gaps; no duplicate completed capability. Next research: service calendars and schedule administration. Alternative candidates remain calendar management and event navigation.

Acceptance: valid preview sends exactly reviewed fields; missing/malformed/tampered/expired tokens, changed content/configuration, cross-actor use and revoked authority fail before external delivery. Synthetic HTTP provider on both databases, API auth regression and complete exact gates. Results: all selected cases pass, including the reproduced connector-recreation repair. The final full run completed in 4m41s.

QA finding R2-1: connector retirement/recreation resets its revision, allowing an old preview if only alias/origin/revision are bound. Both database regressions reproduced a provider call instead of rejection. The consent fingerprint now includes the encrypted credential envelope as well; it is hashed internally and never returned or logged. The first full run passed, but is superseded by the post-repair run.

Final gates: 715 tests, zero failures/errors/skips; whole-project Java 6805/6805 lines, 3618/3618 branches, 395/395 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| AiConsent.java | 20/20 | 8/8 | 3/3 |
| ProviderAssist.java | 95/95 | 18/18 | 4/4 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Bind AI summary consent to reviewed data and provider authority`. No push.

## Round 3 — browser calendar administration

Selected `OS-N17-03`, workstream 8 / OS-PARITY-02. Calendar validation, holidays, clock snapshots and API saving already exist, but Operations has no creation/editing forms. Add ordinary labelled fields for a named calendar, IANA time zone, daily start/end, weekdays and excluded dates; list saved calendars with editable definitions. Reuse validation/auditing. Existing clocks retain snapshots. No schedule inheritance, split/overnight shifts or graphical calendar claim. Alternatives remain event navigation and approval reviewer selection.

Evidence retrieved 2026-11-17: [Holidays](https://www.servicenow.com/docs/r/platform-administration/time-configuration/c_Holidays.html) and [parent/child schedules](https://www.servicenow.com/docs/r/platform-administration/time-configuration/c_ParentAndChildSchedules.html), both Brazil updated 2026-09-10. Newly tracked `OS-DISC-SCHEDULE-INHERITANCE`: reusable parent/child holiday schedules, missing P2, dependent on cycle-safe versioned schedule composition. Acceptance must reject cycles/conflicting inclusion types and preserve active-clock schedule semantics. This round only exposes the existing independent-calendar contract. Next research: operational-event correlation/navigation.

Acceptance: create/update through protected forms on both databases; persisted holiday dates and audit; existing clock remains unchanged; invalid dates/zones/hours/days and unauthorized/missing-CSRF requests denied; real keyboard journey, mobile/desktop and both themes. Results: both database and browser acceptance passed, including mobile-visible guidance.

Regression tests failed against the prior unsupported form operation on both databases. During UI inspection, the shared `quiet` class hid required weekday/date guidance on mobile; retained visible instructions and added a mobile visibility assertion. Corrected test assumptions to the existing 302 redirect contract. The initial empty data attribute was omitted by template rendering; a stable form marker now supports the browser journey. Final gates passed; counts below.

Final gates: 718 tests, zero failures/errors/skips; whole-project Java 6816/6816 lines, 3623/3623 branches, 397/397 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| CalendarForms.java | 9/9 | 4/4 | 2/2 |
| Operations.java | 131/131 | 91/91 | 5/5 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Manage business calendars through the operations browser`. No push.

## Round 4 — searchable operational events and browser responses

Selected `OS-N17-04`, workstream 13 / OS-PARITY-05. Retained events and audited response APIs exist, but the API only lists the newest 100 and no browser management journey exists. Add administrator-only literal key/summary search, exact severity/state filters, totals and bounded pagination at `/api/openservice/event-search`, plus `/admin/events` with acknowledgement, resolution and reopening forms backed by existing actions. Keep the old list API unchanged. Offset pages are live; ASCII case-insensitive matching, no ranking or external remediation. Historical events may remain after their configuration item is removed; response execution still requires that item to exist. Alternatives: notification history and eligible reviewer selection.

Evidence: [Brazil Event Management](https://www.servicenow.com/docs/r/it-operations-management/event-management/c_EM.html) and [alert correlation rules](https://www.servicenow.com/docs/r/it-operations-management/event-management/t_EMConfigureAnEventCorrelationRule.html), updated 2026-09-10, retrieved 2026-11-17. Discovery merges primary/secondary root-cause grouping and reopening-triggered rule evaluation into the existing OS-PARITY-05 correlation gap (missing P2). Depends on defined rule semantics, asset identity and scope; acceptance must prove deterministic grouping without claiming inferred causality as verified fact. Existing key deduplication is not this richer grouping. Next research: scoped review delegation and eligibility. These are OpenService native filters/actions, not ServiceNow wire parity.

Acceptance: 100+5 paging, exact filtered totals, literal wildcard characters, malformed/duplicate inputs, anonymous/non-admin denial, CSRF-protected audited actions, keyboard pagination, escaped summaries, empty states and both themes at desktop/mobile widths. Results: database and browser acceptance passed; the full gate passed with counts below.

Both database regressions failed against the missing route, then passed. The browser journey passed after making select labels explicit; desktop-light and mobile-dark screenshots were inspected, with escaped summaries and no horizontal page overflow. No confirmed backend defect found during this round. Final gates passed.

Final gates: 721 tests, zero failures/errors/skips; whole-project Java 6841/6841 lines, 3639/3639 branches, 398/398 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| EventSearch.java | 13/13 | 6/6 | 1/1 |
| WebController.java | 2106/2106 | 1077/1077 | 41/41 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Search operational events and manage responses in the browser`. No push.

## Round 5 — automatic review-assignment notifications

Replanned before editing: individual delegation and its browser form already exist; date-bounded delegation requires a larger authority model. Select the missing assignment-notification journey instead (`OS-N17-05`, workstream 7 / OS-PARITY-02): after a successful review request or delegation, atomically persist an in-app notice for the newly assigned reviewer, honoring their business-notification preference. Notices contain only the opaque approval reference and navigation guidance, never record fields or the private review reason. Existing notes remain historical; notices do not confer access. A later reassignment back to the same reviewer produces a new unread notice. No SMTP delivery, timed delegation or automatic approval claim. Alternatives: notification history search and audit-viewer browser grants.

Official evidence: [approval notifications](https://www.servicenow.com/docs/r/servicenow-platform/approvals/c_ReceiveNotifications.html) and [granular delegation creation](https://www.servicenow.com/docs/r/employee-service-management/granular-delegation/create-delegation-admin.html), Brazil updated 2026-09-10, retrieved 2026-11-17. Assignment notifications are established vendor behavior; this slice uses OpenService in-app delivery rather than native email semantics. Merge date-bounded/table-conditional delegation and confidential-notification exclusions into the existing delegation gap, missing P1; current record authority must be rechecked and delegation must not grant underlying access. Next research: notification preferences/history.

Acceptance: initial and delegated recipients receive notices only after successful authorized transitions; rejected/duplicate requests have no notice side effects; opt-out is respected; reassignment creates fresh unread evidence; rollback and restart persistence; notification → My approvals → decision browser journey with unchanged access enforcement and both themes. Results: selected acceptance and the full regression/coverage gate passed.

Both database regressions first observed zero assignment notices; targeted acceptance then passed, including preference suppression, reassignment, rejection, rollback and restart. Existing manual-reminder assertions now explicitly distinguish one assignment notice from one deduplicated reminder. The notification → acknowledgement → My approvals → decision browser journey passed; desktop-light/mobile-dark screenshots and accessibility were inspected. Final gates passed.

Rebalance checkpoint 5 (five implemented and verified feature rounds): rounds 1–5 cover workstreams 9, 15, 8, 13 and 7, one each. Round 3 exposed existing calendar semantics; round 5 replaced already-built delegation with missing assignment notices. No QA repair counted as a feature. Continue rounds 6–10 across notifications (6), audit grants (4), saved reports (14), profiles (3) and migration reconciliation (11). Shared deployment, enterprise identity and integration-provider qualification deferrals remain explicit; no concentration exception is needed.

Final gates: 723 tests, zero failures/errors/skips; whole-project Java 6846/6846 lines, 3639/3639 branches, 399/399 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| Approvals.java | 214/214 | 112/112 | 12/12 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Notify reviewers atomically on assignment and delegation`. No push.

## Round 6 — personal notification history search

Selected `OS-N17-06`, workstream 6 / OS-PARITY-02. The existing inbox returns only the latest 100 messages and rejects search arguments. Add personal literal message search, all/unread/acknowledged filters, exact account-scoped totals and bounded pagination through the existing browser/API. Preserve preference and acknowledgement actions and their ownership/CSRF checks; browser forms retain the validated search selection in hidden return fields while posting to a query-free endpoint. No cross-account/admin inbox inspection, full-text ranking, email history or category subscriptions claim. Alternatives: audit-viewer grants and saved-report management.

Research retrieved 2026-11-17: [notification preferences](https://www.servicenow.com/docs/r/platform-administration/set-notification-preferences.html), Brazil, describes global/category/channel controls; [Core UI preferences](https://www.servicenow.com/docs/r/platform-administration/user-notification-preferences.html), search index Australia scope, describes searchable preference definitions and subscriptions. These are broader notification capabilities, not evidence of OpenService message-history endpoint parity. Merge missing category/channel/conditional subscriptions into the existing workstream-6 backlog, P2, dependent on durable delivery policies; acceptance must honor opt-out and current audience permissions at delivery. Next research: delegated audit administration.

Acceptance: more than 100 personal messages, account isolation before counts/paging, literal wildcard search, unread/acknowledged transitions, unknown/duplicate/invalid arguments, unauthorized acknowledgement, browser filtering/paging/acknowledgement, preference changes from filtered pages, safe rendering and both themes. Targeted database/browser acceptance and the final clean full gate passed.

QA R6-1: the browser regression reproduced a 400 when acknowledgement inherited the filtered page query. The preference form had the explicit action, but the acknowledgement form did not. Both now post to the query-free endpoint with validated return fields. Added rollback assertions for malformed return selection; an invalid preference save must not change delivery preference.

The first full run exposed a test-helper assumption: requester login redirects from the staff homepage. The rollback test now signs in and obtains CSRF from the actual requester inbox; product behavior was correct. This failed run is not counted as passing evidence.

Final QA re-review found no new or reopened defects and made no repairs. Light desktop and dark 390px screenshots were inspected; safe text rendering, account isolation, live pagination and preserved form selection passed.

Final gates: 726 tests, zero failures/errors/skips; whole-project Java 6859/6859 lines, 3651/3651 branches, 400/400 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| NotificationSearch.java | 11/11 | 6/6 | 1/1 |
| Notifications.java | 27/27 | 14/14 | 3/3 |
| WebController.java | 2108/2108 | 1083/1083 | 41/41 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Search personal notification history with preserved filters`. No push.

## Round 7 — audit access from account administration

Selected `OS-N17-07`, workstream 4 / OS-PARITY-08. Separate audit-viewer authority and read-only journals already exist in the API; the account browser cannot grant it. Add an explicit audit-viewer selection to the existing administrator account form (`operation=audit`, `enabled=true|false`). Reuse the existing audited grant/revoke service and its session invalidation. Display the stored grant independently from account role; administrators retain inherent audit access. No custom-role/domain delegation or expanded record access. Alternatives: saved-report management and profile editing.

Official evidence: [granular security roles](https://www.servicenow.com/docs/r/platform-security/platsec-granular-roles.html) and [process administration](https://www.servicenow.com/docs/r/platform-security/c_DelegatedAdministration.html), Brazil updated 2026-09-10, retrieved 2026-11-17. Audit-viewer and configuration authority are separated; hierarchical domain policy overrides are broader. Merge domain-specific policy inheritance and agent-role configuration viewer/admin separation into existing WS4/WS15 missing P1 gaps, dependent on organization isolation and agent execution identities. Acceptance must deny upward/cross-domain writes and preserve current role checks. Next research: saved reports and analytics.

Acceptance: administrator grants/revokes through real protected form; invalid selection, CSRF and unauthorized writes denied; target role unchanged, security evidence retained, browser sessions revoked, newly authenticated audit reader can view/export journals but cannot administer accounts. Both themes/mobile/keyboard and persisted selected state. Targeted database/browser acceptance and the final clean full gate passed.

The new protected-form regression returned 400 against the previous unsupported operation on both databases, then passed with the implementation. Browser inspection found the audit panel heading lacked the established inset/header treatment; corrected the fragment before the final run. No authority or session defect was found in the accumulated-change review.

Post-repair review of the accumulated production changes, callers and boundary tests found no new/reopened defects and made no changes. Final desktop-light/mobile-dark rendering and accessibility passed; grant and revoke preserve role, restrict journal authority and invalidate sessions as documented.

Final gates: 729 tests, zero failures/errors/skips; whole-project Java 6864/6864 lines, 3655/3655 branches, 400/400 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| WebController.java | 2113/2113 | 1087/1087 | 41/41 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Manage separate audit access from account administration`. No push.

## Round 8 — saved-report browser management

Selected `OS-N17-08`, workstream 14 / OS-PARITY-07. Saved-report create/update/run/delete and viewer-specific execution history exist only in the native API. Add browser list, creation, execution and owner-only editing/deletion, with ordinary labelled name/table/measure/group/filter/range/sharing fields. Staff can manage their own definitions; other signed-in users can execute shared definitions using current record/field permissions. Existing limits (100 owned reports; list up to 200) remain explicit. No dashboards, scheduled delivery, report-specific sharing groups or graphical designer claim. Alternatives: profile editing and migration reconciliation review.

Official research: [create Core UI reports](https://www.servicenow.com/docs/r/now-intelligence/reporting/t_CreateYourOwnReport.html), Brazil updated 2026-09-17, and [share Core UI reports](https://www.servicenow.com/docs/r/now-intelligence/reporting/t_ShareASetting.html), Brazil updated 2026-09-24, retrieved 2026-11-17. Legacy Core UI and current Platform Analytics visualizations differ; this is native OpenService management, not vendor wire parity. Merge user/group/role-targeted sharing and natural-language analytics into existing reporting/AI gaps, missing P2, with permission-filtered definitions/results and explicit edit authority. Next research: user profiles and locale preferences.

Acceptance: create/update/delete persistence and owner boundaries on both databases; sharing does not cache the author's private results; revoked field access blocks execution; invalid forms, CSRF and unauthorized mutation fail; keyboard/browser journey and both themes/mobile; shared viewers have no owner controls. Targeted SQLite/PostgreSQL/browser acceptance, stale-definition repair and the final clean full gate passed.

QA R8-1: both database regressions reproduced a 400 that hid the owner's repair/delete controls after a numeric-field permission change. The browser detail now catches calculation failure only for a currently authorized staff owner, shows an explicit unavailable-results message and retains editing/deletion; shared non-owners still receive the original denial. Tests repair the definition to another allowed numeric field. The browser additionally reproduces a field-type change and repairs it. An initial unsupported-PUT assertion was corrected to the existing CSRF-before-routing 403 contract.

Final UI copy review clarified that only successful calculations enter execution history; failed owner-repair views do not. The in-progress full run was stopped and superseded before changing the template; the final gate below measures the corrected tree.

Final gates: 732 tests, zero failures/errors/skips; whole-project Java 6891/6891 lines, 3673/3673 branches, 401/401 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| ReportForms.java | 4/4 | 2/2 | 1/1 |
| WebController.java | 2136/2136 | 1103/1103 | 41/41 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Manage and repair saved reports through the browser`. No push.

## Round 9 — personal profile details

Selected `OS-N17-09`, workstream 3 / OS-PARITY-08. Accounts currently have a login name and security settings but no editable personal display name or job title. Add an owner-only browser/API profile with optional NFC-normalized display name and job title, bounded Unicode text, clearing, persistence and audit of changed field names. Keep login identity, roles and verified email separate. Display profile details to the owner and account administrators; do not create a public directory or claim verified employment. Existing accounts upgrade with empty values. Alternatives: reconciliation review and knowledge-version comparison.

Official evidence: [employee profile](https://www.servicenow.com/docs/r/employee-service-management/employee-experience-foundation/update-employee-profile.html) and [HR profile editing](https://www.servicenow.com/docs/r/employee-service-management/hr-service-delivery/t_CreateOrModifyAUserProfile.html), Brazil updated 2026-09-10, retrieved 2026-11-17. Configurable employee-editable fields and profile privacy controls are broader than this local slice. Merge configurable edit allowlists, manager/team relationships and audience-specific profile visibility into the existing profile backlog, missing P2; depend on directory identity and current field permissions. Do not collect HR-sensitive categories merely because the counterpart supports them. Next research: migration verification and reconciliation.

Acceptance: owner edits/clears persisted fields through both surfaces; forbidden target/role/email keys rejected; anonymous/deactivated accounts denied; Unicode normalization, bounds, control characters and escaped HTML; audit identifies changed keys without retaining profile values; restart and upgrade retain data; requester/reader ownership and CSRF; keyboard/light/dark/mobile. Concurrent profile edits are last-save-wins. Targeted acceptance and the clean full verification passed.

The new PostgreSQL endpoint regression first returned 404. Owner/administrator privacy, changed-key audit, normalization, 80-code-point supplementary characters, clearing, deactivation and repeatable migration 36 passed. Light desktop and dark mobile screenshots were inspected; keyboard and accessibility passed. Accumulated-change QA found no new or reopened defect and made no repairs in its final review.

Final gates: 737 tests, zero failures/errors/skips; whole-project Java 6917/6917 lines, 3701/3701 branches, 404/404 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| Migrations.java | 183/183 | 98/98 | 3/3 |
| Profiles.java | 11/11 | 12/12 | 3/3 |
| WebController.java | 2147/2147 | 1117/1117 | 41/41 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Add private owner-managed personal profiles`. No push.

## Round 10 — migration reconciliation review and download

Selected `OS-N17-10`, workstream 11 / OS-PARITY-03. Local reconciliation already compares retained staging/link snapshots through the API; Migration Studio has no human-readable reconciliation page or download action. Add an administrator-only page linked to the currently selected migration, showing state, compared links, missing staged items, changed destinations and truncation. Provide an authenticated JSON download containing the same bounded diagnostic contract. Preserve the distinction between local checks and source completeness/cutover qualification. No new source extraction, schema translation or automatic rollback claim. Alternatives: article-version comparison and reference pickers.

Official research: [import run details](https://www.servicenow.com/docs/r/integrate-applications/system-import-sets/import-run-details.html), Brazil Workflow Data Fabric updated 2026-09-10, retrieved 2026-11-17. Run counts, row status, target linkage and diagnostic logs are established vendor capabilities. Merge richer per-row transform outcomes and run-level reconciliation evidence into the existing migration diagnostics backlog, missing P2, dependent on durable staging/provenance. Acceptance must distinguish ignored/skipped/error outcomes and never equate retained local rows with complete source export. Next research: knowledge version comparison.

Acceptance: authenticated administrator review/download; anonymous/non-admin/unknown job/invalid query/method denial; download and browser counts agree for clean and changed/missing data; no embedded source credentials or record content; explicit source-completeness warning and truncated lists; selected-job link, keyboard and both themes. All focused checks and the final full gate passed.

Round 10 QA evidence: initial route regressions returned 404 on both databases, then the selected-job browser and database journeys passed. A subsequently added truncation fixture omitted the required `target_id` column; the first full run failed in fixture construction and was stopped. The corrected large-volume fixture passed on both databases before the final full rerun. Rendered light/dark review screens and download contents were inspected. Review continued to the in-flight selector/link interaction before final qualification.

QA R10-1: a browser regression confirmed that the job selector stayed enabled during a blocked source fetch. Because concurrent change handlers are suppressed by the busy guard, a changed selection could leave the displayed reconciliation link for a previous job. The guard now disables selection and hides the review link throughout in-flight work, restoring selection on success or failure; successful refresh exposes the link for the reviewed job. The regression exercises a real delayed source response. The previous passing full gate predates this repair and is superseded by the final run.

Final post-repair QA review of the accumulated change found no new/reopened defects and made no repairs. Browser-script exact scope: migrations.js 79/79 lines, 49/49 branches, 12/12 functions; theme.js 36/36, 15/15, 6/6.

Rebalance checkpoint 10: rounds 1–10 cover workstreams 9, 15, 8, 13, 7, 6, 4, 14, 3 and 11, one each. Actual feature allocation matches the revised plan; QA repairs are included within their feature round. Continue with knowledge comparison (9), reference selection (12), audit checkpoints (5), governed conversation (15) and deadline previews (8), then the remaining five planned rounds. No concentration exception; shared deployment, enterprise identity and integration-provider qualification remain explicitly deferred.

Final gates: 739 tests, zero failures/errors/skips; whole-project Java 6925/6925 lines, 3705/3705 branches, 404/404 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| WebController.java | 2155/2155 | 1121/1121 | 41/41 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Review and download migration reconciliation safely`. No push.

## Round 11 — compare immutable knowledge publications

Selected `OS-N17-11`, workstream 9 / OS-PARITY-04. Publication history and restore exist, but administrators cannot compare selected versions through the browser. Add admin-only browser/API comparison of two explicit immutable versions, defaulting to latest and its predecessor (same version when only one exists), full escaped title/body plus changed-field indicators and retirement metadata. Draft content is excluded, unknown/deleted versions fail, and requester access remains denied. No word-level diff, rich HTML or scoped knowledge-administrator role claim. Alternatives: reference picker and audit evidence review.

Official evidence: [compare article versions](https://www.servicenow.com/docs/r/servicenow-platform/knowledge-management/compare-two-article-versions.html), Brazil updated 2026-09-10, retrieved 2026-11-17, and [Horizon comparison component](https://horizon.servicenow.com/workspace/page-templates/version-compare), Brazil/Knowledge Management Advanced. Merge word-level highlighting, scoped contributor comparison and translation review into the existing knowledge gap, missing P2, dependent on publication/field authority. Native comparison is not vendor UI/API equivalence. Next research: reference selection and qualifiers.

Acceptance: default/same/reversed/unknown versions, changed and unchanged fields, retirement and draft isolation, strict query parameters, denied reader/anonymous/mutation, HTML escaping, keyboard selection and both themes/mobile. Focused and full acceptance passed, including comparison across 103 retained versions.

Final accumulated review checked snapshot lookup, draft isolation, current administrative authority, escaping, bounded numeric parameters and the previous consent, filter and migration interaction repairs. No new or reopened findings; no repair in this final review. Desktop and mobile comparison screenshots were inspected.

Final gates: 742 tests, zero failures/errors/skips; whole-project Java 6940/6940 lines, 3721/3721 branches, 405/405 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| KnowledgeComparison.java | 10/10 | 10/10 | 1/1 |
| WebController.java | 2160/2160 | 1127/1127 | 41/41 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Compare immutable published knowledge versions`. No push.

## Round 12 — choose a reference by its readable label

Selected `OS-N17-12`, workstream 12 / OS-PARITY-01. Permission-aware reference search exists only as an API; the record form expects raw IDs. Add a server-rendered chooser for an existing record's typed reference field: search readable target summaries, page authorized results, select or clear a value, then return to the record. Recheck source/target record, table and field authority on submission and require the displayed source revision. No type-ahead JavaScript, unsaved-record editing, custom qualifier or alternative display-field claim. Alternatives: audit checkpoint review and grounded assistance.

Official evidence: [reference autocomplete](https://www.servicenow.com/docs/r/platform-administration/c_AutoCompleteForReferenceFields.html), Brazil updated 2026-09-10, retrieved 2026-11-17, and [reference field types](https://www.servicenow.com/docs/r/platform-administration/c_ReferenceField.html). Reference choices require table read authority; configured display columns, qualifiers, multi-column matching and inherited/overridden attributes are broader capabilities. Merge those missing P2 features into existing typed-field backlog; acceptance must enforce field visibility before labels/counts and current authority when saving. Next research: externally retained audit evidence.

Acceptance: real selection/clear journey, literal search and pagination, stale revision, hidden/deleted target, revoked field/table access, wrong field/type, CSRF and forbidden reader mutation; existing record validation/approval gates unchanged; keyboard/light/dark/mobile. Focused database/browser acceptance and the clean full gates passed.

Prototype QA before application: avoid rendering an empty reference panel on every record; show a panel only for each currently readable/writable typed reference. Target table read policy must be checked explicitly because `Service.record` itself enforces record visibility, not table role policy. Final source/tests still require verification.

QA R12-1: accessibility inspection reproduced a visible-button/accessible-name mismatch. The selection button now includes its visible “Select record” wording in the accessible name, with the target label appended. A test fixture incorrectly reused an incident-only schema writer for custom tables; it now updates the explicit source/target schema and asserts one affected row. No product authorization bypass was reproduced.

Final round-12 accumulated QA checked current table/record/field authority on lookup and submission, cross-table targets, revision handling, query-free forms, escaping and previous repairs. No new or reopened findings; no repairs in the final review. Mobile dark rendering was inspected and accessibility passed in both themes.

Final gates: 745 tests, zero failures/errors/skips; whole-project Java 6962/6962 lines, 3739/3739 branches, 407/407 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| ReferencePicker.java | 15/15 | 14/14 | 2/2 |
| WebController.java | 2167/2167 | 1131/1131 | 41/41 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Select readable reference records through the browser`. No push.

## Round 13 — retain and verify an audit checkpoint

Selected `OS-N17-13`, workstream 5 / OS-PARITY-08. Audit-viewer grants and SHA-256 retained-prefix verification already exist through the API, but the browser has no checkpoint journey. Add a read-only checkpoint screen, portable JSON download and labelled fields to verify an externally retained checkpoint. New journal entries do not invalidate an earlier prefix; changed/deleted prefix data produces an explicit mismatch. This is tamper evidence only when a trusted checkpoint is retained independently: no signature, immutable storage, protected retention or external forwarding claim. Alternatives: grounded conversational support and SLA deadline explanation.

Official research: [Log Export Service sources](https://www.servicenow.com/docs/r/platform-security/les-log-sources-export.html) and [security posture practices](https://www.servicenow.com/docs/r/platform-security/security-center/best-practices.html), Brazil updated 2026-09-10, retrieved 2026-11-17. External retention, additional workflow/authentication/reference-change log sources and configurable field auditing are broader vendor capabilities. Merge source-complete forwarding, export checkpoints and protected retention into the existing WS5 missing P1 backlog; acceptance must reconcile omitted/duplicate events and detect altered retained evidence. The local hash contract is OpenService-native. Next research: contextual conversational assistance and governed provider execution.

Acceptance: administrator/delegated viewer access, anonymous/ungranted/revoked denial, download equality, strict parameters, valid earlier prefix after append, changed-prefix mismatch, browser download/verification through keyboard in both themes/mobile. Focused and final clean full gates passed.

Focused SQLite/PostgreSQL and browser acceptance passed after the old route returned 404. Review confirmed authorization uses the separate live audit grant, download/verification parameters are strict, downloads contain only checkpoint metadata and verification compares an earlier prefix after later append operations. No new or reopened findings in accumulated-change review; full gate follows.

QA R13-1: the first full run exposed an intermittent pre-existing session-revocation test failure on PostgreSQL: an unordered query assumed its first row was the earliest event. Production revocation and all three events were correct. Added explicit journal sequence ordering to the acceptance query; no product behavior or assertion was weakened. The failed full run is superseded below.

Final post-repair review of the accumulated change verified ordered evidence, live audit authority, strict checkpoint parameters, query/download separation and prior repairs. No new or reopened findings and no repairs in this final review. Desktop/mobile rendering was inspected; no claim of protected retention is made.

Final gates: 748 tests, zero failures/errors/skips; whole-project Java 6969/6969 lines, 3751/3751 branches, 407/407 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| WebController.java | 2174/2174 | 1143/1143 | 41/41 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Retain and verify audit checkpoints through the browser`. No push.

## Round 14 — previewed questions about a record

Selected `OS-N17-14`, workstream 15 / OS-PARITY-06. Existing administrator summary APIs do not offer a contextual browser question-and-answer journey. Add a record-linked assistant: ask one question, inspect exactly the selected record fields, question, provider alias and model, then explicitly send for a read-only answer. The ten-minute consent binds the precise question, record revision/content, actor authority and provider configuration. Render escaped draft text with a server-controlled source link/revision and no action tools. Reject unavailable configuration, changed consent, excessive input/output and provider failures. No transcript is persisted; each new question starts afresh. Repeated valid submissions may incur another provider call; no automatic retries, global spending cap or in-flight cancellation. This narrower slice replaces the drafted persistent-conversation proposal so retention, revocation and multi-turn memory are not silently introduced without their complete lifecycle. Alternatives: deadline explanation and asset impact review.

Official evidence: [Brazil conversational help](https://www.servicenow.com/docs/r/intelligent-experiences/now-assist-skills/conversational-help-skills.html), updated 2026-09-10, retrieved 2026-11-17, explicitly says the legacy skill is hidden on new instances starting Australia; successor synthesized search/document connectors must be tracked separately. [Brazil AI security/privacy](https://www.servicenow.com/docs/r/intelligent-experiences/ai-control-tower/security-privacy-tab.html), same dates, adds access maps and privileged/dormant-agent oversight. Merge these into OS-AI-001/004/005, missing P1/P2 according to authority/evaluation prerequisites; no claim to replicate vendor retrieval or agent governance. Next discovery: schedule-aware deadline explanations.

Acceptance budgets: one provider call per explicit send, question <=2,000 characters, selected record JSON <=16,000 characters, request timeout 30 seconds, maximum 800 completion tokens, response <=64 KiB and draft <=8,000 characters; zero business actions. Synthetic local-provider tests establish transport/authority/escaping contracts, not grounded-answer accuracy, time saved or commercial cost. Real-provider quality/cost/latency qualification remains required before an AI advantage claim. Tested both databases, current role/table access, question/config/record token substitution, CSRF, no egress during preview, failure visibility, no prompt values in security audit, and keyboard/light/dark/mobile. All focused and final full gates passed.

The old browser route returned 404 on both databases. Focused tests now cover preview/no egress, consent substitution between actors/questions/summary actions, source changes, forbidden table reads, disabled/missing provider configuration, failure/size boundaries, escaped output, duplicate sends and metadata-only audit. Visual review found the confirmation checkbox inherited full-width input layout; the existing confirmation-panel treatment fixes its alignment without new theme code. A browser assertion checks the checkbox remains compact. Final gates passed.

QA R14-1/R14-2: accumulated review reproduced missing table-read enforcement in the earlier summary grounding and publication comparison paths: both databases returned 200 after a table policy excluded the administrator role. The new assistant itself denied this case. Moved the assistant guard into shared provider grounding and added the comparison guard. Regression tests cover preview, execution and browser/API comparison; no separate feature is counted for these repairs. The first full run was stopped before completion and is superseded by the final repaired-tree run.

Final review rechecked the accumulated routes, data/role boundaries, input escaping, consent fingerprints, retention limits, migration and previously repaired browser flows. No new or reopened finding remained after the two table-policy repairs. Frozen final source matched the five-minute full verification.

Final gates: 751 tests, zero failures/errors/skips; whole-project Java 7000/7000 lines, 3765/3765 branches, 410/410 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| KnowledgeComparison.java | 10/10 | 10/10 | 1/1 |
| ProviderAssist.java | 96/96 | 18/18 | 4/4 |
| RecordQuestions.java | 25/25 | 10/10 | 3/3 |
| WebController.java | 2179/2179 | 1147/1147 | 41/41 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Add previewed record questions with governed provider consent`. No push.

## Round 15 — explain a business-time deadline before starting work

Selected `OS-N17-15`, workstream 8 / OS-PARITY-02. Active clocks consume calendar time, but administrators cannot preview the date reached by a proposed business-minute budget. Add a read-only calendar preview through browser/API: explicit calendar, ISO-8601 start and 1–525600 business minutes; calculate the exact UTC/local deadline and contributing business windows with the existing timezone/weekday/holiday semantics. Compute through at most 3660 local dates and return at most 100 windows with explicit truncation. No active clock mutation, pause forecast, inherited schedules or arbitrary 24x7 fallback. Alternatives: affected-asset review and approval diagnostics.

Official evidence: [SLA duration and schedules](https://www.servicenow.com/docs/r/it-service-management/service-level-management/c_SLATimingsAndSchedules.html), Brazil updated 2026-09-10, retrieved 2026-11-17. Business duration spans eligible windows; the vendor's optional no-schedule behavior differs from this explicit-calendar contract. Merge original-versus-adjusted breach-time explanations and schedule/paused-time timelines into the existing SLA gap, missing P2, dependent on persisted pause events and calendar composition. Next research: unified dependency maps and impact review.

Acceptance: weekends, holidays, before/after hours, exact boundaries, spring/fall DST timeline duration, bounded horizon/list, invalid dates/budgets/query, administrator boundaries, unchanged clocks/calendar, keyboard and light/dark/mobile. Focused and final full gates passed.

The missing preview route returned 404 on both databases before implementation. Focused database/browser tests passed for holiday/weekend arithmetic, start/end boundaries, spring/fall DST, strict limits and unchanged active clocks. Both theme screenshots were inspected. Accumulated-change review checked the calendar arithmetic against existing clock semantics, prior authority repairs and the complete route/input contracts; no new/reopened finding or repair. Final full verification passed.

Rebalance checkpoint 15: 15 verified rounds cover 12 workstreams, with no workstream exceeding two rounds and no consecutive concentration. Remaining allocation stays 16/assets, 17/approvals, 18/reporting, 19/jobs and 20/production engineering, adding workstream 16 for 13 overall. The bounded stateless assistant replaced the persistent-conversation draft with explicit lifecycle deferrals. No concentration exception; M1–M3 remain unmet.

Final gates: 754 tests, zero failures/errors/skips; whole-project Java 7021/7021 lines, 3793/3793 branches, 412/412 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| BusinessDeadline.java | 17/17 | 18/18 | 2/2 |
| WebController.java | 2183/2183 | 1157/1157 | 41/41 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Explain calendar-based service deadlines in the browser`. No push.
