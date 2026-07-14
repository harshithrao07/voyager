import type { CanvasNodePositions } from './types/workflowCanvas';

export interface WorkflowGenerationRequest {
  instruction: string;
  modelId?: string;
}

export interface WorkflowGenerationResponse {
  definition: any;
  rawOutput: string;
  validationIssues: string[];
}

export type WorkflowStatusDTO = 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'ARCHIVED';
export type WorkflowAiStage =
  | 'COLLECTING_WORKFLOW_DETAILS'
  | 'ASL_READY'
  | 'ASL_UNDER_REVIEW'
  | 'COLLECTING_SCHEDULE_DETAILS'
  | 'PLAN_READY'
  | 'ACCEPTED';

export interface AiModelConfigDTO {
  id: string;
  displayName: string;
  providerType: 'OPENAI_COMPATIBLE_LOCAL';
  baseUrl: string;
  modelName: string;
  enabled: boolean;
  defaultModel: boolean;
}

export interface AiModelConfigRequest {
  displayName: string;
  baseUrl: string;
  modelName: string;
  apiKey?: string | null;
  defaultModel: boolean;
}

export interface AiModelTestRequest {
  baseUrl: string;
  modelName?: string | null;
  apiKey?: string | null;
}

export interface AiModelTestResponse {
  success: boolean;
  message: string;
}

export interface AiModelDiscoverRequest {
  baseUrl: string;
  apiKey?: string | null;
}

export interface AiModelEnabledRequest {
  enabled: boolean;
}

export interface CreateWorkflowRequest {
  name: string;
  cronExpression?: string | null;
  timezone?: string | null;
  maxAttempts: number;
  idempotencyKey: string;
  definition: any;
}

export interface WorkflowAiStartRequest {
  instruction: string;
  modelConfigId?: string | null;
  userDateTime?: string;
}

export interface WorkflowAiChatRequest {
  conversationId: string;
  message: string;
  modelConfigId?: string | null;
}

export interface WorkflowAiReviewAslRequest {
  conversationId: string;
  definition: any;
}

export interface WorkflowAiAcceptPlanRequest {
  conversationId: string;
}

export interface WorkflowAiResponse {
  conversationId: string;
  conversationName: string;
  stage: WorkflowAiStage;
  message: string;
  aslDefinition?: any | null;
  validationIssues: string[];
  finalPlan?: any | null;
  draftWorkflowPayload?: CreateWorkflowRequest | null;
  workflowId?: string | null;
  workflow?: WorkflowResponseDTO | null;
  assistantMessage?: WorkflowAiMessageDTO | null;
}

export interface WorkflowAiConversationSummaryDTO {
  id: string;
  name: string;
  stage: WorkflowAiStage;
  modelConfigId?: string | null;
  modelDisplayName?: string | null;
  initialInstruction: string;
  createdAt: string;
  updatedAt: string;
}

export interface WorkflowAiMessageDTO {
  id: string;
  role: 'USER' | 'ASSISTANT' | 'SYSTEM';
  content: string;
  modelConfigId?: string | null;
  modelDisplayName?: string | null;
  durationMs?: number | null;
  inputTokens?: number | null;
  outputTokens?: number | null;
  totalTokens?: number | null;
  thinkingContent?: string | null;
  finishReason?: string | null;
  regeneratedFromMessageId?: string | null;
  createdAt: string;
}

export interface WorkflowAiConversationDetailDTO extends WorkflowAiConversationSummaryDTO {
  aslDefinition?: any | null;
  finalPlan?: any | null;
  draftWorkflowPayload?: CreateWorkflowRequest | null;
  messages: WorkflowAiMessageDTO[];
}

export interface WorkflowAiRegenerateRequest {
  modelConfigId?: string | null;
}

export interface ListWorkflowsRequest {
  page?: number;
  size?: number;
  status?: WorkflowStatusDTO;
  name?: string;
}

export interface GetWorkflowRequest {
  workflowId: string;
}

export interface GetWorkflowRevisionsRequest {
  workflowId: string;
}

export interface CreateWorkflowRevisionRequest {
  definition: unknown;
  activate: boolean;
}

export interface UpdateWorkflowMetadataRequest {
  expectedVersion: number;
  name?: string;
  cronExpression?: string | null;
  timezone?: string;
  maxAttempts?: number;
}

