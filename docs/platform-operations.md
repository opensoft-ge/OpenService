# Platform operations and shared-evaluation checklist

The [platform expansion](platform-expansion-2026-11-16.md) defines current behavior and limits. This runbook supports a local evaluation and prepares a shared deployment; it does not qualify an unidentified public environment.

## Enable and inspect background work

Keep credentials and deployment settings in the generated, ignored `.env` file (mode `600`) or the host's secret manager. Compose loads optional settings from that file. Preserve `SECRET_KEY` with protected backups: changing it without a migration makes existing encrypted email/job payloads and connector credentials unreadable. Never put real tokens in configuration packages, source control, shell history or support tickets.

Set `WORK_ENABLED=true` to run durable business jobs and SLA processing. The poll default is one second; `WORK_POLL_MS` changes it. Restart the application to apply environment changes. Account email uses its independent `DELIVERY_ENABLED`/SMTP configuration. AI remains disabled unless explicitly configured. Keep production connector origins narrowly allowlisted.

Open **Operations** as an administrator, or inspect `GET /api/openservice/operations/qualification`. Investigate failed jobs and stale leases. The dashboard reports configuration, not proof that a proxy, mailbox or provider works.

1. For `AUTHORITY_OR_INPUT_UNAVAILABLE`, verify the submitting account and authentication revision, target visibility and payload. Cancel invalid work and submit a fresh request under current authority; retry does not bypass revoked authority.
2. For `EXECUTION_FAILED`, inspect the relevant source's health and non-secret server logs. Jobs retry up to five attempts. Restore the dependency and explicitly retry a failed job. Do not paste encrypted envelopes, tokens, private provider content or database credentials into logs or tickets.
3. Workers reclaim expired processing leases after restart. Read-only connector work may repeat a remote read; the system does not guarantee exactly-once network effects. Migration jobs perform one existing checkpoint, with promotion still governed by migration controls.
4. Cleanup is limited to completed/cancelled jobs older than 30 days and acknowledged notifications older than 30 days. Failed work needs an explicit disposition. Retention and queue capacity are bounded, not an archival strategy.

Workers currently serialize database mutations and can hold a transaction during a bounded external call. Qualify latency and contention with representative workloads before increasing poll rates or treating this as a high-throughput integration engine. Monitor application/DB saturation and external timeouts.

## Identity and account incident handling

Deactivating an account revokes its access. Native identity synchronization also reconciles memberships; a stable external identity cannot attach itself to an existing local administrator. Upstream IdP authentication and SCIM are not implemented. New synchronized accounts require administrator-assisted password setup through the existing recovery/onboarding path.

Verified email replacement needs both old/new mailbox proofs. If the old mailbox is unavailable, stop that flow and use the organization's independently verified support procedure; the application currently supplies no safe bypass. Replacing the organization password corpus is atomic, but the operator owns source quality, update cadence and documented freshness review.

Grant audit viewing independently from administrator rights. Export audit checkpoints to a separately controlled location if using them to compare later exports. The application has no protected external retention service; a checkpoint kept only alongside a mutable database is insufficient tamper evidence.

## Shared deployment acceptance

Use PostgreSQL for shared evaluation. Concurrent SQLite qualification remains open after an intermittent HTTP 500 during a 200-request/eight-client smoke; the repeat passing does not close that issue. See OS-EXP-16-PERF-01 in the roadmap.

Before inviting real users, record the exact image digest, application/DB location, approved users, data classification, operations owner, security owner, escalation contacts and support hours. Assign real names outside the public repository.

- Verify the actual HTTPS certificate, TLS endpoint, canonical public URL, reverse-proxy headers, secure-cookie setting, request limits and anonymous/API/browser access. The supplied Compose file binds loopback HTTP with insecure cookies for local use only.
- Confirm invitation, dual-proof email replacement and recovery delivery through the approved SMTP provider into real controlled inboxes; check sender authentication, delivery failure handling and who responds to exhausted deliveries.
- Restrict database and management ports, external connector/provider destinations, secret access and deployment permissions. Inspect backups for protected secret availability and retention.
- Verify active administrators and intended user roles. Demonstrate invitation → password setup → login → restricted record access → deactivation in that environment.
- Run fresh-install and restore drills using the actual approved image. Specify measured recovery-point/recovery-time objectives and retain evidence. Database restore is not automatic reversal of arbitrary external business actions.
- Establish external readiness monitoring, failure/queue alerts, DB capacity alerts and an on-call owner. Test alert delivery and incident escalation. HA/failover and complete manual accessibility assessment remain separate release gates.

For a disposable local installation, `LOAD_PASSWORD=<disposable password> bash scripts/load-smoke.sh http://127.0.0.1:PORT 200 8` measures authenticated reads. Supply the secret through a protected environment mechanism rather than recording a real password in history. This bounded smoke is not sustained capacity or a production SLO test. Installation/recovery scripts create and remove only their own disposable containers and volumes.

## Native platform extensions

These are OpenService contracts, not ServiceNow wire compatibility. Routes below use `/api/openservice/operations/`; responses are under `result`. Existing authentication, session CSRF and current permissions apply. Administrator-only unless stated otherwise. The [round report](paired-cycles-diverse20-2026-11-16.md) records full acceptance and limits.

