// Frontend mirror of the scheduler's JSONata-only ASL validator.
//
// Keep this aligned with the validators under
// src/main/java/com/job/scheduler/workflow/asl/validation. The server remains
// authoritative (and parses JSONata with jsonata4java), while this module makes
// structural, graph, dialect, and expression-placement failures visible before
// a workflow can be saved.

type JsonObject = Record<string, unknown>;

const STATE_TYPES = new Set([
  'Task',
  'Pass',
  'Choice',
  'Wait',
  'Succeed',
  'Fail',
  'Parallel',
  'Map',
]);

const ROOT_FIELDS = new Set(['Comment', 'TimeoutSeconds', 'StartAt', 'States', 'QueryLanguage']);
const NEXT_OR_END_TYPES = new Set(['Task', 'Pass', 'Wait', 'Parallel', 'Map']);

const STATE_FIELDS: Record<string, Set<string>> = {
  Pass: new Set(['Type', 'Comment', 'Assign', 'Output', 'Next', 'End']),
  Task: new Set([
    'Type', 'Comment', 'Resource', 'Arguments', 'Assign', 'Output',
    'TimeoutSeconds', 'HeartbeatSeconds', 'Retry', 'Catch', 'Next', 'End',
  ]),
  Choice: new Set(['Type', 'Comment', 'Assign', 'Output', 'Choices', 'Default']),
  Wait: new Set(['Type', 'Comment', 'Seconds', 'Timestamp', 'Assign', 'Output', 'Next', 'End']),
  Parallel: new Set([
    'Type', 'Comment', 'Branches', 'Arguments', 'Assign', 'Output',
    'Retry', 'Catch', 'Next', 'End',
  ]),
  Map: new Set([
    'Type', 'Comment', 'ItemProcessor', 'Iterator', 'ItemReader', 'Items',
    'ItemSelector', 'ItemBatcher', 'ResultWriter', 'MaxConcurrency',
    'ToleratedFailurePercentage', 'ToleratedFailureCount', 'Assign', 'Output',
    'Retry', 'Catch', 'Next', 'End',
  ]),
  Succeed: new Set(['Type', 'Comment', 'Output']),
  Fail: new Set(['Type', 'Comment', 'Error', 'Cause']),
};

const CHOICE_RULE_FIELDS = new Set(['Condition', 'Next', 'Assign', 'Output']);
const RETRY_FIELDS = new Set([
  'ErrorEquals', 'IntervalSeconds', 'MaxAttempts', 'BackoffRate',
  'MaxDelaySeconds', 'JitterStrategy',
]);
const CATCH_FIELDS = new Set(['ErrorEquals', 'Next', 'Assign', 'Output']);

const JSONPATH_ONLY_FIELDS = new Set([
  'InputPath', 'OutputPath', 'Parameters', 'Result', 'ResultPath', 'ResultSelector',
  'ItemsPath', 'SecondsPath', 'TimestampPath', 'MaxConcurrencyPath', 'MaxItemsPath',
  'MaxItemsPerBatchPath', 'MaxInputBytesPerBatchPath', 'ToleratedFailureCountPath',
  'ToleratedFailurePercentagePath', 'ErrorPath', 'CausePath',
]);

const JSONPATH_CHOICE_FIELDS = new Set([
  'Variable', 'And', 'Or', 'Not', 'StringEquals', 'StringEqualsPath',
  'StringLessThan', 'StringLessThanPath', 'StringGreaterThan', 'StringGreaterThanPath',
  'StringLessThanEquals', 'StringLessThanEqualsPath', 'StringGreaterThanEquals',
  'StringGreaterThanEqualsPath', 'StringMatches', 'NumericEquals', 'NumericEqualsPath',
  'NumericLessThan', 'NumericLessThanPath', 'NumericGreaterThan', 'NumericGreaterThanPath',
  'NumericLessThanEquals', 'NumericLessThanEqualsPath', 'NumericGreaterThanEquals',
  'NumericGreaterThanEqualsPath', 'BooleanEquals', 'BooleanEqualsPath',
  'TimestampEquals', 'TimestampEqualsPath', 'TimestampLessThan', 'TimestampLessThanPath',
  'TimestampGreaterThan', 'TimestampGreaterThanPath', 'TimestampLessThanEquals',
  'TimestampLessThanEqualsPath', 'TimestampGreaterThanEquals',
  'TimestampGreaterThanEqualsPath', 'IsNull', 'IsPresent', 'IsNumeric', 'IsString',
  'IsBoolean', 'IsTimestamp',
]);

