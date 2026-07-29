import { ModelSettingsModal } from '../components/workflow-create/ModelSettingsModal';
import { useAiModelManagement } from '../components/workflow-create/useAiModelManagement';

const fieldClass =
  'mt-2 h-10 w-full rounded-DEFAULT border border-border-subtle bg-surface-base px-3 text-body-md text-on-surface outline-none transition-colors placeholder:text-on-surface-variant/45 focus:border-secondary';

/**
 * Standalone AI Settings page: add local/cloud models, manage endpoints, and benchmark/rank models —
 * the same surface reachable from the workflow-create model picker, now first-class in the sidebar.
 */
export function AiSettingsPage() {
  const management = useAiModelManagement();

  return (
    <div className="flex h-full min-h-0 w-full flex-1 flex-col p-4 md:p-6">
      <div className="flex min-h-0 flex-1">
        <ModelSettingsModal
          embedded
          settingsTab={management.settingsTab}
          onSettingsTabChange={management.setSettingsTab}
          onClose={() => undefined}
          fieldClass={fieldClass}
          discoverEndpoint={management.discoverEndpoint}
          onDiscoverEndpointChange={management.setDiscoverEndpoint}
          localModelName={management.localModelName}
          onLocalModelNameChange={management.setLocalModelName}
          onAddLocalModel={management.addLocalModel}
          addingModel={management.addingModel}
          localCredentialRef={management.localCredentialRef}
          onLocalCredentialRefChange={management.setLocalCredentialRef}
          localEndpointNeedsDockerHint={management.localEndpointNeedsDockerHint}
          modelActionMessage={management.modelActionMessage}
          modelActionSuccess={management.modelActionSuccess}
          apiProvider={management.apiProvider}
          onApiProviderChange={management.changeApiProvider}
          apiEndpoint={management.apiEndpoint}
          onApiEndpointChange={management.setApiEndpoint}
          apiModelName={management.apiModelName}
          onApiModelNameChange={management.setApiModelName}
          apiCredentialRef={management.apiCredentialRef}
          onApiCredentialRefChange={management.setApiCredentialRef}
          onAddApiModel={management.addApiModel}
          apiActionMessage={management.apiActionMessage}
          apiActionSuccess={management.apiActionSuccess}
          endpointGroups={management.endpointGroups}
          expandedEndpoint={management.expandedEndpoint}
          setExpandedEndpoint={management.setExpandedEndpoint}
          managingModels={management.managingModels}
          onCopyEndpoint={management.copyEndpoint}
          onUpdateEndpointEnabled={management.updateEndpointEnabled}
          onDeleteSingleModel={management.deleteSingleModel}
          onUpdateSingleModelEnabled={management.updateSingleModelEnabled}
        />
      </div>
    </div>
  );
}
