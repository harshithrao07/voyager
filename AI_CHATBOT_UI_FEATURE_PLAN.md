# Odysseus UI Feature Plan for Voyager

## Goal

Bring the useful Odysseus chatbot product features into Voyager without copying Odysseus styling, theme, layout density, or unrelated agent/product areas. Voyager should keep its own workflow-builder identity while adopting the interaction behaviors that make Odysseus feel reliable during long AI chats.

This plan is separate from `AI_CHATBOT_ODYSSEUS_PLAN.md`. That file focuses on streaming/backend mechanics. This file focuses on UI feature parity and the implementation order for the chatbot experience.

## Scope

In scope:

- Chat sidebar behavior.
- New workflow/new chat route behavior.
- Composer states, send/stop behavior, and bottom-position model picker usability.
- Processing, streaming, thinking, answer, error, stopped, and completed message states.
- Message actions such as copy, edit, regenerate, delete, and more menu.
- Per-message model identity, token/time metadata, and history rendering.
- Model endpoint UI behaviors already requested for Voyager.
- Recovery UX when the model endpoint fails, hangs, or stops mid-response.

Out of scope for this plan:

- Odysseus visual theme, colors, typography, decorative styling, and exact layout clone.
- Odysseus research mode, gallery, email, calendar, notes, tasks, TTS, source boxes, tool-thread rendering, background stream continuation, and cost accounting unless explicitly requested later.
- Replacing Voyager's workflow builder flow with a generic chat app.

## Evidence Checked

Odysseus:

- `D:\odysseus\static\js\chat.js`
  - Send button switches into stop mode while streaming.
  - User stop calls backend stop, preserves partial content, and shows interrupted/cancelled UI.
  - SSE reader handles deltas, thinking, metrics, message saved, errors, and done.
  - Thinking can stream live into a collapsible panel with elapsed stats.
  - Regenerate truncates from the target point and starts a new streamed response.
  - Stalled or incomplete turns surface continue/recovery affordances.
- `D:\odysseus\static\js\chatRenderer.js`
  - Copy strips thinking/tool-only content.
  - Footer actions include copy, edit, regenerate, fork, delete, and rewrite variants.
  - Stopped messages render consistently on reload and can continue when partial content exists.
  - Regenerated messages can keep variant navigation.
- `D:\odysseus\static\js\sessions.js`
  - Sidebar sessions support rename, delete, archive, important/star, active stream status, and library/search flows.
- `D:\odysseus\static\js\modelPicker.js`
  - Model picker includes endpoint probing, search, current session model updates, and local server discovery.

Voyager:

- `D:\voyager\frontend\src\App.tsx`
  - Root/new workflow navigation exists.
  - Sidebar has `Chats` with persisted summaries.
  - Search modal shell exists but does not yet search/filter conversations.
  - Chat rows do not yet expose rename/delete/archive/favorite actions.
- `D:\voyager\frontend\src\components\CreateWorkflowView.tsx`
  - First send creates a provisional `/c/{id}` route immediately.
  - Normal chat sends use SSE and show processing, thinking deltas, answer deltas, usage, metrics, saved message replacement, and errors.
  - Review ASL, accept, and regenerate still use full-response paths.
  - Message cards show model name, thinking, markdown, token count, duration, copy, edit, and regenerate.
  - Model picker opens up/down and is scroll-limited, but does not yet support richer per-endpoint/session cues.
  - Added models UI groups by endpoint, probes, copies endpoint URL, and enables/disables endpoint or model groups.
- `D:\voyager\frontend\src\api.ts`
  - `streamWorkflowAiConversation` exists only for normal `START` and `MESSAGE` operations.
  - Regenerate, review ASL, and accept remain non-streaming.
- `D:\voyager\src\main\java\com\job\scheduler\controller\WorkflowAiConversationController.java`
  - No stop endpoint exists yet.
  - No rename/delete/archive/favorite conversation endpoints exist yet.

## Feature Gap Matrix

