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

In Operations, create a named calendar or edit its saved fields. Use an IANA time zone (for example Europe/London), same-day start/end, comma-separated weekdays (1 Monday to 7 Sunday), and optional ISO holiday dates separated by commas/whitespace. Validation/auditing is shared with the native calendar API. Existing names are updated; concurrent edits use the most recent save. Existing service clocks retain their snapshotted calendars. The browser does not yet create clocks, compose parent schedules or draw a graphical calendar.

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
