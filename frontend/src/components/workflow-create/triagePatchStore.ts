// A one-shot hand-off for the "Apply patch" action in failure triage: the execution view stashes a
// proposed ASL for a workflow, then navigates to that workflow's revision editor, which consumes it
// once to seed the editor buffer. Kept module-local (not React state) so it survives the navigation
// between unrelated component trees without a shared provider.

const pendingPatches = new Map<string, unknown>();

/** Stash a proposed definition for a workflow's revision editor to pick up on its next mount. */
export function setPendingTriagePatch(workflowId: string, definition: unknown) {
  pendingPatches.set(workflowId, definition);
}

/** Consume and clear the pending patch for a workflow, or null if none was stashed. */
export function takePendingTriagePatch(workflowId: string): unknown | null {
  if (!pendingPatches.has(workflowId)) {
    return null;
  }
  const definition = pendingPatches.get(workflowId);
  pendingPatches.delete(workflowId);
  return definition ?? null;
}