| Method and resource | Input and behavior |
|---|---|
| GET `dependencies/{asset_id}` | At most 100 visible impacted assets, cycle-safe; `truncated` indicates another distinct visible asset. Available to authorized readers. |
| DELETE `connectors/{alias}` | `{reason}` removes the local encrypted credential and audits retirement. Later queued reads fail while the alias is absent. Remote token revocation is separate. |
| GET `knowledge/{article_id}` | Up to 100 immutable versions, including retired snapshots. `?before=N` follows `next_before`. Administrator-only; drafts are separate. |
| POST `knowledge/{article_id}` | `{operation:"restore",version:N}` publishes an older snapshot as a new version without modifying the draft. Only workspace-visible articles may be published. |
| POST `clocks/{sla_id}` | `{operation:"cancel"}` makes a running/paused clock terminal. A detected breach is preserved. Also available in the Operations form. |
| POST `reports/{report_id}` | `{name,table,query,shared}` updates the owner's definition in place. Staff can manage their own definitions; authorized users execute shared reports under their own permissions. |
| POST `jobs` | `{kind,key,payload,run_at?}` schedules one supported job. Optional ISO instant has millisecond precision and a 366-day horizon; explicit Retry makes it due now. |
| GET `jobs/{job_id}` | Job metadata plus `receipt` and `receipt_status`: not_available, available or unreadable. Only delivery/connector metadata is exposed; no message, token or provider-body content. |
| POST `approval-reminders/{approval_id}` | Empty object delivers a preference-aware in-app reminder after rechecking current reviewer authority. Deduplicates while its notice is retained. |
| POST `assist-preview` | `{table,record_id}` returns selected grounding/content, revision and alias/model without calling a provider. Includes `preview_token` and epoch-millisecond `preview_expires_at`; summary requires this token and `allow_external:true`. A token lasts ten minutes and binds the actor/auth revision, exact record grounding/revision and provider/egress configuration. Changed or expired previews return 409 before provider delivery; malformed tokens return 400. Obtain a fresh preview after any change. Tokens are reusable within their validity window; this is not redaction or a spending limit. |
| GET `preflight` | Seven actionable configuration checks. `shared_release_qualified` is always false; external deployment qualification is recorded separately. |
| POST `event-actions` | `{key,operation,reason}` accepts acknowledge, resolve or reopen. Duplicate state changes are idempotent; new occurrences reopen resolved events. |

Numeric reports use `/api/openservice/analytics/{table}` with optional exact `minimum`/`maximum`, also available in Analytics and saved definitions. See [analytics](analytics.md).
| GET `report-history/{report_id}` | Authenticated viewers inspect their own latest 100 successful executions of a currently visible saved report. Duration and definition digest only; execution still rechecks live data permissions. Deleting the report removes history. |
| GET `clocks/{record_id}?table={table}` | Any authenticated viewer with current access to that record, including its requester, sees up to 100 clock summaries and `truncated`. Values reflect persisted `last_tick`; GET does not advance a clock. The global list is administrator-only. |
| GET `audit-checkpoint?algorithm=sha256-chain-v1&count=N&last_sequence=S&hash=H` | Administrator or separately granted audit viewer verifies an independently retained checkpoint. First save the no-query response under separate retention controls, then submit all four unchanged fields. `verified=false` requires investigation; normal appends do not invalidate a retained prefix. This is not protected storage or an external log forwarder. |
| GET `approval-diagnostics/{approval_id}` | Administrator-only explanation of current review eligibility. `qualifies_for_actor` uses the current administrator as the transition actor; a review by that same actor does not qualify. Diagnostics neither approve nor execute a transition. |
| GET `reference-search/{table}?q=literal&limit=20&offset=0` | Authenticated, live-policy value/label search. Literal query up to 200 characters; limit 1–100; visible totals and nullable next offset. Hidden labels cannot be searched. Uses `short_description`; configurable display columns and a browser autocomplete widget remain open. Concurrent edits can move offset pages. |
| GET `queue-health` | Administrator-only aggregate due/scheduled/processing/failed work, expired leases, oldest overdue age and remaining 5000-job capacity. Alerts cover disabled workers with pending work, failures, expired leases, five-minute overdue work and full retention. The Operations page presents these checks and recovery actions. |

Queue cleanup is available from Operations or POST `jobs/retention` with `{"operation":"cleanup"}`. It removes only succeeded/cancelled work created more than 30 days ago; it cannot free a queue filled entirely with recent jobs. Retry and cancel still require queued/failed state and current administrator authority. Refresh the dashboard after recovery. External alert routing, representative load qualification and operational ownership remain separate requirements.

## AI product direction

Requirement added 2026-11-16: OpenService must become fully AI enabled, agentic and conversational across its core journeys. The [AI capability and acceptance guide](ai-capabilities.md) defines the required work and evidence. This direction does not change the implementation scope or historical validation results recorded above.

## Find published knowledge

Signed-in users can search `/knowledge` or GET `/api/openservice/knowledge` with `q` (up to 200 characters), `limit` (1–100, default 100) and `offset` (0–1,000,000, default 0). Search matches a literal substring in the current published title/body; `%`, `_` and `!` are literal. ASCII case-insensitive matching works on both supported databases; full Unicode linguistic matching, ranking and attachment search are not provided.

