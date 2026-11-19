# Enterprise workflow implementation pass — 18 November 2026

Base: `519db49c81e40b44784db692b064c516b1cc68da`. Branch: `codex/openservice-enterprise-pass-20261118`. One implementation pass across all seven requested areas, followed by accumulated QA/QC. Changes are isolated in `opensoft-ge-worktrees/openservice-enterprise-pass-20261118`; the main checkout and the completed 50-round branch are untouched. No commit, push or deployment has been made for this pass.

## Delivered behavior

| Area | New working capabilities | Meaningful remaining gaps |
|---|---|---|
| HR | Administrator-reviewed lifecycle cancellation stops unfinished onboarding; every completed step needs a compensation/no-reversal review note. Staff publish selected case documents; the employee downloads only current approved files. Withdrawn, modified, held or unreleased files are unavailable. | Transfer, promotion and leave lifecycle templates; automatic compensation; document signatures and retention. |
| Field service | Technician qualifications, per-task skill requirements, ranked appointment proposals using current team, record access, calendars and reserved visits. Ordinary assignment/acceptance enforces required skills. Search reports when its 2,000-slot limit is reached. | Offline mobile, route/global optimization, crews and geographic capacity planning. |
| Assets | Purchase draft → approval → exact registered asset receiving into native stockroom custody. Retained acquisition values and straight-line monthly book values. Software entitlements support expiry, seat capacity, allocation and release. | Vendor ordering/invoices, multi-line procurement, vendor returns, discovered software reconciliation and accounting integrations. |
| Security | Reviewed ordered response playbooks create native dependent tasks, expose live progress and stop unfinished work. Completed work remains; existing security closure and required-task review still apply. | Threat feeds, external response automation, broader governance and post-incident assessments. |
| Portfolio | Inclusive cross-project capacity reservations enforce 100% per person atomically. Budgets, actual cost receipts, remaining estimates, forecasts and budget variance use exact decimal amounts and one currency. Date changes/closure cannot strand existing reservations. | Resource calendars and rates, multiple currencies, investment scenarios and predictive forecasting. |
| Integrations | Encrypted OAuth client-credentials exchange with recorded expiry and alias-change checks. Incoming values have exact record/field correlation, current source/target review and explicit conflict resolution. Outgoing record projections use existing reviewed durable connector actions. | Certified vendor connectors, authorization-code/refresh-token flows, automatic two-way reconciliation and deletion synchronization. |
| AI agents | Exact delegated record/field grants to API-only agents; dry-run native plan review; explicit approval; durable per-step execution and receipts; current owner/delegate/grant/record checks; revocation and stop. Disabled by default. | Model-generated planning, external tool orchestration, runaway-trigger detection, interoperability and representative quality/cost evaluation. |

The new native API and operator browser forms are reachable from **Enterprise workspaces**, HR/security/project records and field dispatch. Employee documents have a separate owner-scoped portal. Operator forms currently use exact identifiers and structured plan input; guided selectors and graphical plan builders remain UX work.

Schema 99 adds bounded revisioned enterprise documents. Existing native business APIs, permissions and task/custody rules remain authoritative. Plans stop future effects; completed provisioning or external effects are not silently reversed. The agent runner supports at most ten distinct incident/problem/configuration-item updates and commits one native step at a time. It performs no model/provider calls, so passing deterministic tests is not an AI-quality or vendor-parity claim.

