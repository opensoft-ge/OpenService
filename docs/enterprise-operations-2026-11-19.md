# Enterprise operations workflows

The seven workflows below are available from the Enterprise workspaces page. The same commands are available by posting to `/api/openservice/enterprise/{area}/{subject}`. Workspace reads show current document revisions. Use those values for commands that require a revision.

| Area | Operator workflow | Evidence to inspect |
| --- | --- | --- |
| Integrations | In Integrations, schedule a `github` or `gitlab` connection with one issue API path, one OpenService incident ID and an interval in minutes. The connector synchronizes the issue title and body with the incident summary and description. | Schedule status, last success, the competing values and their fingerprint, plus incident revision history. |
| Field service | Accept an assigned visit and download its worksheet from the task’s field workspace. Edit it offline in a phone spreadsheet. Enter worked minutes, an evidence note, and one part row per item used. Keep any photo on the phone and upload it with the saved CSV after reconnecting. | The receipt records time, parts and the attachment ID. Re-uploading an identical worksheet and photo returns the same receipt. A changed assignment creates a dispatcher review flag. Keep the original files for review. |
| HR | On an open HR case, choose a transfer or leave, enter the effective date and assign each step to a department group. For leave, add return work and a later return date. Department staff claim and complete the resulting tasks. | HR sees responsible groups and task outcomes. Pending work can move to a new date. Cancellation requires a reversal-review note for each completed action. |
| Assets | Register a product alias and schedule an `inventory` import for its canonical product name. In Purchasing, enter an invoice against an approved single- or multi-model order. | Installation and entitlement exceptions identify affected seats. Each invoice line shows quantities ordered, received and billed so far, plus the price variance for that line. |
| Security | Schedule a `threat` import, link the shared feed to incidents, and record observed SHA-256 digests. Analysts can request matches across the cases they can open. | Results identify the incident, source, feed revision, expiry and withdrawal. Failed refreshes retain the last good feed. Each feed retains up to 100 current indicators and 100 recent withdrawals. |
| Portfolio | Set weekday availability, dated exceptions, hourly rate and currency for each reserved person. Select the two sets of projects, planning period and total funding. Save the comparison under a new scenario ID. | Each option shows forecast labor, funding remaining after labor and the person and date of each capacity shortfall. Reopening uses the original inputs and rechecks project access. |
| AI agents | Grant an API-only agent the `state` field of a specific managed request. Review a manual or model-drafted claim, inspect the expected assignee, evaluate the reviewed plan, then approve execution separately. | Native fulfillment and record revision checks run again before the claim. Evaluations report task completion, rejection after delegation revocation, stop behavior and elapsed time. Each trial rolls back its record changes and receipts. |

## Connector setup and recovery

Enable `WORK_ENABLED=true` for the scheduler. Outbound issue updates also require `CONNECTOR_WRITES_ENABLED=true`. Allowlist the exact connector origins in `CONNECTOR_ALLOWED_ORIGINS`. GitHub uses `/repos/{owner}/{repo}/issues/{number}`. GitLab uses `/api/v4/projects/{numeric_project_id}/issues/{iid}`. Scheduling authorizes these two issue fields in both directions. On first run, the issue supplies the initial values if the incident has stayed as it was at configuration.

Each subsequent run compares the two field values with the last synchronized version. A change on one side can flow to the other. If both sides changed differently, inspect the conflict, choose `local` or `remote` and submit its fingerprint and current schedule revision. The worker checks both sides again. It persists outbound intent before attempting a write. After a lost response it rereads the issue to confirm convergence or request a decision. Replay makes a schedule due again. A worker interrupted during a fetch leaves a claim that expires after two minutes. Changes made at the vendor between polls can coalesce into a single observed update. The worker sends a strong ETag as an HTTP precondition when the read provides one. On endpoints without enforced conditional writes, a concurrent edit between the last read and the write can still race.

