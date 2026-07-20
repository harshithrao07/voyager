# Roadmap

Proposed, **not yet committed** work. Everything here is pending — a backlog of ideas to pull from
once the core app is stable. Items reference the systems they plug into so they can be picked up
without re-deriving context.

Status: `[ ]` pending · `[~]` in progress · `[x]` done

---

## AI features (pending)

Grounding: the AI stack lives in
[`WorkflowAiConversationService`](../src/main/java/com/job/scheduler/service/WorkflowAiConversationService.java)
(prompt, parse, repair, provisioning), the live catalog in
[`WorkflowAiResourceCatalogService`](../src/main/java/com/job/scheduler/service/WorkflowAiResourceCatalogService.java),
functions via [`FunctionRegistryService`](../src/main/java/com/job/scheduler/service/FunctionRegistryService.java)
(Judge0), MCP via the MCP registry/tools, and the chat UI in
[`CreateWorkflowView`](../frontend/src/components/CreateWorkflowView.tsx) /
[`ResourcePlanCard`](../frontend/src/components/workflow-create/ResourcePlanCard.tsx).

### Next up (high value, reuses existing machinery)

- [ ] **AI failure triage on executions** — on a failed run, feed the failing state + input +
  `errorOutput` + ASL to the model and return a plain-English root cause **plus a one-click ASL
  patch** (add Retry/Catch, fix a JSONata path). Reuses the repair loop + validators already in
  `WorkflowAiConversationService`, pointed at executions instead of authoring.
- [ ] **AI JSONata expression assistant** — in the state inspector, "describe what you want" →
  validated JSONata `{% … %}` from the state's input shape + desired output. Removes the biggest
  authoring pain point.
- [ ] **Placeholder-secret guard on provisioning** — reject creating any AI-proposed function whose
  code carries placeholder credentials (`YOUR_API_KEY`, bearer tokens) or won't serialize. Small;
  closes a real hole in the resource-provisioning flow.
- [ ] **Auto-generate function test cases** — have the model propose input/expected-output cases so
  provisioned functions ship with a smoke test instead of `testCases: []`
  (`FunctionVersion.testCases` already exists).

### Authoring & editing

- [ ] **Natural-language edit of a selected state/node** ("retry 3× with backoff", "add a Catch to
  email on failure") — scoped edits vs. whole-workflow regeneration.
- [ ] **AI workflow explainer** — readable summary of what an ASL does (useful for revisions/handoff).
- [ ] **"Fix my ASL" button** — run validators, feed issues to the model, apply the corrected
  definition (user-facing version of the internal repair loop).

### MCP & catalog

- [ ] **MCP registry recommendation** — when a `RESOURCES_PROPOSED` MCP requirement appears, search a
  public MCP registry for a matching server and offer one-click "register this."
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
- [ ] **Trust-aware confirmation** — surface when the AI wires in a `WRITE`/`DESTRUCTIVE` tool and
  require explicit confirmation.

### Platform / UX

- [ ] **Streaming responses** — token streaming over the WebSocket instead of returning the whole
  reply at once (faster feel; makes truncation visible as it happens).
- [ ] **Model routing** — auto-pick a stronger model for code-generation steps (function proposals)
  vs. a cheap one for chat, addressing weak-local-model code-in-JSON failures structurally.

---

## AI engineering concepts / experiments (pending)

Techniques worth building into Voyager as much for the learning as the feature. Voyager is a strong
sandbox for these because it pairs **structured output** (ASL/JSONata), **deterministic validators**
(ground truth for grading), **tool use** (MCP), and **code-gen** (Judge0 functions) in one system.

Suggested arc: evals → constrained decoding → distillation / tool-calling.

### Fixes problems already hit

- [ ] **Constrained decoding / grammar-guided generation** ⭐ — force valid output instead of hoping
  for it. Ollama/llama.cpp support GBNF grammars; OpenAI-compat supports `response_format:
  json_schema`. Feed the response contract (stage/message/aslDefinition/resourcePlan) as a schema so
  the model *cannot* emit comments, unescaped quotes, or truncated JSON. Root-cause fix for the
  malformed-JSON failures the lenient parser only patches.
- [ ] **Evals + LLM-as-judge** ⭐ — a benchmark of prompts → expected outcome (proposes function?
  proposes MCP? ASL validates? runs?). The ASL validators are a ready-made automatic grader, so
  prompt/model changes can be *measured* instead of eyeballed.
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
