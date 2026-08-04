import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const [baselinePathArg, enhancedPathArg, outputPathArg] = process.argv.slice(2);
if (!baselinePathArg || !enhancedPathArg) {
  throw new Error('Usage: node compare-workflow-ai-evals.mjs <baseline.json> <enhanced.json> [output.json]');
}

const baselinePath = resolve(baselinePathArg);
const enhancedPath = resolve(enhancedPathArg);
const baseline = JSON.parse(await readFile(baselinePath, 'utf8'));
const enhanced = JSON.parse(await readFile(enhancedPath, 'utf8'));

if (baseline.suite?.sha256 !== enhanced.suite?.sha256) {
  throw new Error('Baseline and enhanced reports were produced from different suite contents.');
}
if (baseline.run?.model?.id !== enhanced.run?.model?.id) {
  throw new Error('Baseline and enhanced reports used different models.');
}

const baselineByKey = new Map(baseline.observations.map((observation) => [key(observation), observation]));
const enhancedByKey = new Map(enhanced.observations.map((observation) => [key(observation), observation]));
const pairedKeys = [...baselineByKey.keys()].filter((entry) => enhancedByKey.has(entry));
const pairs = pairedKeys.map((entry) => ({
  key: entry,
  baseline: validWorkflow(baselineByKey.get(entry)),
  enhanced: validWorkflow(enhancedByKey.get(entry)),
}));

const baselinePassed = pairs.filter((pair) => pair.baseline).length;
const enhancedPassed = pairs.filter((pair) => pair.enhanced).length;
const baselineOnly = pairs.filter((pair) => pair.baseline && !pair.enhanced).length;
const enhancedOnly = pairs.filter((pair) => !pair.baseline && pair.enhanced).length;
const baselineTokens = baseline.summary?.tokens ?? {};
const enhancedTokens = enhanced.summary?.tokens ?? {};
const totalTokenReductionRate = reduction(
  baselineTokens.totalTokens,
  enhancedTokens.totalTokens,
);
const inputTokenReductionRate = reduction(
  baselineTokens.inputTokens,
  enhancedTokens.inputTokens,
);

const comparison = {
  suiteId: baseline.suite?.id,
  suiteSha256: baseline.suite?.sha256,
  model: baseline.run?.model,
  pairedObservations: pairs.length,
  validation: {
    baseline: rateSummary(baselinePassed, pairs.length),
    enhanced: rateSummary(enhancedPassed, pairs.length),
    percentagePointLift: round((enhancedPassed - baselinePassed) * 100 / pairs.length, 2),
    rateRatio: baselinePassed === 0
      ? null : round(enhancedPassed / baselinePassed, 4),
    relativeLift: baselinePassed === 0
      ? null : round((enhancedPassed - baselinePassed) / baselinePassed, 4),
    discordantPairs: { baselineOnly, enhancedOnly },
    mcnemarExactTwoSidedPValue: exactMcNemar(baselineOnly, enhancedOnly),
  },
  tokens: {
    baseline: baselineTokens,
    enhanced: enhancedTokens,
    inputTokenReductionRate,
    totalTokenReductionRate,
    baselineTokensPerValidWorkflow: baselinePassed === 0
      ? null : round(baselineTokens.totalTokens / baselinePassed, 2),
    enhancedTokensPerValidWorkflow: enhancedPassed === 0
      ? null : round(enhancedTokens.totalTokens / enhancedPassed, 2),
    tokensPerValidWorkflowReductionRate: baselinePassed === 0 || enhancedPassed === 0
      ? null : reduction(
        baselineTokens.totalTokens / baselinePassed,
        enhancedTokens.totalTokens / enhancedPassed,
      ),
  },
  sourceReports: { baseline: baselinePath, enhanced: enhancedPath },
};

if (outputPathArg) {
  await writeFile(resolve(outputPathArg), `${JSON.stringify(comparison, null, 2)}\n`, 'utf8');
}

console.log(JSON.stringify(comparison, null, 2));

function key(observation) {
  return `${observation.caseId}#${observation.repetition}`;
}

function validWorkflow(observation) {
  return Boolean(observation?.workflowExpected
    && observation.response?.hasAsl
    && observation.metrics?.validation_clean?.passed
    && observation.metrics?.asl_structural_valid?.passed);
}

function rateSummary(passed, total) {
  return {
    passed,
    total,
    rate: total === 0 ? 0 : round(passed / total, 4),
    wilson95: wilson(passed, total),
  };
}

function wilson(passed, total) {
  if (total === 0) return { low: 0, high: 0 };
  const z = 1.959963984540054;
  const proportion = passed / total;
  const denominator = 1 + z * z / total;
  const center = (proportion + z * z / (2 * total)) / denominator;
  const margin = z * Math.sqrt(
    proportion * (1 - proportion) / total + z * z / (4 * total * total),
  ) / denominator;
  return { low: round(center - margin, 4), high: round(center + margin, 4) };
}

function exactMcNemar(leftOnly, rightOnly) {
  const discordant = leftOnly + rightOnly;
  if (discordant === 0) return 1;
  const tail = Math.min(leftOnly, rightOnly);
  let probability = 0;
  for (let index = 0; index <= tail; index += 1) {
    probability += combination(discordant, index) * (0.5 ** discordant);
  }
  return Number(Math.min(1, probability * 2).toPrecision(6));
}

function combination(n, k) {
  const selected = Math.min(k, n - k);
  let value = 1;
  for (let index = 1; index <= selected; index += 1) {
    value = value * (n - selected + index) / index;
  }
  return value;
}

function reduction(baselineValue, enhancedValue) {
  return !Number.isFinite(baselineValue) || baselineValue === 0
    ? null : round(1 - enhancedValue / baselineValue, 4);
}

function round(value, digits) {
  const scale = 10 ** digits;
  return Math.round(value * scale) / scale;
}
