# Voyager AI Chatbot UI Reference

## Source-Informed Patterns

- Vercel AI SDK UI patterns separate submitted, streaming, ready, and error states. Mirror that state machine even when using custom APIs.
- OpenAI-style streaming sends incremental chunks; only call the UI truly streaming when the backend emits token/message deltas.
- Server-sent events are a good fit for one-way model streams; WebSocket/STOMP is useful when the app already has bidirectional workflow channels.
- Chatbot UX should keep users oriented during waits, preserve conversation context, recover clearly from errors, and avoid dead blank states.

Useful references to re-check when current guidance matters:

- Vercel AI SDK UI chatbot docs: `https://ai-sdk.dev/docs/ai-sdk-ui/chatbot`
- OpenAI streaming responses guide: `https://platform.openai.com/docs/guides/streaming-responses`
- MDN Server-sent events: `https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events`
- Nielsen Norman Group chatbot UX: `https://www.nngroup.com/articles/chatbots/`

## Voyager Information Architecture

- `App.tsx` owns shell routing, sidebar navigation, chat summaries, and `/c/{id}`.
- `CreateWorkflowView.tsx` owns the new workflow chat page, composer, model picker, message rendering, settings dialog, ASL panel, and workflow save path.
- `api.ts` owns request/response DTOs and endpoint wrappers.
- Backend workflow AI conversation endpoints live under `WorkflowAiConversationController` and `WorkflowAiConversationService`.
- AI model onboarding and endpoint management live under `AiModelController` and `AiModelConfigService`.

## Message Rendering Contract

Render user messages as compact right-aligned cards:

- header: status dot, `You`, timestamp
- body: plain text preserving whitespace
- actions: edit, copy, more, placed bottom-right

Render assistant messages as wider left/center cards:

- header: model name, timestamp
- processing state: "Processing request" plus animated blocks
- thinking state: collapsible panel, metadata on the right, scroll body if long
- answer state: markdown-rendered final content
- final footer: token count, duration, regenerate, copy, edit, more

Keep footer actions hidden while streaming/progressive reveal is active.

## Streaming Strategy

Use this decision tree:

1. Does the backend emit chunks through SSE, WebSocket/STOMP, or fetch stream?
   - Yes: update the active assistant message per chunk.
   - No: show processing, then progressively reveal the full returned message.

2. Does the model return thinking separately or in tags such as `<think>`?
   - Yes: store it as `thinkingContent`, reveal it before answer text, and keep it collapsible.
   - No: do not fabricate thinking.

3. Does the backend return token usage and duration?
   - Yes: show metadata after completion.
   - No: omit the metadata instead of guessing.

## Local Model UX

- Prefer OpenAI-compatible base URLs ending in `/v1`.
- Warn that `localhost` from Docker points inside the container; suggest `host.docker.internal` without rewriting the user's input.
- Add "Scan for Servers", "Add Ollama", and "API key" affordances where endpoint setup is done.
- Group configured models by endpoint host.
- Show only a small number of models before scrolling.
- Test/probe endpoints with `/models` or equivalent OpenAI-compatible checks.
- Protected endpoints need an API key field but should not force a key for local Ollama.

## Backend Data Requirements

For each assistant message, prefer storing:

- `modelConfigId`
- `modelDisplayName`
- `content`
- `thinkingContent`
- `durationMs`
- `inputTokens`
- `outputTokens`
- `totalTokens`
- `finishReason`
- `regeneratedFromMessageId`

History should render the actual model per message so a mid-chat model switch is visible.

## Verification Checklist

- `npm.cmd run build` passes.
- `mvn -DskipTests compile` passes after backend DTO/entity/service changes.
- First send navigates immediately to `/c/{id}`.
- Root and "New Workflow" clear the active chat and show the new workflow page.
- Processing card appears immediately after send.
- Thinking appears only when `thinkingContent` is present.
- Long thinking/model lists scroll instead of stretching the page.
- Regenerate creates a new assistant message or clearly replaces the intended message.
- Copy actions produce one toast/update, not duplicated notices.
