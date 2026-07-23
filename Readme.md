# Distributed ASL Workflow Engine

A distributed workflow engine that executes JSON-defined state machines based on the Amazon States Language (ASL). It replaces JSONPath with JSONata for expressive data transformation and conditions.

Workflows are accepted through a Spring Boot REST API, stored durably in PostgreSQL, and executed asynchronously by a frontier-driven engine with background watchdog services.

## Table of Contents

- [Getting Started](#getting-started)
- [Documentation](#documentation)
- [Workflow Engine Architecture (ASL)](#workflow-engine-architecture-asl)
- [Testing and CI](#testing-and-ci)

## Getting Started

Run the full scheduler stack locally with Docker Compose:

```bash
git clone https://github.com/harshithrao07/distributed-scheduler.git scheduler
cd scheduler
openssl rand -base64 32
```

Put the generated value in the git-ignored `.env` file:

```dotenv
SCHEDULER_SECRETS_MASTER_KEY=<generated-base64-value>
```

Then start the stack:

```bash
docker compose up --build -d
```

Compose starts the frontend only after PostgreSQL, Redis, Kafka, Judge0's database and API, a live
Judge0 execution worker, the backend readiness probe, and Prometheus are all healthy. The UI is
therefore not published while the default stack is still warming up. Optional profile services such
as `demo-mcp` do not block the default frontend startup.

Voyager refuses to start without this deployment-owned 32-byte key. It encrypts AI-provider and MCP
credentials stored in PostgreSQL; keep it backed up and never commit `.env`.

Then run the test suite:

```bash
mvn test
```

Useful local URLs:

- Frontend UI: `http://localhost:3000`
- API base URL: `http://localhost:8081/app/v1`
- Swagger UI: `http://localhost:8081/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8081/v3/api-docs`
- Health: `http://localhost:8081/actuator/health`
- Prometheus metrics: `http://localhost:8081/actuator/prometheus`

Local services:

| Service | Port | Purpose |
|---|---:|---|
| Frontend | `3000` | React UI served by Nginx, proxies API and WebSocket traffic |
| App | `8081` | Spring Boot REST API, schedulers, workers |
| PostgreSQL | `5432` | Durable job and execution log storage |
| Kafka | `9092` | Job queue, high-priority queue, and DLQ |
| Redis | `6379` | Worker locks and idempotency markers |
| Prometheus | `9090` | Scrapes application and scheduler metrics |

Stop the stack with `docker compose down`. Use `docker compose down -v` when you want a clean database, Kafka log, and Redis state.

---

## Documentation

Open **Docs** in the Voyager sidebar at `http://localhost:3000/docs`. The Markdown files below are
the source of truth and are bundled into that in-app documentation UI during the frontend build:

- [Workflows](docs/workflows.md)
- [AI Workflow Generator](docs/ai-workflows.md)
- [Functions](docs/functions.md)
- [MCP Servers](docs/mcp.md)
- [ASL with JSONata](docs/asl-jsonata.md)
- [Interpreter Internals](docs/interpreter.md)
- [Secrets](docs/secrets.md)
- [Jenkins CI/CD](docs/jenkins.md)

The optional deterministic MCP fixture used by development and AI-generation tests is documented in
[MCP Servers — Local demo MCP fixture](docs/mcp.md#local-demo-mcp-fixture).

The local-first Jenkins setup, GHCR image names, credentials, guarded Docker Desktop deployment, and
rollback procedure are documented in [Jenkins CI/CD](docs/jenkins.md).

---

## Workflow Engine Architecture (ASL)

The scheduler includes a distributed workflow engine that executes JSON-defined state machines based on the Amazon States Language (ASL). It replaces JSONPath with JSONata (`{% ... %}`) for expressive data transformation and conditions.

### Core Components

👉 **[Read the Syntax Guide: ASL with JSONata](docs/asl-jsonata.md)** for a guide on how we replaced JSONPath with JSONata (`{% ... %}`) for data mapping (`Arguments`, `Assign`, `Output`).
👉 **[Read the Deep-Dive: Workflow Interpreter Internals](docs/interpreter.md)** for a code-level breakdown of the transition engine, JSONata evaluations, and execution locking.

1. **State Machines & Execution Hierarchy**:
   - `Workflow` & `WorkflowDefinition`: Represents the registered ASL state machine and its versioned JSON definitions.
   - `WorkflowExecution`: A single run of a workflow.
   - `ExecutionScope`: Represents a branch of execution. Workflows start with a root scope. Parallel and Map states create child scopes.
   - `StateExecution` & `StateExecutionAttempt`: Tracks the active state within a scope and individual task attempts.

2. **Frontier-Driven Execution (`WorkflowExecutionRunner`)**:
   Instead of using heavy threads per workflow, the engine is "frontier-driven". The `drive(execution)` loop uses a `Deque<UUID>` of runnable scope IDs.
   - A scope that suspends (e.g., waiting for a Task or a timer) drops out of the frontier.
   - A Parallel/Map state pushes its child branch scopes to the frontier.
   - A settled child scope pushes its parent compound scope back to the frontier.
   - The engine iterates through the runnable queue, transitioning states (`workflowInterpreter.advance()`), until the queue is empty, achieving deep execution without stack overflows.

3. **Database-Backed Locking & Synchronization**:
   To safely settle parallel branches, the system relies on PostgreSQL row-level pessimistic locking (`SELECT ... FOR UPDATE`). When multiple child scopes complete concurrently, the DB lock ensures they serialize when evaluating if the parent compound state can resume.

4. **Background Watchdogs & Wait Scheduling**:
   Executions that are suspended are woken up asynchronously by background watchdogs:
   - `DueWorkflowWaitSchedulerService`: Resumes workflows waiting on Wait states.
   - `DueTaskAttemptSchedulerService`: Dispatches ready tasks to workers.
   - `RunningTaskAttemptWatchdogService`: Recovers tasks that timed out.
   When these services fire, they call `resume()` which enqueues the scope back into the `drive()` loop.

### Comparison to Other Tools

- **AWS Step Functions**: The ASL syntax is heavily inspired by AWS Step Functions, but this engine uses **JSONata** exclusively instead of JSONPath, offering much richer data mapping and transformation within the state machine definition itself. It also runs entirely within your own infrastructure (PostgreSQL + Spring Boot).
- **Temporal / Cadence**: Temporal uses code-based workflows (e.g., Java/Go) that block on promises and use event sourcing to rebuild state. Our engine uses declarative JSON definitions and tracks the active state pointer in PostgreSQL, avoiding the need to replay workflow histories.
- **Apache Airflow**: Airflow is DAG-based and typically suited for coarse-grained data pipelines (batch jobs). Our engine is state-machine based, making it better suited for microservice orchestration, long-running business processes, and complex branching/looping logic.

---


---

## Testing and CI

The test suite uses Testcontainers for integration coverage. Make sure Docker is running before executing:

```bash
mvn test
```

To generate coverage and send analysis to SonarQube or SonarCloud:

```bash
mvn clean verify sonar:sonar \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.token=your_sonar_token
```

JaCoCo writes the coverage report to `target/site/jacoco/jacoco.xml`, and Surefire writes test results to `target/surefire-reports`.

### Coverage

The tables below are regenerated by `scripts/update-coverage-readme.py` after each `mvn test` run in CI; do not edit between the markers manually.

<!-- COVERAGE-START -->

Latest JaCoCo run across the full Testcontainers-backed suite:

| Metric | Covered | Total | Coverage |
|---|---:|---:|---:|
| Instructions | 28,380 | 34,407 | 82% |
| Branches | 2,429 | 3,629 | 67% |
| Lines | 6,252 | 7,619 | 82% |
| Methods | 1,031 | 1,190 | 87% |
| Classes | 252 | 262 | 96% |

Per-package instruction coverage:

| Package | Coverage |
|---|---:|
| `consumers` | 100% |
| `com.job.scheduler.entity` | 100% |
| `enums` | 100% |
| `monitoring` | 100% |
| `producers` | 100% |
| `utility` | 100% |
| `dto` | 94% |
| `com.job.scheduler.workflow.asl.validation` | 94% |
| `handlers` | 93% |
| `com.job.scheduler.workflow.task` | 91% |
| `dto.payload` | 89% |
| `scheduler` (due-job, watchdogs, DLQ) | 89% |
| `controller` | 88% |
| `com.job.scheduler.workflow.asl.runtime` | 88% |
| `exception` | 87% |
| `com.job.scheduler.entity.converter` | 86% |
| `service` (job lifecycle, worker, locks) | 75% |
| `config` | 69% |
| `com.job.scheduler` (root) | 38% |

Open `target/site/jacoco/index.html` after `mvn test` for the drill-down view.

<!-- COVERAGE-END -->

### GitHub Actions CI

The repository includes `.github/workflows/ci.yml`. It runs:

- `./mvnw -B test` on every push and pull request
- JaCoCo and Surefire artifact uploads for debugging and coverage review
- On pushes to `main`, regenerates the README Coverage tables from the fresh `jacoco.csv` and commits the diff back as `github-actions[bot]` (skipped on PRs and forks)
- `./mvnw -B clean verify sonar:sonar` when both `SONAR_TOKEN` and the `SONAR_HOST_URL` repository variable are configured

To enable Sonar analysis in GitHub Actions:

1. Add a repository secret named `SONAR_TOKEN`
2. Add a repository variable named `SONAR_HOST_URL`
3. Make sure that SonarQube server is reachable from the GitHub runner; `http://localhost:9000` only works for local runs, not GitHub-hosted runners