The API retains the `result` article list and adds filtered `total`, normalized `query`, `limit`, `offset`, `previous_offset` and `next_offset` (-1 on the final page). Navigation preserves the search. Results and counts exclude deleted, retired and newly restricted articles; draft edits never replace published snapshots. Pagination is evaluated on each request, not a frozen export, so concurrent publication changes can move entries between pages.

## Business calendar forms

In Operations, create a named calendar or edit its saved fields. Use an IANA time zone (for example Europe/London), same-day start/end, comma-separated weekdays (1 Monday to 7 Sunday), and optional ISO holiday dates separated by commas/whitespace. Validation/auditing is shared with the native calendar API. Existing names are updated; concurrent edits use the most recent save. Existing service clocks retain their snapshotted calendars. Record pages now provide clock creation and controls. Parent schedules and a graphical calendar remain separate work.

## Find and respond to operational events

Operations → Operational events opens `/admin/events`. Administrators can search literal event keys/summaries, filter severity (`all`, `info`, `warning`, `critical`) and state (`all`, `open`, `acknowledged`, `resolved`), then acknowledge/resolve/reopen with a required reason. Browser responses require CSRF, use existing audited state rules and return to the unfiltered list.

GET `/api/openservice/event-search` exposes the same selection with `q` (up to 200 characters, ASCII case-insensitive), `severity`, `state`, `offset` (0–1000000) and `limit` (1–100, default 100). Returns `result`, `total`, echoed filters, offsets and limit; `next_offset=-1` ends paging. Unknown/duplicate/invalid arguments fail 400. Existing `/api/openservice/operations/events` remains unchanged. Offset pages can move with concurrent ingestion. Retained events may outlive their configuration item; response actions still require that item. No automated remediation or root-cause inference is implied.

## Personal notification history

Use `/account/notifications` or GET `/api/openservice/me/notifications` with `q` (literal substring, maximum 200 characters), `state` (`all`, `unread`, `acknowledged`), `limit` (1–100, default 100) and `offset` (0–1,000,000). Results and totals are restricted to the signed-in account before paging. ASCII case-insensitive matching is supported; `%`, `_` and `!` are literal. API metadata includes `total`, `query`, `state`, `limit`, `offset`, `previous_offset` and `next_offset` (-1 at the end). Pages are live selections, not snapshots. Browser acknowledgement and preference saves preserve filters; acknowledging an unread message removes it from that selection. Delivery preferences do not erase retained messages.

## Grant audit access

Accounts → select a person → Audit access lets administrators enable or disable the separate audit-viewer grant. It permits reading/exporting retained workspace security and authentication journals, which can contain account activity. It does not change the account role or grant administration/business-record access. Saving uses the existing security-audited operation and signs out the target’s browsers; security links are revoked. Administrators retain inherent journal access even with the separate grant disabled.

## Manage saved reports in the browser

Saved reports opens `/reports`. Administrators and agents can create up to 100 owned numeric report definitions using record/field keys from the data dictionary, optional grouping/encoded filters and decimal range bounds. The library lists up to 200 owned/shared definitions. Sharing distributes the definition; every execution recalculates with the current viewer’s record and field permissions and records their own bounded execution history. Only a currently authorized staff owner can edit/delete a definition. If their report can no longer calculate after a schema or permission change, the owner receives an explicit unavailable-results message and can repair or delete it. Other viewers retain the original access denial. Deleting a report removes its execution history, without deleting business records. Concurrent edits use the last accepted save; group-specific sharing, scheduling and graphical report design remain open.


### Personal profiles

Use **Your profile** to edit optional display name and job title. Each field accepts at most 80 Unicode characters and is normalized to NFC; empty text clears it. Details are visible to the owner and account administrators, separate from verified email, username and roles. Administrators cannot edit another account through this endpoint. Changes record only changed field names in the security journal. Migration 36 initializes empty values on existing accounts; subsequent upgrades preserve them.


### Migration reconciliation review

Select a job in Migration Studio and open **Review reconciliation**. The page and JSON download compare retained source links/staging with current local destinations; counts cover all detected issues while each list is capped at 100. A local pass is not proof of source completeness or safe live cutover. Source permissions, deletions, unmapped data and configuration require separate qualification.


### Compare knowledge publications

Administrators can choose **Compare publication versions** on a published article, then select the two version numbers. The page shows immutable titles/bodies with changed-field indicators; it does not render unpublished draft text or modify either version. Retired snapshots remain available to administrators through the comparison URL while the underlying article exists. A missing version returns 404. This is a field comparison, without word-level highlighting.

### Reference selection

On an existing record, use **Choose [reference field]** to search readable summaries, select a target or clear an optional reference. Save other edits first. Selection saves that field with the displayed record revision; changed permissions, a hidden/deleted target or a stale revision cause a denial rather than overwriting newer work. This is a separate server-rendered page, not autocomplete for unsaved records.

### Retain audit evidence

From **Security events**, open **Retain or verify an audit checkpoint**. Save the JSON outside this installation in a trusted location. Later enter its algorithm/count/last sequence/hash fields to compare that retained prefix. A match covers only those retained events; a mismatch requires investigation. This hash does not authenticate the downloaded file or prevent a database administrator from changing the journal. The page requires administrator or separately granted audit-viewer authority and contains no event bodies.

