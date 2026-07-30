import { expect, test, type APIRequestContext, type Page } from '@playwright/test';

const createdWorkflowIds = new Set<string>();
const createdDraftIds = new Set<string>();

const manualChoiceDefinition = {
  StartAt: 'Route',
  States: {
    Route: {
      Type: 'Choice',
      Choices: [
        {
          Condition: '{% $states.input.approved = true %}',
          Next: 'Approved',
        },
      ],
      Default: 'Rejected',
    },
    Approved: {
      Type: 'Succeed',
      Output: "{% { 'decision': 'approved' } %}",
    },
    Rejected: {
      Type: 'Fail',
      Error: 'Approval.Rejected',
      Cause: 'The workflow input was not approved.',
    },
  },
};

const cancelableWaitDefinition = {
  StartAt: 'HoldForReview',
  States: {
    HoldForReview: {
      Type: 'Wait',
      Seconds: 60,
      End: true,
    },
  },
};

const recurringPassDefinition = {
  StartAt: 'ScheduledPass',
  States: {
    ScheduledPass: {
      Type: 'Pass',
      Output: "{% { 'source': 'scheduler-e2e' } %}",
      End: true,
    },
  },
};

const invalidValidationDefinition = {
  StartAt: 'MissingStart',
  States: {
    Route: {
      Type: 'Choice',
      Choices: [],
    },
    Orphan: {
      Type: 'Pass',
      End: true,
    },
  },
};

const allStatesDefinition = {
  StartAt: 'Prepare',
  States: {
    Prepare: { Type: 'Pass', Next: 'CallMissing' },
    CallMissing: {
      Type: 'Task',
      Resource: 'voyager://e2e/missing-resource',
      Arguments: { requestId: '{% $states.input.requestId %}' },
      Retry: [{ ErrorEquals: ['States.TaskFailed'], MaxAttempts: 0 }],
      Catch: [{
        ErrorEquals: ['States.ALL'],
        Output: '{% $states.input %}',
        Next: 'Route',
      }],
      Next: 'Route',
    },
    Route: {
      Type: 'Choice',
      Choices: [{
        Condition: '{% $states.input.shouldFail = true %}',
        Next: 'ExpectedFailure',
      }],
      Default: 'FanOut',
    },
    ExpectedFailure: {
      Type: 'Fail',
      Error: 'E2E.ExpectedFailure',
      Cause: 'The failure route was requested by the test input.',
    },
    FanOut: {
      Type: 'Parallel',
      Branches: [
        {
          StartAt: 'BranchPass',
          States: {
            BranchPass: {
              Type: 'Pass',
              Output: "{% { 'branch': 'pass' } %}",
              End: true,
            },
          },
        },
        {
          StartAt: 'BranchDone',
          States: {
            BranchDone: {
              Type: 'Succeed',
              Output: "{% { 'branch': 'succeed' } %}",
            },
          },
        },
      ],
      Next: 'MapItems',
    },
    MapItems: {
      Type: 'Map',
      Items: '{% [1, 2] %}',
      ItemSelector: {
        value: '{% $states.context.Map.Item.Value %}',
        index: '{% $states.context.Map.Item.Index %}',
      },
      MaxConcurrency: 2,
      ItemProcessor: {
        ProcessorConfig: { Mode: 'INLINE' },
        StartAt: 'DoubleItem',
        States: {
          DoubleItem: {
            Type: 'Pass',
            Output: "{% { 'value': $states.input.value * 2, 'index': $states.input.index } %}",
            End: true,
          },
        },
      },
      Next: 'Pause',
    },
    Pause: { Type: 'Wait', Seconds: 0, Next: 'Done' },
    Done: {
      Type: 'Succeed',
      Output: "{% { 'result': 'ok', 'items': $states.input } %}",
    },
  },
};

const activeExecutionStatuses = new Set([
  'PENDING',
  'QUEUED',
  'RUNNING',
  'WAITING',
]);

