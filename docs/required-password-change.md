# Required password replacement

An administrator can select **Require password change** on an account detail page. This keeps the account's activation state and role, revokes existing browser sessions and revision-bound security links, and requires replacement before workspace access resumes. Inactive accounts remain inactive; reactivation does not clear the requirement. Applying an already-pending requirement is idempotent and does not revoke newly issued recovery links again.

The user signs in with their existing credential to a minimal `/password-required` page, enters the current password and a different 15–1024-character password, then signs in again. The page provides both themes, sign-out and the existing recovery entry point. It contains no workspace navigation, record counts or business data. Errors retain no passwords. Existing current-password throttling, custom exclusions, CSRF and caller-revision checks apply. A successful change or valid recovery clears the requirement atomically with the new credential and session revocation; a failed/rolled-back change leaves it pending.

Normal authenticated APIs, downloads, reports and workspace routes are denied while a replacement is pending, including requests using Basic authentication. API calls receive 403. Browser routes redirect to the replacement page. Only `/password-required`, `POST /api/openservice/me/password`, logout and existing public/static routes remain accessible. The API change endpoint still requires exactly `current_password` and `new_password`. Public recovery cannot activate a withdrawn account or bypass its usual token/identity checks.

| Administrator API | Result |
|---|---|
| `GET /api/openservice/users/{id}/require-password-change` | `200 {"result":{"required":false}}` or `true` |
| `POST /api/openservice/users/{id}/require-password-change` | Empty JSON object; `200 {"result":{"required":true}}` |

Both routes reject query arguments; non-admins receive 403, unknown targets 404, unsupported methods 405. The corresponding browser POST is `/admin/accounts/{id}/require-password-change` with CSRF. There is no administrator clear/bypass operation. A new requirement creates `account.password_change_required` with actor/target IDs and an empty detail object; repeats do not duplicate audit events. Existing password-change/recovery events record satisfaction.

Schema 17 adds `os_user.password_reset_required` with default false. Upgrades preserve credentials and never force existing users to reset implicitly. Backup/restore must preserve this column. Quiesce and replace older application nodes together during upgrade: an already-running older binary cannot enforce a flag it does not understand. The restore drill uses a stopped source, not mixed-version rolling deployment. Tests cover both databases, old-schema upgrade, rollback, concurrent idempotent administrators, restart, inactive-account behavior, invalidated old links, denied API surfaces, throttling, recovery and desktop/mobile light/dark journeys. The container drill verifies the flag and API denial after restore.

Administrators can also set the requirement atomically during provisioning: `POST /api/openservice/users` accepts optional boolean `require_password_change` (default false for existing clients). The browser form defaults to Required and permits an explicit Not required selection. The choice survives validation errors; duplicate creation never changes the existing account. Recipient-selected invitation passwords and bootstrap accounts are not implicitly flagged. Organization-wide mandatory provisioning policy, notification delivery, scheduled expiry and privileged service-account credential rotation remain separate gaps. Current support does not establish complete ServiceNow password-policy parity or identity proofing.

## AI product direction

Requirement added 2026-11-16: OpenService must become fully AI enabled, agentic and conversational across its core journeys. The [AI capability and acceptance guide](ai-capabilities.md) defines the required work and evidence. This direction does not change the implementation scope or historical validation results recorded above.
