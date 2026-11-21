# Enterprise platform workflows

The ten workflows below are available in the enterprise workspaces. Staff can open `/enterprise`. The browser and `POST /api/openservice/enterprise/{area}/global` share operation names, authorization and revision checks. The operation result is in `result.outcome`. Use the current revision when changing an existing record. Scheduled operations require the OpenService worker to be enabled.

| Workspace | What an operator can do | Scope of the evidence |
|---|---|---|
| Connector packages | Bind a versioned REST action separately for test and production. Schedule a read with `schedule_delta`. Specify a string request cursor and the corresponding provider output field. Inspect progress with `delta_status` and look up an imported change with `read_change`. Each successful page commits its receipts and checkpoint together. | Local HTTP-provider tests exercise restart, failed responses and replay. The provider must supply stable, unique change cursors. Each poll commits at most 250 rows. A paginated cycle accepts at most 20 pages and rejects a repeated page or a changed endpoint. Recovery retries at the configured interval. The worker verifies the installation revision, destination and scheduling identity. |
| Decision workflows | Build a list policy using the browser’s item rows. Bind each item to a budget owner and a catalog offering. Map a source record field into a catalog question. Start a run from purchasing lines or an authorized HR case. Each distinct owner receives a notice and sees their assigned inputs in a browser queue. Inspect a failed line, correct its order answers and retry it. | The run snapshots the policy, item inputs and offering revisions. A completed line returns its existing catalog request on replay. This is catalog fulfillment. A physical goods receipt remains a purchasing operation. |
| Intake applications | Create confidential and reference fields, configure representative requester, reviewer and outsider accounts, and run native case-access tests before promotion or rollback. Transport schema, workflow and group mappings with `scripts/promote-intake.py`. | Release tests use a temporary case inside a rolled-back transaction. Configuration and credentials are local to each destination. The pipeline driver rejects an unreviewed source digest or unexpected destination revision. |
| Pipeline releases | Propose a CAB window, inspect service conflicts, CI blackout rules and on-call coverage, then record approval or a new time. The registered pipeline calls `deployment_gate` before deploying. Its durable clearance lets `checked_outcome` record the result if a deployment finishes after the window closes. A reschedule invalidates the clearance. Use `cab_history` for the decision trail. | An overlapping approved change on another component of the same mapped service blocks approval. On-call coverage is reported at the proposed start. A new conflict blocks the deployment callback. |
| Service mapping | Import AWS Cost and Usage Report rows, set a service budget, review monthly costs and compare two months with `cloud_variance`. The comparison includes resource-level deltas. | Attribution uses the service map at import. Shared and unattributed charges have separate buckets. Credits retain their sign and original decimal precision. A repeated line is free of additional spend. A changed amount under an existing line ID requires review. |
| Employee request assistant | Search eligible catalog offerings and their linked knowledge policies, choose a suggestion, review its citation, fill in the offering’s questions and confirm. A service desk can accept the conversation when clarification is insufficient. | Retrieval and confirmation check current access. Ranking uses words from titles and configured terms. The published evaluation below includes an unfamiliar paraphrase the matcher missed. |
| Field routing | Search selected stockrooms for a model, reserve a particular serialized asset, release it or issue it to the accepted technician. The offline visit form accepts an installed asset ID. Sync records customer custody, and a return receives customer equipment at a depot. | Duplicate sync leaves one installation. Stock reserved by a catalog order or visit is unavailable to other visits. When a model is unavailable the dispatcher can supply a replenishment time and obtain available appointments with the affected case and original commitment. |
| Employee lifecycle | Publish a location-specific agreement template with retention days. Create an agreement for a workplace event, or bind a native employee transfer to a location. Review and sign the exact generated text in the employee workspace. HR can retain the document under a case hold. | Agreements retain their digest, template version, signer and date. Rescheduling preserves the earlier version as superseded. Scheduled expiry removes document text while keeping its verification metadata. The signature records an authenticated employee attestation. |
| Portfolio finance | Fund a quarterly strategic goal, prepare competing allocations, compare the saved assumptions and approve an option as a second administrator. Book actual labor at a worker’s stored calendar rate. Reopen old plans after reforecasting. | Snapshots include procurement actuals and commitments, posted labor, estimated remaining labor, team calendars and reservations. Each labor receipt posts once. Approval adjusts project budgets by the change from the previously approved allocation. |
| Audit engagements | Schedule a recurring test against one completed privileged grant or all completed grants using the same privileged workflow. Inspect the frozen approval evidence, resolve the underlying problem, and have an independent administrator retest the issue. | The indicator checks distinct signers, account revisions, the employee’s current manager and group membership. Repeat failures update one owned issue per control. A missing historic signer revision cannot establish a current approval. |

## Running the imports and release pipeline

For AWS, use the existing inventory collector with an authorized AWS CLI profile. Configure a service map using resource identifiers that match the billing export. Convert a Cost and Usage Report CSV for one usage account and billing month with the command below. Each output line is a `cloud_import` request body for the mapping workspace.

```sh
python3 scripts/aws-billing-import.py --account 123456789012 --month 2027-01 --source aws < usage.csv
```