export interface WorkflowDefinitionResponseDTO {
  id: string;
  revision: number;
  definitionHash: string;
  definition: any;
  canvasLayout: CanvasNodePositions;
  active: boolean;
  createdAt: string;
}

export interface WorkflowResponseDTO {
  id: string;
  version: number;
  name: string;
  status: WorkflowStatusDTO;
  cronExpression: string | null;
  timezone: string | null;
  nextRunAt: string | null;
  maxAttempts: number;
  idempotencyKey: string;
  activeDefinition: WorkflowDefinitionResponseDTO | null;
  createdAt: string;
  updatedAt: string;
}

export interface WorkflowPageDTO {
  content: WorkflowResponseDTO[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export type DraftStateTestStatus = 'SUCCEEDED' | 'FAILED' | 'WAITING' | 'TASK_PREVIEW';

export interface DraftStateTestRequest {
  definition: unknown;
  stateName: string;
  input: unknown;
  variables?: Record<string, unknown>;
  executeTask?: boolean;
}

export interface DraftStateTestResponse {
  status: DraftStateTestStatus;
  stateName: string;
  stateType: string;
  input: unknown;
  output: unknown;
  variables: Record<string, unknown>;
  nextStateName: string | null;
  taskResource: string | null;
  taskArguments: unknown;
  wakeAt: string | null;
  error: string | null;
  cause: string | null;
  durationMs: number;
}

async function readError(response: Response) {
  const errorText = await response.text().catch(() => 'Unknown error');
  if (!errorText) {
    return `${response.status} - ${response.statusText || 'Request failed'}`;
  }

  try {
    const parsed = JSON.parse(errorText);
    const message = parsed.message || parsed.error || errorText;
    return `${response.status} - ${message}`;
  } catch {
    const title = errorText.match(/<title>(.*?)<\/title>/i)?.[1];
    const heading = errorText.match(/<h1[^>]*>(.*?)<\/h1>/i)?.[1];
    const plainText = (title || heading || errorText)
      .replace(/<[^>]*>/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
    return `${response.status} - ${plainText.slice(0, 180)}${plainText.length > 180 ? '...' : ''}`;
  }
}

async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url);

  if (!response.ok) {
    throw new Error(await readError(response));
  }

  return response.json();
}

function buildQuery(params: Record<string, string | number | undefined>) {
  const query = new URLSearchParams();

  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') {
      query.set(key, String(value));
    }
  }

  const queryString = query.toString();
  return queryString ? `?${queryString}` : '';
}

export function listWorkflows(request: ListWorkflowsRequest = {}): Promise<WorkflowPageDTO> {
  const query = buildQuery({
    page: request.page ?? 0,
    size: request.size ?? 20,
    status: request.status,
    name: request.name,
  });

  return getJson<WorkflowPageDTO>(`/app/v1/workflows${query}`);
}

export function getWorkflow(request: GetWorkflowRequest): Promise<WorkflowResponseDTO> {
  return getJson<WorkflowResponseDTO>(`/app/v1/workflows/${request.workflowId}`);
}

export function testDraftWorkflowState(
  request: DraftStateTestRequest,
): Promise<DraftStateTestResponse> {
  return sendJson<DraftStateTestResponse>(
    '/app/v1/workflows/draft-tests/state',
    'POST',
    request,
  );
}

export interface FunctionLanguageDTO {
  id: number;
  name: string;
  multiFileSupported: boolean;
}

export interface Judge0LimitsDTO {
  cpuTimeLimit: number | null;
  maxCpuTimeLimit: number | null;
  wallTimeLimit: number | null;
  maxWallTimeLimit: number | null;
  memoryLimit: number | null;
  maxMemoryLimit: number | null;
  maxFileSize: number | null;
  maxExtractSize: number | null;
  enableNetwork: boolean | null;
  allowEnableNetwork: boolean | null;
}

export interface Judge0RuntimeInfoDTO {
  reachable: boolean;
  error: string | null;
  languageCount: number;
  statusCount: number;
  workers: number;
  availableWorkers: number;
  languages: FunctionLanguageDTO[];
  limits: Judge0LimitsDTO | null;
}

export function getFunctionRuntimeInfo(): Promise<Judge0RuntimeInfoDTO> {
  return getJson<Judge0RuntimeInfoDTO>('/app/v1/functions/runtime');
}

