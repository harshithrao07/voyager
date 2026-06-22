# Machine structure and JSONata

## Machine object

Required:

- `StartAt`: string exactly matching a state in `States`.
- `States`: non-empty object mapping state names to definitions.

Optional:

- `Comment`: human-readable description.
- `TimeoutSeconds`: integer maximum execution duration; exceeding it produces `States.Timeout`. Absence means no ASL-defined machine timeout.
- `Version`: optional ASL version; this project omits it and assumes ASL 1.0.

JSONata is implicit, so omit `QueryLanguage`.

## States and transitions

- Every state requires `Type` and may have `Comment`.
- State names are unique within their machine and at most 80 Unicode characters.
- `Task`, `Pass`, `Wait`, `Parallel`, and `Map` require exactly one of `Next` or `"End": true`.
- `Next` is case-sensitive and targets a state in the same `States` object.
- `Choice` transitions through `Choices[].Next` or `Default`.
- `Succeed` and `Fail` are terminal by type.
- Nested Parallel branches and Map processors are closed transition scopes.
- Multiple states may transition to one state.

## Data and timestamps

- Machine input, state input, results, outputs, and machine output are JSON texts.
- Initial input defaults to `{}`.
- State output becomes the next state's input.
- The successful terminal state's output becomes machine output.
- Timestamps follow RFC 3339: uppercase `T`, and uppercase `Z` when no numeric offset is present.

## JSONata

- Expressions are strings delimited by `{%` and `%}`.
- Evaluate JSONata strings recursively within fields that accept JSONata values.
- Expressions may query, transform, construct JSON, call supported functions, and define expression-local variables/functions.
- Expression-local assignments never update state-machine variables; only `Assign` does.

## Reserved `$states`

The interpreter provides:

```json
{
  "input": "state input",
  "result": "Task, Map, or Parallel result",
  "errorOutput": "error object inside Catch",
  "context": "interpreter context"
}
```

- `$states.input` and `$states.context`: available in JSONata-capable fields.
- `$states.result`: available in top-level `Assign` and `Output` of Task, Map, and Parallel.
- `$states.errorOutput`: available in `Assign` and `Output` inside a matching Catcher.
- Never assign a variable named `states`.
- Reject unavailable `$states` fields during validation when possible.
- ASL leaves most Context Object fields interpreter-defined. Document scheduler extensions separately.

## Processing model

1. A state receives input.
2. `Arguments`, when supported and present, is evaluated; otherwise use state input.
3. Work produces a result.
4. `Assign` evaluates new variables using entry values and, when permitted, `$states.result`.
5. `Output` produces state output. Without it:
   - Task, Map, and Parallel output their result.
   - Other non-Fail states output their input.

`Arguments`:

- Allowed on Task and Parallel.
- Must be JSON or evaluate to JSON.
- May use `$states.input` and `$states.context`, not result or error output.

`Output`:

- Allowed on all states except Fail.
- Must be JSON or evaluate to JSON.
- May use `$states.result` only on Task, Map, and Parallel.

`Assign`:

- Allowed on all states except Succeed and Fail.
- Must be an object mapping variable names to JSON/JSONata values.
- All right sides see values from state entry.
- New values become visible in the next state.
- `Assign` and `Output` evaluate independently in the same state.

## Variables and scopes

- Names are at most 80 Unicode characters and follow Unicode identifier rules.
- A variable belongs to the machine-local scope where a state assigns it.
- Parallel branches and Map iterations have separate inner scopes and values.
- Branches/iterations cannot read variables assigned by siblings.
- Inner scopes may read outer variables if supported, but cannot assign a name existing in an outer scope.
- Inner variables disappear at completion; return data via terminal output.

## Runtime policy

- Treat syntax, type, undefined-value, and invalid-result failures as JSONata evaluation errors.
- Pin and document the JSONata implementation and supported functions.
- Prevent expressions from directly accessing storage, databases, or the network.
- Validate expression placement and expected result types before execution when possible.
