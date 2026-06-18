# Agentic Plan: Versioned Natural-Language Workflow System

## 1. Objective

Users describe a task using natural language:

```json
{
  "instruction": "Find customer user@example.com and send their details to accounting"
}
```

The system:

```text
Instruction
-> LLM extracts explicit values
-> LLM generates a workflow graph
-> backend validates and repairs it
-> persist immutable plan versions
-> user reviews diagram, mappings, and risks
-> user approves an exact version
-> existing scheduler Job is created
-> worker executes the graph deterministically
```

The LLM is a planner only. It cannot invoke tools, approve workflows, or directly create runnable jobs.

## 2. Planning API

```text
POST  /app/v1/agentic/plans
GET   /app/v1/agentic/plans/{planId}

GET   /app/v1/agentic/plans/{planId}/versions
GET   /app/v1/agentic/plans/{planId}/versions/{versionNumber}

PATCH /app/v1/agentic/plans/{planId}/versions/{versionNumber}/input
POST  /app/v1/agentic/plans/{planId}/versions/{versionNumber}/approve
POST  /app/v1/agentic/plans/{planId}/versions/{versionNumber}/reject
```

Planning is synchronous in V1.

## 3. Planning DTOs

Initial request contains only the instruction:

```java
public record NaturalLanguageWorkflowRequestDTO(
    @NotBlank String instruction
) {}
```

Supplying missing values:

```java
public record WorkflowPlanInputUpdateDTO(
    @NotNull JsonNode input
) {}
```

Input updates create a new immutable version.

Approval:

```java
public record WorkflowPlanApprovalRequestDTO(
    @NotBlank String workflowHash
) {}
```

Rejection:

```java
public record WorkflowPlanRejectDTO(
    String reason
) {}
```

Plan status:

```java
public enum PlanStatus {
    PLANNING,
    NEEDS_INPUT,
    NEEDS_APPROVAL,
    APPROVED,
    REJECTED,
    FAILED
}
```

Validation severity:

```java
public enum PlanIssueSeverity {
    ERROR,
    WARNING
}
```

Validation issue:

```java
public record PlanValidationIssueDTO(
    PlanIssueSeverity severity,
    String code,
    String path,
    String message
) {}
```

Errors block approval. Warnings remain visible but permit approval.

## 4. Plan Response

`WorkflowPlanResponseDTO` is returned after planning, inspection, input updates, approval, and rejection.

```java
public record WorkflowPlanResponseDTO(
    UUID planId,
    UUID versionId,
    long versionNumber,
    UUID parentVersionId,
    String workflowHash,
    PlanStatus status,
    String summary,
    JsonNode extractedInput,
    JsonNode requiredInputSchema,
    List<String> missingInputs,
    WorkflowJobRequestDTO workflow,
    WorkflowGraphDTO graph,
    List<WorkflowDataMappingDTO> dataMappings,
    WorkflowRiskSummaryDTO riskSummary,
    List<PlanValidationIssueDTO> validationIssues,
    UUID jobId,
    Instant createdAt
) {}
```

Important semantics:

- `workflow` is the authoritative executable definition.
- `graph`, `dataMappings`, and `riskSummary` are backend-derived views.
- `jobId` remains `null` until that exact version is approved.
- Immutable versions do not need `updatedAt`.

## 5. Workflow DTOs

Extend the existing `WorkflowJobRequestDTO`:

```java
public record WorkflowJobRequestDTO(
    JobPriority jobPriority,
    String cronExpression,
    Integer maxAttempts,
    String idempotencyKey,
    JsonNode sharedWorkflowInput,
    String timezone,
    String entryStepKey,
    List<JobStepRequestDTO> steps,
    List<WorkflowTransitionDTO> transitions
) {}
```

Step definition:

```java
public record JobStepRequestDTO(
    int stepOrder,
    String stepKey,
    JobType stepType,
    JsonNode payload,
    String inputMappingExpression
) {}
```

Meaning:

```text
payload                = static base input
inputMappingExpression = JSONata transformation
resolvedInput          = actual handler input
```

Transition:

```java
public record WorkflowTransitionDTO(
    String sourceStepKey,
    String targetStepKey,
    String condition,
    boolean defaultTransition
) {}
```

Transition rules:

