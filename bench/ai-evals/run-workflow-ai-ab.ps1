[CmdletBinding()]
param(
    [string] $ModelName = 'qwen3:8b',

    [ValidateRange(1, 20)]
    [int] $Repetitions = 3,

    [ValidateSet('Both', 'Baseline', 'Enhanced')]
    [string] $Arm = 'Both',

    [string] $BaselineReport,

    [string] $BaseUrl = 'http://localhost:8081',

    [string] $Suite = 'bench/ai-evals/workflow-generation-ab-v1.json',

    [string] $CaseId,

    [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $repoRoot

$timestamp = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')
$resultDirectory = Join-Path $repoRoot "bench\ai-evals\results\workflow-ai-ab-$timestamp"
New-Item -ItemType Directory -Path $resultDirectory -Force | Out-Null
$suitePath = (Resolve-Path -LiteralPath $Suite).Path
$compose = @(
    'compose',
    '-f', (Join-Path $repoRoot 'docker-compose.yml'),
    '-f', (Join-Path $repoRoot 'bench\docker-compose.ai-ab.yml')
)

function Write-Step([string] $Message) {
    Write-Host "[$([DateTimeOffset]::Now.ToString('HH:mm:ss'))] $Message" -ForegroundColor Cyan
}

function Wait-AppReady([int] $BudgetSeconds = 240) {
    $deadline = (Get-Date).AddSeconds($BudgetSeconds)
    do {
        try {
            $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 5
            if ($health.status -eq 'UP') { return }
        } catch {
            Start-Sleep -Seconds 2
        }
    } while ((Get-Date) -lt $deadline)
    throw "Voyager did not become healthy within ${BudgetSeconds}s"
}

function Set-Profile(
    [string] $Name,
    [bool] $ToolCalling,
    [bool] $Embedding,
    [bool] $StructuredOutput,
    [int] $RepairPasses
) {
    $env:AI_AB_TOOL_CALLING_ENABLED = $ToolCalling.ToString().ToLowerInvariant()
    $env:AI_AB_EMBEDDING_ENABLED = $Embedding.ToString().ToLowerInvariant()
    $env:AI_AB_STRUCTURED_OUTPUT_ENABLED = $StructuredOutput.ToString().ToLowerInvariant()
    $env:AI_AB_MAX_REPAIR_PASSES = $RepairPasses.ToString()
    Write-Step "Starting $Name profile"
    & docker @compose up -d --force-recreate app
    if ($LASTEXITCODE -ne 0) { throw "Could not start the $Name app profile" }
    Wait-AppReady
}

function Resolve-ModelId {
    $models = Invoke-RestMethod -Uri "$BaseUrl/app/v1/ai/models" -TimeoutSec 30
    $model = @($models | Where-Object {
        $_.modelName -eq $ModelName -or $_.displayName -eq $ModelName
    }) | Select-Object -First 1
    if (-not $model) { throw "Enabled model '$ModelName' was not found" }
    $model.id
}

function Invoke-Evaluation([string] $Profile, [string] $ModelId, [string] $OutputPath) {
    Write-Step "Running $Profile evaluation ($Repetitions repetitions)"
    $arguments = @(
        'bench/ai-evals/run-workflow-ai-evals.mjs',
        '--suite', $suitePath,
        '--base-url', $BaseUrl,
        '--model-id', $ModelId,
        '--repetitions', $Repetitions,
        '--profile', $Profile,
        '--output', $OutputPath
    )
    if ($CaseId) { $arguments += @('--case-id', $CaseId) }
    & node @arguments
    if ($LASTEXITCODE -notin @(0, 2)) {
        throw "$Profile evaluation failed with exit code $LASTEXITCODE"
    }
}

$baselinePath = if ($BaselineReport) {
    (Resolve-Path -LiteralPath $BaselineReport).Path
} else {
    Join-Path $resultDirectory 'baseline.json'
}
$enhancedPath = Join-Path $resultDirectory 'enhanced.json'
$comparisonPath = Join-Path $resultDirectory 'comparison.json'

if ($Arm -eq 'Enhanced' -and -not $BaselineReport) {
    throw '-BaselineReport is required when -Arm Enhanced is selected'
}

try {
    if (-not $SkipBuild) {
        Write-Step 'Building benchmark app image'
        & docker @compose build app
        if ($LASTEXITCODE -ne 0) { throw 'App image build failed' }
    }

    if ($Arm -in @('Both', 'Baseline')) {
        Set-Profile -Name 'baseline' -ToolCalling $false -Embedding $false `
            -StructuredOutput $false -RepairPasses 0
        $modelId = Resolve-ModelId
        Invoke-Evaluation -Profile 'baseline' -ModelId $modelId -OutputPath $baselinePath
    }

    if ($Arm -in @('Both', 'Enhanced')) {
        Set-Profile -Name 'enhanced' -ToolCalling $true -Embedding $true `
            -StructuredOutput $true -RepairPasses 2
        $modelId = Resolve-ModelId
        Invoke-Evaluation -Profile 'enhanced' -ModelId $modelId -OutputPath $enhancedPath
    }

    if ((Test-Path -LiteralPath $baselinePath) -and (Test-Path -LiteralPath $enhancedPath)) {
        Write-Step 'Comparing paired observations'
        & node 'bench/ai-evals/compare-workflow-ai-evals.mjs' `
            $baselinePath $enhancedPath $comparisonPath
        if ($LASTEXITCODE -ne 0) { throw 'A/B comparison failed' }
    }
} finally {
    Remove-Item Env:AI_AB_TOOL_CALLING_ENABLED -ErrorAction SilentlyContinue
    Remove-Item Env:AI_AB_EMBEDDING_ENABLED -ErrorAction SilentlyContinue
    Remove-Item Env:AI_AB_STRUCTURED_OUTPUT_ENABLED -ErrorAction SilentlyContinue
    Remove-Item Env:AI_AB_MAX_REPAIR_PASSES -ErrorAction SilentlyContinue
    Write-Step 'Restoring normal Voyager app profile'
    & docker compose up -d --force-recreate app
    Wait-AppReady
}

Write-Step "A/B benchmark arm complete: $resultDirectory"
