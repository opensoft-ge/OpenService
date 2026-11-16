# Local qualification after the ten follow-up cycles

Measured 15 November 2026 against the final follow-up source. This is a local evaluation qualification, not a production deployment, vendor conformance or enterprise-readiness certification. The separate [cycle report](paired-cycles-followup-2026-11-15.md) records acceptance, exact coverage and review findings for each commit. Earlier [twenty-cycle image evidence](release-qualification-auto20.md) remains historical.

## Image and artifact identity

Local image `openservice:followup10-20261115`, immutable ID `sha256:be51efcc8fa79b95b32f5d1e32ef8ebe0d800d1764f45d06a31907915cf3e6fb`. Packaged application SHA-256: `fcc6f7f67ea416ee5a9954be8f8a27e27d0982166f3f5414ed6c5909a441383a`.

All 59 compiled application class files and all packaged application resources match the local build byte-for-byte. All 52 nested Java libraries match the existing hash-checked CycloneDX runtime inventory, including Spring's bundled Jarmode tool. No dependency upgrade was made in this batch. Java 27.0.1.0.2+1 / Spring Boot 4.1.2; schema 23.

## Install and recovery checks

Fresh private-credential setup, repeated-setup preservation, Compose TCP readiness, login and administrator API access passed. Schema **23→23 restore** and **20→23 upgrade** passed using separate disposable containers/volumes with the source stopped before backup. The source upgrade image is `sha256:6f3650deec75e277d03443d5d03ff85b0639310e0c68e1b2fe942b5c9985fe14`.

Both recovery drills completed in 13 seconds locally; this is not a recovery SLA. They checked configuration, records, attachments, private/requester access, account credentials, API-only/password-replacement restrictions, custom password exclusions, audit, revision-bound approvals and encrypted pending delivery. Outbound email remained disabled. The new account-limit migration, session policies and session/audit transaction behavior are additionally exercised by the database acceptance suites. Browser sessions deliberately require fresh login after application restart; registry persistence is not distributed servlet continuity.

## Scans and remaining limits

Trivy 0.76.2 used vulnerability data updated 2026-11-15 (Java DB also 2026-11-15). The measured results were:

| Scope | Result |
|---|---|
| Packaged Java libraries, verified against runtime SBOM | No reported vulnerabilities |
| Repository dependency manifests in the publication snapshot | No reported vulnerabilities |
| Runtime OS packages | 0 critical, 0 high; **30 medium and 4 low**, with no fixed versions listed |
| Source/publication snapshot, Gitleaks with redacted reporting | No reported secrets |

Scanners and exact coverage do not establish absence of vulnerabilities. The OS findings remain open, and production identity, real email/HTTPS delivery, operating ownership, workload capacity, retention policy and M1–M3 adoption gates remain unqualified. Native text/multipart/empty-content-type Basic clients must follow the [new explicit header or CSRF contract](api.md#browser-writes-and-basic-credentials). No remote deployment or push was performed.

Reproduce acceptance with `sh scripts/verify.sh` using the pinned Java installation. Build `docker build -t openservice:followup10-20261115 .`; run `scripts/fresh-install-drill.sh IMAGE`, `scripts/restore-drill.sh IMAGE` and `scripts/restore-drill.sh IMAGE PRIOR_IMAGE`. Temporary evidence is under `target/release/` and `/tmp/openservice-followup10-evidence/`; per-cycle logs and scan results use `/tmp/openservice-followup10-*`. These local temporary files are not a durable external evidence archive.

## AI product direction

Requirement added 2026-11-16: OpenService must become fully AI enabled, agentic and conversational across its core journeys. The [AI capability and acceptance guide](ai-capabilities.md) defines the required work and evidence. This direction does not change the implementation scope or historical validation results recorded above.
