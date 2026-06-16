# Agentic MCP Scheduler Roadmap

This project should evolve from a distributed job scheduler into:

> Temporal-style durable scheduling + MCP tool execution + AI planning.

The goal is not to build another chatbot. The goal is to build an agentic scheduler that can discover, schedule, and durably execute tools exposed by MCP servers.

In practical terms, the scheduler becomes an MCP client runtime:

```text
User goal
  -> optional AI planner
  -> validated job or workflow definition
  -> PostgreSQL source of truth
  -> Kafka worker execution
  -> MCP tool calls
  -> retries, DLQ, history, metrics
```

The current scheduler is already a strong foundation: PostgreSQL stores canonical job state, Kafka handles async delivery, Redis coordinates distributed workers, and watchdogs recover stuck jobs. MCP should be added as a first-class execution target on top of that foundation.

---

## Positioning

A concise project description:

> An agentic distributed scheduler that can register MCP servers, discover their tools, and run those tools as durable scheduled jobs and workflows.

This is stronger than "natural language scheduling" by itself. Natural language is the interface layer. MCP execution is the real platform capability.

Example user goal:

```text
Every Monday at 9 AM, summarize my GitHub issues and email me.
```

Target workflow:

```json
{
  "cronExpression": "0 0 9 * * MON",
  "jobType": "MCP_WORKFLOW",
  "steps": [
    {
      "id": "fetch_issues",
      "type": "MCP_TOOL",
      "serverId": "github",
      "toolName": "list_issues",
      "arguments": {
        "assignee": "me"
      }
    },
    {
      "id": "summarize",
      "type": "LLM",
      "inputFrom": "fetch_issues"
    },
    {
      "id": "send_email",
      "type": "LOCAL_TOOL",
      "toolName": "send_email",
      "inputFrom": "summarize"
    }
  ]
}
```

---

## Phase 0: Existing Distributed Scheduler

Status: implemented.

Current foundation:

- Spring Boot REST API
- PostgreSQL source of truth
- Kafka dispatch and worker execution
- Redis locks and idempotency markers
- Retry and DLQ support
- Cron jobs
- Multi-replica support
- Watchdog recovery for stuck jobs
- Prometheus metrics
- Testcontainers integration tests
- Load-tested benchmark harness

This phase matters because MCP tools should inherit the same durability guarantees as existing `WEBHOOK`, `SEND_EMAIL`, and `CLEANUP` jobs.

---

## Phase 1: MCP Server Registry

Goal: register MCP servers and persist their available tools.

The scheduler needs to know which MCP servers exist, how to connect to them, which tools they expose, and what argument schemas those tools require.

Suggested APIs:

```http
POST /app/v1/mcp/servers
GET /app/v1/mcp/servers
GET /app/v1/mcp/servers/{serverId}
POST /app/v1/mcp/servers/{serverId}/sync
GET /app/v1/mcp/tools
```

Suggested server model:

```json
{
  "id": "github",
  "name": "GitHub MCP",
  "transport": "HTTP",
  "endpoint": "http://localhost:3001/mcp",
  "authType": "BEARER_TOKEN",
  "enabled": true,
  "trustLevel": "READ_ONLY",
  "lastToolSyncAt": "2026-06-15T12:00:00Z"
}
```

Suggested tool model:

```json
{
  "serverId": "github",
  "name": "list_issues",
  "description": "List GitHub issues visible to the authenticated user",
  "inputSchema": {
    "type": "object",
    "properties": {
      "assignee": {
        "type": "string"
      }
    }
  },
  "outputSchema": null,
  "enabled": true,
  "requiresApproval": false
}
```

Important details:

- Tool discovery should call MCP `tools/list`.
- Store tool schemas so jobs can be validated before execution.
- Track sync status and connection failures.
- Do not let AI invent tools that are not in the registry.

---

## Phase 2: MCP Tool Execution

Goal: call a tool on a registered MCP server from the scheduler backend.

Suggested API:

```http
POST /app/v1/mcp/tools/call
```

```json
{
  "serverId": "github",
  "toolName": "list_issues",
  "arguments": {
    "assignee": "me"
  }
}
```