async function sendJson<T>(url: string, method: string, body: unknown): Promise<T> {
  const response = await fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body ?? {}),
  });

  if (!response.ok) {
    throw new Error(await readError(response));
  }

  return response.json();
}

export type FunctionStatus = 'ENABLED' | 'DISABLED' | 'ARCHIVED';
export type FunctionVersionStatus = 'DRAFT' | 'AVAILABLE' | 'ARCHIVED';
export type FunctionSourceMode = 'SINGLE_FILE' | 'MULTI_FILE';
export type FunctionInvocationStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export interface FunctionDefinitionDTO {
  id: string;
  name: string;
  description: string | null;
  activeVersion: number | null;
  status: FunctionStatus;
  createdAt: string;
  updatedAt: string;
}

export interface FunctionDefinitionRequest {
  name: string;
  description?: string | null;
  status?: FunctionStatus | null;
}

export interface FunctionVersionDTO {
  id: string;
  functionId: string;
  version: number;
  sourceMode: FunctionSourceMode;
  languageId: number;
  hasSourceCode: boolean;
  hasAdditionalFiles: boolean;
  sourceCode: string | null;
  additionalFilesBase64: string | null;
  files: FunctionSourceFileDTO[];
  compilerOptions: string | null;
  commandLineArguments: string | null;
  cpuTimeLimitSeconds: number;
  wallTimeLimitSeconds: number;
  memoryLimitKb: number;
  maxFileSizeKb: number;
  maxOutputBytes: number;
  enableNetwork: boolean;
  note: string | null;
  testCases: FunctionTestCase[];
  status: FunctionVersionStatus;
  createdAt: string;
  updatedAt: string;
}

export interface FunctionSourceFileDTO {
  path: string;
  content: string;
}

export interface FunctionTestCase {
  name: string;
  input: string;
  expectedOutput: string;
  expectedError: string;
}

export interface FunctionVersionRequest {
  sourceMode?: FunctionSourceMode;
  languageId: number;
  sourceCode?: string | null;
  additionalFilesBase64?: string | null;
  compilerOptions?: string | null;
  commandLineArguments?: string | null;
  cpuTimeLimitSeconds?: number | null;
  wallTimeLimitSeconds?: number | null;
  memoryLimitKb?: number | null;
  maxFileSizeKb?: number | null;
  maxOutputBytes?: number | null;
  enableNetwork?: boolean | null;
  note?: string | null;
  testCases?: FunctionTestCase[] | null;
  status?: FunctionVersionStatus | null;
}

export interface FunctionVersionSettingsRequest {
  compilerOptions?: string | null;
  commandLineArguments?: string | null;
  cpuTimeLimitSeconds: number;
  wallTimeLimitSeconds: number;
  memoryLimitKb: number;
  maxFileSizeKb: number;
  maxOutputBytes: number;
  enableNetwork: boolean;
}

