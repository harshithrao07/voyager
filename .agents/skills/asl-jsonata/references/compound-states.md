# Compound states

## Parallel

Required:

- `Type`: `"Parallel"`.
- Non-empty `Branches`, each a nested machine with `StartAt` and `States`.
- Exactly one of `Next` or `"End": true`.

May use `Comment`, `Assign`, `Arguments`, `Output`, `Retry`, and `Catch`.

Behavior:

- Evaluate Arguments, defaulting to state input, and pass the same value to each branch.
- Execute branches concurrently and wait for all.
- Result is branch outputs in declaration order.
- Output defaults to that result array.
- Succeed ends only its branch.
- Unhandled branch failure fails Parallel and terminates other branches.
- Branch transitions and variables are isolated.

## Map pipeline

Required:

- `Type`: `"Map"`.
- `ItemProcessor`: nested machine with `StartAt` and `States`.
- Exactly one of `Next` or `"End": true`.

May use `Comment`, `Assign`, `Output`, `Retry`, `Catch`, `Items`, `ItemSelector`, and `MaxConcurrency`.

Do not generate deprecated `Iterator`.

**Not supported by this scheduler** (rejected at activation as `RUNTIME_SUPPORT`): `ItemReader`, `ItemBatcher`, `ResultWriter`, `ToleratedFailurePercentage`, and `ToleratedFailureCount`. Do not generate these. Any unhandled iteration failure fails the Map (subject to its `Retry`/`Catch`).

Order:

1. `Items` selects/constructs the array.
2. `ItemSelector` transforms each element.
3. `ItemProcessor` runs per item.
4. `Output` transforms the Map result.

## Items

`Items` is an array or JSONata expression producing an array. Without `Items`, the state input must be an array.

## ItemSelector

- JSON or JSONata expression producing JSON.
- Replaces each raw element's iteration input.
- May reference Map item index/value through `$states.context.Map.Item`.
- Defaults to the current element.

```json
"ItemSelector": {
  "order": "{% $states.context.Map.Item.Value %}",
  "index": "{% $states.context.Map.Item.Index %}",
  "customerId": "{% $states.input.customerId %}"
}
```

## ItemProcessor

- Contains `StartAt` and `States`; may have `ProcessorConfig` (only `Mode: INLINE` is supported).
- Processes one selected item per iteration.
- Transitions cannot cross its boundary.
- Succeed ends one iteration.
- Every iteration has isolated variable values.
- The Map result is an ordered array with one result per item.

## Concurrency

`MaxConcurrency` is a non-negative integer or JSONata expression producing one:

- `0` or absent: no workflow-declared limit.
- `1`: sequential.
- Positive `N`: at most N active iterations.

The runtime may enforce a lower platform limit but cannot exceed a positive workflow limit.

## Failure handling

Any unhandled iteration failure fails the Map, which is then subject to the Map's own `Retry` and `Catch`. There is no tolerated-failure threshold.
