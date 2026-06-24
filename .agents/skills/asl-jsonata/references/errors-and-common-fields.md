# Common fields and error handling

## JSONata field matrix

| Field | Task | Parallel | Map | Pass | Wait | Choice | Succeed | Fail |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `Type` | required | required | required | required | required | required | required | required |
| `Comment` | yes | yes | yes | yes | yes | yes | yes | yes |
| `Output` | yes | yes | yes | yes | yes | yes | yes | no |
| `Assign` | yes | yes | yes | yes | yes | yes | no | no |
| `Next` or `End` | required | required | required | required | required | no | no | no |
| `Arguments` | yes | yes | no | no | no | no | no | no |
| `Retry`, `Catch` | yes | yes | yes | no | no | no | no | no |

## Errors

- Error names are case-sensitive strings.
- ASL-reserved names begin with `States.`.
- Application/resource errors must not use that prefix.
- Unhandled errors fail the containing machine.
- Error output contains at least an `Error` string and may contain `Cause` or interpreter details.

## Retry

Task, Parallel, and Map may contain an ordered `Retry` array.

Each retrier requires non-empty `ErrorEquals`. Optional fields:

- `IntervalSeconds`: positive integer, default `1`.
- `MaxAttempts`: non-negative integer, default `3`; `0` means never retry.
- `BackoffRate`: number at least `1.0`, default `2.0`.
- `MaxDelaySeconds`: positive integer delay cap.
- `JitterStrategy`: interpreter-defined string.

Use the first matching retrier. Apply exponential backoff, cap, then jitter. Retry before Catch. `States.ALL` must appear alone and last.

```json
"Retry": [
  {
    "ErrorEquals": ["States.Timeout"],
    "IntervalSeconds": 2,
    "MaxAttempts": 3,
    "BackoffRate": 2,
    "MaxDelaySeconds": 30,
    "JitterStrategy": "FULL"
  }
]
```

## Catch

Task, Parallel, and Map may contain an ordered `Catch` array.

Each catcher requires:

- `ErrorEquals`: non-empty string array.
- `Next`: target in the containing machine.

Optional:

- `Assign`: may use input, error output, and context.
- `Output`: may use the same values and becomes input to `Next`.

After retries are exhausted, select the first matching catcher. Without catcher `Output`, output defaults to Error Output. `States.ALL` must appear alone and last.

## Built-in errors

- `States.ALL`: wildcard matcher.
- `States.Timeout`: machine/task/heartbeat timeout.
- `States.TaskFailed`: task failure wildcard excluding distinguished timeout behavior.
- `States.Permissions`: insufficient task privileges.
- `States.BranchFailed`: Parallel branch failure.
- `States.NoChoiceMatched`: Choice had no match or Default.
- `States.QueryEvaluationError`: JSONata evaluation failed.

JSONPath-specific errors such as `States.ResultPathMatchFailure`, `States.ParameterPathFailure`, and `States.IntrinsicFailure` must not originate from this dialect.
