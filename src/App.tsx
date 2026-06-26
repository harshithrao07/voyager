import { useState } from 'react';
import { WorkflowGeneratorPanel } from './components/WorkflowGeneratorPanel';
import { AslCodeViewer } from './components/AslCodeViewer';
import { AslGraphViewer } from './components/AslGraphViewer';
import { NodeDetailsPanel } from './components/NodeDetailsPanel';
import { ExecutionStatusView, type ExecutionRun } from './components/ExecutionStatusView';
import { WorkflowListView, type WorkflowSummary } from './components/WorkflowListView';

const workflows: WorkflowSummary[] = [
  {
    id: 'wf-data-sync',
    name: 'Source Sync Workflow',
    status: 'Active',
    schedule: '0 0 * * *',
    nextRun: 'Next: in 3h',
    description: 'Collects source records, validates payload shape, and routes processing or failure handling.',
  },
  {
    id: 'wf-onboarding',
    name: 'User Onboarding Flow',
    status: 'Paused',
    schedule: 'Trigger-based',
    nextRun: '-',
    description: 'Coordinates account creation, profile enrichment, and welcome notification steps.',
  },
  {
    id: 'wf-analytics',
    name: 'Analytics Aggregator',
    status: 'Failed',
    schedule: '0 * * * *',
    nextRun: 'Retrying (2/5)',
    description: 'Aggregates hourly metrics and stores normalized summaries for reporting.',
  },
  {
    id: 'wf-backups',
    name: 'Nightly Backups',
    status: 'Active',
    schedule: '0 2 * * *',
    nextRun: 'Next: in 5h',
    description: 'Runs a scheduled backup workflow across durable storage targets.',
  },
];

const activeDefinition = {
  "StartAt": "Fetch Source Data",
  "States": {
    "Fetch Source Data": {
      "Type": "Task",
      "Resource": "Lambda::Invoke",
      "Next": "Data Validation"
    },
    "Data Validation": {
      "Type": "Choice",
      "Choices": [
        { "Next": "Process Embeddings" }
      ],
      "Default": "Log Failure"
    },
    "Log Failure": {
      "Type": "Task",
      "Resource": "SNS::Publish",
      "End": true
    },
    "Process Embeddings": {
      "Type": "Task",
      "Resource": "ECS::RunTask",
      "End": true
    }
  }
};

type WorkflowRevision = {
  id: string;
  label: string;
  timestamp: string;
  active?: boolean;
  note: string;
  definition: any;
  runs: ExecutionRun[];
};

const revisions: WorkflowRevision[] = [
  {
    id: 'rev-3',
    label: 'Rev 3',
    timestamp: 'Today, 14:32 UTC',
    active: true,
    note: 'Adds validation routing and embedding processing.',
    definition: activeDefinition,
    runs: [
      { id: 'ex-r3-9a8b7c-20231024', status: 'Completed', duration: '14.2s', started: '10:42:01 AM' },
      { id: 'ex-r3-1d2e3f-20231024', status: 'Failed', duration: '2.1s', started: '10:40:15 AM' },
      { id: 'ex-r3-4g5h6i-20231024', status: 'Running', duration: '45.0s', started: '10:39:50 AM' },
      { id: 'ex-r3-7j8k9l-20231024', status: 'Completed', duration: '112.5s', started: '10:35:12 AM' },
    ],
  },
  {
    id: 'rev-2',
    label: 'Rev 2',
    timestamp: 'Yesterday, 09:15 UTC',
    note: 'Uses direct persistence without the failure branch.',
    definition: {
      "StartAt": "Fetch Source Data",
      "States": {
        "Fetch Source Data": {
          "Type": "Task",
          "Resource": "Lambda::Invoke",
          "Next": "Process Embeddings"
        },
        "Process Embeddings": {
          "Type": "Task",
          "Resource": "ECS::RunTask",
          "Next": "Persist Results"
        },
        "Persist Results": {
          "Type": "Task",
          "Resource": "DynamoDB::PutItem",
          "End": true
        }
      }
    },
    runs: [
      { id: 'ex-r2-a18c44-20231023', status: 'Completed', duration: '18.6s', started: '09:15:20 AM' },
      { id: 'ex-r2-b27d91-20231023', status: 'Completed', duration: '17.4s', started: '08:15:17 AM' },
      { id: 'ex-r2-f48e03-20231023', status: 'Failed', duration: '3.8s', started: '07:15:09 AM' },
    ],
  },
  {
    id: 'rev-1',
    label: 'Rev 1',
    timestamp: 'Oct 12, 18:00 UTC',
    note: 'Initial single pass prototype.',
    definition: {
      "StartAt": "Fetch Source Data",
      "States": {
        "Fetch Source Data": {
          "Type": "Task",
          "Resource": "Lambda::Invoke",
          "Next": "Normalize Payload"
        },
        "Normalize Payload": {
          "Type": "Pass",
          "Result": "Payload normalized",
          "End": true
        }
      }
    },
    runs: [
      { id: 'ex-r1-001-20231012', status: 'Completed', duration: '6.1s', started: '06:00:12 PM' },
      { id: 'ex-r1-002-20231012', status: 'Completed', duration: '6.4s', started: '05:00:08 PM' },
    ],
  },
];