### Ask about a record

Administrators can open **Ask about this record** from an existing record. Configure `AI_ENABLED`, `AI_CONNECTOR_ALIAS`, `AI_MODEL`, `AI_ALLOWED_FIELDS` and an allowed connector origin first. Write a question, preview the selected fields/provider/model, then explicitly confirm sending. Cancel before sending to avoid egress. Each request is independent; OpenService stores only safe audit metadata, not conversation text. Provider retention and inference charges remain provider-specific; `store=false` is a request parameter, not a retention guarantee.

Answers are unverified, plain-text drafts linked to the source revision; no tools or business actions run. A changed question, record, authority or provider setting requires a fresh preview. Requests are synchronous, bounded to 30 seconds/800 completion tokens/8,000 answer characters, and hold the existing request transaction. A repeat submission can make another provider call; no automatic retry or global spending/cancellation control is claimed. Multi-turn history, human handoff, real-provider evaluation and deployment latency qualification remain open.

### Preview a service deadline

In Operations, choose **Preview a service deadline**, select a saved calendar, enter an ISO-8601 start instant with a UTC offset and 1–525600 business minutes. The result explains the UTC/local deadline and counted work windows, including holidays and daylight-saving transitions. It assumes uninterrupted work and changes no clock. At most 3660 local dates are examined; a budget beyond that horizon is rejected, and only the first 100 contributing windows are displayed. Start instants support at most millisecond precision. The administrator-only JSON equivalent is `GET /api/openservice/calendar-preview?calendar=NAME&start=ISO_INSTANT&minutes=N`; URL-encode the instant.

### Review dependency impact

Open a configuration item and select **Inspect dependent configuration items**. The list traverses currently visible dependents, avoids cycles and excludes paths through hidden records. Table read permissions and field visibility are checked on every request; a hidden summary becomes an ID label. More than 100 visible dependents produces an explicit incomplete-list warning. An empty list means no visible dependents were found, not proof of no real-world impact. Dependency ingestion/discovery and a complete service topology remain separate work.

### Copy a report privately

Open an accessible saved report, enter **New report name**, then choose **Create private copy**. The new definition belongs to you and starts private. The source keeps its original ownership and sharing, and neither cached results nor execution history is copied. Each execution of either report still applies its current viewer's record/field permissions. A source that is no longer shared or whose query is no longer permitted cannot be copied. The staff-only API is `POST /api/openservice/reports/{id}/copy` with `{"name":"My analysis"}`; a successful creation returns 201 and the new report ID.

## Find retained background work

From Operations, choose **Search all retained work**. Administrators can search job IDs/deduplication keys, filter kind and state, page through retained jobs and open a safe receipt. Search excludes encrypted payloads and receipt contents. SQLite case folding is ASCII; pages reflect live data. Retry/cancel are available for queued or failed jobs and recheck current authority and state. Processing jobs cannot be cancelled through this control. Retry preserves the existing payload and execution rules; it is not an automatic business-action rollback.

## SQLite acquisition contention

If another transaction prevents opening the request transaction within the existing busy timeout, OpenService returns **503 Service Unavailable** with `Retry-After: 1`. This specific response says the request did not start; it does not imply arbitrary failed writes can safely be repeated. The browser recovery page and theme remain usable while the database is busy. No request is automatically replayed, and post-acquisition or unrelated SQL failures retain their existing behavior. Monitor contention and qualify representative capacity before shared use; a graceful failure is not high availability.

## Service catalogue intake

Administrators use `/admin/catalogue` to create, revise or deactivate up to 200 retained offerings. Offerings are initially available to every signed-in workspace user. An administrator can restrict an offering to one current group. `/catalogue` lists active offerings for which the viewer is eligible; requesters, agents and administrators can order when eligible, while readers can browse only. The form copies reviewed text into an owned `sc_request`; service teams fulfil requests using existing workflows. Ordering does not purchase or provision a resource.

Native API: `GET /api/openservice/catalogue`, `GET /api/openservice/catalogue/{id}`; administrator POST on the collection or PUT on the item takes `{name,description,active,revision}` (revision 0 for creation, current integer for update). `POST /api/openservice/catalogue/{id}/order` takes `{revision,details,key}` with optional `answers` as described below: positive reviewed integer revision, at most 2000 detail characters and a 32-character lowercase hexadecimal idempotency key. Same-user identical retries return the original request under current access; conflicting reuse returns 409. Offering edits require a fresh review for new orders; retirement rejects new orders. A successful original order can still be retried after an offering changes. Never reuse an order key for different intended work. The group criterion below restricts new discovery and orders; a cart, broader criteria and multi-step fulfilment remain separate gaps. Optional single-team routing is described below.

## Clocks on a record

Follow **View service clocks** from a saved record, or open `/record/{table}/{id}/clocks`. The page rechecks current record access. Administrators select an existing business calendar, a purpose and a 1–525600-minute budget to start a clock, then pause, resume, complete or cancel active clocks. Submitting a clock belonging to another record is rejected. Calendar changes do not rewrite existing clock snapshots. The latest 100 clocks are shown with explicit truncation; remaining time reflects the last persisted processing, not a browser timer. Enable `WORK_ENABLED` for automatic progress and breach processing; the administrator page identifies when it is disabled. Conditional SLA assignment and historical repair remain roadmap items.

