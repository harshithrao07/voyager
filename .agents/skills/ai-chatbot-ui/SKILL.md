---
name: ai-chatbot-ui
description: Build, review, or refine AI chatbot interfaces for Voyager-style apps. Use when implementing chat UX, AI message bubbles, streaming states, thinking/reasoning panels, model pickers, local model endpoint controls, token/time metadata, regenerate/copy/edit actions, chat history sidebars, /c/{id} routes, or backend contracts for chat persistence and model selection.
---

# AI Chatbot UI

## Overview

Use this skill to design and implement production-grade AI chat experiences that match Voyager's current direction: compact dark UI, local/OpenAI-compatible models, visible processing states, thinking panels, message metadata, and persisted chat history.

For detailed patterns, current web-backed references, and project-specific checklists, read [references/voyager-ai-chatbot-ui.md](references/voyager-ai-chatbot-ui.md).

## Workflow

1. Inspect the current app before changing UI.
   - Start with `frontend/src/components/CreateWorkflowView.tsx`, `frontend/src/App.tsx`, `frontend/src/api.ts`, and `frontend/src/index.css`.
   - Check backend DTOs/controllers/services under `src/main/java/com/job/scheduler` before changing data shape.

2. Preserve Voyager's product rules.
   - Root `/` and "New Workflow" are the same new-chat/new-workflow page.
   - First user send navigates immediately to `/c/{id}`.
   - Chat history belongs in the sidebar under `Chats`.
   - Model choice is per message, not only per conversation.
   - AI and user messages use the same readable semantic font treatment.
   - Model names, not generic product names, identify assistant replies.

3. Build the chat lifecycle.
   - On send: append the user message and an assistant processing card immediately.
   - While waiting: show a compact assistant card with "Processing request" and an animated indicator.
   - On response: replace the processing card with the assistant message.
   - If thinking is present: reveal thinking first, keep it collapsible, then reveal the answer.
   - After completion: show token count, duration, regenerate, copy, edit, and more actions.
   - On error: replace the processing card with an error message and keep the user message.

4. Treat streaming honestly.
   - If the backend only returns a complete response, call it progressive reveal or UI streaming.
   - Use true streaming only when the backend exposes SSE, WebSocket/STOMP, or fetch stream chunks.
   - Do not fake token metadata; show it only when returned by the backend.

5. Implement model controls as first-class chat UI.
   - Keep model picker usable near a bottom composer; open upward or sideways when needed.
   - Group added models by endpoint.
   - Endpoint enable/disable acts on the endpoint group.
   - Individual model enable/disable acts only on that model.
   - Probe refreshes endpoint/model status.
   - Surface localhost versus Docker host warnings as suggestions, not automatic rewrites.

6. Verify.
   - Run `npm.cmd run build` for frontend changes.
   - Run `mvn -DskipTests compile` for backend contract changes.
   - For visual changes, inspect desktop and narrow layouts with the app running when feasible.

## UI Standards

- Keep chat content centered with a practical max width; do not make a landing page for the chat screen.
- Use stable dimensions for message action rows, model pickers, and composer controls.
- Keep assistant actions at the lower edge of the assistant card after completion.
- Keep user actions grouped inside the user card, usually bottom-right.
- Make long model lists scroll after a small visible count.
- Use icon buttons with tooltips/titles for copy, regenerate, edit, close, and menus.
- Avoid explanatory in-app text unless it directly resolves state, error, or action.

## Backend Standards

- Persist conversations and messages in the backend.
- Store `modelConfigId` and `modelDisplayName` on assistant messages so history can show model switches mid-chat.
- Store `thinkingContent`, `durationMs`, `inputTokens`, `outputTokens`, and `totalTokens` when available.
- Keep reasoning/thinking separate from final assistant content.
- Never require a new database migration unless the user explicitly wants one or the existing schema cannot support the requested behavior.
