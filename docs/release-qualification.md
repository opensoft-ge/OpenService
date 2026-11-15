# Release foundation qualification — 15 November 2026

Foundation evidence baseline: local commit `31e4e21`; later appearance changes are qualified separately in [product experience](product-experience.md) and [validation](validation.md). Image/scan/drill results below identify that foundation image and must not be represented as a later image without requalification.

Scope: the five user-selected release priorities, reviewed against `ca4c6dedea6042c241de5078c7e82e08024fa814`. This is a local release candidate for synthetic-data evaluation, not a claim of complete ServiceNow parity or production certification. No cloud deployment, external invitation or publication is part of this qualification.

## Implemented contracts

| ID / priority | Result and boundary |
|---|---|
| OS-050 / reliable CI | Reproduced PostgreSQL's temporary initialization server accepting Unix-socket readiness while TCP was unavailable. JDBC tests now wait for authenticated `SELECT 1` on the published TCP endpoint. Compose and restore-drill health checks explicitly use TCP. Coverage thresholds and acceptance cases remain enforced. |
| OS-047 / background delivery | Transactional encrypted outbox, concurrent claims, leases, restart recovery, backoff from completion, five-attempt failure handling, bounded submissions, sanitized queue UI/API and administrator retry. Account email is wired end to end; general notifications, migration execution and connector consumers remain future work. |
| OS-048 / onboarding | Emailed single-use invitations verify recipient address on acceptance. Verified-email recovery revokes old credentials/sessions. The browser test performs administrator invitation → real local SMTP → recipient acceptance → requester work → recovery → deactivation. Manual links remain an explicitly unverified fallback. |
| OS-049 / access | Requester ownership intersects existing private/group visibility. Field-read roles apply to forms, schemas, APIs, query conditions/order/grouping, exports, audit snapshots and numeric reports. Blind field submissions are rejected before stored-value comparisons. Raw source export and deleted-record field snapshots are administrator-only. |
| OS-050 / qualification | Fresh setup/Compose, schema upgrade and restore drills, dependency and image vulnerability scans, current-source secret scanning, browser accessibility review and documentation. Deployment-specific acceptance remains separate. |

## Reproduce

