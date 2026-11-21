# Service assurance and cross-application workflows

This pass extends ten existing workspaces. Open the named workspace under Enterprise, or post its operation and displayed fields to `/api/openservice/enterprise/{area}/global`. Administrative commands require an active administrator. Employees can search and resume only their own conversations.

| Workflow | How to exercise it | Evidence and limits |
| --- | --- | --- |
| Connector failover | Start two OpenService workers against the same PostgreSQL database with the same application encryption key, `WORK_ENABLED=true`, distinct `CONNECTOR_WORKER_ID` values, and access to the approved origins. Enable connector writes through the existing installation setting. In Packages, install the action, pin its `history_contract`, preview and queue the write. Use `worker_evidence` with the resulting job ID. | The first claim is durable before dispatch. A second worker reconciles a failed write using read-only history when the original job owner still has authority. A reclaimed lease cannot send the write again. The journal identifies the claiming worker, write credential alias and revision, evidence digest, audit connection revision, and recovery worker. Missing, ambiguous, or foreign events leave the outcome unresolved. Workers run the application and share its database. The local lost-response test uses separate JVMs and a controlled HTTP provider. |
| Supplier workflow | Publish reusable legal and security review procedures in Access. In Designer, put both at stage 1 of the high-risk workflow, test the decision routes, and publish. Select a published workflow for each risk level in `exception_design` and bind a versioned purchase action. Enter line data against an open request using `exception_requisition`. Start the exception with the same total as the saved lines. | A requisition can enter review once. The run pins its input lines, action, and selected subflow. After independent reviewers finish, `advance_exception` waits for effective signed terms, creates native orders and records each outcome. Operators can retry after a purchasing hold. Cancellation releases pending requisition lines and creates an assigned reversal request for each created order. The worker resumes reviewed cases when signed terms become effective. Revoking the initiating admin’s authority sends the run for review. Failed lines remain available to an operator for explicit retry. |
| Permanent fix | Link recurring incidents to a problem and publish the workaround. In Releases use `propose_fix` to bind the problem, an existing proposed deployment, an objective for its component, and an observation window. Use the existing CAB and pipeline to authorize and deploy the artifact. Run `verify_fix` to inspect the result. | The workflow requires three linked incidents evidenced by monitored episodes. Failed deployments and rollbacks leave the fix unverified. The observation starts at the first whole probe interval after deployment. Every interval needs two healthy probes. A recurrence or a coverage gap is visible, and the native problem cannot close before verification. A subsequent fix can use a new release. |
| Intune inventory | Bind a Microsoft Graph connection with permission to list designated managed devices. In Mapping, optionally set `intune_authority` to a cloud source keyed by Entra device ID. Call `intune_import` with the same run ID for successive pages. Use `intune_device` to inspect facts and `intune_bind` to correct a match. | OpenService publishes a new map only on the terminal page, rejects foreign continuations, and pins the connection revision during a run. A rename keeps the managed device ID. Ambiguous or reused identities need review. A matching enrolled collector retains its CI when a cloud source disappears while Intune still observes it. This adapter handles a bounded fleet of up to 200 devices and uses the public Microsoft Graph endpoint. Live Windows and Intune qualification remains open. [Microsoft Graph managed devices](https://learn.microsoft.com/en-us/graph/api/intune-devices-manageddevice-list?view=graph-rest-1.0) |
| Employee search | Use the existing Confluence publisher and delegated reader bindings. Employees can compare `search_services` and `weighted_search`, choose an offering, and confirm its request. `resume_search` rechecks the current reader before displaying suggestions. Revoked access withdraws the citation and pending action and restores the configured handoff desk. | The alternate ranker weights titles and rare matching words over accessible offerings. The evaluation checks the actual request and its offering, handoff group, displayed citations, latency, and model cost. The same 35 synthetic questions were replayed across both databases. The indexed and weighted configurations each completed 21 of 30 eligible requests. Fresh staff questions and a designated Confluence tenant are still needed. [Detailed scores and misses](department-search-results.json) |
| Metric anomalies | Create a service objective and its roster. Use `metric_baseline` to supply at least three distinct historical observations per represented UTC hour. Submit subsequent readings with `metric_sample`. Inspect `metric_status` and compare labeled evaluation times using `metric_score`. | The classifier compares latency in milliseconds, saturation percentage, and error percentage with the observed hourly upper bounds. It keeps raw inputs, expected ranges, baseline revision and episode. Related signals share the existing incident and page. Missing telemetry is unknown. The two-day synthetic evaluation below scores both hourly classifications and whole degradation periods. |
| Publisher license position | In Licenses submit a `license_fact` for each dated grant, renewal, credit, installation or removal. Name exact edition and version combinations in each contract's `covers` list and optionally set `through` on time-limited rights. Freeze a report with `license_position` and reuse its ID to reproduce the snapshot. | An installation consumes one license from an eligible pool. Matching can move a flexible right to cover a version that a narrower contract cannot cover. The snapshot shows each allocation, uncovered CI, surplus, rights, source references, and previously verified seat reassignments. Native seat reclamation retains the provider verification and waiting employee. The implemented model is one product with one unit per installation. |
| Delivery performance | Use `track_delivery` to register a team's production pipeline release. Import source references and occurrence times through `delivery_event`. Request `delivery_performance` with UTC start and end times. | The deployment cohort uses a half-open deployment window. Frequency counts reported deployments per elapsed day, including failed deployments. Lead time is mean earliest linked commit to deployment per release with evidence. Failure rate is the fraction with a failed deployment, incident, or revert. Recovery time is the mean first linked failure to first observed restoration. The report lists release, change, contributing events, missing evidence, and metric sample counts. A late incident revises the original cohort. Sources currently push events through the API. |
| Operational resilience | Add applications, suppliers, people, sites, a manual fallback, maximum disruption, and a backlog limit to a continuity plan with `business_tolerance`. Run the exercise and record customer impact using `business_impact` before measuring recovery. | Exercises retain the plan version and its tolerance. Backlog can fail the exercise after technical recovery succeeds. The assigned owner records corrective work. A later passing run of the same plan version verifies the correction. Backlog and business recovery time are entered by the exercise controller. |
| Telecom assurance | Reserve a primary circuit and a disjoint `backup_path`. Bind each installed site to its account, entitlement, calendar, and support contact. Bind a site-specific transaction objective with `circuit_probe`. Submit provider alarms with a stable reference using `network_alarm`. Use `assure_site` after the repaired path carries the transaction successfully. | An interrupted site gets one case, contractual clock, and repair task. A protected site gets an account-scoped backup notice. Replayed alarms preserve case and clock counts. A port recovery alone cannot complete the site's clock. Two fresh, healthy end-to-end observations on the registered objective verify the restored path and publish the customer update. |

## Metric evaluation

The fixture trains on 144 hourly measurements across six days. It then processes 48 hourly measurements from the next two days, including a normal evening peak, telemetry loss, a severe degradation, a mild degradation and a healthy outlier. The labels are independent of the trained ranges. The same result was reproduced on SQLite and PostgreSQL.

| Held-out result | Count |
| --- | ---: |
| Correct hourly classifications | 46 / 48 |
| Unknown readings correctly classified | 2 / 2 |
| Labeled degradation periods detected | 1 / 2 |
| False alarm periods | 1 |

The healthy outlier crossed the learned upper bound. The mild degradation remained inside it. A period consists of consecutive degraded observations with gaps no longer than the configured measurement interval. Overlap with a labeled degradation counts as a detection. These figures describe a controlled scenario with two incidents. [Executable evaluation](../src/test/java/ge/opensoft/openservice/ServiceAssuranceTest.java)

These workflows use the existing revisioned workspace store with a limit of 1,000 documents per area and 64,000 characters per document. Large inventory payloads can reach the document limit before the 200-device fleet limit. The metric replay measures classification behavior at the tested volume. Sustained production telemetry retention and large-fleet throughput require separate qualification.

### Reconciliation and evidence

A connector worker checks the existing operator resolution before reading provider history. This allows a second worker to finish bookkeeping after the original owner's authority changes. The resolution retains its author and note. The history receipt, when present, identifies the independently observed provider event.

Each supplier-exception order records the signed terms used at creation. If a price differs or a termination has taken effect, that line stays available for retry. A permanent-fix check uses the incident's observed occurrence time, or its opening time for a desk-reported incident. Late evidence inside the declared window can invalidate a previous verification and block closure.

When Intune still observes an endpoint after its cloud registration disappears, OpenService retains the enrollment and its affected services. Withdrawal of the final observation triggers the existing retirement and entitlement checks. A conflicting same-day installation or removal can include a positive integer `sequence` in its manifest. A higher sequence takes precedence. An unresolved conflict prevents a new license snapshot for that date. Previously frozen positions keep their original evidence.

A temporary policy-repository outage leaves a pending confirmation available for another permission check. Loss of access withdraws the action and visible citation. The evaluator retains identifiers for articles previously displayed. Circuit restoration requires both healthy probes to have evaluated the current transaction rule. A deployment recovery recorded before its failure is excluded from the recovery-time calculation.

## Qualification dependencies

Live connector qualification requires designated test group and member IDs, a Jira issue and transition, rotatable bindings and authorized access to provider history. The existing read-only audit utility can inspect a completed window independently. The automated worker consumes the normalized, correlation-scoped history contract. A provider-specific history adapter or evidence export must establish that correlation. The local tests never contact either tenant. [Microsoft directory audit API](https://learn.microsoft.com/en-us/graph/api/directoryaudit-list?view=graph-rest-1.0) · [Jira issue transitions and changelog](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issues/)

Live discovery additionally requires an enrolled Windows machine and its designated fleet. Live search requires a designated Confluence site, employee-delegated reader bindings, and questions supplied independently of the development cohort. [Previous qualification guide](industry-operations.md)

## Verification

`ServiceAssuranceTest` exercises SQLite and PostgreSQL. `BrowserTest#composeSupplierException` exercises the browser designer. `WorkflowTest#thirdPartyRecoveryContract` and its PostgreSQL counterpart exercise separate worker processes, a lost response and a rotated credential. `departmentSearchHoldout` in both workflow suites runs the retrieval comparison. Full local verification runs through `scripts/verify.sh`.

### Automatic QA/QC on 21 November 2026

The review covered the accumulated changes on `codex/enterprise-workflows-ten` against `e80a665`, including untracked source, tests and documentation.

| Review round | Findings and disposition |
| --- | --- |
| 1 | Fixed late and out-of-window recurrence handling, supplier contract enforcement, deferred endpoint retirement, malformed Graph continuation handling, same-day license ordering, retained search disclosure evidence and failover after an operator had already reconciled a write. Added regression coverage for the open Java branch gate. |
| 2 | Rechecked the fixes with revoked authority, multiple inventory sources and revised rules. Fixed temporary policy-source failures discarding pending confirmations, obsolete probes authorizing restoration, and early recovery timestamps hiding valid deployment evidence. Covered retry, capacity, ownership and older stored records. |
| 3 | Verified both operator reconciliation entry points. Fixed equivalent HTTPS origins passing release-independence checks and incomplete provider pages counting as proof of a single effect. Corrected the validation message for exercises without business objectives and a millisecond timing race in the certificate test. |
| 4 | Running the suites together exposed shared import receipts and a timing-dependent signed-event assertion. Reset the four new operational tables between workflow tests, scoped receipt counts to the tested connector binding, verified the signature's exact time limits with a fixed clock, and made the license allocation fixture exercise reassignment independently of generated IDs. |
| 5 | The final review found no new or reopened defects. The complete regression suite and exact coverage checks passed. |

The final sharded suite passed **2,054 distinct Java tests**, including **164 Chromium tests**, with zero failures, errors or skips. Maven packaged the application using the combined execution data. JaCoCo covered **21,965 of 21,965 lines**, **17,388 of 17,388 branch outcomes** and **1,692 of 1,692 methods**. The exact per-file check passed for all 255 production Java files changed against the review base.

An independent unsharded run also passed all 2,054 tests. Its initial coverage check missed the license reassignment branch with the earlier fixture. Rerunning the six affected SQLite and PostgreSQL cases with the final fixtures passed Maven verification and produced the same exact counts as the final sharded run.

The 26 Python tests passed. Coverage measured 554 of 554 executable lines and 210 of 210 branch outcomes across all seven operational scripts. All four production browser scripts passed their exact checks with 163 lines, 83 branch outcomes and 26 functions covered. The local TLS exercise also observed the partially deployed certificate before confirming replacement on both endpoints.

### Initial results on 21 November 2026

All 2,033 tests in the clean sharded regression run passed, including 164 Chromium tests. Eight additional SQLite and PostgreSQL cases cover an abandoned write lease, an operator reconciling ahead of failover, recurrence during fix observation, and contention for standby capacity. All 2,041 distinct cases passed. In the abandoned lease case the remote write completed before the original worker recorded the result. A second worker claimed the expired lease, withheld reconciliation while history was incomplete, and verified the single effect when history became available. All 21 Python tests passed. The four production browser scripts reached full measured line, branch and function coverage.

The initial Maven verification and OpenService exact-count gates failed on branch coverage. The following counters describe that run.

| Scope | Lines | Branch outcomes | Methods |
| --- | ---: | ---: | ---: |
| 11 new Java files | 1,111 / 1,111 | 481 / 587 | 59 / 59 |
| 25 Java files touched in this pass | 3,565 / 3,565 | 1,802 / 1,919 | 172 / 172 |
| Entire Java application | 21,904 / 21,904 | 17,245 / 17,362 | 1,681 / 1,681 |

<details><summary>Coverage by changed source file</summary>

| Source | Lines | Branch outcomes | Methods |
| --- | ---: | ---: | ---: |
| BusinessTolerance.java | 38 / 38 | 18 / 22 | 2 / 2 |
| CircuitAssurance.java | 176 / 176 | 70 / 88 | 6 / 6 |
| ConnectorFailover.java | 46 / 46 | 20 / 28 | 3 / 3 |
| CustomerImpact.java | 174 / 174 | 88 / 90 | 3 / 3 |
| DeliveryPerformance.java | 83 / 83 | 59 / 64 | 4 / 4 |
| DeliveryWorkspace.java | 209 / 209 | 137 / 137 | 14 / 14 |
| DesignInputs.java | 66 / 66 | 79 / 82 | 2 / 2 |
| EmployeeSearchReview.java | 58 / 58 | 35 / 40 | 4 / 4 |
| EndpointCollectors.java | 139 / 139 | 53 / 54 | 2 / 2 |
| IntuneInventory.java | 154 / 154 | 53 / 70 | 7 / 7 |
| JourneyForms.java | 50 / 50 | 67 / 67 | 11 / 11 |
| MetricAnomalies.java | 170 / 170 | 81 / 96 | 11 / 11 |
| Migrations.java | 458 / 458 | 234 / 234 | 3 / 3 |
| NetworkCircuits.java | 119 / 119 | 44 / 44 | 3 / 3 |
| PermanentFixes.java | 90 / 90 | 24 / 30 | 7 / 7 |
| ProblemInvestigations.java | 61 / 61 | 55 / 58 | 7 / 7 |
| ProviderHistory.java | 60 / 60 | 20 / 20 | 2 / 2 |
| PublisherPosition.java | 102 / 102 | 51 / 64 | 10 / 10 |
| RecoveryExercises.java | 107 / 107 | 59 / 60 | 5 / 5 |
| SeatRecovery.java | 255 / 255 | 90 / 90 | 14 / 14 |
| Service.java | 570 / 570 | 301 / 302 | 38 / 38 |
| ServiceAssurance.java | 19 / 19 | 13 / 13 | 3 / 3 |
| ServiceSearch.java | 75 / 75 | 26 / 26 | 5 / 5 |
| SupplierExceptions.java | 175 / 175 | 57 / 72 | 2 / 2 |
| WorkWorker.java | 111 / 111 | 68 / 68 | 4 / 4 |

</details>
