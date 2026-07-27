# Workflow AI evaluations

`../../src/main/resources/ai-evals/workflow-ai-v1.json` is the stable benchmark for Voyager's
workflow assistant and is shared by the backend and CLI runner. It exercises the
real HTTP API and configured model, then applies deterministic graders to response shape,
validation issues, chat routing, JSONata ASL structure, resource classification, secret safety,
and retry supersession.

Run it against a started Voyager backend:

```powershell
node bench/ai-evals/run-workflow-ai-evals.mjs --base-url http://localhost:8081
```

Useful options:

- `--model-id <uuid>` selects a specific enabled Voyager model; otherwise the default/first enabled
  model is used.
- `--repetitions 3` measures pass rates across stochastic runs.
- `--output <path>` chooses the JSON report location.
- `--keep-conversations true` keeps benchmark conversations for inspection. By default each
  temporary conversation is deleted after grading.

The runner exits `0` when every quality gate passes, `2` when the benchmark completes but a gate
fails, and `1` for setup/runtime failures.

Generated JSON reports are stored under `results/` and ignored by Git because they contain
run-specific model output and conversation identifiers.

## In-app runner and LLM judge

The same suite powers the in-app benchmark (AI workflow chat → model settings → **Added Models**):
Quick runs one pass, Reliability three, and results persist per model as the ranked capability tape
(Chat / ASL / MCP / Functions / Safety).

The in-app runner can additionally attach an **LLM judge**: pick any registered, enabled model in
the "LLM judge" selector before starting a run. The judge receives each case's instruction, the
suite's per-case `judge.expectation` rubric, and a bounded summary of the candidate response
(message, ASL, proposed function code, MCP requirements, validation issues), and returns
`{"score": 1-5, "rationale": "…"}`. A case passes the judge at `score >= judge.passScore`
(suite default 4).

Judgments are **advisory**: they never move deterministic metrics, quality gates, latency numbers,
or the RECOMMENDED/LIMITED/FAILED recommendation. The persisted result JSON gains a `judge` block
(judge model, mean score, pass rate, STRONG/MIXED/WEAK verdict, per-case failure rationales, judge
errors) and each observation gains its own `judge` node. Judge calls run after a case's latency is
measured, and a judge outage records a judge error instead of failing the case.

Practical notes:

- Prefer a judge stronger than the model under test; a model judging itself grades leniently.
- The judge prompt pins candidate output as untrusted data, so instructions embedded in a
  candidate's reply are graded, not followed.
- The CLI runner stays deterministic-only; the judge is a feature of the in-app runner.

Keep the suite unchanged so results remain comparable. When product expectations change, copy it to
`workflow-ai-v2.json` and document the changed contract instead of silently moving the baseline.
