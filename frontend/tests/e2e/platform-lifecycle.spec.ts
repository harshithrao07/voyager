import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import http from 'node:http';
import type { AddressInfo } from 'node:net';

type FunctionLanguage = {
  id: number;
  name: string;
  multiFileSupported: boolean;
};

type FunctionDefinition = {
  id: string;
  name: string;
  activeVersion: number | null;
  status: 'ENABLED' | 'DISABLED' | 'ARCHIVED';
};

type FunctionVersion = {
  version: number;
  status: 'DRAFT' | 'AVAILABLE' | 'ARCHIVED';
  enableNetwork: boolean;
  testCases: Array<{
    name: string;
    input: string;
    expectedOutput: string;
    expectedError: string;
  }>;
};

type McpServer = {
  serverId: string;
  status: 'ENABLED' | 'DISABLED';
};

const createdWorkflowIds = new Set<string>();
const createdFunctionIds = new Set<string>();
const createdMcpServerIds = new Set<string>();
const closeMockMcpServers: Array<() => Promise<void>> = [];

const activeExecutionStatuses = new Set([
  'PENDING',
  'QUEUED',
  'RUNNING',
  'WAITING',
]);

function uniqueSlug(prefix: string) {
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
}

function functionWorkflowDefinition(functionName: string) {
  return {
    StartAt: 'CallFunction',
    States: {
      CallFunction: {
        Type: 'Task',
        Resource: `voyager://function/${functionName}`,
        Arguments: {
          amount: '{% $states.input.amount %}',
          label: '{% $states.input.label %}',
        },
        Output: '{% $states.result %}',
        End: true,
      },
    },
  };
}

function mcpWorkflowDefinition(serverId: string) {
  return {
    StartAt: 'EchoThroughMcp',
    States: {
      EchoThroughMcp: {
        Type: 'Task',
        Resource: `voyager://mcp/${serverId}/echo`,
        Arguments: {
          message: '{% $states.input.message %}',
        },
        Output: '{% $states.result %}',
        End: true,
      },
    },
  };
}

function jsonRpcResult(id: unknown, result: unknown) {
  return { jsonrpc: '2.0', id, result };
}

function jsonRpcError(id: unknown, code: number, message: string) {
  return { jsonrpc: '2.0', id, error: { code, message } };
}

function mockMcpResponse(message: any) {
  const echoTool = {
    name: 'echo',
    title: 'Echo',
    description: 'Returns the provided message as structured content.',
    inputSchema: {
      type: 'object',
      properties: {
        message: { type: 'string' },
      },
      required: ['message'],
      additionalProperties: false,
    },
  };

  if (message.id === undefined || message.id === null) {
    return null;
  }

  if (message.method === 'initialize') {
    return jsonRpcResult(message.id, {
      protocolVersion: message.params?.protocolVersion ?? '2025-11-25',
      capabilities: { tools: { listChanged: false } },
      serverInfo: { name: 'voyager-e2e-mcp-http', version: '1.0.0' },
    });
  }

  if (message.method === 'tools/list') {
    return jsonRpcResult(message.id, { tools: [echoTool] });
  }

  if (message.method === 'tools/call') {
    const name = message.params?.name;
    const value = message.params?.arguments?.message;
    if (name !== echoTool.name) {
      return jsonRpcError(message.id, -32602, `Unknown tool: ${name}`);
    }
    if (typeof value !== 'string') {
      return jsonRpcError(message.id, -32602, 'message must be a string');
    }
    const structuredContent = { echo: value, ok: true };
    return jsonRpcResult(message.id, {
      content: [{ type: 'text', text: JSON.stringify(structuredContent) }],
      structuredContent,
      isError: false,
    });
  }

  return jsonRpcError(message.id, -32601, `Unsupported method: ${message.method}`);
}

