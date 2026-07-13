// Lightweight, client-side semantic checks for ASL definitions.
//
// This is intentionally shallow: it does NOT evaluate JSONata expressions or
// enforce full Amazon States Language conformance. It catches the structural
// mistakes that are cheap to detect and most confusing when missed — dangling
// transitions, empty `Next`, states that need a transition, unreachable states,
// and placeholder Task resources. Anything deeper is left to server-side review.

type AnyState = Record<string, any>;

const NEEDS_NEXT_OR_END = new Set(['Task', 'Pass', 'Wait', 'Parallel', 'Map']);
// Scheme with an empty path, e.g. "voyager://" or "mcp://" — a builder default, not a real target.
const PLACEHOLDER_RESOURCE = /^[a-z][a-z0-9+.-]*:\/\/\/*$/i;

function transitionTargets(state: AnyState): string[] {
  const targets: string[] = [];
  if (typeof state?.Next === 'string') targets.push(state.Next);
  if (typeof state?.Default === 'string') targets.push(state.Default);
  if (Array.isArray(state?.Choices)) {
    for (const choice of state.Choices) {
      if (typeof choice?.Next === 'string') targets.push(choice.Next);
    }
  }
  if (Array.isArray(state?.Catch)) {
    for (const catcher of state.Catch) {
      if (typeof catcher?.Next === 'string') targets.push(catcher.Next);
    }
  }
  return targets;
}

function validateMachine(machine: any, context: string, issues: string[]) {
  const prefix = context ? `${context}: ` : '';

  const states: Record<string, AnyState> | null =
    machine && typeof machine.States === 'object' && machine.States !== null && !Array.isArray(machine.States)
      ? machine.States
      : null;

  if (!states || Object.keys(states).length === 0) {
    issues.push(`${prefix}States must be a non-empty object.`);
    return;
  }

  const stateNames = Object.keys(states);
  const startAt = machine.StartAt;
  const startAtValid = typeof startAt === 'string' && startAt in states;

  if (typeof startAt !== 'string' || startAt === '') {
    issues.push(`${prefix}StartAt is required.`);
  } else if (!startAtValid) {
    issues.push(`${prefix}StartAt "${startAt}" is not a defined state.`);
  }

  const checkTarget = (target: any, who: string) => {
    if (typeof target !== 'string' || target === '') {
      issues.push(`${prefix}${who} has no target state.`);
    } else if (!(target in states)) {
      issues.push(`${prefix}${who} points to "${target}", which does not exist.`);
    }
  };

  for (const [name, state] of Object.entries(states)) {
    const type = typeof state?.Type === 'string' ? state.Type : undefined;
    if (!type) {
      issues.push(`${prefix}State "${name}" is missing a Type.`);
      continue;
    }

    if (type === 'Choice') {
      const choices = Array.isArray(state.Choices) ? state.Choices : [];
      if (choices.length === 0) {
        issues.push(`${prefix}${name}: Choice has no rules.`);
      }
      choices.forEach((choice: AnyState, index: number) => {
        if (typeof choice?.Condition !== 'string' || choice.Condition === '') {
          issues.push(`${prefix}${name}: rule ${index + 1} is missing a Condition.`);
        }
        checkTarget(choice?.Next, `${name} rule ${index + 1} Next`);
      });
      if (state.Default !== undefined) {
        checkTarget(state.Default, `${name} Default`);
      }
    }

    if (NEEDS_NEXT_OR_END.has(type)) {
      const hasNext = typeof state.Next === 'string' && state.Next !== '';
      const hasEnd = state.End === true;
      if (hasNext && hasEnd) {
        issues.push(`${prefix}${name}: has both Next and "End": true — use exactly one.`);
      } else if (!hasNext && !hasEnd) {
        if (state.Next === '') {
          issues.push(`${prefix}${name}: Next has no target state.`);
        } else {
          issues.push(`${prefix}${name}: needs a Next transition or "End": true.`);
        }
      } else if (hasNext) {
        checkTarget(state.Next, `${name} Next`);
      }
    }

    if (Array.isArray(state.Catch)) {
      state.Catch.forEach((catcher: AnyState, index: number) => {
        checkTarget(catcher?.Next, `${name} Catch ${index + 1} Next`);
      });
    }

    if (type === 'Task') {
      const resource = state.Resource;
      if (typeof resource !== 'string' || resource === '') {
        issues.push(`${prefix}${name}: Task is missing a Resource.`);
      } else if (PLACEHOLDER_RESOURCE.test(resource)) {
        issues.push(`${prefix}${name}: Resource "${resource}" is a placeholder — choose a real target.`);
      }
    }

    if (type === 'Parallel' && Array.isArray(state.Branches)) {
      if (state.Branches.length === 0) {
        issues.push(`${prefix}${name}: Parallel has no branches.`);
      }
      state.Branches.forEach((branch: any, index: number) => {
        validateMachine(branch, `${prefix}${name} branch ${index + 1}`.replace(/: $/, ''), issues);
      });
    }

    if (type === 'Map' && state.ItemProcessor !== undefined) {
      validateMachine(state.ItemProcessor, `${prefix}${name} ItemProcessor`.replace(/: $/, ''), issues);
    }
  }

  // Reachability from StartAt.
  if (startAtValid) {
    const reachable = new Set<string>();
    const stack = [startAt as string];
    while (stack.length > 0) {
      const current = stack.pop() as string;
      if (reachable.has(current)) continue;
      reachable.add(current);
      for (const target of transitionTargets(states[current])) {
        if (target in states && !reachable.has(target)) {
          stack.push(target);
        }
      }
    }
    const unreachable = stateNames.filter((stateName) => !reachable.has(stateName));
    if (unreachable.length > 0) {
      issues.push(`${prefix}Unreachable states: ${unreachable.join(', ')}.`);
    }
  }
}

/**
 * Returns human-readable semantic issues for a parsed ASL definition.
 * Assumes the definition already parses as JSON with StartAt/States; returns
 * an empty array when the machine is structurally sound at this shallow level.
 */
export function collectAslIssues(definition: any): string[] {
  const issues: string[] = [];
  validateMachine(definition, '', issues);
  return issues;
}