| Feature | Odysseus behavior | Voyager current state | Gap | Priority |
| --- | --- | --- | --- | --- |
| New chat/root route | New chat resets composer and active session | Root and New Workflow are already aligned | Keep verified during future changes | P0 guardrail |
| First send route | Chat creates/navigates to a session immediately | Voyager creates provisional `/c/{id}` immediately | Keep and test against reload/history | P0 guardrail |
| Sidebar chat list | Sessions list with active state, actions, streaming markers | `Chats` list exists with active state and model/time labels | Add search/filter, row actions, and stream/error badges | P1 |
| Conversation search | Search opens chat/library search | Search modal shell exists but is not wired | Add client filter first, backend search later if needed | P1 |
| Conversation actions | Rename, delete, archive, important/star | Not present for workflow AI chats | Add backend endpoints and row menu | P1 |
| Processing state | Assistant card appears immediately with animated wait state | Present | Add timeout wording per failure class and stop support | P0/P1 |
| Stop/cancel | Send button becomes stop, backend cancels active run, partial/stopped state persists | Not present | Add backend active stream registry, stop endpoint, UI stop state | P0 |
| Partial response recovery | Interrupted message shows continue affordance | Not present | Add stopped/cancelled message state and continue action | P1 |
| SSE normal chat | Deltas, thinking, metrics, saved message, done | Present for START/MESSAGE | Strengthen event handling and tests | P0 |
| SSE regenerate | Regenerate streams and preserves variants | Regenerate is full response | Move regenerate to stream path; optional variants later | P1 |
| SSE ASL review/accept | Similar operations can stream when needed | Review/accept are full response | Stream review first; accept can stay fast unless AI work is added | P2 |
| Thinking panel | Live expanded thinking, timer/tokens, final collapsible panel | Live thinking panel exists with duration/tokens when returned | Add live elapsed timer and clearer non-thinking behavior | P1 |
| Copy message | Copies final answer, not thinking/tool data | Copies `message.content` only | Good; keep tests/edge cases | P0 guardrail |
| Message footer actions | Copy, edit, regenerate, fork, delete, rewrite, more | Copy, edit, regenerate, more icon shown | Add delete, retry from error, and coherent more menu | P1 |
| Regenerate history | Targeted regenerate from a message, variants retained | Appends regenerated response, stores `regeneratedFromMessageId` | Add streamed regenerate and optional variant navigation | P1/P3 |
| Message edit | Edit message in place | UI editing exists locally | Confirm backend persistence semantics; add resend-from-edit later | P2 |
| Metrics | Tokens, time, model, sometimes cost/context popovers | Tokens and duration displayed | Add metadata popover/details for model, finish reason, input/output tokens | P2 |
| Model picker | Search, endpoint/session model updates, local probes | Search and add button exist; opens up/down | Prefer left/up near bottom composer, group by endpoint, show status hints | P1 |
| Endpoint management | Probe, local discovery, add API key, endpoint status | Mostly present in settings | Keep; add clearer status to picker if useful | P2 |
| Error states | Provider errors replace spinner with visible failure | Error replacement exists for normal stream timeout/failures | Add provider-class messages and retry/choose model action | P1 |
| Reload history | Stopped/thinking/model metadata survives reload | Thinking/model/tokens survive when persisted | Add stopped/cancelled state persistence | P1 |
| Background streaming | Streams continue when user switches session | Not present | Optional later, not needed for current Voyager parity | P4 |

## Implementation Plan

### Phase 0 - Preserve Current Chat Contract

Do this before adding more features so future work does not regress the behavior the user already requested.

- Keep `/` and `New Workflow` as the same new workflow page.
- Keep immediate navigation to `/c/{id}` on first send.
- Keep chat summaries under `Chats` in the sidebar.
- Keep model identity per assistant message, not only per conversation.
- Keep copy behavior limited to final answer content and never include thinking content.
- Keep action footers hidden while a message is processing or streaming.

Verification:

- First send from `/` immediately moves to `/c/{id}`.
- Clicking logo/New Workflow/root clears active chat and opens the empty new workflow page.
- Reloading `/c/{id}` shows persisted messages with their model names.

### Phase 1 - Stop, Cancel, and Honest Failure UI

This is the most important missing Odysseus feature because it prevents infinite "processing request" states.

Backend:

- Track active SSE streams by a stream id or conversation id in `WorkflowAiConversationService`.
- Add `POST /app/v1/workflow-ai/conversations/{conversationId}/stop`.
- Cancel the upstream OpenAI-compatible request when possible.
- Persist an assistant placeholder when stopped before output:
  - `content = ""`
  - stopped/cancelled metadata if the existing schema can carry it, otherwise encode through existing nullable fields without a migration only if clean.
