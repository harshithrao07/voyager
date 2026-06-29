# Odysseus Chatbot Study and Voyager Implementation Plan

## Scope

This plan studies only the chatbot implementation in `D:\odysseus` and translates the useful parts into Voyager's workflow-builder chat. It does not cover Odysseus tools, research mode, image generation, memory, gallery, or unrelated agent features except where they affect the core chat stream lifecycle.

Voyager product rules that must remain true:

- `/` and "New Workflow" are the same new workflow chat page.
- First user send navigates immediately to `/c/{id}`.
- Chat history stays in the sidebar under `Chats`.
- Model choice is visible per message so mid-chat model switches are obvious in history.
- No database migration unless the existing schema truly cannot support the behavior.

## Odysseus Findings

### 1. Transport: Real SSE Streaming

Odysseus uses `POST /api/chat_stream` in `routes/chat_routes.py`, returning `StreamingResponse(..., media_type="text/event-stream")`. The frontend posts a form request from `static/js/chat.js`, then reads `res.body.getReader()` in a loop.

Core event types seen in Odysseus:

- `data: {"delta": "..."}`
- `data: {"delta": "...", "thinking": true}`
- `data: {"type": "usage", "data": {...}}`
- `data: {"type": "metrics", "data": {...}}`
- `data: {"type": "message_saved", "id": "..."}`
- `event: error` with JSON payload
- `data: [DONE]`

The important design choice is that the UI receives output before the final message is complete. Voyager currently does not do this; it receives one full STOMP response and then progressively reveals it.

### 2. Backend Thinking Normalization

Odysseus normalizes thinking/reasoning from several provider shapes inside `src/llm_core.py`:

- OpenAI-compatible/vLLM style: `reasoning_content`
- Newer vLLM/NIM style: `reasoning`
- Some Ollama-compatible style: `thinking`
- Mistral structured content blocks with `type: "thinking"`
- Literal `<think>...</think>` tags in normal content

It then emits thinking as `{"delta": text, "thinking": true}` so the frontend can route it to a thinking panel instead of leaking it into the answer.

Voyager already extracts completed `<think>` or `<thinking>` tags in `WorkflowAiConversationService`, but it only does this after the full model response arrives.

### 3. Backend Metrics, Save, and Recovery

Odysseus keeps stream state in `_active_streams`, accumulates only non-thinking text into the saved assistant response, emits metrics when usage arrives, and emits `message_saved` after persistence. It also handles partial save when the stream is stopped or cancelled.

Useful pieces to copy conceptually:

- Separate visible answer content from thinking content.
- Emit usage and timing as structured events.
- Emit the persisted message id when save succeeds.
- Return clear `event: error` payloads for upstream connection failures, read timeouts, provider 4xx/5xx, and malformed model output.
- Keep a dead-host/cooldown concept for endpoints that fail repeatedly, so the app does not hang on the same broken endpoint over and over.

### 4. Frontend Stream Lifecycle

Odysseus chat UI has a strict lifecycle:

1. Append user message.
2. Append assistant processing card with an animated "Processing request" state.
3. Start request with `AbortController`.
4. If first output arrives, switch from processing to receiving.
5. Route `thinking: true` deltas into the thinking panel.
6. Route normal deltas into the answer body.
7. Render metrics and actions only after completion.
8. On timeout/error/abort, replace the pending card with a visible failure or stopped state.

It also has a stall watchdog. If the stream is silent for too long, the UI surfaces recovery instead of leaving the user staring at an infinite processing state.

Voyager has already moved partway here:

- Processing card appears immediately.
- First send navigates to `/c/{id}`.
- Full responses are progressively revealed.
- Thinking is shown when `thinkingContent` exists.
- Timeout and socket-close failures now replace the processing card with an error.

### 5. Streaming Markdown Renderer

Odysseus has a dedicated `static/js/streamingRenderer.js` and `streamingSegmenter.js`. It freezes finalized markdown blocks and only re-renders the live tail, with special handling for open code fences. This prevents flicker, broken code highlighting, and expensive full-message re-renders while tokens stream.

Voyager currently uses React state plus `ReactMarkdown`. That is acceptable for short progressive reveal, but true token streaming will eventually need either:

- a React-friendly version of the Odysseus "finalized blocks plus live tail" idea, or
- a simpler first pass that re-renders the active assistant message until performance or code-block flicker becomes a problem.

### 6. Message Actions

Odysseus message actions include copy, regenerate/retry, edit/delete variants, metrics display, and protection against copying thinking content. The important Voyager requirement is:

- Copy should copy the final answer, not hidden thinking.
- Regenerate should create a new assistant response linked to the original message.
- Message footer should stay hidden during processing/streaming and appear only after completion.

