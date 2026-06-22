# Validation checklist

Apply recursively to the top-level machine, Parallel branches, and Map ItemProcessors.

## Machine and graph

- Root is an object.
- `StartAt` is present and names a state.
- `States` is a non-empty object.
- `Comment` is string when present.
- `TimeoutSeconds` is positive integer when present.
- Project output omits `Version` and `QueryLanguage`; compatibility input accepts only supported version and `"JSONata"`.
- State names are unique per machine and at most 80 Unicode characters.
- Every transition target exists in the same machine.
- Nested transitions never cross boundaries.
- Check reachability and terminating paths.

## State transitions

- Every state has a recognized `Type`.
- Task, Pass, Wait, Parallel, and Map have exactly one of `Next` or true `End`.
- Choice, Succeed, and Fail have neither state-level `Next` nor `End`.

## JSONata and variables

- Expressions use `{% ... %}`.
- Parse expressions at definition time when possible.
- Validate expected boolean, number, array, string, and timestamp result types.
- Reject assignment to `states`.
- Reject unavailable `$states.result`/`$states.errorOutput`.
- Detect illegal outer/inner variable name collisions.

## State-specific

- Task has URI Resource and valid timeout/heartbeat/retry/catch.
- Choice has non-empty rules with Condition and Next; Default targets a state.
- Wait has exactly one of Seconds or Timestamp.
- Succeed has no Assign or transition.
- Fail has no Assign/Output/transition; Error/Cause produce strings.
- Parallel has non-empty valid nested branches.
- Map has valid ItemProcessor, array Items, non-negative concurrency/tolerances, and positive batching limits.

## Retry and Catch

- Only Task, Parallel, and Map use them.
- `ErrorEquals` is a non-empty string array.
- `States.ALL` appears alone and last.
- Retrier numeric constraints are enforced.
- Catcher Next resolves in the containing machine.
- Error output is referenced only inside Catcher Assign/Output.

## Reject JSONPath

Reject:

- `InputPath`, `OutputPath`, `Parameters`, `Result`, `ResultPath`, `ResultSelector`
- `ItemsPath`, `SecondsPath`, `TimestampPath`
- `MaxConcurrencyPath`, `MaxItemsPath`, `MaxItemsPerBatchPath`, `MaxInputBytesPerBatchPath`
- `ToleratedFailureCountPath`, `ToleratedFailurePercentagePath`
- `ErrorPath`, `CausePath`
- keys ending in `.$`
- JSONPath Choice fields/operators such as `Variable`, `And`, `Or`, `Not`, `StringEquals`, or `NumericGreaterThan`
- `States.*(...)` intrinsic calls
- per-state query-language overrides

## Review output

For each issue, report:

```text
JSON location
ASL rule
Why it fails
Minimal correction
Runtime-support note, if separate
```

Never call valid ASL invalid merely because the scheduler has not implemented it. Report language validity and runtime support separately.
