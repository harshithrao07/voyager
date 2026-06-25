import { useState } from 'react';
import { WorkflowGeneratorPanel } from './components/WorkflowGeneratorPanel';
import { AslCodeViewer } from './components/AslCodeViewer';
import { AslGraphViewer } from './components/AslGraphViewer';

function App() {
  const [workflowDef, setWorkflowDef] = useState<any>({
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
      "Process Embeddings": {
        "Type": "Task",
        "Resource": "ECS::RunTask",
        "End": true
      },
      "Log Failure": {
        "Type": "Task",
        "Resource": "SNS::Publish",
        "End": true
      }
    }
  });
  const [activeTab, setActiveTab] = useState<'visualizer' | 'definition' | 'executions'>('visualizer');

  return (
    <div className="font-body-sm text-body-sm overflow-hidden selection:bg-status-accent/30 selection:text-primary">
      {/* TopNavBar */}
      <nav className="bg-surface-base dark:bg-surface-base border-b border-border-subtle dark:border-border-subtle backdrop-blur-md fixed top-0 left-0 w-full z-50 flex justify-between items-center px-gutter h-14">
        <div className="flex items-center gap-element-gap-md">
          <div className="text-headline-md font-headline-md font-extrabold text-primary dark:text-primary">
            AIGen_Workflow
          </div>
          <div className="hidden md:flex items-center gap-element-gap-sm ml-8 h-full">
            <div className="relative group mr-4">
              <span className="material-symbols-outlined absolute left-2 top-1/2 -translate-y-1/2 text-on-surface-variant text-[16px]">search</span>
              <input className="bg-surface-elevated border border-border-subtle rounded-DEFAULT text-body-sm font-body-sm text-primary pl-8 pr-3 py-1 focus:outline-none focus:border-status-info focus:ring-1 focus:ring-status-info transition-colors w-48 placeholder-on-surface-variant/50" placeholder="Search..." type="text" />
            </div>
            <a className="text-on-surface-variant dark:text-on-surface-variant hover:text-primary dark:hover:text-primary transition-colors duration-200 h-full flex items-center px-2" href="#">Projects</a>
            <a className="text-on-surface-variant dark:text-on-surface-variant hover:text-primary dark:hover:text-primary transition-colors duration-200 h-full flex items-center px-2" href="#">Deployments</a>
            <a className="text-on-surface-variant dark:text-on-surface-variant hover:text-primary dark:hover:text-primary transition-colors duration-200 h-full flex items-center px-2" href="#">Settings</a>
          </div>
        </div>
        <div className="flex items-center gap-element-gap-md">
          <div className="flex items-center gap-element-gap-sm text-on-surface-variant">
            <button className="hover:text-primary transition-colors duration-200 w-8 h-8 flex items-center justify-center rounded-DEFAULT hover:bg-surface-container">
              <span className="material-symbols-outlined text-[18px]">notifications</span>
            </button>
            <button className="hover:text-primary transition-colors duration-200 w-8 h-8 flex items-center justify-center rounded-DEFAULT hover:bg-surface-container">
              <span className="material-symbols-outlined text-[18px]">terminal</span>
            </button>
          </div>
          <button className="bg-primary text-surface-lowest hover:bg-primary-fixed transition-colors font-body-sm text-body-sm font-medium px-4 py-1.5 rounded-DEFAULT">
            Deploy
          </button>
          <div className="w-8 h-8 rounded-full bg-surface-container-high overflow-hidden border border-border-subtle ml-2">
            <img alt="User profile" className="w-full h-full object-cover" src="https://lh3.googleusercontent.com/aida-public/AB6AXuDT4lJ1ZoPwuY5IP-oiWUqi2VhMpVFK1mmdiprcBmVO9ReLNgDzecUiUGZ4OB0fkJZ-PyQ_opXcc7yOI8eqXEgoRLn4x99xR7gjOWsErdbf11wHdVvQjlnc9K5bWmb8p1vDFpX28iawDAkJaVH4xoD-KDgmb3IGedhE42lz_XP9189WQRMlhE8U1mX92E7M1UgMQ7xPuSW8k5NdlyyA_kN1Q52z_bcsxFM96T9aQ-gkhkwffxTjJNfUeOoTDmzsAu7hgWuVrEnGbPTb" />
          </div>
        </div>
      </nav>

      <div className="flex h-screen pt-14">
        {/* SideNavBar */}
        <aside className="bg-surface-container-low dark:bg-surface-container-low border-r border-border-subtle dark:border-border-subtle fixed left-0 top-14 h-[calc(100vh-3.5rem)] w-sidebar-width flex-col py-4 hidden md:flex z-40">
          <div className="px-4 mb-6">
            <div className="flex items-center gap-3 mb-4">
              <div className="w-10 h-10 rounded-DEFAULT bg-surface-elevated border border-border-subtle flex items-center justify-center overflow-hidden">
                <img alt="Project Icon" className="w-full h-full object-cover" src="https://lh3.googleusercontent.com/aida-public/AB6AXuAID3DvDx9O42iDw0SbcsskwdaQIYt0zdoVF8rQMCiNmcDkIqObGxJu8TDPf3YliawP0qfZJKotGC1yD_EnVl9XNhaGaQQqy9M5ZuT5Dubmo7cg6_KA4Xh6glLMHHvBCqhNGAvMvYc9ld0SWdOF2VwugGMx9_truePISK55-010DhQHe0jbxnYvwBygmSilaAZ_WUtg9sSkGO6DQnK2FWyiknFRlLypkcoPCeYxibgEgf0qSJrN-DRU_QJQfX4nxVEYGsxEfyXLIkEE" />
              </div>
              <div>
                <div className="text-headline-md font-headline-md font-extrabold text-primary dark:text-primary">AI Engine v2</div>
                <div className="text-label-caps font-label-caps text-on-surface-variant">Production Cluster</div>
              </div>
            </div>
            <button className="w-full bg-surface-elevated hover:bg-surface-container border border-border-subtle text-primary font-body-sm text-body-sm py-2 rounded-DEFAULT transition-colors flex items-center justify-center gap-2">
              <span className="material-symbols-outlined text-[16px]">add</span>
              New Workflow
            </button>
          </div>
          <div className="flex-1 overflow-y-auto px-2 space-y-1">
            <a className="flex items-center gap-3 px-3 py-2 rounded-DEFAULT text-on-surface-variant dark:text-on-surface-variant opacity-70 hover:bg-surface-container dark:hover:bg-surface-container hover:opacity-100 transition-all" href="#">
              <span className="material-symbols-outlined text-[18px]">edit_note</span>
              <span className="font-body-sm text-body-sm">Editor</span>
            </a>
            <a className="flex items-center gap-3 px-3 py-2 rounded-DEFAULT text-on-surface-variant dark:text-on-surface-variant opacity-70 hover:bg-surface-container dark:hover:bg-surface-container hover:opacity-100 transition-all" href="#">
              <span className="material-symbols-outlined text-[18px]">account_tree</span>
              <span className="font-body-sm text-body-sm">Graphs</span>
            </a>
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
              <span className="material-symbols-outlined text-[18px]">help</span>
              <span className="font-body-sm text-body-sm">Support</span>
            </a>
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
                <span className="hover:text-primary cursor-pointer transition-colors">AIGen_Workflow</span>
                <span className="material-symbols-outlined text-[14px]">chevron_right</span>
                <span className="text-primary font-medium flex items-center gap-1.5">
                  <span className="material-symbols-outlined text-[14px] text-status-success">check_circle</span>
                  data-pipeline-v3
                </span>
              </div>
              <div className="flex items-center gap-3">
                <div className="flex items-center gap-1.5 text-on-surface-variant font-mono-sm text-mono-sm bg-surface-container-low px-2 py-1 rounded-DEFAULT border border-border-subtle">
                  <span className="w-2 h-2 rounded-full bg-status-success/50 border border-status-success"></span>
                  Healthy
                </div>
                <div className="text-on-surface-variant font-mono-sm text-mono-sm">
                  Last execution: 2m ago
                </div>
              </div>
            </div>
            
            {/* Tabs */}
            <div className="flex justify-between items-center w-full max-w-[1440px] mx-auto">
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
              <div className="flex items-center gap-2">
                <button className="bg-surface-elevated hover:bg-surface-container border border-border-subtle text-primary font-body-sm text-body-sm px-3 py-1.5 rounded-DEFAULT transition-colors flex items-center gap-2">
                  <span className="material-symbols-outlined text-[16px]">history</span>
                  Revert
                </button>
                <button className="bg-primary text-surface-lowest hover:bg-primary-fixed border border-primary font-body-sm text-body-sm font-medium px-3 py-1.5 rounded-DEFAULT transition-colors flex items-center gap-2">
                  <span className="material-symbols-outlined text-[16px]">play_arrow</span>
                  Execute
                </button>
              </div>
            </div>
          </header>

          <div className="flex-1 flex overflow-hidden">
            {/* Left Pane: Visualizer/Editor */}
            <div className="flex-1 relative bg-surface-lowest overflow-hidden">
               {activeTab === 'visualizer' ? (
                 <AslGraphViewer definition={workflowDef} />
               ) : (
                 <AslCodeViewer definition={workflowDef} />
               )}
            </div>

            {/* Right Pane: Generator */}
            <div className="w-[400px] border-l border-border-subtle bg-surface-base hidden lg:flex flex-col z-20 shadow-[-8px_0_24px_rgba(0,0,0,0.2)]">
               <WorkflowGeneratorPanel onWorkflowGenerated={setWorkflowDef} />
            </div>
          </div>
        </main>
      </div>
    </div>
  );
}

export default App;