Voyager already has copy, edit, regenerate, token count, duration, and thinking panel UI. Copy should continue to exclude `thinkingContent` by only copying `message.content`.

## Voyager Current State

### Backend

Current files:

- `src/main/java/com/job/scheduler/controller/WorkflowAiConversationController.java`
- `src/main/java/com/job/scheduler/service/WorkflowAiConversationService.java`
- `src/main/java/com/job/scheduler/service/WorkflowAiModelResolver.java`
- `src/main/java/com/job/scheduler/dto/WorkflowAiMessageDTO.java`

Current behavior:

- HTTP endpoints return one full `WorkflowAiResponseDTO`.
- STOMP endpoints also return one full `WorkflowAiResponseDTO` to `/user/queue/workflow-ai`.
- `WorkflowAiConversationService.callAssistant(...)` calls `ChatLanguageModel.generate(messages)` synchronously.
- `WorkflowAiMessageDTO` already carries model id/name, duration, input/output/total tokens, thinking content, finish reason, and regenerated message id.
- `WorkflowAiModelResolver` builds a non-streaming `OpenAiChatModel`.

This means the schema and DTOs are mostly ready, but the transport and model invocation are not true streaming yet.

### Frontend

Current files:

- `frontend/src/components/CreateWorkflowView.tsx`
- `frontend/src/api.ts`
- `frontend/src/App.tsx`
- `frontend/src/index.css`

Current behavior:

- The composer appends the user message and assistant processing card immediately.
- First send navigates to `/c/{id}` with a provisional id.
- The response comes back as one complete DTO and is progressively revealed.
- Thinking is displayed from `thinkingContent` once the full response exists.
- Model picker opens upward/downward depending on available space.
- Failed socket/timeout requests now replace the processing card with a visible error.

## Target Architecture

Use an Odysseus-like stream contract, but keep Voyager's workflow-specific state machine.

Recommended stream event contract:

```text
conversation      { conversationId, conversationName, stage }
model_info        { modelConfigId, modelDisplayName, requestedModelName, actualModelName }
thinking_delta    { text }
delta             { text }
usage             { inputTokens, outputTokens, totalTokens }
metrics           { durationMs, tokensPerSecond, finishReason }
workflow_state    { stage, aslDefinition, validationIssues, finalPlan, draftWorkflowPayload, workflowId }
message_saved     { assistantMessage }
error             { message, status, retryable }
done              {}
```

The final `message_saved` should include the same `WorkflowAiMessageDTO` shape the history API already returns. That keeps reload/history rendering consistent.

## Implementation Plan

### Phase 0 - Current No-Stream Robustness

Goal: make the existing full-response path impossible to leave visually stuck.

Status:

- Done: WebSocket close/error/invalid-frame/timeout failures now reject in `frontend/src/api.ts`.
- Done: failures replace the assistant processing card in `CreateWorkflowView.tsx`.
- Done: frontend build passes.

Remaining checks:

- Manually test backend down: send a message, confirm the processing card becomes an error.
- Manually test model endpoint down: send a message, confirm the processing card becomes an error before the long backend timeout feels infinite.

### Phase 1 - Backend Stream Endpoint

Add a streaming endpoint without removing the existing STOMP/full-response endpoints.

Preferred first endpoint:

```text
POST /app/v1/workflow-ai/conversations/stream
Content-Type: application/json
Accept: text/event-stream
```

Request shape:

```json
{
  "conversationId": "optional-existing-id",
  "instruction": "first message only",
  "message": "follow-up message only",
  "modelConfigId": "optional",
  "userDateTime": "optional ISO datetime",
  "operation": "START|MESSAGE|REGENERATE|REVIEW_ASL"
}
```

Backend behavior:

- Create or load the conversation up front.
- Persist the user message before model streaming starts.
- Immediately emit `conversation` and `model_info`.
- Stream thinking and answer deltas.
- Accumulate full thinking and answer server-side.
- On completion, parse the final answer as Voyager workflow JSON exactly like `callAssistant(...)` does today.
- Validate ASL only after the full structured JSON exists.
- Persist assistant message once.
- Emit `workflow_state`, `message_saved`, then `done`.
- On provider or parsing failure, emit `error` and persist no assistant message unless there is meaningful partial content.

### Phase 2 - Streaming Model Resolver

Add streaming support beside the current model resolver.

Options:

- If the current LangChain4j version supports `StreamingChatLanguageModel` for OpenAI-compatible models, add `WorkflowAiStreamingModelResolver`.
- If it does not expose the provider fields Voyager needs, implement a small OpenAI-compatible streaming client with Spring `WebClient` or Java HTTP client.

