import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const argumentsMap = parseArguments(process.argv.slice(2));
const suitePath = resolve(
  argumentsMap.suite
    ?? resolve(scriptDirectory, '..', '..', 'src', 'main', 'resources', 'ai-evals', 'workflow-ai-v1.json'),
);
const baseUrl = (argumentsMap['base-url'] ?? 'http://localhost:8081').replace(/\/+$/, '');
const suiteText = await readFile(suitePath, 'utf8');
const suite = JSON.parse(suiteText);
const selectedCases = argumentsMap['case-id']
  ? suite.cases.filter((testCase) => testCase.id === argumentsMap['case-id'])
  : suite.cases;
if (selectedCases.length === 0) {
  throw new Error(`Suite case '${argumentsMap['case-id']}' was not found.`);
}
const repetitions = positiveInteger(argumentsMap.repetitions) ?? suite.repetitions ?? 1;
const outputPath = resolve(
  argumentsMap.output
    ?? resolve(scriptDirectory, 'results', `${suite.id}-${fileTimestamp()}.json`),
);

const models = await requestJson(`${baseUrl}/app/v1/ai/models`);
const model = selectModel(models, argumentsMap['model-id']);
const observations = [];

console.log(`Voyager AI eval ${suite.id}`);
console.log(`Model: ${model.displayName} (${model.modelName})`);
console.log(`Cases: ${selectedCases.length} x ${repetitions}`);

for (let repetition = 1; repetition <= repetitions; repetition += 1) {
  for (const testCase of selectedCases) {
    const label = `${testCase.id} [${repetition}/${repetitions}]`;
    process.stdout.write(`- ${label} ... `);
    const observation = await runCase(testCase, model.id, repetition);
    observations.push(observation);
    console.log(observation.passed ? `PASS (${observation.latencyMs} ms)` : 'FAIL');
  }
}

const metrics = aggregateMetrics(observations);
const gates = evaluateGates(metrics, suite.qualityGates ?? {});
const report = {
  suite: {
    id: suite.id,
    description: suite.description,
    path: suitePath,
    sha256: createHash('sha256').update(suiteText).digest('hex'),
  },
  run: {
    startedAt: new Date(
      Math.min(...observations.map((observation) => Date.parse(observation.startedAt))),
    ).toISOString(),
    finishedAt: new Date().toISOString(),
    baseUrl,
    profile: argumentsMap.profile ?? null,
    repetitions,
    model: {
      id: model.id,
      displayName: model.displayName,
      modelName: model.modelName,
      providerType: model.providerType,
    },
  },
  summary: {
    passedCases: observations.filter((observation) => observation.passed).length,
    totalCases: observations.length,
    casePassRate: ratio(
      observations.filter((observation) => observation.passed).length,
      observations.length,
    ),
    latencyMs: latencySummary(observations.map((observation) => observation.latencyMs)),
    workflowValidation: workflowValidationSummary(observations),
    tokens: tokenSummary(observations),
    qualityGatesPassed: gates.every((gate) => gate.passed),
  },
  metrics,
  gates,
  observations,
};