Expected behavior:

- Look up the server and tool.
- Validate arguments against the stored MCP input schema.
- Apply permission and approval policy.
- Call MCP `tools/call`.
- Normalize the result into a scheduler-owned response shape.
- Store audit information for the call.

Result shape:

```json
{
  "serverId": "github",
  "toolName": "list_issues",
  "status": "SUCCESS",
  "content": [
    {
      "type": "text",
      "text": "..."
    }
  ],
  "structuredContent": {}
}
```

Failure classes should be separated:

- Protocol errors
- Tool execution errors
- Authentication errors
- Authorization errors
- Validation errors
- Network timeouts
- Rate limits

These should not all retry the same way.

---

## Phase 3: Scheduled MCP Tool Jobs

Goal: make MCP tool calls schedulable.

Add a job type such as:

```java
MCP_TOOL
```

Example job request:

```json
{
  "jobType": "MCP_TOOL",
  "jobPriority": "MEDIUM",
  "cronExpression": "0 0 9 * * MON",
  "payload": {
    "serverId": "github",
    "toolName": "list_issues",
    "arguments": {
      "assignee": "me"
    }
  },
  "maxAttempts": 3,
  "idempotencyKey": "weekly-github-issues"
}
```

At this point, MCP tools inherit existing scheduler behavior:

- Immediate execution
- Cron scheduling
- Kafka dispatch
- Worker concurrency
- Redis locks
- Retries
- DLQ
- Execution logs
- Metrics

This is the first major milestone where the project becomes a true MCP-enabled scheduler.

---

## Phase 4: Durable MCP Workflows

Goal: support multi-step workflows where step outputs feed later steps.

Add a job type such as:

```java
MCP_WORKFLOW
```

Example workflow:

```json
{
  "steps": [
    {
      "id": "fetch_issues",
      "type": "MCP_TOOL",
      "serverId": "github",
      "toolName": "list_issues",
      "arguments": {
        "assignee": "me"
      }
    },
    {
      "id": "summarize",
      "type": "LLM",
      "inputFrom": "fetch_issues"
    },
    {
      "id": "send_email",
      "type": "LOCAL_TOOL",
      "toolName": "send_email",
      "arguments": {
        "to": "user@example.com"
      },
      "inputFrom": "summarize"
    }
  ]
}
```

Workflow state should be persisted at step level:

- Step ID
- Step type
- Status
- Attempt count
- Input
- Output
- Error
- Started time
- Completed time
- Dependency information

Avoid storing workflows only as a large opaque JSON blob. That will make retries, inspection, partial recovery, and debugging painful.

---

## Phase 5: Natural Language Planning

Goal: let users describe scheduled workflows in plain English.

Example:

```text
Every Monday at 9 AM, summarize issues assigned to me in GitHub and email the result.
```

The LLM should produce a structured workflow definition, not execute tools directly.

Planning flow:

```text
User prompt
  -> fetch available MCP tools from registry
  -> LLM creates workflow draft
  -> backend validates cron, tools, schemas, permissions
  -> optional user confirmation
  -> durable workflow is saved
```

Suggested API:

```http
POST /app/v1/ai/workflows/plan
```

```json
{
  "prompt": "Every Monday at 9 AM, summarize my GitHub issues and email me."
}
```

Possible response:

```json
{
  "requiresConfirmation": true,
  "workflow": {
    "cronExpression": "0 0 9 * * MON",
    "steps": []
  },
  "warnings": [
    "The workflow calls an external GitHub MCP server.",
    "The send_email step will send data outside the system."
  ]
}
```

Important rule:

The LLM is a planner, not the authority. The backend must validate everything.

---

## Phase 6: Human Approval and Tool Safety

Goal: prevent unsafe automated tool execution.

MCP tools can read data, write data, or perform destructive actions. The scheduler must model that risk explicitly.

Suggested trust levels:

| Trust Level | Meaning | Default Behavior |
|---|---|---|
| `READ_ONLY` | Reads data only | Can run automatically |
| `WRITE` | Creates or updates external data | Requires approval |
| `DESTRUCTIVE` | Deletes, cancels, merges, pays, deploys, or changes access | Requires explicit approval |
| `UNTRUSTED` | Unknown or newly registered server | Disabled until reviewed |