const RESERVED_ERRORS = new Set([
  'States.ALL',
  'States.Timeout',
  'States.TaskFailed',
  'States.Permissions',
  'States.BranchFailed',
  'States.NoChoiceMatched',
  'States.QueryEvaluationError',
]);

const UNSUPPORTED_MAP_FIELDS = new Set([
  'ItemReader',
  'ItemBatcher',
  'ResultWriter',
  'ToleratedFailureCount',
  'ToleratedFailurePercentage',
]);

const PLACEHOLDER_RESOURCE = /^[a-z][a-z0-9+.-]*:\/\/*$/i;
const URI = /^[A-Za-z][A-Za-z0-9+.-]*:[^\s]+$/;
const STATES_INTRINSIC = /States\.[A-Za-z][A-Za-z0-9_]*\s*\(/;
const VARIABLE_NAME = /^[\p{ID_Start}_][\p{ID_Continue}_]*$/u;
const RFC3339 = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/;

function isObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasOwn(object: JsonObject, field: string) {
  return Object.prototype.hasOwnProperty.call(object, field);
}

function isNonblankString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function codePointLength(value: string) {
  return [...value].length;
}

function addIssue(issues: string[], location: string, message: string) {
  issues.push(`${location}: ${message}`);
}

function isExpression(value: unknown): boolean {
  if (typeof value !== 'string') return false;
  const trimmed = value.trim();
  return trimmed.startsWith('{%') && trimmed.endsWith('%}');
}

function validateExpression(
  value: string,
  location: string,
  allowResult: boolean,
  allowErrorOutput: boolean,
  issues: string[],
) {
  const trimmed = value.trim();
  const startsExpression = trimmed.startsWith('{%');
  const endsExpression = trimmed.endsWith('%}');

  if (startsExpression !== endsExpression) {
    addIssue(issues, location, 'JSONata expressions must be delimited by {% and %}.');
    return;
  }
  if (!startsExpression) return;

  const expression = trimmed.slice(2, -2).trim();
  if (!expression) {
    addIssue(issues, location, 'JSONata expression must not be empty.');
    return;
  }
  if (!allowResult && expression.includes('$states.result')) {
    addIssue(issues, location, '$states.result is not available in this field.');
  }
  if (!allowErrorOutput && expression.includes('$states.errorOutput')) {
    addIssue(issues, location, '$states.errorOutput is available only inside a matching Catcher.');
  }
  if (STATES_INTRINSIC.test(expression)) {
    addIssue(issues, location, 'States.* intrinsic functions are not allowed in the JSONata dialect.');
  }
}

function validateRequiredExpression(
  value: unknown,
  location: string,
  allowResult: boolean,
  allowErrorOutput: boolean,
  issues: string[],
) {
  if (!isExpression(value)) {
    addIssue(issues, location, 'Must be a JSONata expression delimited by {% and %}.');
    return;
  }
  validateExpression(value as string, location, allowResult, allowErrorOutput, issues);
}

function validateJsonataValues(
  value: unknown,
  location: string,
  allowResult: boolean,
  allowErrorOutput: boolean,
  issues: string[],
) {
  if (typeof value === 'string') {
    validateExpression(value, location, allowResult, allowErrorOutput, issues);
    return;
  }
  if (Array.isArray(value)) {
    value.forEach((entry, index) => {
      validateJsonataValues(entry, `${location}[${index}]`, allowResult, allowErrorOutput, issues);
    });
    return;
  }
  if (!isObject(value)) return;

  for (const [field, entry] of Object.entries(value)) {
    if (field.endsWith('.$')) {
      addIssue(issues, `${location}.${field}`, 'Keys ending in .$ are not allowed in the JSONata dialect.');
    }
    validateJsonataValues(entry, `${location}.${field}`, allowResult, allowErrorOutput, issues);
  }
}

function validateAssign(
  assign: unknown,
  location: string,
  allowResult: boolean,
  allowErrorOutput: boolean,
  issues: string[],
) {
  if (assign === undefined) return;
  if (!isObject(assign)) {
    addIssue(issues, location, 'Assign must be a JSON object.');
    return;
  }

  for (const [variableName, value] of Object.entries(assign)) {
    const variableLocation = `${location}.${variableName}`;
    if (variableName === 'states') {
      addIssue(issues, variableLocation, 'The variable name states is reserved.');
    }
    if (!variableName || codePointLength(variableName) > 80 || !VARIABLE_NAME.test(variableName)) {
      addIssue(issues, variableLocation, 'Variable names must be Unicode identifiers of at most 80 characters.');
    }
    validateJsonataValues(value, variableLocation, allowResult, allowErrorOutput, issues);
  }
}

function validateAllowedFields(
  object: JsonObject,
  allowed: Set<string>,
  location: string,
  label: string,
  issues: string[],
) {
  for (const field of Object.keys(object)) {
    if (allowed.has(field)
      || field === 'QueryLanguage'
      || JSONPATH_ONLY_FIELDS.has(field)
      || field.endsWith('.$')) {
      continue;
    }
    addIssue(issues, `${location}.${field}`, `${field} is not allowed ${label}.`);
  }
}

function validateDialectFields(state: JsonObject, location: string, issues: string[]) {
  for (const field of Object.keys(state)) {
    if (field === 'QueryLanguage') {
      addIssue(issues, `${location}.QueryLanguage`, 'Per-state QueryLanguage overrides are not allowed.');
    } else if (JSONPATH_ONLY_FIELDS.has(field) || field.endsWith('.$')) {
      addIssue(issues, `${location}.${field}`, `${field} is JSONPath-only and is not allowed in the JSONata dialect.`);
    }
  }
}

function validateTransitionTarget(
  target: unknown,
  states: JsonObject,
  location: string,
  label: string,
  issues: string[],
) {
  if (!isNonblankString(target)) {
    addIssue(issues, location, `${label} must be a nonblank state name.`);
  } else if (!hasOwn(states, target)) {
    addIssue(issues, location, `${label} must name a state in the same States object; "${target}" does not exist here.`);
  }
}

function validateNextOrEnd(state: JsonObject, states: JsonObject, location: string, issues: string[]) {
  const hasNext = hasOwn(state, 'Next');
  const hasEnd = hasOwn(state, 'End');
  if (hasNext === hasEnd) {
    addIssue(issues, location, 'Must contain exactly one of Next or "End": true.');
  }
  if (hasNext) {
    validateTransitionTarget(state.Next, states, `${location}.Next`, 'Next', issues);
  }
  if (hasEnd && state.End !== true) {
    addIssue(issues, `${location}.End`, 'End must be the boolean value true.');
  }
}

function validatePositiveIntegerOrExpression(value: unknown, location: string, issues: string[]) {
  if (value === undefined) return;
  if (Number.isInteger(value) && (value as number) > 0) return;
  if (typeof value === 'string') {
    validateRequiredExpression(value, location, false, false, issues);
    return;
  }
  addIssue(issues, location, 'Must be a positive integer or a JSONata expression producing one.');
}

function validateNonNegativeIntegerOrExpression(
  value: unknown,
  location: string,
  label: string,
  issues: string[],
) {
  if (value === undefined) return;
  if (Number.isInteger(value) && (value as number) >= 0) return;
  if (typeof value === 'string') {
    validateRequiredExpression(value, location, false, false, issues);
    return;
  }
  addIssue(issues, location, `${label} must be a non-negative integer or a JSONata expression producing one.`);
}

function validateTimestamp(value: unknown, location: string, issues: string[]) {
  if (!isNonblankString(value)) {
    addIssue(issues, location, 'Timestamp must be an RFC 3339 string or a JSONata expression producing one.');
    return;
  }
  if (isExpression(value)) {
    validateExpression(value, location, false, false, issues);
    return;
  }
  if (value.includes('t') || value.endsWith('z')) {
    addIssue(issues, location, 'Timestamp must use uppercase T and uppercase Z.');
    return;
  }
  const match = RFC3339.exec(value);
  if (!match || Number.isNaN(Date.parse(value))) {
    addIssue(issues, location, 'Timestamp must be a valid RFC 3339 timestamp.');
    return;
  }

  const [, year, month, day, hour, minute, second] = match;
  const date = new Date(Date.UTC(Number(year), Number(month) - 1, Number(day)));
  if (date.getUTCFullYear() !== Number(year)
    || date.getUTCMonth() !== Number(month) - 1
    || date.getUTCDate() !== Number(day)
    || Number(hour) > 23
    || Number(minute) > 59
    || Number(second) > 59) {
    addIssue(issues, location, 'Timestamp must be a valid RFC 3339 timestamp.');
  }
}

function validateErrorEquals(value: unknown, location: string, issues: string[]) {
  if (!Array.isArray(value) || value.length === 0) {
    addIssue(issues, location, 'ErrorEquals must be a non-empty array of error names.');
    return false;
  }

  let matchesAll = false;
  value.forEach((errorName, index) => {
    if (!isNonblankString(errorName)) {
      addIssue(issues, `${location}[${index}]`, 'Error name must be a nonblank string.');
      return;
    }
    if (errorName === 'States.ALL') {
      matchesAll = true;
    } else if (errorName.startsWith('States.') && !RESERVED_ERRORS.has(errorName)) {
      addIssue(issues, `${location}[${index}]`, 'Unknown error names must not use the reserved States. prefix.');
    }
  });
  if (matchesAll && value.length !== 1) {
    addIssue(issues, location, 'States.ALL must appear alone in ErrorEquals.');
  }
  return matchesAll;
}

function validateRetry(value: unknown, location: string, issues: string[]) {
  if (value === undefined) return;
  if (!Array.isArray(value)) {
    addIssue(issues, location, 'Retry must be an array.');
    return;
  }
  if (value.length === 0) {
    addIssue(issues, location, 'Retry must contain at least one retrier.');
    return;
  }

  value.forEach((retrier, index) => {
    const retrierLocation = `${location}[${index}]`;
    if (!isObject(retrier)) {
      addIssue(issues, retrierLocation, 'Each Retry entry must be an object.');
      return;
    }
    validateAllowedFields(retrier, RETRY_FIELDS, retrierLocation, 'on a Retry entry', issues);
    const matchesAll = validateErrorEquals(retrier.ErrorEquals, `${retrierLocation}.ErrorEquals`, issues);
    if (matchesAll && index !== value.length - 1) {
      addIssue(issues, `${retrierLocation}.ErrorEquals`, 'A retrier matching States.ALL must be last.');
    }
    if (retrier.IntervalSeconds !== undefined
      && (typeof retrier.IntervalSeconds !== 'number'
        || !Number.isInteger(retrier.IntervalSeconds)
        || retrier.IntervalSeconds <= 0)) {
      addIssue(issues, `${retrierLocation}.IntervalSeconds`, 'IntervalSeconds must be a positive integer.');
    }
    if (retrier.MaxAttempts !== undefined
      && (typeof retrier.MaxAttempts !== 'number'
        || !Number.isInteger(retrier.MaxAttempts)
        || retrier.MaxAttempts < 0)) {
      addIssue(issues, `${retrierLocation}.MaxAttempts`, 'MaxAttempts must be a non-negative integer.');
    }
    if (retrier.BackoffRate !== undefined
      && (typeof retrier.BackoffRate !== 'number' || retrier.BackoffRate < 1)) {
      addIssue(issues, `${retrierLocation}.BackoffRate`, 'BackoffRate must be a number greater than or equal to 1.0.');
    }
    if (retrier.MaxDelaySeconds !== undefined
      && (typeof retrier.MaxDelaySeconds !== 'number'
        || !Number.isInteger(retrier.MaxDelaySeconds)
        || retrier.MaxDelaySeconds <= 0)) {
      addIssue(issues, `${retrierLocation}.MaxDelaySeconds`, 'MaxDelaySeconds must be a positive integer.');
    }
    if (retrier.JitterStrategy !== undefined && !isNonblankString(retrier.JitterStrategy)) {
      addIssue(issues, `${retrierLocation}.JitterStrategy`, 'JitterStrategy must be a nonblank string.');
    } else if (typeof retrier.JitterStrategy === 'string' && retrier.JitterStrategy !== 'FULL') {
      addIssue(issues, `${retrierLocation}.JitterStrategy`, 'Only FULL JitterStrategy is supported by this runtime.');
    }
  });
}

function validateCatch(value: unknown, states: JsonObject, location: string, issues: string[]) {
  if (value === undefined) return;
  if (!Array.isArray(value)) {
    addIssue(issues, location, 'Catch must be an array.');
    return;
  }
  if (value.length === 0) {
    addIssue(issues, location, 'Catch must contain at least one catcher.');
    return;
  }

  value.forEach((catcher, index) => {
    const catcherLocation = `${location}[${index}]`;
    if (!isObject(catcher)) {
      addIssue(issues, catcherLocation, 'Each Catch entry must be an object.');
      return;
    }
    validateAllowedFields(catcher, CATCH_FIELDS, catcherLocation, 'on a Catch entry', issues);
    const matchesAll = validateErrorEquals(catcher.ErrorEquals, `${catcherLocation}.ErrorEquals`, issues);
    if (matchesAll && index !== value.length - 1) {
      addIssue(issues, `${catcherLocation}.ErrorEquals`, 'A catcher matching States.ALL must be last.');
    }
    validateTransitionTarget(catcher.Next, states, `${catcherLocation}.Next`, 'Catcher Next', issues);
    validateAssign(catcher.Assign, `${catcherLocation}.Assign`, false, true, issues);
    validateJsonataValues(catcher.Output, `${catcherLocation}.Output`, false, true, issues);
  });
}

function validateChoiceRule(
  rule: unknown,
  states: JsonObject,
  location: string,
  issues: string[],
) {
  if (!isObject(rule)) {
    addIssue(issues, location, 'Choice rule must be a JSON object.');
    return;
  }

  for (const field of Object.keys(rule)) {
    if (JSONPATH_CHOICE_FIELDS.has(field)) {
      addIssue(issues, `${location}.${field}`, 'JSONPath Choice operators are not allowed in the JSONata dialect.');
    } else if (!CHOICE_RULE_FIELDS.has(field)) {
      addIssue(issues, `${location}.${field}`, `${field} is not allowed on a JSONata Choice rule.`);
    }
  }

  validateRequiredExpression(rule.Condition, `${location}.Condition`, false, false, issues);
  validateTransitionTarget(rule.Next, states, `${location}.Next`, 'Choice rule Next', issues);
  validateAssign(rule.Assign, `${location}.Assign`, false, false, issues);
  validateJsonataValues(rule.Output, `${location}.Output`, false, false, issues);
}

function collectAssignmentLocations(states: JsonObject, machineLocation: string) {
  const locations = new Map<string, string[]>();
  const collect = (assign: unknown, location: string) => {
    if (!isObject(assign)) return;
    for (const name of Object.keys(assign)) {
      const entries = locations.get(name) || [];
      entries.push(`${location}.${name}`);
      locations.set(name, entries);
    }
  };

  for (const [stateName, rawState] of Object.entries(states)) {
    if (!isObject(rawState)) continue;
    const stateLocation = `${machineLocation}.States.${stateName}`;
    collect(rawState.Assign, `${stateLocation}.Assign`);
    if (Array.isArray(rawState.Choices)) {
      rawState.Choices.forEach((choice: unknown, index: number) => {
        if (isObject(choice)) collect(choice.Assign, `${stateLocation}.Choices[${index}].Assign`);
      });
    }
    if (Array.isArray(rawState.Catch)) {
      rawState.Catch.forEach((catcher: unknown, index: number) => {
        if (isObject(catcher)) collect(catcher.Assign, `${stateLocation}.Catch[${index}].Assign`);
      });
    }
  }
  return locations;
}

function transitionTargets(state: unknown, states: JsonObject) {
  const targets = new Set<string>();
  if (!isObject(state)) return targets;
  const add = (value: unknown) => {
    if (typeof value === 'string' && hasOwn(states, value)) targets.add(value);
  };
  add(state.Next);
  add(state.Default);
  if (Array.isArray(state.Choices)) state.Choices.forEach((choice) => isObject(choice) && add(choice.Next));
  if (Array.isArray(state.Catch)) state.Catch.forEach((catcher) => isObject(catcher) && add(catcher.Next));
  return targets;
}

function validateGraph(
  startAt: string | null,
  states: JsonObject,
  location: string,
  issues: string[],
) {
  if (!startAt || !hasOwn(states, startAt)) return;

  const transitions = new Map<string, Set<string>>();
  for (const [name, state] of Object.entries(states)) {
    transitions.set(name, transitionTargets(state, states));
  }

  const reachable = new Set<string>();
  const pending = [startAt];
  while (pending.length > 0) {
    const current = pending.pop() as string;
    if (reachable.has(current)) continue;
    reachable.add(current);
    for (const target of transitions.get(current) || []) pending.push(target);
  }

  for (const name of Object.keys(states)) {
    if (!reachable.has(name)) {
      addIssue(issues, `${location}.States.${name}`, 'State is not reachable from StartAt. Connect it or remove it.');
    }
  }

  const terminalStates = new Set<string>();
  for (const [name, state] of Object.entries(states)) {
    if (!isObject(state)) continue;
    if (state.Type === 'Succeed'
      || state.Type === 'Fail'
      || state.End === true
      || (state.Type === 'Choice' && !hasOwn(state, 'Default'))) {
      terminalStates.add(name);
    }
  }

  const predecessors = new Map<string, Set<string>>();
  for (const name of Object.keys(states)) predecessors.set(name, new Set());
  for (const [source, targets] of transitions) {
    for (const target of targets) predecessors.get(target)?.add(source);
  }

  const canTerminate = new Set<string>();
  const terminalPending = [...terminalStates];
  while (terminalPending.length > 0) {
    const current = terminalPending.pop() as string;
    if (canTerminate.has(current)) continue;
    canTerminate.add(current);
    for (const predecessor of predecessors.get(current) || []) terminalPending.push(predecessor);
  }

  for (const name of reachable) {
    if (!canTerminate.has(name)) {
      addIssue(issues, `${location}.States.${name}`, 'State cannot reach a successful or failed terminal outcome.');
    }
  }
}

function validateState(
  stateName: string,
  rawState: unknown,
  states: JsonObject,
  machineLocation: string,
  visibleVariables: Set<string>,
  issues: string[],
) {
  const location = `${machineLocation}.States.${stateName}`;
  if (!isObject(rawState)) {
    addIssue(issues, location, 'State definition must be a JSON object.');
    return;
  }

  if (rawState.Comment !== undefined && typeof rawState.Comment !== 'string') {
    addIssue(issues, `${location}.Comment`, 'State Comment must be a string.');
  }
  validateDialectFields(rawState, location, issues);

  if (!isNonblankString(rawState.Type)) {
    addIssue(issues, `${location}.Type`, 'State Type must be a nonblank string.');
    return;
  }
  const type = rawState.Type;
  if (!STATE_TYPES.has(type)) {
    addIssue(issues, `${location}.Type`, `Unsupported ASL state Type: ${type}.`);
    return;
  }

  validateAllowedFields(rawState, STATE_FIELDS[type], location, `on a ${type} state`, issues);

  if (NEXT_OR_END_TYPES.has(type)) validateNextOrEnd(rawState, states, location, issues);

  switch (type) {
    case 'Task': {
      if (!isNonblankString(rawState.Resource) || !URI.test(rawState.Resource)) {
        addIssue(issues, `${location}.Resource`, 'Task Resource must be a nonblank URI.');
      } else if (PLACEHOLDER_RESOURCE.test(rawState.Resource)) {
        addIssue(issues, `${location}.Resource`, `Resource "${rawState.Resource}" is a placeholder; choose a real target.`);
      }
      validateJsonataValues(rawState.Arguments, `${location}.Arguments`, false, false, issues);
      validateAssign(rawState.Assign, `${location}.Assign`, true, false, issues);
      validateJsonataValues(rawState.Output, `${location}.Output`, true, false, issues);
      validatePositiveIntegerOrExpression(rawState.TimeoutSeconds, `${location}.TimeoutSeconds`, issues);
      validatePositiveIntegerOrExpression(rawState.HeartbeatSeconds, `${location}.HeartbeatSeconds`, issues);
      validateRetry(rawState.Retry, `${location}.Retry`, issues);
      validateCatch(rawState.Catch, states, `${location}.Catch`, issues);
      break;
    }
    case 'Pass':
      validateAssign(rawState.Assign, `${location}.Assign`, false, false, issues);
      validateJsonataValues(rawState.Output, `${location}.Output`, false, false, issues);
      break;
    case 'Choice': {
      validateAssign(rawState.Assign, `${location}.Assign`, false, false, issues);
      validateJsonataValues(rawState.Output, `${location}.Output`, false, false, issues);
      if (!Array.isArray(rawState.Choices) || rawState.Choices.length === 0) {
        addIssue(issues, `${location}.Choices`, 'Choices must be a non-empty array.');
      } else {
        rawState.Choices.forEach((rule: unknown, index: number) => {
          validateChoiceRule(rule, states, `${location}.Choices[${index}]`, issues);
        });
      }
      if (rawState.Default !== undefined) {
        validateTransitionTarget(rawState.Default, states, `${location}.Default`, 'Default', issues);
      }
      break;
    }
    case 'Wait': {
      validateAssign(rawState.Assign, `${location}.Assign`, false, false, issues);
      validateJsonataValues(rawState.Output, `${location}.Output`, false, false, issues);
      const hasSeconds = hasOwn(rawState, 'Seconds');
      const hasTimestamp = hasOwn(rawState, 'Timestamp');
      if (hasSeconds === hasTimestamp) {
        addIssue(issues, location, 'Wait state must contain exactly one of Seconds or Timestamp.');
      }
      if (hasSeconds) validateNonNegativeIntegerOrExpression(rawState.Seconds, `${location}.Seconds`, 'Seconds', issues);
      if (hasTimestamp) validateTimestamp(rawState.Timestamp, `${location}.Timestamp`, issues);
      break;
    }
    case 'Succeed':
      validateJsonataValues(rawState.Output, `${location}.Output`, false, false, issues);
      break;
    case 'Fail': {
      for (const field of ['Error', 'Cause']) {
        const value = rawState[field];
        if (value === undefined) continue;
        if (typeof value !== 'string') {
          addIssue(issues, `${location}.${field}`, `${field} must be a string or JSONata expression producing a string.`);
        } else {
          validateExpression(value, `${location}.${field}`, false, false, issues);
          if (!isExpression(value) && value.startsWith('States.') && !RESERVED_ERRORS.has(value)) {
            addIssue(issues, `${location}.${field}`, 'Unknown error names must not use the reserved States. prefix.');
          }
        }
      }
      break;
    }
    case 'Parallel': {
      validateJsonataValues(rawState.Arguments, `${location}.Arguments`, false, false, issues);
      validateAssign(rawState.Assign, `${location}.Assign`, true, false, issues);
      validateJsonataValues(rawState.Output, `${location}.Output`, true, false, issues);
      validateRetry(rawState.Retry, `${location}.Retry`, issues);
      validateCatch(rawState.Catch, states, `${location}.Catch`, issues);
      if (!Array.isArray(rawState.Branches) || rawState.Branches.length === 0) {
        addIssue(issues, `${location}.Branches`, 'Parallel Branches must be a non-empty array.');
      } else {
        rawState.Branches.forEach((branch: unknown, index: number) => {
          validateMachine(branch, `${location}.Branches[${index}]`, false, visibleVariables, issues);
        });
      }
      break;
    }
    case 'Map': {
      validateAssign(rawState.Assign, `${location}.Assign`, true, false, issues);
      validateJsonataValues(rawState.Output, `${location}.Output`, true, false, issues);
      validateRetry(rawState.Retry, `${location}.Retry`, issues);
      validateCatch(rawState.Catch, states, `${location}.Catch`, issues);

      if (rawState.ItemProcessor === undefined) {
        addIssue(issues, `${location}.ItemProcessor`, 'Map requires an ItemProcessor nested machine.');
      } else {
        if (isObject(rawState.ItemProcessor)
          && rawState.ItemProcessor.ProcessorConfig !== undefined
          && !isObject(rawState.ItemProcessor.ProcessorConfig)) {
          addIssue(issues, `${location}.ItemProcessor.ProcessorConfig`, 'ProcessorConfig must be a JSON object.');
        }
        validateMachine(rawState.ItemProcessor, `${location}.ItemProcessor`, true, visibleVariables, issues);
      }

      if (rawState.Items !== undefined) {
        if (typeof rawState.Items === 'string') {
          validateRequiredExpression(rawState.Items, `${location}.Items`, false, false, issues);
        } else if (!Array.isArray(rawState.Items)) {
          addIssue(issues, `${location}.Items`, 'Items must be an array or a JSONata expression producing an array.');
        }
      }
      validateJsonataValues(rawState.ItemSelector, `${location}.ItemSelector`, false, false, issues);
      validateNonNegativeIntegerOrExpression(rawState.MaxConcurrency, `${location}.MaxConcurrency`, 'MaxConcurrency', issues);

      if (hasOwn(rawState, 'Iterator')) {
        addIssue(issues, `${location}.Iterator`, 'Map must use ItemProcessor instead of deprecated Iterator.');
      }
      for (const field of UNSUPPORTED_MAP_FIELDS) {
        if (hasOwn(rawState, field)) {
          addIssue(issues, `${location}.${field}`, `Map ${field} is not supported by this runtime.`);
        }
      }
      const mode = isObject(rawState.ItemProcessor)
        && isObject(rawState.ItemProcessor.ProcessorConfig)
        ? rawState.ItemProcessor.ProcessorConfig.Mode
        : undefined;
      if (typeof mode === 'string' && mode !== 'INLINE') {
        addIssue(issues, `${location}.ItemProcessor.ProcessorConfig.Mode`, `ProcessorConfig Mode ${mode} is not supported; use INLINE.`);
      }
      break;
    }
  }
}

function validateMachine(
  machine: unknown,
  location: string,
  itemProcessor: boolean,
  outerVariables: Set<string>,
  issues: string[],
) {
  if (!isObject(machine)) {
    addIssue(issues, location, 'State machine definition must be a JSON object.');
    return;
  }

  for (const field of Object.keys(machine)) {
    const allowedProcessorField = itemProcessor && field === 'ProcessorConfig';
    if (!ROOT_FIELDS.has(field) && field !== 'Version' && !allowedProcessorField) {
      addIssue(issues, `${location}.${field}`, `Unknown state machine field: ${field}.`);
    }
  }
  if (machine.Comment !== undefined && typeof machine.Comment !== 'string') {
    addIssue(issues, `${location}.Comment`, 'Comment must be a string.');
  }
  if (machine.TimeoutSeconds !== undefined
    && (typeof machine.TimeoutSeconds !== 'number'
      || !Number.isInteger(machine.TimeoutSeconds)
      || machine.TimeoutSeconds <= 0)) {
    addIssue(issues, `${location}.TimeoutSeconds`, 'TimeoutSeconds must be a positive integer.');
  }
  if (machine.QueryLanguage !== undefined && machine.QueryLanguage !== 'JSONata') {
    addIssue(issues, `${location}.QueryLanguage`, 'QueryLanguage must be JSONata when provided.');
  }
  if (machine.Version !== undefined) {
    addIssue(issues, `${location}.Version`, 'Version is omitted in the scheduler ASL dialect.');
  }

  const startAt = isNonblankString(machine.StartAt) ? machine.StartAt : null;
  if (!startAt) addIssue(issues, `${location}.StartAt`, 'StartAt must be a nonblank string.');

  if (!isObject(machine.States)) {
    addIssue(issues, `${location}.States`, 'States must be a JSON object.');
    return;
  }
  const states = machine.States;
  if (Object.keys(states).length === 0) {
    addIssue(issues, `${location}.States`, 'States must contain at least one state.');
    return;
  }
  if (startAt && !hasOwn(states, startAt)) {
    addIssue(issues, `${location}.StartAt`, `StartAt must name a state in States; "${startAt}" does not exist here.`);
  }

  const assignmentLocations = collectAssignmentLocations(states, location);
  for (const [variableName, variableLocations] of assignmentLocations) {
    if (!outerVariables.has(variableName)) continue;
    variableLocations.forEach((variableLocation) => {
      addIssue(issues, variableLocation, `Nested scope cannot assign outer variable: ${variableName}.`);
    });
  }
  const visibleVariables = new Set([...outerVariables, ...assignmentLocations.keys()]);

  for (const [stateName, state] of Object.entries(states)) {
    const stateLocation = `${location}.States.${stateName}`;
    if (!stateName.trim()) addIssue(issues, stateLocation, 'State name must not be blank.');
    if (codePointLength(stateName) > 80) {
      addIssue(issues, stateLocation, 'State name must not exceed 80 Unicode characters.');
    }
    validateState(stateName, state, states, location, visibleVariables, issues);
  }

  validateGraph(startAt, states, location, issues);
}

/**
 * Returns actionable frontend ASL/JSONata validation issues.
 *
 * Any recognized state type may be selected by StartAt. Task, Pass, Wait,
 * Parallel, and Map use exactly one of Next/End; Choice, Succeed, and Fail use
 * neither. Validation is recursive for Parallel branches and Map processors.
 */
export function collectAslIssues(definition: unknown): string[] {
  const issues: string[] = [];
  validateMachine(definition, '$', false, new Set(), issues);
  return [...new Set(issues)];
}