- Persist partial assistant output when stopped after deltas.
- Emit a final stream event that clearly maps to a stopped UI state.

Frontend:

- Add an `AbortController` to `streamWorkflowAiConversation`.
- Change the composer send button into a stop button while `generating` or any message has `streamingStatus`.
- On stop before first delta, replace processing card with "Cancelled by user" state.
- On stop after partial content, keep the partial assistant card and show "Message interrupted" plus Continue.
- On provider error, replace the processing card with an error card that includes Retry and model picker affordances.
- Remove any state path where the app can remain in `processing` after fetch failure, stream close before done, or timeout.

Verification:

- Missing model endpoint returns a visible error without hanging.
- User stop before first token persists a cancelled assistant turn.
- User stop after partial tokens keeps partial text and unlocks composer.
- Reload shows the stopped/cancelled state consistently.

### Phase 2 - Sidebar Conversation Features

Borrow Odysseus session affordances, but keep Voyager's compact sidebar.

Backend:

- Add minimal conversation endpoints:
  - `PATCH /app/v1/workflow-ai/conversations/{conversationId}` for rename.
  - `DELETE /app/v1/workflow-ai/conversations/{conversationId}` for delete.
  - Optional: `POST /archive`, `POST /restore`, `POST /favorite` only if the UI will expose them now.
- Add list query support only when needed:
  - For small lists, client-side filter is enough.
  - For large lists, add `?query=`.

Frontend:

- Wire the existing search modal to filter chat summaries by title, latest text, model name, and date.
- Add per-chat row menu with Rename and Delete first.
- Add Archive/Favorite later only if the product needs them.
- Show a small state marker on a chat row when its latest message is streaming, failed, stopped, or draft.
- Keep the list scrollable and stable in collapsed sidebar mode.

Verification:

- Rename updates sidebar, header, and reload state.
- Delete removes the row and routes away if the deleted chat is open.
- Search filters instantly and handles empty results.

### Phase 3 - Stream Regenerate and Improve Message Actions

Regenerate should behave like a real chat operation, not a delayed full-response swap.

Backend:

- Extend the stream request operation enum to support `REGENERATE`.
- Accept `messageId` and `modelConfigId`.
- Use existing `regeneratedFromMessageId`.
- Persist the regenerated assistant message with the model used for that attempt.
- Return the same SSE event contract as normal chat.

Frontend:

- Make `handleRegenerateMessage` call the streaming path.
- Insert a processing card near the original assistant message or directly after the conversation tail, depending on the chosen product behavior.
- Keep the original response visible until the regenerated response starts.
- Add message-level retry for failed assistant cards.
- Add a real More menu:
  - Copy
  - Edit
  - Regenerate
  - Delete
  - Optional later: Fork from here, rewrite shorter, explain simpler.
- Add Delete message only when backend support exists, with clear history reload behavior.

Optional later:

- Variant navigation for original/regenerated answers.

Verification:

- Regenerate streams thinking and answer deltas.
- History shows both the original and regenerated assistant messages or a clear variant UI.
- Mid-chat model switch is visible in both responses.

### Phase 4 - Thinking and Metrics Polish

Voyager already has thinking. This phase makes it feel as deliberate as Odysseus.

Frontend:

- Show live elapsed time while thinking is streaming.
- Keep thinking expanded while live thinking arrives; collapse state remains user-controlled after completion.
- Keep thinking body scrollable.
- Do not render the thinking panel for models that do not emit thinking.
- Add a metadata popover or compact details row for:
  - input tokens
  - output tokens
  - total tokens
  - duration
  - tokens per second
  - finish reason
  - model display name
- Keep footer actions hidden until the message is finished or failed.

Backend:

- Ensure usage chunks and final metrics consistently map to `WorkflowAiMessageDTO`.
- Keep `thinkingContent` separate from final content for every provider shape.

Verification:

- Thinking model shows live thinking and final answer separately.
- Non-thinking model shows no fake thinking box.
- Copy never includes thinking text.
- Reload preserves thinking, tokens, duration, and model name.