await mkdir(dirname(outputPath), { recursive: true });
await writeFile(outputPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
printSummary(report, outputPath);
process.exitCode = report.summary.qualityGatesPassed ? 0 : 2;

async function runCase(testCase, modelConfigId, repetition) {
  const startedAt = new Date().toISOString();
  const started = Date.now();
  let conversationId = null;
  try {
    const response = await requestJson(`${baseUrl}/app/v1/workflow-ai/conversations`, {
      method: 'POST',
      body: JSON.stringify({
        instruction: `${suite.instructionPrefix ?? ''}${testCase.instruction}`,
        modelConfigId,
        userDateTime: new Date().toISOString(),
      }),
    });
    conversationId = response.conversationId;
    const metricResults = commonMetrics(response);

    if (testCase.category === 'general_chat') {
      Object.assign(metricResults, gradeGeneralChat(response));
    } else if (testCase.category === 'asl') {
      Object.assign(metricResults, gradeAsl(response));
    } else if (testCase.category === 'mcp') {
      Object.assign(metricResults, gradeMcp(response));
    } else if (testCase.category === 'function') {
      Object.assign(metricResults, gradeFunction(response));
    } else if (testCase.category === 'safety') {
      Object.assign(metricResults, gradeSafety(response), gradeMcp(response));
    } else if (testCase.category === 'retry') {
      Object.assign(metricResults, gradeGeneralChat(response));
      const originalMessageId = response.assistantMessage?.id;
      if (!originalMessageId) {
        metricResults.retry_supersession = failure('Initial assistant message was missing.');
      } else {
        const retry = await requestJson(
          `${baseUrl}/app/v1/workflow-ai/messages/${encodeURIComponent(originalMessageId)}/regenerate`,
          {
            method: 'POST',
            body: JSON.stringify({ modelConfigId }),
          },
        );
        metricResults.retry_supersession = retry.assistantMessage?.regeneratedFromMessageId
          === originalMessageId
          ? success()
          : failure('Regenerated reply did not supersede the original message.');
      }
    }

    const requestedMetrics = ['response_contract', 'validation_clean', ...testCase.metrics];
    const applicable = Object.fromEntries(
      requestedMetrics.map((metric) => [
        metric,
        metricResults[metric] ?? failure(`No grader implemented for ${metric}.`),
      ]),
    );
    return {
      caseId: testCase.id,
      category: testCase.category,
      workflowExpected: testCase.workflowExpected ?? testCase.category === 'asl',
      repetition,
      startedAt,
      latencyMs: Date.now() - started,
      passed: Object.values(applicable).every((result) => result.passed),
      metrics: applicable,
      response: summarizeResponse(response),
    };
  } catch (error) {
    return {
      caseId: testCase.id,
      category: testCase.category,
      workflowExpected: testCase.workflowExpected ?? testCase.category === 'asl',
      repetition,
      startedAt,
      latencyMs: Date.now() - started,
      passed: false,
      metrics: {
        response_contract: failure(error instanceof Error ? error.message : String(error)),
      },
      error: error instanceof Error ? error.message : String(error),
    };
  } finally {
    if (conversationId && argumentsMap['keep-conversations'] !== 'true') {
      await fetch(
        `${baseUrl}/app/v1/workflow-ai/conversations/${encodeURIComponent(conversationId)}`,
        { method: 'DELETE' },
      ).catch(() => undefined);
    }
  }
}

function commonMetrics(response) {
  const contractValid = typeof response?.conversationId === 'string'
    && typeof response?.stage === 'string'
    && typeof response?.message === 'string'
    && response.message.trim().length > 0;
  return {
    response_contract: contractValid
      ? success()
      : failure('Required response fields were missing.'),
    validation_clean: Array.isArray(response?.validationIssues)
      && response.validationIssues.length === 0
      ? success()
      : failure(`${response?.validationIssues?.length ?? 'unknown'} validation issue(s).`),
  };
}

function gradeGeneralChat(response) {
  const noArtifacts = !response.aslDefinition
    && !response.resourcePlan
    && !response.draftWorkflowPayload
    && !response.finalPlan;
  const deflects = /\b(workflow name|name (?:for|of) (?:the|your) workflow|what workflow|build(?:ing)? your workflow|create (?:a|the) workflow)\b/i
    .test(response.message ?? '');
  return {
    general_chat_mode: response.stage === 'COLLECTING_WORKFLOW_DETAILS' && noArtifacts
      ? success()
      : failure(`Expected chat-only collecting stage; received ${response.stage}.`),
    general_chat_no_workflow_deflection: !deflects
      ? success()
      : failure('Reply unnecessarily redirected the user into workflow creation.'),
  };
}

function gradeAsl(response) {
  const definition = response.aslDefinition;
  return {
    asl_present: definition ? success() : failure('No aslDefinition was returned.'),
    asl_structural_valid: definition && structurallyValidAsl(definition)
      ? success()
      : failure('ASL was missing required structure or contained JSONPath-only fields.'),
  };
}

function gradeMcp(response) {
  const requirements = response.resourcePlan?.mcpRequirements ?? [];
  const requirementValid = requirements.some(
    (requirement) => typeof requirement?.capability === 'string'
      && requirement.capability.trim().length > 0,
  );
  const mcpTaskPresent = collectResources(response.aslDefinition)
    .some((resource) => resource.startsWith('voyager://mcp/'));
  return {
    mcp_classification: requirementValid || mcpTaskPresent
      ? success()
      : failure('Neither a concrete MCP requirement nor an MCP Task was produced.'),
  };
}

function gradeFunction(response) {
  const functions = response.resourcePlan?.functions ?? [];
  const proposalValid = functions.some(
    (fn) => typeof fn?.name === 'string'
      && fn.name.trim().length > 0
      && typeof fn?.sourceCode === 'string'
      && fn.sourceCode.trim().length > 0,
  );
  const functionTaskPresent = collectResources(response.aslDefinition)
    .some((resource) => resource.startsWith('voyager://function/'));
  return {
    function_classification: proposalValid || functionTaskPresent
      ? success()
      : failure('Neither a complete function proposal nor a function Task was produced.'),
    secret_guard_compliance: unsafeFunctionSources(functions).length === 0
      ? success()
      : failure('A proposed function contained a placeholder or likely embedded credential.'),
  };
}

function gradeSafety(response) {
  const functions = response.resourcePlan?.functions ?? [];
  return {
    secret_guard_compliance: unsafeFunctionSources(functions).length === 0
      ? success()
      : failure('A proposed function contained a placeholder or likely embedded credential.'),
  };
}

function structurallyValidAsl(definition) {
  if (!definition || typeof definition !== 'object' || Array.isArray(definition)) return false;
  if (typeof definition.StartAt !== 'string' || !definition.StartAt.trim()) return false;
  if (!definition.States || typeof definition.States !== 'object'
      || Array.isArray(definition.States) || Object.keys(definition.States).length === 0) return false;
  if (!(definition.StartAt in definition.States)) return false;
  const forbidden = new Set([
    'InputPath', 'OutputPath', 'Parameters', 'Result', 'ResultPath',
    'ResultSelector', 'ItemsPath',
  ]);
  let valid = true;
  walk(definition, (key) => {
    if (forbidden.has(key) || key.endsWith('.$')) valid = false;
  });
  return valid;
}

function collectResources(definition) {
  const resources = [];
  walk(definition, (key, value) => {
    if (key === 'Resource' && typeof value === 'string') resources.push(value);
  });
  return resources;
}

function unsafeFunctionSources(functions) {
  const unsafe = /YOUR[_\s-]*(?:API[_\s-]*KEY|TOKEN|SECRET|PASSWORD)|REPLACE[_\s-]*ME|<\s*(?:API[_\s-]*KEY|TOKEN|SECRET|PASSWORD)\s*>|\bBearer\s+[A-Za-z0-9._~+/=-]{12,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|\b(?:sk-|gh[pousr]_)[A-Za-z0-9_-]{16,}/i;
  return functions.filter((fn) => unsafe.test(fn?.sourceCode ?? ''));
}

function walk(value, visitor) {
  if (!value || typeof value !== 'object') return;
  if (Array.isArray(value)) {
    value.forEach((entry) => walk(entry, visitor));
    return;
  }
  Object.entries(value).forEach(([key, entry]) => {
    visitor(key, entry);
    walk(entry, visitor);
  });
}

function aggregateMetrics(observations) {
  const buckets = new Map();
  for (const observation of observations) {
    for (const [name, result] of Object.entries(observation.metrics)) {
      const bucket = buckets.get(name) ?? { passed: 0, total: 0, failures: [] };
      bucket.total += 1;
      if (result.passed) bucket.passed += 1;
      else bucket.failures.push({ caseId: observation.caseId, detail: result.detail });
      buckets.set(name, bucket);
    }
  }
  return Object.fromEntries([...buckets.entries()].map(([name, bucket]) => [
    name,
    {
      passed: bucket.passed,
      total: bucket.total,
      rate: ratio(bucket.passed, bucket.total),
      failures: bucket.failures,
    },
  ]));
}

function evaluateGates(metrics, qualityGates) {
  return Object.entries(qualityGates).map(([metric, minimum]) => {
    const actual = metrics[metric]?.rate ?? null;
    return {
      metric,
      minimum,
      actual,
      passed: actual !== null && actual >= minimum,
    };
  });
}

function summarizeResponse(response) {
  const message = response.assistantMessage ?? {};
  const telemetry = message.toolTelemetry ?? {};
  return {
    conversationId: response.conversationId,
    stage: response.stage,
    message: response.message,
    validationIssueCount: response.validationIssues?.length ?? null,
    validationIssues: response.validationIssues ?? [],
    hasAsl: Boolean(response.aslDefinition),
    proposedFunctionCount: response.resourcePlan?.functions?.length ?? 0,
    proposedMcpCount: response.resourcePlan?.mcpRequirements?.length ?? 0,
    inputTokens: message.inputTokens ?? null,
    outputTokens: message.outputTokens ?? null,
    totalTokens: message.totalTokens ?? null,
    modelCalls: telemetry.modelCalls ?? null,
    toolLoopUsed: telemetry.toolLoopUsed ?? false,
    promptCatalogTokensPerCall: telemetry.promptCatalogTokensPerCall ?? 0,
    toolSchemaTokensPerCall: telemetry.toolSchemaTokensPerCall ?? 0,
    estimatedNetInputTokensSaved: telemetry.estimatedNetInputTokensSaved ?? 0,
  };
}

function workflowValidationSummary(observations) {
  const workflows = observations.filter((observation) => observation.workflowExpected);
  const passed = workflows.filter((observation) =>
    observation.response?.hasAsl
      && observation.metrics?.validation_clean?.passed
      && (observation.metrics?.asl_structural_valid?.passed ?? true)).length;
  return {
    passed,
    total: workflows.length,
    rate: ratio(passed, workflows.length),
  };
}

function tokenSummary(observations) {
  const measured = observations.filter((observation) =>
    Number.isFinite(observation.response?.inputTokens)
      && Number.isFinite(observation.response?.outputTokens)
      && Number.isFinite(observation.response?.totalTokens));
  const inputTokens = sum(measured.map((observation) => observation.response.inputTokens));
  const outputTokens = sum(measured.map((observation) => observation.response.outputTokens));
  const totalTokens = sum(measured.map((observation) => observation.response.totalTokens));
  const estimatedNetInputTokensSaved = sum(measured.map(
    (observation) => observation.response.estimatedNetInputTokensSaved ?? 0));
  const estimatedBaselineInputTokens = inputTokens + estimatedNetInputTokensSaved;
  const estimatedBaselineTotalTokens = totalTokens + estimatedNetInputTokensSaved;
  const validWorkflows = workflowValidationSummary(observations).passed;
  return {
    measuredTurns: measured.length,
    inputTokens,
    outputTokens,
    totalTokens,
    estimatedNetInputTokensSaved,
    estimatedBaselineInputTokens,
    estimatedBaselineTotalTokens,
    estimatedInputReductionRate: ratio(
      estimatedNetInputTokensSaved, estimatedBaselineInputTokens),
    estimatedTotalReductionRate: ratio(
      estimatedNetInputTokensSaved, estimatedBaselineTotalTokens),
    tokensPerValidWorkflow: validWorkflows === 0
      ? null : Number((totalTokens / validWorkflows).toFixed(2)),
  };
}

function sum(values) {
  return values.reduce((total, value) => total + value, 0);
}

function latencySummary(values) {
  const sorted = [...values].sort((left, right) => left - right);
  return {
    min: sorted[0] ?? null,
    p50: percentile(sorted, 0.5),
    p95: percentile(sorted, 0.95),
    max: sorted.at(-1) ?? null,
  };
}

function percentile(sorted, fraction) {
  if (sorted.length === 0) return null;
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * fraction) - 1)];
}

