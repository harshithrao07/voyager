# Roadmap

Tracked product and AI-engineering work. Pending items form the backlog; completed and in-progress
items remain here as delivery context. Items reference the systems they plug into so future work can
be picked up without re-deriving context.

Status: `[ ]` pending · `[~]` in progress · `[x]` done

---

## AI features

Grounding: the AI stack lives in
[`WorkflowAiConversationService`](../src/main/java/com/job/scheduler/service/WorkflowAiConversationService.java)
(prompt, parse, repair, provisioning), the live catalog in
[`WorkflowAiResourceCatalogService`](../src/main/java/com/job/scheduler/service/WorkflowAiResourceCatalogService.java),
functions via [`FunctionRegistryService`](../src/main/java/com/job/scheduler/service/FunctionRegistryService.java)
(Judge0), MCP via the MCP registry/tools, and the chat UI in
[`CreateWorkflowView`](../frontend/src/components/CreateWorkflowView.tsx) /
[`ResourcePlanCard`](../frontend/src/components/workflow-create/ResourcePlanCard.tsx).

### Next up (high value, reuses existing machinery)

- [x] **AI failure triage on executions** — a "Diagnose with AI" action on a failed/timed-out run
  feeds the failing state + input + error/cause + ASL to the model and returns a plain-English root
  cause plus an optional validated ASL patch; **Apply patch** opens the revision editor pre-loaded
  with the fix (so it still passes ASL validation + the trust gate on save). `WorkflowAiFailureTriageService`
  reuses the model resolver and the authoring validators; endpoint `POST /workflows/{id}/executions/{execId}/triage`.
  - [x] **Live patch-generation verification:** reproduced `States.QueryEvaluationError` from an
    unsupported JSONata function, confirmed two registered models returned validated ASL patches,
    and verified **Apply patch** opens the revision editor with the corrected definition. The live
    check also fixed a React Strict Mode handoff bug that could consume the one-shot patch before the
    committed editor mounted.
- [ ] **AI JSONata expression assistant** — in the state inspector, "describe what you want" →
  validated JSONata `{% … %}` from the state's input shape + desired output. Removes the biggest
  authoring pain point.
- [x] **Placeholder-secret guard on provisioning** — reject creating any AI-proposed function whose
  code carries placeholder credentials (`YOUR_API_KEY`, bearer tokens) or won't serialize. Small;
  closes a real hole in the resource-provisioning flow.
- [x] **Post-creation function qualification and test generation** — AI resource proposals contain
  code without test cases. Approval creates a draft, then generates independent successful examples
  from the intended behavior and runs the draft through Judge0. Only drafts that compile, obey the
  stdin/stdout JSON contract, and match every expected result have their tests persisted and version
  published into the catalog; failures remain drafts with no generated tests.

### Authoring & editing

