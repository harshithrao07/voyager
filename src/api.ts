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
export type WorkflowPriorityDTO = 'HIGH' | 'MEDIUM' | 'LOW';
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

export interface CreateWorkflowRequest {
  name: string;
  priority: WorkflowPriorityDTO;
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

export interface WorkflowDefinitionResponseDTO {
  id: string;
  revision: number;
  definitionHash: string;
  definition: any;
  active: boolean;
  createdAt: string;
}

export interface WorkflowResponseDTO {
  id: string;
  version: number;
  name: string;
  status: WorkflowStatusDTO;
  priority: WorkflowPriorityDTO;
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

async function readError(response: Response) {
  const errorText = await response.text().catch(() => 'Unknown error');
  return `${response.status} - ${errorText || response.statusText || 'Request failed'}`;
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

export function getWorkflowRevisions(
  request: GetWorkflowRevisionsRequest,
): Promise<WorkflowDefinitionResponseDTO[]> {
  return getJson<WorkflowDefinitionResponseDTO[]>(`/app/v1/workflows/${request.workflowId}/revisions`);
}

export function listAiModels(): Promise<AiModelConfigDTO[]> {
  return getJson<AiModelConfigDTO[]>('/app/v1/ai/models');
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
