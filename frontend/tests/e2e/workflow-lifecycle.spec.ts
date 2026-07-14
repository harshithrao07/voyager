import { expect, test, type APIRequestContext, type Page } from '@playwright/test';

type WorkflowExecutionResponse = {
  workflowExecutionId: string;
  status: string;
  output: unknown;
  error: string | null;
};

type WorkflowExecutionDetail = {
  execution: {
    id: string;
    definitionRevision: number;
    status: string;
    output: unknown;
  };
  scopes: Array<{
    stateExecutions: Array<{
      stateName: string;
      status: string;
    }>;
  }>;
};

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

async function executeWorkflow(
  request: APIRequestContext,
  workflowId: string,
  input: unknown,
) {
  const response = await request.post(
    `/app/v1/workflows/${workflowId}/executions`,
    { data: { input } },
  );
  expect(response, await response.text()).toBeOK();
  return response.json() as Promise<WorkflowExecutionResponse>;
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

  const execution = await executeWorkflow(request, workflowId, {
    source: 'builder-e2e',
  });
  expect(execution.status).toBe('SUCCEEDED');
  expect(execution.error).toBeNull();
  expect(execution.output).toEqual({ revision: 2, source: 'builder-e2e' });

  const detailResponse = await request.get(
    `/app/v1/workflows/${workflowId}/executions/${execution.workflowExecutionId}`,
  );
  expect(detailResponse).toBeOK();
  const detail = await detailResponse.json() as WorkflowExecutionDetail;
  expect(detail.execution.definitionRevision).toBe(2);
  expect(detail.execution.status).toBe('SUCCEEDED');
  expect(detail.scopes.flatMap((scope) => scope.stateExecutions))
    .toEqual(expect.arrayContaining([
      expect.objectContaining({ stateName: 'NewPass', status: 'SUCCEEDED' }),
    ]));
});

test('manual ASL creation routes a JSONata Choice and executes the selected state', async ({
  page,
  request,
}) => {
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

  const execution = await executeWorkflow(request, workflowId, {
    approved: true,
  });
  expect(execution.status).toBe('SUCCEEDED');
  expect(execution.error).toBeNull();
  expect(execution.output).toEqual({ decision: 'approved' });

  const detailResponse = await request.get(
    `/app/v1/workflows/${workflowId}/executions/${execution.workflowExecutionId}`,
  );
  expect(detailResponse).toBeOK();
  const detail = await detailResponse.json() as WorkflowExecutionDetail;
  expect(detail.execution.status).toBe('SUCCEEDED');
  expect(detail.scopes.flatMap((scope) => scope.stateExecutions))
    .toEqual(expect.arrayContaining([
      expect.objectContaining({ stateName: 'Route', status: 'SUCCEEDED' }),
      expect.objectContaining({ stateName: 'Approved', status: 'SUCCEEDED' }),
    ]));
});