- [ ] **Natural-language edit of a selected state/node** ("retry 3× with backoff", "add a Catch to
  email on failure") — scoped edits vs. whole-workflow regeneration.
- [ ] **AI workflow explainer** — readable summary of what an ASL does (useful for revisions/handoff).
- [ ] **"Fix my ASL" button** — run validators, feed issues to the model, apply the corrected
  definition (user-facing version of the internal repair loop).

### MCP & catalog

- [x] **MCP registry recommendation** — a "Find a server" deep-link on each `RESOURCES_PROPOSED` MCP
  requirement opens a public-catalog browser (`PublicMcpRegistryService`: bundled JSON always, external
  `registry.modelcontextprotocol.io` enabled by default and configurable via `scheduler.mcp.registry.external.*`); picking an install
  option prefills the register form for the user to review trust + secrets before creating.
- [ ] **Embeddings / RAG catalog matching** — embed functions + MCP tools and retrieve only relevant
  ones per prompt instead of injecting the whole catalog. Scales the catalog and cuts token/
  truncation pressure on local models.
- [ ] **AI-generated descriptions** for functions/MCP tools — better catalog descriptions measurably
  improve resource matching.

### Execution & ops

- [ ] **Run summaries / reports** — natural-language digest of an execution or batch: what ran, what
  each state produced, where it failed.
- [ ] **Anomaly detection** on run metrics (Prometheus is already wired) — flag latency/failure-rate
  spikes.
- [ ] **Pre-run cost/duration estimate** for a workflow.

### Safety (extends the provisioning work)

- [ ] **Pre-activation AI review** — before activation, flag risky patterns: unguarded `DESTRUCTIVE`
  MCP calls, PII in logs, missing error handling.
- [x] **Trust-aware confirmation** — saving an AI-authored workflow that grants `WRITE`/`DESTRUCTIVE`
  MCP trust (`?trust=…`) is blocked until the user confirms. `WorkflowAiTrustReviewService` scans the
  definition; `saveWorkspaceWorkflow` throws `WorkflowAiTrustConfirmationRequiredException` → HTTP 409
  `MCP_TRUST_CONFIRMATION_REQUIRED` with the tools; the UI shows `TrustConfirmationModal` and retries
  the save with `confirmElevatedTrust: true`.

### Platform / UX

- [ ] **Streaming responses** — token streaming over the WebSocket instead of returning the whole
  reply at once (faster feel; makes truncation visible as it happens).
- [ ] **Model routing** — auto-pick a stronger model for code-generation steps (function proposals)
  vs. a cheap one for chat, addressing weak-local-model code-in-JSON failures structurally.

---

## AI engineering concepts / experiments

Techniques worth building into Voyager as much for the learning as the feature. Voyager is a strong
sandbox for these because it pairs **structured output** (ASL/JSONata), **deterministic validators**
(ground truth for grading), **tool use** (MCP), and **code-gen** (Judge0 functions) in one system.

Suggested arc: evals → constrained decoding → distillation / tool-calling.

### Fixes problems already hit

- [x] **Constrained decoding / grammar-guided generation** ⭐ — the provider call now sends the
  workflow response contract through `response_format: json_schema`, starting with strict schema
  enforcement where supported. Capability negotiation is cached per registered model and degrades
  only on an explicit provider rejection: strict schema → schema → JSON object → prompt-only. The
  dynamic ASL payload remains subject to Voyager's deterministic semantic validators.
- [x] **Evals + LLM-as-judge** ⭐ — deterministic `workflow-ai-v1` benchmark, registered-model
  runner, ranked comparison, prompt-freshness tracking, and the LLM judge delivered.
  The benchmark covers prompts → expected outcome
  (proposes function? proposes MCP? ASL validates? runs?) and persists a comparable Chat / ASL /
  MCP / Functions / Safety capability tape for each model. The ASL validators are a ready-made
  automatic grader, so prompt/model changes can be *measured* instead of eyeballed. Any registered
  model can additionally be attached as an advisory judge (`AiModelEvaluationJudgeService`): it
  scores each case 1–5 against the suite's per-case `judge.expectation` rubric and adds a `judge`
  block (mean score, pass rate, STRONG/MIXED/WEAK verdict, rationales) to the persisted result
  without moving deterministic gates or the recommendation.
- [ ] **Distillation / fine-tuning a small ASL model** — generate synthetic training data with a
  large model, then SFT/LoRA a small local model on Voyager's ASL + JSONata + function format.
  Structural fix for weak local models (e.g. qwen3:8b) failing at code-in-JSON.
- [ ] **Data flywheel from user edits** — the `ResourcePlanCard` already captures the human-corrected
  function code before approval. That (AI draft → approved) diff is preference/SFT data; feeds DPO or
  an SFT corpus that compounds with usage.

### New frontiers

- [ ] **Real tool-calling instead of a text catalog** — expose `search_catalog`, `validate_asl`,
  `create_function` as tools the model calls in a ReAct loop, so it queries for resources and checks
  its own work mid-generation rather than reading the whole catalog blob.
- [ ] **Multi-agent orchestration** — Planner → Coder → Reviewer pipeline; the conversation stages
  already imply these roles. A clean study of agent handoff.
- [ ] **Semantic caching + RAG** — embed workflows/functions/executions for catalog retrieval, plus
  semantic response caching (a similar request adapts a prior workflow). Embeddings, vector search,
  reranking.
- [ ] **LLM observability / tracing** — extend the per-message token/duration logging into full
  tracing: prompt versions, cost, latency, failure taxonomy (Langfuse/OpenLLMetry-style).
- [ ] **Guardrail / safety classifier** — a small policy model that flags PII or `DESTRUCTIVE` MCP
  usage before activation (pairs with the Safety items above).

---

## Notes

- Delivered already (context for the above): AI resource provisioning — the assistant proposes
  functions (Voyager creates + publishes on approval) and MCP attach requirements at the
  `RESOURCES_PROPOSED` stage, then generates the ASL against the updated catalog.
- Infra (Jenkins CI/CD) exists in-repo but is intentionally **inactive** until the app is complete;
  see [`jenkins.md`](jenkins.md).
