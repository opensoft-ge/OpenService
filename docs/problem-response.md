# Workaround delivery and known errors

A problem coordinator can now send a published workaround to linked incident desks, see the outcome on each incident and turn a failed attempt into required work for an assigned team. The same investigation can produce a private known-error draft for independent knowledge review.

## Respond to a recurring incident

1. Link the incidents to the problem in their investigation forms. Publish the cause and workaround on the problem.
2. Open **Workaround delivery and known error** on the problem and select **Notify linked desks**. Each eligible incident receives a native work note. Staff who follow its notes receive the usual in-app notification. The note directs staff to the investigation. The workaround text stays behind the problem's access controls.
3. On a linked incident, select **Report workaround outcome**. Choose **Effective** or **Ineffective** and describe what happened. A later observation can revise that outcome. The problem owner can see the actor, time and earlier responses for each incident they can read.
4. For an ineffective workaround, choose a follow-up team. The incident needs a work-task plan with a configured closure state. A single required task enters the team's normal queue. Its owner claims and completes it through the existing case-task interface. The normal incident closure gate enforces completion.
5. After changing the workaround, publish and send its new revision. Each desk starts with a fresh outcome. Prior feedback stays associated with its original revision. Saving the analysis as a draft withdraws current guidance. A coordinator can inspect the exact text of previous communications under **Communicated revisions**.

A repeat send delivers the same revision to newly linked, currently accessible incidents. It preserves earlier notes and receipts. Simultaneous sends are serialized with changes to problem analysis and incident links. A repeated identical feedback submission from the same actor returns the existing outcome. Conflicting writes receive a revision conflict. Revoking read access or unlinking an incident removes its feedback from this desk. The UI lists up to 25 recent feedback entries per visible incident and 25 communicated editions per problem. The underlying edition and feedback history is retained.

## Publish a known error

Select **Create known error draft**. OpenService copies the saved cause and workaround into one private knowledge record owned by the author. The problem retains the source analysis revision. **Refresh private draft** can update an untouched, never-published private article. A manual edit, a wider sharing setting or prior publication requires the knowledge owner to reconcile further changes. Retrying creation returns the existing article.

The knowledge administrator reviews the wording and deliberately changes the article's visibility to workspace before publication. If the article's publication policy names an independent reviewer, submit it for approval before applying publication. **Review publication** and **Review employee feedback** open the existing knowledge screens. Employees can find the approved snapshot in knowledge search, read it and rate that version. Reclassifying the article as private removes it from employee search.

## Native API

Use `GET /api/openservice/problem/{problem_id}/response` to obtain the current analysis revision, authorized incident rows, delivery totals and permitted article details. POST to the same endpoint. The browser uses `/record/problem/{problem_id}/response`. All actions require the current integer analysis revision.

| Operation | Additional inputs | Result |
|---|---|---|
| `communicate` | None | Records the published edition and posts one note per readable, linked incident for that edition. |
| `feedback` | `incident_id`, `feedback_revision`, `outcome`, `detail` | Appends the next observation for that incident and edition. Use zero for the first response. |
| `followup` | `incident_id`, `feedback_revision`, `group_id` | Creates one required native incident task following an ineffective outcome. Concurrent retries preserve the first group and task. |
| `draft` | None | Creates or retrieves the primary private known-error draft. |
| `refresh` | None | Updates an eligible draft from the current analysis. |

Migration 104 adds durable receipts and article associations. Existing investigations acquire their first delivery evidence when staff explicitly send a published workaround. The response endpoint enforces current problem, incident, work-note and article permissions. An incident desk can record feedback with incident update permission. Managing the communication or the source article linkage requires problem update permission.

## ServiceNow comparison

The target reviewed on 21 November 2026 is the Brazil family. ServiceNow identifies Patch 2 as the GA baseline. The exact builds of the two Problem Management plugins remain unverified. [Brazil GA release notice](https://www.servicenow.com/community/upgrades-and-patching-blogs/brazil-patch-2-ga-family-release-now-available/ba-p/3618406)

The Brazil documentation describes notifying related incidents of a workaround and creating a primary known-error article from problem analysis. The latter requires the Problem Management Best Practice and Knowledge Integration plugins. OpenService uses its native staff roles, record permissions, journal subscriptions, work tasks and article approval policies. Caller email, paging escalation changes, vendor plugin interoperability, generalized knowledge-base user criteria and duplicate article matching remain open. Standard parent–child incident state synchronization and scheduled major-incident stakeholder communications are separate gaps. [Communicate a workaround](https://www.servicenow.com/docs/r/it-service-management/problem-management/communicate-workaround.html) · [Create a known error article](https://www.servicenow.com/docs/r/it-service-management/problem-management/create-known-error-from-problem.html) · [Knowledge article access criteria](https://www.servicenow.com/docs/r/servicenow-platform/knowledge-management/t_SelectUCArticle.html) · [Parent–child incident synchronization](https://www.servicenow.com/docs/r/it-service-management/incident-management/parent-child-state-sync.html)

The catalog assessment retains 0.75 for Problem Management and Incident Management and 0.50 for Knowledge Center. These workflows deepen existing capabilities within those bands. The resulting estimate remains 39.25 points across 151 areas (26.0%). [Product scorecard](servicenow-brazil-parity-estimate.csv)

## Reproduce the acceptance checks

Use Java 27 and the project's Maven wrapper. The focused checks exercise the HTTP and native browser paths on the two supported databases. The browser journey covers desktop light mode and mobile dark mode, including the publication review and employee feedback screens.

```sh
./mvnw -B -Dtest='WorkflowTest#problemResponseReachesLinkedDesksAndTracksRecovery+knownErrorUsesPrivateDraftAndReviewedPublication,PostgresWorkflowTest#problemResponseReachesLinkedDesksAndTracksRecovery+knownErrorUsesPrivateDraftAndReviewedPublication,BrowserTest#problemInvestigationJourney,MigrationsTest#workTaskUpgradeRetainsAssignmentsAndDependencies' test
```

The new endpoint returned HTTP 404 on the unmodified base commit `28ab6437c227`. Acceptance tests on the implementation cover concurrent dispatch, a newly linked incident, a withdrawn and superseded edition, restricted records and fields, conflicting desk feedback, concurrent creation of one required task, closure before and after task completion, knowledge approval, employee search, rating and removal from search after access changes. They also verify that a manual article edit survives a refresh conflict.

The full regression run passed 2,096 JVM tests, including 165 browser tests, and 35 Python tests. No tests were skipped. JaCoCo recorded full coverage of the 4,168 executable lines, 3,442 branches and 97 methods in the four changed Java production files.
