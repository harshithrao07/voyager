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

Keep the suite unchanged so results remain comparable. When product expectations change, copy it to
`workflow-ai-v2.json` and document the changed contract instead of silently moving the baseline.
