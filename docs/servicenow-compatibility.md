# ServiceNow compatibility ledger

Baseline: empty Git repository. The user expanded the first iteration from one feature into a broad, connected foundation with a polished UI, migration and three-cloud Terraform. Reference inventory: `../skills/open-service/references/servicenow-blueprint.md`.

Target: a bounded subset of the **Australia Table API documentation**, accessed 2026-11-13, not verified Australia Patch 6 runtime parity. [Primary reference](https://www.servicenow.com/docs/r/australia/api-reference/rest-apis/c_TableAPI.html). The generic docs URL returned Brazil; no silent mixing of release contracts is intended. There is no licensed-instance conformance capture.

| ID | Inventory | Capability | Status and boundary |
|---|---|---|---|
| OS-001 | A4(a) | Table CRUD and JSON envelopes | Implemented subset: seven configured tables, unversioned and v1/v2 routes, bounded parameters. API details in `api.md`. |
| OS-002 | A1 | Shared schema and persistence | Partial: configurable registry drives typed validation, forms, references and assignment checks; see configuration.md. No native sys_db_object/sys_dictionary CRUD, table inheritance or reference display-value resolution. |
| OS-003 | A2 | Workspaces, forms, lists | Implemented original UI: overview, record forms, search, paging, dictionary, history and migration. No native UI16/g_form compatibility. |
| OS-004 | A4(d) | Encoded queries | Partial: equality, inequality, contains/not-contains, starts-with, ends-with, IN/NOT IN, empty/not-empty, AND and ordering; database filtering. No full grammar or scripting. |
| OS-005 | A2/A4(e) | Identity and access | Partial: local admin/agent/reader/requester accounts, verified emailed or manual invitations/recovery, deactivation, groups, Basic/session authentication and CSRF. OS-047–049 add durable account email, requester ownership and field-read roles. OAuth/SSO, full ACL semantics and domain separation remain missing. |
| OS-006 | A3 | Incident/change/request/problem workflows | Partial: reusable state machines, role-gated transitions, required fields per state and revision checks. No separate approval-request engine or verified vendor state/BR semantics. |
| OS-007 | A1/A4(a) | Audit, journal and files | Partial: persistent record events, internal notes and attachments. Attachment upload/read/delete APIs; no native journal API or full activity semantics. |
| OS-008 | Recommendations | Migration/import/export | Implemented bounded JSON/CSV path with dry-run, source retention, stable IDs and repeat-import skips. See migration limits. |
| OS-009 | A3 | Configuration items/knowledge/groups | Partial: CRUD workspaces, group membership and configurable asset/knowledge authoring examples. No full CMDB/IRE, requester knowledge publishing or discovery. |
| OS-010 | Platform operations | Docker and multi-cloud infrastructure | Docker verified locally; AWS/GCP/Azure validated and mock-plan tested, not deployed. |
| OS-011 | A1/A4(c) | Business rules and Glide runtime | Missing; prioritize a sandboxed, explicitly scoped implementation with source-backed semantics. |
| OS-012 | A4(a) | Aggregate, native attachments, Import Set and Batch APIs | Partial: filtered/grouped count Aggregate API added; attachment upload/read/delete supported; numeric aggregates, attachment updates, Import Set and Batch remain missing. |
| OS-013 | A1/A5 | Flow Designer, SLAs, catalog, update sets | Partial: local configuration packages and configurable workflow policies. Native Flow Designer, SLA timers, service catalogue and ServiceNow update-set compatibility remain missing. |
| OS-014 | Enterprise operation | SSO, fine-grained ACLs, rate limits, workers, migrations | Partial: serialized upgrades, account/session controls, record visibility, field read/write roles, persistent authentication budgets and a durable account-email worker. SSO, full vendor ACL semantics, general background consumers and production qualification remain open. |
| OS-015 | Migration | Direct connector, attachments, journals, differential sync | Partial: OS-046 implements a bounded connected migration workflow; live qualification, universal permission/configuration translation and durable background execution remain open. |

## Build priorities

The [adoption roadmap](roadmap.md) controls implementation order. OS-005/009/014 identity, membership and access prerequisites, supported by migration and reliable-delivery foundations, take priority over optional API extensions. ADOPT-01 through ADOPT-19 make the previously broad identity, operational, governance and service-workflow gaps explicit; their statuses and milestone gates are separate from compatibility and code coverage. Ready P0 work should be selected first in future development cycles.

## Acceptance gates

- Real CRUD persists and is shared by UI/API; all seven record types render and edit.
- Readers cannot mutate; agents cannot approve changes or delete; session mutations require CSRF.
- Invalid bodies, fields, parameters and transitions fail; stale revisions conflict.
- Bound database filters preserve literal wildcard characters and resist query injection.
- Import preview writes nothing; errors prevent partial writes; retries skip existing records; original source exports round-trip.
- Attachments persist and download as files; deletion removes files but preserves audit events.
- The Maven suite runs real HTTP tests against SQLite and disposable PostgreSQL, plus Chromium browser journeys.
- Coverage includes every production Java class with exactly 100% executable lines, methods and branch outcomes. Templates/CSS are checked through rendered views and browser journeys; JaCoCo does not measure visual correctness or Terraform.
- Terraform uses provider validation and mock-provider plan assertions for each target; no claim of a live cloud deployment.

Reproduce results with the commands in the README. Record the final measured counts and checks in `validation.md`. Future iterations should close one ledger gap at a time unless the user expands scope again.

## Count aggregation iteration — 13 November 2026

[Primary Aggregate API reference](https://www.servicenow.com/docs/r/api-reference/rest-apis/c_AggregateAPI.html?contentId=z1y5kSDzdmS5hEs40YMlSQ), Australia documentation updated 2026-04-21. Implements stored-value counts over the full filtered selection, optional groupings and COUNT/field ordering. Numeric aggregate functions and display-value resolution are explicitly rejected. Baseline HEAD: `4360f0a868633f7b7dba9b3b2e415da1d5bcf4c5`.

## OS-004 continuation — membership filters, 13 November 2026

Baseline HEAD 512a471, main, clean tree. Blueprint A4(d). Selected the missing IN/NOT IN subset over native attachments (OS-012, missing) and upgrade migrations (OS-014, missing), extending the common database selection layer used by table and aggregate reads.

Contract: Australia encoded-query operator documentation, dated 2026-03-12, retrieved 2026-11-13 from the [official Australia documentation source](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/platform-user-interface/r_OpAvailableFiltersQueries.md). The generic documentation route now returns Brazil; Australia source is the selected evidence. Literal stored-value comma-list matching is implemented; local limits and empty/null behavior are specified in api.md. No vendor-instance conformance claim.

Implementation: `Service.java`; Java contract and workflow tests run on SQLite and PostgreSQL. Current measurements are in [validation](validation.md).

## OS-005 continuation — local account onboarding, 13 November 2026

Baseline HEAD `f4a2d3e`, branch `main`, clean tree; baseline suite: 88 passing tests. Blueprint A2 and A4(e). Account creation existed only through an administrator API, making a multi-person browser evaluation depend on API tooling. Selected a complete administrator account-list/create workflow. Native Attachment API (OS-012, missing), versioned schema upgrades (OS-014, missing) and real assignment/group membership (OS-009, missing) remain candidates for later iterations.

Primary contract evidence, retrieved 2026-11-13:

- [Create a user, official Australia source](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/platform-administration/user-administration/t_CreateAUser.md), updated 2026-03-12: privileged user administration, unique usernames and a create/list workflow.
- [Assign a role to a user, official Australia source](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/platform-administration/user-administration/t_AssignARoleToAUser.md), updated 2026-09-10: privileged role assignment and additional restrictions on granting administrator access.

The generic documentation route now returns Brazil. The release-specific Australia source above supports the conceptual workflow; this implementation is an original OpenService local-account contract, **not** `sys_user` API or vendor-instance conformance. OpenService requires its own `admin` role and assigns one of three local roles at creation. It does not reproduce `user_admin`, role inheritance, profile fields or the vendor password-setting workflow.

Implemented slice: `GET/POST /admin/accounts`, administrator-only navigation, 25-account pages, shared API/browser validation and password hashing, reader default, inline errors, duplicate protection and immediate sign-in. Detailed input/status/serialization boundaries are in [API scope](api.md#local-accounts). The users table is unchanged. Passwords now have an explicit maximum of 1024 characters on both creation entry points.

Acceptance checklist:

- [x] Administrator lists names/roles, creates a persisted account and sees confirmation; the account can sign in immediately.
- [x] Reader/agent cannot access account administration or elevate privileges; missing/invalid session CSRF cannot create an account.
- [x] Both creation paths reject invalid types, names, passwords and roles; maximum accepted lengths work; passwords/hashes are never returned in the listing or error form.
- [x] Duplicates preserve existing credentials/role; an actual unique-constraint failure rolls back and leaves the database session usable.
- [x] Pagination is bounded and ordered; invalid, repeated, unknown and out-of-range parameters fail explicitly.
- [x] Desktop/mobile Chromium verifies the complete form and subsequent agent/reader access, including absence of document overflow and browser script errors.

Implementation: `Service.java`, `WebController.java`, Thymeleaf templates and CSS. Java contract/workflow tests and Chromium exercise the acceptance checklist. Current exact measurements are in [validation](validation.md).

Only the local onboarding slice is implemented. OS-005 remains partial: no account editing, password reset, deactivation, SSO, inherited roles, customer separation or field/record ACLs. The evaluator walkthrough records these boundaries; this does not establish enterprise production readiness.

## Java 27 migration and preserved attachment work

All production logic now uses Java 27 and Spring Boot 4.1.2. Persisted records, identities, password hashes, events and attachment bytes are retained. The interrupted attachment work is included: authenticated metadata listing with exact `table_name`/`table_sys_id` filters, pagination, metadata lookup and file downloads. Orphan attachments are hidden; parent deletion removes attachment data. Subsequent cycles add binary/multipart uploads and deletion; attachment updates remain outside this subset. The laptop sidebar scrolling repair is preserved and tested through Chromium. Current verification is in [validation](validation.md).

## OS-016 — Binary attachment upload

Implemented bounded slice, blueprint A1/A4(a). Australia Attachment API POST /file (primary source linked above): required parent/filename query fields, raw file body, 201 metadata and Location. This route was previously GET-only and the new positive HTTP regression failed with 405 before implementation. Acceptance passed on SQLite and PostgreSQL: agent/admin uploads, reader/anonymous rejection, session CSRF, exact binary/media-type round-trip, empty and 5 MiB files, oversized rejection, invalid/repeated/unknown/blank parameters, missing/wrong-table parents, safe filenames, atomic validation, unchanged parent revisions, audit events, and additive legacy-schema upgrade preserving bytes. Form-encoded bytes remain opaque; multipart forms are explicitly rejected on the raw endpoint. No multipart upload, attachment updates, antivirus or granular record ACL parity is claimed in this slice. Schema upgrade requires one application instance; legacy/browser uploads retain the octet-stream default. Exact acceptance/coverage and QA base are recorded in [cycle 1](iteration-log.md).

## OS-017 — Attachment deletion

Implemented bounded slice, blueprint A1/A4(a). Australia Attachment API DELETE resource: an empty 204 response and 404 for missing attachments. The route previously rejected DELETE with 405; the new regression failed before implementation. OpenService restricts deletion to local administrators. Acceptance passed on both databases: administrator session with CSRF deletes metadata and bytes, returns an empty 204, preserves the parent/revision and other attachments, and records actor, ID and filename in audit history. Missing/repeated deletion returns 404; unauthenticated, reader, agent, missing-CSRF, query-parameter and wrong-route cases are rejected. The role policy is local, not vendor ACL parity. No attachment update, multipart upload or antivirus is added in this slice. Exact acceptance/coverage and QA base are recorded in [cycle 2](iteration-log.md).

## OS-018 — Multipart attachment upload

Implemented bounded slice, blueprint A1/A4(a). Australia Attachment API POST /upload: parent fields in the multipart body, file-part Content-Type, 201 metadata and Location. The official example uses uploadFile. The positive regression failed with 405 before implementation; a second regression using the documented part name failed with 400 before the QA repair. Acceptance passed on SQLite/PostgreSQL for agent/admin and session-CSRF uploads, literal bytes/media type, empty and maximum-size files, metadata/Location, audit persistence, oversized files, forbidden roles, missing/duplicate/extra fields, missing/multiple/wrong file parts, blank/null filenames, absent part media types, query-string rejection and invalid parent/table pairing. Binary and multipart APIs use the same validation and persistence method. Exactly one uploadFile part and the existing local metadata subset are supported. Attachment updates, malware scanning and native field/record ACL parity remain outside this slice. Exact acceptance/coverage and QA base are recorded in [cycle 3](iteration-log.md).

## OS-019 — Versioned Table API routes

Implemented bounded slice, blueprint A4(a). Australia Table API documents /api/now/{api_version}/table paths and examples v1/v2. Its collection contract distinguishes v1 empty results (404) from v2 (200 with an empty result array). The new positive regression returned 404 on creation before implementation. Both database suites verify v1/v2 creation, shared unversioned records, field projection, ETags, update/PUT, stale-revision conflict, role restrictions, deletion, invalid/repeated parameters, empty/filter-empty/out-of-range pages, unsupported versions and version-preserving Location/Link headers. Only the existing bounded Table API subset is versioned. Other historical vendor-version differences, versioned Attachment/Aggregate APIs and complete vendor parity are not claimed. Exact acceptance/coverage and QA base are recorded in [cycle 4](iteration-log.md).

## OS-020 — Empty and nonempty encoded-query predicates

Implemented bounded slice, blueprint A4(d). Australia operator reference defines ISEMPTY and ISNOTEMPTY as operand-free absence/presence tests. The first HTTP regression failed with unsupported-query 400 before implementation. OpenService specifies missing JSON keys, JSON null and empty strings as empty; whitespace is a value. SQLite/PostgreSQL acceptance covers missing/null/empty/whitespace/nonempty records, both operators, AND/order composition, filtering before pagination, full-selection aggregate counts, contradictory predicates and rejection of operands, unknown fields and OR syntax through both APIs. Stored-value predicates only; no display-value resolution, reference traversal, OR grammar or native scripting is added. Exact acceptance/coverage and QA base are recorded in [cycle 5](iteration-log.md).

## OS-021 — Literal ENDSWITH filtering

Implemented bounded slice, blueprint A4(d). Australia string operator reference defines ENDSWITH as matching a stored suffix. The new real HTTP regression failed with unsupported-query 400 before implementation. OpenService reuses bound, escaped LIKE-family SQL rather than interpreting user wildcard characters. SQLite/PostgreSQL Table and Aggregate cases verify ordinary suffixes versus prefixes/middle text, percent/underscore/backslash literals, injection-shaped quote strings, Unicode, empty operands, null exclusion, system fields, AND/order composition, pagination, counts and invalid fields/operator rejection. Existing database LIKE comparison policy is retained: SQLite default ASCII-insensitive behavior differs from PostgreSQL case-sensitive behavior. No vendor collation, reference/display-value or full encoded-query parity is claimed. Exact acceptance/coverage and QA base are recorded in [cycle 6](iteration-log.md).

## OS-022 — Literal NOT LIKE filtering

Implemented bounded slice, blueprint A4(d). Australia operator reference spells the negative substring operator NOT LIKE with a space. The positive HTTP regression failed with unsupported-query 400 before implementation. OpenService binds and escapes a literal substring, using SQL NOT LIKE. Both database suites verify exclusion of ordinary, wildcard/backslash, quote/injection-shaped and Unicode substrings; absent/empty operands; empty stored strings and null exclusion; AND with ISNOTEMPTY; ordering/pagination; matching full-selection aggregate counts; and unknown fields/unsupported spelling. Existing database case-comparison differences and stored-value-only scope remain documented. No display-value resolution or complete vendor encoded-query parity is claimed. Exact acceptance/coverage and QA base are recorded in [cycle 7](iteration-log.md).

## OS-023 — Optional pagination-header suppression

Implemented bounded slice, blueprint A4(a). Australia Table API documents sysparm_suppress_pagination_header, default false, with true suppressing Link. The new HTTP regression failed with unsupported-parameter 400 before implementation. OpenService accepts strict lowercase boolean values. Both database suites exercise unversioned/v1/v2 collection routes, true/false/omitted flags, unchanged rows/projection/count, all pagination relations, preservation of the explicit false flag in links, empty results and invalid/repeated/single-record parameter rejection. This flag suppresses links only; avoiding the count query is a separate candidate. Existing local pagination/error semantics and bounded Table subset remain in force. Exact acceptance/coverage and QA base are recorded in [cycle 8](iteration-log.md).

## OS-024 — Table reads without a total-count query

Implemented bounded slice, blueprint A4(a). Australia Table API sysparm_no_count defaults false; true avoids the SELECT COUNT(*) query. The new HTTP regression failed with unsupported-parameter 400 before implementation. OpenService uses a bounded extra row for next-page detection and omits unknown total/last-page metadata. SQLite/PostgreSQL tests inspect prepared statements on real JDBC connections: no-count executes the selection without COUNT, while false executes COUNT and returns the total. HTTP cases cover unversioned/v1/v2 paging, first/previous/next links, following next URLs, no last/total headers, projection, exact page size, final/empty/out-of-range pages, suppression composition, false/default behavior and invalid/repeated/resource-only parameters. First/previous/next without last is an explicit local policy when totals are unknown. Count omission does not remove filtering or large-offset costs. Other Table API limitations remain unchanged. Exact acceptance/coverage and QA base are recorded in [cycle 9](iteration-log.md).

## OS-025 — Stable attachment metadata ordering

Implemented bounded slice, blueprint A4(a). Australia Attachment API list documentation supports ORDERBY/ORDERBYDESC terms in sysparm_query. OpenService supports its four existing identifier/name filter fields and adds sys_id as a stable tie breaker. The new populated-list regression failed with 400 before implementation. Both database suites verify ascending/descending filenames, duplicate-name stable ties, multi-key table/name ordering, ID and parent-ID ordering, mixed equality filters, sorting before offset/limit, empty pages, rejected unknown/empty/injected/malformed fields, duplicate parameters and authentication. Mutation counts remain unchanged. Final Java 27 Docker/PostgreSQL acceptance verifies packaging, readiness, persistent records and attachment bytes after container replacement, versioned routes, new query/header behavior and audited deletion. Ordering is limited to sys_id, file_name, table_name and table_sys_id. It does not add size/content ordering, full Table query grammar, attachment updates or vendor-instance conformance. Exact acceptance/coverage and QA base are recorded in [cycle 10](iteration-log.md).

## OS-026 — Serialized versioned schema upgrades

Implemented original OpenService persistence slice, blueprint A1 and platform operations, ADOPT-02. Startup serializes schema/bootstrap changes and journals ordered transactional upgrades. Fresh, legacy and previously upgraded databases retain records and credentials; concurrent starts do not race; failed upgrades roll back; unknown or inconsistent histories reject startup. PostgreSQL metadata is scoped to the current schema. This is a local operational contract, not a ServiceNow database/runtime compatibility claim. See [contract, sources and QA evidence](platform-foundations.md).

## OS-027 — Persistent user identities

Implemented local identity slice, ADOPT-03 / blueprint A1/A4(e): migration backfills stable unique IDs, new/bootstrap accounts receive IDs, sessions bind to identities, and the authenticated current-account API exposes only ID/name/role. Credentials, existing create-account responses and historic actor names are preserved. No native sys_user, membership, recovery or record-isolation parity is claimed. See [cycle 2 evidence and sources](platform-foundations.md).

## OS-028 — Persistent browser-session revocation

Implemented local ADOPT-05/08 slice: persistent authentication revisions, next-request invalidation of all caller browser sessions, self-service UI/API for every role, CSRF protection and fresh-login recovery. No individual-session directory, account lockout, SSO or ServiceNow session API parity is claimed. See [cycle 3](platform-foundations.md).

## OS-029 — Administrator account-state lifecycle

Implemented local ADOPT-05 slice: active/inactive migration, administrator UI/API, denied inactive authentication on every supported path, revocation on effective state changes, idempotency, persistent reactivation and concurrent last-active-administrator protection. Identities, passwords and attribution are preserved. Native sys_user, vendor reference-picker rules and delegated roles are not claimed. See [cycle 4](platform-foundations.md).

## OS-030 — Guarded local role administration

Implemented local ADOPT-05 slice: administrator UI/API role changes, transactional final-administrator protection, immediate subsequent-request Basic permissions and browser session revocation. Account state/identity is preserved. Group inheritance, custom roles and native ServiceNow role APIs remain outside this contract. See [cycle 5](platform-foundations.md).

## OS-031 — Authenticated password changes

Implemented local ADOPT-05/08 slice: current-secret verification, bounded password validation, transactional update and all-browser revocation, UI/API for every local role, and concurrent replay rejection. Native password-reset applications, MFA, notification delivery and recovery are not claimed. See [cycle 6](platform-foundations.md).

## OS-032 — Account lifecycle security journal

Implemented local ADOPT-09 slice: transactionally persisted successful account changes with stable attribution, sanitized details, administrator UI/API and a deterministic 100-event window. Native sys_audit_role/identity plugins, login logging and tamper-resistant archives are not claimed. See [cycle 7](platform-foundations.md).

## OS-033 — Absolute browser-session expiration

Implemented local ADOPT-08 slice: server-side authentication age, an exclusive eight-hour cutoff, invalidation and fresh-login recovery, plus explicit servlet idle configuration. Eight hours is a local policy and does not claim ServiceNow property/default parity. See [cycle 8](platform-foundations.md).

## OS-034 — Manual invitation onboarding

Implemented local ADOPT-04 slice: administrator UI/API issuance/revocation, hashed expiring bearer tokens, issuer-revision binding, CSRF-protected recipient password setup, atomic single-use acceptance and sanitized journals. Vendor application-specific invitation semantics, email delivery, address verification and scoped membership are not claimed. See [cycle 9](platform-foundations.md).

## OS-035 — Administrator-assisted account recovery

Implemented local ADOPT-05/08/09 slice: administrator reauthentication, one-time hashed 15-minute links, replacement/revocation, issuer/target revision binding, recipient password selection, atomic single-use consumption and browser/credential revocation with sanitized audit. It does not unlock inactive users or reproduce vendor delegated reset roles, identity-verification integrations or delivery services. See [cycle 10](platform-foundations.md).

## OS-036 — Private account record visibility

Implemented local ADOPT-06 slice: administrator-selected private owner and consistent visibility across existing record surfaces. This is a local extension, not ServiceNow's programmable ACL engine. See [cycle 11](platform-foundations.md) for evidence, scope and exact counters.

## OS-037 — Group membership administration

Implemented local ADOPT-03 slice: administrator roster, add/remove, persistence, audit and cleanup. Australia group concepts are mapped, while group-role inheritance remains missing. See [cycle 12](platform-foundations.md) for acceptance and exact counters.

## OS-038 — Group-restricted record visibility

Implemented local ADOPT-06 slice: administrator policy selection, current-membership enforcement, revocation and fail-closed group deletion. Australia contextual-security concepts inform this behavior; programmable ACLs and inherited roles remain missing. See [cycle 13](platform-foundations.md) for acceptance and exact counters.

## OS-039 — Persistent password-attempt throttling

Implemented local ADOPT-08 slice: browser and Basic limits, durable counters, fixed automatic expiry, concurrent enforcement and bounded storage. Local fixed-window behavior differs from the vendor's configurable lockout scripts. See [cycle 14](platform-foundations.md) for evidence and exact counters.

## OS-040 — Password-authentication event journal

Implemented local ADOPT-09 slice: durable password-verification outcomes, administrator UI/API, secret exclusion and bounded rolling retention. It is distinct from native ServiceNow event names, SSO and full security monitoring. See [cycle 15](platform-foundations.md) for exact evidence.

## OS-041 — Searchable account administration

Implemented local ADOPT-03/05 slice: administrator username/role/state filters, matching counts, paginated UI/API and sanitized output. It does not reproduce vendor encoded queries or delegated user administration. See [cycle 16](platform-foundations.md) for acceptance and exact counters.

## OS-042 — Journal history navigation and export

Implemented local ADOPT-09 slice: bounded cursor pages, Older/Latest browser navigation and permission-checked selected-page JSON downloads for account and authentication journals. It is not native sys_audit or Log Export Service parity. See [cycle 17](platform-foundations.md) for exact evidence.

## OS-043 — Accurate invitation availability

Implemented local ADOPT-04/05 correction: derived invalidated/unavailable states match issuer and username eligibility; public redemption, replacement guidance and revocation agree. Product-specific vendor magic-link documentation informs lifecycle handling without implying a universal invite API. See [cycle 18](platform-foundations.md).

## OS-044 — Account group-access review and removal

Implemented local ADOPT-03/05/06/09 slice: per-account group review and atomic bulk membership withdrawal with security audit and request-time enforcement. Vendor group-removal workflows inform this local extension; inherited group roles and complete deprovisioning are not implemented. See [cycle 19](platform-foundations.md).

## OS-045 — Private-record access handover

Implemented local ADOPT-05/06/09 slice: atomic administrative handover of live private-record access, including parent-governed attachments, with rollback/concurrency/security evidence. Original record attribution and business assignments remain unchanged. Deleted-record audit policies and other visibility modes are excluded. This is a local extension, not vendor offboarding or task reassignment parity. See [cycle 20](platform-foundations.md).

## Reusable application configuration — 2026-11-14

Implemented bounded slices in OS-PARITY-01/02/09, with reusable case/asset/knowledge examples for OS-PARITY-04/05. [Contract and primary references](configuration.md). Existing API paths and legacy unversioned definitions retain their contracts. Configured tables enforce types and relationships through real entry points. Promotion uses destination revisions and validates affected persisted records atomically; no arbitrary code or vendor package execution is enabled.

Remaining shortlist: typed metrics (OS-PARITY-07); durable delivery/connectors (03 and ADOPT-07); separate approvals/SLA timers (02); requester/field controls (08); discovery/impact (05); grounded, governed AI (06); operational restore and failover evidence (10). Whole-area statuses remain partial or missing. Exact validation results are recorded in validation.md.

## Typed numeric reporting — 2026-11-14

OS-PARITY-07 now includes complete-selection sum/minimum/maximum/average reports for configured integer/decimal fields, optional grouping and encoded filtering, record-level visibility enforcement and a browser workspace. [Contract and evidence](analytics.md). Results use exact decimal accumulation and documented DECIMAL128 averages. SQLite/PostgreSQL tests explicitly cover 10,001 rows and private records; there is no Table API pagination shortcut. This is a local API extension; native numeric Aggregate API compatibility, saved/time-series reports and process mining remain open.

## Bounded recovery verification — 2026-11-14

OS-PARITY-10 / ADOPT-15: [restore-drill.sh](../scripts/restore-drill.sh) successfully restores a quiesced PostgreSQL dump to a separate database volume and verifies state through the rebuilt non-root application. [Scope and instructions](recovery.md). Production recovery objectives, online recovery, cross-version upgrades and high availability remain unverified.

## Paired-cycle discovery checkpoint — 2026-11-14

See [paired-cycle evidence](paired-cycles-2026-11-14.md) for contracts, source retrieval, acceptance, QA rounds and exact coverage. OS-Q10-01 closes initial-state authorization under OS-PARITY-01/02/08 after its gates.

| ID | Status / priority / area | Capability, dependency and acceptance | Evidence |
|---|---|---|---|
| OS-DISC-01 | Missing; P1/M2; OS-PARITY-08 | Granular operational administration, extending ADOPT-03. Separate diagnostic/audit access from identity, configuration and business-data administration; depends on configurable privileges. Prove an operator can inspect permitted diagnostics but cannot grant roles, change schemas or view restricted business records. Current three fixed roles do not provide this separation. | [Granular admin roles](https://www.servicenow.com/docs/r/platform-security/granular-admin-roles.html), retrieved 2026-11-14, Brazil platform security; individual product role scope/entitlements vary. |

Checkpoint 1 reviewed authorization, Brazil release highlights and operational roles. New OS-DISC-01; state-entry correction OS-Q10-01. Next: field ACL/data-policy coverage. All existing M1–M3 adoption gaps remain open.

Checkpoint 2 / OS-Q10-02: internal-note visibility correction advances ADOPT-06 and OS-PARITY-02/08; see [evidence](paired-cycles-2026-11-14.md#cycle-2--internal-note-visibility-os-q10-02). Discovery merged knowledge versioning into OS-PARITY-04 (missing, P1/M2): preserve published content while an authorized author edits a separate draft, support rollback/retirement; depends on publication permissions. Acceptance must prove readers cannot see drafts and publication does not mutate the prior version. Source: [Brazil article versioning](https://www.servicenow.com/docs/r/servicenow-platform/knowledge-management/article-versioning.html), retrieved 2026-11-14; Knowledge Management Advanced plugin required. Next research: comments and journal synchronization.

Checkpoint 3 / OS-Q10-03 adds shared comments while retaining internal-note separation (ADOPT-06, OS-PARITY-02/04/08). [Cycle evidence](paired-cycles-2026-11-14.md#cycle-3--shared-comments-os-q10-03). Discovery merged journal synchronization into OS-015/OS-PARITY-03 (missing, P1/M2): preserve entry attribution and internal/public classification across mapped systems; depends on connector identity mapping, durable delivery and correlation. Acceptance must reject duplicate/replayed entries and prevent an internal-to-public mapping from leaking content. Source: [Brazil journal framework](https://www.servicenow.com/docs/r/service-exchange/service-bridge-v2-expolre-journal-field-framework.html), retrieved 2026-11-14; Service Exchange application/entitlement required. Next research: field ACL/data protection.

Checkpoint 4 / OS-Q10-04 adds configurable field write roles under ADOPT-06 and OS-PARITY-01/08/09; [evidence](paired-cycles-2026-11-14.md#cycle-4--configurable-field-write-roles-os-q10-04). Field read visibility remains missing. New OS-DISC-02 (missing; P1/M2; OS-PARITY-08/10): classify and redact sensitive content, support scoped anonymization jobs and quarantine sensitive attachments until authorized review. Depends on retention policy, field-read controls and durable jobs. Acceptance: forbidden input is rejected or redacted consistently, quarantined files cannot be downloaded by other users, jobs expose failures without logging sensitive originals. [Data Privacy release notes](https://www.servicenow.com/docs/r/store-release-notes/store-plat-sec-rn-data-privacy.html), retrieved 2026-11-14, Store 9.1.1 and earlier; product licence/family requirements apply. Current source has no classification/anonymization/quarantine engine. Next: table privileges and access diagnostics.

Checkpoint 5 / OS-Q10-05 adds table record-mutation policies (ADOPT-06; OS-PARITY-01/08/09); [evidence](paired-cycles-2026-11-14.md#cycle-5--table-record-operation-policies-os-q10-05). New OS-DISC-03 (missing; P1/M1; OS-PARITY-08): explain effective access and compare users/roles before applying permission changes. Depends on shared policy evaluation; acceptance must match actual allowed/denied requests, exclude unauthorized subject/data inspection and expose which policy constrains an operation. [Access Analyzer release notes](https://www.servicenow.com/docs/r/store-release-notes/store-platsec-rn-access-analyzer.html) and [permission analysis](https://www.servicenow.com/docs/r/zurich/platform-security/access-control/export-access-analyzer-queries.html), retrieved 2026-11-14; Store application licensing/family requirements apply. No diagnostic endpoint existed in the inspected code. Next: self-service permission diagnostics, with simulation/comparison carried forward.

Checkpoint 6 / OS-Q10-06 implements current-caller mutation diagnostics; OS-DISC-03 is now partial, with user comparison, simulation and UI still missing. [Evidence](paired-cycles-2026-11-14.md#cycle-6--current-caller-permission-diagnostics-os-q10-06). New OS-DISC-04 (missing; P1/M2; OS-PARITY-09/10): reusable configuration-health checks with persisted findings and scan scheduling; existing package validation is only a partial prerequisite. Depends on durable jobs, permission-aware checks and finding lifecycle. Acceptance: detect a seeded unsafe/stale configuration, identify its object without leaking protected values, record resolved findings after repair and bound concurrent scans. Sources: [Instance Scan](https://www.servicenow.com/docs/r/platform-administration/instance-scan/hs-landing-page.html), [configuration](https://www.servicenow.com/docs/r/platform-administration/instance-scan/hs-intro-scans.html), retrieved 2026-11-14 (Brazil overview/Australia configuration; deployment scope must be verified). Next: account offboarding and assignment continuity.

Checkpoint 7 / OS-Q10-07 provides a configured-assignment directory for active/inactive accounts (ADOPT-05/06; OS-PARITY-01/08). [Evidence](paired-cycles-2026-11-14.md#cycle-7--assigned-work-inventory-for-offboarding-os-q10-07). Discovery merged individual-session inspection/termination into ADOPT-08 (missing subcapability, P1/M2): allow a user/admin to identify and revoke one session without ending unrelated sessions; depends on a persistent session inventory. Acceptance must revoke only the selected session while account-wide revocation still works. [Managing user sessions](https://www.servicenow.com/docs/r/platform-administration/user-administration/c_ManageUserSessions.html), retrieved 2026-11-14, Brazil platform administration. Next: authorized assignment handover and membership eligibility.

Checkpoint 8 / OS-Q10-08 implements atomic selected-assignment handover, separate from private-access handover (ADOPT-05/06/09; OS-PARITY-01/02/08). [Evidence](paired-cycles-2026-11-14.md#cycle-8--atomic-selected-assignment-handover-os-q10-08). Discovery merges notification categories, subscriptions and channel preferences into OS-PARITY-02/ADOPT-07 (missing, P1/M2; durable delivery remains P0 prerequisite). Acceptance: current record permissions, account status and opt-outs must be checked at delivery; internal notes must not reach reader channels. [Notification preferences](https://www.servicenow.com/docs/r/platform-administration/set-notification-preferences.html), retrieved 2026-11-14, Brazil platform administration; individual channel availability depends on configuration. Next: preview and concurrency safeguards for bulk changes.

Checkpoint 9 / OS-Q10-09 adds repeatable assignment-handover preview with rollback of assignment/revision/audit rows (ADOPT-05/06/09). [Evidence](paired-cycles-2026-11-14.md#cycle-9--assignment-handover-preview-os-q10-09). Discovery merges side-by-side configuration comparison and explicit per-conflict decisions into OS-PARITY-09 (missing subcapability, P1/M2; current package validation/version checks are partial prerequisites). Acceptance: show incoming/local differences, reject unresolved dependencies or stale approvals, and preserve unselected local configuration. [Update-set collision resolution](https://www.servicenow.com/docs/r/application-development/system-update-sets/update-set-collisions.html) and [preview guidance](https://www.servicenow.com/docs/r/platform-administration/ia-des-conf.html), retrieved 2026-11-14, Brazil application development/administration; the latter applies to Otto for Setup. Business-assignment preview is a local extension, not ServiceNow update-set compatibility. Next: record-change safeguards between preview and apply.

Checkpoint 10 / OS-Q10-10 adds optional API and mandatory browser revision preconditions for atomic assignment handover (OS-PARITY-01/08; ADOPT-05/06/10). [Evidence](paired-cycles-2026-11-14.md#cycle-10--revision-safe-assignment-handover-os-q10-10). Discovery merges durable Kafka producer/consumer connectors, topic-level permissions, retries/reconciliation and consumer lag/failed-message visibility into OS-PARITY-03 (missing, P1/M2; depends on durable jobs/outbox, credential management and connector identities). Acceptance: replay after a restart does not duplicate business effects, forbidden topics are inaccessible, and interrupted/failed processing exposes recoverable status and lag. These are OpenService acceptance goals, not a claim that the vendor guarantees exactly-once delivery. [Stream Connect](https://www.servicenow.com/docs/r/integrate-applications/integration-hub/stream-connect-apache-kafka.html) and [release highlights](https://www.servicenow.com/docs/r/release-notes/rn-summary-highlights.html), retrieved 2026-11-14, Brazil Workflow Data Fabric; Workflow Data Fabric and Stream Connect subscriptions are required for that vendor product. Next: durable outbox/delivery foundations and requester/field-read access. M1–M3 remain unmet.

## OS-046 — Connected, resumable migration (2026-11-14)

ADOPT-14; OS-PARITY-01/02/03/08/09/10. Contract, official source URLs, API/UI behavior and explicit exclusions are recorded in [migration.md](migration.md). The provisional Australia target is unchanged; newer Brazil documentation is research evidence, not vendor-instance conformance.

Implementation: migration schema 13, `ServiceNowSource`, `TransferJobs`, `TransferApply`, administrator routes, guided browser workspace and private staging. Synthetic acceptance covers real HTTP transport, both databases, interrupted fetch/resume, atomic dry run, configuration/value mapping, inactive accounts, mapped roles and memberships, private records, binary checks, archived history, unchanged/changed synchronization, identifier/name conflicts, stale snapshots, local edits, missing-source reports, origin/auth limits and browser cutover. Source scripts and arbitrary ACL/integration logic are not executed.

The five MIGR-01–05 roadmap entries retain the unfinished parent capabilities. Discovery is reconciled into those existing items: source ACL/retention boundaries prevent an unconditional completeness claim; source update sets are not portable executable applications; a cutover record cannot reverse arbitrary external business actions. Next: source metadata assessment and authorized-instance reconciliation, then durable workers and explicit conflict decisions. No telecom-specific behavior or external customer data was introduced. Exact final verification is recorded in [validation.md](validation.md).

## OS-047–050 — Release foundation (15 November 2026)

OS-047 adds durable leased account-email delivery (ADOPT-07; PARITY-02/03/08/10), including encrypted transactional payloads, bounded retries, crash recovery and administrator-visible failures. OS-048 adds verified emailed invitation/recovery and complete requester onboarding/deactivation acceptance (ADOPT-04/05/08). OS-049 adds configurable field-read roles, query enforcement and requester-owned intake (ADOPT-01/06; PARITY-01/04/07/08/09). OS-050 qualifies CI startup, installation, schema upgrade/restore, dependencies, images, publication secrets and bounded accessibility (ADOPT-02/15/17/18).

Contracts, final coverage, acceptance evidence, QA repairs and explicit remaining gates: [release qualification](release-qualification.md), [delivery](delivery.md), [field policies](configuration.md#field-read-roles). These are bounded local extensions, not native ServiceNow Email API, scripted ACL or catalogue parity. Parent workstreams remain partial. The Brazil field-query ACL and email-diagnostics discovery checkpoint is deduplicated into the existing roadmap; next discovery area is approval delegation and schedule-aware SLA processing.

## OS-051 / UX-001 — Professional light and dark themes

Original professional blue-grey/slate styling with a persistent, keyboard-accessible toggle on signed-out and authenticated views. System preference is the initial default; manual choice is browser-local and blocked storage degrades gracefully. Shared semantic tokens cover all existing templates, native controls and focus/status states. [Product experience](product-experience.md) defines the contract, acceptance coverage and UX-002 backlog for administrator theme publishing/account synchronization. [Validation](validation.md) records exact counts; this UI slice does not establish Theme Builder, UI16 or g_form compatibility, nor full accessibility certification.

## Twenty-cycle delivery — November 2026

See the [cycle evidence ledger](paired-cycles-2026-11-15.md). OS-A20-01 adds complete protected queue diagnostics to OS-PARITY-10/ADOPT-07, with full acceptance and exact coverage passed. The Brazil discovery sweep also merges AI-admin readiness into the unimplemented OS-PARITY-06 backlog; neither addition expands native API compatibility claims.

OS-A20-02: delivery directory state/kind filters and bounded pages are implemented with acceptance and exact coverage passed, extending the local PARITY-10 operating contract. Noncascading/time-bounded delegation remains missing under PARITY-02/08; see the roadmap and cycle evidence.

- **OS-A20-03 / ADOPT-07 / PARITY-02/08/10**, implemented: administrator cancellation of queued/failed account deliveries, payload erasure and audit; processing attempts cannot be cancelled. Local extension, not a ServiceNow API. [Cycle evidence](paired-cycles-2026-11-15.md#cycle-3--cancel-unsent-account-messages-os-a20-03). Brazil [email log](https://www.servicenow.com/docs/r/platform-security/r_EmailLogs.html) and [email retention](https://www.servicenow.com/docs/r/platform-administration/email-retention.html), retrieved 2026-11-15. Retention/archiving rules remain missing, P1/M2 under ADOPT-07; require terminal-state policy and audit, preserving active work and links. Next research: retention controls and approval foundations.

- **OS-A20-04 / ADOPT-07/16 / PARITY-10**, implemented: previewed, confirmed administrator retention of at most 500 delivered/cancelled metadata rows per action, 30–3650 days after link expiry; preserves unfinished work and audit. [Cycle evidence](paired-cycles-2026-11-15.md#cycle-4--bounded-delivery-metadata-retention-os-a20-04). Local extension; [Brazil email retention](https://www.servicenow.com/docs/r/platform-administration/email-retention.html), retrieved 2026-11-15. Archives, scheduling and organizational holds remain missing P1/M2. Wider [Brazil feature discovery](https://www.servicenow.com/docs/r/release-notes/rn-summary-new-features.html) merges deadline-bound approval reminders into PARITY-02/06, missing P1/M2, dependent on approvals and durable authorized notifications; test no reminders after completion/revocation or duplicate restart processing. Next: profile-email safeguards and approval lifecycle.

- **OS-A20-05 / ADOPT-03/05/07/08 / PARITY-08**, implemented: first verified recovery-email enrollment for existing local accounts, with reauthentication, cancellation/resend controls, durable delivery, revision-bound expiring links and session revocation. [Contract](email-enrollment.md), [cycle evidence](paired-cycles-2026-11-15.md#cycle-5--verified-email-enrollment-for-existing-accounts-os-a20-05). [Brazil verification enrollment](https://www.servicenow.com/docs/r/servicenow-platform/password-reset/c_PWRVerifications.html) and [OWASP authentication](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html), retrieved 2026-11-15. Local link protocol, not vendor code/timing compatibility. Registered-address replacement remains missing P0: authenticated/reverified requests, old/new-address proof and explicit recovery exceptions. Multiple recovery factors/configurable strength remain missing P1/M2 under ADOPT-05/13; depend on MFA/factor revocation and must prevent fallback bypass. Next: password screening and protected profile changes.

- **OS-A20-06 / ADOPT-02/05/08 / PARITY-08**, implemented: password-change reauthentication shares durable five-failure/five-minute limits and sanitized authentication events; post-verification mutation locks and original caller revision checks prevent stale-session changes. Both database and browser acceptance include expiry/restart, denied mutations and session revocation. [Cycle evidence](paired-cycles-2026-11-15.md#cycle-6--throttle-password-change-reauthentication-os-a20-06). [Brazil policy properties](https://www.servicenow.com/docs/r/platform-security/authentication/password-policy-properties.html), [excluded-password administration](https://www.servicenow.com/docs/r/platform-security/authentication/blacklist-passwords.html), and [OWASP authentication](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html), retrieved 2026-11-15. Local limits are not vendor-default parity. Remaining P0: shared maintained password screening across creation/change/recovery, with no secret disclosure or bypass; policy administration and login-time policy enforcement remain missing. Next: recovery reauthentication and password screening.

- **OS-A20-07 / ADOPT-05/08/09 / PARITY-08**, implemented: administrator-assisted recovery confirmations use durable shared throttling and sanitized events, then recheck current authority and original caller revision under reacquired locks. Failed attempts preserve existing links; browser/API expose the bounded retry response. [Cycle evidence](paired-cycles-2026-11-15.md#cycle-7--throttle-administrator-assisted-recovery-os-a20-07). [Brazil service desk recovery](https://www.servicenow.com/docs/r/servicenow-platform/password-reset/reset-password-for-user.html), [process configuration](https://www.servicenow.com/docs/r/servicenow-platform/password-reset/configure-password-reset-form.html), and [OWASP recovery](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html), retrieved 2026-11-15. Dedicated reset roles and configurable target/verification policies remain missing P1/M2 under ADOPT-05/13; depend on scoped admin rights and enrolled factors, with tests for unauthorized targets, self-granted recovery authority and retained verification decisions. Manual identity verification remains an operator responsibility. Next: maintained password screening and own-account security visibility.

- **OS-A20-08 / ADOPT-08/09 / PARITY-08**, implemented: every active role can browse/download its own retained verification events with strict account-owned cursors and a four-field projection; other identities and global sequence counters stay private. [Cycle evidence](paired-cycles-2026-11-15.md#cycle-8--private-self-service-authentication-history-os-a20-08). [Brazil Identity Center](https://www.servicenow.com/docs/r/platform-security/identity/identity-center-for-user.html), [login history](https://www.servicenow.com/docs/r/platform-security/identity/login-history.html), [Access Observer](https://www.servicenow.com/docs/r/platform-security/review-access-obs-logs.html), and [OWASP logging](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html), retrieved 2026-11-15. Local 100-row/10,000-global-event policy differs from vendor role/retention defaults. New/merged P1/M2 gaps: trusted-device/individual-session inventory and revocation (ADOPT-08/13; requires owned persistent session/device records and revocation tests), and configurable column-read observation (ADOPT-09; requires central bounded instrumentation, protected export and tests excluding secrets/content). Next: maintained password screening and approval lifecycle.

- **OS-A20-09 / ADOPT-02/05/08 / PARITY-08**, implemented: administrator-managed custom whole-password exclusions, hashed at rest, audited and enforced across all password-setting routes with existing credentials preserved. Schema 16; bounded list/pagination, duplicate concurrency, rollback, restart and browser light/dark acceptance. [Contract](password-exclusions.md), [cycle evidence](paired-cycles-2026-11-15.md#cycle-9--administrator-managed-password-exclusions-os-a20-09). [Brazil exclusions](https://www.servicenow.com/docs/r/platform-security/authentication/blacklist-passwords.html) and [NIST password guidance](https://pages.nist.gov/800-63-4/sp800-63b.html), retrieved 2026-11-15. Maintained common/compromised screening, 15-character single-factor minimum migration and NFC handling remain P0 gaps; this is not a maintained corpus or NIST conformance claim. [Advanced approval release notes](https://www.servicenow.com/docs/r/store-release-notes/store-rn-som-advanced-approval-mgmt.html), retrieved 2026-11-15, add recall and ad-hoc approver insertion to PARITY-02 (missing P1/M2; requires persisted decisions and revision binding; old decisions must not authorize changed records). Next: credential-policy gaps and approval lifecycle.

- **OS-A20-10 / ADOPT-02/05/08 / PARITY-08**, implemented: one 15–1024 Unicode-code-point validator for all new credentials and first administrator setup; existing credentials/hashes stay usable. Browser creation, recovery and reauthentication fields accommodate 1024 supplementary characters. [Cycle evidence](paired-cycles-2026-11-15.md#cycle-10--consistent-minimum-length-for-new-passwords-os-a20-10), [API compatibility](api.md#new-password-length-and-compatibility). [Brazil policies](https://www.servicenow.com/docs/r/platform-security/authentication/set-your-password-policy.html), [first-login setup](https://www.servicenow.com/docs/r/platform-security/authentication/reset-your-password.html), and [NIST](https://pages.nist.gov/800-63-4/sp800-63b.html), retrieved 2026-11-15. Minimum-length gap closed for new credentials; maintained screening and normalization remain missing P0. Merge mandatory first-login credential replacement without Basic/API bypass into ADOPT-02/05/08 (missing P0; depends on durable reset state and restricted sessions). [Brazil release discovery](https://www.servicenow.com/docs/r/release-notes/rn-summary-new-features.html), retrieved 2026-11-15, reconfirms upgrade-readiness pre-checks under PARITY-09/10 (missing P1/M2; requires persisted configuration/dependency checks and recovery evidence). Next: first-login and session security.

- **OS-A20-11 / ADOPT-02/05/08 / PARITY-08**, implemented: persisted administrator-required password replacement, old-session/link revocation, a minimal light/dark replacement page and default denial of ordinary API/browser access, including Basic. Only successful password change/recovery clears the flag; retries are idempotent and inactive accounts stay inactive. Schema 17. [Contract](required-password-change.md), [cycle evidence](paired-cycles-2026-11-15.md#cycle-11--administrator-required-password-replacement-os-a20-11). [Brazil reset enforcement](https://www.servicenow.com/docs/r/platform-security/instance-security-hardening-settings/sc-enforce-password-reset-on-api-requests.html), [first-login setup](https://www.servicenow.com/docs/r/platform-security/authentication/reset-your-password.html), [session administration](https://www.servicenow.com/docs/r/platform-administration/user-administration/c_ManageUserSessions.html) and [OWASP authentication](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html), retrieved 2026-11-15. Automatic requirements on newly provisioned accounts remain missing P0; this is an explicit administrator action. Merge expiry warnings/explicit bounded renewal into ADOPT-08 (missing P1/M2; requires authoritative expiry, no absolute-limit extension and revoked-session denial). Per-session inventory remains missing. Next: provisioned first-login defaults and session controls.

### 15 November — OS-A20-12 provisioning-time password replacement

OS-PARITY-08 / ADOPT-01: administrator-created accounts can atomically require first-sign-in replacement. The API adds an optional strict boolean, default false for existing clients; the browser defaults to Required with explicit opt-out. Existing Basic/session restrictions apply. See [contract](required-password-change.md) and [cycle evidence](paired-cycles-2026-11-15.md). Organization-wide mandatory policy and dedicated integration identities remain missing.

Discovery merges catalogue draft/published version isolation into OS-PARITY-04/09, missing P1/M2: [Brazil Catalog Builder](https://www.servicenow.com/docs/r/servicenow-platform/service-catalog/catalog-builder.html), updated 2026-09-10, retrieved 2026-11-15. Depends on catalogue offerings, versioned configuration and approval records. Acceptance: draft edits do not disturb the published offering, unpublished versions cannot leak through requester search, and an old approval cannot publish a changed revision. Next research: dedicated integration identities and delegated permissions.

### 15 November — OS-A20-13 API-only accounts

OS-PARITY-08 / ADOPT-08/13: local API-only account mode, session/link revocation and final browser-administrator protection are implemented as a bounded channel restriction. Existing authorization and required-password-change rules remain in force; scoped tokens, machine identity provisioning and OAuth remain missing. See [contract](api-only-accounts.md) and [cycle evidence](paired-cycles-2026-11-15.md). Source: [Brazil non-interactive sessions](https://www.servicenow.com/docs/r/platform-administration/user-administration/c_NonInteractiveSessions.html), updated 2026-09-10, retrieved 2026-11-15.

Wider discovery: [Brazil Ask for Approval](https://www.servicenow.com/docs/r/build-workflows/workflow-studio/ask-approval-flow-designer.html), updated 2026-09-10, retrieved 2026-11-15. Merge configurable inactive-approver handling and explicit timeout outcomes into OS-PARITY-02 (missing P1/M2). Depends on persisted approvals and durable deadlines; acceptance must prevent silent approval after deactivation, unobservable stranded work and duplicate terminal actions after restart. Next research: persisted approvals and permission-aware decisions.

### 15 November — OS-A20-14 assigned staff review evidence

OS-PARITY-02/08/09: revision-bound requests and single-use assigned approval/rejection, persisted audit, strict role/record/state-field boundaries, pagination and accessible browser forms. [Local contract and limitations](approvals.md), [acceptance and exact coverage](paired-cycles-2026-11-15.md). This records evidence only; state enforcement and automated execution remain missing. [Brazil approvals](https://www.servicenow.com/docs/r/servicenow-platform/approvals/r_Approvals.html), updated 2026-09-30, retrieved 2026-11-15, informs the reusable capability without native endpoint parity. Wider [approval reauthentication](https://www.servicenow.com/docs/r/servicenow-platform/approvals/approval-with-e-signature.html), updated 2026-09-10, retrieved 2026-11-15, merges signer/time/meaning plus configurable reauthentication into OS-PARITY-02/09, missing P1/M2, dependent on persisted approvals/authentication; failed verification must leave the decision unchanged. Next: prerequisites and delegation/withdrawal.


### Personal assigned-review queue — OS-A20-15

OS-PARITY-02/08: staff can discover their assigned pending/approved/rejected reviews through **My approvals**, with visibility applied before 100-row keyset pagination. State-field restrictions, current record access and account identity are rechecked; deleted records are absent and outdated proposals remain explicitly marked. [HTTP/UI contract](approvals.md), [cycle evidence](paired-cycles-2026-11-15.md). No delegation, reminders or bulk decisions are implemented.

Discovery merged into OS-PARITY-02/08: date-bounded delegation scoped by table/condition and separate approval/assignment duties, missing P1/M2. Depends on durable delegation and current access enforcement; acceptance must reject expired/revoked authority, excluded sensitive notifications and broad record access merely from delegation. Sources: [My items](https://horizon.servicenow.com/service-portal/widgets/my-items-widget), [Brazil approval rules](https://www.servicenow.com/docs/r/servicenow-platform/approvals/c_ApprovalRules.html), [Brazil Granular Delegation](https://www.servicenow.com/docs/r/employee-service-management/granular-delegation/granular-delegation.html), retrieved 2026-11-15 (rules/delegation updated 2026-09-10). Employee/fulfiller product scopes differ; no universal native contract is claimed. Next: withdrawal and prerequisites.


### Pending-review withdrawal — OS-A20-16

OS-PARITY-02/08: requester/admin withdrawal is implemented with current permissions, a required reason, atomic audit, single terminal outcome and a withdrawn-history queue filter. [Contract](approvals.md#withdrawal), [cycle evidence](paired-cycles-2026-11-15.md). Configurable prerequisites, resubmission, delegation and deadlines remain missing P1/M2.

Discovery merges condition-specific checkpoints on non-state changes (such as due-date extensions) into OS-PARITY-02/09, missing P1/M2; depends on versioned rules and approval prerequisites. Acceptance: configured changes are gated consistently and approval cannot silently cover unrelated field changes. [Brazil new features](https://www.servicenow.com/docs/r/release-notes/rn-summary-new-features.html), updated 2026-11-04, identifies this in GRC Issue Management 23.0.5; [Sales CRM recall](https://www.servicenow.com/docs/r/order-management/submitting-approval-requests.html), updated 2026-09-10, informs the bounded withdrawal capability. Both retrieved 2026-11-15. No quote/draft-reset or smart-reapproval parity is claimed. Next: prerequisites/quorum.


### Enforced minimum peer approvals — OS-A20-17

OS-PARITY-02/08/09: destination `min_approvals` gates all record persistence paths with current independent reviewers, revision/configuration binding and live authority/credential checks. Compound field edits and imports directly into protected states fail atomically. [Local contract](approvals.md#transition-prerequisites), [cycle evidence](paired-cycles-2026-11-15.md). Parent workflow parity remains partial; decisions do not automatically execute actions.

Discovery merges mandatory designated responses independent of numeric quorum and dynamic group/reference approver selection into OS-PARITY-02/09, missing P1/M2. Depends on stable approval groups/rules and membership semantics. Acceptance: a satisfied count cannot bypass a mandatory response, and membership changes have explicit tested effects. Sources: [Brazil Ask for Approval](https://www.servicenow.com/docs/r/build-workflows/workflow-studio/ask-approval-flow-designer.html), updated 2026-09-10, and [Brazil change approval definitions](https://www.servicenow.com/docs/r/it-service-management/change-management/create-approval-definitions.html), updated 2026-11-10, retrieved 2026-11-15. This local threshold format does not implement native policy or Workflow Studio contracts. Next: requirement explainability and safe resubmission.

### Iteration checkpoint 18 — approval requirement explanation

OS-PARITY-02/08/09: implemented current-executor counts in the existing record review API/page, with revision/configuration metadata and all existing access restrictions. Counts and write enforcement share the same evaluator; no transition executes from this snapshot. [Contract](approvals.md#current-requirement-snapshot), [cycle evidence](paired-cycles-2026-11-15.md).

Discovery: [Brazil approval status](https://www.servicenow.com/docs/r/order-management/tracking-approval-status.html) and [flow execution details](https://www.servicenow.com/docs/r/build-workflows/workflow-studio/flow-execution-details.html), updated 2026-09-10, retrieved 2026-11-15. Merge configurable diagnostic reporting levels and permission-filtered runtime values into OS-PARITY-02/07/10, missing P1/M2. Requires persisted execution state and retention; basic viewers must not receive sensitive runtime values, and disabled collection cannot provide historical traces. Broader approval chains/overrides remain missing. Next: recall/resubmission.

### Iteration checkpoint 19 — fresh review after withdrawal

OS-PARITY-02/09: the existing request form/API can create a fresh review after withdrawal while preserving original history and audit. Schema 20 enforces uniqueness for non-withdrawn proposals; approved/rejected decisions are not reversed. [Contract](approvals.md#resubmission-after-withdrawal), [cycle evidence](paired-cycles-2026-11-15.md).

[Brazil resubmission](https://www.servicenow.com/docs/r/order-management/submitting-approval-requests.html), updated 2026-09-10, retrieved 2026-11-15, supports broader Sales CRM reevaluation and conditional smart reapproval. Merge dependency-aware reapproval rules into OS-PARITY-02/09, missing P1/M2: requires field/step dependencies, revision evidence and versioned policies; changed dependency values or reviewer authority must prevent reuse. This implementation always requires a fresh decision after withdrawal. Next: readiness and release qualification.

### Iteration checkpoint 20 — readiness and local release qualification

OS-PARITY-10 / ADOPT-09: stateless public readiness reports database reachability and exact required migration history, with sanitized unavailable responses and no sessions. Container/Compose probes and target-image drills check it. [Contract](api.md#readiness-for-deployment-tooling), [cycle evidence](paired-cycles-2026-11-15.md), [current qualification](release-qualification-auto20.md). This is not HA, full schema validation, business-action rollback or worker/delivery health.

[Brazil instance availability](https://www.servicenow.com/docs/r/impact/io-availability.html), updated 2026-09-10, retrieved 2026-11-15, and [Spring Boot probe guidance](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html) inform this bounded implementation. Merge availability/latency alert thresholds and persisted outage/recovery timelines into OS-PARITY-10, missing P1/M2: requires metrics, retention and notification delivery; deduplicate distinct outage/recovery events and protect operational details. M1–M3 remain unmet; next work is actual deployment-specific qualification, SSO/MFA and operational ownership.


## Follow-up discovery — 15 November 2026, cycle 1

OS-F10-01 adds private browser-session inventory (ADOPT-08, OS-PARITY-08/10); see [contract and cycle evidence](paired-cycles-followup-2026-11-15.md). Existing expiry and all-browser revocation remain enforced; inventory is not distributed session storage. M1–M3 remain unmet.

OS-DISC-SESSION-LIMIT — missing, P1/M1, ADOPT-08/OS-PARITY-08. [Brazil concurrent-session policy](https://www.servicenow.com/docs/r/platform-security/authentication/explore-lcs.html), updated 2026-09-10, retrieved 2026-11-15, documents oldest-session eviction when a configured limit is exceeded (vendor plugin required). Depends on registry and admin policy; acceptance must include simultaneous logins, exact bounds and next-request denial of evicted cookies. Individual termination remains a candidate. Authentication/identity release-note URLs returned 404; identifier-first routing is unverified within the existing SSO backlog. Next research: individual termination and API authentication policy.


### Follow-up cycle 2 — OS-F10-02

Own-session termination is implemented through audited UI/API actions with account isolation and a post-lock cookie check; [contract and evidence](paired-cycles-followup-2026-11-15.md#cycle-2--end-one-browser-session-os-f10-02). ADOPT-08/09 and OS-PARITY-08 remain partial. M1–M3 remain unmet.

OS-DISC-API-AUTH-POLICY — missing P1/M1, ADOPT-08/OS-PARITY-08/03. [Brazil API access policy](https://www.servicenow.com/docs/r/platform-security/authentication/api-access-policy.html), updated 2026-09-10, retrieved 2026-11-15. Requires authentication mechanisms and scoped administrators; deny disallowed mechanisms consistently across API/export routes and preserve an administrative recovery path. OS-DISC-AI-CONTAINMENT merges into OS-PARITY-06, missing P1/M2: [Brazil new-feature notes](https://www.servicenow.com/docs/r/release-notes/rn-summary-new-features.html), updated 2026-11-04, retrieved 2026-11-15, describe delegated agent containment. Requires AI identities/tool policy/runtime lifecycle; authorize and audit containment, preventing subsequent execution. Next research: concurrent sessions and identity policy administration.


### Follow-up cycle 3 — OS-F10-03

OS-DISC-SESSION-LIMIT is partially implemented: a deployment-configurable cap with atomic oldest-entry removal, audit and current-cookie preservation; per-user/per-role policy remains missing. [Cycle evidence](paired-cycles-followup-2026-11-15.md#cycle-3--configurable-concurrent-browser-limit-os-f10-03). ADOPT-08/09, OS-PARITY-08/10 remain partial; M1–M3 remain unmet.

Discovery promotes identifier-first routing from unverified research to a documented capability, not implemented parity: OS-DISC-LOGIN-ROUTING, missing P1/M1 under the existing SSO backlog. [Brazil release summary](https://www.servicenow.com/docs/r/release-notes/rn-summary-new-features.html), updated 2026-11-04, retrieved 2026-11-15. Requires identity providers, safe local recovery and policy administration; prove deterministic routing, callback validation and no enumeration leak. Detailed linked docs were unavailable, so exact semantics remain unverified. Basic-auth restrictions merge into OS-DISC-API-AUTH-POLICY; next research is that bounded control and identity-provider lifecycle.


### Follow-up cycle 4 — OS-F10-04

OS-DISC-API-AUTH-POLICY is partial: per-account Basic allow/block, atomic revocation/audit, browser recovery and mutually consistent API-only mode. [Contract and evidence](paired-cycles-followup-2026-11-15.md#cycle-4--per-account-basic-authentication-control-os-f10-04). OAuth, scoped credentials, global/per-endpoint policies and tracking remain missing; ADOPT-08/09 and OS-PARITY-03/08 stay partial. M1–M3 remain unmet.

Discovery merges knowledge retirement/replacement into OS-PARITY-04 versioning, missing P1/M2: [Brazil retirement](https://www.servicenow.com/docs/r/servicenow-platform/knowledge-management/retire-versioned-article.html), updated 2026-09-10, retrieved 2026-11-15. Depends on publication isolation/access policy; retired items must disappear from requester search, and replacement redirects must respect target permissions. SAML certificate-expiry alerts merge into the existing SSO lifecycle backlog, missing P1/M2: [Brazil release summary](https://www.servicenow.com/docs/r/release-notes/rn-summary-new-features.html), updated 2026-11-04, retrieved 2026-11-15. Requires trusted configuration/delivery; verify expiry, replacement and recovery. Next: administrator sessions and knowledge lifecycle.


### Follow-up cycle 5 — OS-F10-05

Administrator inventory for one account is implemented as a bounded read-only UI/API capability; [contract/evidence](paired-cycles-followup-2026-11-15.md#cycle-5--administrator-browser-session-inventory-os-f10-05). ADOPT-08/09, OS-PARITY-08/10 remain partial. Administrator termination, fleet operations and distributed session continuity remain missing; M1–M3 remain unmet.

Discovery merges separate read/contribute criteria and explicit deny precedence into OS-PARITY-04/08, missing P1/M2: [Brazil knowledge access](https://www.servicenow.com/docs/r/servicenow-platform/knowledge-management/user-access-knowledge.html), updated 2026-09-10, retrieved 2026-11-15. Depends on publication/rule administration; enforce distinct rights consistently in search, article and attachments. Knowledge-derived approval invalidation merges into OS-PARITY-02/04/06, missing P1/M2: [Brazil release notes](https://www.servicenow.com/docs/r/release-notes/rn-summary-new-features.html), updated 2026-11-04, retrieved 2026-11-15. Requires versioned evidence/governed automation; changed or retired sources must prevent future authorization. Next: administrator session control and approval evidence lifecycle.


### Follow-up cycle 6 — OS-F10-06

Administrator selected-session termination is implemented with a required reason, atomic actor/target audit and next-request denial; [contract/evidence](paired-cycles-followup-2026-11-15.md#cycle-6--administrator-termination-of-one-browser-session-os-f10-06). ADOPT-08/09, OS-PARITY-08/10 remain partial. All-browser administrative termination, identity-provider logout and distributed continuity remain missing; M1–M3 remain unmet.

OS-DISC-NOTIFICATION-PREF — missing P1/M2 under OS-PARITY-02/04/08. [Brazil mandatory notifications](https://www.servicenow.com/docs/r/platform-administration/t_MakingANotificationMandatory.html), updated 2026-09-10, and [Brazil subscriptions](https://www.servicenow.com/docs/r/platform-administration/c_SubscriptionBasedNotifications.html), retrieved 2026-11-15. Requires generalized delivery, authorized subscriptions and essential-message policy; optional unsubscribe must preserve required security/service messages and record confidentiality. Dispatch-versus-recipient acknowledgment merges into OS-PARITY-02/10, missing P1/M2: [Brazil release notes](https://www.servicenow.com/docs/r/release-notes/rn-summary-new-features.html), updated 2026-11-04, retrieved 2026-11-15. Requires authenticated callbacks, stable IDs and replay defense; queue/SMTP acceptance cannot stand in for recipient acknowledgment. Next: all-browser administrative termination and delivery evidence.


Priority follow-up OS-DISC-BROWSER-BASIC-CSRF — missing P0/M1, ADOPT-08/OS-PARITY-08. Cycle 6 proved and fixed the new administrator form's Basic-authentication CSRF bypass; the shared handler still needs equivalent protection across existing forms and browser-originated API mutations. [MDN](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Authentication) and [OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html), retrieved 2026-11-15. Depends on existing session-bound CSRF, browser metadata and a safe authenticated token bootstrap. Acceptance: cached Basic cannot authorize a tokenless cross-origin form/API write, valid same-session tokens work, native API clients retain their documented contract, anonymous bootstrap fails and no forwarded-header trust is introduced. This security correction takes priority over optional next-session features.


### Follow-up cycle 7 — OS-F10-07

OS-DISC-BROWSER-BASIC-CSRF is implemented as shared HTML/browser API protection with authenticated token bootstrap and an explicit native simple-upload header contract. [Feature, evidence and limits](paired-cycles-followup-2026-11-15.md#cycle-7--browser-safe-authenticated-writes-os-f10-07). ADOPT-08 and OS-PARITY-08 remain partial; M1–M3 remain unmet.

Discovery merges configurable, narrowly scoped CORS into OS-DISC-API-AUTH-POLICY, missing P1/M2: [Brazil CORS rules](https://www.servicenow.com/docs/r/api-reference/rest-api-explorer/t_DefineACORSRule.html), retrieved 2026-11-15. Requires audited policy administration; deny unapproved origins/methods/headers and permissive credentialed wildcards. OS-DISC-DELEGATED-CONNECTOR-CREDENTIALS — missing P1/M2, OS-PARITY-03/08: [Brazil release notes](https://www.servicenow.com/docs/r/release-notes/rn-summary-new-features.html), updated 2026-11-04, retrieved 2026-11-15, describes personal source credentials for Databricks/Snowflake connectors. Generalize to caller-specific source authorization; depends on OAuth/secrets and identity mapping, with acceptance prohibiting privileged shared-credential fallback. Next: administrator all-session termination and integration credential lifecycle.


### Follow-up cycle 8 — OS-F10-08

Administrator all-session termination is implemented as an account-scoped, reasoned, atomic registry deletion with audited counts and idempotent repeats. [Contract/evidence](paired-cycles-followup-2026-11-15.md#cycle-8--administrator-termination-of-all-account-sessions-os-f10-08). ADOPT-08/09 and OS-PARITY-08/10 remain partial; SSO logout, fleet operations, distributed continuity and M1–M3 gates remain open.

OS-DISC-CONNECTION-ALIASES — missing P1/M2 under OS-PARITY-03/09: [Brazil connections/credentials/aliases](https://www.servicenow.com/docs/r/platform-security/connections-and-credentials/credentials-connections-alias.html), updated 2026-11-05, retrieved 2026-11-15. Requires secret storage, connector execution and versioned configuration; test environment-specific bindings, authorized alias use and secret-free promotion/rotation. Current per-request migration tokens are not reusable connection aliases. Next: account concurrency and connector environment binding.


### Follow-up cycle 9 — OS-F10-09

OS-DISC-SESSION-LIMIT is partial with a persisted per-account 1–100 override, zero/default inheritance, atomic audit and next-sign-in enforcement. [Contract/evidence](paired-cycles-followup-2026-11-15.md#cycle-9--account-specific-browser-session-limits-os-f10-09). Role policies, unlimited exemptions and distributed continuity remain missing. ADOPT-08/09, OS-PARITY-08/10 and M1–M3 remain incomplete.

OS-DISC-SEARCH-AUTHORIZATION — missing P1/M2 under OS-PARITY-01/07/08: [Brazil release notes](https://www.servicenow.com/docs/r/release-notes/rn-summary-new-features.html), updated 2026-11-04, retrieved 2026-11-15, document column-level search authorization. Requires an index/search engine and current field policy; hidden fields must not influence matches, counts, snippets or export after role/policy changes. Existing SQL field boundaries do not constitute indexed search. Next: account authentication investigation and security monitoring/export.


### Follow-up cycle 10 — OS-F10-10

Administrator account-specific authentication investigation, keyset pagination and page-scoped download are implemented; [contract/evidence](paired-cycles-followup-2026-11-15.md#cycle-10--administrator-account-sign-in-investigation-os-f10-10). ADOPT-09 and OS-PARITY-08/10 remain partial. Global rolling retention, limited event taxonomy and absent external forwarding remain explicit limits; M1–M3 remain unmet.

Discovery merges [official Log Export Service/SIEM guidance](https://support.servicenow.com/kb?id=kb_article_view&sysparm_article=KB2540104), updated 2026-07-01, retrieved 2026-11-15, into the existing ADOPT-09/OS-PARITY-10 forwarding backlog, missing P1/M2. Requires durable checkpoints, destination authorization/redaction, replay handling and retention/backpressure policy; acceptance must detect gaps, resume export and keep credentials out of records. Local page download does not implement forwarding. Next: scoped integration credentials, MFA/SSO and audit retention/export qualification.

## OS-N10-01 — scoped integration credentials (implemented)

ADOPT-08/09, OS-PARITY-03/08; blueprint A4(e). [Current contract, acceptance and official research](paired-cycles-next20-2026-11-15.md#cycle-1--os-n10-01-scoped-read-only-integration-keys). Original header-only, table-bound read keys; not ServiceNow token-format/OAuth conformance. Rotations, write scopes, outbound aliases, enterprise identity and production qualification remain unimplemented. Discovery merges slow-pattern trends into the existing monitoring/analytics backlog (P1/M2); details and next research are in the roadmap/report.

## OS-N10-02 — integration key rotation (implemented)

ADOPT-08/09, OS-PARITY-03/08; blueprint A4(e). [Local rotation contract and evidence](paired-cycles-next20-2026-11-15.md#cycle-2--os-n10-02-rotate-an-integration-key). No native ServiceNow rotation/API parity claim. Discovery adds OS-DISC-API-KEY-SUSPEND and merges child bindings into OS-DISC-CONNECTION-ALIASES; both remain missing P1/M2 with dependencies/acceptance in the roadmap/report.

## OS-N10-03 — individual API key suspension (implemented)

OS-DISC-API-KEY-SUSPEND adds administrator-controlled suspension/resumption with unchanged secret, scope and expiry; ADOPT-08/09 and OS-PARITY-03/08 remain partial. [Contract, research, acceptance and QA evidence](paired-cycles-next20-2026-11-15.md#cycle-3--os-n10-03-suspend-and-resume-one-integration-key). Brazil API-key documentation confirms Active state; release-note AI deny-by-default access is merged into OS-PARITY-06/08, missing P1/M2, dependent on current identity/tool authorization with revocation acceptance. Cleanup-document details remain unverified. Next research: credential cleanup and knowledge publication. M1–M3 remain unmet.

## OS-N10-04 — Revoke every API key for one account (implemented)

ADOPT-05/08/09 and OS-PARITY-03/08 gain bounded account-wide credential withdrawal. Publication/version-state access and validity dates merge into existing OS-PARITY-04/08 knowledge lifecycle, missing P1/M2; depends on publication isolation and requires search/direct-ID/attachment leak prevention. Next research: token-only integration accounts and knowledge read/contribute criteria. [Contract, discovery and evidence](paired-cycles-next20-2026-11-15.md). M1–M3 remain unmet.

## OS-N10-05 — Convert an integration account to API keys only (implemented)

OS-DISC-API-AUTH-POLICY remains partial; explicit key-only conversion provides a supported alternative to Basic for eligible integration accounts. Knowledge read/contribute deny precedence merges into OS-PARITY-04/08. OS-DISC-SCOPED-KNOWLEDGE is unverified P1/M2: scoped bases require explicit authorization even for administrators according to the knowledge access page; depends on scoped policy and direct primary-page verification, acceptance must deny unauthorized administrative access. Next: scoped writes and scoped knowledge administration. [Contract, discovery and evidence](paired-cycles-next20-2026-11-15.md). M1–M3 remain unmet.

## OS-N10-06 — Scoped API keys for record creation and updates (implemented)

OS-DISC-API-AUTH-POLICY is partial with table-specific create/update credentials and post-lock revocation checks. Native OAuth/profiles remain missing. OS-DISC-MCP-CLIENT-POLICY merges into OS-PARITY-06/08, missing P1/M2: authenticated clients/tool runtime are prerequisites; deny ungranted tools and enforce revoked policy. Scoped-knowledge-base details remain unverified because the primary link was inaccessible. Next: credential activity and inbound integration administration. [Contract, discovery and evidence](paired-cycles-next20-2026-11-15.md). M1–M3 remain unmet.

## OS-N10-07 — Per-key successful-request activity (implemented)

Per-key accepted-request activity partially advances OS-PARITY-03/07/10 and ADOPT-09. Native method/resource usage trends, configurable collection and historical retention merge into OS-PARITY-07/10, missing P1/M2, requiring safe aggregation/retention and access-controlled counts without secret leakage. Inbound console detail remains unverified. Existing catalogue governance/version isolation remains missing; next research: expired credentials and catalogue governance. [Contract, discovery and evidence](paired-cycles-next20-2026-11-15.md). M1–M3 remain unmet.

Discovery refinement OS-DISC-CATALOG-ENTITLEMENT — missing P1/M2, OS-PARITY-04/08. [Brazil release summary](https://www.servicenow.com/docs/r/release-notes/rn-summary-new-features.html), updated 4 November, retrieved 16 November 2026, documents configurable subscription-versus-item-criteria precedence. Requires catalogue offerings, eligibility rules and service entitlements. Acceptance must show that configured item denial cannot be bypassed by a parent-service subscription through search, direct item access or submission; native configurable defaults are not assumed. This extends the existing catalogue-access backlog rather than implementing another slice.

## OS-N10-08 — Remove expired credentials without disrupting active integrations (implemented)

OS-DISC-API-KEY-RETENTION is partial P1/M2: manual reasoned per-account expired-key cleanup is implemented in this slice; scheduled/configurable post-expiry retention remains missing, dependent on authorized durable jobs and retention policy. Test retained rows before deadline, no live-key removal, restart idempotence and preserved audit. The current Brazil primary cleanup page resolves the earlier inaccessible-link uncertainty. Catalogue restricted templates and draft/published isolation merge into existing OS-PARITY-04/09 missing governance work, requiring requester-invisible drafts and preserved published versions. Next: expiry administration and field access governance. [Contract, discovery and evidence](paired-cycles-next20-2026-11-15.md). M1–M3 remain unmet.

## OS-N10-09 — Shorten an existing key lifetime without extending access (implemented)

Expiry shortening partially advances ADOPT-08/09 and OS-DISC-API-AUTH-POLICY without extending a credential. OS-DISC-DERIVED-FIELD-ACCESS merges into OS-PARITY-01/07/08, missing P1/M2: a safe derived-field engine is prerequisite, with denial across forms/APIs/reports when any contributing field is unreadable. Exact Access Analyzer detail remains unverified after an inaccessible primary URL. Next: credential diagnostics and permission evaluation. [Contract, discovery and evidence](paired-cycles-next20-2026-11-15.md). M1–M3 remain unmet.

## OS-N10-10 — Explain effective credential eligibility to administrators (implemented)

Credential-status explanation partially advances OS-DISC-03 and ADOPT-08/09. Full user comparison/simulation, AI-tool evaluation and native access-analysis exports remain missing; Store applicability and exact Brazil detail require verification. OS-DISC-QUERY-ACL merges into OS-PARITY-01/07/08, missing P1/M2: shared query policy is prerequisite; reject forbidden predicates across data, counts, ordering and reports. Current hidden-field rejection is preserved. Next: administrator subject-level permission diagnosis and invitation administration. [Contract, discovery and evidence](paired-cycles-next20-2026-11-15.md). M1–M3 remain unmet.

## OS-N10-11 — Administrator diagnosis of another account's record permissions (implemented)

OS-DISC-03 now has bounded administrator subject-level record-policy diagnosis; comparison/simulation and native exports remain missing. Scoped administration is merged into ADOPT-03/06/09 and OS-PARITY-08, missing P1/M2, requiring composable permissions and denied unrelated administration after delegation/revocation. Exact Brazil Access Analyzer behavior and native invitation-specific behavior remain unverified. Next: credential downscoping and invitation directory administration. [Contract, discovery and evidence](paired-cycles-next20-2026-11-15.md). M1–M3 remain unmet.

## OS-N10-12 — Reduce existing integration keys to read-only (implemented)

OS-PARITY-03/08 and OS-DISC-API-AUTH-POLICY: bounded reduction of a retained key to read-only; arbitrary OAuth/resource scopes remain missing. New OS-DISC-WORKLOAD-FEDERATION is missing P2/M2, dependent on connector trust/identity configuration; require issuer/audience/expiry rejection, short-lived credentials and auditable mappings. Brazil release notes establish the capability; detailed provider flows remain unverified. Next: invitation directory and account access-mode filters. [Contract, discovery and evidence](paired-cycles-next20-2026-11-15.md). M1–M3 remain unmet.

## OS-N10-13 — Search and page through every retained invitation (implemented)

ADOPT-04/09 and PARITY-08: retained invitations are searchable and pageable beyond the latest-100 window. State filters and saved views remain missing. OS-DISC-SAVED-FILTERS is missing P2/M2 under PARITY-01/07/08; depends on shared query and membership-aware sharing, with denied unauthorized edits/shares and live data permissions required. Native invitation semantics remain unverified. [Contract, discovery and evidence](paired-cycles-next20-2026-11-15.md). M1–M3 remain unmet.

## OS-N10-14 — Audit invitation issuance, replacement and revocation (implemented)

ADOPT-04/09, PARITY-08/10: invitation issue/replacement/revocation have transactional sanitized journal evidence. Configurable security-table/field audit selection is merged into PARITY-08/10, missing P2/M2, dependent on stable event schema and restricted configuration; require selected change capture with secret suppression and denied unauthorized changes. SIEM export, tamper evidence and retention remain missing. [Contract, discovery and evidence](paired-cycles-next20-2026-11-15.md). M1–M3 remain unmet.
