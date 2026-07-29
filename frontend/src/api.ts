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
  | 'RESOURCES_PROPOSED'
  | 'ASL_READY'
  | 'ASL_UNDER_REVIEW'
  | 'COLLECTING_SCHEDULE_DETAILS'
  | 'PLAN_READY'
  | 'ACCEPTED';

export interface WorkflowAiFunctionTestCase {
  name?: string | null;
  input?: string | null;
  expectedOutput?: string | null;
  expectedError?: string | null;
}

export interface WorkflowAiProposedFunction {
  name: string;
  description?: string | null;
  languageId?: number | null;
  sourceCode?: string | null;
  testCases?: WorkflowAiFunctionTestCase[] | null;
  rationale?: string | null;
}

export interface WorkflowAiMcpRequirement {
  capability: string;
  suggestedToolName?: string | null;
  reason?: string | null;
  trustLevelHint?: string | null;
}

export interface WorkflowAiResourcePlan {
  functions?: WorkflowAiProposedFunction[] | null;
  mcpRequirements?: WorkflowAiMcpRequirement[] | null;
}

export interface WorkflowAiProvisionRequest {
  conversationId: string;
  functions: WorkflowAiProposedFunction[];
  modelConfigId?: string | null;
}

export interface AiModelConfigDTO {
  id: string;
  displayName: string;
  providerType: 'OPENAI_COMPATIBLE_LOCAL' | 'OPENAI_COMPATIBLE_API';
  baseUrl: string;
  modelName: string;
  enabled: boolean;
  defaultModel: boolean;
  hasCredential: boolean;
  structuredOutputMode: AiStructuredOutputMode;
}

export type AiStructuredOutputMode =
  | 'UNKNOWN'
  | 'STRICT_JSON_SCHEMA'
  | 'JSON_SCHEMA'
  | 'JSON_OBJECT'
  | 'PROMPT_ONLY';

export interface AiModelConfigRequest {
  displayName: string;
  providerType?: 'OPENAI_COMPATIBLE_LOCAL' | 'OPENAI_COMPATIBLE_API';
  baseUrl: string;
  modelName: string;
  // Actual secret / API key, encrypted server-side. Omit to leave unchanged; "" to clear.
  credential?: string | null;
  defaultModel: boolean;
}

export interface AiModelTestRequest {
  baseUrl: string;
  modelName?: string | null;
  credential?: string | null;
}

export interface AiModelTestResponse {
  success: boolean;
  message: string;
}

export interface AiModelDiscoverRequest {
  baseUrl: string;
  credential?: string | null;
  providerType?: 'OPENAI_COMPATIBLE_LOCAL' | 'OPENAI_COMPATIBLE_API';
}

export interface AiModelEnabledRequest {
  enabled: boolean;
}

export type AiModelEvaluationMode = 'QUICK' | 'RELIABILITY';
export type AiModelEvaluationStatus = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type AiModelRecommendation = 'RECOMMENDED' | 'LIMITED' | 'FAILED';

export interface AiModelEvaluationMetric {
  passed: number;
  total: number;
  rate: number;
  failures: string[];
}

export type AiModelJudgeVerdict = 'STRONG' | 'MIXED' | 'WEAK' | 'UNSCORED';

/** Advisory LLM-as-judge aggregate; present only when the run was started with a judge model. */
export interface AiModelEvaluationJudgeSummary {
  modelConfigId: string;
  modelName: string;
  displayName: string;
  passScore: number;
  judgedCases: number;
  scoredCases: number;
  erroredCases: number;
  meanScore: number;
  passRate: number;
  verdict: AiModelJudgeVerdict;
  failures: string[];
  errors: string[];
}

/** One graded metric within a single case run. */
export interface AiModelEvaluationObservationMetric {
  passed: boolean;
  detail?: string;
}

/** Summary of what the model returned for a single case run. */
export interface AiModelEvaluationObservationResponse {
  stage: string;
  message: string;
  hasAsl: boolean;
  validationIssueCount: number;
  proposedFunctionCount: number;
  proposedMcpCount: number;
  validationIssues: string[];
  /** The raw model reply (bounded), including an ASL that was rejected in validation. */
  rawModelReply?: string | null;
}

/** A single case run: the exact prompt, the graded metrics, and the model's response. */
export interface AiModelEvaluationObservation {
  caseId: string;
  category: string;
  instruction: string;
  repetition: number;
  startedAt: string;
  latencyMs: number;
  passed: boolean;
  metrics: Record<string, AiModelEvaluationObservationMetric>;
  response?: AiModelEvaluationObservationResponse | null;
  error?: string | null;
  judge?: {
    score?: number;
    passed?: boolean;
    rationale?: string;
    error?: string;
    latencyMs: number;
  } | null;
}

