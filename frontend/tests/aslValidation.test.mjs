import assert from 'node:assert/strict';
import test from 'node:test';

import { collectAslIssues } from '../src/utils/aslValidation.ts';
import { isValidStateName } from '../src/components/workflow-create/stateBuilder.ts';
import {
  canvasPositionKey,
  filterCanvasPositionsForDefinition,
  getMachineAtPath,
  mergeCanvasPositionsForScope,
  updateMachineAtPath,
} from '../src/components/workflow-create/nestedMachine.ts';

const machine = (startAt, states) => ({ StartAt: startAt, States: states });

const validStateByType = {
  Task: { Type: 'Task', Resource: 'voyager://function/send-email', End: true },
  Pass: { Type: 'Pass', End: true },
  Wait: { Type: 'Wait', Seconds: 0, End: true },
  Parallel: {
    Type: 'Parallel',
    Branches: [machine('BranchEnd', { BranchEnd: { Type: 'Succeed' } })],
    End: true,
  },
  Map: {
    Type: 'Map',
    Items: [],
    ItemProcessor: machine('ItemEnd', { ItemEnd: { Type: 'Succeed' } }),
    End: true,
  },
  Choice: {
    Type: 'Choice',
    Choices: [{ Condition: '{% true %}', Next: 'Done' }],
  },
  Succeed: { Type: 'Succeed' },
  Fail: { Type: 'Fail', Error: 'Order.Invalid' },
};

test('any recognized state type may be the single StartAt target', () => {
  for (const [type, state] of Object.entries(validStateByType)) {
    const states = type === 'Choice'
      ? { Start: state, Done: { Type: 'Succeed' } }
      : { Start: state };
    assert.deepEqual(collectAslIssues(machine('Start', states)), [], type);
  }
});

test('builder applies the ASL 80 Unicode code-point state-name limit', () => {
  assert.equal(isValidStateName('🚀'.repeat(80)), true);
  assert.equal(isValidStateName('🚀'.repeat(81)), false);
  assert.equal(isValidStateName('   '), false);
});

test('only transition-capable states may use End or Next', () => {
  const definition = machine('ChoiceState', {
    ChoiceState: {
      Type: 'Choice',
      Choices: [{ Condition: '{% true %}', Next: 'Succeeded' }],
      End: true,
    },
    Succeeded: { Type: 'Succeed', Next: 'Failed' },
    Failed: { Type: 'Fail', End: true },
  });

  const issues = collectAslIssues(definition);
  assert.ok(issues.some((issue) => issue.includes('ChoiceState.End') && issue.includes('not allowed')));
  assert.ok(issues.some((issue) => issue.includes('Succeeded.Next') && issue.includes('not allowed')));
  assert.ok(issues.some((issue) => issue.includes('Failed.End') && issue.includes('not allowed')));
});

test('states with no reference are rejected as unreachable', () => {
  const issues = collectAslIssues(machine('First', {
    First: { Type: 'Pass', End: true },
    Orphan: { Type: 'Pass', End: true },
  }));

  assert.ok(issues.some((issue) => issue.includes('$.States.Orphan') && issue.includes('not reachable')));
});

test('reachable cycles must still have a terminating path', () => {
  const issues = collectAslIssues(machine('A', {
    A: { Type: 'Pass', Next: 'B' },
    B: { Type: 'Pass', Next: 'A' },
  }));

  assert.ok(issues.some((issue) => issue.includes('$.States.A') && issue.includes('cannot reach')));
  assert.ok(issues.some((issue) => issue.includes('$.States.B') && issue.includes('cannot reach')));
});

test('nested machine references cannot escape their own scope', () => {
  const issues = collectAslIssues(machine('Process', {
    Process: {
      Type: 'Map',
      Items: [],
      ItemProcessor: machine('Inside', {
        Inside: { Type: 'Pass', Next: 'Outside' },
      }),
      Next: 'Outside',
    },
    Outside: { Type: 'Succeed' },
  }));

  assert.ok(issues.some((issue) => issue.includes('ItemProcessor.States.Inside.Next') && issue.includes('same States object')));
});

test('JSONPath-only fields and invalid JSONata placement are rejected', () => {
  const issues = collectAslIssues(machine('Route', {
    Route: {
      Type: 'Choice',
      Choices: [{ Variable: '$.amount', NumericGreaterThan: 100, Next: 'Done' }],
    },
    Done: {
      Type: 'Pass',
      Parameters: { 'amount.$': '$.amount' },
      Output: '{% $states.result %}',
      End: true,
    },
  }));

  assert.ok(issues.some((issue) => issue.includes('Variable') && issue.includes('JSONPath Choice')));
  assert.ok(issues.some((issue) => issue.includes('Parameters') && issue.includes('JSONPath-only')));
  assert.ok(issues.some((issue) => issue.includes('Done.Output') && issue.includes('$states.result')));
});

