# OpenService: AI, agents and conversational support

Owner requirement recorded 2026-11-16: **OpenService must become fully AI enabled, agentic and conversational across its core product journeys.** This is a delivery requirement, not a statement that all capabilities are implemented. The objective is useful, measurable AI that helps users complete work independently.

## Product journey and outcomes

A requester describes a problem, receives an answer grounded in permitted published knowledge and records, reviews a case or service request, and follows a policy-authorized fulfilment workflow with progress, approvals and human handoff.

Product scope: Requester support, fulfiller triage, source-linked summaries, proposed workflows and bounded service actions, including administrator and operator assistance. Preserve requester/field boundaries, approval authority and internal-versus-public visibility throughout.

Measure: Time to accurate triage and resolution; citation correctness; accepted suggestions; completed requests; correction and escalation rates; unauthorized actions; cost per resolved case.

## Current delivery status

Partial: the existing implementation provides explicitly enabled administrator summaries and permission-filtered grounding previews with ten-minute version/configuration-bound summary consent. OS-N17-14 adds a stateless administrator browser question-and-answer journey with exact-question preview/consent, source revision and escaped draft text. OS-N17B-12 adds explicitly enabled, consented questions about a current published knowledge article for signed-in requesters/readers, with source/version citations, honest provider failures and a retained-audit-based limit of 20 committed provider attempts per account per rolling hour. General multi-turn support and autonomous tool/action execution remain missing. Content de-identification, provider qualification and broader agent evaluations remain open. See the existing OS-PARITY-06, OS-DISC-02 and OS-DISC-AI-CONTAINMENT entries; preserve their unresolved limits.

## Required capabilities

- **Conversational access and support:** a contextual, multi-turn interface for help and real product work, with clarification, editable drafts, progress, cancellation and human handoff with authorized context. Cite permitted source records or documentation, distinguish facts from suggestions, and acknowledge missing evidence. Enforce conversation ownership, retention, export and deletion. Keep ordinary forms/APIs usable; provide accessible keyboard and light/dark experiences. Voice is mandatory for OpenDream and a separately tracked option elsewhere.
- **Assistance throughout the product:** map every core requester, practitioner, administrator and operator journey to useful explanation, search, drafting, diagnosis or planning. Record a reason where AI adds no value. Retrieve only currently permitted data; record/field, organization and source permissions also apply to indexes, memory, caches, citations and exports. Recheck permissions after revocation. A chat widget or stored messages alone do not complete this requirement.
- **Agents that complete work:** support plan → preview → policy-authorized execution → result verification, with structured, validated tools using the same business APIs and authorization as normal users. Scope each agent to an accountable identity and explicit grants. Require human approval for actions outside configured autonomy; bind approval to the actual action, target and current revision. Show concrete effects and outcomes; containment/stop must prevent further actions and report work already in flight. Untrusted retrieved content, quoted conversation and model output cannot grant authority. Capture explicit authenticated approval through the supported modalities, bound to the specific proposal; voice approval must provide the same assurance as a button or keyboard action.
- **Reliable execution:** persist agent state and queued work, bound retries, steps, time, tokens and spend, and expose failures and receipts. Recheck identity, permissions, policy and approval immediately before each side effect. Use idempotency and reconcile ambiguous external outcomes before retrying. Test cancellation, restart, concurrent changes and provider failures. Describe compensation limits accurately; stopping an agent does not undo completed business actions.
- **Portable, governed AI:** configurable provider/model adapters, bring-your-own credentials and qualified self-hosted/open-model options; no mandatory OpenSoft subscription or proprietary feature unlock. Disclose inference/infrastructure costs and model-specific limitations. Minimize/redact transmitted data, protect credentials, restrict egress and bind external-send consent to the reviewed content/version where required. Treat retrieval and tool output as untrusted; isolate generated code, constrain tools and test prompt injection. Retain a useful manual path and honest unavailable states. Test doubles are not evidence of real provider compatibility.
- **Evidence and operations:** version prompts, models, tools, policy and evaluation datasets; record safe execution metadata and accountable changes without dumping secrets or private prompts. Provide operator diagnostics, usage/cost reporting and rollback of configurations. AI features and their administration, security and evaluation tooling belong in the fully featured Apache-2.0 product; optional support is a service, not a feature gate. Third-party providers and models retain their own licences and terms.

## Acceptance and measurable advantage

For a selected journey, define success criteria and numerical quality, latency and cost budgets before evaluation. Compare against the existing manual workflow and, when reproducible authorized evidence exists, the relevant competitor workflow. Measure task completion, time saved, grounded accuracy, human correction, failed/unauthorized actions and cost per completed outcome. Keep representative held-out cases separate from prompt tuning; record dataset provenance, provider/model/configuration, sample size and uncertainty. Do not advertise superior AI on the strength of demos, model names or unmeasured expectations.

Test the real UI → API → authorization → provider/tool → durable state → user-visible result path. Cover ambiguity, unsupported answers, missing or stale citations, hidden fields, cross-organization access, revoked authority, prompt injection, forged approvals, duplicates, timeouts, exhausted budgets, cancellation, restart and failed human handoff. Use deterministic contract tests plus explicitly scoped real-provider qualification with synthetic or authorized data. Preserve the existing exact line, branch and method/function coverage gates; coverage is additional to behavioral and AI-quality evidence.

## Required delivery backlog

The rows below define acceptance scope, not completed features. Track status, evidence, priority, dependencies and next action in the [capability ledger](servicenow-compatibility.md). Existing equivalent IDs remain authoritative and should be cross-linked.

| ID | Required outcome | Dependencies and acceptance |
|---|---|---|
| OS-AI-001 | Conversational help and task completion | Authenticated user/context, governed knowledge and accessible UI. Demonstrate clarification → grounded response/draft → reviewed action → status/handoff with permission changes covered. |
| OS-AI-002 | Assistance across core user and admin journeys | Journey inventory, current source permissions and typed product contracts. Accept accurate source-linked recommendations and editable outputs; preserve a useful manual path. |
| OS-AI-003 | Scoped agents that perform real work | Business APIs, accountable identity, tool grants and approval policy. Demonstrate permitted actions, rejection outside scope, revision-bound approval and observable verified outcomes. |
| OS-AI-004 | Durable, portable and governed execution | Queue/checkpoints, provider adapters, credentials, budgets and audit. Qualify restart, revocation, idempotency, containment, protected data and a supported local/open-model route. |
| OS-AI-005 | Measured quality and competitive outcomes | Representative held-out tasks, declared baselines and reproducible provider runs. Publish completion, correctness, corrections, latency and cost with scope and remaining limits. |

## Continued discovery

During product development, inspect current official counterpart/provider AI documentation and release notes, record source URLs, retrieval date and product/version scope, and add new reusable gaps to the existing roadmap/ledger. Separate verified vendor behavior from owner-defined improvements. Research and documentation do not by themselves authorize external data transmission or implementation beyond the requested scope.
