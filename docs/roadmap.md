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
  cause and supporting evidence. Triage is diagnosis-only: it does not propose fixes, patches,
  workflow edits, or next steps. Endpoint: `POST /workflows/{id}/executions/{execId}/triage`.
- [x] **Placeholder-secret guard on provisioning** — reject creating any AI-proposed function whose
  code carries placeholder credentials (`YOUR_API_KEY`, bearer tokens) or won't serialize. Small;
  closes a real hole in the resource-provisioning flow.
- [x] **Post-creation function qualification and test generation** — AI resource proposals contain
  code without test cases. Approval creates a draft, then generates independent successful examples
  from the intended behavior and runs the draft through Judge0. Only drafts that compile, obey the
  stdin/stdout JSON contract, and match every expected result have their tests persisted and version
  published into the catalog; failures remain drafts with no generated tests.

### Authoring & editing

- [x] **Natural-language edit of a selected state/node** ("retry 3× with backoff", "add a Catch to
  email on failure") — scoped edits vs. whole-workflow regeneration.
- [x] **AI workflow explainer** — readable summary of what an ASL does (useful for revisions/handoff).
- [x] **"Fix my ASL" button** — run validators, feed issues to the model, apply the corrected
  definition (user-facing version of the internal repair loop).

### MCP & catalog

- [x] **MCP registry recommendation** — a "Find a server" deep-link on each `RESOURCES_PROPOSED` MCP
  requirement opens a public-catalog browser (`PublicMcpRegistryService`: bundled JSON always, external
  `registry.modelcontextprotocol.io` enabled by default and configurable via `scheduler.mcp.registry.external.*`); picking an install
  option prefills the register form for the user to review trust + secrets before creating.
- [x] **Embeddings / RAG catalog matching** — models are now typed by role (`AiModelRole` CHAT /
  EMBEDDING; one default per role). A registered EMBEDDING model vectorizes functions + MCP tools
  into a pgvector `resource_embeddings` table (`WorkflowAiEmbeddingService`, reconciled on a fixed
  delay, failure-safe). `buildCatalog(intent)` embeds the turn intent and retrieves the top-k
  nearest resources (`cosine_distance` via hibernate-vector) to render at full detail — MCP arg
  schemas + descriptions — while the rest stay a one-line index so the model still sees the whole
  menu. Retrieval only engages once the catalog passes `min-catalog-size`; below that, on a blank
  intent, or on any embedding failure it falls back to the full-detail catalog. Trades the
  KV-cache-stable catalog prefix for per-turn relevance, which pays off exactly when the catalog is
  large enough to pressure local-model context. Pairs with **AI-generated descriptions** (the
  embedded text) below.
- [x] **AI-generated descriptions** for functions/MCP tools — missing descriptions are generated
  after function publication/activation and during MCP tool sync; human/provider descriptions win.
- [x] **Embedding / retrieval ranking** — `EmbeddingRankingService` scores EMBEDDING-role models on
  *retrieval quality* (parallel to the chat `workflow-ai-v1` ranking). Ground truth is synthetic: the
  default chat model writes one natural-language query per catalog resource (cached in
  `resource_eval_queries`, regenerated on text change). Each model embeds the catalog + queries
  **in-memory** (models differ in dimension, so it never touches the fixed-width production
  `resource_embeddings` column) and is scored on where it ranks the correct resource per query —
  recall@1, recall@k, MRR — plus average latency and dimension, ranked best-first. Runs async on a
  background thread (`embedding_ranking_runs` ledger; `POST /app/v1/ai/embeddings/ranking`, poll
  `GET .../latest`); the shared `CatalogResourceProvider` guarantees the eval embeds the exact text
  production does. UI: the Embedding Ranking tab in AI Settings. Answers "which embedding model should
  be the default."

### Execution & ops

- [x] **Run summaries / reports** — completed executions expose an on-demand AI digest grounded in
  the persisted trace: overall outcome, a state-by-state report across root/Parallel/Map scopes, and
  the failure point when applicable. It uses the default Chat model, preserves real scope/sequence/
  status metadata, and retains generation state across in-app navigation.

### Safety (extends the provisioning work)

- [x] **Pre-activation AI review** — an explicit **AI review** action in the editor and workflow
  details calls the default Chat model on demand and shows warning-only observations for unguarded
  `DESTRUCTIVE` MCP calls, data exposure, and missing error handling. The review never changes ASL,
  proposes a fix, blocks activation, or adds model latency to normal save/activate actions.
- [x] **Trust-aware confirmation** — saving an AI-authored workflow that grants `WRITE`/`DESTRUCTIVE`
  MCP trust (`?trust=…`) is blocked until the user confirms. `WorkflowAiTrustReviewService` scans the
  definition; `saveWorkspaceWorkflow` throws `WorkflowAiTrustConfirmationRequiredException` → HTTP 409
  `MCP_TRUST_CONFIRMATION_REQUIRED` with the tools; the UI shows `TrustConfirmationModal` and retries
  the save with `confirmElevatedTrust: true`.

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

### New frontiers

- [ ] **Real tool-calling instead of a text catalog** — expose `search_catalog`, `validate_asl`,
  `create_function` as tools the model calls in a ReAct loop, so it queries for resources and checks
  its own work mid-generation rather than reading the whole catalog blob.
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