### Phase 5 - Model Picker and Endpoint UX

Voyager already has most endpoint management. The remaining UI parity is about fast use during chat.

Frontend:

- Open the model picker upward or leftward from the bottom composer so it never falls below the viewport on `/c/{id}`.
- Group model picker rows by endpoint host when the list is long.
- Keep only a small number of models visible before scrolling.
- Show endpoint status hints in the picker:
  - online
  - disabled
  - unreachable
  - protected/API key required
- Keep the Add Model shortcut, but avoid opening large settings UI accidentally while sending.
- Keep localhost/Docker suggestions as warnings only, not automatic rewrites.

Backend:

- Reuse existing model endpoint discovery/probe APIs.
- Avoid introducing a second model registry.

Verification:

- Picker is usable at desktop and narrow widths.
- Long model lists scroll.
- Disabled endpoint/model rows cannot be selected for new sends.
- Selecting a different model mid-chat is reflected in the next assistant message and history.

### Phase 6 - Stream ASL Review and Accept Only Where It Helps

This is useful, but it is less urgent than normal chat, stop, and regenerate.

Backend:

- Add stream operations for `REVIEW_ASL` and optionally `ACCEPT`.
- `REVIEW_ASL` should stream model explanation/review text if the model is involved.
- `ACCEPT` only needs SSE if it performs AI work or long validation; otherwise keep it as a normal request with good loading/error UI.

Frontend:

- Reuse the same processing/streaming assistant card behavior.
- Keep ASL editor state synchronized with final `workflow_state`.
- On review errors, keep the editor open and show a message card explaining the failure.

Verification:

- Review ASL streams visible text.
- Validation errors are shown without locking the composer/editor.
- Accept creates the draft workflow and routes/updates UI exactly once.

### Phase 7 - Later Odysseus Features

These are intentionally later because they are not required for the current Voyager chatbot feature parity.

- Background stream continuation when switching sessions.
- Source boxes.
- Tool-thread rendering.
- TTS.
- Cost accounting.
- Fork from any message.
- Rewrite shorter/explain simpler quick actions.
- Regeneration variant navigation.
- Advanced markdown streaming renderer that freezes completed blocks.

## Suggested First Implementation Slice

Start with the smallest slice that fixes the most user-visible pain:

1. Add stop/cancel support for normal SSE chat.
2. Add stopped/cancelled assistant message UI and persistence.
3. Add retry/continue actions for failed or interrupted assistant messages.
4. Wire sidebar search to existing chat summaries.
5. Convert regenerate to SSE.

This gives Voyager the core Odysseus feel: the user is never trapped in a spinner, can stop a bad run, can recover from partial output, and can retry or regenerate without losing context.

## Test Plan

Frontend:

- `npm.cmd run build`
- Manual desktop and narrow checks:
  - `/`
  - `/c/{id}`
  - model picker near bottom composer
  - chat sidebar open/collapsed
  - search modal
  - stopped/error/regenerated messages

Backend:

- `mvn -DskipTests compile`
- Add focused tests for:
  - stream event order
  - provider unavailable error
  - stop before first token
  - stop after partial tokens
  - usage/metrics mapping
  - thinking parser provider shapes
  - regenerate stream preserving `regeneratedFromMessageId`

Manual E2E:

- Run backend, frontend, and a real local OpenAI-compatible endpoint.
- Send first message from `/` and confirm immediate `/c/{id}` route.
- Confirm processing card appears immediately.
- Confirm live deltas arrive.
- Confirm thinking panel appears only when the model emits thinking.
- Stop during processing and during partial output.
- Regenerate an assistant message using a different selected model.
- Reload the chat and verify model names, thinking, tokens, duration, stopped state, and regenerated messages.

## Completion Criteria

The UI feature work is complete when:

- Normal chat, regenerate, and ASL review either stream through the same UI state machine or intentionally document why a specific operation remains non-streaming.
- No AI operation can leave the UI permanently stuck in processing.
- The send button becomes stop while a model response is active.
- Stopped and failed turns are visible, recoverable, and reload-safe.
- Sidebar chats can be searched and managed.
- Message actions are consistent and hidden during streaming.
- Per-message model identity and metrics survive reload.
- The model picker remains usable from the bottom composer.
