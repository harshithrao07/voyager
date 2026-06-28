import { useState } from 'react';
import { Play, Loader2, AlertCircle } from 'lucide-react';
import { generateWorkflow } from '../api';

interface Props {
  onWorkflowGenerated: (definition: any) => void;
}

export function WorkflowGeneratorPanel({ onWorkflowGenerated }: Props) {
  const [instruction, setInstruction] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleGenerate = async () => {
    if (!instruction.trim()) return;
    
    setLoading(true);
    setError(null);
    try {
      const response = await generateWorkflow({ instruction });
      if (response.validationIssues && response.validationIssues.length > 0) {
        setError('Validation issues: ' + response.validationIssues.join(', '));
      }
      onWorkflowGenerated(response.definition);
    } catch (err: any) {
      setError(err.message || 'An error occurred during generation');
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      <div className="glass-shell px-5 py-4 border-b border-border-subtle flex items-center justify-between bg-surface-elevated/50 backdrop-blur-md">
        <h2 className="text-headline-md font-headline-md font-medium text-primary">Generation Controls</h2>
        <button className="text-on-surface-variant hover:text-primary transition-colors">
          <span className="material-symbols-outlined text-[20px]">close</span>
        </button>
      </div>
      
      <div className="flex-1 overflow-y-auto p-5 space-y-5">
        
        {/* Header Card Style for Instructions */}
        <div className="glass-card bg-surface-elevated border border-border-subtle rounded-lg p-4 relative overflow-hidden">
          <div className="absolute top-0 left-0 w-1 h-full bg-status-info"></div>
          <div className="flex items-start justify-between mb-2 pl-2">
            <div>
              <div className="text-label-caps font-label-caps text-status-info mb-1">PROMPT</div>
              <div className="text-headline-md font-headline-md font-medium text-primary">Workflow Objective</div>
            </div>
            <span className="material-symbols-outlined text-on-surface-variant">terminal</span>
          </div>
          <div className="pl-2 mt-4">
            <p className="text-body-sm font-body-sm text-on-surface-variant leading-relaxed">
              Describe the workflow sequence and required states. The AI will output standard ASL JSON.
            </p>
          </div>
        </div>

        {/* Input Area */}
        <div className="space-y-3">
          <h3 className="text-label-caps font-label-caps text-on-surface-variant">INSTRUCTION</h3>
          <div className="glass-card bg-surface-lowest border border-border-subtle rounded-lg p-1 relative focus-within:border-status-info transition-colors">
            <textarea
              className="w-full min-h-[180px] bg-transparent text-mono-sm font-mono-sm text-primary p-3 focus:outline-none resize-y placeholder-on-surface-variant/50"
              placeholder="{\n  // e.g. Fetch data from S3\n  // Check if count > 5\n  // Execute Lambda\n}" 
              value={instruction}
              onChange={(e) => setInstruction(e.target.value)}
              disabled={loading}
            />
          </div>
        </div>

        {/* Config Bento */}
        <div className="grid grid-cols-2 gap-3">
          <div className="glass-card bg-surface-container-low border border-border-subtle rounded-lg p-3">
            <div className="text-label-caps font-label-caps text-on-surface-variant mb-1">MODEL</div>
            <div className="text-mono-sm font-mono-sm text-primary truncate">Llama 3 8B</div>
          </div>
          <div className="glass-card bg-surface-container-low border border-border-subtle rounded-lg p-3">
            <div className="text-label-caps font-label-caps text-on-surface-variant mb-1">VALIDATION</div>
            <div className="text-mono-sm font-mono-sm text-status-success">Enabled</div>
          </div>
        </div>

        {error && (
          <div className="bg-error-container/20 border border-error-container/50 rounded-lg p-3 flex gap-2 items-start text-error text-body-sm">
            <AlertCircle size={16} className="mt-0.5 flex-shrink-0" />
            <div>{error}</div>
          </div>
        )}
      </div>

      <div className="glass-shell p-5 border-t border-border-subtle">
        <button 
          className="w-full bg-primary text-surface-lowest hover:bg-primary-fixed border border-primary font-body-sm text-body-sm font-medium px-4 py-2.5 rounded-DEFAULT transition-colors flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          onClick={handleGenerate}
          disabled={loading || !instruction.trim()}
        >
          {loading ? (
            <>
              <Loader2 className="animate-spin" size={16} />
              Compiling ASL...
            </>
          ) : (
            <>
              <Play size={16} fill="currentColor" />
              Generate Workflow
            </>
          )}
        </button>
      </div>
    </>
  );
}
