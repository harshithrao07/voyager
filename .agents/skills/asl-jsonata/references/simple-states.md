# Simple states

## Pass

- `Type`: `"Pass"`.
- May use `Comment`, `Assign`, and `Output`.
- Requires exactly one of `Next` or `"End": true`.
- Without `Output`, output equals input.

## Task

- `Type`: `"Task"`.
- Requires URI `Resource`.
- Requires exactly one of `Next` or `"End": true`.
- May use `Comment`, `Assign`, `Arguments`, `Output`, `Retry`, and `Catch`.
- `TimeoutSeconds`: positive integer or JSONata expression producing one.
- `HeartbeatSeconds`: positive integer or JSONata expression producing one.
- Arguments default to state input; output defaults to resource result.
- Resource integrations must preserve ASL-visible timeout and failure semantics.

```json
"CallTool": {
  "Type": "Task",
  "Resource": "mcp://crm/get_customer",
  "Arguments": {
    "customerId": "{% $states.input.customerId %}"
  },
  "TimeoutSeconds": 30,
  "Output": {
    "customer": "{% $states.result %}"
  },
  "Next": "CheckCustomer"
}
```

## Choice

- `Type`: `"Choice"`.
- Requires non-empty `Choices`.
- Each JSONata rule requires boolean `Condition` and valid `Next`.
- May have `Default`.
- May use state-level `Comment`, `Assign`, and `Output`.
- A rule may have `Assign` and `Output`, applied only when selected.
- Evaluate in array order and choose the first true rule.
- If none match, use Default or fail with `States.NoChoiceMatched`.
- A non-boolean/evaluation failure is an error, not false.
- Choice has no state-level `Next` or `End`.

```json
"Route": {
  "Type": "Choice",
  "Choices": [
    {
      "Condition": "{% $states.input.total > 1000 %}",
      "Next": "ManualReview"
    }
  ],
  "Default": "Approve"
}
```

## Wait

- `Type`: `"Wait"`.
- Requires exactly one of:
  - `Seconds`: non-negative integer or JSONata expression producing one.
  - `Timestamp`: RFC 3339 string or JSONata expression producing one.
- Requires exactly one of `Next` or `"End": true`.
- May use `Comment`, `Assign`, and `Output`.
- Without Output, output equals input.
- Implement durably by persisting wake time; never hold a worker thread while waiting.

## Succeed

- `Type`: `"Succeed"`.
- Successfully terminates the machine, Parallel branch, or Map iteration.
- May use `Comment` and `Output`.
- Without Output, output equals input.
- Has no `Next`, `End`, or `Assign`.

## Fail

- `Type`: `"Fail"`.
- Immediately fails the containing machine.
- May use `Comment`.
- `Error`: optional string or JSONata expression producing string.
- `Cause`: optional string or JSONata expression producing string.
- Has no `Next`, `End`, `Output`, or `Assign`.