test('Retry and Catch are state-specific and enforce wildcard ordering', () => {
  const issues = collectAslIssues(machine('InvalidRetryOwner', {
    InvalidRetryOwner: {
      Type: 'Pass',
      Retry: [{ ErrorEquals: ['States.ALL'] }],
      Next: 'Task',
    },
    Task: {
      Type: 'Task',
      Resource: 'voyager://function/process',
      Retry: [
        { ErrorEquals: ['States.ALL'] },
        { ErrorEquals: ['Order.Invalid'] },
      ],
      Catch: [{ ErrorEquals: ['States.ALL', 'Order.Invalid'], Next: 'Done' }],
      Next: 'Done',
    },
    Done: { Type: 'Succeed' },
  }));

  assert.ok(issues.some((issue) => issue.includes('InvalidRetryOwner.Retry') && issue.includes('not allowed')));
  assert.ok(issues.some((issue) => issue.includes('Task.Retry[0]') && issue.includes('must be last')));
  assert.ok(issues.some((issue) => issue.includes('Task.Catch[0].ErrorEquals') && issue.includes('appear alone')));
});

test('valid branching, retries, catches, and nested machines pass together', () => {
  const definition = machine('Route', {
    Route: {
      Type: 'Choice',
      Choices: [{ Condition: '{% $states.input.amount >= 1000 %}', Next: 'Process' }],
      Default: 'Rejected',
    },
    Process: {
      Type: 'Parallel',
      Arguments: { order: '{% $states.input %}' },
      Branches: [machine('Wait', {
        Wait: { Type: 'Wait', Seconds: 1, Next: 'BranchDone' },
        BranchDone: { Type: 'Succeed' },
      })],
      Retry: [{ ErrorEquals: ['States.Timeout'], MaxAttempts: 2 }],
      Catch: [{ ErrorEquals: ['States.ALL'], Next: 'Rejected', Output: '{% $states.errorOutput %}' }],
      Next: 'Accepted',
    },
    Accepted: { Type: 'Succeed', Output: '{% $states.input %}' },
    Rejected: { Type: 'Fail', Error: 'Order.Rejected' },
  });

  assert.deepEqual(collectAslIssues(definition), []);
});

test('nested machine updates stay inside the selected Parallel branch', () => {
  const definition = machine('Fan/Out', {
    'Fan/Out': {
      Type: 'Parallel',
      Branches: [
        machine('Shared', { Shared: { Type: 'Pass', End: true } }),
        machine('Shared', { Shared: { Type: 'Pass', End: true } }),
      ],
      End: true,
    },
  });
  const firstBranch = [{ kind: 'parallel', stateName: 'Fan/Out', branchIndex: 0 }];
  const replacement = machine('Changed', { Changed: { Type: 'Wait', Seconds: 1, End: true } });

  const updated = updateMachineAtPath(definition, firstBranch, replacement);

  assert.equal(getMachineAtPath(updated, firstBranch)?.StartAt, 'Changed');
  assert.equal(updated.States['Fan/Out'].Branches[1].StartAt, 'Shared');
  assert.equal(definition.States['Fan/Out'].Branches[0].StartAt, 'Shared');
});

test('canvas positions use distinct ASL pointers for nested scopes', () => {
  const definition = machine('Fan/Out', {
    'Fan/Out': {
      Type: 'Parallel',
      Branches: [
        machine('Shared', { Shared: { Type: 'Pass', End: true } }),
        machine('Shared', { Shared: { Type: 'Pass', End: true } }),
      ],
      Next: 'Each',
    },
    Each: {
      Type: 'Map',
      Items: [],
      ItemProcessor: machine('Handle~item', { 'Handle~item': { Type: 'Pass', End: true } }),
      End: true,
    },
  });
  const firstBranch = [{ kind: 'parallel', stateName: 'Fan/Out', branchIndex: 0 }];
  const secondBranch = [{ kind: 'parallel', stateName: 'Fan/Out', branchIndex: 1 }];
  const itemProcessor = [{ kind: 'map', stateName: 'Each' }];
  const stored = {
    'Fan/Out': { x: 10, y: 20 },
    [canvasPositionKey(firstBranch, 'Shared')]: { x: 30, y: 40 },
    [canvasPositionKey(secondBranch, 'Shared')]: { x: 50, y: 60 },
    [canvasPositionKey(itemProcessor, 'Handle~item')]: { x: 70, y: 80 },
    '@scope/States/Missing/Branches/0/States/Ghost': { x: 90, y: 100 },
  };

  assert.equal(
    canvasPositionKey(firstBranch, 'Shared'),
    '@scope/States/Fan~1Out/Branches/0/States/Shared',
  );
  assert.equal(
    canvasPositionKey(itemProcessor, 'Handle~item'),
    '@scope/States/Each/ItemProcessor/States/Handle~0item',
  );

  const movedFirstBranch = mergeCanvasPositionsForScope(stored, firstBranch, {
    Shared: { x: 300, y: 400 },
  });
  assert.deepEqual(movedFirstBranch[canvasPositionKey(firstBranch, 'Shared')], { x: 300, y: 400 });
  assert.deepEqual(movedFirstBranch[canvasPositionKey(secondBranch, 'Shared')], { x: 50, y: 60 });

  const filtered = filterCanvasPositionsForDefinition(definition, movedFirstBranch);
  assert.equal(filtered['@scope/States/Missing/Branches/0/States/Ghost'], undefined);
  assert.deepEqual(filtered[canvasPositionKey(itemProcessor, 'Handle~item')], { x: 70, y: 80 });
});