## Review, test and retire a connector

Select an alias from Operations to open `/admin/connectors/{alias}`. The page exposes origin/revision metadata and a review fingerprint, never the credential or encrypted envelope. A connection test queues an authenticated GET to a chosen read-only path at that allowed origin. It uses the durable worker and exposes only a bounded receipt (alias, revision, bytes and checksum), not provider content. The form's stable queue key prevents duplicate submissions of the same test. Worker-disabled status is explicit.

Reviewed tests fail closed if configuration changes before queueing or execution, including retirement and recreation under the same alias/revision. Native `connector.read` jobs may include the optional `review` fingerprint; older jobs without it retain dynamic resolution of the current alias. Retiring the current reviewed alias requires a reason, removes its credential and is audited. Existing receipts remain; dependent integrations require reconfiguration. This is a connection-read test, not proof of provider business semantics or comprehensive integration health.

## Hold attachment downloads

Administrators select **Review attachment access** beside a file on its record. Review the current checksum and metadata, choose **Hold downloads** or **Allow downloads**, and supply a reason. The protected form binds that review to the current file and hold revision; refresh and inspect again after a conflict. Held files return HTTP 423 through both browser and Attachment API file downloads, including for administrators. Filenames and metadata remain visible under record permissions.

The native administrator API is `GET/POST /api/openservice/attachment-access/{id}`; POST takes `{held: boolean, review: fingerprint, reason: string}`. Holding does not scan, classify or delete the file. A release records a human decision, not a guarantee of safety. Existing backups and migration staging can retain bytes. Replacing an imported file preserves its hold and requires a fresh content review before release. Automatic scanner integration, quarantine retention and forensic exceptions remain separate roadmap work.

## Export a saved report

Open a runnable saved report and select **Download CSV**. The download recomputes results under your current permissions and sharing, rather than exporting a cached author's view. Equivalent GET paths are `/reports/{id}/export.csv` and `/api/openservice/reports/{id}/export.csv`. No query parameters are accepted. Columns are `group,count,value_count,sum,min,max,average`; missing numeric values are empty cells, and numbers use exact decimal strings in the UTF-8 file. Each successful export appears in your own execution history.

Formula-like group labels, including leading controls and full-width formula characters, receive a leading apostrophe and CSV quoting for spreadsheet viewing. Preserve this protection when importing. Spreadsheet applications can reinterpret modified/re-saved files; no universal spreadsheet safety or numeric display-precision guarantee is made. Download permission currently follows report/data access; independent export-role policies, scheduled exports and XLSX remain roadmap items.

## Give and review article feedback

From **Published knowledge**, choose **Read and give feedback**. The page displays the immutable current publication and your own response, if any. Choose whether it helped and optionally explain in up to 2000 characters. Feedback is visible to you and administrators; it is not a public discussion. A newer publication requires reading and responding to that version. Identical retries preserve the existing review; changing either answer or details requests a new review.

Administrators select **Review article feedback** from the article page. Read the response and its publication version, enter an internal editorial note, and mark that exact response revision reviewed. Concurrent edits or repeated reviews return a conflict. Internal notes are not returned to readers. The latest 100 responses are shown, with explicit truncation and a count of all pending responses; complete history pagination remains open. Marking reviewed does not edit/publish the article or automatically create work.

Native API: `GET /api/openservice/knowledge/{id}` returns the current public article and only the caller's response; `POST /api/openservice/knowledge/{id}/feedback` takes `{version: integer, helpful: boolean, details: string}`. Administrator GET/POST `/api/openservice/knowledge-feedback/{id}` reads the review view or takes `{feedback_id, revision: integer, note}`. Retirement or restricted article visibility prevents new public feedback, but administrators retain access to feedback on existing drafts.

### Configure table read access

A configuration package may set `record_roles.read` to a list of the existing `admin`, `agent`, `reader` and `requester` roles. It must include `admin`; omitting the property retains the previous account/record access rules. This is an additional restriction, not a grant of create/update/delete authority or a way around field/private/group/requester boundaries. Preview and install through Configuration. New requests use current policy and current account roles, including for existing sessions, reports, CSV, files and navigation counts. An account denied the default incident table can still reach its profile and published knowledge from the landing page.

Non-administrators no longer see deleted-record journal entries, because those retained entries cannot reliably be associated with a current table policy. Administrators retain the evidence. Published knowledge is deliberately distributed through its own publication grant: denying draft-table read does not retract an already published workspace article; retire it or restrict its record visibility to withdraw that publication. This slice does not provide custom/inherited roles, scripted ACLs, cross-organization isolation or an administrator-exclusion policy.

### Acknowledge the reviewed notification page

Use **Acknowledge this page** after reviewing the displayed messages. The form submits only the unread IDs that were rendered: later deliveries and other pages are unaffected. Your filters remain selected. Native clients may POST `{"acknowledge_many":["32-character-notice-id", "another-notice-id"]}` to `/api/openservice/me/notifications`, with 1–100 distinct IDs belonging to the authenticated account. Any unavailable or foreign ID rejects the entire batch; retrying a valid batch is safe. This does not delete messages, change delivery preferences or certify that the recipient read or acted on them.