async function startMockMcpHttpServer() {
  const server = http.createServer((request, response) => {
    if (request.method === 'GET') {
      response.writeHead(405);
      response.end();
      return;
    }
    if (request.method === 'DELETE') {
      response.writeHead(202);
      response.end();
      return;
    }
    if (request.method !== 'POST' || request.url !== '/mcp') {
      response.writeHead(404);
      response.end();
      return;
    }

    let body = '';
    request.setEncoding('utf8');
    request.on('data', (chunk) => {
      body += chunk;
    });
    request.on('end', () => {
      try {
        const message = JSON.parse(body);
        const result = mockMcpResponse(message);
        if (!result) {
          response.writeHead(202);
          response.end();
          return;
        }
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end(JSON.stringify(result));
      } catch (error) {
        response.writeHead(400, { 'content-type': 'application/json' });
        response.end(JSON.stringify(jsonRpcError(null, -32700, 'Parse error')));
      }
    });
  });

  await new Promise<void>((resolve) => server.listen(0, '0.0.0.0', resolve));
  const { port } = server.address() as AddressInfo;
  const host = process.env.E2E_MCP_HOST || 'host.docker.internal';
  const close = () => new Promise<void>((resolve, reject) => {
    server.close((error) => (error ? reject(error) : resolve()));
  });
  closeMockMcpServers.push(close);
  return {
    baseUrl: `http://${host}:${port}`,
  };
}

async function probeBackend(request: APIRequestContext) {
  const workflowProbe = await request.get('/app/v1/workflows?page=0&size=1');
  expect(
    workflowProbe,
    'The workflow backend must be reachable through the frontend proxy. '
      + 'Start it on E2E_BACKEND_URL (default http://127.0.0.1:8081).',
  ).toBeOK();

  const functionsProbe = await request.get('/app/v1/functions/languages');
  expect(
    functionsProbe,
    'The function runtime registry must be reachable for function lifecycle E2E.',
  ).toBeOK();

  const mcpProbe = await request.get('/app/v1/mcp/servers');
  expect(
    mcpProbe,
    'The MCP registry must be reachable for MCP lifecycle E2E.',
  ).toBeOK();
}

async function javascriptLanguage(request: APIRequestContext) {
  const response = await request.get('/app/v1/functions/languages');
  expect(response).toBeOK();
  const languages = await response.json() as FunctionLanguage[];
  const language = languages.find((candidate) => {
    const name = candidate.name.toLowerCase();
    return name.includes('javascript') && name.includes('node');
  }) ?? languages.find((candidate) => candidate.name.toLowerCase().includes('javascript'));
  expect(language, 'Judge0 must expose a JavaScript runtime for the UI E2E starter function.').toBeTruthy();
  return language!;
}

async function findFunctionByName(
  request: APIRequestContext,
  name: string,
  includeArchived = true,
) {
  const response = await request.get(`/app/v1/functions?includeArchived=${includeArchived ? 'true' : 'false'}`);
  expect(response).toBeOK();
  const functions = await response.json() as FunctionDefinition[];
  return functions.find((fn) => fn.name === name) ?? null;
}

async function getFunctionVersions(request: APIRequestContext, functionId: string) {
  const response = await request.get(`/app/v1/functions/${functionId}/versions`);
  expect(response).toBeOK();
  return await response.json() as FunctionVersion[];
}

async function getFunction(request: APIRequestContext, functionId: string) {
  const response = await request.get(`/app/v1/functions/${functionId}`);
  expect(response).toBeOK();
  return await response.json() as FunctionDefinition;
}

async function getMcpServer(request: APIRequestContext, serverId: string) {
  const response = await request.get(`/app/v1/mcp/servers/${encodeURIComponent(serverId)}`);
  expect(response).toBeOK();
  return await response.json() as McpServer;
}

async function saveWorkflowFromDefinition(
  page: Page,
  fileName: string,
  workflowName: string,
  definition: unknown,
) {
  await page.goto('/');
  await page.getByTestId('workflow-template-file').setInputFiles({
    name: fileName,
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(definition, null, 2)),
  });
  await expect(page.getByTestId('workflow-editor-builder')).toBeVisible();
  await expect(page.getByTestId('workflow-definition-status'))
    .toHaveAttribute('title', 'Frontend ASL checks pass');

  const showPanelButton = page.getByTestId('workflow-show-panel');
  if (await showPanelButton.isVisible()) {
    await showPanelButton.click();
  }
  await page.getByLabel('Name', { exact: true }).fill(workflowName);
  await expect(page.getByTestId('workflow-save')).toBeEnabled();
  await page.getByTestId('workflow-save').click();
  await expect(page).toHaveURL(/\/workflows\/[0-9a-f-]+$/);

  const workflowId = new URL(page.url()).pathname.split('/').pop()!;
  expect(workflowId).toMatch(/^[0-9a-f-]{36}$/);
  createdWorkflowIds.add(workflowId);
  return workflowId;
}

