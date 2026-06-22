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

May use `Comment`, `Assign`, `Output`, `Retry`, `Catch`, `ItemReader`, `Items`, `ItemSelector`, `ItemBatcher`, `ResultWriter`, `MaxConcurrency`, `ToleratedFailurePercentage`, and `ToleratedFailureCount`.

Do not generate deprecated `Iterator`.

Order:

1. `ItemReader` optionally loads source data.
2. `Items` selects/constructs the array.
3. `ItemSelector` transforms each element.
4. `ItemBatcher` optionally groups elements.
5. `ItemProcessor` runs per item/batch.
6. `ResultWriter` optionally writes results externally.
7. `Output` transforms the Map result.

## ItemReader and Items

`ItemReader` requires URI `Resource` and may have `Arguments` and `ReaderConfig`. `ReaderConfig.MaxItems` is a positive integer or JSONata expression producing one. Implementations may define additional reader configuration.

`Items` is an array or JSONata expression producing an array. It defaults to the ItemReader result. Without ItemReader and Items, state input must be an array.

Reading failures produce `States.ItemReaderFailed`.

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

## ItemBatcher

May contain:

- `BatchInput`: JSON or JSONata expression producing JSON.
- `MaxItemsPerBatch`: positive integer or JSONata expression producing one.
- `MaxInputBytesPerBatch`: positive integer or JSONata expression producing one.

At least one maximum is required. Without ItemBatcher, process one selected item per invocation.

## ItemProcessor

- Contains `StartAt` and `States`; may have interpreter-defined `ProcessorConfig`.
- Transitions cannot cross its boundary.
- Succeed ends one iteration.
- Unhandled iteration failures contribute to tolerance handling.
- Every iteration has isolated variable values.

## ResultWriter

Requires URI `Resource` and may have `Arguments`.

- Without it, Map result is an ordered array with one result per item or batch.
- With it, result is an interpreter-defined JSON description of written results.
- Writing failure produces `States.ResultWriterFailed`.

## Concurrency

`MaxConcurrency` is a non-negative integer or JSONata expression producing one:

- `0` or absent: no workflow-declared limit.
- `1`: sequential.
- Positive `N`: at most N active iterations.

The runtime may enforce a lower platform limit but cannot exceed a positive workflow limit.

## Failure tolerance

- `ToleratedFailurePercentage`: non-negative percentage up to 100, or JSONata expression producing it.
- `ToleratedFailureCount`: non-negative integer or JSONata expression producing it.
- Both default to zero.
- When both exist, exceeding either fails the Map.
- Threshold failure produces `States.ExceedToleratedFailureThreshold`.
