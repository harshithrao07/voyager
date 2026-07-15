import { expect, test, type APIRequestContext, type Page } from '@playwright/test';

const createdWorkflowIds = new Set<string>();

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
  await page.getByTestId('execution-input-json').fill(JSON.stringify(input, null, 2));
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
});

test('builder creation persists, appears in the list, revisions, and executes', async ({
  page,
  request,
}) => {
  const name = uniqueName('E2E Builder Workflow');
  await openManualCreator(page);
  await page.getByTestId('workflow-add-state-pass').click();
  await expect(page.getByTestId('workflow-definition-status'))
    .toHaveAttribute('title', 'Frontend ASL checks pass');

  const workflowId = await saveWorkflow(page, name);
  await verifyWorkflowInList(page, workflowId, name);

  await page.goto(`/workflows/${workflowId}`);
  await page.getByTestId('workflow-edit-revision').click();
  await expect(page).toHaveURL(new RegExp(
    `/workflows/${workflowId}/revisions/1/edit$`,
  ));

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

  const revisionsResponse = await request.get(
    `/app/v1/workflows/${workflowId}/revisions`,
  );
  expect(revisionsResponse).toBeOK();
  const revisions = await revisionsResponse.json();
  expect(revisions).toHaveLength(2);
  expect(revisions[0].active).toBe(true);
  expect(revisions[0].revision).toBe(2);

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