export interface AiModelEvaluationResult {
  suiteId: string;
  suiteDescription: string;
  promptFingerprint: string;
  mode: AiModelEvaluationMode;
  repetitions: number;
  modelName: string;
  providerType: AiModelConfigDTO['providerType'];
  structuredOutputMode: AiStructuredOutputMode;
  metrics: Record<string, AiModelEvaluationMetric>;
  observations: AiModelEvaluationObservation[];
  qualityGates: Record<string, {
    minimum: number;
    actual: number;
    passed: boolean;
  }>;
  capabilities: {
    chat: number;
    asl: number;
    mcp: number;
    functions: number;
    safety: number;
  };
  judge?: AiModelEvaluationJudgeSummary | null;
  summary: {
    passedCases: number;
    totalCases: number;
    casePassRate: number;
    qualityGatesPassed: boolean;
    recommendation: AiModelRecommendation;
    latencyP50Ms: number;
    latencyP95Ms: number;
  };
}

export interface AiModelEvaluationDTO {
  runId: string;
  modelConfigId: string;
  modelDisplayName: string;
  status: AiModelEvaluationStatus;
  mode: AiModelEvaluationMode;
  repetitions: number;
  completedCases: number;
  totalCases: number;
  cancelRequested: boolean;
  stale?: boolean;
  result?: AiModelEvaluationResult | null;
  /** Cases finished so far while status is RUNNING; null once the run ends (see result.observations). */
  progressObservations?: AiModelEvaluationObservation[] | null;
  errorMessage?: string | null;
  startedAt: string;
  finishedAt?: string | null;
}

