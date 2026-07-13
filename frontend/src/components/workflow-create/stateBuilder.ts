export type AslState = Record<string, any>;

export type AslDefinition = {
  StartAt?: string;
  States?: Record<string, AslState>;
  [key: string]: any;
};

export const STATE_TYPES = ['Task', 'Pass', 'Choice', 'Wait', 'Parallel', 'Map', 'Succeed', 'Fail'] as const;

export type StateType = (typeof STATE_TYPES)[number];

export const STATE_TYPE_DESCRIPTIONS: Record<StateType, string> = {
  Task: 'Invoke a resource such as voyager://webhook, voyager://namespace/name or mcp://',
  Pass: 'Forward input to output, optionally reshaping it',
  Choice: 'Route to the first branch whose condition is true',
  Wait: 'Pause for a duration or until a timestamp',
  Parallel: 'Run branches concurrently and collect their outputs',
  Map: 'Run a processor once per item of an array',
  Succeed: 'Terminate the workflow successfully',
  Fail: 'Terminate the workflow with an error',
};

const TERMINAL_TYPES: StateType[] = ['Choice', 'Succeed', 'Fail'];

export function stateTypeOf(state: AslState | undefined): StateType {
  const type = state?.Type;
  return STATE_TYPES.includes(type) ? type : 'Task';
}

export function supportsNextOrEnd(type: StateType) {
  return !TERMINAL_TYPES.includes(type);
}

export function supportsOutput(type: StateType) {
  return type !== 'Fail';
}

export function supportsAssign(type: StateType) {
  return type !== 'Succeed' && type !== 'Fail';
}

export function supportsRetryCatch(type: StateType) {
  return type === 'Task' || type === 'Parallel' || type === 'Map';
}

export function createStateTemplate(type: StateType): AslState {
  switch (type) {
    case 'Task':
      return { Type: 'Task', Resource: 'voyager://', End: true };
    case 'Pass':
      return { Type: 'Pass', End: true };
    case 'Choice':
      return { Type: 'Choice', Choices: [{ Condition: '{% true %}', Next: '' }] };
    case 'Wait':
      return { Type: 'Wait', Seconds: 60, End: true };
    case 'Parallel':
      return {
        Type: 'Parallel',
        Branches: [
          {
            StartAt: 'Branch1',
            States: { Branch1: { Type: 'Pass', End: true } },
          },
        ],
        End: true,
      };
    case 'Map':
      return {
        Type: 'Map',
        ItemProcessor: {
          StartAt: 'ProcessItem',
          States: { ProcessItem: { Type: 'Pass', End: true } },
        },
        End: true,
      };
    case 'Succeed':
      return { Type: 'Succeed' };
    case 'Fail':
      return { Type: 'Fail' };
  }
}

export function uniqueStateName(states: Record<string, AslState>, base: string) {
  if (!(base in states)) return base;
  let index = 2;
  while (`${base}${index}` in states) {
    index += 1;
  }
  return `${base}${index}`;
}

export function isValidStateName(name: string) {
  return name.trim().length > 0 && name.length <= 80;
}

function replaceTargets(state: AslState, replace: (target: string) => string): AslState {
  const next: AslState = { ...state };
  if (typeof next.Next === 'string') next.Next = replace(next.Next);
  if (typeof next.Default === 'string') next.Default = replace(next.Default);
  if (Array.isArray(next.Choices)) {
    next.Choices = next.Choices.map((choice: any) => (
      choice && typeof choice.Next === 'string' ? { ...choice, Next: replace(choice.Next) } : choice
    ));
  }
  if (Array.isArray(next.Catch)) {
    next.Catch = next.Catch.map((catcher: any) => (
      catcher && typeof catcher.Next === 'string' ? { ...catcher, Next: replace(catcher.Next) } : catcher
    ));
  }
  return next;
}

/** Rename a state, updating StartAt and every top-level transition that targets it. */
export function renameState(definition: AslDefinition, oldName: string, newName: string): AslDefinition {
  const states = definition.States || {};
  if (!(oldName in states) || newName in states || !isValidStateName(newName)) {
    return definition;
  }

  const replace = (target: string) => (target === oldName ? newName : target);
  const nextStates: Record<string, AslState> = {};
  for (const [name, state] of Object.entries(states)) {
    nextStates[name === oldName ? newName : name] = replaceTargets(state, replace);
  }

  return {
    ...definition,
    StartAt: definition.StartAt === oldName ? newName : definition.StartAt,
    States: nextStates,
  };
}