Safety features:

- Server allowlist
- Tool allowlist
- Per-tool approval policy
- User-visible proposed arguments
- Audit log for every tool call
- Timeouts for every tool call
- Output sanitization before passing tool results to an LLM
- Rate limits per server and tool

This phase is important because scheduled jobs run later, possibly without a human watching.

---

## Phase 7: Execution History RAG

Goal: use historical executions to debug failures and answer operational questions.

Store searchable history for:

- MCP tool failures
- Protocol errors
- Tool execution errors
- Retry history
- Workflow step outputs
- Incident notes
- Exception stack traces

Example user question:

```text
Have we seen this GitHub MCP timeout before?
```

System flow:

```text
Current failure
  -> embed failure text
  -> vector search historical failures
  -> return similar incidents
  -> suggest likely fixes
```

PostgreSQL with pgvector is a natural fit because PostgreSQL is already the source of truth.

---

## Phase 8: Observability and Kubernetes

Goal: make the MCP scheduler operable in production-like environments.

MCP-specific metrics:

```text
mcp_servers_registered_total
mcp_tools_discovered_total
mcp_tool_calls_total
mcp_tool_call_latency_seconds
mcp_tool_call_failures_total
mcp_tool_schema_validation_failures_total
workflow_steps_executed_total
workflow_step_latency_seconds
workflow_step_failures_total
llm_plans_generated_total
llm_plan_validation_failures_total
```

Operational additions:

- OpenTelemetry traces across workflow steps
- Prometheus dashboards for MCP calls and workflows
- Kubernetes deployments for scheduler API and workers
- ConfigMaps for non-secret configuration
- Secrets for MCP credentials and LLM API keys
- HPA for worker replicas
- Ingress for API access

---

## Main Design Holes To Avoid

### Starting With Natural Language Too Early

If AI planning comes before MCP registry and MCP execution, the project becomes a prompt demo. Build tool discovery and execution first, then let AI create workflows from those real capabilities.

### Treating MCP Calls As Simple HTTP Calls

MCP has tool schemas, protocol errors, tool execution errors, result content types, and structured outputs. The runtime should model those explicitly instead of flattening everything into a string.

### No Persisted Step State

Multi-step workflows need step-level status, attempts, outputs, errors, and dependencies. A single job payload is enough for one tool call, but not enough for durable workflow orchestration.

### No Permission Model

MCP tools can read data or take external actions. The scheduler needs trust levels, server allowlists, tool allowlists, and approval rules.

### No Output Size Strategy

MCP tools can return large text, structured objects, images, or resource references. Store outputs carefully. Large outputs may need truncation, summaries, object storage, or references.

### No Timeout And Retry Classification

Protocol errors, auth errors, validation errors, rate limits, and network failures should not all follow the same retry policy.

### LLM-Hallucinated Tools

AI planning must only choose from discovered tools in the registry. Every argument must validate against the MCP tool input schema before a job or workflow is saved.

### Passing Untrusted Tool Output Directly To An LLM

Tool output can contain prompt injection. Sanitize and label tool outputs before using them as LLM context.

### Letting Scheduled Jobs Bypass Approval

A workflow approved once can run many times. Approval policy should distinguish between one-time execution and recurring autonomous execution.

---

## Recommended Build Order

Build the project in this order:

1. MCP server registry
2. MCP tool discovery
3. MCP tool execution API
4. `MCP_TOOL` scheduled job type
5. Step-level workflow persistence
6. `MCP_WORKFLOW` execution
7. Natural language workflow planning
8. Human approval policies
9. Execution history RAG
10. MCP metrics, tracing, and Kubernetes deployment

Stop after step 6 for a very strong MVP. Steps 7 onward make it agentic and production-polished.

---

## Resume Statement

> Built an agentic distributed scheduler that acts as an MCP client runtime, discovering tools from external MCP servers and executing them as durable scheduled jobs and workflows with PostgreSQL-backed state, Kafka workers, Redis coordination, retries, DLQ recovery, and observability.

