# Workflow-generation A/B benchmark results

Run dates: 2026-08-03 to 2026-08-04  
Model: local `qwen3:8b`  
Suite: `workflow-generation-ab-v1`, SHA-256
`9fc4033bcf14394a955a6398906b9ff4c1d33185c252ef3266590a04179c4813`

## Headline

The combined enhanced pipeline raised strict workflow-validation success from **1/90 (1.1%)** to
**57/90 (63.3%)**: a **+62.22 percentage-point** lift and a **57x rate ratio**. The paired exact
McNemar result was `p = 2.77556e-17` (0 baseline-only wins and 56 enhanced-only wins).

Use this resume wording:

> Raised valid workflow generation from 1.1% to 63.3% (+62.2 pp; 57x; 90 paired generations) on a
> local qwen3:8b model using pgvector tool retrieval, strict JSON-schema output, and validator-driven
> repair; retrieval avoided an estimated 225k input tokens (20.1%) versus injecting the full
> 44-resource catalog.

## Experimental design

- 30 frozen workflow prompts, repeated three times, for 90 paired observations per arm.
- Both arms used the same model ID, suite contents, case order, and `/no_think` instruction prefix.
- Baseline: tool calling off, embedding retrieval off, structured output off, and zero repair passes.
- Enhanced: bounded tool calling on, embedding retrieval on, strict structured output on, and at most
  two validator-feedback repair passes.
- A success had to contain ASL and pass the response contract, clean-validation check, and recursive
  ASL structural validator. JSONPath-only fields and syntax did not count as valid JSONata workflows.
- The catalog snapshot contained 44 registered resources. Retrieval selected a bounded subset; it did
  not invoke the external Tasks while generating a workflow.

The test measures the enhanced bundle. It does not independently attribute the validation lift to
retrieval, schema enforcement, or repair.

## Validation results

| Measure | Baseline | Enhanced |
| --- | ---: | ---: |
| Strictly valid workflows | 1/90 | 57/90 |
| Validation rate | 1.11% | 63.33% |
| Wilson 95% interval | 0.20%-6.03% | 53.02%-72.55% |
| Paired-only wins | 0 | 56 |

Enhanced results by workload family:

| Family | Valid | Rate |
| --- | ---: | ---: |
| Simple ASL | 13/18 | 72.2% |
| JSONata transformations/conditions | 10/18 | 55.6% |
| Docs MCP tools | 14/18 | 77.8% |
| Notion MCP tools | 16/18 | 88.9% |
| Parallel, Map, nested, and error handling | 4/18 | 22.2% |

Nested and compound ASL remains the clearest improvement target.

## Token results

There are two different token questions, and they must not be conflated.

### Retrieval-context reduction

For the 79 enhanced turns with provider token telemetry, the retrieval telemetry estimated:

- 894,383 actual input tokens;
- 225,264 input tokens avoided versus including the full prompt catalog on each applicable model
  call;
- 1,119,647 estimated input tokens without retrieval;
- **20.12% estimated input-token reduction** from retrieval;
- **16.64% estimated total-token reduction** from retrieval (1,128,211 observed versus a 1,353,475
  no-retrieval estimate).

This is an estimate, not a tokenizer-measured counterfactual. Voyager estimates catalog/tool-result
context at four characters per token and subtracts retrieved tool schemas plus resent tool results
from the full-catalog cost.

### Whole-pipeline consumption

The enhanced pipeline did **not** reduce raw token consumption relative to the prompt-only baseline:

| Provider telemetry | Baseline | Enhanced |
| --- | ---: | ---: |
| Turns with token telemetry | 90/90 | 79/90 |
| Input tokens | 368,640 | 894,383 |
| Output tokens | 18,367 | 233,828 |
| Total tokens | 387,007 | 1,128,211 |

Even with 11 enhanced failed turns missing token telemetry, observed enhanced total usage was 191.5%
higher. The 79 measured enhanced turns made 286 model calls (3.62 per turn on average, range 1-9),
because tool selection and validation repair trade tokens and latency for valid outputs.

Observed tokens per valid workflow fell from 387,007 to 19,793 (94.89%). Treat that figure as
directional: the baseline produced only one valid workflow, and the enhanced numerator excludes token
usage from 11 failed HTTP responses. None of the 57 enhanced successes lacked telemetry.

## Latency

| Measure | Baseline | Enhanced |
| --- | ---: | ---: |
| Wall clock | 10m 10.8s | 2h 30m 23.7s |
| Per-turn p50 | 6.405s | 78.562s |
| Per-turn p95 | 11.059s | 288.600s |
| Per-turn max | 21.180s | 304.246s |

The enhanced arm intentionally includes the latency of all tool-loop and repair calls.

## Limitations and next run

- Arms were paired by case/repetition but run in a fixed temporal order rather than randomized or
  interleaved. The baseline completed on 2026-08-03 and the resumed enhanced arm on 2026-08-04.
- The sample is a frozen local benchmark, not production traffic.
- Eleven failed enhanced HTTP responses exposed no provider token telemetry, so enhanced raw token
  totals are a lower bound.
- A four-arm factorial follow-up (prompt-only, schema/repair only, retrieval only, and full enhanced)
  is required to estimate the individual causal contribution of each safeguard.

## Artifacts

- Baseline: `bench/ai-evals/results/workflow-ai-ab-20260803-182954/baseline.json`
- Enhanced: `bench/ai-evals/results/workflow-ai-ab-20260804-022214/enhanced.json`
- Paired comparison: `bench/ai-evals/results/workflow-ai-ab-20260804-022214/comparison.json`

The result directory is intentionally git-ignored because raw observations contain generated output
and conversation identifiers. This tracked report records the immutable suite hash and aggregate
results.