export function deleteState(definition: AslDefinition, name: string): AslDefinition {
  const states = { ...(definition.States || {}) };
  delete states[name];
  const remaining = Object.keys(states);
  const nextStates: Record<string, AslState> = {};

  for (const [stateName, state] of Object.entries(states)) {
    const nextState: AslState = { ...state };
    const type = stateTypeOf(nextState);

    if (nextState.Next === name) {
      delete nextState.Next;
      if (supportsNextOrEnd(type)) {
        nextState.End = true;
      }
    }

    if (nextState.Default === name) {
      delete nextState.Default;
    }

    if (Array.isArray(nextState.Choices)) {
      nextState.Choices = nextState.Choices.filter((choice: any) => choice?.Next !== name);
    }

    if (Array.isArray(nextState.Catch)) {
      const catchers = nextState.Catch.filter((catcher: any) => catcher?.Next !== name);
      if (catchers.length > 0) {
        nextState.Catch = catchers;
      } else {
        delete nextState.Catch;
      }
    }

    nextStates[stateName] = nextState;
  }

  const nextDefinition: AslDefinition = {
    ...definition,
    States: nextStates,
  };

  if (definition.StartAt === name) {
    if (remaining[0]) {
      nextDefinition.StartAt = remaining[0];
    } else {
      nextDefinition.StartAt = '';
    }
  }

  return nextDefinition;
}

export function updateState(definition: AslDefinition, name: string, state: AslState): AslDefinition {
  return {
    ...definition,
    States: { ...(definition.States || {}), [name]: state },
  };
}

export type ConnectStatesResult = {
  definition: AslDefinition;
  changed: boolean;
  message?: string;
};

export type TransitionKind = 'next' | 'choice' | 'default' | 'catch';

export type TransitionRef = {
  sourceName: string;
  targetName: string;
  kind: TransitionKind;
  index?: number;
};

export function connectStates(definition: AslDefinition, sourceName: string, targetName: string): ConnectStatesResult {
  const states = definition.States || {};
  const source = states[sourceName];
  const target = states[targetName];
  if (!source || !target || sourceName === targetName) {
    return { definition, changed: false, message: 'Pick two different states to connect.' };
  }

  const sourceType = stateTypeOf(source);
  if (sourceType === 'Succeed' || sourceType === 'Fail') {
    return { definition, changed: false, message: `${sourceType} states cannot have outgoing transitions.` };
  }

  if (sourceType === 'Choice') {
    const choices = Array.isArray(source.Choices) ? [...source.Choices] : [];
    const alreadyConnected = source.Default === targetName || choices.some((choice: any) => choice?.Next === targetName);
    if (alreadyConnected) {
      return { definition, changed: false, message: 'Those states are already connected.' };
    }

    const emptyChoiceIndex = choices.findIndex((choice: any) => !choice?.Next);
    let nextState: AslState;
    let message = 'Added Choice condition branch. Edit the condition in the inspector.';
    if (emptyChoiceIndex >= 0) {
      choices[emptyChoiceIndex] = {
        ...(choices[emptyChoiceIndex] || {}),
        Condition: choices[emptyChoiceIndex]?.Condition || '{% true %}',
        Next: targetName,
      };
      nextState = { ...source, Choices: choices };
    } else {
      nextState = {
        ...source,
        Choices: [...choices, { Condition: '{% true %}', Next: targetName }],
      };
      message = 'Added Choice condition branch. Edit the condition in the inspector. Use Default in the inspector for fallback routing.';
    }

    return {
      definition: updateState(definition, sourceName, nextState),
      changed: true,
      message,
    };
  }

  if (!supportsNextOrEnd(sourceType)) {
    return { definition, changed: false, message: `${sourceType} states cannot use Next transitions.` };
  }

  if (source.Next === targetName) {
    return { definition, changed: false, message: 'Those states are already connected.' };
  }

  const nextState: AslState = { ...source, Next: targetName };
  delete nextState.End;
  return {
    definition: updateState(definition, sourceName, nextState),
    changed: true,
    message: 'Connected states.',
  };
}

