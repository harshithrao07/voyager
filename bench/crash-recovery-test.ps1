[CmdletBinding()]
param(
    [ValidateRange(1, 50)]
    [int] $Runs = 5,

    [ValidateRange(4, 2000)]
    [int] $TotalExecutions = 200,

    [ValidateRange(1, 120)]
    [int] $CrashDowntimeSeconds = 10,

    [ValidateRange(60, 3600)]
    [int] $RecoveryBudgetSeconds = 900,

    [ValidateRange(5, 300)]
    [int] $ArmBudgetSeconds = 30,

    [ValidateRange(1, 500)]
    [int] $SubmissionBatchSize = 50,

    [string] $ApiBase = 'http://localhost:8081',

    [switch] $SkipPhase1
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$terminalExecutionStatuses = @('SUCCEEDED', 'FAILED', 'CANCELED', 'TIMED_OUT')
$terminalScopeSql = "'SUCCEEDED','FAILED','CANCELED','TIMED_OUT'"
$sessionId = "crash-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
$resultsRoot = Join-Path $repoRoot "bench\results\crash-recovery\$sessionId"
New-Item -ItemType Directory -Path $resultsRoot -Force | Out-Null

function Write-Step([string] $Message) {
    Write-Host "[$([DateTime]::Now.ToString('HH:mm:ss'))] $Message" -ForegroundColor Cyan
}

function Invoke-JsonRest {
    param(
        [Parameter(Mandatory)] [string] $Method,
        [Parameter(Mandatory)] [string] $Uri,
        [object] $Body,
        [int] $TimeoutSec = 30
    )

    $parameters = @{
        Method = $Method
        Uri = $Uri
        TimeoutSec = $TimeoutSec
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = ($Body | ConvertTo-Json -Depth 100 -Compress)
    }
    Invoke-RestMethod @parameters
}

function Invoke-Sql([string] $Sql) {
    $lines = & docker compose exec -T postgres psql `
        -U postgres -d jobscheduler -v ON_ERROR_STOP=1 -tA -c $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed with exit code $LASTEXITCODE"
    }
    (($lines -join "`n").Trim())
}

function Invoke-SqlCsv([string] $Sql) {
    $copy = "COPY ($Sql) TO STDOUT WITH (FORMAT CSV, HEADER TRUE)"
    $lines = & docker compose exec -T postgres psql `
        -U postgres -d jobscheduler -v ON_ERROR_STOP=1 -c $copy
    if ($LASTEXITCODE -ne 0) {
        throw "psql CSV export failed with exit code $LASTEXITCODE"
    }
    ($lines -join "`n")
}

function Wait-AppReady([int] $BudgetSeconds = 180) {
    $deadline = (Get-Date).AddSeconds($BudgetSeconds)
    do {
        try {
            $health = Invoke-RestMethod `
                -Uri "$ApiBase/actuator/health/readiness" `
                -TimeoutSec 3
            if ($health.status -eq 'UP') {
                return [DateTimeOffset]::UtcNow
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    } while ((Get-Date) -lt $deadline)
    throw "Voyager did not become ready within ${BudgetSeconds}s"
}

function Wait-CounterReady([int] $BudgetSeconds = 30) {
    $deadline = (Get-Date).AddSeconds($BudgetSeconds)
    do {
        try {
            $health = Invoke-RestMethod `
                -Uri 'http://localhost:18082/health' `
                -TimeoutSec 2
            if ($health.status -eq 'UP') {
                return
            }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    } while ((Get-Date) -lt $deadline)
    throw "Crash counter did not become ready within ${BudgetSeconds}s"
}

function New-Workflow {
    param(
        [string] $Name,
        [object] $Definition,
        [string] $IdempotencyKey
    )
    Invoke-JsonRest -Method POST -Uri "$ApiBase/app/v1/workflows" -Body @{
        name = $Name
        maxAttempts = 0
        idempotencyKey = $IdempotencyKey
        definition = $Definition
    }
}

function New-SleeperFunction {
    $name = "crash-sleeper-$($sessionId.Replace('crash-', ''))"
    $function = Invoke-JsonRest -Method POST `
        -Uri "$ApiBase/app/v1/functions" -Body @{
            name = $name
            description = 'Crash-recovery benchmark sleeper; reads/writes one JSON value.'
            status = 'ENABLED'
        }

    $source = @'
import json
import sys
import time

value = json.load(sys.stdin)
time.sleep(float(value.get("sleepSeconds", 3)))
print(json.dumps(value, separators=(",", ":")))
'@

    $version = Invoke-JsonRest -Method POST `
        -Uri "$ApiBase/app/v1/functions/$($function.id)/versions" -Body @{
            sourceMode = 'SINGLE_FILE'
            languageId = 71
            sourceCode = $source
            cpuTimeLimitSeconds = 2.0
            wallTimeLimitSeconds = 10.0
            memoryLimitKb = 262144
            maxFileSizeKb = 1024
            maxOutputBytes = 65536
            enableNetwork = $false
            note = 'Created by crash-recovery benchmark'
            status = 'DRAFT'
        }
    Invoke-JsonRest -Method POST `
        -Uri "$ApiBase/app/v1/functions/$($function.id)/versions/$($version.version)/publish" | Out-Null

    [pscustomobject]@{
        Id = $function.id
        Name = $name
        Version = $version.version
        Resource = "voyager://function/$name@v$($version.version)"
    }
}

function Read-WorkflowTemplate {
    param([string] $Name, [string] $SleeperResource)
    $path = Join-Path $repoRoot "bench\crash-recovery\workflows\$Name.json"
    $raw = Get-Content -Raw -LiteralPath $path
    if ($SleeperResource) {
        $raw = $raw.Replace('__SLEEPER_RESOURCE__', $SleeperResource)
    }
    $raw | ConvertFrom-Json
}

function Submit-ExecutionsConcurrently {
    param([array] $Specs, [string] $RunId)

    Add-Type -AssemblyName System.Net.Http
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.MaxConnectionsPerServer = [Math]::Max(256, $Specs.Count)
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(60)
    $responses = [System.Collections.Generic.List[object]]::new()
    for ($offset = 0; $offset -lt $Specs.Count; $offset += $SubmissionBatchSize) {
        $pending = [System.Collections.Generic.List[object]]::new()
        $last = [Math]::Min($offset + $SubmissionBatchSize, $Specs.Count)
        for ($specIndex = $offset; $specIndex -lt $last; $specIndex++) {
            $spec = $Specs[$specIndex]
            $json = @{
                input = @{
                    runId = $RunId
                    workload = $spec.Workload
                    index = $spec.Index
                }
            } | ConvertTo-Json -Depth 10 -Compress
            $content = [System.Net.Http.StringContent]::new(
                $json,
                [System.Text.Encoding]::UTF8,
                'application/json'
            )
            $uri = "$ApiBase/app/v1/workflows/$($spec.WorkflowId)/executions"
            $pending.Add([pscustomobject]@{
                Uri = $uri
                Task = $client.PostAsync($uri, $content)
            })
        }

        foreach ($entry in $pending) {
            try {
                $response = $entry.Task.GetAwaiter().GetResult()
            } catch {
                throw "Execution start request failed for $($entry.Uri): $($_.Exception.Message)"
            }
            $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if (-not $response.IsSuccessStatusCode) {
                throw "Execution start failed ($([int]$response.StatusCode)): $text"
            }
            $responses.Add(($text | ConvertFrom-Json))
        }
    }
    $client.Dispose()
    @($responses)
}

function Get-ArmSnapshot {
    param(
        [string] $RunId,
        [string] $WaitWorkflowId,
        [string] $TaskWorkflowId
    )
    $sql = @"
WITH run_exec AS (
  SELECT id, workflow_id
  FROM workflow_executions
  WHERE input ->> 'runId' = '$RunId'
), partial_compounds AS (
  SELECT parent.id
  FROM execution_scopes parent
  JOIN run_exec execution ON execution.id = parent.workflow_execution_id
  JOIN execution_scopes child ON child.parent_scope_id = parent.id
  GROUP BY parent.id
  HAVING bool_or(child.status IN ($terminalScopeSql))
     AND bool_or(child.status NOT IN ($terminalScopeSql))
)
SELECT json_build_object(
  'submitted', (SELECT count(*) FROM run_exec),
  'waitSuspended', (
    SELECT count(*) FROM execution_scopes scope
    JOIN run_exec execution ON execution.id = scope.workflow_execution_id
    WHERE execution.workflow_id = '$WaitWorkflowId'
      AND scope.scope_type = 'ROOT'
      AND scope.status = 'WAITING'
      AND scope.wake_at IS NOT NULL
  ),
  'taskSuspended', (
    SELECT count(*) FROM execution_scopes scope
    JOIN run_exec execution ON execution.id = scope.workflow_execution_id
    WHERE execution.workflow_id = '$TaskWorkflowId'
      AND scope.scope_type = 'ROOT'
      AND scope.status = 'WAITING'
      AND scope.current_state_name = 'SleepInJudge0'
  ),
  'partialCompounds', (SELECT count(*) FROM partial_compounds),
  'terminalExecutions', (
    SELECT count(*) FROM workflow_executions execution
    JOIN run_exec selected ON selected.id = execution.id
    WHERE execution.status IN ($terminalScopeSql)
  )
)::text
"@
    (Invoke-Sql $sql) | ConvertFrom-Json
}

function Get-FinalSnapshot([string] $RunId, [DateTimeOffset] $RestartAt) {
    $restartIso = $RestartAt.UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    $sql = @"
WITH run_exec AS (
  SELECT * FROM workflow_executions
  WHERE input ->> 'runId' = '$RunId'
), status_counts AS (
  SELECT status, count(*) AS count
  FROM run_exec GROUP BY status
), webhook_attempts AS (
  SELECT attempt.*
  FROM state_execution_attempts attempt
  JOIN state_executions state ON state.id = attempt.state_execution_id
  JOIN execution_scopes scope ON scope.id = state.execution_scope_id
  JOIN run_exec execution ON execution.id = scope.workflow_execution_id
  WHERE state.resource = 'voyager://system/webhook'
), function_attempts AS (
  SELECT attempt.*
  FROM state_execution_attempts attempt
  JOIN state_executions state ON state.id = attempt.state_execution_id
  JOIN execution_scopes scope ON scope.id = state.execution_scope_id
  JOIN run_exec execution ON execution.id = scope.workflow_execution_id
  WHERE state.resource LIKE 'voyager://function/%'
), function_invocations AS (
  SELECT invocation.*, attempt.state_execution_id
  FROM workflow_function_invocations invocation
  LEFT JOIN state_execution_attempts attempt
    ON attempt.id = invocation.state_execution_attempt_id
  JOIN run_exec execution ON execution.id = invocation.workflow_execution_id
), duplicate_function_attempts AS (
  SELECT state_execution_attempt_id, count(*) AS count
  FROM function_invocations
  WHERE state_execution_attempt_id IS NOT NULL
  GROUP BY state_execution_attempt_id HAVING count(*) > 1
), duplicate_function_operations AS (
  SELECT state_execution_id, count(*) AS count
  FROM function_invocations
  WHERE state_execution_id IS NOT NULL
  GROUP BY state_execution_id HAVING count(*) > 1
), latency AS (
  SELECT extract(epoch FROM (completed_at - '$restartIso'::timestamptz)) * 1000 AS ms
  FROM run_exec WHERE completed_at IS NOT NULL
)
SELECT json_build_object(
  'executionCount', (SELECT count(*) FROM run_exec),
  'statusCounts', COALESCE((
    SELECT json_object_agg(status, count) FROM status_counts
  ), '{}'::json),
  'nonterminalExecutions', (
    SELECT count(*) FROM run_exec WHERE status NOT IN ($terminalScopeSql)
  ),
  'nonterminalScopes', (
    SELECT count(*) FROM execution_scopes scope
    JOIN run_exec execution ON execution.id = scope.workflow_execution_id
    WHERE scope.status NOT IN ($terminalScopeSql)
  ),
  'traceGaps', (
    SELECT count(*) FROM run_exec execution
    WHERE NOT EXISTS (
      SELECT 1 FROM execution_scopes scope
      WHERE scope.workflow_execution_id = execution.id
    ) OR NOT EXISTS (
      SELECT 1 FROM execution_scopes scope
      JOIN state_executions state ON state.execution_scope_id = scope.id
      WHERE scope.workflow_execution_id = execution.id
    )
  ),
  'webhookStartedAttempts', (
    SELECT count(*) FROM webhook_attempts WHERE started_at IS NOT NULL
  ),
  'webhookAttemptCount', (SELECT count(*) FROM webhook_attempts),
  'functionStartedAttempts', (
    SELECT count(*) FROM function_attempts WHERE started_at IS NOT NULL
  ),
  'functionAttemptCount', (SELECT count(*) FROM function_attempts),
  'functionInvocationCount', (SELECT count(*) FROM function_invocations),
  'functionInvocationsWithoutAttemptId', (
    SELECT count(*) FROM function_invocations
    WHERE state_execution_attempt_id IS NULL
  ),
  'functionInvocationsStillRunning', (
    SELECT count(*) FROM function_invocations WHERE status = 'RUNNING'
  ),
  'duplicateFunctionAttemptInvocations', COALESCE((
    SELECT sum(count - 1) FROM duplicate_function_attempts
  ), 0),
  'duplicateFunctionOperationInvocations', COALESCE((
    SELECT sum(count - 1) FROM duplicate_function_operations
  ), 0),
  'recoveryLatencyP50Ms', (SELECT round(percentile_cont(0.50) WITHIN GROUP (ORDER BY ms)::numeric, 1) FROM latency),
  'recoveryLatencyP95Ms', (SELECT round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 1) FROM latency),
  'recoveryLatencyP99Ms', (SELECT round(percentile_cont(0.99) WITHIN GROUP (ORDER BY ms)::numeric, 1) FROM latency),
  'recoveryLatencyMaxMs', (SELECT round(max(ms)::numeric, 1) FROM latency)
)::text
"@
    (Invoke-Sql $sql) | ConvertFrom-Json
}

function Get-WebhookAttemptIds([string] $RunId) {
    $sql = @"
SELECT attempt.id
FROM state_execution_attempts attempt
JOIN state_executions state ON state.id = attempt.state_execution_id
JOIN execution_scopes scope ON scope.id = state.execution_scope_id
JOIN workflow_executions execution ON execution.id = scope.workflow_execution_id
WHERE execution.input ->> 'runId' = '$RunId'
  AND state.resource = 'voyager://system/webhook'
  AND attempt.started_at IS NOT NULL
ORDER BY attempt.id
"@
    @((Invoke-Sql $sql) -split "`n" | Where-Object { $_ })
}

function Wait-ForTerminalExecutions([string] $RunId, [int] $Expected) {
    $deadline = (Get-Date).AddSeconds($RecoveryBudgetSeconds)
    do {
        $sql = @"
SELECT count(*) FILTER (WHERE status IN ($terminalScopeSql)) || '|' ||
       count(*) FILTER (WHERE status NOT IN ($terminalScopeSql))
FROM workflow_executions
WHERE input ->> 'runId' = '$RunId'
"@
        $parts = (Invoke-Sql $sql) -split '\|'
        $terminal = [int]$parts[0]
        $active = [int]$parts[1]
        Write-Host "    terminal=$terminal active=$active" -ForegroundColor DarkGray
        if ($terminal -eq $Expected -and $active -eq 0) {
            return [DateTimeOffset]::UtcNow
        }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)
    throw "Run $RunId did not drain within ${RecoveryBudgetSeconds}s"
}

function Invoke-Phase1Smoke {
    Write-Step 'Phase 1: single durable Wait smoke test'
    $definition = @'
{
  "StartAt": "CrashWindow",
  "States": {
    "CrashWindow": {"Type": "Wait", "Seconds": 10, "Next": "Recovered"},
    "Recovered": {"Type": "Succeed", "Output": {"recovered": true}}
  }
}
'@ | ConvertFrom-Json
    $workflow = New-Workflow `
        -Name "$sessionId-phase1-wait" `
        -Definition $definition `
        -IdempotencyKey "$sessionId-phase1-wait"
    $execution = Invoke-JsonRest -Method POST `
        -Uri "$ApiBase/app/v1/workflows/$($workflow.id)/executions" `
        -Body @{ input = @{ runId = "$sessionId-phase1" } }
    if ($execution.status -ne 'WAITING') {
        throw "Phase 1 expected WAITING, got $($execution.status)"
    }
    & docker compose kill -s SIGKILL app | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not SIGKILL the app service' }
    Start-Sleep -Seconds 12
    & docker compose start app | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not restart the app service' }
    Wait-AppReady | Out-Null
    $deadline = (Get-Date).AddSeconds(120)
    do {
        $detail = Invoke-JsonRest -Method GET `
            -Uri "$ApiBase/app/v1/workflows/$($workflow.id)/executions/$($execution.workflowExecutionId)"
        if ($detail.execution.status -eq 'SUCCEEDED') {
            $result = [ordered]@{
                passed = $true
                workflowId = $workflow.id
                executionId = $execution.workflowExecutionId
                finalStatus = $detail.execution.status
            }
            $result | ConvertTo-Json -Depth 10 | Set-Content `
                -LiteralPath (Join-Path $resultsRoot 'phase1.json') -Encoding utf8
            Write-Host '    Phase 1 passed' -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw 'Phase 1 execution did not recover to SUCCEEDED'
}

Write-Step "Crash-recovery session $sessionId (default production timings)"
Write-Step 'Starting invocation counter'
& docker compose -f bench/docker-compose.crash-recovery.yml up -d | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not start crash-counter' }
Wait-CounterReady
Invoke-RestMethod -Method POST -Uri 'http://localhost:18082/reset' -TimeoutSec 10 | Out-Null
Wait-AppReady | Out-Null

if (-not $SkipPhase1) {
    Invoke-Phase1Smoke
}

Write-Step 'Provisioning Judge0 sleeper and four validated workflow shapes'
$sleeper = New-SleeperFunction
$definitions = @{
    wait = Read-WorkflowTemplate -Name 'wait' -SleeperResource $null
    task = Read-WorkflowTemplate -Name 'task' -SleeperResource $sleeper.Resource
    parallel = Read-WorkflowTemplate -Name 'parallel' -SleeperResource $sleeper.Resource
    map = Read-WorkflowTemplate -Name 'map' -SleeperResource $null
}
$workflows = @{}
foreach ($kind in @('wait', 'task', 'parallel', 'map')) {
    $workflows[$kind] = New-Workflow `
        -Name "$sessionId-$kind" `
        -Definition $definitions[$kind] `
        -IdempotencyKey "$sessionId-$kind"
}

$waitCount = [int][Math]::Floor($TotalExecutions * 0.40)
$taskCount = [int][Math]::Floor($TotalExecutions * 0.40)
$remaining = $TotalExecutions - $waitCount - $taskCount
$parallelCount = [int][Math]::Floor($remaining / 2)
$mapCount = $remaining - $parallelCount

$sessionSummary = [ordered]@{
    sessionId = $sessionId
    profile = 'default'
    defaultTimingConfig = [ordered]@{
        queuedTimeoutMs = 300000
        queuedWatchdogPollMs = 60000
        runningTimeoutMs = 600000
        runningWatchdogPollMs = 60000
        scopeRecoveryStaleTimeoutMs = 60000
        scopeRecoveryPollMs = 1000
    }
    requestedRuns = $Runs
    totalExecutionsPerRun = $TotalExecutions
    mix = [ordered]@{
        wait = $waitCount
        task = $taskCount
        parallel = $parallelCount
        map = $mapCount
    }
    sleeper = $sleeper
    workflowIds = [ordered]@{
        wait = $workflows.wait.id
        task = $workflows.task.id
        parallel = $workflows.parallel.id
        map = $workflows.map.id
    }
    successCriteria = @(
        'mixed Wait, Task, and partially settled compound crash point armed',
        'all executions terminal',
        'zero nonterminal scopes',
        'exactly one external invocation per persisted started attempt',
        'all submitted execution IDs and persisted traces preserved'
    )
    runs = @()
}

for ($runNumber = 1; $runNumber -le $Runs; $runNumber++) {
    $runId = "$sessionId-r$runNumber"
    $runDir = Join-Path $resultsRoot "run-$runNumber"
    New-Item -ItemType Directory -Path $runDir -Force | Out-Null
    Write-Step "Run $runNumber/${Runs}: submitting $TotalExecutions concurrent executions"

    $specs = @()
    foreach ($kind in @('wait', 'task', 'parallel', 'map')) {
        $count = switch ($kind) {
            'wait' { $waitCount }
            'task' { $taskCount }
            'parallel' { $parallelCount }
            'map' { $mapCount }
        }
        for ($index = 0; $index -lt $count; $index++) {
            $specs += [pscustomobject]@{
                Workload = $kind
                WorkflowId = $workflows[$kind].id
                Index = $index
            }
        }
    }
    $specs = @($specs | Sort-Object { Get-Random })
    $submittedAt = [DateTimeOffset]::UtcNow
    $responses = Submit-ExecutionsConcurrently -Specs $specs -RunId $runId
    $executionIds = @($responses | ForEach-Object { $_.workflowExecutionId })
    $executionIds | Set-Content -LiteralPath (Join-Path $runDir 'execution-ids.txt') -Encoding ascii
    if ($executionIds.Count -ne $TotalExecutions) {
        throw "Expected $TotalExecutions execution IDs, got $($executionIds.Count)"
    }

    Write-Step "Run $runNumber/${Runs}: arming crash after mixed suspension is visible"
    $armDeadline = (Get-Date).AddSeconds($ArmBudgetSeconds)
    $armCriteriaMet = $false
    do {
        $arm = Get-ArmSnapshot `
            -RunId $runId `
            -WaitWorkflowId $workflows.wait.id `
            -TaskWorkflowId $workflows.task.id
        Write-Host ("    wait={0} task={1} partialCompounds={2} terminal={3}" -f `
            $arm.waitSuspended, $arm.taskSuspended,
            $arm.partialCompounds, $arm.terminalExecutions) -ForegroundColor DarkGray
        $requiredTaskSuspended = if ($TotalExecutions -lt 20) {
            0
        } else {
            [Math]::Max(1, [int]($taskCount * 0.75))
        }
        $armCriteriaMet = [int]$arm.waitSuspended -ge [Math]::Max(1, [int]($waitCount * 0.75)) `
            -and [int]$arm.taskSuspended -ge $requiredTaskSuspended `
            -and [int]$arm.partialCompounds -ge 1
        if (-not $armCriteriaMet) { Start-Sleep -Seconds 1 }
    } while (-not $armCriteriaMet -and (Get-Date) -lt $armDeadline)

    $preCrash = Get-ArmSnapshot `
        -RunId $runId `
        -WaitWorkflowId $workflows.wait.id `
        -TaskWorkflowId $workflows.task.id
    $preCrash | ConvertTo-Json -Depth 10 | Set-Content `
        -LiteralPath (Join-Path $runDir 'pre-crash.json') -Encoding utf8

    Write-Step "Run $runNumber/${Runs}: SIGKILL app (arm criteria met: $armCriteriaMet)"
    $crashedAt = [DateTimeOffset]::UtcNow
    & docker compose kill -s SIGKILL app | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not SIGKILL the app service' }
    Start-Sleep -Seconds $CrashDowntimeSeconds

    Write-Step "Run $runNumber/${Runs}: restarting app and measuring frontier drain"
    $restartAt = [DateTimeOffset]::UtcNow
    & docker compose start app | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not restart the app service' }
    $readyAt = Wait-AppReady
    $drainedAt = Wait-ForTerminalExecutions -RunId $runId -Expected $TotalExecutions
    $drainMs = [Math]::Round(($drainedAt - $restartAt).TotalMilliseconds, 1)

    $final = Get-FinalSnapshot -RunId $runId -RestartAt $restartAt
    $counterResponse = Invoke-RestMethod `
        -Uri "http://localhost:18082/invocations?runId=$runId" `
        -TimeoutSec 30
    $counterRecords = @()
    for ($counterIndex = 0;
         $counterIndex -lt $counterResponse.Count;
         $counterIndex++) {
        $counterRecords += $counterResponse[$counterIndex]
    }
    $expectedWebhookAttemptIds = @(Get-WebhookAttemptIds -RunId $runId)
    $actualWebhookAttemptIds = @(
        $counterRecords |
            Where-Object { $_.stateExecutionAttemptId } |
            ForEach-Object { [string]$_.stateExecutionAttemptId }
    )
    $duplicateCounterAttempts = @(
        $actualWebhookAttemptIds | Group-Object |
            Where-Object Count -gt 1 |
            ForEach-Object { $_.Count - 1 } |
            Measure-Object -Sum
    ).Sum
    if ($null -eq $duplicateCounterAttempts) { $duplicateCounterAttempts = 0 }
    $duplicateCounterOperations = @(
        $counterRecords | Where-Object { $_.operationId } |
            Group-Object operationId | Where-Object Count -gt 1 |
            ForEach-Object { $_.Count - 1 } | Measure-Object -Sum
    ).Sum
    if ($null -eq $duplicateCounterOperations) { $duplicateCounterOperations = 0 }
    $missingAttemptHeaders = @(
        $counterRecords | Where-Object { -not $_.stateExecutionAttemptId }
    ).Count
    $missingWebhookAttempts = @(
        $expectedWebhookAttemptIds | Where-Object { $_ -notin $actualWebhookAttemptIds }
    ).Count
    $unexpectedWebhookInvocations = @(
        $actualWebhookAttemptIds | Where-Object { $_ -notin $expectedWebhookAttemptIds }
    ).Count

    $submittedIdList = $executionIds | ForEach-Object { "'$_'" }
    $preservedIds = [int](Invoke-Sql @"
SELECT count(*) FROM workflow_executions
WHERE input ->> 'runId' = '$runId'
  AND id IN ($($submittedIdList -join ','))
"@)

    $perExecutionSql = @"
SELECT execution.id,
       execution.workflow_id,
       execution.status,
       execution.created_at,
       execution.started_at,
       execution.completed_at,
       round((extract(epoch FROM (execution.completed_at - '$($restartAt.UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ss.fffZ'))'::timestamptz)) * 1000)::numeric, 1) AS recovery_latency_ms,
       (SELECT count(*) FROM execution_scopes scope WHERE scope.workflow_execution_id = execution.id) AS scope_count,
       (SELECT count(*) FROM execution_scopes scope JOIN state_executions state ON state.execution_scope_id = scope.id WHERE scope.workflow_execution_id = execution.id) AS state_count
FROM workflow_executions execution
WHERE execution.input ->> 'runId' = '$runId'
ORDER BY execution.created_at
"@
    Invoke-SqlCsv $perExecutionSql | Set-Content `
        -LiteralPath (Join-Path $runDir 'execution-recovery-latencies.csv') -Encoding utf8
    $counterRecords | ConvertTo-Json -Depth 20 | Set-Content `
        -LiteralPath (Join-Path $runDir 'counter-invocations.json') -Encoding utf8

    $criteria = [ordered]@{
        mixedCrashPointArmed = $armCriteriaMet
        allExecutionsTerminal = [int]$final.nonterminalExecutions -eq 0 `
            -and [int]$final.executionCount -eq $TotalExecutions
        zeroScopesStuck = [int]$final.nonterminalScopes -eq 0
        zeroDuplicateCounterAttemptInvocations = [int]$duplicateCounterAttempts -eq 0
        zeroDuplicateFunctionAttemptInvocations = [int]$final.duplicateFunctionAttemptInvocations -eq 0
        invocationLedgerMatchesAttempts = $counterRecords.Count -eq [int]$final.webhookStartedAttempts `
            -and [int]$missingAttemptHeaders -eq 0 `
            -and [int]$missingWebhookAttempts -eq 0 `
            -and [int]$unexpectedWebhookInvocations -eq 0 `
            -and [int]$final.functionInvocationCount -eq [int]$final.functionStartedAttempts `
            -and [int]$final.functionInvocationsWithoutAttemptId -eq 0
        executionIdsAndTracesPreserved = $preservedIds -eq $TotalExecutions `
            -and [int]$final.traceGaps -eq 0
    }
    $logicalOperationDiagnostics = [ordered]@{
        duplicateCounterLogicalOperations = [int]$duplicateCounterOperations
        duplicateFunctionLogicalOperations = [int]$final.duplicateFunctionOperationInvocations
    }
    $passed = @($criteria.Values | Where-Object { -not $_ }).Count -eq 0

    $runSummary = [ordered]@{
        runNumber = $runNumber
        runId = $runId
        profile = 'default'
        submittedAt = $submittedAt.ToString('o')
        crashedAt = $crashedAt.ToString('o')
        restartCommandAt = $restartAt.ToString('o')
        appReadyAt = $readyAt.ToString('o')
        drainedAt = $drainedAt.ToString('o')
        restartToDrainMs = $drainMs
        armCriteriaMet = $armCriteriaMet
        preCrash = $preCrash
        final = $final
        counter = [ordered]@{
            invocationCount = $counterRecords.Count
            expectedStartedAttemptCount = [int]$final.webhookStartedAttempts
            missingAttemptHeaders = [int]$missingAttemptHeaders
            missingExpectedAttempts = [int]$missingWebhookAttempts
            unexpectedAttemptIds = [int]$unexpectedWebhookInvocations
            duplicateAttemptInvocations = [int]$duplicateCounterAttempts
            duplicateLogicalOperations = [int]$duplicateCounterOperations
        }
        preservedExecutionIds = $preservedIds
        criteria = $criteria
        logicalOperationDiagnostics = $logicalOperationDiagnostics
        passed = $passed
    }
    $runSummary | ConvertTo-Json -Depth 30 | Set-Content `
        -LiteralPath (Join-Path $runDir 'summary.json') -Encoding utf8
    $sessionSummary.runs += $runSummary

    $color = if ($passed) { 'Green' } else { 'Red' }
    Write-Host ("    run {0}: passed={1}, restart-to-drain={2}ms, counter={3}, functionInvocations={4}" -f `
        $runNumber, $passed, $drainMs, $counterRecords.Count,
        $final.functionInvocationCount) -ForegroundColor $color
}

$sessionSummary.allRunsPassed = @(
    $sessionSummary.runs | Where-Object { -not $_.passed }
).Count -eq 0
$sessionSummary | ConvertTo-Json -Depth 40 | Set-Content `
    -LiteralPath (Join-Path $resultsRoot 'summary.json') -Encoding utf8

Write-Step "Complete. Results: $resultsRoot"
if (-not $sessionSummary.allRunsPassed) {
    Write-Warning 'One or more runs failed at least one predeclared success criterion.'
    exit 2
}
