export interface WorkflowGenerationRequest {
  instruction: string;
}

export interface WorkflowGenerationResponse {
  definition: any;
  rawOutput: string;
  validationIssues: string[];
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
    let errorText = await response.text().catch(() => 'Unknown error');
    throw new Error(`Failed to generate workflow: ${response.status} - ${errorText}`);
  }

  return response.json();
}