export function disconnectStates(definition: AslDefinition, transition: TransitionRef): ConnectStatesResult {
  const states = definition.States || {};
  const source = states[transition.sourceName];
  if (!source || !(transition.targetName in states)) {
    return { definition, changed: false, message: 'That transition no longer exists.' };
  }

  if (transition.kind === 'next') {
    if (source.Next !== transition.targetName) {
      return { definition, changed: false, message: 'That transition no longer exists.' };
    }
    const nextState: AslState = { ...source, End: true };
    delete nextState.Next;
    return {
      definition: updateState(definition, transition.sourceName, nextState),
      changed: true,
      message: 'Disconnected transition. Source state now ends the workflow.',
    };
  }

  if (transition.kind === 'default') {
    if (source.Default !== transition.targetName) {
      return { definition, changed: false, message: 'That default transition no longer exists.' };
    }
    const nextState: AslState = { ...source };
    delete nextState.Default;
    return {
      definition: updateState(definition, transition.sourceName, nextState),
      changed: true,
      message: 'Removed Choice default transition.',
    };
  }

  if (transition.kind === 'choice') {
    const choices = Array.isArray(source.Choices) ? [...source.Choices] : [];
    const index = transition.index ?? choices.findIndex((choice: any) => choice?.Next === transition.targetName);
    if (index < 0 || choices[index]?.Next !== transition.targetName) {
      return { definition, changed: false, message: 'That Choice branch no longer exists.' };
    }
    choices.splice(index, 1);
    return {
      definition: updateState(definition, transition.sourceName, { ...source, Choices: choices }),
      changed: true,
      message: 'Removed Choice condition branch.',
    };
  }

  const catchers = Array.isArray(source.Catch) ? [...source.Catch] : [];
  const index = transition.index ?? catchers.findIndex((catcher: any) => catcher?.Next === transition.targetName);
  if (index < 0 || catchers[index]?.Next !== transition.targetName) {
    return { definition, changed: false, message: 'That catcher transition no longer exists.' };
  }
  catchers.splice(index, 1);
  const nextState: AslState = { ...source };
  if (catchers.length > 0) {
    nextState.Catch = catchers;
  } else {
    delete nextState.Catch;
  }
  return {
    definition: updateState(definition, transition.sourceName, nextState),
    changed: true,
    message: 'Removed catcher transition.',
  };
}

export function setChoiceDefaultTransition(definition: AslDefinition, transition: TransitionRef): ConnectStatesResult {
  const states = definition.States || {};
  const source = states[transition.sourceName];
  if (!source || stateTypeOf(source) !== 'Choice' || !(transition.targetName in states)) {
    return { definition, changed: false, message: 'Only Choice transitions can be made default.' };
  }
  if (source.Default === transition.targetName) {
    return { definition, changed: false, message: 'This target is already the Choice default.' };
  }

  const choices = Array.isArray(source.Choices)
    ? source.Choices.filter((choice: any, index: number) => (
      index !== transition.index && choice?.Next !== transition.targetName
    ))
    : [];

  return {
    definition: updateState(definition, transition.sourceName, {
      ...source,
      Choices: choices,
      Default: transition.targetName,
    }),
    changed: true,
    message: 'Set as Choice default. Fallback routing is now explicit.',
  };
}

export function addState(definition: AslDefinition, type: StateType): { definition: AslDefinition; name: string } {
  const states = definition.States || {};
  const name = uniqueStateName(states, `New${type}`);
  const next: AslDefinition = {
    ...definition,
    StartAt: definition.StartAt || name,
    States: { ...states, [name]: createStateTemplate(type) },
  };
  return { definition: next, name };
}

/** Every state name referenced by transitions out of the given state. */
export function transitionTargets(state: AslState): string[] {
  const targets: string[] = [];
  if (typeof state.Next === 'string' && state.Next) targets.push(state.Next);
  if (typeof state.Default === 'string' && state.Default) targets.push(state.Default);
  for (const choice of Array.isArray(state.Choices) ? state.Choices : []) {
    if (choice && typeof choice.Next === 'string' && choice.Next) targets.push(choice.Next);
  }
  for (const catcher of Array.isArray(state.Catch) ? state.Catch : []) {
    if (catcher && typeof catcher.Next === 'string' && catcher.Next) targets.push(catcher.Next);
  }
  return targets;
}

/** Map of state name -> transition targets that do not exist in the machine. */
export function danglingTargets(definition: AslDefinition): Record<string, string[]> {
  const states = definition.States || {};
  const result: Record<string, string[]> = {};
  for (const [name, state] of Object.entries(states)) {
    const missing = [...new Set(transitionTargets(state))].filter((target) => !(target in states));
    if (missing.length > 0) {
      result[name] = missing;
    }
  }
  return result;
}
