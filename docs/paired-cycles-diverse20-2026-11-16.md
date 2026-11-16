# OpenService diverse 20-round batch — 16 November 2026

Requested scope: 20 sequential open-service feature rounds, each followed by QA/QC, acceptance, exact coverage and a local commit. No push or deployment. Starting commit: `7189162`; branch `main`, clean working tree, 21 pre-existing unpublished commits. Authorship remains the configured opendevbot GitHub noreply identity. No co-author trailers.

QA scope is this requested batch, with fixed base `7189162`. Every review includes the accumulated batch diff. Prior 20 feature rounds were all account/credential administration; the subsequent coordinated foundation commit supplied broader engines. This batch develops those engines into useful operator and user journeys. No workstream receives more than five rounds; allocation is reviewed after each five.

## Allocation before implementation

| Workstream | Rounds | Intended outcome or justified deferral |
|---|---:|---|
| 1 Shared deployment | 10 | Actionable configuration preflight; real public TLS/mail and named owners still require a selected deployment. |
| 2 Enterprise identity | 0 | SSO/MFA need a coherent provider/recovery journey; do not spend this batch on more credential refinements. |
| 3 Account lifecycle | 0 | Existing tranche covered lifecycle; corpus operations and exceptional recovery remain open. |
| 4 Permissions/isolation | 0 | Enforce existing policies in every new slice; custom roles/multitenancy are separate architectural work. |
| 5 Audit/governance | 16 | Verify a retained audit checkpoint against current history; external retention remains separate. |
| 6 Background work | 6, 13 | Schedule queued work and inspect safe execution receipts. |
| 7 Workflow/approvals | 7, 17 | Pending-review reminders and usable proposal diagnostics. |
| 8 SLAs | 4, 15 | Cancel invalid clocks and inspect service clocks by authorized record. |
| 9 Catalogue/knowledge | 3, 12 | Inspect publication history and restore a prior published version without exposing drafts. |
| 10 Integrations | 2 | Retire a connector and prevent later queued execution using it. |
| 11 Migration | 18 | Reconciliation summary for a staged migration before cutover. |
| 12 Data/API | 8, 19 | Bounded query/reference capabilities selected after inspecting existing contracts. |
| 13 Assets/operations | 1, 11 | Enforce impact traversal bounds; acknowledge/resolve operational events. |
| 14 Reporting/search | 5, 14 | Maintain saved report definitions and inspect report execution history. |
| 15 Governed AI | 9 | Permission-aware preview of exactly what assistance would send externally. |
| 16 Release assurance | 20 | Actionable queue metrics and final qualification evidence. HA/load/public deployment remain open. |

All slices retain existing authentication/CSRF, database transactions and field/record policies. API-native capabilities are documented as APIs; this batch does not claim a workflow designer or complete browser administration for every new endpoint. Each round records its precise contract before implementation and its results afterward.

## Research and discovery

