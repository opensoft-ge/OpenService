# First evaluation

Use a local disposable instance and synthetic records for this walkthrough. It exercises individual sign-in, service-desk work and read-only review. OpenService is an evaluation foundation; the [compatibility ledger](servicenow-compatibility.md) records what remains before production adoption.

## Evaluation boundary

This walkthrough is milestone M0 in the [adoption roadmap](roadmap.md#milestones-and-exit-gates). It is suitable for feature feedback on synthetic data. The tested emailed onboarding and requester boundaries are available when configured, but this walkthrough does not qualify a public deployment or real customer/employee data. Before offering a managed shared evaluation, complete M1 identity/invite/revocation/access/delivery gates; before an operational pilot, record the evaluator-specific requirements and satisfy M2. Manual account creation below remains a local fallback; the preferred recipient-set journey is described next.

## Start the workspace

Follow [Run locally](../README.md#run-locally), then open http://localhost:8090 and sign in as `admin` with the generated password from your private `.env`. The default listener is local to your computer. Each evaluator can run their own copy; a shared hosted instance needs separately configured access and HTTPS.

## Preferred invitation walkthrough

Configure an isolated SMTP sink or an authorized test provider following [delivery setup](delivery.md). As administrator, open Invitations, choose a new username, email and Requester role, then create the invitation. Check Delivery queue, open the message in the sink, accept it and choose a password. Sign in in a separate browser profile, submit a synthetic request and add a shared comment. A second requester must not see the first request, its attachments, internal notes, exports or report counts. Use Forgot password to recover the verified account; the old password/session must stop working. Deactivate it from Accounts and verify its next browser/API request is rejected.

For field isolation, install a configured table with an administrator-only `read_roles` field. Compare agent/reader forms, APIs, exports, queries and reports with administrator views. See the exact [field policy](configuration.md#field-read-roles).

## Manual account fallback

1. As administrator, choose **Accounts** in the navigation.
2. Create an account such as `EvalAgent`, choose **Agent**, and supply a unique password of 12–1024 characters. Usernames accept 1–80 letters or digits and are case-sensitive.
3. Create `EvalReader` with **Reader**, the form's default role. Share credentials through an agreed secure channel.
4. Confirm both names and roles appear in the list. With more than 25 accounts, use the page controls. Passwords are never displayed after submission.

Administrator can also create another administrator. Duplicate names return an inline error without changing the existing account. Select an account name to deactivate or reactivate it. Deactivation blocks browser and Basic authentication, and existing browser sessions lose access on their next request; reactivation requires a fresh login. The last active administrator cannot be deactivated. Use the account’s role form to promote or demote it; existing browser sessions must sign in again. Each user can change their own password from Account security using their current password. Administrator-assisted recovery is available through short-lived one-time links; keep this evaluation environment disposable and use synthetic data.

## Walk through service-desk work

| Person | Try | Expected result |
|---|---|---|
| Agent, in a separate browser profile | Sign in and open Incidents; create a record with a synthetic summary | A persistent incident with its own number appears |
| Agent | Move the incident from New to In progress, save and add an internal note | The new state and activity appear after reloading |
| Reader, in another profile | Sign in and open the same incident | The record and activity are visible; creation/edit controls are unavailable |
| Reader or agent | Open `/admin/accounts` directly | Access is denied; the Accounts navigation link is absent |
| Administrator | Return to Accounts | All local accounts and their roles are visible |

Staff roles can read workspace-visible records and attachments subject to field policies; readers and requesters do not receive internal notes. Requesters see only their own submitted requests, also constrained by record visibility. Administrators may restrict a record to an active account from its Record visibility form; other users then lose access to its records, files and audit snapshots. Agent can create/update records, add notes and upload files; administrator additionally manages account creation, imports data, approves changes and deletes records. Record and field policies add boundaries to these action permissions; this remains a dedicated single-organization deployment.

For a broader review, use [Migration studio](migration.md) to preview a small synthetic export before applying it, inspect the audit trail and compare the [API scope](api.md) with required integrations. Keep notes on the workflow, expected behavior, observed result and any missing capability.

## Adoption questions to capture

- Which service workflows, integrations and migration sources are essential?
- What identity provider, team/customer separation and audit controls are required?
- What availability, recovery, upgrade and performance targets would a pilot need?

SSO/MFA, inherited/dynamic ACLs, multi-organization isolation, real-provider delivery qualification and high availability remain incomplete. Versioned upgrades and the disposable PostgreSQL restore drill are evidenced in [release qualification](release-qualification.md). Existing Docker/browser tests demonstrate the documented evaluation workflows; they do not establish production readiness or complete ServiceNow compatibility.

Use **Security events** as an administrator to review successful account changes. Failed attempts are not part of this journal; it shows recorded lifecycle events in pages of 100 and does not replace external security monitoring.

For a recipient-set password, open **Invitations**, choose a new username and role, and create a link. Copy it from that response and share it with the intended colleague through your agreed secure channel. In a separate browser, the recipient opens the link, chooses their password and signs in. Links work once, expire after 24 hours, and can be revoked or replaced; issuer security changes also invalidate outstanding links. This manual flow does not send email or verify an address, and initially provides access to workspace-visible records.

For a forgotten password, an administrator opens the person’s account, verifies the recipient through the agreed support process, confirms their own password and chooses **Create recovery link**. Share the displayed link privately; it expires after 15 minutes. The recipient chooses a new password and signs in again. Existing browser sessions and the old API password stop working. **Revoke recovery link** cancels an unused link. Disabled accounts must stay disabled; recovery does not reactivate them.

Administrators can open **Authentication events** to inspect browser/Basic password successes, failures and temporary throttling. The page displays entries in pages of 100 and retains the newest 10,000. Unknown usernames are fingerprinted, and credentials are excluded. This diagnostic journal is separate from account-change Security events and does not replace external monitoring or an agreed retention policy.

Use **Older events** to investigate earlier retained journal entries and **Download this page** to save their sanitized JSON. Refresh **Latest events** for newly recorded activity; downloads contain the selected page rather than a complete archive.
