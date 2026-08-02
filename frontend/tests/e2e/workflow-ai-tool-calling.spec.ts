import { expect, test } from '@playwright/test';

const enabled = process.env.E2E_AI_TOOL_CALLING === '1';

test.describe('workflow AI tool calling', () => {
  test.skip(!enabled, 'Set E2E_AI_TOOL_CALLING=1 with a configured local model to run AI E2E.');

  test('README prompt grounds the exact catalog Task and exposes cancellable retry progress', async ({
    page,
    request,
  }) => {
    test.setTimeout(6 * 60_000);
    const prompt = 'Create an unscheduled workflow that reads README.md using the exact '
      + 'registered Voyager filesystem Task resource and then succeeds.';

    await page.goto('/');
    await page.getByRole('textbox', { name: 'Describe a workflow...' }).fill(prompt);
    await page.getByRole('button', { name: 'arrow_upward' }).click();

    await expect(page.getByText(/Choosing a catalog tool|Searching the catalog|Validating ASL/).first())
      .toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('2 states')).toBeVisible({ timeout: 4 * 60_000 });
    await expect(page.getByText('1 tasks')).toBeVisible();
    await expect(page.getByTestId('workflow-definition-status'))
      .toHaveAttribute('title', 'Frontend ASL checks pass');
    await expect(page.getByText(/Tools: \d+ call\(s\).*model pass\(es\)/)).toBeVisible();

    await expect(page).toHaveURL(/\/c\/[0-9a-f-]{36}$/);
    const conversationId = new URL(page.url()).pathname.split('/').at(-1)!;
    const conversationResponse = await request.get(
      `/app/v1/workflow-ai/conversations/${conversationId}`,
    );
    expect(conversationResponse).toBeOK();
    const conversation = await conversationResponse.json() as {
      aslDefinition: {
        States: Record<string, {
          Type?: string;
          Resource?: string;
          Arguments?: Record<string, unknown>;
        }>;
      };
    };
    const taskResources = Object.values(conversation.aslDefinition.States)
      .filter((state) => state.Type === 'Task')
      .map((state) => state.Resource);
    expect(taskResources).toEqual(['voyager://mcp/filesystem/read_text_file']);
    const task = Object.values(conversation.aslDefinition.States)
      .find((state) => state.Type === 'Task');
    expect(task?.Arguments?.path).toBe('README.md');

    await page.getByRole('button', { name: 'Retry' }).click();
    const retryProgress = page.getByTestId('workflow-ai-retry-progress');
    await expect(retryProgress).toBeVisible({ timeout: 10_000 });
    await expect(retryProgress).toContainText(
      /Starting retry|Choosing a catalog tool|Searching the catalog|Preparing the workflow|Validating ASL|Grounding the Task resource/,
    );
    await expect(retryProgress).toContainText(/\d+s/);
    await page.getByTestId('workflow-ai-retry-stop').click();
    await expect(retryProgress).toBeHidden({ timeout: 10_000 });
    await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible();
  });
});