The [operations contract](platform-operations.md#enterprise-lifecycle-and-planning-workspaces) lists routes, inputs, limits, flags and failure semantics. The [capability ledger](servicenow-compatibility.md#enterprise-pass--18-november-2026), [roadmap](roadmap.md#enterprise-continuation-after-the-18-november-pass) and [AI requirements](ai-capabilities.md#enterprise-pass-delegated-native-execution) retain every remaining parent gap.

## QA/QC ledger

The fixed scope includes all tracked edits and new source, templates, tests and documentation against the base above. No coverage exclusions, threshold reductions or test suppression were introduced.

| Round | New / reopened / fixed | Verified findings and repair |
|---|---|---|
| 1 | 4 / 0 / 4 | Agent plan pages could prevent stopping after target removal, and grant revocation depended on a usable target/delegate. Safe redaction plus an independent owner-only revoke operation now preserve controls. Project window edits could invalidate capacity reservations; native record saves now guard reservations. Completed response playbooks displayed only their launch state; progress now derives completion from native tasks. The enterprise hub's security link pointed to an absent route; it now uses the existing security workspace. |
| 2 | 4 / 0 / 4 | A null plan target could trigger a server error; it now receives identifier validation. Appointment search supplied fractional seconds while native schedule parsing requires whole seconds; its default now satisfies the parser. Project closure could retain capacity that its closed workspace could no longer release; closure now requires explicit release. OAuth Basic authentication failed to form-encode credentials and could trim meaningful secret characters; encoding now follows RFC 6749 and preserves exact credentials. |
| 3 | 2 / 0 / 2 | The full regression run found two older migration fixtures that treated version 99 as future or removed only version 98. The future-version check now uses `Migrations.VERSION+1`; historical-upgrade setup removes the whole later migration suffix. Both keep the original rejection/rollback assertions. |
| 4 | 1 / 0 / 1 | The operations text described an environment flag as a global stop. It now distinguishes per-instance configuration from a database-backed plan stop, and instructs operators to disable every worker for a system-wide pause. No production change was needed. |
| 5 | 2 coverage gaps / 0 reopened / 2 resolved | All 1,737 tests passed, but the exact gate found two uncovered branches. Removed a redundant write-route exception for the read-only employee documents endpoint and added explicit requester write rejection. Separated the worker transaction body from resource ownership to remove a compiler-generated close branch; existing failure/cleanup tests still pass. No exclusions or thresholds changed. |
| 6 | 0 / 0 / 0 | Repair-free accumulated review of the final changes. The full SQLite, PostgreSQL, browser, migration and coverage gates pass against unchanged source; the exact per-file checks also pass for all sixteen touched production Java files and all three browser scripts. |

Additional review tightened appointment-search work to 2,000 inspected slots with explicit truncation, while preserving live dispatch checks. This is a bounded scheduling proposal, not a global optimizer.

Regressions exercise all seven real HTTP journeys on SQLite and PostgreSQL; malformed/stale input; native authorization; employee ownership and changed-file withdrawal; entitlement expiry/exhaustion; simultaneous cross-project capacity requests; playbook task dependencies and stop; OAuth failures/rotation/special-character credentials; source conflicts and stale outbound review; agent dry-run rollback, restart, completed receipts, stop, grant revocation, target deletion, changed accounts/fields/records and approval expiry. Browser journeys cover keyboard submissions, escaped content, employee document access, all workspace pages, light/dark themes, mobile overflow and axe accessibility. Schema 98→99 tests verify transactional migration rollback, retained prior records and restart persistence on both databases.

## Verification

The full gate passed on 18 November 2026 at 22:04:37 +04:00, in 18 minutes 38 seconds, against a frozen manifest of 749 source/test files:

```sh
OPEN_SERVICE_TEST_POSTGRES_BIN=/opt/homebrew/opt/postgresql@17/bin \
JAVA_HOME=/Users/david/Library/Java/JavaVirtualMachines/jdk-27.0.1.0.2+1/Contents/Home \
MAVEN_ARGS=-DforkCount=4 sh scripts/verify.sh
```

| Check | Final result |
|---|---|
| Automated tests | 1,737 passing across 45 suites; zero failures, errors or skips. This is 35 more tests than the 1,702-test base. |
| SQLite / PostgreSQL workflows | 522 passing tests on each database. |
| Browser / migration suites | 159 browser tests and 152 migration tests passing. |
| Java coverage | 10,339/10,339 lines, 11,477/11,477 branches and 1,165/1,165 methods; all 213 classes and 160,303 instructions covered. |
| Exact changed-file coverage | All sixteen touched production Java files pass the existing line, branch and method checks. |
| Browser JavaScript coverage | `catalogue.js`: 20/20 lines, 11/11 branches, 4/4 functions; `migrations.js`: 82/82, 49/49, 12/12; `theme.js`: 36/36, 15/15, 6/6. |
| Source integrity | All 749 source/test files exactly match the pre-run SHA-256 manifest. |
| Documentation / whitespace | 596 local Markdown links resolve; `git diff --check` passes. |

The source manifest SHA-256 is `57f50a7987e64cd79381ee7794069efc6ee681a6c4526adaabf36ea940ed339c`. Local run evidence is retained in `/tmp/openservice-enterprise-pass/final-verify-2.log`, `final-results.json` and `final-source-hashes.json`. Coverage is execution evidence for these bounded contracts; it does not establish complete ServiceNow feature parity.

Existing release qualifications from the 50-round report still apply: the previous container findings, runtime patch qualification and Spring Session provenance discrepancy are not resolved by this feature pass. No new image, production deployment, live vendor connector certification or real-provider AI evaluation is claimed.