## Reviewed operational-event escalation

From **Operational events**, open **Review event case**. Review the current configuration item, severity, occurrence count and summary, then explicitly create a private incident. Its initial priority is 1 for critical, 2 for warning and 3 for information events. Current event details are copied once; later events never overwrite case content. Creation leaves the event state unchanged, and resolving either side does not resolve the other. Review incident visibility before sharing it.

The review is invalidated by event changes before creation. Repeated submission returns the existing accessible incident. The mapping survives case deletion and prevents silently creating a replacement; retained orphan cleanup/relinking is not currently supported. Resolved events must be reopened before first escalation. Administrators still need current table/create permissions. Native clients use `GET /api/openservice/event-cases?key=...` then `POST /api/openservice/event-cases` with exactly `key` and the returned `review`. Browser writes require CSRF. This is a manually reviewed escalation, not automatic remediation or a full alert-management policy engine.

## Cancelling an unapplied migration

In **Migration studio**, choose **Review and cancel unfinished migrations**, open a saved job and enter a cancellation reason. Only fetching/review jobs can be stopped. The directory shows the latest 100 jobs, including terminal states; older jobs remain stored and accessible by their known URL. `GET` and `POST /api/openservice/migrations/{id}/cancel` provide the same administrator review/action; POST accepts exactly `reason` (1–2000 characters).

Cancellation is terminal for that job. Staged data, archives, phase and cursor remain for review; create a fresh migration to retry. Applied jobs require separately reviewed recovery procedures. An in-flight source page may complete before cancellation is recorded. Pending work checks cancellation before its next source request and clears its encrypted source token when it records the stop. With workers disabled, use Operations to cancel that queued work explicitly if you need its token removed immediately. Cancellation does not delete destination/source data, undo an application or switch external traffic.

## Questions about published knowledge

Enable `AI_ENABLED=true` and the separate `AI_KNOWLEDGE_ENABLED=true` only after configuring `AI_CONNECTOR_ALIAS`, `AI_MODEL` and `CONNECTOR_ALLOWED_ORIGINS`. All signed-in roles can then use **Ask about this article** on currently available published knowledge. The entire displayed published snapshot is eligible, independently of `AI_ALLOWED_FIELDS` for private record assistance. Unpublished drafts, reader feedback and internal editorial notes are excluded. Review the exact question, article, provider alias and model before consenting. A cancelled preview sends nothing. Each submission is independent, with no saved conversation and no action tools.

GET/POST `/api/openservice/knowledge/{id}/assistant` (browser equivalent `/knowledge/{id}/assistant`) accepts `operation:preview|ask`, `question`, and, for ask, `preview_token` plus boolean `allow_external:true`. Preview lasts ten minutes and binds current identity/source/provider configuration. Responses contain `result.source`, `question`, `answer` and `error`. Provider execution failure is a processed command with HTTP 200, `answer:null` and an explicit `error`; clients must inspect both. Admission/input/disabled/consent/budget failures retain their non-2xx status. Safe attempt metadata commits even for provider failures, without storing question or answer text.

Limits are 16,000 characters of serialized article, 2,000 for the question, 800 requested output tokens and 8,000 answer characters, using the existing 30-second/64-KiB provider contract. Twenty committed attempts per account per rolling hour are allowed; failed provider calls count. Audit retention and a crash/commit failure after provider egress can affect accounting. This is not an absolute monetary cap or a crash-proof reservation. There is no automatic retry; another explicit send may incur charges. `store=false` is a provider request option, not a provider retention guarantee. Calls are synchronous and hold the database write transaction; qualify capacity before shared use. Current testing uses a deterministic HTTP provider, not real-model answer/citation quality or a measured commercial advantage.

### Distinct response and resolution clocks

Clock creation accepts optional `purpose` (`general`, `response`, `resolution`; default `general`) alongside `table`, `record_id`, `calendar` and integer `minutes`. Purpose is persisted and shown in record/API summaries. One running or paused clock per record is allowed for each named purpose; duplicate starts return 409. General clocks retain the previous multiple-clock behavior. Completed, cancelled or breached named clocks remain in history and permit a new clock for that purpose.

Complete the response clock when your team has responded, and the resolution clock when the work is resolved. Their remaining budgets and controls are independent. These labels do not detect a response, inspect comments or automatically change record state. Schema 41 preserves all existing clocks as general clocks and enforces active named uniqueness. Stop application processes and back up before upgrading; mixed-version writers are not qualified. Evidence includes concurrent direct starts on both databases, rollback/restart and an accessible browser journey completing response while resolution remains running.

### Catalogue group eligibility

On an existing offering, use **Eligible group** and **Save eligibility** to require one group or allow all signed-in users. The criterion uses current membership on every catalogue listing, direct view and new order; membership removal and group deletion fail closed immediately. Administrators may inspect every offering, but an ineligible administrator is shown an explanation instead of an order form. Readers remain unable to order. Eligibility does not grant access to existing requests or bypass record/field permissions.

