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