export interface FunctionInvocationDTO {
  id: string;
  functionId: string;
  version: number;
  workflowExecutionId: string | null;
  stateName: string | null;
  judge0Token: string | null;
  status: FunctionInvocationStatus;
  input: unknown;
  output: unknown;
  stdout: string | null;
  stderr: string | null;
  compileOutput: string | null;
  message: string | null;
  exitCode: number | null;
  exitSignal: number | null;
  judge0StatusId: number | null;
  judge0StatusDescription: string | null;
  errorName: string | null;
  errorMessage: string | null;
  timeSeconds: number | null;
  wallTimeSeconds: number | null;
  memoryKb: number | null;
  startedAt: string;
  completedAt: string | null;
  durationMs: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface FunctionTestInvocationRequest {
  version?: number | null;
  input: unknown;
}

export function listFunctionLanguages(): Promise<FunctionLanguageDTO[]> {
  return getJson<FunctionLanguageDTO[]>('/app/v1/functions/languages');
}

export interface ListFunctionsRequest {
  includeArchived?: boolean;
}

export function listFunctions(request: ListFunctionsRequest = {}): Promise<FunctionDefinitionDTO[]> {
  const query = buildQuery({
    includeArchived: request.includeArchived ? 'true' : undefined,
  });

  return getJson<FunctionDefinitionDTO[]>(`/app/v1/functions${query}`);
}

export function getFunctionDefinition(functionId: string): Promise<FunctionDefinitionDTO> {
  return getJson<FunctionDefinitionDTO>(`/app/v1/functions/${functionId}`);
}

export function createFunctionDefinition(request: FunctionDefinitionRequest): Promise<FunctionDefinitionDTO> {
  return sendJson<FunctionDefinitionDTO>('/app/v1/functions', 'POST', request);
}

export function updateFunctionDefinition(functionId: string, request: FunctionDefinitionRequest): Promise<FunctionDefinitionDTO> {
  return sendJson<FunctionDefinitionDTO>(`/app/v1/functions/${functionId}`, 'PUT', request);
}

export async function deleteFunctionDefinition(functionId: string): Promise<FunctionDefinitionDTO> {
  const response = await fetch(`/app/v1/functions/${functionId}`, {
    method: 'DELETE',
  });

  if (!response.ok) {
    throw new Error(await readError(response));
  }

  return response.json();
}

export function listFunctionVersions(functionId: string): Promise<FunctionVersionDTO[]> {
  return getJson<FunctionVersionDTO[]>(`/app/v1/functions/${functionId}/versions`);
}

export function createFunctionVersion(functionId: string, request: FunctionVersionRequest): Promise<FunctionVersionDTO> {
  return sendJson<FunctionVersionDTO>(`/app/v1/functions/${functionId}/versions`, 'POST', request);
}

// Updates a version's note, execution settings, and test cases in place.
// Code, language, and status are ignored server-side, so this is safe on
// published versions — it can never change what the version runs.
export function updateFunctionVersionMetadata(
  functionId: string,
  version: number,
  request: FunctionVersionRequest,
): Promise<FunctionVersionDTO> {
  return sendJson<FunctionVersionDTO>(`/app/v1/functions/${functionId}/versions/${version}/metadata`, 'PUT', request);
}

// Overwrites a DRAFT version in place. The backend rejects non-draft versions
// so published/active versions stay immutable.
export function updateFunctionVersion(
  functionId: string,
  version: number,
  request: FunctionVersionRequest,
): Promise<FunctionVersionDTO> {
  return sendJson<FunctionVersionDTO>(`/app/v1/functions/${functionId}/versions/${version}`, 'PUT', request);
}

export function activateFunctionVersion(functionId: string, version: number): Promise<FunctionDefinitionDTO> {
  return sendJson<FunctionDefinitionDTO>(`/app/v1/functions/${functionId}/versions/${version}/activate`, 'POST', {});
}

export function publishFunctionVersion(functionId: string, version: number): Promise<FunctionVersionDTO> {
  return sendJson<FunctionVersionDTO>(`/app/v1/functions/${functionId}/versions/${version}/publish`, 'POST', {});
}

export function updateFunctionVersionSettings(
  functionId: string,
  version: number,
  request: FunctionVersionSettingsRequest,
): Promise<FunctionVersionDTO> {
  return sendJson<FunctionVersionDTO>(`/app/v1/functions/${functionId}/versions/${version}/settings`, 'PUT', request);
}

export function testInvokeFunction(functionId: string, request: FunctionTestInvocationRequest): Promise<FunctionInvocationDTO> {
  return sendJson<FunctionInvocationDTO>(`/app/v1/functions/${functionId}/test-invocations`, 'POST', request);
}

export function listFunctionInvocations(functionId: string): Promise<FunctionInvocationDTO[]> {
  return getJson<FunctionInvocationDTO[]>(`/app/v1/functions/${functionId}/invocations`);
}

export interface FunctionRunRequest {
  languageId: number;
  sourceMode?: FunctionSourceMode;
  sourceCode?: string | null;
  additionalFilesBase64?: string | null;
  compilerOptions?: string | null;
  commandLineArguments?: string | null;
  cpuTimeLimitSeconds?: number | null;
  wallTimeLimitSeconds?: number | null;
  memoryLimitKb?: number | null;
  maxFileSizeKb?: number | null;
  maxOutputBytes?: number | null;
  enableNetwork?: boolean | null;
  input: unknown;
}

export interface FunctionRunResult {
  status: FunctionInvocationStatus;
  output: unknown;
  stdout: string | null;
  stderr: string | null;
  compileOutput: string | null;
  message: string | null;
  exitCode: number | null;
  judge0StatusId: number | null;
  judge0StatusDescription: string | null;
  errorName: string | null;
  errorMessage: string | null;
  timeSeconds: number | null;
  memoryKb: number | null;
}

export function executeFunctionCode(request: FunctionRunRequest): Promise<FunctionRunResult> {
  return sendJson<FunctionRunResult>('/app/v1/functions/run', 'POST', request);
}

export function getWorkflowRevisions(
  request: GetWorkflowRevisionsRequest,
): Promise<WorkflowDefinitionResponseDTO[]> {
  return getJson<WorkflowDefinitionResponseDTO[]>(`/app/v1/workflows/${request.workflowId}/revisions`);
}

export function createWorkflowRevision(
  workflowId: string,
  request: CreateWorkflowRevisionRequest,
): Promise<WorkflowDefinitionResponseDTO> {
  return sendJson<WorkflowDefinitionResponseDTO>(
    `/app/v1/workflows/${workflowId}/revisions`,
    'POST',
    request,
  );
}

export function updateWorkflowMetadata(
  workflowId: string,
  request: UpdateWorkflowMetadataRequest,
): Promise<WorkflowResponseDTO> {
  return sendJson<WorkflowResponseDTO>(
    `/app/v1/workflows/${workflowId}`,
    'PATCH',
    request,
  );
}

export function pauseWorkflow(workflowId: string): Promise<WorkflowResponseDTO> {
  return sendJson<WorkflowResponseDTO>(
    `/app/v1/workflows/${workflowId}/pause`,
    'POST',
    {},
  );
}

export function resumeWorkflow(workflowId: string): Promise<WorkflowResponseDTO> {
  return sendJson<WorkflowResponseDTO>(
    `/app/v1/workflows/${workflowId}/resume`,
    'POST',
    {},
  );
}

export function archiveWorkflow(workflowId: string): Promise<WorkflowResponseDTO> {
  return sendJson<WorkflowResponseDTO>(
    `/app/v1/workflows/${workflowId}/archive`,
    'POST',
    {},
  );
}

export function activateWorkflowRevision(
  workflowId: string,
  revision: number,
): Promise<WorkflowDefinitionResponseDTO> {
  return sendJson<WorkflowDefinitionResponseDTO>(
    `/app/v1/workflows/${workflowId}/revisions/${revision}/activate`,
    'POST',
    {},
  );
}

export function updateWorkflowCanvasLayout(
  workflowId: string,
  revision: number,
  positions: CanvasNodePositions,
): Promise<WorkflowDefinitionResponseDTO> {
  return sendJson<WorkflowDefinitionResponseDTO>(
    `/app/v1/workflows/${workflowId}/revisions/${revision}/canvas-layout`,
    'PUT',
    { positions },
  );
}

export function listAiModels(): Promise<AiModelConfigDTO[]> {
  return getJson<AiModelConfigDTO[]>('/app/v1/ai/models');
}

export function listAllAiModels(): Promise<AiModelConfigDTO[]> {
  return getJson<AiModelConfigDTO[]>('/app/v1/ai/models/all');
}

export async function createAiModel(request: AiModelConfigRequest): Promise<AiModelConfigDTO> {
  const response = await fetch('/app/v1/ai/models', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new Error(`Failed to add model: ${await readError(response)}`);
  }

  return response.json();
}

export async function testAiModel(request: AiModelTestRequest): Promise<AiModelTestResponse> {
  const response = await fetch('/app/v1/ai/models/test', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new Error(`Failed to test model: ${await readError(response)}`);
  }

  return response.json();
}

export async function discoverAiModels(request: AiModelDiscoverRequest): Promise<AiModelConfigDTO[]> {
  const response = await fetch('/app/v1/ai/models/discover', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new Error(`Failed to discover models: ${await readError(response)}`);
  }

  return response.json();
}

export async function setAiModelEnabled(
  modelId: string,
  request: AiModelEnabledRequest,
): Promise<AiModelConfigDTO> {
  const response = await fetch(`/app/v1/ai/models/${modelId}/enabled`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new Error(`Failed to update model: ${await readError(response)}`);
  }

  return response.json();
}

export async function deleteAiModel(modelId: string): Promise<void> {
  const response = await fetch(`/app/v1/ai/models/${modelId}`, {
    method: 'DELETE',
  });

  if (!response.ok) {
    throw new Error(`Failed to delete model: ${await readError(response)}`);
  }
}
export function listWorkflowAiConversations(): Promise<WorkflowAiConversationSummaryDTO[]> {
  return getJson<WorkflowAiConversationSummaryDTO[]>('/app/v1/workflow-ai/conversations');
}

export function getWorkflowAiConversation(conversationId: string): Promise<WorkflowAiConversationDetailDTO> {
  return getJson<WorkflowAiConversationDetailDTO>(`/app/v1/workflow-ai/conversations/${conversationId}`);
}

export async function createWorkflow(request: CreateWorkflowRequest): Promise<WorkflowResponseDTO> {
  const response = await fetch('/app/v1/workflows', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new Error(`Failed to create workflow: ${await readError(response)}`);
  }

  return response.json();
}

export async function generateWorkflow(request: WorkflowGenerationRequest): Promise<WorkflowGenerationResponse> {
  const response = await fetch('/app/v1/workflows/generate', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new Error(`Failed to generate workflow: ${await readError(response)}`);
  }

  return response.json();
}

function websocketUrl() {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}/ws`;
}

function encodeStompFrame(command: string, headers: Record<string, string>, body = '') {
  const headerText = Object.entries(headers)
    .map(([key, value]) => `${key}:${value}`)
    .join('\n');
  return `${command}\n${headerText}\n\n${body}\0`;
}

function parseStompFrames(data: string) {
  return data
    .split('\0')
    .map((frame) => frame.trim())
    .filter(Boolean)
    .map((frame) => {
      const separator = frame.indexOf('\n\n');
      const head = separator >= 0 ? frame.slice(0, separator) : frame;
      const body = separator >= 0 ? frame.slice(separator + 2) : '';
      const [command] = head.split('\n');
      return { command, body };
    });
}

function sendWorkflowAiSocket(destination: string, payload: unknown): Promise<WorkflowAiResponse> {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(websocketUrl());
    const timeout = window.setTimeout(() => {
      socket.close();
      reject(new Error('Workflow AI socket timed out'));
    }, 120000);

    const cleanup = () => {
      window.clearTimeout(timeout);
      socket.onopen = null;
      socket.onmessage = null;
      socket.onerror = null;
      socket.onclose = null;
    };

    socket.onerror = () => {
      cleanup();
      reject(new Error('Workflow AI socket connection failed'));
    };

    socket.onopen = () => {
      socket.send(encodeStompFrame('CONNECT', {
        'accept-version': '1.2',
        'heart-beat': '0,0',
      }));
    };

    socket.onmessage = (event) => {
      const frames = parseStompFrames(String(event.data));
      for (const frame of frames) {
        if (frame.command === 'CONNECTED') {
          socket.send(encodeStompFrame('SUBSCRIBE', {
            id: 'workflow-ai-response',
            destination: '/user/queue/workflow-ai',
          }));
          socket.send(encodeStompFrame('SEND', {
            destination,
            'content-type': 'application/json',
          }, JSON.stringify(payload)));
        }
        if (frame.command === 'MESSAGE') {
          cleanup();
          socket.close();
          resolve(JSON.parse(frame.body) as WorkflowAiResponse);
        }
        if (frame.command === 'ERROR') {
          cleanup();
          socket.close();
          reject(new Error(frame.body || 'Workflow AI socket error'));
        }
      }
    };
  });
}

export function startWorkflowAiConversation(request: WorkflowAiStartRequest): Promise<WorkflowAiResponse> {
  return sendWorkflowAiSocket('/app/workflow-ai/start', request);
}

export function continueWorkflowAiConversation(request: WorkflowAiChatRequest): Promise<WorkflowAiResponse> {
  return sendWorkflowAiSocket('/app/workflow-ai/message', request);
}

export function reviewWorkflowAiAsl(request: WorkflowAiReviewAslRequest): Promise<WorkflowAiResponse> {
  return sendWorkflowAiSocket('/app/workflow-ai/review-asl', request);
}

export function acceptWorkflowAiPlan(request: WorkflowAiAcceptPlanRequest): Promise<WorkflowAiResponse> {
  return sendWorkflowAiSocket('/app/workflow-ai/accept', request);
}

export async function regenerateWorkflowAiMessage(
  messageId: string,
  request: WorkflowAiRegenerateRequest,
): Promise<WorkflowAiResponse> {
  const response = await fetch(`/app/v1/workflow-ai/messages/${messageId}/regenerate`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new Error(`Failed to regenerate message: ${await readError(response)}`);
  }

  return response.json();
}
