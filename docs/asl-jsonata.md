# ASL with JSONata

This scheduler uses the [Amazon States Language (ASL) 1.0](https://states-language.net/spec.html) to define state machines. However, it replaces the standard JSONPath expressions with **JSONata** for richer data mapping, querying, and transformations. 

This document explains the differences between standard ASL and our JSONata dialect.

## Core Differences

1. **No JSONPath**: Fields like `InputPath`, `ResultPath`, `OutputPath`, `Parameters`, and `ResultSelector` are **not supported**. They have been entirely replaced by `Arguments`, `Assign`, and `Output`.
2. **Explicit Expressions**: JSONata expressions must be enclosed in `{% ... %}`.
3. **No Intrinsic Functions**: `States.Format`, `States.Array`, etc., are unnecessary. You can construct arrays and format strings directly using native JSONata syntax.
4. **Context Object & Reserved Variables**: In place of `$$`, you interact with a reserved `$states` variable containing context information.

---

## State Variables (`$states`)

Within any expression `{% ... %}`, the interpreter exposes the `$states` variable:

```json
{
  "input": "The input provided to the current state",
  "result": "The result returned by a Task, Parallel, or Map",
  "errorOutput": "The error object caught within a Catch block",
  "context": "Interpreter context metadata"
}
```

- `$states.input`: Available everywhere.
- `$states.result`: Available only in `Assign` and `Output` fields of a `Task`, `Parallel`, or `Map` state.
- `$states.errorOutput`: Available only inside a `Catch` block's `Assign` or `Output` fields.

---

## Data Flow: Arguments, Assign, and Output

The standard ASL data flow mechanism (`Parameters` -> `ResultSelector` -> `ResultPath` -> `OutputPath`) is consolidated into three JSONata-aware fields:

### 1. `Arguments` (Replaces `Parameters`)
Allowed on `Task` and `Parallel`. Prepares the input specifically for the worker or child branch. You can use `$states.input` and `$states.context` here.

```json
"Arguments": {
  "customerId": "{% $states.input.userId %}",
  "timestamp": "{% $states.context.startTime %}"
}
```

### 2. `Assign` (Replaces `ResultPath`)
Allowed on all states except `Succeed` and `Fail`. Allows you to declare variables that carry over into subsequent states. In `Task`, `Map`, or `Parallel`, you can read `$states.result` here.

```json
"Assign": {
  "customerData": "{% $states.result %}",
  "totalProcessed": "{% $states.input.currentTotal + $states.result.count %}"
}
```

Variables assigned this way become accessible as `$customerData` and `$totalProcessed` in the next state.

### 3. `Output` (Replaces `OutputPath` & `ResultSelector`)
Allowed on all states except `Fail`. Determines the exact JSON that is passed as input to the `Next` state. If omitted, the state passes its `input` along (or its `result` if it is a Task/Parallel/Map).

```json
"Output": {
  "status": "COMPLETED",
  "data": "{% $states.result.items %}"
}
```

---

## Example: A Full JSONata Task State

```json
"FetchCustomer": {
  "Type": "Task",
  "Resource": "mcp://crm/get_customer",
  "Comment": "Fetch customer details and save them to a variable",
  
  // 1. Prepare data for the worker
  "Arguments": {
    "id": "{% $states.input.userId %}"
  },
  
  // 2. Worker executes and returns $states.result
  
  // 3. Save specific pieces to variables for later
  "Assign": {
    "customerEmail": "{% $states.result.email %}",
    "customerTier": "{% $states.result.tier %}"
  },
  
  // 4. Shape the input for the next state
  "Output": {
    "userId": "{% $states.input.userId %}",
    "readyForProcessing": true
  },
  "Next": "ProcessCustomer"
}
```

---

## Choice State

The `Choice` state uses JSONata for its `Condition` fields. The condition must evaluate to a boolean. 

```json
"CheckTier": {
  "Type": "Choice",
  "Choices": [
    {
      "Condition": "{% $customerTier = 'GOLD' or $states.input.override = true %}",
      "Next": "GoldProcessing"
    }
  ],
  "Default": "StandardProcessing"
}
```
*Note: `$customerTier` was defined in a previous state's `Assign` block.*

---

## Wait State

The `Wait` state natively accepts JSONata to dynamically compute timestamps or wait durations.

```json
"WaitUntilReady": {
  "Type": "Wait",
  "Seconds": "{% $states.input.delaySeconds %}",
  "Next": "Proceed"
}
```

---

## Error Handling (`Retry` and `Catch`)

The `Retry` and `Catch` blocks behave exactly as they do in the ASL specification, but `Catch` blocks can also use `Assign` and `Output` to shape the fallback payload using `$states.errorOutput`.

```json
"Catch": [
  {
    "ErrorEquals": ["States.TaskFailed"],
    "Next": "HandleFailure",
    "Assign": {
      "lastError": "{% $states.errorOutput.Error %}"
    },
    "Output": {
      "recovered": false,
      "originalInput": "{% $states.input %}"
    }
  }
]
```
