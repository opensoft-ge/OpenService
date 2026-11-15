# Custom password exclusions

Administrators can open **Accounts → Password exclusions** to prohibit whole password values, such as organization-specific examples. The same policy applies to new accounts, invitation acceptance, password changes, recovery and creation of a missing bootstrap administrator. Rejection leaves invitations/recovery links usable and does not change existing credentials. Adding a policy never locks out an existing account or requires its password to be changed at login.

Each entry has an administrative label (1–80 Unicode code points, trimmed, no control characters) and a case-sensitive value (1–1024 code points). Spaces and case are significant; substring matching and Unicode normalization are not performed. Only a SHA-256 fingerprint of the value is persisted, separately from the salted password hashes used for authentication. No candidate value or fingerprint is returned in the API, rendered in the page or placed in security events. Labels are administrative metadata; do not put credentials into them. The form clears password inputs after submission.

At most 1,000 entries can be configured. Lists contain 100 entries per page, ordered by creation time descending and ID. Concurrent duplicate additions serialize; duplicates and a full list return 409. Add/remove audit events contain only the exclusion ID and actor identity. Removing a rule allows that value on future password changes, subject to the remaining length/difference rules. Browser operations require administrator authority and CSRF; API operations use the same authorization and transactional policy.

| API | Contract |
|---|---|
| `GET /api/openservice/password-exclusions?page=1` | `result` rows of `exclusion_id`, `label`, `created_at`; plus `page`, `pages`, `total`, `capacity`. Positive page of at most six digits; outside-list pages return 400. |
| `POST /api/openservice/password-exclusions` | Exactly `label` and `password`; 201 with the public entry in `result`. No query arguments. |
| `DELETE /api/openservice/password-exclusions/{id}` | Empty JSON object, no query arguments; 200 with `result.removed=true`, missing ID 404. |

The browser workspace is `/admin/password-exclusions`. Unknown/repeated query arguments are rejected. Policy administration does not grant account or record permissions. Responses inherit the application's no-store policy.

Schema 16 adds the policy table transactionally; schema 15 upgrades preserve accounts and credentials. Keep the policy table with the database backup. Acceptance covers SQLite/PostgreSQL upgrades, restart, rollback, duplicate writers, all password-setting routes, token preservation, administrator authorization, browser keyboard navigation and both themes. The restore drill compares policy rows and verifies enforcement after restore.

This feature is a **custom exclusion list**, not a maintained common/compromised-password corpus. New credentials require 15–1024 Unicode code points. Absent NFC normalization and an absent maintained screening corpus remain recorded gaps against current NIST guidance; no NIST conformance or complete ServiceNow password-policy parity is claimed. Configure transport security before real deployment and enter only synthetic/known-unsafe examples into the policy interface, never actual users' secrets.
