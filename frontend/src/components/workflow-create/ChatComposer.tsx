import type { Dispatch, RefObject, SetStateAction } from 'react';
import { Bot, ChevronDown, Loader2, Plus, Sparkles, Square } from 'lucide-react';
import type { AiModel } from './types';

type Props = {
  instruction: string;
  onInstructionChange: (value: string) => void;
  onSubmit: () => void;
  instructionTextareaRef: RefObject<HTMLTextAreaElement | null>;
  modelPickerRef: RefObject<HTMLDivElement | null>;
  selectedModel: AiModel | null;
  filteredModels: AiModel[];
  modelId: string;
  modelSearch: string;
  onModelSearchChange: (value: string) => void;
  modelPickerOpen: boolean;
  setModelPickerOpen: Dispatch<SetStateAction<boolean>>;
  modelPickerPlacement: 'down' | 'up';
  setModelPickerPlacement: (placement: 'down' | 'up') => void;
  onModelSelected: (modelId: string) => void;
  onOpenModelSettings: () => void;
  generating: boolean;
  canGenerate: boolean;
  /** Aborts the in-flight turn. When provided, the send button becomes a stop button. */
  onCancel?: () => void;
  placeholder?: string;
  /** Narrow the model picker for the sidebar, where a full-width dropdown would overflow. */
  compact?: boolean;
};

