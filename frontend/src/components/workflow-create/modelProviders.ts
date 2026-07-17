export const cloudProviderPresets = [
  {
    name: 'DeepSeek',
    endpoint: 'https://api.deepseek.com',
    modelPlaceholder: 'deepseek-v4-flash',
  },
  {
    name: 'OpenAI',
    endpoint: 'https://api.openai.com/v1',
    modelPlaceholder: 'Model name from your OpenAI account',
  },
  {
    name: 'OpenRouter',
    endpoint: 'https://openrouter.ai/api/v1',
    modelPlaceholder: 'openai/gpt-5.2',
  },
  {
    name: 'Groq',
    endpoint: 'https://api.groq.com/openai/v1',
    modelPlaceholder: 'llama-3.3-70b-versatile',
  },
  {
    name: 'Custom',
    endpoint: '',
    modelPlaceholder: 'Model name exposed by the endpoint',
  },
] as const;

export type CloudProviderName = typeof cloudProviderPresets[number]['name'];

export function cloudProviderPreset(name: string) {
  return cloudProviderPresets.find((provider) => provider.name === name)
    || cloudProviderPresets[cloudProviderPresets.length - 1];
}