Use the pinned Java 27.0.1.0.2+1 toolchain, Spring Boot 4.1.2, Docker and Node.js 24. The disposable drills also use Bash, curl, jq, OpenSSL and ripgrep. The runtime stack was rechecked against [Spring Boot's compatibility requirements](https://docs.spring.io/spring-boot/system-requirements.html) and [Temurin's GA release information](https://adoptium.net/en-GB/news/2026/10/eclipse-temurin-17021-21013-2505-2701-available) on 2026-11-15. Node and axe-core are development-only.

```bash
sh scripts/install-browser.sh
sh scripts/verify.sh
docker build -t openservice:release-candidate .
bash scripts/fresh-install-drill.sh openservice:release-candidate
bash scripts/restore-drill.sh openservice:release-candidate
# Optional upgrade rehearsal from an already available, known prior image:
bash scripts/restore-drill.sh openservice:release-candidate PRIOR_LOCAL_IMAGE
trivy fs --scanners vuln --skip-dirs .git --skip-dirs target --skip-dirs tools/browser-coverage/node_modules .
trivy image --scanners vuln openservice:release-candidate
```

The install drill uses a separate Compose project, generated private credentials and an ephemeral loopback port. It verifies setup does not overwrite credentials, health/login, administrator authentication and UID 10001, then removes its own containers/volumes. The restore drill creates a fresh installation and fresh restore target. Pending-email envelopes and requester ownership are compared after stopping the source; restored outbound delivery stays disabled. The optional previous image exercises its schema upgrade. These scripts never target an existing deployment.

## Test and scan evidence

Final clean `sh scripts/verify.sh`: **455 tests, zero failures/errors/skips**. Java: **3640/3640 executable lines, 1931/1931 branches, 225/225 methods**, 22542/22542 instructions and 41/41 instrumented classes. The skill's exact-count script also passed for every production Java source file. Browser migration script: **75/75 lines, 49/49 branches, 12/12 functions**. No coverage exclusions or relaxed thresholds were added. Templates/CSS are not Java executable lines; their behavior is checked by the HTTP/browser/accessibility suites.

Fresh Compose setup, schema 14 → 14 restore and schema 13 → 14 upgrade all passed. The previous-image source was `sha256:1e33593275fe32f0f10c9a66b7acc1ed41445619f9a398751a415f92eb157768`. The final patched image is `sha256:197481d911ce206b9caa1fc82972871c93c340d83dcbbd07cb61e78ab7185062`. All 42 compiled production class files (including the nonexecutable sender interface) in the packaged application match the tested build byte-for-byte.

| Scan scope | Final observed result |
|---|---|
| Maven/npm manifests, Trivy 0.76.2 | 0 vulnerability findings |
| All 52 packaged Java libraries, exact CycloneDX inventory | 0 vulnerability findings |
| Final Ubuntu 26.04 runtime image, 126 OS packages | 0 critical, 0 high, **30 medium and 4 low findings**; the scanner lists no fixed versions for the remaining findings |
| Current publication source, Gitleaks | 0 detected secrets; full redaction enabled |

The combined image scan timed out while downloading the Java identification index. The completed fallback scans the image OS packages with `--pkg-types os --skip-files '**/app.jar'` and scans a separate CycloneDX inventory of **every** nested library in that JAR. Each library is byte-matched to its resolved Maven artifact; the bundled Spring Jarmode tool is byte-matched to Spring Boot's loader-tools resource. No application dependency is omitted. The SBOM, scan JSON and drill logs are retained locally under `target/release/` and regenerated for subsequent releases. Java runtime/vendor binaries and unrecognized components are not certified by these package scans.

Available Ubuntu updates reduced OS findings from 59 to 34. Remaining advisories are **open release risks**, not suppressed or declared harmless; assess applicability and track vendor updates before an operational pilot. The scan database was updated on 2026-11-15. No exploitability or penetration-test certification is implied. GitHub-hosted CI has not run this unpublished commit; the same repository gate passed locally.

The initial dependency scan identified five Bouncy Castle 1.83 advisories (two critical, two high, one medium). The dependency is now pinned to 1.85, the vendor's [fixed release](https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-85/). The follow-up Maven/npm manifest scan reported no vulnerabilities. Runtime/transitive dependencies are additionally checked in the packaged image; the license inventory is reconciled with its 52 nested JARs. Source secret scanning uses Gitleaks with full redaction over a snapshot of tracked and nonignored untracked publication files; it does not inspect unrelated home-directory material.

Automated axe-core WCAG 2.0/2.1 A/AA checks cover login, invitation administration/acceptance, requester intake/detail/comments, recovery and delivery administration. Tests also check mobile horizontal overflow, keyboard activation, labels and focus-driven form interactions. Contrast defects were fixed in the shared stylesheet. This is bounded automated accessibility evidence, not a full WCAG conformance audit or screen-reader certification.

## Review and repair ledger

1. Initial implementation review: fixed the PostgreSQL readiness race, transactional delivery/capacity handling, encrypted payload lifecycle, query/read boundary consistency and stale permission/onboarding documentation. Updated the CSS byte fixture to the reviewed accessible stylesheet, and regression expectations for newly restricted raw exports/deleted snapshots; no assertions or coverage gates were removed.
2. Qualification review: bounded repeated worker crashes with a visible failure, prevented stale lease acknowledgements, preserved pending work through restore, and tested concurrent claims on both databases. Fresh Compose setup found that forwarding `.env` also forwarded the host `PORT`; the container now explicitly retains port 8080. Permission review found an unchanged-value guessing path; hidden fields now reject all blind submissions, while readable-field write rules retain their existing behavior.
3. Final accumulated-diff review against `ca4c6de`: no new or reopened implementation defects after the repairs; no repairs in this round. Acceptance, exact coverage, installation/restore/upgrade and bounded accessibility checks passed. Remaining OS advisories and deployment-specific qualification are explicit open risks, so this is not an unrestricted production release. No unrelated history or external system is changed.

## Remaining adoption gates

M1–M3 remain unmet. Before a shared evaluation, qualify the actual HTTPS/reverse-proxy and SMTP configuration, authorized recipients and deliverability, backup/secret handling, operational ownership, and the evaluator's required identity/access scope. SSO/MFA, inherited roles, email changes/reverification, multi-organization isolation, protected audit retention, attachment scanning, provider bounce/queue alerts, general notification/migration consumers, production load/HA/DR targets and a complete manual accessibility review remain tracked work. The local queue is at least once; SMTP acceptance is not inbox delivery. No licensed ServiceNow instance was used for conformance.

Official capability evidence reviewed 2026-11-15: Brazil [field ACL enforcement for query inputs](https://www.servicenow.com/docs/r/platform-security/instance-security-hardening-settings/sc-enforce-field-acls-for-inbound-query-requests.html), [email diagnostics](https://www.servicenow.com/docs/r/platform-administration/email-diagnostics-dashboard.html), and [OWASP recovery guidance](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html). These inform the local contracts; they do not establish identical vendor implementation or entitlement coverage.
