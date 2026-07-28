// A one-shot hand-off for the "Apply patch" action in failure triage: the execution view stashes a
// proposed ASL for a workflow, then navigates to that workflow's revision editor, which consumes it
// once to seed the editor buffer. Kept module-local (not React state) so it survives the navigation
// between unrelated component trees without a shared provider.

const pendingPatches = new Map<string, unknown>();

/** Stash a proposed definition for a workflow's revision editor to pick up on its next mount. */
export function setPendingTriagePatch(workflowId: string, definition: unknown) {
  pendingPatches.set(workflowId, definition);
}

/**
 * Read the pending patch without clearing it. React Strict Mode may invoke a state initializer twice,
 * so consuming it inside the initializer can discard the patch before the committed editor mounts.
 */
export function getPendingTriagePatch(workflowId: string): unknown | null {
  return pendingPatches.get(workflowId) ?? null;
}

/** Clear a patch after the revision editor has committed its initial state. */
export function clearPendingTriagePatch(workflowId: string) {
  pendingPatches.delete(workflowId);
}
