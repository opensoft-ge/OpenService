# Account email and durable delivery

The application includes a transactional outbox and a scheduled SMTP worker. The first consumers are emailed invitations and password recovery. Generic business notifications, migration execution and connector synchronization have **not** been moved to this worker. Connected migration remains explicitly advanced through its workbench/API.

## Configure delivery

Delivery is disabled by default. Put configuration in the private `.env` created by the setup command (Compose passes it to the application), or supply environment variables to the Java process. Never commit credentials.

| Setting | Meaning |
|---|---|
| `DELIVERY_ENABLED=true` | Enable the worker and emailed onboarding |
| `PUBLIC_BASE_URL=https://service.example.org` | Canonical public origin used in links; no path, query, fragment or credentials |
| `MAIL_HOST` / `MAIL_PORT` | SMTP submission host and port; port defaults to 587 |
| `MAIL_FROM` | Sender address authorized by the provider |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | SMTP credentials, when required |
| `MAIL_STARTTLS` | Defaults to true; TLS is required and hostname verification enabled |
| `DELIVERY_POLL_MS` | Poll interval, default 1000 ms |

Restart after configuration changes. Use an HTTPS deployment with secure cookies before inviting real users; the loopback Compose walkthrough is synthetic-data only. Provision sender-domain verification, SPF/DKIM/DMARC and provider quotas separately. Local SMTP acceptance tests do not establish public deliverability, bounce handling or provider uptime.

`MAIL_ALLOW_INSECURE_LOCAL=true` permits plaintext SMTP only to a loopback host, and HTTP links only to a loopback origin. It is for an isolated local mail sink, not a production TLS bypass. Tests use an in-process sink that cannot forward email. No real invitation recipient is needed for the test suite.

## Invitation and recovery journey

As administrator, open **Invitations**, choose a new username and role, enter the recipient email and submit. The response contains a delivery ID, not a bearer link. **Delivery queue** shows the latest 100 jobs and sanitized failure codes. The recipient follows the emailed link, chooses a password and signs in. Acceptance verifies possession of the invited address, normalizes it to lowercase and enforces account-email uniqueness. Links are single-use and expire after 24 hours. Revocation, replacement and issuer security changes invalidate outstanding links.

An empty recipient email retains the manual invitation flow for a disposable walkthrough; those accounts have no verified address. Existing accounts are not silently treated as verified. Email changes/reverification and converting existing manual accounts are future work; assisted recovery remains available.

**Forgot password?** accepts a verified account email. Its response is identical for unknown, inactive, unverified, disabled-delivery and throttled accounts. Recovery links expire after 15 minutes, work once, require a different password and revoke existing sessions. Requests are bounded to one per address per minute, ten per observed remote address per hour and 1000 globally per hour. Configure proxy boundaries before a public deployment: the limiter uses the servlet's remote address, not an untrusted forwarded header. Address-based denial of recovery remains an operational abuse concern; assisted recovery is available. Responses are generic, not a claim of constant-time behavior.

## Queue semantics and recovery

Queue insertion and account-link creation commit together. Envelopes are AES-GCM encrypted using a key derived from `SECRET_KEY`; the queue UI/API never returns envelopes, addresses, links, transport messages or credentials. Back up this secret with the database. Changing or losing it prevents pending envelopes from being decrypted; drain/cancel and reissue links as part of a planned key rotation.

Workers claim jobs transactionally, release the database before network I/O and hold a 60-second lease. PostgreSQL uses row locks with `SKIP LOCKED`; SQLite serializes writes. Expired leases are reclaimable after a process restart. A stale worker cannot overwrite a newer lease's state. SMTP failures retry with exponential delay; five failed attempts produce a visible `failed` job; five abandoned processing leases also produce `failed` with `LEASE_EXHAUSTED`. Administrators can retry an unexpired failed job. A maximum of 1000 unfinished jobs and 20 invitation jobs per minute bound account-email submissions. Expired or invalid links are cancelled before sending. Terminal delivered/cancelled jobs have their encrypted payload erased.

Delivery is **at least once**. A crash after SMTP acceptance but before acknowledgement can send a duplicate. A stable Message-ID helps correlation but cannot promise provider deduplication. `delivered` means the SMTP server accepted the message, not that it arrived in an inbox. Metadata is retained in the database; the latest-100 view is not a retention policy. Scheduled retention, provider/bounce telemetry, alerting and dead-letter bulk operations remain roadmap work.

| Interface | Access and behavior |
|---|---|
| `POST /api/openservice/email-invitations` | Administrator; exactly `name`, `role`, `email`; 201 with invitation metadata and `delivery_id` |
| `GET /admin/delivery`, `GET /api/openservice/delivery` | Administrator; latest 100 sanitized jobs plus complete queue diagnostics |
| `POST /admin/delivery/{id}/retry`, API equivalent | Administrator; only unexpired failed jobs; browser CSRF required |
| `GET/POST /forgot` | Public recovery form; POST requires CSRF; does not expose account existence |

These are original OpenService contracts, not ServiceNow Email API compatibility.

## Complete queue diagnostics

`GET /api/openservice/delivery` retains its `result` array and adds `diagnostics`: `total`, `queued`, `processing`, `failed`, `delivered`, `cancelled`, `ready`, `expired_leases`, `expired_links`, `oldest_pending_age_seconds` and `observed_at_epoch_ms`. Counts cover all retained rows, independent of the page limit. Empty counts and age are zero. Ready means queued, due and unexpired; expired leases count unexpired processing jobs whose lease deadline has arrived. Expired links count unfinished rows awaiting worker cleanup. Pending age excludes failed, terminal and expired jobs and clamps future timestamps to zero.

The administrator screen gives readable labels for these values in both themes. It exposes no message content, recipient, reference ID, bearer link or SMTP error text. Observations are point-in-time diagnostics, not inbox delivery guarantees or a substitute for a monitoring/alerting service. Expired leases may be retried or exhausted according to the bounded worker policy.