function ratio(numerator, denominator) {
  return denominator === 0 ? 0 : Number((numerator / denominator).toFixed(4));
}

function success() {
  return { passed: true };
}

function failure(detail) {
  return { passed: false, detail };
}

async function requestJson(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers ?? {}),
    },
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}: ${text.slice(0, 500)}`);
  }
  return text ? JSON.parse(text) : null;
}

function selectModel(models, requestedId) {
  const enabled = models.filter((model) => model.enabled !== false);
  const selected = requestedId
    ? enabled.find((model) => model.id === requestedId)
    : enabled.find((model) => model.defaultModel) ?? enabled[0];
  if (!selected) {
    throw new Error(requestedId
      ? `Enabled model ${requestedId} was not found.`
      : 'Voyager has no enabled AI model.');
  }
  return selected;
}

function positiveInteger(value) {
  if (value === undefined) return null;
  const parsed = Number.parseInt(value, 10);
  if (!Number.isInteger(parsed) || parsed < 1) {
    throw new Error(`Expected a positive integer, received ${value}.`);
  }
  return parsed;
}

function parseArguments(values) {
  const result = {};
  for (let index = 0; index < values.length; index += 1) {
    const argument = values[index];
    if (!argument.startsWith('--')) throw new Error(`Unexpected argument: ${argument}`);
    const [rawKey, inlineValue] = argument.slice(2).split('=', 2);
    if (inlineValue !== undefined) result[rawKey] = inlineValue;
    else if (values[index + 1] && !values[index + 1].startsWith('--')) result[rawKey] = values[++index];
    else result[rawKey] = 'true';
  }
  return result;
}

function fileTimestamp() {
  return new Date().toISOString().replace(/[:.]/g, '-');
}

function printSummary(report, path) {
  console.log('\nMetrics');
  for (const [name, metric] of Object.entries(report.metrics)) {
    console.log(`  ${name.padEnd(36)} ${(metric.rate * 100).toFixed(1).padStart(6)}% (${metric.passed}/${metric.total})`);
  }
  console.log('\nQuality gates');
  for (const gate of report.gates) {
    const actual = gate.actual === null ? 'n/a' : `${(gate.actual * 100).toFixed(1)}%`;
    console.log(`  ${gate.passed ? 'PASS' : 'FAIL'} ${gate.metric}: ${actual} >= ${(gate.minimum * 100).toFixed(1)}%`);
  }
  console.log(`\nCase pass rate: ${(report.summary.casePassRate * 100).toFixed(1)}%`);
  const validation = report.summary.workflowValidation;
  console.log(`Workflow validation rate: ${(validation.rate * 100).toFixed(1)}% (${validation.passed}/${validation.total})`);
  const tokens = report.summary.tokens;
  console.log(`Tokens input/output/total: ${tokens.inputTokens}/${tokens.outputTokens}/${tokens.totalTokens}`);
  console.log(`Estimated net input tokens saved: ${tokens.estimatedNetInputTokensSaved}`);
  console.log(`Latency p50/p95: ${report.summary.latencyMs.p50}/${report.summary.latencyMs.p95} ms`);
  console.log(`Result: ${path}`);
}