- `condition` is a JSONata boolean expression.
- Conditional transitions are evaluated in list order.
- At most one default transition exists per source.
- `targetStepKey = null` completes the workflow successfully.
- A step with no outgoing transitions also completes successfully.
- No matching condition and no default causes failure.

Compatibility defaults:

```text
sharedWorkflowInput = {}
timezone = UTC
stepKey = "step" + stepOrder
inputMappingExpression = null
```

Existing linear workflows without transitions are converted into sequential transitions.

## 6. Diagram DTOs

```java
public record WorkflowGraphDTO(
    String entryNodeKey,
    List<WorkflowGraphNodeDTO> nodes,
    List<WorkflowGraphEdgeDTO> edges
) {}
```

```java
public record WorkflowGraphNodeDTO(
    String key,
    String label,
    JobType stepType,
    String description,
    String serverId,
    String toolName,
    McpTrustLevel trustLevel,
    boolean entryNode
) {}
```

```java
public enum WorkflowGraphEdgeType {
    CONDITIONAL,
    DEFAULT,
    COMPLETION
}
```

```java
public record WorkflowGraphEdgeDTO(
    String sourceKey,
    String targetKey,
    String label,
    String conditionExpression,
    WorkflowGraphEdgeType type
) {}
```

Use `__END__` as the UI-only target for successful completion.

The backend derives the graph from validated steps and transitions. The LLM never supplies an independent graph.

## 7. Data Mapping And Risk DTOs

```java
public record WorkflowDataMappingDTO(
    String targetStepKey,
    String expression,
    List<String> referencedStepKeys,
    boolean usesSharedWorkflowInput
) {}
```

```java
public record WorkflowRiskSummaryDTO(
    McpTrustLevel highestTrustLevel,
    boolean sendsExternalData,
    boolean hasDestructiveActions,
    List<String> warnings
) {}
```

Secrets, authentication references, and sensitive headers must never appear in graph or risk DTOs.

## 8. Versioned Persistence

Stable parent:

```text
agentic_workflow_plans
----------------------
id UUID
instruction TEXT
created_at TIMESTAMP
updated_at TIMESTAMP
```

Immutable versions:

```text
agentic_workflow_plan_versions
------------------------------
id UUID
plan_id UUID
version_number BIGINT
parent_version_id UUID nullable
status VARCHAR
extracted_input JSONB
supplied_input JSONB
required_input_schema JSONB
generated_workflow JSONB
validation_issues JSONB
summary TEXT
graph JSONB
data_mappings JSONB
risk_summary JSONB
workflow_hash VARCHAR
timezone VARCHAR
planner_provider VARCHAR
planner_model VARCHAR
prompt_version VARCHAR
repair_attempt INTEGER
job_id UUID nullable
approved_at TIMESTAMP nullable
rejected_at TIMESTAMP nullable
rejection_reason TEXT nullable
created_at TIMESTAMP
```

Constraints:

```text
UNIQUE(plan_id, version_number)
UNIQUE(plan_id, workflow_hash)
```

A new version is created whenever workflow content changes:

- Initial LLM draft
- Automatic repair
- Missing input supplied
- User-requested revision
- Manual edit
- Re-planning against changed capabilities

Versions remain inspectable permanently.

Any valid historical version may be approved. Different approved versions create different jobs.

## 9. Scheduler Persistence

Add to `jobs`:

```text
shared_workflow_input JSONB nullable
timezone VARCHAR not null default 'UTC'
entry_step_key VARCHAR nullable
current_step_key VARCHAR nullable
last_completed_step_key VARCHAR nullable
final_output JSONB nullable
final_output_ref JSONB nullable
```

Add to `job_steps`:

```text
step_key VARCHAR
input_mapping_expression TEXT nullable
```

Create transitions:

```text
workflow_transitions
--------------------
id UUID
job_id UUID
source_job_step_id UUID
target_job_step_id UUID nullable
condition_expression TEXT nullable
default_transition BOOLEAN
evaluation_order INTEGER
created_at TIMESTAMP
```

Create decision history:

```text
workflow_transition_decisions
-----------------------------
id UUID
execution_log_id UUID
source_step_execution_id UUID
source_step_key VARCHAR
selected_target_step_key VARCHAR nullable
condition_expression TEXT nullable
condition_result BOOLEAN nullable
created_at TIMESTAMP
```