The streaming parser must handle:

- `choices[].delta.content`
- `choices[].delta.reasoning_content`
- `choices[].delta.reasoning`
- `choices[].delta.thinking`
- `usage` chunks from `stream_options.include_usage`
- literal `<think>...</think>` split across chunks
- final finish reason
- provider `event: error` or non-2xx responses

Do not fabricate thinking. Only show thinking when the model/provider emits it.

### Phase 3 - Frontend Stream Client

Add a fetch/SSE reader in `frontend/src/api.ts`, separate from the current STOMP helper.

Client behavior:

- Use `AbortController`.
- Parse `data:` lines and named `event: error` lines.
- Surface malformed events as errors.
- Resolve only after `done`.
- Reject if the stream closes before `done`.
- Expose callbacks such as `onConversation`, `onThinkingDelta`, `onDelta`, `onMetrics`, `onWorkflowState`, `onMessageSaved`, `onError`.

`CreateWorkflowView.tsx` behavior:

- Keep the same immediate user message and processing card.
- On first stream event, replace processing state with a streaming assistant message.
- Append `thinking_delta` to `thinkingContent`.
- Append `delta` to `content`.
- Keep the thinking panel expanded while live thinking arrives.
- Hide footer actions while streaming.
- On `message_saved`, replace the provisional assistant message with the persisted DTO.
- On stream error/close-before-done, replace the active assistant message with an error card and unlock the composer.
- Keep the current full-response STOMP path as fallback until streaming is stable.

### Phase 4 - Rendering Polish

Start simple, then adopt the Odysseus renderer idea only if needed.

V1:

- Keep `ReactMarkdown` rendering the active assistant message.
- Keep thinking in a scrollable panel.
- Keep action footer hidden until stream completion.
- Use the existing processing bars and cursor styles.

V2:

- Add a React hook inspired by Odysseus `streamingRenderer.js`.
- Freeze finalized markdown blocks and re-render only the active tail.
- Special-case open code fences so code blocks do not flicker while streaming.

### Phase 5 - Stop, Retry, and Regenerate

Add explicit stream cancellation after basic streaming works.

Backend:

- Track active streams by conversation id or generated run id.
- Add `POST /app/v1/workflow-ai/conversations/{conversationId}/stop`.
- On stop, cancel upstream request if possible.
- If partial content is useful, persist it with a stopped finish reason; otherwise only record a system/status event if needed.

Frontend:

- Change the send button into a stop button while streaming.
- On stop, abort the fetch and call the stop endpoint.
- Show a stopped/error footer instead of pretending the response completed.
- Regenerate should use the streaming path and preserve `regeneratedFromMessageId`.

### Phase 6 - Tests and Verification

Backend tests:

- Thinking parser splits `reasoning_content`, `reasoning`, `thinking`, and `<think>` tags into thinking content.
- Stream endpoint emits `conversation`, deltas, `message_saved`, and `done` in order.
- Provider error emits `error` and does not leave an unhandled backend exception.
- Usage chunks map to `WorkflowAiMessageDTO` token fields.
- Mid-chat model switch stores the selected model on the assistant message.

Frontend tests/manual checks:

- `npm.cmd run build`.
- Backend down: processing card becomes error.
- Model endpoint down: processing card becomes error.
- Normal send: user message appears, `/c/{id}` route changes immediately, assistant streams in.
- Thinking model: thinking panel receives thinking before final answer.
- Non-thinking model: no fake thinking panel.
- Copy assistant message copies only final answer text.
- Regenerate streams a new assistant message and preserves history.
- Reload `/c/{id}` and verify persisted model names, thinking, tokens, and duration still render.

## What Not To Copy From Odysseus Yet

- Background stream continuation across multiple sessions.
- Agent tool thread rendering.
- Web/research/source boxes.
- TTS.
- Cost accounting.
- Browser Web Locks.
- Large imperative DOM renderer as-is.

These are useful later, but Voyager should first get the core chat lifecycle, true model deltas, thinking, errors, persistence, and regenerate behavior right.

## Recommended Next Slice

Implement Phase 1 and Phase 3 for normal `START` and `MESSAGE` only:

1. Add `POST /app/v1/workflow-ai/conversations/stream`.
2. Add a streaming OpenAI-compatible client or LangChain4j streaming resolver.
3. Stream `thinking_delta`, `delta`, `metrics`, `message_saved`, and `done`.
4. Wire `CreateWorkflowView.tsx` to use the stream for normal sends.
5. Keep ASL review, accept, and regenerate on the existing full-response path until the normal chat stream is stable.

This gets Voyager closest to Odysseus's chatbot feel without destabilizing the workflow builder's ASL parsing and save path.