export interface AiModelEvaluationHistoryDTO {
  runs: AiModelEvaluationDTO[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
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
  /** ASL already open in the editor, so the assistant amends it instead of starting over. */
  definition?: any;
  /** Raw editor buffer, sent even when incomplete/invalid so the AI can see in-progress edits. */
  definitionText?: string | null;
}

export interface WorkflowAiChatRequest {
  conversationId: string;
  message: string;
  modelConfigId?: string | null;
  /** ASL already open in the editor, so the assistant amends it instead of starting over. */
  definition?: any;
  /** Raw editor buffer, sent even when incomplete/invalid so the AI can see in-progress edits. */
  definitionText?: string | null;
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
  resourcePlan?: WorkflowAiResourcePlan | null;
  resourcePlanMessageId?: string | null;
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
  resourcePlan?: WorkflowAiResourcePlan | null;
  createdAt: string;
}

export interface WorkflowAiConversationDetailDTO extends WorkflowAiConversationSummaryDTO {
  aslDefinition?: any | null;
  /** Exact autosaved editor buffer; may contain temporarily invalid/incomplete JSON. */
  workspaceDefinitionText?: string | null;
  finalPlan?: any | null;
  draftWorkflowPayload?: CreateWorkflowRequest | null;
  resourcePlan?: WorkflowAiResourcePlan | null;
  resourcePlanMessageId?: string | null;
  canvasLayout?: Record<string, { x: number; y: number }> | null;
  workspaceSettings?: WorkflowAiWorkspaceSettingsDTO | null;
  /** Workflow created from this chat; later saves create revisions of it. */
  workflowId?: string | null;
  messages: WorkflowAiMessageDTO[];
}

export interface WorkflowAiWorkspaceSettingsDTO {
  name?: string | null;
  cronExpression?: string | null;
  maxAttempts?: number | null;
  idempotencyKey?: string | null;
  timezone?: string | null;
}

export interface WorkflowAiWorkspaceRequest {
  /** Exact editor buffer. The backend promotes it to authoritative ASL only when valid. */
  definitionText: string;
  canvasLayout: Record<string, { x: number; y: number }>;
  settings: WorkflowAiWorkspaceSettingsDTO;
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

export type WorkflowExecutionStatusDTO =
  | 'PENDING'
  | 'QUEUED'
  | 'RUNNING'
  | 'WAITING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELED'
  | 'TIMED_OUT';

export type WorkflowExecutionTriggerDTO = 'MANUAL' | 'SCHEDULED';

export interface ListWorkflowExecutionsRequest {
  page?: number;
  size?: number;
  status?: WorkflowExecutionStatusDTO;
  revision?: number;
  trigger?: WorkflowExecutionTriggerDTO;
  search?: string;
}

export type WorkflowRuntimeStatusDTO =
  | 'PENDING'
  | 'QUEUED'
  | 'RUNNING'
  | 'WAITING'
  | 'RETRY_WAIT'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELED'
  | 'TIMED_OUT';

export interface StartWorkflowExecutionRequest {
  input: unknown;
}

export interface WorkflowExecutionResponseDTO {
  workflowExecutionId: string;
  status: WorkflowExecutionStatusDTO;
  output: unknown;
  error: string | null;
  cause: string | null;
  wakeAt: string | null;
  stateExecutionAttemptId: string | null;
}

export interface WorkflowExecutionSummaryDTO {
  id: string;
  workflowId: string;
  workflowDefinitionId: string;
  definitionRevision: number;
  runNumber: number;
  status: WorkflowExecutionStatusDTO;
  scheduledFor: string | null;
  input: unknown;
  output: unknown;
  error: string | null;
  cause: string | null;
  deadlineAt: string | null;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface WorkflowStateExecutionAttemptDTO {
  id: string;
  attemptNumber: number;
  status: WorkflowRuntimeStatusDTO;
  arguments: unknown;
  result: unknown;
  workerId: string | null;
  availableAt: string | null;
  queuedAt: string | null;
  startedAt: string | null;
  heartbeatAt: string | null;
  timeoutSeconds: number | null;
  heartbeatSeconds: number | null;
  timeoutAt: string | null;
  heartbeatDeadlineAt: string | null;
  completedAt: string | null;
  durationMs: number | null;
  error: string | null;
  cause: string | null;
  dispatchAttemptCount: number;
  lastDispatchError: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface WorkflowStateExecutionDTO {
  id: string;
  sequenceNumber: number;
  stateName: string;
  stateType: 'PASS' | 'TASK' | 'CHOICE' | 'WAIT' | 'SUCCEED' | 'FAIL' | 'PARALLEL' | 'MAP';
  status: WorkflowRuntimeStatusDTO;
  resource: string | null;
  input: unknown;
  output: unknown;
  retryAt: string | null;
  error: string | null;
  cause: string | null;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
  updatedAt: string;
  attempts: WorkflowStateExecutionAttemptDTO[];
}

export interface WorkflowExecutionScopeDTO {
  id: string;
  parentScopeId: string | null;
  scopeType: 'ROOT' | 'PARALLEL_BRANCH' | 'MAP_ITERATION';
  scopePath: string;
  ownerStateName: string | null;
  branchIndex: number | null;
  itemIndex: number | null;
  status: WorkflowRuntimeStatusDTO;
  currentStateName: string | null;
  currentStateInput: unknown;
  variables: unknown;
  output: unknown;
  wakeAt: string | null;
  error: string | null;
  cause: string | null;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
  updatedAt: string;
  stateExecutions: WorkflowStateExecutionDTO[];
}

export interface WorkflowExecutionDetailDTO {
  execution: WorkflowExecutionSummaryDTO;
  scopes: WorkflowExecutionScopeDTO[];
}

export interface WorkflowExecutionPageDTO {
  content: WorkflowExecutionSummaryDTO[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface WorkflowExecutionCancellationResponseDTO {
  workflowExecutionId: string;
  status: WorkflowExecutionStatusDTO;
  error: string | null;
  cause: string | null;
  completedAt: string | null;
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

export function startWorkflowExecution(
  workflowId: string,
  request: StartWorkflowExecutionRequest,
): Promise<WorkflowExecutionResponseDTO> {
  return sendJson<WorkflowExecutionResponseDTO>(
    `/app/v1/workflows/${workflowId}/executions`,
    'POST',
    request,
  );
}

export function listWorkflowExecutions(
  workflowId: string,
  request: ListWorkflowExecutionsRequest = {},
): Promise<WorkflowExecutionPageDTO> {
  const query = buildQuery({
    page: request.page ?? 0,
    size: request.size ?? 20,
    status: request.status,
    revision: request.revision,
    trigger: request.trigger,
    search: request.search,
  });
  return getJson<WorkflowExecutionPageDTO>(
    `/app/v1/workflows/${workflowId}/executions${query}`,
  );
}

export function getWorkflowExecution(
  workflowId: string,
  executionId: string,
): Promise<WorkflowExecutionDetailDTO> {
  return getJson<WorkflowExecutionDetailDTO>(
    `/app/v1/workflows/${workflowId}/executions/${executionId}`,
  );
}

export interface WorkflowTriagePatch {
  hasPatch: boolean;
  aslDefinition: any | null;
  changes: string[];
  valid: boolean;
  validationIssues: string[];
}

export interface WorkflowTriageResponse {
  executionId: string;
  failingStateName: string | null;
  rootCause: string;
  explanation: string;
  patch: WorkflowTriagePatch;
}

/** AI diagnosis of a failed execution: root cause + an optional validated ASL patch. */
export function triageWorkflowExecution(
  workflowId: string,
  executionId: string,
  modelConfigId?: string | null,
): Promise<WorkflowTriageResponse> {
  return sendJson<WorkflowTriageResponse>(
    `/app/v1/workflows/${workflowId}/executions/${executionId}/triage`,
    'POST',
    { modelConfigId: modelConfigId ?? null },
  );
}

export function cancelWorkflowExecution(
  workflowId: string,
  executionId: string,
): Promise<WorkflowExecutionCancellationResponseDTO> {
  return sendJson<WorkflowExecutionCancellationResponseDTO>(
    `/app/v1/workflows/${workflowId}/executions/${executionId}/cancel`,
    'POST',
    {},
  );
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

export function listLatestAiModelEvaluations(): Promise<AiModelEvaluationDTO[]> {
  return getJson<AiModelEvaluationDTO[]>('/app/v1/ai/models/evaluations/latest');
}

export function listAiModelEvaluationHistory(
  modelId: string,
  page = 0,
  size = 10,
): Promise<AiModelEvaluationHistoryDTO> {
  const query = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  return getJson<AiModelEvaluationHistoryDTO>(
    `/app/v1/ai/models/${encodeURIComponent(modelId)}/evaluations?${query.toString()}`,
  );
}

export function startAiModelEvaluation(
  modelId: string,
  mode: AiModelEvaluationMode,
  judgeModelConfigId?: string | null,
): Promise<AiModelEvaluationDTO> {
  return sendJson<AiModelEvaluationDTO>(
    `/app/v1/ai/models/${encodeURIComponent(modelId)}/evaluations`,
    'POST',
    { mode, judgeModelConfigId: judgeModelConfigId ?? null },
  );
}

export function cancelAiModelEvaluation(
  modelId: string,
  runId: string,
): Promise<AiModelEvaluationDTO> {
  return sendJson<AiModelEvaluationDTO>(
    `/app/v1/ai/models/${encodeURIComponent(modelId)}/evaluations/${encodeURIComponent(runId)}/cancel`,
    'POST',
    {},
  );
}

export function getWorkflowAiConversation(
  conversationId: string,
): Promise<WorkflowAiConversationDetailDTO> {
  return getJson<WorkflowAiConversationDetailDTO>(
    `/app/v1/workflow-ai/conversations/${encodeURIComponent(conversationId)}`,
  );
}

export interface WorkflowAiSaveWorkflowRequest {
  workflow: CreateWorkflowRequest;
  canvasLayout: CanvasNodePositions;
  /** Set once the user has acknowledged the WRITE/DESTRUCTIVE MCP tools this workflow calls. */
  confirmElevatedTrust?: boolean;
}

export interface WorkflowAiSaveWorkflowResponse {
  workflow: WorkflowResponseDTO;
  revision: WorkflowDefinitionResponseDTO;
}

/** An MCP call that grants WRITE/DESTRUCTIVE trust, surfaced for explicit confirmation. */
export interface ElevatedMcpTool {
  /** ASL state that makes the call. */
  stateName: string;
  /** Trust granted by the call, e.g. "WRITE crm/create-lead (CRM)". */
  detail: string;
}

/**
 * Thrown by the workflow-save calls when the definition wires in WRITE/DESTRUCTIVE MCP tools
 * that the user has not yet confirmed. Retry the same save with confirmElevatedTrust: true.
 */
export class TrustConfirmationRequiredError extends Error {
  readonly tools: ElevatedMcpTool[];
  constructor(message: string, tools: ElevatedMcpTool[]) {
    super(message);
    this.name = 'TrustConfirmationRequiredError';
    this.tools = tools;
  }
}

/** Returns the typed error when a save failed only for lack of trust confirmation, else null. */
async function readTrustConfirmation(response: Response): Promise<TrustConfirmationRequiredError | null> {
  if (response.status !== 409) {
    return null;
  }
  const body = await response.clone().json().catch(() => null);
  if (!body || body.error !== 'MCP_TRUST_CONFIRMATION_REQUIRED') {
    return null;
  }
  const tools: ElevatedMcpTool[] = Array.isArray(body.fieldErrors)
    ? body.fieldErrors.map((entry: { field?: string; message?: string }) => ({
        stateName: entry.field ?? '',
        detail: entry.message ?? '',
      }))
    : [];
  return new TrustConfirmationRequiredError(
    body.message ?? 'This workflow calls MCP tools that can write or delete data.',
    tools,
  );
}

export function getWorkflowAiDraft(
  draftId: string,
): Promise<WorkflowAiConversationDetailDTO> {
  return getJson<WorkflowAiConversationDetailDTO>(
    `/app/v1/workflow-ai/drafts/${encodeURIComponent(draftId)}`,
  );
}

export function listWorkflowAiConversations(): Promise<WorkflowAiConversationSummaryDTO[]> {
  return getJson<WorkflowAiConversationSummaryDTO[]>('/app/v1/workflow-ai/conversations');
}

export function listWorkflowAiDrafts(): Promise<WorkflowAiConversationSummaryDTO[]> {
  return getJson<WorkflowAiConversationSummaryDTO[]>('/app/v1/workflow-ai/drafts');
}

async function renameWorkflowAiWorkspace(
  workspaceKind: 'conversations' | 'drafts',
  workspaceId: string,
  name: string,
): Promise<WorkflowAiConversationSummaryDTO> {
  const response = await fetch(
    `/app/v1/workflow-ai/${workspaceKind}/${encodeURIComponent(workspaceId)}/name`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
    },
  );
  if (!response.ok) {
    throw new Error(`Failed to rename workspace: ${await readError(response)}`);
  }
  return response.json();
}

export function renameWorkflowAiConversation(
  conversationId: string,
  name: string,
): Promise<WorkflowAiConversationSummaryDTO> {
  return renameWorkflowAiWorkspace('conversations', conversationId, name);
}

export function renameWorkflowAiDraft(
  draftId: string,
  name: string,
): Promise<WorkflowAiConversationSummaryDTO> {
  return renameWorkflowAiWorkspace('drafts', draftId, name);
}

export async function createWorkflowAiDraft(
  request: WorkflowAiWorkspaceRequest,
): Promise<WorkflowAiConversationDetailDTO> {
  const response = await fetch('/app/v1/workflow-ai/drafts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
  if (!response.ok) {
    throw new Error(`Failed to create workflow draft: ${await readError(response)}`);
  }
  return response.json();
}

export async function deleteWorkflowAiConversation(conversationId: string): Promise<void> {
  const response = await fetch(
    `/app/v1/workflow-ai/conversations/${encodeURIComponent(conversationId)}`,
    { method: 'DELETE' },
  );
  if (!response.ok) {
    throw new Error(`Failed to delete chat: ${await readError(response)}`);
  }
}

export async function deleteAllWorkflowAiConversations(): Promise<void> {
  const response = await fetch('/app/v1/workflow-ai/conversations', {
    method: 'DELETE',
  });
  if (!response.ok) {
    throw new Error(`Failed to delete chats: ${await readError(response)}`);
  }
}

export async function deleteWorkflowAiDraft(draftId: string): Promise<void> {
  const response = await fetch(
    `/app/v1/workflow-ai/drafts/${encodeURIComponent(draftId)}`,
    { method: 'DELETE' },
  );
  if (!response.ok) {
    throw new Error(`Failed to delete draft: ${await readError(response)}`);
  }
}

export async function deleteAllWorkflowAiDrafts(): Promise<void> {
  const response = await fetch('/app/v1/workflow-ai/drafts', { method: 'DELETE' });
  if (!response.ok) {
    throw new Error(`Failed to delete drafts: ${await readError(response)}`);
  }
}

export async function saveWorkflowAiWorkspace(
  conversationId: string,
  request: WorkflowAiWorkspaceRequest,
): Promise<void> {
  const response = await fetch(
    `/app/v1/workflow-ai/conversations/${encodeURIComponent(conversationId)}/workspace`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    },
  );
  if (!response.ok) {
    throw new Error(`Failed to save conversation workspace: ${await readError(response)}`);
  }
}

export async function saveWorkflowAiConversation(
  conversationId: string,
  request: WorkflowAiSaveWorkflowRequest,
): Promise<WorkflowAiSaveWorkflowResponse> {
  const response = await fetch(
    `/app/v1/workflow-ai/conversations/${encodeURIComponent(conversationId)}/workflow`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    },
  );
  if (!response.ok) {
    const trustError = await readTrustConfirmation(response);
    if (trustError) throw trustError;
    throw new Error(`Failed to save conversation workflow: ${await readError(response)}`);
  }
  return response.json();
}

export async function saveWorkflowAiDraft(
  draftId: string,
  request: WorkflowAiSaveWorkflowRequest,
): Promise<WorkflowAiSaveWorkflowResponse> {
  const response = await fetch(
    `/app/v1/workflow-ai/drafts/${encodeURIComponent(draftId)}/workflow`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    },
  );
  if (!response.ok) {
    const trustError = await readTrustConfirmation(response);
    if (trustError) throw trustError;
    throw new Error(`Failed to save draft workflow: ${await readError(response)}`);
  }
  return response.json();
}

export async function saveWorkflowAiDraftWorkspace(
  draftId: string,
  request: WorkflowAiWorkspaceRequest,
): Promise<void> {
  const response = await fetch(
    `/app/v1/workflow-ai/drafts/${encodeURIComponent(draftId)}/workspace`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    },
  );
  if (!response.ok) {
    throw new Error(`Failed to save workflow draft: ${await readError(response)}`);
  }
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
      const [command, ...headerLines] = head.split('\n');
      // The subscription header is what distinguishes a progress frame from the final reply, since
      // both arrive on the same socket.
      const subscription = headerLines
        .find((line) => line.startsWith('subscription:'))
        ?.slice('subscription:'.length)
        .trim();
      const heartBeat = headerLines
        .find((line) => line.startsWith('heart-beat:'))
        ?.slice('heart-beat:'.length)
        .trim();
      return { command, body, subscription, heartBeat };
    });
}

/**
 * Incremental frame emitted while a turn is still running. Only reasoning arrives verbatim: the
 * answer is a JSON envelope the backend parses and may discard, so it reports progress only.
 */
export interface WorkflowAiStreamEvent {
  conversationId: string;
  type: 'STAGE' | 'THINKING_DELTA' | 'ANSWER_PROGRESS' | 'ERROR';
  stage?: string | null;
  text?: string | null;
  /** Which model call of the turn produced this; a higher pass supersedes earlier output. */
  pass: number;
  answerCharacters: number;
}

const STREAM_SUBSCRIPTION = '/user/queue/workflow-ai-stream';
const RESPONSE_SUBSCRIPTION = '/user/queue/workflow-ai';
/** Heart-beat interval requested in the STOMP CONNECT, in ms (both directions). */
const HEARTBEAT_MS = 10000;
/** Miss this many heart-beat intervals with no inbound data and the connection is treated as dead. */
const HEARTBEAT_TOLERANCE = 2;
/**
 * Fallback liveness budget, used ONLY when the broker declines heart-beats (an older backend). With
 * heart-beats negotiated, a dead connection is caught in ~HEARTBEAT_MS * HEARTBEAT_TOLERANCE instead,
 * decoupled from how long a turn legitimately runs.
 */
const SOCKET_IDLE_TIMEOUT_MS = 180000;

/** Cancellation surfaces as an AbortError so callers can tell it apart from a real failure. */
export function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError';
}

function abortError(): Error {
  return Object.assign(new Error('Generation cancelled'), { name: 'AbortError' });
}

function sendWorkflowAiSocket(
  destination: string,
  payload: unknown,
  onEvent?: (event: WorkflowAiStreamEvent) => void,
  signal?: AbortSignal,
): Promise<WorkflowAiResponse> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(abortError());
      return;
    }

    const socket = new WebSocket(websocketUrl());
    // Liveness is proven by STOMP heart-beats negotiated on CONNECTED, not by guessing how long a turn
    // should take. Heart-beats flow from the broker independently of the turn thread, so even a long
    // blocking model call keeps the connection provably alive. The fixed idle timer is only a fallback
    // for a broker that declines heart-beats (e.g. an older backend).
    let idleTimer = 0;
    let livenessTimer = 0;
    let heartbeatSender = 0;
    let serverHeartbeatMs = 0;

    const cleanup = () => {
      window.clearTimeout(idleTimer);
      window.clearTimeout(livenessTimer);
      window.clearInterval(heartbeatSender);
      signal?.removeEventListener('abort', onAbort);
      socket.onopen = null;
      socket.onmessage = null;
      socket.onerror = null;
      socket.onclose = null;
    };

    const failDead = () => {
      cleanup();
      try {
        socket.close();
      } catch {
        // Already closing; the reject below is what matters.
      }
      reject(new Error('Workflow AI connection lost'));
    };

    const armIdleTimer = () => {
      window.clearTimeout(idleTimer);
      idleTimer = window.setTimeout(failDead, SOCKET_IDLE_TIMEOUT_MS);
    };

    // Any inbound data — a frame or a bare heart-beat newline — proves the connection is alive.
    const markInbound = () => {
      if (serverHeartbeatMs > 0) {
        window.clearTimeout(idleTimer);
        window.clearTimeout(livenessTimer);
        livenessTimer = window.setTimeout(failDead, serverHeartbeatMs * HEARTBEAT_TOLERANCE);
      } else {
        armIdleTimer();
      }
    };

    const onAbort = () => {
      cleanup();
      try {
        socket.close();
      } catch {
        // Already closing; the reject below is what matters.
      }
      reject(abortError());
    };

    signal?.addEventListener('abort', onAbort);
    armIdleTimer();

    socket.onerror = () => {
      cleanup();
      reject(new Error('Workflow AI socket connection failed'));
    };

    socket.onclose = () => {
      // A server- or network-initiated close; our own intentional closes null this handler first.
      failDead();
    };

    socket.onopen = () => {
      socket.send(encodeStompFrame('CONNECT', {
        'accept-version': '1.2',
        'heart-beat': `${HEARTBEAT_MS},${HEARTBEAT_MS}`,
      }));
    };

    socket.onmessage = (event) => {
      markInbound();
      const frames = parseStompFrames(String(event.data));
      for (const frame of frames) {
        if (frame.command === 'CONNECTED') {
          // Negotiate heart-beat intervals per the STOMP spec, then move liveness off the fallback
          // idle timer and onto heart-beat monitoring. Header is "<server-out>,<server-wants-in>".
          const [serverOutMs, serverWantsInMs] = (frame.heartBeat || '0,0')
            .split(',')
            .map((value) => Number(value) || 0);
          serverHeartbeatMs = serverOutMs === 0 ? 0 : Math.max(HEARTBEAT_MS, serverOutMs);
          const clientToServerMs = serverWantsInMs === 0
            ? 0
            : Math.max(HEARTBEAT_MS, serverWantsInMs);
          if (clientToServerMs > 0) {
            heartbeatSender = window.setInterval(() => {
              try {
                socket.send('\n');
              } catch {
                // Socket closing; cleanup will clear this interval.
              }
            }, clientToServerMs);
          }
          markInbound();

          socket.send(encodeStompFrame('SUBSCRIBE', {
            id: 'workflow-ai-response',
            destination: RESPONSE_SUBSCRIPTION,
          }));
          // Subscribe to progress before sending, so no frame is missed on a fast first token.
          socket.send(encodeStompFrame('SUBSCRIBE', {
            id: 'workflow-ai-stream',
            destination: STREAM_SUBSCRIPTION,
          }));
          socket.send(encodeStompFrame('SEND', {
            destination,
            'content-type': 'application/json',
          }, JSON.stringify(payload)));
        }
        if (frame.command === 'MESSAGE') {
          if (frame.subscription === 'workflow-ai-stream') {
            try {
              onEvent?.(JSON.parse(frame.body) as WorkflowAiStreamEvent);
            } catch {
              // A malformed progress frame is cosmetic; the authoritative reply still decides.
            }
            continue;
          }
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

export function startWorkflowAiConversation(
  request: WorkflowAiStartRequest,
  onEvent?: (event: WorkflowAiStreamEvent) => void,
  signal?: AbortSignal,
): Promise<WorkflowAiResponse> {
  return sendWorkflowAiSocket('/app/workflow-ai/start', request, onEvent, signal);
}

export function continueWorkflowAiConversation(
  request: WorkflowAiChatRequest,
  onEvent?: (event: WorkflowAiStreamEvent) => void,
  signal?: AbortSignal,
): Promise<WorkflowAiResponse> {
  return sendWorkflowAiSocket('/app/workflow-ai/message', request, onEvent, signal);
}

export async function provisionWorkflowAiResources(
  request: WorkflowAiProvisionRequest,
): Promise<WorkflowAiResponse> {
  const response = await fetch('/app/v1/workflow-ai/provision-resources', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new Error(`Failed to create resources: ${await readError(response)}`);
  }

  return response.json();
}

export async function regenerateWorkflowAiMessage(
  messageId: string,
  request: WorkflowAiRegenerateRequest,
): Promise<WorkflowAiResponse> {
  const response = await fetch(`/app/v1/workflow-ai/messages/${messageId}/regenerate`, {
    method: 'POST',
    // A retry is persisted by the backend. Let the small request outlive a page refresh so the
    // server can finish and the reloaded conversation can recover the regenerated message.
    keepalive: true,
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

export type McpTransport = 'HTTP' | 'STDIO';
export type McpAuthType = 'NONE' | 'BEARER_TOKEN' | 'API_KEY' | 'BASIC' | 'CUSTOM_HEADERS';
export type McpTrustLevel = 'UNTRUSTED' | 'READ_ONLY' | 'WRITE' | 'DESTRUCTIVE';
export type McpServerStatus = 'ENABLED' | 'DISABLED';
export type McpToolExecutionStatus = 'RUNNING' | 'SUCCESS' | 'FAILED' | 'REJECTED';

export interface McpServerDTO {
  id: string;
  serverId: string;
  displayName: string;
  // HTTP transport
  baseUrl: string | null;
  endpoint: string | null;
  // STDIO transport
  command: string | null;
  args: string[];
  env: Record<string, string>;
  // Names of configured secret env vars; values are never returned.
  secretEnvKeys: string[];
  // Names of configured encrypted custom auth headers; values are never returned.
  secretHeaderNames: string[];
  authEnvVar: string | null;
  transport: McpTransport;
  authType: McpAuthType;
  hasAuthToken: boolean;
  authHeaderName: string | null;
  authUsername: string | null;
  trustLevel: McpTrustLevel;
  status: McpServerStatus;
  requestTimeoutMs: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface McpServerRequest {
  serverId: string;
  displayName: string;
  // HTTP transport (required when transport is HTTP)
  baseUrl?: string | null;
  endpoint?: string | null;
  // STDIO transport (command required when transport is STDIO)
  command?: string | null;
  args?: string[] | null;
  env?: Record<string, string> | null;
  // Secret env vars (name -> plaintext value); encrypted server-side, never returned.
  // Omit a key to drop it; send an empty value to keep the existing encrypted value.
  secretEnv?: Record<string, string> | null;
  // CUSTOM_HEADERS auth values, encrypted server-side and never returned.
  // Blank values keep existing encrypted values; omitted names are removed.
  secretHeaders?: Record<string, string> | null;
  authEnvVar?: string | null;
  transport: McpTransport;
  authType: McpAuthType;
  // Actual token, encrypted server-side. Omit/blank to leave unchanged on edit.
  authToken?: string | null;
  authHeaderName?: string | null;
  authUsername?: string | null;
  trustLevel?: McpTrustLevel | null;
  status?: McpServerStatus | null;
  requestTimeoutMs?: number | null;
}

export interface McpToolDTO {
  id: string;
  serverId: string;
  toolName: string;
  title: string | null;
  description: string | null;
  inputSchema: unknown;
  outputSchema: unknown | null;
  enabled: boolean;
  lastSeenAt: string;
  createdAt: string;
  updatedAt: string;
}

export interface McpToolSyncResultDTO {
  serverId: string;
  discoveredCount: number;
  createdCount: number;
  updatedCount: number;
  disabledCount: number;
  syncedAt: string;
  tools: McpToolDTO[];
}

export interface McpToolExecutionDTO {
  id: string;
  serverId: string;
  toolName: string;
  arguments: unknown;
  result: unknown | null;
  status: McpToolExecutionStatus;
  maxAllowedTrustLevel: McpTrustLevel | null;
  errorMessage: string | null;
  startedAt: string;
  completedAt: string | null;
  durationMs: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface McpToolCallRequest {
  arguments: Record<string, unknown>;
  maxAllowedTrustLevel?: McpTrustLevel | null;
}

export interface McpLiveToolInfo {
  name: string;
  title?: string | null;
  description?: string | null;
  [key: string]: unknown;
}

export interface McpListToolsResult {
  tools?: McpLiveToolInfo[];
  nextCursor?: string | null;
}

export interface McpToolCallContentBlock {
  type: string;
  text?: string;
  [key: string]: unknown;
}

export interface McpToolCallResult {
  content?: McpToolCallContentBlock[];
  structuredContent?: unknown;
  isError?: boolean | null;
}

export type PublicMcpSource = 'BUNDLED' | 'EXTERNAL';

export interface PublicMcpEnvVar {
  name: string;
  description: string | null;
  secret: boolean;
  required: boolean;
  defaultValue: string | null;
}

export interface PublicMcpInstallOption {
  label: string;
  transport: McpTransport;
  command: string | null;
  args: string[] | null;
  baseUrl: string | null;
  endpoint: string | null;
  env: PublicMcpEnvVar[];
}

export interface PublicMcpServer {
  sourceId: string;
  name: string;
  description: string | null;
  version: string | null;
  repositoryUrl: string | null;
  source: PublicMcpSource;
  installs: PublicMcpInstallOption[];
  suggestedTrustLevel: McpTrustLevel;
}

export interface PublicMcpRegistryPage {
  servers: PublicMcpServer[];
  nextCursor: string | null;
}

/** Searches the public MCP catalog (bundled + external when enabled) for a capability. */
export function searchMcpRegistry(query: string, limit = 10): Promise<PublicMcpServer[]> {
  return getJson<PublicMcpServer[]>(`/app/v1/mcp/servers/registry/search${buildQuery({ query, limit })}`);
}

/** Browses a cursor-addressable page of the public MCP catalog. */
export function browseMcpRegistry(
  query: string,
  limit = 50,
  cursor?: string | null,
): Promise<PublicMcpRegistryPage> {
  return getJson<PublicMcpRegistryPage>(
    `/app/v1/mcp/servers/registry/browse${buildQuery({ query, limit, cursor: cursor || undefined })}`,
  );
}

export function listMcpServers(status?: McpServerStatus): Promise<McpServerDTO[]> {
  return getJson<McpServerDTO[]>(`/app/v1/mcp/servers${buildQuery({ status })}`);
}

export function getMcpServer(serverId: string): Promise<McpServerDTO> {
  return getJson<McpServerDTO>(`/app/v1/mcp/servers/${encodeURIComponent(serverId)}`);
}

export function registerMcpServer(request: McpServerRequest): Promise<McpServerDTO> {
  return sendJson<McpServerDTO>('/app/v1/mcp/servers', 'POST', request);
}

export function updateMcpServer(serverId: string, request: McpServerRequest): Promise<McpServerDTO> {
  return sendJson<McpServerDTO>(`/app/v1/mcp/servers/${encodeURIComponent(serverId)}`, 'PUT', request);
}

export function updateMcpServerStatus(serverId: string, status: McpServerStatus): Promise<McpServerDTO> {
  return sendJson<McpServerDTO>(`/app/v1/mcp/servers/${encodeURIComponent(serverId)}/status`, 'PATCH', { status });
}

export function listMcpLiveTools(serverId: string): Promise<McpListToolsResult> {
  return getJson<McpListToolsResult>(`/app/v1/mcp/servers/${encodeURIComponent(serverId)}/tools`);
}

export function listMcpKnownTools(serverId: string, enabledOnly = false): Promise<McpToolDTO[]> {
  return getJson<McpToolDTO[]>(
    `/app/v1/mcp/servers/${encodeURIComponent(serverId)}/tools/known${buildQuery({ enabledOnly: String(enabledOnly) })}`,
  );
}

export function syncMcpTools(serverId: string): Promise<McpToolSyncResultDTO> {
  return sendJson<McpToolSyncResultDTO>(`/app/v1/mcp/servers/${encodeURIComponent(serverId)}/tools/sync`, 'POST', {});
}

export function listMcpExecutions(serverId: string, toolName?: string): Promise<McpToolExecutionDTO[]> {
  return getJson<McpToolExecutionDTO[]>(
    `/app/v1/mcp/servers/${encodeURIComponent(serverId)}/executions${buildQuery({ toolName })}`,
  );
}

export function callMcpTool(
  serverId: string,
  toolName: string,
  request: McpToolCallRequest,
): Promise<McpToolCallResult> {
  return sendJson<McpToolCallResult>(
    `/app/v1/mcp/servers/${encodeURIComponent(serverId)}/tools/${encodeURIComponent(toolName)}/call`,
    'POST',
    request,
  );
}