async function triggerWorkflowThroughUi(
  page: Page,
  workflowId: string,
  input: unknown,
) {
  await page.goto(`/workflows/${workflowId}`);
  await page.getByTestId('workflow-tab-executions').click();
  await expect(page.getByTestId('execution-trigger-run')).toBeEnabled();
  await page.getByTestId('execution-trigger-run').click();
  await page.getByTestId('execution-input-json').fill(JSON.stringify(input, null, 2));
  await page.getByTestId('execution-submit-run').click();
  await expect(page.getByTestId('execution-trigger-dialog')).toBeHidden();
  await expect(page.getByTestId('execution-selected-status')).toBeVisible();
}

async function createPublishedFunctionThroughUi(
  page: Page,
  request: APIRequestContext,
  functionName: string,
) {
  const language = await javascriptLanguage(request);

  await page.goto('/functions');
  await expect(page.getByTestId('function-create-open')).toBeVisible();
  await page.getByTestId('function-create-open').click();
  await expect(page.getByTestId('function-workbench')).toBeVisible();
  await page.getByTestId('function-create-name').fill(functionName);
  await page.getByTestId('function-create-language').selectOption(String(language.id));
  await expect(page.getByTestId('function-workbench-publish')).toBeEnabled();
  await page.getByTestId('function-workbench-publish').click();

  await expect.poll(
    async () => Boolean(await findFunctionByName(request, functionName)),
    { timeout: 15_000 },
  ).toBe(true);

  const functionRecord = await findFunctionByName(request, functionName);
  expect(functionRecord).toBeTruthy();
  createdFunctionIds.add(functionRecord.id);
  await expect(page.getByTestId('function-tab-versions')).toBeVisible({ timeout: 15_000 });
  await expect.poll(
    async () => (await getFunction(request, functionRecord.id)).activeVersion,
    { timeout: 15_000 },
  ).toBe(1);
  return functionRecord;
}

