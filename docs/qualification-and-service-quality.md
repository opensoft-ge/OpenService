# Provider qualification, service quality and industry operations

This guide applies to the enterprise workspaces on the Brazil comparison branch. The same commands are available under `POST /api/openservice/enterprise/{area}/global`. The browser also exposes the required fields, team permissions and revision for each operation.

## Qualifying a designated tenant

1. Install a test Entra membership or Jira transition action with its approved origin, test records and authorized identities. In Workflow Studio, review the exact target and approval steps. Use test bindings that the integration owner can rotate.
2. A native write records its attempt before dispatch. In Packages, use `provider_attempt` with the run ID, one-based step and generation (`sequence` starts at zero). Retain the actor, write alias, origin, connection revision, credential source, token revision, time and correlation ID. Jira transitions send the ID as history metadata. Entra writes send it as the Microsoft Graph client request ID. Provider-side propagation must be verified on the actual tenant.
3. After the authorized test write, credential rotation and recovery, query the independent audit source using `python3 scripts/check-provider-effects.py observation.json`. Use a read-only credential in the named environment variable. Supply the attempt's `correlation_id` for strict verification. A match requires exactly one distinct event for the designated actor, target and change in the entire completed time window, with a matching provider-side correlation marker. A second matching change with a different correlation fails verification. Repeating the audit query performs reads only.
4. Preserve the provider event IDs and report alongside the attempt and worker evidence. The existing `worker_evidence` command applies to queued connector jobs. Native Studio attempts have their own journal. A scope-only report or test-fixture report cannot establish a live failover result.

Example Jira observation. The start and end must enclose the actual write and the end must have passed.

```json
{
  "provider": "jira", "site": "YOUR-SITE.atlassian.net", "issue": "QUAL-1",
  "actor": "TEST-ACCOUNT-ID", "from_status": "10", "to_status": "20",
  "start": "2026-11-21T08:00:00Z", "end": "2026-11-21T09:00:00Z",
  "auth_env": "JIRA_AUDIT_AUTH", "correlation_id": "UUID-FROM-PROVIDER-ATTEMPT"
}
```

