# OpenService: second 20-round development and QA batch, 17 November 2026

Status: in progress. Feature baseline: `a143e653d6a738fa3ce3574f3f7d82c10518b39e`, clean `main`. Fixed QA base: locally recorded `origin/main` / merge base `e80a665f121e99489f480131a52ca777ec304282`; reviews include the previous batch's accumulated changes and repairs. No fetch, push, deployment or history rewrite. Each new round requires a bounded feature, meaningful acceptance, clean full verification, exact whole-touched-file coverage, an accumulated-change QA review and a local opendevbot commit without co-author trailers.

The latest 20 completed features covered 13 workstreams, no more than two each, largely completing browser journeys over existing engines. This batch favors additional service/business behavior and integration/governance prerequisites. Review the proposed slice at each selection and reallocate if actual implementation or dependencies contradict the plan. QA repairs do not count as extra features.

Stack reverified on 2026-11-17: [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html) identify stable 4.1.2 with Java support through 27; [Oracle downloads](https://www.oracle.com/java/technologies/downloads/) identify Java 27 as latest GA. Retain Java 27/Spring Boot 4.1.2. Oracle's current vendor patch listing is 27.0.1.0.3; local test JDK is 27.0.1.0.2 and pinned container vendor builds are qualified separately, not claimed identical to that Oracle patch. No preview stack selected.

## Planned allocation

| Workstream | Rounds | Intended outcome / remaining dependency |
|---|---|---|
| 1. Shared deployment | Deferred | Actual HTTPS/SMTP, permitted users and operating owner need a named deployment; local checks do not complete M1. |
| 2. Enterprise identity | Deferred | SSO/MFA and provider recovery require a coherent identity-provider contract and live qualification. |
| 3. Account lifecycle | Deferred | Existing profile/password/onboarding work is substantial; avoid another optional credential refinement ahead of business journeys. |
| 4. Permissions/isolation | 7 | Make table-read policy configurable and consistently enforced, or select a complete independently useful permission journey if architecture blocks it. |
| 5. Audit/governance | 4 | Attachment quarantine/hold with enforced download exclusion and audited release; manual control is not malware scanning. |
| 6. Jobs/notifications | 8, 15 | Safe bulk acknowledgment of a reviewed personal history window; record subscriptions with permission-aware notifications. |
| 7. Workflow/approvals | 13 | Decision reauthentication with unchanged separation-of-duties and revision boundaries. |
| 8. SLAs | 2, 14 | Start and operate a service clock from a record; distinguish response/resolution commitments or a similarly coherent clock-policy gap. |
| 9. Catalogue/knowledge | 1, 6 | Catalogue offering → owned request journey; scoped article feedback/review follow-up. |
| 10. Integrations | 3 | Save alias → queue a bounded connector probe → inspect receipt → retire alias through the browser. OAuth/federation remains open. |
| 11. Migration | 10 | Explicit migration stop/cancellation with stale worker rejection and honest resumption limits. Live conformance remains open. |
| 12. Data/configuration | 11, 16 | Additional validated reusable field types; reviewable configuration differences before promotion. |
| 13. Assets/operations | 9, 17 | Operational event → correlated case; edit dependency relationships with current access and graph checks. |
| 14. Reporting/search | 5, 18 | Permission-aware report CSV export; bounded time-trend analysis over complete permitted selections. |
| 15. Governed AI | 12, 19 | Grounded published-knowledge assistance; reviewed AI request drafting with explicit execution authority. Provider quality/cost/latency evaluation remains separate. |
| 16. Production/release | 20 | Requalify final package, schema upgrades/restores, workers, scans and bounded load; select a demonstrated reliability gap when found. |

Twenty intended rounds across 13 workstreams; at most two per primary workstream and no consecutive repeats. Rebalance at rounds 5, 10, 15 and 20. Prerequisite dependencies, acceptance and exclusions are pinned in each selected round before editing.

## Round 1 — catalogue offering to owned request

Selected `OS-N17B-01`, workstream 9 / OS-PARITY-04. Current requester intake accepts free text but has no reusable catalogue offerings or order path. Add administrator-managed active/inactive offerings with optimistic revision checks, a browse/detail/order journey, and an owned service request containing an immutable copy of the reviewed offering text. Preserve current request permissions. Bound offerings and order input; reuse a client order key to prevent duplicate requests and reject changed-payload reuse. Retired/changed offerings cannot create new orders. No cart, pricing, variable designer, catalogue-specific eligibility, request-for-another-user or automated fulfilment claim.

Candidates compared: catalogue intake (9), record clock launch (8), and connector probe/retirement (10). Catalogue supplies a prerequisite for reusable fulfilment and governed request drafting; choose it first. Acceptance covers admin lifecycle, optimistic conflicts, requester ownership, strict inputs/roles/CSRF, inactive items, idempotent retry/conflict, source edits after ordering, rollback, persistence and an accessible both-theme keyboard browser journey.

Research retrieved 2026-11-17: official Brazil [catalogue item form](https://www.servicenow.com/docs/r/servicenow-platform/service-catalog/catalog-item-form.html), [Catalog Builder](https://www.servicenow.com/docs/r/servicenow-platform/service-catalog/create-item-cat-builder.html) and [user criteria](https://www.servicenow.com/docs/r/servicenow-platform/service-catalog/t_CreateAUserCriteriaRecord.html), updated 2026-09-10. Active offerings and reviewed ordering inform this original native contract; no vendor wire compatibility or live conformance is claimed. Merge catalogue-specific eligibility, variable definitions and fulfilment orchestration into the existing missing P1 catalogue backlog: depend on a criteria engine and durable fulfilment; acceptance must deny both browsing and direct ordering after eligibility revocation. Next research area: SLA clocks and schedules.

Both database HTTP regressions failed with 404 before implementation. QA R1-1: the catalogue capacity fixture polluted a later browser test; restore its added rows. The requester journey also needs its requester landing page rather than the staff-login helper. QA R1-2: simultaneous direct service calls reproduced a PostgreSQL idempotency race outside the already serialized HTTP dispatcher. Serialize catalogue mutations with the existing transaction lock; retain a two-caller regression on both databases. Upgrade tests exercise schema rollback, repeat startup, credential preservation and retained orders. These repairs count within this feature, not as additional features.

Inspected desktop-light and mobile-dark requester screenshots; automated accessibility and keyboard checks passed. The final accumulated-change review covered route/method/CSRF boundaries, offering edits, concurrent retries, migration rollback, prior AI-consent and approval repairs, field/record serialization, search filters and escaped templates. No new or reopened finding remained after the two documented repairs.

Final gates: 780 tests, zero failures/errors/skips; whole-project Java 7139/7139 lines, 3921/3921 branches, 418/418 methods. Browser-script exact gates passed. Frozen source matched the final tested tree.

| Touched production Java file | Lines | Branches | Methods |
|---|---|---|---|
| Catalogue.java | 36/36 | 40/40 | 4/4 |
| Migrations.java | 187/187 | 100/100 | 3/3 |
| WebController.java | 2236/2236 | 1231/1231 | 41/41 |

Final accumulated-change QA review against `e80a665f121e99489f480131a52ca777ec304282`: no new/reopened findings, no repairs in the final review, and no unresolved finding within this round. Known product gaps remain tracked separately. Local opendevbot commit: `Add reviewed catalogue offerings and owned service requests`. No push.