Administrator-only `PUT /api/openservice/catalogue/{id}/eligibility` takes exactly `{group_id,revision}`: an existing lowercase 32-hex group ID, or empty text for unrestricted eligibility, and the current positive integer offering revision. Successful saves increment the revision and record an audit event; stale saves conflict with 409. Ordinary content edits preserve the criterion. Browser changes require CSRF and the same revision. Deleted groups remain a blocking criterion until an administrator explicitly chooses a current group or clears it. Schema 43 upgrades retained offerings as unrestricted; criteria and revision guards survive restart.

An identical retained order-key retry returns the original request only under current request access and role checks, even if offering eligibility was later revoked; it creates no new work. New orders require the current offering revision and eligibility. The native criterion does not implement arbitrary user-criteria scripts, allow/deny precedence, company/location conditions, category inheritance or subscription bypass.

### Routed fulfilment

On an existing offering, choose **Fulfilment group** and **Save routing**. An empty group keeps manual fulfilment. Each future order atomically creates one team task; content/routing edits do not move existing tasks. The team queue shows work only for current group members with current request access; administrators can recover unfinished tasks with **Handoff fulfilment**. Routing grants no record permissions.

Staff claim a task, complete it with a shared outcome, and follow the request's normal state/field/approval policies. A rejected policy or stale task/request revision rolls back the entire operation. Claim moves the request to `fulfilling`; completion atomically moves both task and request to `complete`. The native request lifecycle must support those states/transitions; configured approval requirements still need qualifying reviews. Ordinary record writes cannot complete a managed request before its task, or reopen a completed task. A requester follows **Follow fulfilment** from their request and sees progress/outcome without internal team/account identifiers. State and description field-read permission are required across the task API, queue, controls and link.

Administrator `PUT /api/openservice/catalogue/{id}/routing` takes `{group_id,revision}`. Staff `GET /api/openservice/fulfilment` accepts state (`open`, `queued`, `in_progress`, `complete`, `all`), limit (1–100, default 50) and offset (0–1000000). Counts/paging follow current access. `GET /api/openservice/requests/{id}/fulfilment` returns current task and request revisions; POST requires `{operation,revision,request_revision}` plus `outcome` for complete (nonblank, at most 2000 characters), or `group_id` for administrator reassign. Browser mutations require CSRF. Stale reviews return 409; an unauthorized caller cannot claim or complete work.

Schema 44 retains routing/tasks across restart and transactional upgrades. Deleting a routed group blocks agent work until an administrator chooses a live replacement; new orders fail if their configured group disappeared. Deleting a request removes its task. This is one native group task per order; multi-item carts, parallel/branching plans, purchasing and cancellation/reopen orchestration remain separate capabilities.

### Configurable catalogue questions

Use **Manage questions** on an offering to add, edit or remove up to 20 questions. Each has a stable lowercase key (letter first, then letters/digits/underscores, at most 40 characters), a label of at most 160 characters, an answer type and a required/optional setting. Supported types are text, choice, yes/no, date and signed whole number. Choice questions require 1–20 distinct nonblank choices of at most 120 characters; enter one per line. Other types have no choices. Saves require the current offering revision and browser CSRF; stale reviews conflict. Retained offerings upgrade with no questions in schema 45.

Administrator `PUT /api/openservice/catalogue/{id}/questions` accepts exactly `{revision,questions}`. Each question has exactly `{key,label,type,required,choices}`; types are `string`, `choice`, `boolean`, `date`, `integer`. Required is boolean, choices is a list. Replace the whole reviewed list atomically. The order API accepts an optional `answers` object keyed by question key; all values are strings of at most 2000 characters without NUL. Boolean values are `true`/`false`, dates use ISO local dates and integers fit a signed 64-bit value. Missing/blank required answers, unknown keys and invalid typed values fail before creating work. The browser renders the corresponding controls.

Successful orders retain the reviewed keys, labels, types and values, including blank optional answers, in the request transaction with any fulfilment task. Later edits affect future orders only. Identical order retries use the original canonical answer payload and return the accessible original request even after questions change; a changed answer under the same key conflicts. Empty/omitted answers preserve legacy retry compatibility. Answers are also appended to the initial request description for ordinary workflows. The immutable submitted-answer panel and `GET /api/openservice/requests/{id}/answers` require current request access and description field-read permission; the API returns an empty list for non-catalogue requests. Do not use these questions to collect secrets: this version has no independent answer ACLs, conditional questions, reference/file/multi-row inputs, pricing or executable client scripts.

### Background-job attempt history

Open a job receipt and select **View attempt history**. Each actual worker claim records its sequence, retry number, UTC start/finish, duration and outcome. Processing attempts have a pending duration. `retry_scheduled` records a transient failure before another attempt; `lease_expired` records an unfinished worker whose lease was reclaimed; `failed` records exhaustion, and `cancelled` records permanent authority/input failure. Successful attempts retain `succeeded` even if a later operator action affects other jobs. Error codes contain no provider response or credentials.

Administrator `GET /api/openservice/operations/jobs/{id}/attempts` accepts `limit` 1–100 (default 50) and `offset` 0–100, newest first, with total/previous/next offsets. The browser uses `/admin/jobs/{id}/attempts`. Current administrator authority is required, including when the job belongs to another administrator. Unknown jobs return 404. The history does not return internal leases, payloads or receipt bodies.