For automatic OAuth renewal, configure a schedule-owned refresh credential with the approved token issuer alias, its token endpoint, client ID, client secret and refresh token. The token issuer can have a different origin from the issue API. The worker stores rotated credentials encrypted. Changes to a connector binding require a fresh review. Reauthorize and replace the renewal profile if the issuer revokes the refresh token or rotates it during an interrupted exchange. The workspace displays its revision separately from the schedule revision. [GitHub issue updates](https://docs.github.com/en/rest/issues/issues#update-an-issue) · [GitLab issue updates](https://docs.gitlab.com/api/issues/#update-an-issue) · [GitHub token renewal](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens) · [GitLab OAuth](https://docs.gitlab.com/api/oauth2/)

## Data and evaluation conventions

- An inventory snapshot is a JSON object with `product` and `installations`. Each installation identifies an asset and user. The threat endpoint supplies a JSON array with `sha256`, `label` and `expires_on` per indicator. The scheduler checks the destination, connector and authorizing account again before accepting a snapshot.
- Invoice price variance is the billed unit price minus the ordered unit price, multiplied by the current invoice quantity. A negative received-minus-billed quantity identifies units invoiced ahead of receipt. Entering an invoice records the comparison. Payment approval is a separate purchasing responsibility.
- Portfolio reservations use a standard eight-hour Monday-to-Friday workday. Each person’s availability and date overrides determine whether the requested minutes fit. Enter remaining project costs exclusive of the labor being modeled to avoid counting it twice. A scenario covers up to 366 days.
- Enable `AI_AGENT_EXECUTION_ENABLED=true` for evaluation and execution. A model-generated plan records generation latency. With provider token counts and both `AI_PLAN_INPUT_USD_PER_MILLION` and `AI_PLAN_OUTPUT_USD_PER_MILLION` set, OpenService reports estimated generation cost in USD. Missing usage or pricing leaves the estimate empty.

## QA review

The review covered the accumulated enterprise worktree against `519db49c81e40b44784db692b064c516b1cc68da`. Both SQLite and PostgreSQL exercised the operator APIs.

| Round | Findings and repairs |
| --- | --- |
| Workflow execution | Normalized PostgreSQL authorization revision comparisons and repaired evaluation of reviewed plans loaded by key. The agent trials now finish with the native request and real plan unchanged. |
| Recovery and authorization | Tested an update applied by a vendor followed by a lost response, credential rotation, owner and connector changes, conflicting edits during outbound preparation, and resolution against changed inputs. An uncertain outbound attempt requires reconciliation before another write. Threat feeds retain a bounded withdrawal history and filter matches by current incident access. |
| Operator journeys | Verified partial receipts and price variance, completed a transfer and both phases of leave, preserved staffing assumptions on reopen, and exercised a mobile CSV with a byte-order mark and padded columns. The task list and claim operation now use the same effective-date rule. |

The final review found no new or reopened defects. An oversized issue conflict now records a retryable failure and retains the last successful checkpoint. A restart test with two overdue schedules verifies that the worker claims the earliest checkpoint.

Connector tests used local protocol fixtures. The mobile test used Chromium with a touchscreen phone viewport. AI tests exercised the configured provider interface with a fixture and the real database-backed execution path.


## Verification results

A clean run of `sh scripts/verify.sh` passed against a source-identical copy of the worktree on November 19, 2026. Both PostgreSQL and SQLite were enabled.

| Check | Result |
| --- | --- |
| Automated tests | 1,763 passed in 45 suites, zero failures, errors or skips |
| Database workflows | 535 on SQLite and 535 on PostgreSQL |
| Browser and migration suites | 159 browser tests and 152 migration tests |
| Java coverage | 11,273 of 11,273 lines, 11,913 of 11,913 branches, 1,219 of 1,219 methods |
| Browser JavaScript coverage | All 138 measured lines, 75 branches and 22 functions across the three production scripts |
| Documentation and whitespace | 602 local Markdown links resolved, and the worktree passed `git diff --check` |
