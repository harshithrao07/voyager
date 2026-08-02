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

- [x] **Real tool-calling instead of a text catalog** — large catalogs use a bounded read-only
  loop with `search_catalog` and `validate_asl`; small catalogs and endpoints that reject tools retain
  the prompt-catalog fallback. Function creation deliberately remains an approval-gated resource
  proposal followed by safety checks and Judge0 qualification rather than a model-authorized write.
  Every turn now persists aggregate multi-pass tokens plus per-tool mode/status/duration, rejection
  and fallback traces, and an estimated prompt-catalog token reduction. The chat UI exposes those
  details and live `Searching the catalog` / `Validating ASL` stages. The registered-model eval suite
  covers native-or-automatic tool use, bounded rounds, exact final validation, and grounded Task URIs.
- [x] **Semantic caching + RAG** — catalog retrieval is delivered (see **Embeddings / RAG catalog
  matching** above). Semantic *response* caching is now in place via the adaptation path: saving an
  AI-authored workflow embeds its instruction and stores the validated ASL
  (`WorkflowSolutionCacheService.recordSolution`, called from `saveWorkspaceWorkflow`); starting a
  new conversation embeds the instruction, retrieves the nearest prior solution
  (`workflow_solution_embeddings`, cosine distance, reuses the default EMBEDDING model + shared
  vector column), and — within `adapt-max-distance` — seeds the prompt with it as an adaptation
  template (`startConversation`). The model still regenerates and the result is validated against the
  live catalog, so a stale entry is self-healing and never served verbatim. Enabled by default
  (`scheduler.workflow-ai.solution-cache.*`); `adapt-max-distance` calibrated to 0.35 against
  mxbai-embed-large (related instructions ≤0.23, unrelated ≥0.56). Verified live: read-path query
  matched a similar request at 0.078 and rejected an unrelated one at 0.58. **Bug fixed in passing:**
  the vector dimension was hard-pinned to 768 (nomic) but the configured model is mxbai-embed-large
  (1024), so *all* embedding writes were silently rejected and `resource_embeddings` was empty —
  catalog RAG had never worked with this model. Replaced the hard pin with a fixed-width design:
  `EmbeddingVector.DIMENSIONS` = 4000 (pgvector's halfvec index ceiling) is the single source of truth
  for both tables' columns, and `WorkflowAiEmbeddingService.embed()` zero-pads shorter model outputs
  up to it. Trailing zeros leave cosine distance unchanged (verified: raw-1024 vs padded-4000 are
  byte-identical), so any embedding model of ≤4000 dims now works with no code/config/schema change;
  the obsolete `embedding.dimensions` property was removed. `resource_embeddings` now populates (14
  tools, stored at width 4000 with real values in the first 1024). **Self-healing:**
  `WorkflowSolutionCacheService.reconcile()` re-embeds cached solutions left over from a previous
  EMBEDDING model (re-embeds the stored instruction text in place, so the cache survives a model
  switch rather than being lost) — verified live: a planted stale-model row re-embedded to the current
  model within one pass, no manual cleanup. **Intentionally out of scope** (decided against, not
  pending): Mode A verbatim-serve (a speed-only optimization for exact-repeat requests, whose safety
  cost isn't worth it while the adapt path already re-validates every result); orphan-row pruning
  (harmless — templates are re-validated on use); and embedding executions (would only benefit a
  future failure-triage upgrade, not workflow generation).
- [~] **LLM observability / tracing** — self-hosted **Langfuse v4** stack added to docker-compose
  (langfuse-web on 3100; clickhouse/minio/redis/postgres internal-only; headless-provisioned org
  "Voyager"/project "Voyager AI" with fixed API keys). `LangfuseTracingService` ships one OTLP trace
  per workflow-AI turn to `/api/public/otel/v1/traces` (HTTP basic auth), reconstructed from
  telemetry the app already captures — a root span (→ trace, `langfuse.session.id` = conversation id,
  input/output, stage + prompt-fingerprint metadata) plus a `generation` child (model, token usage,
  latency). Uses the OpenTelemetry Java SDK; export is post-turn and fully failure-safe, so an
  unreachable Langfuse never affects a turn. Verified live: turns produce linked trace+generation
  observations readable via Langfuse's v4 `GET /api/public/v2/observations`. **Plus a native in-app
  panel** (kept alongside Langfuse, not instead of it): `GET /app/v1/ai/observability/metrics`
  (`WorkflowAiObservabilityService`) aggregates the same persisted per-turn telemetry into totals,
  latency avg/p50/p95, a per-model breakdown, a finish-reason taxonomy, and recent turns — rendered in
  the sidebar's **Observability page** (stat tiles + tables) above an "Open Langfuse" link-out
  (Langfuse can't be iframed: X-Frame-Options + cross-origin cookies). The native panel reads
  Voyager's own tables, so it needs no Langfuse API. **Delivered:** latency, session grouping, model,
  tokens, prompt-version signal, a finish-reason failure taxonomy, and the in-app dashboard.
  **Deferred:** $ cost (local Ollama models aren't in Langfuse's price table — infra supports it for a
  priced model) and per-pass / per-tool child spans (one aggregate generation per turn for now).
- [ ] **Guardrail / safety classifier** — a small policy model that flags PII or `DESTRUCTIVE` MCP
  usage before activation (pairs with the Safety items above).

---

## Notes

- Delivered already (context for the above): AI resource provisioning — the assistant proposes
  functions (Voyager creates + publishes on approval) and MCP attach requirements at the
  `RESOURCES_PROPOSED` stage, then generates the ASL against the updated catalog.
- Infra (Jenkins CI/CD) exists in-repo but is intentionally **inactive** until the app is complete;
  see [`jenkins.md`](jenkins.md).