Schema 46 begins history at installation; past attempts cannot be reconstructed. The latest 100 attempts per retained job survive restart. Manual retry restarts the retry counter while preserving the monotonic history sequence. Job cleanup removes history when eligible terminal jobs are removed. Claim and history, and completion and history, share their respective transactions and job locks; a stale worker cannot replace a newer lease's outcome. Durations describe observed worker timing, not a service-level guarantee. This is native background-job metadata, not general flow/action tracing, input/output logging, replay or AI cause analysis.

### Check proposed creation

From an account's permission diagnosis, select **Check proposed creation**. Choose staff record creation or an owned service request, enter the table name and an exact JSON object of proposed string values. Omitting a field differs from supplying empty text. Owned requests use `sc_request` and accept only `short_description` and `description`. The result explains the first current validation/policy rejection or confirms that the creation checks passed. No record, record number, business event, job or provider call is created.

Administrator POST `/api/openservice/users/{id}/creation-preview` takes exactly `{kind,table,values}`, where kind is `record` or `request`; proposed serialized values are bounded to 65536 characters. GET/POST `/admin/accounts/{id}/creation-preview` is the browser journey with CSRF. Malformed envelopes return 400; a valid proposal returns 200 with `{result:{account_id,table,kind,allowed,status,message}}`, where status describes the native creation outcome (201 when allowed, or its policy/validation failure). An unknown target returns 404. The shared creation preparation evaluates current account activity/password replacement, journey role, table read/create policy, field visibility/write rules, types and references, initial-state/required fields and creation approval prerequisites.

This is a current native policy/validation preview, not authentication, an impersonated session or a permission grant. Channel restrictions, integration-key scopes, catalogue eligibility/questions and future policy changes are outside its contract. The actual submission always checks current authority again. The editor and result escape user text; the result includes no existing record values or credentials. SQL/runtime failure is an error, never a passing preview.

### Fetch an entire migration in the background

Choose a saved migration in Migration Studio, open **Review reconciliation**, then **Fetch remaining data in the background**. The same link is available from cancellation controls. Supply the authorized source OAuth token and queue the remaining pages. The worker can continue after the browser closes; if disabled, the page says the work will wait. Follow the job receipt and attempt history in Operations.

Administrator POST `/api/openservice/migrations/{id}/fetch-jobs` takes `{key,token}` and returns the queued job with 201. The existing generic job API also accepts kind `migration.fetch` with `{migration_id,token}` payload. First enqueue requires a fetching migration; an identical retained key returns the original job, while different work under that key conflicts. A failed job can be retried through existing job controls. To replace credentials, cancel the old queued/failed work and queue with a fresh key/token; no token is displayed again.

Each worker claim handles one source page and atomically retains its checkpoint. A successful page with more work records `continued`, resets the per-page retry counter and becomes eligible one second later. Failed pages retain their checkpoint and use the existing bounded retries/backoff. The latest 100 attempt entries remain available; older entries may be pruned. The job stops successfully at review, retaining a receipt with migration ID/status/phase/staged-row count. It never promotes destination data or records cutover. A duplicate fetch job that finds the migration already reviewed/applied/cut over finishes without another source call.

Pending/failed credentials remain encrypted so work can resume. Success or cancellation clears the encrypted token. Cancelling a migration is observed before the worker's next page and clears its token then; if the worker is disabled, cancel the background job in Operations to clear the token immediately. A currently running page may finish before cancellation. Existing source allowlists, current owner authority, migration row/byte limits and manual promotion/reconciliation gates remain in force. These tests use a synthetic source and do not establish licensed live-instance conformance or production capacity.

### Assisted catalogue requests

From an eligible offering, choose **Describe your request with assistance**. Describe the need, inspect exactly what will be sent and explicitly consent to generation. AI_ENABLED and AI_CATALOGUE_ENABLED must both be true with the existing configured model/connector/allowed origin. The provider receives the offering ID/revision/name/description and your text, not your question answers. The output is a suggestion bounded to 2000 characters. Edit it, answer the offering questions, review the complete request and explicitly confirm submission. The resulting owned request uses ordinary policy, question validation, idempotence and fulfilment routing. No request exists before confirmation; generated text cannot submit anything.

GET/POST `/api/openservice/catalogue/{id}/assistant` supports exact operation envelopes: preview `{operation,description}`; generate `{operation,description,preview_token,allow_external:true}`; review/edit `{operation,order}`; submit `{operation,order,action_token,confirm:true}`. An order has `{revision,details,key,answers}` with the native catalogue types and limits. Preview/generate/review return 200; confirmed native submission returns 201. Generation failure returns a bounded error in the result so its attempt audit commits. Twenty retained attempts per account per rolling hour include provider failures. Consent and action tokens expire after ten minutes and bind distinct exact contexts. Confirmation rechecks current eligibility, schema and identity; later edits require another review.

Drafts are not saved conversations. Leaving the page abandons an unsubmitted draft; submitted requests remain in My requests. Use the ordinary form when assistance is unavailable. Manually supplied details can also use review/submission without calling a provider. No general autonomous-agent, multi-turn or real-provider quality qualification is claimed.

The review page offers **Edit reviewed request**: existing details, answers and the idempotency key return to the editor without another provider call or business write. Changes must be reviewed again before submission.
