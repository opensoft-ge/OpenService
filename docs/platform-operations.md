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
| POST `assist-preview` | `{table,record_id}` returns selected grounding/content, revision and alias/model without calling a provider. This is a snapshot, not redaction or version-bound execution consent. |
| GET `preflight` | Seven actionable configuration checks. `shared_release_qualified` is always false; external deployment qualification is recorded separately. |
| POST `event-actions` | `{key,operation,reason}` accepts acknowledge, resolve or reopen. Duplicate state changes are idempotent; new occurrences reopen resolved events. |

Numeric reports use `/api/openservice/analytics/{table}` with optional exact `minimum`/`maximum`, also available in Analytics and saved definitions. See [analytics](analytics.md).