For application promotion, export the development app with `package_app`. Review the package and compute its SHA-256 from sorted-key, compact, UTF-8 JSON. Configure `release_test` on each receiving installation. The driver accepts a JSON file with the following structure. The environment-variable names refer to HTTP Authorization headers supplied by the operator.

```json
{
  "sha256": "reviewed-package-sha256",
  "development": {"origin": "https://development.example", "app_id": "source-app-id", "reader_env": "DEV_READER"},
  "test": {"origin": "https://test.example", "app_id": "test-app-id", "design_id": "test-design-id", "group_id": "test-review-group", "bindings": {}, "draft_revision": 0, "release_revision": 0, "author_env": "TEST_AUTHOR", "publisher_env": "TEST_PUBLISHER"},
  "production": {"origin": "https://production.example", "app_id": "production-app-id", "design_id": "production-design-id", "group_id": "production-review-group", "bindings": {}, "draft_revision": 0, "release_revision": 0, "author_env": "PROD_AUTHOR", "publisher_env": "PROD_PUBLISHER"}
}
```

Run `python3 scripts/promote-intake.py configuration.json`. The driver emits a receipt after each destination succeeds. Each destination commits independently. If production fails after test succeeds, review the destination state before continuing. Rollback runs the destination’s access tests and preserves existing cases.

## Employee search evaluation

The two database suites configure the same IT, HR and purchasing sources. They withhold the question texts from the configured intent terms, verify the final native request and assignment, and separately exercise an access revocation between search and selection. A question about salary uses a restricted policy.

| Measure | Result |
|---|---:|
| Correct final outcome | 14 of 15 |
| Expected submissions with the correct policy and offering | 9 of 10 |
| Expected handoffs assigned to the correct group | 5 of 5 |
| Restricted citations exposed | 0 |
| 95th percentile processing latency in the recorded run | SQLite 2 ms, PostgreSQL 25 ms |
| Model cost | USD 0 |

The published run includes each question, expected state and observed result on both databases. [Per-case evaluation results](enterprise-search-evaluation.json)

The missed question was “Can someone set up a tunnel into the company network?” The expected service was VPN access. With no matching term, the assistant handed the question to the desk. The fixture models the employee’s clarification and confirmation. It does not measure an unattended model. Each test run writes per-case results, processing latency and cost to `target/enterprise-search-{sqlite,postgres}.json`.

## Reproducing the checks

The clean 20 November run passed 1,924 distinct Java tests, including the SQLite, PostgreSQL and Chromium suites, and 11 Python tests. Six independent checkouts ran disjoint Java suites. Merging only those checkouts’ JaCoCo execution data passed the Maven release gates with 18,583 of 18,583 lines, 15,559 of 15,559 branches and 1,544 of 1,544 methods covered. The browser-script gate passed 163 of 163 lines, 83 of 83 branches and 26 of 26 functions.

Run `sh scripts/verify.sh` for the repository checks. The Java suites exercise SQLite and PostgreSQL, and the browser suite uses Chromium. The enterprise acceptance tests are in `EnterpriseExpansionAcceptance`. The serialized replacement and customer handoff extend `TenBusinessAcceptance.repair`. Browser coverage includes authoring an item approval, finding and deciding it as the owner, signing an employee agreement, and selecting and confirming a grounded service request.

`python3 scripts/test_enterprise_transport.py` exercises the promotion driver, AWS billing conversion and provider audit reader using fixtures. The Java promotion journey sends the same development package to two independent receiving databases, tests their local roles, and checks that an open case and a local connector binding survive rollback.

## Qualification still required

After an authorized fault-injection run, `python3 scripts/check-provider-effects.py observation.json` reads the provider history. Supply `provider`, `start`, `end` and `auth_env` (the name of an environment variable containing an Authorization header). For Entra add `group`, `member` and `service_principal` GUIDs. For Jira Cloud add `site` (the tenant hostname), `issue`, `actor` (account ID), `from_status` and `to_status` (status IDs). Use a test target dedicated to that run and a completed observation window covering the write, restart and retry. Retain the provider IDs with the connector run, binding rotation and restart records. Audit publication can lag. A result with zero records needs a later observation. The reader counts successful provider effects, including duplicate effects, and issues no writes. [Microsoft Graph directory audit API](https://learn.microsoft.com/en-us/graph/api/directoryaudit-list?view=graph-rest-1.0) · [Jira Cloud issue changelogs](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issues/#api-rest-api-3-issue-issueidorkey-changelog-get)

Live qualification needs an authorized Entra group and test user, a Jira test issue with an allowed transition, and provider audit records for the credential rotation, interrupted response and restart. Live AWS qualification needs an authorized account, its inventory and a matching billing export. Local fixtures and example billing rows provide independent development evidence. Three organizational installations, real user load, and an external signing provider have separate deployment dependencies.

The workflows use OpenService’s existing limit of 1,000 documents per workspace area and 64,000 characters per document. Imported change receipts and billing lines currently consume one document each. Large billing imports, long-lived high-frequency controls and installations with large team calendars need additional storage and load qualification. The control schedule exposes sampling errors alongside prior evidence.