function uniqueName(prefix: string) {
  return `${prefix} ${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
}

async function openManualCreator(page: Page) {
  await page.goto('/');
  await page.getByTestId('workflow-mode-manual').click();
  await expect(page.getByTestId('workflow-editor-builder')).toBeVisible();
}

async function saveWorkflow(page: Page, name: string) {
  const showPanelButton = page.getByTestId('workflow-show-panel');
  if (await showPanelButton.isVisible()) {
    await showPanelButton.click();
  }
  await page.getByLabel('Name', { exact: true }).fill(name);
  const saveButton = page.getByTestId('workflow-save');
  await expect(saveButton).toBeEnabled();
  await saveButton.click();
  await expect(page).toHaveURL(/\/workflows\/[0-9a-f-]+$/);
  const pathParts = new URL(page.url()).pathname.split('/');
  const workflowId = pathParts[pathParts.length - 1];
  expect(workflowId).toMatch(/^[0-9a-f-]{36}$/);
  createdWorkflowIds.add(workflowId);
  return workflowId;
}

async function verifyWorkflowInList(
  page: Page,
  workflowId: string,
  name: string,
) {
  await page.goto('/workflows');
  const workflowCard = page.getByTestId(`workflow-card-${workflowId}`);
  await expect(workflowCard).toBeVisible();
  await expect(workflowCard).toContainText(name);
}

async function triggerWorkflowThroughUi(
  page: Page,
  workflowId: string,
  input: unknown,
) {
  await page.goto(`/workflows/${workflowId}`);
  await page.getByTestId('workflow-tab-executions').click();
  const triggerButton = page.getByTestId('execution-trigger-run');
  await expect(triggerButton).toBeEnabled();
  await triggerButton.click();
  await page.getByTestId('execution-input-json').locator('textarea').fill(JSON.stringify(input, null, 2));
  await page.getByTestId('execution-submit-run').click();
  await expect(page.getByTestId('execution-trigger-dialog')).toBeHidden();
  await expect(page.getByTestId('execution-selected-status')).toBeVisible();
}

async function executionCount(request: APIRequestContext, workflowId: string) {
  const response = await request.get(
    `/app/v1/workflows/${workflowId}/executions?page=0&size=100`,
  );
  expect(response).toBeOK();
  const page = await response.json() as { totalElements: number };
  return page.totalElements;
}

function nodeTransform(style: string | null) {
  return style?.match(/translate\([^)]*\)/)?.[0] || '';
}

test.beforeEach(async ({ request }) => {
  const backendProbe = await request.get('/app/v1/workflows?page=0&size=1');
  expect(
    backendProbe,
    'The workflow backend must be reachable through the frontend proxy. '
      + 'Start it on E2E_BACKEND_URL (default http://127.0.0.1:8081).',
  ).toBeOK();
});

test.afterEach(async ({ request }) => {
  for (const workflowId of createdWorkflowIds) {
    const executionsResponse = await request.get(
      `/app/v1/workflows/${workflowId}/executions?page=0&size=100`,
    );
    if (executionsResponse.ok()) {
      const executions = await executionsResponse.json() as {
        content: Array<{ id: string; status: string }>;
      };
      for (const execution of executions.content) {
        if (activeExecutionStatuses.has(execution.status)) {
          await request.post(
            `/app/v1/workflows/${workflowId}/executions/${execution.id}/cancel`,
            { data: {} },
          );
        }
      }
    }
    await request.post(`/app/v1/workflows/${workflowId}/archive`, { data: {} });
  }
  createdWorkflowIds.clear();

  for (const draftId of createdDraftIds) {
    await request.delete(`/app/v1/workflow-ai/drafts/${draftId}`);
  }
  createdDraftIds.clear();
});

test('manual mode creates a draft only after the first state is added', async ({ page, request }) => {
  const beforeResponse = await request.get('/app/v1/workflow-ai/drafts');
  expect(beforeResponse).toBeOK();
  const beforeDrafts = await beforeResponse.json() as Array<{ id: string }>;

  await page.goto('/');
  await page.getByTestId('workflow-mode-manual').click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByTestId('workflow-editor-builder')).toBeVisible();

  const unchangedResponse = await request.get('/app/v1/workflow-ai/drafts');
  expect(unchangedResponse).toBeOK();
  expect((await unchangedResponse.json() as Array<{ id: string }>)).toHaveLength(beforeDrafts.length);

  await page.getByTestId('workflow-add-state-succeed').click();
  await expect(page).toHaveURL(/\/draft\/[0-9a-f-]{36}$/);
  const draftId = new URL(page.url()).pathname.split('/').at(-1)!;
  createdDraftIds.add(draftId);

  const draftResponse = await request.get(`/app/v1/workflow-ai/drafts/${draftId}`);
  expect(draftResponse).toBeOK();
  const draft = await draftResponse.json() as { workspaceDefinitionText: string };
  expect(draft.workspaceDefinitionText).toContain('NewSucceed');
});

test('a draft can be named from the sidebar and found by that name after refresh', async ({ page, request }) => {
  const name = uniqueName('Invoice approval draft');
  await page.goto('/');
  await page.getByTestId('workflow-mode-manual').click();
  await page.getByTestId('workflow-add-state-succeed').click();
  await expect(page).toHaveURL(/\/draft\/[0-9a-f-]{36}$/);
  const draftId = new URL(page.url()).pathname.split('/').at(-1)!;
  createdDraftIds.add(draftId);

  await page.getByRole('button', { name: 'Expand sidebar' }).click();
  await page.getByTestId(`rename-draft-${draftId}`).click();
  await page.getByRole('textbox', { name: 'Draft name' }).fill(name);
  await page.getByRole('button', { name: 'Save name' }).click();
  await expect(page.getByRole('button', { name: `Open draft ${name}` })).toBeVisible();

  const renamedResponse = await request.get(`/app/v1/workflow-ai/drafts/${draftId}`);
  expect(renamedResponse).toBeOK();
  expect((await renamedResponse.json() as { name: string }).name).toBe(name);

  await page.reload();
  await page.getByRole('button', { name: 'Open drafts' }).click();
  await page.getByRole('searchbox', { name: 'Search drafts' }).fill(name);
  await expect(page.getByRole('button', { name: `Open draft ${name}` })).toBeVisible();
});

test('a saved draft stays linked and clearly saves later edits as revisions', async ({ page, request }) => {
  const name = uniqueName('Linked manual draft');
  await openManualCreator(page);
  await page.getByTestId('workflow-add-state-succeed').click();
  await expect(page).toHaveURL(/\/draft\/[0-9a-f-]{36}$/);
  const draftId = new URL(page.url()).pathname.split('/').at(-1)!;
  createdDraftIds.add(draftId);

  const workflowId = await saveWorkflow(page, name);
  const savedDraftResponse = await request.get(`/app/v1/workflow-ai/drafts/${draftId}`);
  expect(savedDraftResponse).toBeOK();
  expect((await savedDraftResponse.json() as { workflowId: string }).workflowId).toBe(workflowId);

  await page.goto(`/draft/${draftId}`);
  const saveButton = page.getByTestId('workflow-save');
  await expect(saveButton).toContainText('Save new revision');
  await expect(page.getByTestId('workflow-save-revision-note'))
    .toContainText('Creates a new immutable revision');

  await page.getByTestId('workflow-template-file').setInputFiles({
    name: 'linked-draft-revision.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify({
      StartAt: 'Done',
      States: {
        Done: {
          Type: 'Succeed',
          Output: "{% { 'revision': 2 } %}",
        },
      },
    }, null, 2)),
  });
  await expect(saveButton).toBeEnabled();
  await saveButton.click();
  await expect(page).toHaveURL(new RegExp(`/workflows/${workflowId}$`));

  const revisionsResponse = await request.get(`/app/v1/workflows/${workflowId}/revisions`);
  expect(revisionsResponse).toBeOK();
  expect(await revisionsResponse.json()).toHaveLength(2);
});

test('top ASL status lists every validation issue on hover', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('workflow-template-file').setInputFiles({
    name: 'invalid-workflow.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(invalidValidationDefinition, null, 2)),
  });

  const status = page.getByTestId('workflow-definition-status');
  await expect(status).toContainText(/\d+ ASL validation issues/);
  await expect(status).not.toHaveAttribute('title');
  const issueCount = Number((await status.textContent())?.match(/\d+/)?.[0]);
  expect(issueCount).toBeGreaterThan(1);

  await status.hover();
  const tooltip = page.getByTestId('workflow-definition-issues-tooltip');
  await expect(tooltip).toBeVisible();
  await expect(tooltip.getByRole('listitem')).toHaveCount(issueCount);
  await expect(tooltip).toContainText('$.StartAt');
  await expect(tooltip).toContainText('$.States.Route.Choices');
});

test('builder exposes state-specific controls for every supported ASL state', async ({ page }) => {
  await openManualCreator(page);

  const states = [
    { type: 'task', name: 'NewTask', detail: 'Task', errorHandling: true },
    { type: 'pass', name: 'NewPass', detail: 'Data flow', errorHandling: false },
    { type: 'choice', name: 'NewChoice', detail: 'Choice rules', errorHandling: false },
    { type: 'wait', name: 'NewWait', detail: 'Wait', errorHandling: false },
    { type: 'parallel', name: 'NewParallel', detail: 'Parallel branches', errorHandling: true },
    { type: 'map', name: 'NewMap', detail: 'Map', errorHandling: true },
    { type: 'succeed', name: 'NewSucceed', detail: 'Data flow', errorHandling: false },
    { type: 'fail', name: 'NewFail', detail: 'Failure', errorHandling: false },
  ];

  for (const state of states) {
    await page.getByTestId(`workflow-add-state-${state.type}`).click();
    const stateCard = page.getByTestId(`workflow-state-${state.name}`);
    await expect(stateCard).toBeVisible();
    await stateCard.click();

    const editor = page.getByTestId(`workflow-state-editor-${state.type}`);
    await expect(editor).toBeVisible();
    await expect(editor.getByText(state.detail, { exact: true }).first()).toBeVisible();
    if (state.errorHandling) {
      await expect(editor.getByTestId('workflow-error-handling')).toBeAttached();
    } else {
      await expect(editor.getByTestId('workflow-error-handling')).toHaveCount(0);
    }
  }
});

test('builder creation persists its canvas, appears in the list, revisions, and executes', async ({ page }) => {
  const name = uniqueName('E2E Builder Workflow');
  await openManualCreator(page);
  await page.getByTestId('workflow-add-state-pass').click();
  await expect(page.getByTestId('workflow-definition-status'))
    .toHaveAttribute('title', 'Frontend ASL checks pass');

  const showPanelButton = page.getByTestId('workflow-show-panel');
  if (await showPanelButton.isVisible()) await showPanelButton.click();
  await page.getByLabel('Name', { exact: true }).fill(name);

  const builderNode = page.locator('.react-flow__node[data-id="NewPass"]');
  await expect(builderNode).toBeVisible();
  const initialTransform = nodeTransform(await builderNode.getAttribute('style'));
  const nodeBox = await builderNode.boundingBox();
  expect(nodeBox).not.toBeNull();
  await page.mouse.move(nodeBox!.x + nodeBox!.width / 2, nodeBox!.y + nodeBox!.height / 2);
  await page.mouse.down();
  await page.mouse.move(nodeBox!.x + nodeBox!.width / 2 + 160, nodeBox!.y + nodeBox!.height / 2 + 90, {
    steps: 12,
  });
  await page.mouse.up();
  await expect.poll(async () => nodeTransform(await builderNode.getAttribute('style')))
    .not.toBe(initialTransform);
  const persistedTransform = nodeTransform(await builderNode.getAttribute('style'));
  expect(persistedTransform).not.toBe('');

  const workflowId = await saveWorkflow(page, name);

  await page.getByTestId('workflow-settings-open').click();
  await expect(page.getByTestId('workflow-settings-status')).toHaveText('ACTIVE');
  await expect(page.getByText('Manual workflows run on demand')).toBeVisible();
  await page.getByLabel('Close workflow settings').click();
  await expect(page.getByTestId('workflow-activate-schedule')).toHaveCount(0);

  await verifyWorkflowInList(page, workflowId, name);

  await page.goto(`/workflows/${workflowId}`);
  await page.getByTestId('workflow-edit-revision').click();
  await expect(page).toHaveURL(new RegExp(
    `/workflows/${workflowId}/revisions/1/edit$`,
  ));

  const revisionNode = page.locator('.react-flow__node[data-id="NewPass"]');
  await expect(revisionNode).toBeVisible();
  await expect.poll(async () => nodeTransform(await revisionNode.getAttribute('style')))
    .toBe(persistedTransform);

  await page.getByTestId('workflow-state-NewPass').click();
  await page.getByText('Data flow', { exact: true }).click();
  await page.getByRole('textbox', { name: 'Output', exact: true }).fill(
    "{% { 'revision': 2, 'source': $states.input.source } %}",
  );
  await expect(page.getByTestId('workflow-definition-status'))
    .toHaveAttribute('title', 'Frontend ASL checks pass');
  await expect(page.getByTestId('workflow-save')).toBeEnabled();
  await page.getByTestId('workflow-save').click();
  await expect(page).toHaveURL(new RegExp(`/workflows/${workflowId}$`));

  await page.getByTestId('workflow-revision-history').click();
  await expect(page.getByTestId('workflow-revision-count')).toHaveText('2 revisions');
  const revisionCards = page.locator('[data-revision-active]');
  await expect(revisionCards).toHaveCount(2);
  await expect(revisionCards.first()).toContainText('Rev 2');
  await expect(revisionCards.first()).toContainText('Active');
  await expect(revisionCards.first()).toHaveAttribute('data-revision-active', 'true');

  await triggerWorkflowThroughUi(page, workflowId, {
    source: 'builder-e2e',
  });
  await expect(page.getByTestId('execution-selected-status')).toContainText('SUCCEEDED');
  await expect(page.locator('[data-state-name="NewPass"]')).toContainText('SUCCEEDED');
  await expect(page.getByTestId('execution-output-json')).toContainText('"revision": 2');
  await expect(page.getByTestId('execution-output-json')).toContainText('"source": "builder-e2e"');

  const executionRow = page.locator('[data-testid^="execution-row-"]').first();
  await expect(executionRow).toBeVisible();
  const executionRowTestId = await executionRow.getAttribute('data-testid');
  const executionId = executionRowTestId?.replace('execution-row-', '');
  expect(executionId).toMatch(/^[0-9a-f-]{36}$/);

  await page.getByTestId('execution-filter-trigger').selectOption('SCHEDULED');
  await expect(page.getByText('No matching executions')).toBeVisible();
  await page.getByTestId('execution-filter-trigger').selectOption('MANUAL');
  await expect(executionRow).toBeVisible();

  await page.getByTestId('execution-filter-status').selectOption('FAILED');
  await expect(page.getByText('No matching executions')).toBeVisible();
  await page.getByTestId('execution-filter-status').selectOption('SUCCEEDED');
  await expect(executionRow).toBeVisible();

  await page.getByTestId('execution-filter-revision').fill('1');
  await expect(page.getByText('No matching executions')).toBeVisible();
  await page.getByTestId('execution-filter-revision').fill('2');
  await expect(executionRow).toBeVisible();

  await page.getByTestId('execution-filter-search').fill(executionId!);
  await expect(executionRow).toBeVisible();
  await page.getByTestId('execution-filter-search').fill('999999');
  await expect(page.getByText('No matching executions')).toBeVisible();

  await page.getByTestId('execution-clear-filters').click();
  await expect(executionRow).toBeVisible();
});

test('manual ASL creation routes a JSONata Choice through the execution UI', async ({ page }) => {
  const name = uniqueName('E2E Manual ASL Workflow');
  await page.goto('/');
  await page.getByTestId('workflow-template-file').setInputFiles({
    name: 'manual-choice-workflow.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(manualChoiceDefinition, null, 2)),
  });
  await expect(page.getByTestId('workflow-editor-builder')).toBeVisible();
  await expect(page.getByTestId('workflow-definition-status'))
    .toHaveAttribute('title', 'Frontend ASL checks pass');

  const workflowId = await saveWorkflow(page, name);
  await verifyWorkflowInList(page, workflowId, name);

  await triggerWorkflowThroughUi(page, workflowId, {
    approved: true,
  });
  await expect(page.getByTestId('execution-selected-status')).toContainText('SUCCEEDED');
  await expect(page.locator('[data-state-name="Route"]')).toContainText('SUCCEEDED');
  await expect(page.locator('[data-state-name="Approved"]')).toContainText('SUCCEEDED');
  await expect(page.getByTestId('execution-output-json')).toContainText('"decision": "approved"');
});

test('manual ASL executes every supported state type across success and failure routes', async ({ page }) => {
  test.setTimeout(90_000);
  const name = uniqueName('E2E All States Workflow');
  await page.goto('/');
  await page.getByTestId('workflow-template-file').setInputFiles({
    name: 'all-states-workflow.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(allStatesDefinition, null, 2)),
  });
  await expect(page.getByTestId('workflow-definition-status'))
    .toHaveAttribute('title', 'Frontend ASL checks pass');

  const workflowId = await saveWorkflow(page, name);
  await triggerWorkflowThroughUi(page, workflowId, {
    requestId: 'all-states-success',
    shouldFail: false,
  });

  await expect(page.getByTestId('execution-selected-status'))
    .toContainText('SUCCEEDED', { timeout: 45_000 });
  await expect(page.locator('[data-state-name="Prepare"]')).toContainText('SUCCEEDED');
  await expect(page.locator('[data-state-name="CallMissing"]')).toContainText('FAILED');
  await expect(page.locator('[data-state-name="Route"]')).toContainText('SUCCEEDED');
  await expect(page.locator('[data-state-name="FanOut"]')).toContainText('SUCCEEDED');
  await expect(page.locator('[data-state-name="MapItems"]')).toContainText('SUCCEEDED');
  await expect(page.locator('[data-state-name="Pause"]')).toContainText('SUCCEEDED');
  await expect(page.locator('[data-state-name="Done"]')).toContainText('SUCCEEDED');
  await expect(page.locator('[data-state-name="BranchPass"]')).toContainText('SUCCEEDED');
  await expect(page.locator('[data-state-name="BranchDone"]')).toContainText('SUCCEEDED');
  await expect(page.locator('[data-state-name="DoubleItem"]')).toHaveCount(2);
  await expect(page.getByTestId('execution-output-json')).toContainText('"result": "ok"');
  await expect(page.getByTestId('execution-output-json')).toContainText('"value": 4');

  await triggerWorkflowThroughUi(page, workflowId, {
    requestId: 'all-states-failure',
    shouldFail: true,
  });
  await expect(page.getByTestId('execution-selected-status'))
    .toContainText('FAILED', { timeout: 45_000 });
  await expect(page.locator('[data-state-name="ExpectedFailure"]')).toContainText('FAILED');
  await expect(page.locator('[data-state-name="ExpectedFailure"]')).toContainText('E2E.ExpectedFailure');
});

test('an active Wait execution can be canceled through the execution UI', async ({ page }) => {
  const name = uniqueName('E2E Cancel Workflow');
  await page.goto('/');
  await page.getByTestId('workflow-template-file').setInputFiles({
    name: 'cancelable-wait-workflow.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(cancelableWaitDefinition, null, 2)),
  });
  await expect(page.getByTestId('workflow-definition-status'))
    .toHaveAttribute('title', 'Frontend ASL checks pass');

  const workflowId = await saveWorkflow(page, name);
  await triggerWorkflowThroughUi(page, workflowId, { requestId: 'cancel-e2e' });
  await expect(page.getByTestId('execution-selected-status')).toContainText('WAITING');

  const cancelButton = page.getByTestId('execution-cancel-selected');
  await expect(cancelButton).toBeEnabled();
  await cancelButton.click();
  await expect(page.getByTestId('execution-cancel-dialog')).toBeVisible();
  await page.getByTestId('execution-confirm-cancel').click();
  await expect(page.getByTestId('execution-cancel-dialog')).toBeHidden();
  await expect(page.getByTestId('execution-selected-status')).toContainText('CANCELED');
  await expect(page.locator('[data-state-name="HoldForReview"]')).toContainText('CANCELED');
});

test('a recurring workflow can move through draft revisions, activation, and archive through the UI', async ({
  page,
}) => {
  const name = uniqueName('E2E Recurring Draft Lifecycle');
  await page.goto('/');
  await page.getByTestId('workflow-template-file').setInputFiles({
    name: 'recurring-draft-workflow.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(recurringPassDefinition, null, 2)),
  });
  await expect(page.getByTestId('workflow-definition-status'))
    .toHaveAttribute('title', 'Frontend ASL checks pass');

  const showPanelButton = page.getByTestId('workflow-show-panel');
  if (await showPanelButton.isVisible()) await showPanelButton.click();
  await page.getByTestId('workflow-schedule-mode-recurring').click();
  await page.getByTestId('workflow-schedule-advanced-toggle').click();
  await page.getByTestId('workflow-cron-expression').fill('0 0 0 1 1 *');
  await page.getByTestId('workflow-timezone').selectOption('UTC');

  const workflowId = await saveWorkflow(page, name);

  await page.getByTestId('workflow-settings-open').click();
  await expect(page.getByTestId('workflow-settings-status')).toHaveText('DRAFT');
  await page.getByLabel('Close workflow settings').click();

  await page.getByTestId('workflow-tab-executions').click();
  await expect(page.getByTestId('execution-trigger-run')).toBeDisabled();
  await expect(page.getByTestId('execution-trigger-run'))
    .toHaveAttribute('title', 'Activate the recurring workflow revision before executing it.');

  await page.getByTestId('workflow-edit-revision').click();
  await expect(page).toHaveURL(new RegExp(
    `/workflows/${workflowId}/revisions/1/edit$`,
  ));
  await page.getByTestId('workflow-state-ScheduledPass').click();
  await page.getByText('Data flow', { exact: true }).click();
  await page.getByRole('textbox', { name: 'Output', exact: true }).fill(
    "{% { 'source': 'scheduler-e2e', 'revision': 2 } %}",
  );
  await expect(page.getByTestId('workflow-save-without-activation')).toBeEnabled();
  await page.getByTestId('workflow-save-without-activation').click();
  await expect(page).toHaveURL(new RegExp(`/workflows/${workflowId}$`));

  await page.getByTestId('workflow-revision-history').click();
  await expect(page.getByTestId('workflow-revision-count')).toHaveText('2 revisions');
  await expect(page.getByTestId('workflow-revision-2')).toHaveAttribute('data-revision-active', 'false');
  await expect(page.getByTestId('workflow-revision-1')).toHaveAttribute('data-revision-active', 'false');

  await page.getByTestId('workflow-revision-2').click();
  await page.getByTestId('workflow-activate-schedule').click();
  await expect(page.getByTestId('workflow-activate-schedule')).toBeHidden();
  await expect(page.getByTestId('workflow-revision-2')).toHaveAttribute('data-revision-active', 'true');

  await page.getByTestId('workflow-settings-open').click();
  await expect(page.getByTestId('workflow-settings-status')).toHaveText('ACTIVE');
  await page.getByLabel('Close workflow settings').click();

  await page.getByTestId('workflow-edit-revision').click();
  await expect(page).toHaveURL(new RegExp(
    `/workflows/${workflowId}/revisions/2/edit$`,
  ));
  await page.getByTestId('workflow-state-ScheduledPass').click();
  await page.getByText('Data flow', { exact: true }).click();
  await page.getByRole('textbox', { name: 'Output', exact: true }).fill(
    "{% { 'source': 'scheduler-e2e', 'revision': 3 } %}",
  );
  await expect(page.getByTestId('workflow-save')).toBeEnabled();
  await page.getByTestId('workflow-save').click();
  await expect(page).toHaveURL(new RegExp(`/workflows/${workflowId}$`));

  await page.getByTestId('workflow-revision-history').click();
  await expect(page.getByTestId('workflow-revision-count')).toHaveText('3 revisions');
  await expect(page.getByTestId('workflow-revision-3')).toHaveAttribute('data-revision-active', 'true');
  await expect(page.getByTestId('workflow-revision-2')).toHaveAttribute('data-revision-active', 'false');

  await page.getByTestId('workflow-revision-2').click();
  const makeActiveButton = page.getByTestId('workflow-make-revision-active');
  await expect(makeActiveButton).toBeVisible();
  await expect(makeActiveButton).toHaveAttribute('title', 'Make Rev 2 the active revision');
  await makeActiveButton.click();
  await expect(makeActiveButton).toBeHidden();
  await expect(page.getByTestId('workflow-revision-2')).toHaveAttribute('data-revision-active', 'true');
  await expect(page.getByTestId('workflow-revision-3')).toHaveAttribute('data-revision-active', 'false');

  await page.getByTestId('workflow-settings-open').click();
  page.once('dialog', async (dialog) => {
    expect(dialog.message()).toContain(name);
    await dialog.accept();
  });
  await page.getByTestId('workflow-archive').click();
  await expect(page.getByTestId('workflow-settings-status')).toHaveCount(0);

  await page.goto('/workflows');
  const workflowCard = page.getByTestId(`workflow-card-${workflowId}`);
  await expect(workflowCard).toBeVisible();
  await expect(workflowCard).toContainText(name);
  await expect(workflowCard).toContainText('Archived');

  await workflowCard.click();
  await expect(page).toHaveURL(new RegExp(`/workflows/${workflowId}$`));
  await page.getByTestId('workflow-tab-executions').click();
  await expect(page.getByTestId('execution-trigger-run')).toBeDisabled();
  await expect(page.getByTestId('execution-trigger-run'))
    .toHaveAttribute('title', 'Archived workflows cannot be executed.');
});

test('a recurring workflow activates, schedules runs, pauses, and resumes through the UI', async ({
  page,
  request,
}) => {
  test.setTimeout(90_000);
  const name = uniqueName('E2E Recurring Workflow');
  await page.goto('/');
  await page.getByTestId('workflow-template-file').setInputFiles({
    name: 'recurring-pass-workflow.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(recurringPassDefinition, null, 2)),
  });
  await expect(page.getByTestId('workflow-definition-status'))
    .toHaveAttribute('title', 'Frontend ASL checks pass');

  const showPanelButton = page.getByTestId('workflow-show-panel');
  if (await showPanelButton.isVisible()) await showPanelButton.click();
  await page.getByTestId('workflow-schedule-mode-recurring').click();
  await page.getByTestId('workflow-schedule-advanced-toggle').click();
  await page.getByTestId('workflow-cron-expression').fill('*/5 * * * * *');
  await page.getByTestId('workflow-timezone').selectOption('UTC');

  const workflowId = await saveWorkflow(page, name);
  const activateButton = page.getByTestId('workflow-activate-schedule');
  await expect(activateButton).toBeVisible();
  await activateButton.click();
  await expect(activateButton).toBeHidden();

  await page.getByTestId('workflow-settings-open').click();
  await expect(page.getByTestId('workflow-settings-status')).toHaveText('ACTIVE');
  await expect(page.getByTestId('workflow-next-run')).not.toContainText('No upcoming run');
  await page.getByLabel('Close workflow settings').click();

  await page.getByTestId('workflow-tab-executions').click();
  const executionList = page.getByTestId('execution-list');
  await expect(executionList).toContainText('Scheduled', { timeout: 20_000 });
  await expect(page.getByTestId('execution-selected-status')).toContainText('SUCCEEDED');
  await expect(page.locator('[data-state-name="ScheduledPass"]')).toContainText('SUCCEEDED');

  await page.getByTestId('workflow-settings-open').click();
  await page.getByTestId('workflow-pause-schedule').click();
  await expect(page.getByTestId('workflow-settings-status')).toHaveText('PAUSED');
  await expect(page.getByTestId('workflow-next-run')).toContainText('No upcoming run');
  const countWhilePaused = await executionCount(request, workflowId);
  await page.waitForTimeout(6_500);
  expect(await executionCount(request, workflowId)).toBe(countWhilePaused);

  await page.getByTestId('workflow-resume-schedule').click();
  await expect(page.getByTestId('workflow-settings-status')).toHaveText('ACTIVE');
  await expect(page.getByTestId('workflow-next-run')).not.toContainText('No upcoming run');
  await page.getByLabel('Close workflow settings').click();

  await expect.poll(
    () => executionCount(request, workflowId),
    { timeout: 20_000 },
  ).toBeGreaterThan(countWhilePaused);
  await expect.poll(
    () => page.locator('[data-testid^="execution-row-"]').count(),
    { timeout: 10_000 },
  ).toBeGreaterThan(countWhilePaused);
});