async function archiveWorkflowExecutions(request: APIRequestContext, workflowId: string) {
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

test.beforeEach(async ({ request }) => {
  await probeBackend(request);
});

test.afterEach(async ({ request }) => {
  while (closeMockMcpServers.length > 0) {
    const close = closeMockMcpServers.pop()!;
    await close();
  }

  for (const workflowId of createdWorkflowIds) {
    await archiveWorkflowExecutions(request, workflowId);
  }
  createdWorkflowIds.clear();

  for (const functionId of createdFunctionIds) {
    await request.delete(`/app/v1/functions/${functionId}`);
  }
  createdFunctionIds.clear();

  for (const serverId of createdMcpServerIds) {
    await request.patch(`/app/v1/mcp/servers/${encodeURIComponent(serverId)}/status`, {
      data: { status: 'DISABLED' },
    });
  }
  createdMcpServerIds.clear();
});

test('function lifecycle is created, tested, versioned, used by workflow, toggled, and archived through the UI', async ({
  page,
  request,
}) => {
  test.setTimeout(240_000);
  const functionName = uniqueSlug('function-e2e');
  const archivedFunctionName = uniqueSlug('function-archive-e2e');
  const workflowName = `E2E Function Workflow ${functionName}`;

  const functionRecord = await createPublishedFunctionThroughUi(page, request, functionName);
  await page.getByTestId('function-tab-test').click();
  await expect(page.getByTestId('function-test-version')).toHaveValue('active');
  await page.getByTestId('function-test-case-name').fill('Echo starter output');
  await page.getByTestId('function-test-input').fill(JSON.stringify({ amount: 321, label: 'ui-test' }, null, 2));
  await page.getByTestId('function-test-expected-output').fill(JSON.stringify({
    received: { amount: 321, label: 'ui-test' },
    ok: true,
  }, null, 2));
  await expect(page.getByTestId('function-test-run-selected')).toBeEnabled();
  await page.getByTestId('function-test-run-selected').click();
  await expect(page.getByTestId('function-test-result')).toContainText('Pass', { timeout: 90_000 });
  await expect(page.getByTestId('function-test-result')).toContainText('"ok": true');
  await expect(page.getByTestId('function-test-save')).toBeEnabled();
  await page.getByTestId('function-test-save').click();
  await expect(page.getByText('Saved 1 test case')).toBeVisible();
  await expect.poll(
    async () => (await getFunctionVersions(request, functionRecord.id))
      .find((version) => version.version === 1)?.testCases.length,
    { timeout: 10_000 },
  ).toBe(1);

  await page.getByTestId('function-new-version').click();
  await expect(page.getByTestId('function-workbench')).toBeVisible();
  await page.getByTestId('function-workbench-note').fill('E2E draft version before activation');
  await expect(page.getByTestId('function-workbench-save-draft')).toBeEnabled();
  await page.getByTestId('function-workbench-save-draft').click();
  await expect(page.getByTestId('function-tab-versions')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId('function-tab-versions').click();
  await expect(page.getByTestId('function-version-2')).toHaveAttribute('data-version-status', 'DRAFT');
  await expect(page.getByTestId('function-version-2')).toHaveAttribute('data-version-active', 'false');

  await page.getByTestId('function-version-2').click();
  await expect(page.getByTestId('function-version-publish-2')).toBeEnabled();
  await page.getByTestId('function-version-publish-2').click();
  await expect(page.getByTestId('function-version-2')).toHaveAttribute('data-version-status', 'AVAILABLE');
  await expect(page.getByTestId('function-version-activate-2')).toBeEnabled();
  await page.getByTestId('function-version-activate-2').click();
  await expect(page.getByTestId('function-version-2')).toHaveAttribute('data-version-active', 'true');
  await expect.poll(
    async () => (await getFunction(request, functionRecord.id)).activeVersion,
    { timeout: 10_000 },
  ).toBe(2);

  await page.goto('/functions');
  await page.getByTestId('function-search').fill(functionName);
  await page.locator(`[data-function-name="${functionName}"]`).click();
  await page.getByTestId('function-tab-settings').click();
  const networkToggle = page.getByTestId('function-network-toggle');
  if (!(await networkToggle.isChecked())) {
    await networkToggle.check();
  }
  await page.getByTestId('function-save-settings').click();
  await page.getByTestId('function-confirm-action').click();
  await expect.poll(
    async () => (await getFunctionVersions(request, functionRecord.id))
      .find((version) => version.version === 2)?.enableNetwork,
    { timeout: 10_000 },
  ).toBe(true);

  await page.getByTestId('function-toggle-status').click();
  await page.getByTestId('function-confirm-action').click();
  await expect.poll(
    async () => (await getFunction(request, functionRecord.id)).status,
    { timeout: 10_000 },
  ).toBe('DISABLED');
  await expect(page.getByTestId('function-toggle-status')).toContainText('Enable');

  await page.getByTestId('function-toggle-status').click();
  await page.getByTestId('function-confirm-action').click();
  await expect.poll(
    async () => (await getFunction(request, functionRecord.id)).status,
    { timeout: 10_000 },
  ).toBe('ENABLED');
  await expect(page.getByTestId('function-toggle-status')).toContainText('Disable');

  const archivedFunctionRecord = await createPublishedFunctionThroughUi(page, request, archivedFunctionName);
  await page.getByTestId('function-tab-settings').click();
  await page.getByTestId('function-archive').click();
  await page.getByTestId('function-confirm-action').click();
  await expect.poll(
    async () => (await getFunction(request, archivedFunctionRecord.id)).status,
    { timeout: 10_000 },
  ).toBe('ARCHIVED');

  await page.getByTestId('function-show-archived').click();
  await page.getByTestId('function-search').fill(archivedFunctionName);
  await expect(page.locator(`[data-function-name="${archivedFunctionName}"]`)).toHaveAttribute('data-function-status', 'ARCHIVED');

  const workflowId = await saveWorkflowFromDefinition(
    page,
    'function-task-workflow.json',
    workflowName,
    functionWorkflowDefinition(functionName),
  );
  await page.goto('/workflows');
  await expect(page.getByTestId(`workflow-card-${workflowId}`)).toContainText(workflowName);
  await triggerWorkflowThroughUi(page, workflowId, { amount: 987, label: 'workflow-call' });
  await expect(page.getByTestId('execution-selected-status')).toContainText('SUCCEEDED', { timeout: 90_000 });
  await expect(page.locator('[data-state-name="CallFunction"]')).toContainText('SUCCEEDED');
  await expect(page.getByTestId('execution-output-json')).toContainText('"ok": true');
  await expect(page.getByTestId('execution-output-json')).toContainText('"amount": 987');
});

test('MCP lifecycle is registered, synced, called, used by workflow, edited, and disabled through the UI', async ({
  page,
  request,
}) => {
  test.setTimeout(180_000);
  const serverId = uniqueSlug('mcp-e2e');
  const displayName = `E2E MCP ${serverId}`;
  const updatedDisplayName = `${displayName} updated`;
  const mockMcp = await startMockMcpHttpServer();
  const workflowName = `E2E MCP Workflow ${serverId}`;

  await page.goto('/mcp');
  await expect(page.getByTestId('mcp-register-open')).toBeVisible();
  await page.getByTestId('mcp-register-open').click();
  await expect(page.getByTestId('mcp-server-form')).toBeVisible();
  await page.getByTestId('mcp-display-name').fill(displayName);
  await page.getByTestId('mcp-server-id').fill(serverId);
  await page.getByTestId('mcp-transport').selectOption('HTTP');
  await page.getByTestId('mcp-base-url').fill(mockMcp.baseUrl);
  await page.getByTestId('mcp-endpoint').fill('/mcp');
  await page.getByTestId('mcp-timeout').fill('10000');
  await page.getByTestId('mcp-trust-read-only').click();
  await page.getByTestId('mcp-enable').check();
  await page.getByTestId('mcp-save-server').click();
  createdMcpServerIds.add(serverId);

  await expect(page.getByRole('heading', { name: displayName })).toBeVisible({ timeout: 15_000 });
  await expect.poll(
    async () => (await getMcpServer(request, serverId)).status,
    { timeout: 10_000 },
  ).toBe('ENABLED');

  await page.getByTestId('mcp-sync-tools').click();
  await expect(page.getByTestId('mcp-sync-result')).toContainText('1 discovered', { timeout: 30_000 });
  await page.getByTestId('mcp-tab-tools').click();
  await expect(page.getByTestId('mcp-tool-echo')).toBeVisible();
  await expect(page.getByTestId('mcp-tool-echo')).toContainText('echo');

  await page.getByTestId('mcp-tab-playground').click();
  await expect(page.getByTestId('mcp-playground-tool')).toHaveValue('echo');
  await page.getByTestId('mcp-playground-args').fill(JSON.stringify({ message: 'hello from ui' }, null, 2));
  await expect(page.getByTestId('mcp-run-tool')).toBeEnabled();
  await page.getByTestId('mcp-run-tool').click();
  await expect(page.getByTestId('mcp-playground-result')).toContainText('hello from ui', { timeout: 30_000 });
  await expect(page.getByTestId('mcp-playground-result')).toContainText('"ok": true');

  await page.getByTestId('mcp-tab-executions').click();
  const executionRows = page.locator('[data-testid^="mcp-execution-"]:not([data-testid^="mcp-execution-filter-"])');
  await expect(executionRows.first()).toContainText('SUCCESS');
  await expect(executionRows.first()).toContainText('echo');

  await page.goto('/mcp');
  await page.getByTestId('mcp-search').fill(serverId);
  await page.getByTestId(`mcp-server-card-${serverId}`).click();
  await page.getByTestId('mcp-edit-server').click();
  await expect(page.getByTestId('mcp-server-form')).toBeVisible();
  await page.getByTestId('mcp-display-name').fill(updatedDisplayName);
  await page.getByTestId('mcp-save-server').click();
  await expect(page.getByRole('heading', { name: updatedDisplayName })).toBeVisible({ timeout: 15_000 });

  await page.getByTestId('mcp-toggle-status').click();
  await expect.poll(
    async () => (await getMcpServer(request, serverId)).status,
    { timeout: 10_000 },
  ).toBe('DISABLED');
  await expect(page.getByTestId('mcp-toggle-status')).toContainText('Enable');

  await page.getByTestId('mcp-toggle-status').click();
  await expect.poll(
    async () => (await getMcpServer(request, serverId)).status,
    { timeout: 10_000 },
  ).toBe('ENABLED');
  await expect(page.getByTestId('mcp-toggle-status')).toContainText('Disable');

  const workflowId = await saveWorkflowFromDefinition(
    page,
    'mcp-task-workflow.json',
    workflowName,
    mcpWorkflowDefinition(serverId),
  );
  await triggerWorkflowThroughUi(page, workflowId, { message: 'hello from workflow' });
  await expect(page.getByTestId('execution-selected-status')).toContainText('SUCCEEDED', { timeout: 90_000 });
  await expect(page.locator('[data-state-name="EchoThroughMcp"]')).toContainText('SUCCEEDED');
  await expect(page.getByTestId('execution-output-json')).toContainText('hello from workflow');
  await expect(page.getByTestId('execution-output-json')).toContainText('"ok": true');
});