function App() {
  const [workflowDef, setWorkflowDef] = useState<any>(activeDefinition);
  const [activeTab, setActiveTab] = useState<'visualizer' | 'definition' | 'executions'>('visualizer');
  const [selectedStateName, setSelectedStateName] = useState('Fetch Source Data');
  const [selectedWorkflow, setSelectedWorkflow] = useState<WorkflowSummary | null>(null);
  const [selectedRevisionId, setSelectedRevisionId] = useState('rev-3');
  const selectedRevision = revisions.find((revision) => revision.id === selectedRevisionId) || revisions[0];
  const currentDefinition = selectedRevision.id === 'rev-3' ? workflowDef : selectedRevision.definition;

  const handleWorkflowGenerated = (definition: any) => {
    setWorkflowDef(definition);
    setSelectedRevisionId('rev-3');
    if (definition?.StartAt) {
      setSelectedStateName(definition.StartAt);
    }
  };

  const handleWorkflowSelected = (workflow: WorkflowSummary) => {
    setSelectedWorkflow(workflow);
    setSelectedRevisionId('rev-3');
    setSelectedStateName(revisions[0].definition.StartAt);
    setActiveTab('visualizer');
  };

  const handleRevisionSelected = (revision: WorkflowRevision) => {
    setSelectedRevisionId(revision.id);
    setSelectedStateName(revision.definition.StartAt || Object.keys(revision.definition.States || {})[0]);
  };

  return (
    <div className="font-body-sm text-body-sm overflow-hidden selection:bg-status-accent/30 selection:text-primary">
      {/* TopNavBar */}
      <nav className="bg-surface-base dark:bg-surface-base border-b border-border-subtle dark:border-border-subtle backdrop-blur-md fixed top-0 left-0 w-full z-50 flex justify-between items-center px-gutter h-14">
        <div className="flex items-center gap-element-gap-md">
          <div className="text-headline-md font-headline-md font-extrabold text-primary dark:text-primary">
            Agentic Workflow
          </div>
          <div className="hidden md:flex items-center gap-element-gap-sm ml-8 h-full">
            <div className="relative group">
              <span className="material-symbols-outlined absolute left-2 top-1/2 -translate-y-1/2 text-on-surface-variant text-[16px]">search</span>
              <input className="bg-surface-elevated border border-border-subtle rounded-DEFAULT text-body-sm font-body-sm text-primary pl-8 pr-3 py-1 focus:outline-none focus:border-status-info focus:ring-1 focus:ring-status-info transition-colors w-48 placeholder-on-surface-variant/50" placeholder="Search..." type="text" />
            </div>
          </div>
        </div>
        <div className="flex items-center gap-element-gap-md">
          <div className="flex items-center gap-element-gap-sm text-on-surface-variant">
            <button className="hover:text-primary transition-colors duration-200 w-8 h-8 flex items-center justify-center rounded-DEFAULT hover:bg-surface-container">
              <span className="material-symbols-outlined text-[18px]">notifications</span>
            </button>
          </div>
        </div>
      </nav>

      <div className="flex h-screen pt-14">
        {/* SideNavBar */}
        <aside className="bg-surface-container-low dark:bg-surface-container-low border-r border-border-subtle dark:border-border-subtle fixed left-0 top-14 h-[calc(100vh-3.5rem)] w-sidebar-width flex-col py-4 hidden md:flex z-40">
          <div className="px-4 mb-6">
            <button className="w-full bg-surface-elevated hover:bg-surface-container border border-border-subtle text-primary font-body-sm text-body-sm py-2 rounded-DEFAULT transition-colors flex items-center justify-center gap-2">
              <span className="material-symbols-outlined text-[16px]">add</span>
              New Workflow
            </button>
          </div>
          <div className="flex-1 overflow-y-auto px-2 space-y-1">
            <a className="flex items-center gap-3 px-3 py-2 rounded-DEFAULT bg-surface-container-high dark:bg-surface-container-high text-primary dark:text-primary border-l-2 border-status-accent translate-x-1 transition-transform duration-150" href="#">
              <span className="material-symbols-outlined text-[18px]">schema</span>
              <span className="font-body-sm text-body-sm font-medium">Workflows</span>
            </a>
            <a className="flex items-center gap-3 px-3 py-2 rounded-DEFAULT text-on-surface-variant dark:text-on-surface-variant opacity-70 hover:bg-surface-container dark:hover:bg-surface-container hover:opacity-100 transition-all" href="#">
              <span className="material-symbols-outlined text-[18px]">terminal</span>
              <span className="font-body-sm text-body-sm">Logs</span>
            </a>
            <a className="flex items-center gap-3 px-3 py-2 rounded-DEFAULT text-on-surface-variant dark:text-on-surface-variant opacity-70 hover:bg-surface-container dark:hover:bg-surface-container hover:opacity-100 transition-all" href="#">
              <span className="material-symbols-outlined text-[18px]">settings_suggest</span>
              <span className="font-body-sm text-body-sm">Settings</span>
            </a>
          </div>
          <div className="mt-auto px-2 pt-4 border-t border-border-subtle space-y-1">
            <a className="flex items-center gap-3 px-3 py-2 rounded-DEFAULT text-on-surface-variant dark:text-on-surface-variant opacity-70 hover:bg-surface-container dark:hover:bg-surface-container hover:opacity-100 transition-all" href="#">
              <span className="material-symbols-outlined text-[18px]">description</span>
              <span className="font-body-sm text-body-sm">Docs</span>
            </a>
          </div>
        </aside>

        {/* Main Content Area */}
        <main className="flex-1 md:ml-[240px] flex flex-col relative w-full h-full bg-surface-lowest">
          <header className="flex-shrink-0 px-6 py-4 border-b border-border-subtle bg-surface-base z-10 flex flex-col gap-4">
            {/* Breadcrumbs & Meta */}
            <div className="flex justify-between items-center w-full max-w-[1440px] mx-auto">
              <div className="flex items-center gap-2 text-on-surface-variant font-body-sm text-body-sm">
                <button onClick={() => setSelectedWorkflow(null)} className="hover:text-primary cursor-pointer transition-colors">Workflows</button>
                {selectedWorkflow && (
                  <>
                    <span className="material-symbols-outlined text-[14px]">chevron_right</span>
                    <span className="text-primary font-medium flex items-center gap-1.5">
                      <span className={`w-2 h-2 rounded-full ${selectedWorkflow.status === 'Failed' ? 'bg-status-error' : selectedWorkflow.status === 'Paused' ? 'bg-on-surface-variant' : 'bg-status-success'}`}></span>
                      {selectedWorkflow.name}
                    </span>
                  </>
                )}
              </div>
            </div>
            
            {/* Tabs */}
            {selectedWorkflow && (
            <>
              <div className="flex justify-between items-center w-full max-w-[1440px] mx-auto">
                <div>
                  <h1 className="text-headline-lg font-headline-lg text-primary">{selectedWorkflow.name}</h1>
                  <p className="text-body-sm font-body-sm text-on-surface-variant">{selectedWorkflow.description}</p>
                </div>
                <div className="flex items-center gap-2">
                  <button className="bg-surface-elevated hover:bg-surface-container border border-border-subtle text-primary font-body-sm text-body-sm px-3 py-1.5 rounded-DEFAULT transition-colors flex items-center gap-2">
                    <span className="material-symbols-outlined text-[16px]">history</span>
                    Revert
                  </button>
                  <button onClick={() => setActiveTab('executions')} className="bg-primary text-surface-lowest hover:bg-primary-fixed border border-primary font-body-sm text-body-sm font-medium px-3 py-1.5 rounded-DEFAULT transition-colors flex items-center gap-2">
                    <span className="material-symbols-outlined text-[16px]">play_arrow</span>
                    Execute
                  </button>
                </div>
              </div>

              <div className="flex justify-between items-center w-full max-w-[1440px] mx-auto gap-4">
                <div className="flex items-center p-1 bg-surface-container-low border border-border-subtle rounded-lg relative">
                  <div 
                    className="absolute top-1 bottom-1 w-24 bg-surface-container border border-border-subtle rounded-DEFAULT shadow-sm pill-tab-bg"
                    style={{ left: activeTab === 'visualizer' ? '4px' : activeTab === 'definition' ? '100px' : '196px' }}
                  ></div>
                  <button onClick={() => setActiveTab('visualizer')} className={`relative z-10 w-24 py-1.5 font-body-sm text-body-sm font-medium transition-colors ${activeTab === 'visualizer' ? 'text-primary' : 'text-on-surface-variant hover:text-primary'}`}>
                    Visualizer
                  </button>
                  <button onClick={() => setActiveTab('definition')} className={`relative z-10 w-24 py-1.5 font-body-sm text-body-sm transition-colors ${activeTab === 'definition' ? 'text-primary font-medium' : 'text-on-surface-variant hover:text-primary'}`}>
                    Definition
                  </button>
                  <button onClick={() => setActiveTab('executions')} className={`relative z-10 w-24 py-1.5 font-body-sm text-body-sm transition-colors ${activeTab === 'executions' ? 'text-primary font-medium' : 'text-on-surface-variant hover:text-primary'}`}>
                    Executions
                  </button>
                </div>

                <div className="flex items-center gap-2 overflow-x-auto">
                  <span className="text-label-caps font-label-caps text-on-surface-variant shrink-0">Revisions</span>
                  {revisions.map((revision) => (
                    <button
                      key={revision.id}
                      onClick={() => handleRevisionSelected(revision)}
                      className={`shrink-0 border rounded px-3 py-1.5 text-left transition-colors ${selectedRevision.id === revision.id ? 'bg-surface-container-high border-status-info text-primary' : 'bg-surface-elevated border-border-subtle text-on-surface-variant hover:text-primary hover:border-border-muted'}`}
                    >
                      <span className="flex items-center gap-2 text-body-sm font-body-sm">
                        {revision.label}
                        {revision.active && <span className="text-[10px] uppercase text-status-success border border-status-success/20 bg-status-success/10 rounded px-1">Active</span>}
                      </span>
                      <span className="block font-mono-sm text-[10px] opacity-70">{revision.timestamp}</span>
                    </button>
                  ))}
                </div>
              </div>
            </>
            )}
          </header>

          {!selectedWorkflow ? (
            <div className="flex-1 min-h-0 overflow-hidden">
              <WorkflowListView workflows={workflows} onSelect={handleWorkflowSelected} />
            </div>
          ) : activeTab === 'executions' ? (
            <div className="flex-1 min-h-0 overflow-hidden">
              <ExecutionStatusView workflowName={selectedWorkflow.name} revisionLabel={selectedRevision.label} runs={selectedRevision.runs} />
            </div>
          ) : (
          <div className="flex-1 flex overflow-hidden">
            {/* Left Pane: Visualizer/Editor */}
            <div className="flex-1 relative bg-surface-lowest overflow-hidden">
               {activeTab === 'visualizer' ? (
                 <AslGraphViewer
                   definition={currentDefinition}
                   selectedStateName={selectedStateName}
                   onStateSelect={setSelectedStateName}
                 />
               ) : (
                 <AslCodeViewer definition={currentDefinition} />
               )}
            </div>

            {/* Right Pane */}
            <div className="w-[400px] border-l border-border-subtle bg-surface-base hidden lg:flex flex-col z-20 shadow-[-8px_0_24px_rgba(0,0,0,0.2)]">
               {activeTab === 'visualizer' ? (
                 <NodeDetailsPanel definition={currentDefinition} selectedStateName={selectedStateName} />
               ) : (
                 <WorkflowGeneratorPanel onWorkflowGenerated={handleWorkflowGenerated} />
               )}
            </div>
          </div>
          )}
        </main>
      </div>
    </div>
  );
}

export default App;