For Entra, set `provider` to `entra` and provide the designated `group`, `member` and `service_principal` GUIDs in place of the Jira fields. If Graph returns a different correlation ID or Jira omits the metadata, the strict check exits unsuccessfully. Inspect the retained events and leave the attempt unresolved. Allow for provider audit delivery delay before selecting a final observation window. [Microsoft directory audit record](https://learn.microsoft.com/en-us/graph/api/resources/directoryaudit?view=graph-rest-1.0) · [Jira transitions and changelog](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issues/)

| Live read | How to qualify | Evidence |
|---|---|---|
| Intune and Windows | On the designated Windows host run `python scripts/endpoint-collector.py --windows-registration`. Give the audit reader a config with `provider: intune`, `auth_env`, `managed_device_id`, `entra_device_id`, the expected `tenant_id` and the returned object in `windows_registration`. Run the existing endpoint collector and Intune import to inspect the reconciled CI. | The reader checks both Graph device IDs and Windows OS and records last sync. The optional registration compares the supplied Windows join IDs. The local registration export is an operator-supplied observation. |
| Confluence | Configure `provider: confluence`, `cloud_id`, numeric string `page_id`, `reader_account`, `reader_env`, `publisher_env` and `expected_reader: allowed`. Run the check, remove the designated test reader's access, then repeat with `expected_reader: denied`. In the employee assistant, resume the conversation and try confirming. | The reader verifies the bound identity first. The publisher must independently see the same current page. An unexpected or unavailable response fails the check. The output contains page and account IDs and the revision. Page content and credentials are omitted. |

The implementation targets Java 27 and Spring Boot 4.2.0, the GA release published on 19 November 2026. The runtime inventory matches the packaged dependency set. [Spring Boot release](https://spring.io/blog/2026/11/19/spring-boot-4-2-0-available-now/) · [Java compatibility](https://docs.spring.io/spring-boot/4.2/system-requirements.html)

## Search and monitoring

In the employee request assistant, an administrator can publish `search_dictionary` with `entries` such as `[["virtual private network","VPN","secure remote session"]]`. A group can include phrases. Entries must be distinct across groups. Updating the dictionary uses its current revision. Employees use `policy_search` to search current published policy text, accessible external policy text, catalog names and configured terms. Choosing and confirming use the existing catalog eligibility and delegated-reader checks. The original search modes remain available for comparison. [ServiceNow search dictionaries](https://www.servicenow.com/docs/r/platform-administration/ai-search/dictionary-form-ais.html)

Mapping owners can use `metric_corroborated_baseline` with the same training data and revision fields as `metric_baseline`. The model compares measurements to the same UTC hour. A single latency or saturation excursion becomes a warning. A second consecutive excursion in the same signal and baseline revision, two simultaneous signals, or an error-rate breach qualifies as a degradation. Two failing transaction probes in the same interval under the current rule also qualify a measured service. Missing telemetry stays unknown. Each observation freezes its inputs, revision, warning and available transaction evidence. Retries return the stored decision. For consecutive-sample confirmation, submit measurements in observation order. Transaction evidence arriving later remains in the independent synthetic monitoring history. [ServiceNow Metric Intelligence](https://www.servicenow.com/docs/r/it-operations-management/metric-intelligence/operational-intelligence-overview.html)

### Measured outcomes

Both SQLite and PostgreSQL produced the outcomes below. Search completion requires a persisted request for the expected offering. Handoff scoring checks the assigned team.

| Outcome | Previous configuration | New configuration |
|---|---:|---:|
| Original synthetic cohort, eligible requests completed | 21/30 | 26/30 |
| Original cohort, correctly owned handoffs | 5/5 | 5/5 |
| Additional synthetic cohort, eligible requests completed | 15/18 | 18/18 |
| Additional cohort, correctly owned handoffs | 3/4 | 4/4 |
| Monitoring holdout, hours classified correctly | 46/48 | 47/48 |
| Monitoring holdout, degradation periods detected | 1/2 | 1/2 |
| Monitoring holdout, false alarms | 1 | 0 |

The new search returned zero unauthorized citations in these evaluations. Four original requests still missed. Three went to a person and one purchasing question selected equipment repair. The additional author-created cohort shares offerings and vocabulary with development. The missed monitoring incident stayed inside the historical range and lacked independent transaction evidence. The evaluation file preserves the questions, misses, timings, local model cost and per-database results. [Evaluation results](qualification-quality-results.json)

## Product workflows

| Workspace | What a team can do | Scope |
|---|---|---|
| Intake applications, banking disputes | Configure `case_program` for `dispute` and a team. An admin registers a posted transaction for its holder with `register_disputable_transaction`. Only that holder can dispute it, in its original currency and within its posted amount. One team member investigates and another upholds or declines. The requester can view the decision history. | An upheld dispute records one local adjustment approved for posting. Settlement, card-network submission and regulatory timers need additional integrations. |
| Intake applications, healthcare equipment | Configure `turnaround`. Submit a readable equipment CI and site, place the equipment on hold, record decontamination evidence, and obtain a separate release or quarantine decision. A competing turnaround cannot reserve the same CI. Staff can inspect the device using `equipment_readiness`. | Facilities operations for registered equipment. The decisions record staff evidence. |
| Intake applications, public permits | Configure `permit`. Submit the site and activity, record inspection evidence, then have a separate examiner issue or deny the case. | Issuance assigns a stable permit number to the approved record. Statutory permit documents, fee collection and appeals are additional workflows. |
| Intake applications, attended automation | In a desktop Python environment with Playwright and Chromium installed, run `python scripts/attended-case-entry.py https://YOUR-INSTANCE rows.csv`. Sign in as the submitting employee in the opened browser, open Intake applications, then explicitly confirm the batch in the terminal. | The robot enters up to 100 rows through ordinary browser forms, registers an employee-owned batch and returns one case receipt per row. Reopening the same file skips received rows. A conflicting case puts the batch on hold for review. Use `attended_batch_status` with the printed batch ID to inspect pending and received rows. Re-run using the same user, program, reference and details. The business key prevents a second case, even after a lost response. One attended browser process is supported. |
| Intake applications, governed data | An administrator uses `publish_data_product` to select a source table, group and fields. A current group member reads one record with `read_data_product`. The response includes source table, record, record revision and product revision. | Source permissions apply at each read. A group loses access immediately when its membership is removed. This covers live projections of native records. |
| Licenses | For a new product import `license_fact` with `metric: named_user` in its manifest. Specify an existing OpenService `user_id` on each installation. The existing `license_position` workflow reports assignments, eligible editions, rights, shortfalls, surplus and installation evidence. | One user on multiple devices consumes one right covering that user's installed editions. Device installation remains the default. Existing inventory cannot be relabeled. Frozen month-end positions survive later credits and purchases. Per-core, concurrent-use and mixed-contract optimization remain open. |

Case submissions use `file_industry_case`. The authenticated requester, program and external reference identify a submission. Teams use `industry_queue` and `advance_industry_case`. A reviewer cannot decide their own submission or make both decisions. Changing the configured team affects future cases. `industry_case` returns the original team, program revision and recorded decisions to authorized participants.

Example CSV for the attended robot:

```csv
kind,reference,details
permit,P-0001,"{""site"":""East site"",""activity"":""Temporary outdoor event""}"
```

The browser transport can be exercised against the packaged application using Java 27, Python Playwright, Chromium and OpenSSL. Run `python3 scripts/test-attended-case-entry.py` after packaging. It starts a separate loopback TLS instance, enters the same permit twice, reads the stored case independently and verifies that it still has revision one.

## Verification on 21 November 2026

The clean regression run used six isolated test partitions. All 2,075 distinct Java test cases passed, including 165 Chromium tests. No tests were skipped. The merged JaCoCo report covered all 22,267 executable lines, 17,568 branches and 1,705 methods in the Java application. The ten changed Java source files accounted for 3,726 lines, 3,308 branches and 99 methods. The four production browser scripts covered 163 of 163 lines, 83 of 83 branches and 26 of 26 functions.

All 31 Python tests passed. The three changed production scripts covered 314 of 314 lines and 144 of 144 branches. The packaged application also passed the independent attended-browser replay over TLS. These runs used controlled provider responses. Live tenant qualification requires the designated records and read identities in the qualification procedure above.

[ServiceNow dispute workflows](https://www.servicenow.com/docs/r/financial-services-operations/dispute-management/dispute-management-workflows.html) · [License and permit cases](https://www.servicenow.com/docs/r/government-industry/public-sector-resolving-lpr-case-playbooks.html) · [Clinical asset lifecycle](https://www.servicenow.com/content/dam/servicenow-assets/public/en-us/doc-type/resource-center/ebook/eb-clinical-asset-lifecycle-management.pdf) · [Data product access](https://www.servicenow.com/docs/r/integrate-applications/workflow-data-fabric/request-access-to-data-assets-dc.html) · [Software license metrics](https://www.servicenow.com/docs/r/it-asset-management/software-asset-management/c_SAMLicenseMetrics.html)