Retrieved 2026-11-16. Existing Java 27 / Spring Boot 4.1.2 pins remain compatible with [current Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html), [GA announcement](https://spring.io/blog/2026/10/23/spring-boot-4-1-2-available-now/) and [Java release information](https://www.oracle.com/java/technologies/downloads/). The shell initially selected Java 17; verification explicitly selects the installed pinned Java 27.0.1.0.2+1. No build gate was relaxed.

The existing Australia-oriented compatibility baseline is retained; the following currently served Brazil documents inform explicit native extensions, not independently verified ServiceNow wire compatibility or entitlement claims:

- [Release/new-feature discovery](https://www.servicenow.com/docs/r/release-notes/rn-summary-new-features.html): source document identifies Brazil despite stale Australia search labels. Application signing and protected promotion are a discovery lead, pending underlying technical evidence and local deduplication.
- [Knowledge article versioning](https://www.servicenow.com/docs/r/servicenow-platform/knowledge-management/article-versioning.html): version preservation and making an older publication current; vendor feature requires Knowledge Management Advanced. Existing publication/retirement scope is partial; recovery/history extend OS-PARITY-04.
- [System scheduler](https://www.servicenow.com/docs/r/platform-administration/time-configuration/c_SystemScheduler.html): delayed work and calendar scheduling; native job scheduling extends OS-PARITY-02/10.
- [SLA transitions](https://www.servicenow.com/docs/r/it-service-management/service-level-management/r_SLATransitions.html): cancellation is separate from completion; automatic condition engines remain deferred.
- [Alert acknowledgment and impact](https://www.servicenow.com/docs/r/it-operations-management/event-management/operator-phase-acknowledge-analyze.html): acknowledgment does not itself create an incident. Native operational events remain distinct from full Event Management.
- [OWASP authorization guidance](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html): enforce resource authorization at every entry point and re-evaluate current rights.

An approval-reminder search result resolved to an inaccessible primary page; exact vendor reminder behavior remains unverified. Any reminder implementation will be explicitly native, with a narrow tested delivery contract. Next discovery rotation: signed configuration promotion, attachment governance and federation.

## Round contracts and results

### 1 — OS-D20-01: bounded asset impact (workstream 13; OS-PARITY-05)

Inspection: the existing traversal caps visited nodes only between adjacency lists, allowing a wide second layer to exceed its intended bound. Contract: return at most 100 distinct visible impacted assets, excluding the source; traverse cycles safely; report truncation only when another visible distinct asset exists. Preserve deterministic order, field redaction and hidden-path exclusion. No automatic discovery or incident creation. Acceptance: exact boundary, over-bound fan-out, cycles and existing permission tests on SQLite/PostgreSQL, plus the real API regression.

Baseline: 663 tests, zero failures/errors/skips; Java 6437/6437 lines, 3353/3353 branches, 361/361 methods. Browser JavaScript: migrations 75/75 lines, 49/49 branches, 12/12 functions; theme 36/36, 15/15, 6/6. Full baseline took 9m59s. New API regression passes independently on SQLite and PostgreSQL. Final clean verification passed: **665 tests**, no failures/errors/skips; Java **6439/6439 lines, 3351/3351 branches, 361/361 methods**. Feature file `AssetOperations.java`: **56/56 lines, 16/16 branches, 3/3 methods**; browser script counters unchanged and exact. Status: implemented, local commit recorded by `git log --grep="Bound asset impact traversal"`.

QA/QC review 1: one confirmed bound/truncation defect, fixed; zero reopened, zero unresolved. Review 2 of accumulated code and tests: no new/reopened defects or repairs; all final required checks passed. No unresolved findings. The 102-row adjacency lookahead exceeds the maximum 101 already-visited nodes, so it cannot conceal additional distinct impact at the boundary. Freeze: `AssetOperations.java` is the only production source touched by round 1.

### 2 — OS-D20-02: retire an outbound connector (workstream 10; OS-PARITY-03)

Planned. Existing aliases can be saved/rotated but cannot be removed through the application. Contract: administrator DELETE of an identified alias requires a reason, removes the encrypted credential and writes sanitized audit evidence atomically. Anonymous/non-admin calls fail; unknown aliases are 404; invalid input causes no deletion. Existing queued reads and AI execution fail closed when their alias no longer exists. Recreating an alias is a new explicit administrator configuration action; deletion does not cancel all referencing jobs or revoke the remote provider token. Evidence: [Brazil connection aliases](https://www.servicenow.com/docs/r/platform-security/connections-and-credentials/credentials-connections-alias.html), retrieved 2026-11-16; native deletion/audit behavior is an OpenService contract.

### 3 — OS-D20-03: inspect knowledge publication history (workstream 9; OS-PARITY-04)

Planned. Snapshots exist but cannot be inspected through the management API. Provide authorized, bounded history with explicit truncation and version identity; preserve draft separation, retired-state evidence and source-record visibility. No native Knowledge Management API claim. Exact request/pagination contract will be frozen after implementation selection.

### 4 — OS-D20-04: cancel an invalid service clock (workstream 8; OS-PARITY-02)

Planned. Current controls only pause/resume/complete. Add cancellation as a terminal state with audit evidence, preserving an already detected breach. Prevent later worker processing/resume of a cancelled clock. Automatic SLA conditions and contract repair remain deferred.

### 5 — OS-D20-05: maintain a saved report (workstream 14; OS-PARITY-07)

Planned. Current API can create/delete definitions, but POST with an ID still creates another report. Allow the owner to update the existing definition and sharing state without changing its ID; reject unauthorized edits and invalid/hidden fields, keep current-viewer execution policy. Historical result storage and report version conflicts remain separate.
