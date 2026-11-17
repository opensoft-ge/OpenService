# Configuration change review — 17 November 2026

## Scope and selection

OS-CONFIG-REVIEW-01, primary workstream 16 (release assurance), OS-PARITY-09 with OS-PARITY-01/08 support; blueprint A1/A5. Starting commit `83c0c5c65bc6a5bf6cd67fce118bc8b47d89bfe5`, branch `codex/openservice-one-gap-20261117`, isolated worktree `/Users/david/IdeaProjects/opensoft-ge-worktrees/openservice-one-gap-20261117`. The original `main` checkout has unrelated record-following changes, including shared controller, migration, tests and documentation; none are included here. One feature and one local commit are authorized.

The latest 20 committed feature rounds at this baseline span 13 workstreams: WS8 3; WS6/7/9/13/14 2 each; WS4/5/10/11/12/15/16 1 each. WS1/2/3 have no round in this window. Configuration comparison adds release assurance without another credential refinement or consecutive same-workstream round. This is a standalone iteration, not another allocation against the concurrent follow-up batch. Planned/actual allocation: one/one WS16 round; no concentration exception was needed.

Inspected alternatives: saved-report time trends (WS14) need a historical-measurement contract beyond `SavedReports`/`Analytics`; scheduled approval reminders (WS7) need scheduling, deduplication and revocation semantics beyond existing manual reminders. Both remain partial parent capabilities. Shared HTTPS/email qualification (WS1) and enterprise identity (WS2) remain higher adoption priorities requiring a named deployment/provider and operational qualification. This iteration selects a locally testable release prerequisite: administrators currently receive only version numbers from `Configuration.install`, with no explanation of changed fields, workflows or permission rules before promotion.

## Sources and discovery

Retrieved 2026-11-17:

- [Preview a remote update set](https://www.servicenow.com/docs/r/application-development/system-update-sets/t_PreviewARemoteUpdateSet.html), Brazil Build or modify applications, updated 2026-09-10: administrator preview compares local and incoming configuration and describes additions, changes and deletions. This informs the user outcome, not a wire-format or conformance claim. The existing bounded Australia Table API baseline is unchanged; Brazil comparison evidence is explicitly separate. No licensed-instance capture or native update-set import is available.
- [AI Agent Studio release notes](https://www.servicenow.com/docs/r/release-notes/ai-agents-rn.html), Brazil, updated 2026-09-10: deny-by-default agent access and evaluation enhancements. Store AI plugins and entitlements apply; no universal availability is asserted.
- [Test user access to an agentic workflow](https://www.servicenow.com/docs/r/intelligent-experiences/test-aw-access.html), Brazil Enable AI, updated 2026-09-10: select an invoking user and inspect downstream agent/tool ACL decisions. New child gap **OS-DISC-AI-ACCESS-TEST**, missing P1/M2, maps to OS-AI-003/005, OS-DISC-03 and OS-PARITY-06/08. Existing `PermissionComparison` covers records, while `ProviderAssist`, `RecordQuestions` and `KnowledgeQuestions` have no executable agent/tool graph to analyze. Dependencies: scoped agent/tool runtime, subject authority and common authorization decisions. Acceptance: privileged users can inspect the actual allow/deny chain for a chosen identity, including revoked authority and downstream tools, without exposing forbidden source data or executing business effects. Existing MCP client policy and general access diagnostics are reused rather than duplicated.
- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html) identifies 4.1.2 as stable with Java 27 support. [Oracle JDK 27 release](https://docs.oracle.com/en-us/iaas/releasenotes/java-management/jdk-27-release-note.htm) confirms the 2026-09-15 release. Existing Java 27 / Spring Boot 4.1.2 pins remain; tests use installed JDK `27.0.1.0.2+1`. No early-access or framework migration is introduced.

Configuration differences merge into the existing OS-PARITY-09 comparison gap. Persisted history, signed promotion, selective skip/merge and rollback remain open. Next discovery rotation: application configuration history/signature trust and automatic workflow reminder conditions; AI access testing remains a tracked prerequisite for future action execution.

## Acceptance contract

The administrator uses the existing Configuration page or `POST /api/openservice/configuration?dry_run=true`. A valid package returns the existing versions and an additive `changes` list explaining each table as created, updated or unchanged. Comparison excludes the automatic root version increment; nested configuration changes remain visible. Map keys are compared recursively in sorted order; arrays are compared as complete ordered values. Changes to the key sequence of native `states` and `fields` are shown as whole ordered objects because they control the initial workflow state and form layout. Missing properties are distinguishable from explicit JSON null. Paths use JSON Pointer escaping.

The browser presents paths, change types and previous/proposed values, including permission changes. All submitted content is escaped. Previews persist neither definitions, versions nor installation audit events. Installation retains existing version conflict checks and atomic validation, and returns the changes actually installed against the current destination. A preview is advisory: it is not a stored authorization token, mandatory approval or reservation of a destination version. A later install reruns all checks.

Verified acceptance: new/changed/unchanged definitions; nested addition/removal and array changes; safe browser rendering; no preview writes; exact installation receipt; stale preview/install rejection; invalid multi-table rollback; denied anonymous/nonadministrator/CSRF attempts; desktop/mobile keyboard journeys and accessibility in light/dark themes.

## Original implementation validation

Implemented; final whole-project gates passed on 2026-11-17. Frozen production scope: `Configuration.java`, `ConfigurationChanges.java` and `templates/configuration.html`. SHA-256 checks confirmed these files were unchanged throughout the final run. Templates are non-executable markup validated by rendered and browser journeys; no client script or CSS changed. No production readiness or complete ServiceNow parity is claimed.

- Relevant baseline: 50 tests passed, zero failures/errors/skips: `ConfigurationTest`, plus `reusableConfigurationPackagesAndTypedRecords` and `configurationPermissionsPreviewAndConflicts` in both `WorkflowTest` and `PostgresWorkflowTest`.
- Regression demonstrated against old production: `WorkflowTest#configurationPreviewExplainsExactChangesWithoutWriting` failed with `Preview must explain configuration changes` because the old response lacked `changes`.
- Focused implementation gate: 51 tests passed with zero failures/errors/skips (`ConfigurationTest`, `ConfigurationChangesTest`, the new HTTP case on both databases and `BrowserTest#configurationChangesAreReviewableInBothThemes`). Assertions cover persisted installation and audit counts, atomic invalid-package rollback, stale revisions, denied roles and CSRF, unchanged schemas, nested permission/validation changes, missing/null distinctions, ordered arrays and JSON Pointer escaping. Chromium checks keyboard controls, escaped markup and axe WCAG A/AA rules at desktop light and mobile dark sizes. Screenshots were inspected visually.
- Final required gate: `sh scripts/verify.sh` completed successfully with 834 tests, zero failures/errors/skips, including real SQLite/PostgreSQL HTTP suites and Chromium browser journeys. Maven ran `clean verify`, so coverage was generated from a clean output directory. The bundled exact-count gate also passed for both production Java files.
- Final browser-script checks: `migrations.js` 79/79 lines, 49/49 branches, 12/12 functions; `theme.js` 36/36 lines, 15/15 branches, 6/6 functions. The checker verified the measured source against the current files.
- Visual evidence: `target/configuration-review-light.png` (desktop) and `target/configuration-review-dark.png` (390 × 844 mobile), inspected after the focused run and regenerated in the final suite. Comparison tables scroll within their keyboard-focusable regions without whole-page horizontal overflow.

Exact final JaCoCo counters, from `target/site/jacoco/jacoco.xml`:

| Scope | Executable lines | Branch outcomes | Methods |
|---|---:|---:|---:|
| `Configuration.java` | 171/171 | 105/105 | 5/5 |
| `ConfigurationChanges.java` | 24/24 | 24/24 | 2/2 |
| Whole Java project, reported separately | 7370/7370 | 4247/4247 | 448/448 |

The feature removes the opaque-preview gap for native configuration promotion. Persisted history, selective conflict resolution and rollback remain open; scheduled approval reminders (WS7) and historical report measures (WS14) remain independent next candidates. The new AI access-test discovery is recorded as missing, not implemented.

Reproduce the required final gate with Java 27 selected:

```sh
sh scripts/verify.sh
python3 /Users/david/IdeaProjects/opensoft-ge/skills/open-service/scripts/check_coverage.py \
  --report target/site/jacoco/jacoco.xml \
  --file src/main/java/ge/opensoft/openservice/Configuration.java \
  --file src/main/java/ge/opensoft/openservice/ConfigurationChanges.java
```

## QA/QC follow-up

The subsequent [three-round QA/QC review](qa-qc-2026-11-17.md) fixed an order-sensitive comparison defect: reordered state keys can change the initial state and reordered field keys change form layout. These differences now show the complete ordered before/after objects. Unit and SQLite HTTP regressions failed before the repair; PostgreSQL and browser acceptance verify the final behavior. The clean final gate passed 837 tests, with ConfigurationChanges at 26/26 lines, 36/36 branches and 2/2 methods, and whole-project Java at 7372/7372 lines, 4259/4259 branches and 448/448 methods. The original implementation counts above are historical.