Constraints:

- `(job_id, step_key)` is unique.
- Step keys match `[A-Za-z][A-Za-z0-9_]*`.
- At most one default transition exists per source.
- Transition list order is persisted as `evaluation_order`.

## 10. Planning Flow

1. Persist the parent plan.
2. Build a planner capability catalog from:
   - Enabled MCP servers and tools
   - Tool descriptions
   - Input/output schemas
   - Server trust levels
   - System capabilities for email, webhook, and cleanup
3. Call `WorkflowPlannerClient`.
4. Extract only values explicitly present in the instruction.
5. Generate `requiredInputSchema`.
6. Compare extracted and supplied values against that schema.
7. Generate `WorkflowJobRequestDTO`.
8. Validate the workflow.
9. Persist every generated or repaired draft as an immutable version.
10. Return:
    - `NEEDS_INPUT` when input is missing
    - `NEEDS_APPROVAL` when valid
    - `FAILED` after exhausted repairs

The LLM must never invent missing values.

Input updates deep-merge with previously extracted and supplied values, then create a new version.

Default timezone is `UTC` and must be visible before approval.

## 11. Planner Abstraction

```java
public interface WorkflowPlannerClient {
    WorkflowPlannerResult plan(WorkflowPlannerContext context);
}
```

```java
public record WorkflowPlannerContext(
    String instruction,
    JsonNode knownInput,
    List<PlannerCapabilityDTO> capabilities,
    List<PlanValidationIssueDTO> previousIssues,
    int repairAttempt
) {}
```

```java
public record WorkflowPlannerResult(
    JsonNode extractedInput,
    JsonNode requiredInputSchema,
    String timezone,
    WorkflowJobRequestDTO workflow
) {}
```

Requirements:

- Provider-neutral interface
- Structured JSON response
- Versioned system prompt
- Maximum two automatic repair attempts
- Provider, model, and prompt version persisted
- No tool execution during planning

## 12. Backend Validation

Validate before approval:

- Required input against JSON Schema
- Positive and unique step order
- Unique valid step keys
- Entry step exists
- Transition sources and targets exist
- Every step is reachable from entry
- No cycles in V1
- Every reachable route can terminate
- At most one default transition per source
- Conditional transitions contain conditions
- Default transitions contain no condition
- Supported `JobType`
- MCP server/tool exists and is enabled
- MCP server is not `UNTRUSTED`
- Tool arguments match known schemas
- Cron expression and timezone are valid
- JSONata expressions compile
- Mappings reference only shared input or available previous outputs
- Conditions return boolean
- Known schema mismatches are errors
- Unknown output schemas create warnings

Validation issues use stable codes and JSON Pointer paths.

## 13. Exact Version Approval

Approval URL identifies the version. Request body confirms its hash.

```text
POST /app/v1/agentic/plans/{planId}/versions/{versionNumber}/approve
```

Approval flow:

1. Load and lock the requested version.
2. Compare supplied and persisted `workflowHash`.
3. Require an approvable status.
4. Revalidate against the current MCP registry and trust policy.
5. Generate the scheduler idempotency key from plan and version IDs.
6. Call existing `JobService.submitWorkflowJob()`.
7. Store `APPROVED`, `jobId`, and `approvedAt`.
8. Return the approved version response.

Repeated approval of the same version returns its existing `jobId`.

A changed hash returns `PLAN_VERSION_CONFLICT`.

The parent plan may continue creating newer versions after an earlier version is approved.

## 14. JSONata Runtime

JSONata never accesses PostgreSQL or MinIO directly.

Java builds this in-memory context:

```json
{
  "workflow": {
    "sharedWorkflowInput": {}
  },
  "payload": {},
  "steps": {
    "previousStep": {
      "output": {},
      "outputRef": null
    }
  }
}
```

Example input mapping:

```jsonata
$merge([
  payload,
  {
    "arguments": {
      "email": workflow.sharedWorkflowInput.email
    }
  }
])
```

Example transition condition:

```jsonata
steps.checkInvoice.output.amount < 1000
```

Use IBM JSONata4Java `2.6.3` with evaluation timeout, nesting limits, and output-size limits.

