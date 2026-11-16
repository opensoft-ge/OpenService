# Twenty-cycle local release qualification — 15 November 2026

Scope: the 20 requested paired OpenService / QA-QC cycles after `3fa801d`. Each numbered cycle has its own bounded contract, official discovery, review record, exact coverage gate and local bot commit in [the cycle ledger](paired-cycles-2026-11-15.md). This is a local evaluation candidate; no publication, cloud deployment or external messages are included.

The batch adds delivery queue diagnostics/filtering/cancellation/retention, verified recovery-email enrollment, stronger password/recovery controls and account authentication history, administrator password exclusions and required replacement, API-only identities, and revision-bound staff approvals with a personal queue, withdrawal, protected transitions, current requirement counts and preserved resubmission history. The final slice adds stateless database/migration readiness. These are bounded original capabilities, not full ServiceNow product parity.

## Current artifact and qualification

Image: `openservice:auto20-20261115`, immutable ID `sha256:6f3650deec75e277d03443d5d03ff85b0639310e0c68e1b2fe942b5c9985fe14`. Packaged application SHA-256: `165244ff7bf976b759703309951a17ceda48d02d3c3544bfb425fe28ea595fd7`. All **52 compiled class files** match the tested local build byte-for-byte. All **52 nested Java libraries** match the hash-checked CycloneDX inventory; no application library is omitted. This inventory includes Spring's bundled Jarmode tool. Java 27.0.1.0.2+1 / Spring Boot 4.1.2, schema 20.

Final clean test gate: **535 tests, zero failures/errors/skips**. Java **4506/4506 executable lines, 2417/2417 branches and 266/266 methods**; exact per-file coverage passes for every production source. Browser migration script: 75/75 lines, 49/49 branches, 12/12 functions; theme: 36/36 lines, 15/15 branches, 6/6 functions. All 37 browser journeys pass. No coverage exclusions or relaxed thresholds were introduced.

Fresh Compose installation, schema **20 → 20** restore and schema **19 → 20** upgrade all passed. The prior image was `sha256:b566974671620d6e5f9942265e851ea5c0838296f9d4654bfb0c9f40232c4b36`. Both restore drills took 13 seconds locally; this is not a recovery SLA. Existing account restrictions, configuration, record/attachment/audit data, encrypted pending delivery and approval history survived.

| Current scan scope | Observed result |
|---|---|
| Maven/npm manifests | 0 vulnerability findings |
| Hash-verified inventory of all 52 packaged Java libraries | 0 vulnerability findings |
| Runtime image, 126 OS packages | 0 critical, 0 high; **30 medium and 4 low**, with no fixed versions listed |
| Current publication source, fully redacted Gitleaks | 0 detected secrets |

Trivy 0.76.2, vulnerability database updated 2026-11-15. Trivy scans the image OS packages with `--pkg-types os --skip-files '**/app.jar'`; application libraries are scanned separately using the exact CycloneDX inventory. This avoids dependence on downloading Trivy's Java identification index. The remaining OS advisories are open release risks, not suppressed or declared harmless. Package scanning does not certify exploitability, runtime/vendor binaries, penetration testing or absence of unknown vulnerabilities.

Reproduce the gate with `sh scripts/verify.sh`. Build with `docker build -t openservice:auto20-20261115 .`; run `scripts/fresh-install-drill.sh IMAGE`, `scripts/restore-drill.sh IMAGE`, and `scripts/restore-drill.sh IMAGE PRIOR_LOCAL_IMAGE`. The target-image scripts check `/readyz`; prior-image sources retain `/healthz` compatibility. All drills create and remove only their own disposable resources and stop the source before taking its database backup. They do not qualify mixed-version rolling upgrades or a production recovery-time objective. Scan/SBOM/artifact evidence is retained under `target/release/` and `/tmp/openservice-auto20-evidence/20/`; each cycle's exact metrics are archived separately.

## Boundaries before operational adoption

M1–M3 remain unmet. Qualify the actual HTTPS/reverse proxy, SMTP/deliverability, authorized recipients, backup encryption/restore ownership, load and operational monitoring before a shared pilot. SSO/MFA, inherited roles, multi-organization isolation, protected audit retention, attachment malware scanning, general business-notification/migration workers, scoped integration credentials, HA/DR and a complete manual accessibility review remain work. Browser tests cover keyboard interactions, mobile overflow and automated axe checks in both themes; this is not complete WCAG conformance. No licensed ServiceNow instance was used for conformance.

The new readiness probe checks database reachability and exact migration history only. It deliberately exposes no internal diagnostics and creates no sessions. It does not validate every schema object or worker/SMTP health, and should not be used to restart all instances during an external database outage. Hosted GitHub CI has not verified these unpublished commits.

## AI product direction

Requirement added 2026-11-16: OpenService must become fully AI enabled, agentic and conversational across its core journeys. The [AI capability and acceptance guide](ai-capabilities.md) defines the required work and evidence. This direction does not change the implementation scope or historical validation results recorded above.