export function ChatComposer({
  instruction,
  onInstructionChange,
  onSubmit,
  instructionTextareaRef,
  modelPickerRef,
  selectedModel,
  filteredModels,
  modelId,
  modelSearch,
  onModelSearchChange,
  modelPickerOpen,
  setModelPickerOpen,
  modelPickerPlacement,
  setModelPickerPlacement,
  onModelSelected,
  onOpenModelSettings,
  generating,
  canGenerate,
  onCancel,
  placeholder = 'Describe a workflow...',
  compact = false,
}: Props) {
  // While a turn runs we expose a stop button rather than a disabled/loading composer, so the input
  // does not read as "loading" and the request can be cancelled.
  const showCancel = generating && Boolean(onCancel);
  const hasPrompt = instruction.trim().length > 0;
  const openModelPicker = () => {
    if (!modelPickerOpen) {
      const rect = modelPickerRef.current?.getBoundingClientRect();
      setModelPickerPlacement(rect && window.innerHeight - rect.bottom < 220 ? 'up' : 'down');
    }
    setModelPickerOpen(true);
  };
  const handleSubmitIntent = () => {
    if (!hasPrompt || generating) return;
    if (!selectedModel) {
      openModelPicker();
      return;
    }
    onSubmit();
  };
  const sendDisabled = !hasPrompt || generating || (selectedModel !== null && !canGenerate);
  const sendTitle = !hasPrompt
    ? 'Enter a prompt'
    : !selectedModel
      ? 'Select a model before sending'
      : 'Send message';

  return (
    <div className="voyager-composer-shell relative min-h-[126px] rounded-[14px] border p-[18px] pb-[60px] transition-colors">
      <div className="flex items-start gap-3">
        <Sparkles size={15} className="mt-[2px] shrink-0 text-primary" />
        <textarea
          ref={instructionTextareaRef}
          value={instruction}
          onChange={(event) => onInstructionChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault();
              handleSubmitIntent();
            }
          }}
          rows={1}
          className="max-h-[190px] min-h-[46px] w-full resize-none overflow-hidden border-0 bg-transparent p-0 pb-7 font-body-md text-[13px] leading-[1.5] text-on-surface shadow-none outline-none placeholder:text-on-surface-variant/75 focus:border-0 focus:outline-none focus:ring-0 focus-visible:outline-none"
          placeholder={placeholder}
          disabled={generating && !showCancel}
        />
      </div>

      <div ref={modelPickerRef} className="absolute bottom-[18px] left-[18px] w-fit">
        <button
          type="button"
          onClick={() => {
            if (modelPickerOpen) {
              setModelPickerOpen(false);
            } else {
              openModelPicker();
            }
          }}
          disabled={generating}
          title={selectedModel ? `Selected model: ${selectedModel.label}` : 'Select a model before sending'}
          className="inline-flex h-[38px] max-w-[220px] items-center justify-start gap-2 rounded-lg border border-border-subtle bg-surface-container-low px-3 text-left text-body-md text-on-surface shadow-[inset_0_0_0_1px_rgba(255,255,255,0.02)] transition-colors hover:border-primary/45 hover:bg-surface-container disabled:cursor-not-allowed disabled:opacity-60"
        >
          <span className="flex min-w-0 items-center gap-2">
            <Bot size={14} className="shrink-0 text-primary" />
            <span className={`truncate font-mono-sm text-label-mono ${selectedModel ? 'text-on-surface' : 'text-on-surface-variant'}`}>
              {selectedModel?.label || 'Select model'}
            </span>
          </span>
          <ChevronDown size={14} className="shrink-0 text-on-surface-variant transition-transform" />
        </button>

        {modelPickerOpen && (
          <div className={`absolute left-0 z-50 rounded-DEFAULT border border-border-subtle bg-surface-container-lowest p-2 shadow-[0_18px_60px_rgba(0,0,0,0.55)] ${
            compact ? 'w-[244px]' : 'w-[min(448px,calc(100vw-32px))]'
          } ${
            modelPickerPlacement === 'up' ? 'bottom-[46px]' : 'top-[46px]'
          }`}
          >
            <div className="flex gap-2">
              <input
                value={modelSearch}
                onChange={(event) => onModelSearchChange(event.target.value)}
                className="h-10 min-w-0 flex-1 rounded-DEFAULT border border-primary/25 bg-surface-container px-3 font-mono-sm text-label-mono text-primary outline-none placeholder:text-on-surface-variant/55 focus:border-primary/60"
                placeholder="Search models..."
              />
              <button
                type="button"
                onClick={onOpenModelSettings}
                className="flex h-10 w-10 items-center justify-center rounded-DEFAULT border border-primary/25 bg-surface-container text-primary transition-colors hover:border-primary/60 hover:bg-surface-container-high"
                title="Add model"
              >
                <Plus size={16} />
              </button>
            </div>

            <div className="mt-2 max-h-[104px] space-y-1 overflow-y-auto pr-1">
              {filteredModels.length === 0 ? (
                <div className="px-2 py-5 text-center text-body-sm text-on-surface-variant">
                  No models added.
                </div>
              ) : filteredModels.map((model) => (
                <button
                  key={model.id}
                  type="button"
                  onClick={() => {
                    onModelSelected(model.id);
                    setModelPickerOpen(false);
                  }}
                  title={compact ? `${model.label} - ${model.endpoint}` : undefined}
                  className={`grid h-8 w-full items-center gap-3 rounded-DEFAULT px-2 text-left transition-colors hover:bg-surface-container ${
                    compact
                      ? 'grid-cols-[minmax(0,1fr)_16px]'
                      : 'grid-cols-[minmax(0,1fr)_minmax(140px,1fr)_16px]'
                  }`}
                >
                  <span className="flex min-w-0 items-center gap-2">
                    <Bot size={14} className="shrink-0 text-primary" />
                    <span className="truncate font-mono-sm text-[12px] font-semibold text-primary">{model.label}</span>
                  </span>
                  {!compact && (
                    <span className="truncate font-mono-sm text-[11px] text-on-surface-variant">{model.endpoint}</span>
                  )}
                  <span className={`h-2 w-2 rounded-full ${model.id === modelId ? 'bg-primary' : 'bg-border-muted'}`} />
                </button>
              ))}
            </div>
          </div>
        )}
      </div>

      {showCancel ? (
        <button
          type="button"
          onClick={onCancel}
          title="Stop generating"
          className="absolute bottom-[18px] right-[18px] flex h-[38px] w-[38px] items-center justify-center rounded-lg border border-border-subtle bg-surface-container-high text-on-surface transition-colors hover:border-status-error/50 hover:text-status-error"
        >
          <Square size={14} fill="currentColor" />
        </button>
      ) : (
        <button
          type="button"
          onClick={handleSubmitIntent}
          disabled={sendDisabled}
          title={sendTitle}
          className="absolute bottom-[18px] right-[18px] flex h-[38px] w-[38px] items-center justify-center rounded-lg border border-primary/40 bg-primary text-on-primary shadow-[0_12px_34px_rgba(239,138,76,0.28)] transition-colors hover:bg-primary-fixed-dim disabled:cursor-not-allowed disabled:opacity-45"
        >
          {generating ? <Loader2 className="animate-spin" size={16} /> : <span className="material-symbols-outlined text-[18px]">arrow_upward</span>}
        </button>
      )}
    </div>
  );
}