## 15. Graph Execution

Load:

```java
Map<String, JobStep> stepsByKey;
Map<String, List<WorkflowTransition>> outgoingTransitions;
```

Execution:

```text
currentStepKey = entryStepKey
-> resolve current step input
-> execute current step
-> persist output
-> evaluate outgoing conditions in order
-> choose first matching transition
-> otherwise choose default
-> persist decision
-> update currentStepKey
-> execute selected target directly
```

Unselected branches are not scanned or executed.

Rules:

- Non-null target continues execution.
- Null target completes successfully.
- No outgoing transitions completes successfully.
- No match/default fails the workflow.
- Non-boolean condition fails the workflow.
- V1 selects only one transition.
- V1 is sequential, conditional, resumable, and acyclic.

## 16. Durable Resume

On failure:

```text
Step 1 SUCCESS
Step 2 FAILED
-> job retry
-> reuse Step 1 output
-> reuse persisted transition decisions
-> resume Step 2
```

Successful steps are not repeated within the same workflow execution chain.

This prevents duplicate emails, webhooks, and write-capable MCP calls.

Persisted fields and transition decisions determine the exact resume point.

## 17. Step Results

```java
public record StepResult(JsonNode output) {
    public static StepResult empty() {
        return new StepResult(null);
    }
}
```

Handlers return normalized output:

- `MCP_TOOL`: structured MCP result
- `WEBHOOK`: status and response body
- `CLEANUP`: deleted-row count
- `SEND_EMAIL`: empty initially

The terminal step output becomes:

```text
Job.final_output
or
Job.final_output_ref
```

## 18. Large Data And Artifacts

Small JSON:

```text
StepExecution.output
```

Large JSON:

```text
MinIO object
StepExecution.outputRef
```

Before JSONata evaluation:

```text
size <= mapping-load limit
-> Java downloads and parses JSON
-> expose output and outputRef

size > mapping-load limit
-> expose only outputRef
```

The expression must reference `steps.key.outputRef` when content is too large.

Binary artifacts use:

```java
public record ArtifactRef(
    String provider,
    String bucket,
    String key,
    String contentType,
    long sizeBytes,
    String checksum,
    String fileName
) {}
```

JSONata maps artifact metadata and references, never binary bytes.

## 19. Logical Conditions

- Initial request contains only `instruction`.
- Missing values cannot be invented.
- Every valid version requires explicit approval.
- The exact displayed version and hash are approved.
- Only registered, enabled capabilities may be selected.
- Runtime payload validation occurs before external execution.
- Mapping or transition failures prevent downstream actions.
- Retries do not invoke the LLM.
- Runtime output is not returned to the planner in V1.
- Graph, mappings, risks, and workflow hash are backend-derived.
- Existing job APIs remain compatible.

## 20. Test Plan

Planning and versions:

- Initial version creation
- Version created for every workflow change
- Parent and version history retrieval
- Input update creates child version
- Repair attempts persist separate versions
- Historical versions remain inspectable
- Multiple versions can be approved independently

Validation:

- Missing input
- Invalid tools and trust levels
- Invalid cron and timezone
- Invalid mappings and conditions
- Missing entry/source/target
- Cycles and unreachable steps
- Non-terminating paths
- Stable issue codes and paths

Approval:

- Exact version and hash approval
- Hash conflict
- Idempotent repeated approval
- Historical version approval
- Registry change invalidating approval
- Different versions creating separate jobs

Execution:

- Linear workflow compatibility
- Conditional branch selection
- Early completion with null target
- Branch convergence
- No-match failure
- Durable resume without repeating completed steps
- Persisted decision reuse
- Terminal output propagation

Storage:

- Inline input/output
- MinIO-backed JSON mapping
- Mapping-load limit
- Artifact references
- Oversized JSONata result rejection

Regression:

- Existing jobs, MCP execution, Kafka, Redis, retries, DLQ, logs, metrics, and endpoints continue working.

## 21. Defaults

- Planning: synchronous
- Default timezone: UTC
- Planner repair attempts: two
- Mapping and conditions: JSONata
- Graph execution: sequential and acyclic in V1
- Plan versions: immutable
- Any valid historical version may be approved
- Approval creates and schedules the job immediately
