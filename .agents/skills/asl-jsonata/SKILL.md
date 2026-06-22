---
name: asl-jsonata
description: Design, explain, review, validate, or implement Amazon States Language state machines for this scheduler using JSONata exclusively. Use for workflow JSON, ASL state semantics, transitions, data transformation, variables, retries, catches, Task resources, Choice, Wait, Pass, Succeed, Fail, Parallel, Map, nested state machines, or ASL interpreter and persistence design. Reject JSONPath-only syntax and fields.
---

# ASL JSONata

Use Amazon States Language 1.0 semantics as the source of truth, restricted to the project's JSONata-only dialect.

## Project dialect

- Treat JSONata as implicit for every machine and nested machine.
- Omit `QueryLanguage` from generated definitions. For compatibility input, accept only `"JSONata"`; never emit it.
- Reject JSONPath fields, reference paths, payload templates, and `States.*` intrinsic functions.
- Preserve ASL field names and behavior unless the project explicitly documents a deviation.
- Permit project-defined URI schemes in `Resource`, such as `mcp://`, `scheduler://`, or `webhook://`.
- Treat unsupported runtime features as validation errors; never silently change their semantics.

## Workflow

1. Determine whether the task is authoring, validation, explanation, or interpreter design.
2. Read [machine-and-jsonata.md](references/machine-and-jsonata.md) for structure, transitions, expressions, data, variables, and scope.
3. Read [errors-and-common-fields.md](references/errors-and-common-fields.md) for `Assign`, `Arguments`, `Output`, `Retry`, and `Catch`.
4. Read [simple-states.md](references/simple-states.md) for `Pass`, `Task`, `Choice`, `Wait`, `Succeed`, and `Fail`.
5. Read [compound-states.md](references/compound-states.md) for `Parallel` and `Map`.
6. Apply [validation.md](references/validation.md) before presenting or accepting a definition.
7. Distinguish ASL validity, validity in this JSONata-only dialect, and current scheduler runtime support.

## Authoring rules

- Produce strict JSON unless the user asks for DTOs, schemas, or pseudocode.
- Use exactly one of `Next` or `"End": true` on states that require it.
- Do not add `Next` or `End` to `Choice`, `Succeed`, or `Fail`.
- Use `{% ... %}` for JSONata expressions.
- Use `$states.input`, `$states.result`, `$states.errorOutput`, and `$states.context` only where available.
- Keep cron, timezone, priority, ownership, and workflow revision outside the ASL machine unless explicitly requested.
- Do not invent defaults. Omitted top-level `TimeoutSeconds` means no ASL-defined machine timeout.

## Source

This operational guide is derived from the Amazon States Language specification dated November 22, 2024:
https://states-language.net/spec.html

For exact conformance, unusual edge cases, or future changes, verify against the source before changing project behavior.
