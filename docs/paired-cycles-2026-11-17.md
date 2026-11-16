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
| 7. Workflow/approvals | 5, 17 | Complete delegation management and actionable pending-approval diagnosis/reminder journeys. Reconcile existing delegation before implementation. |
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
