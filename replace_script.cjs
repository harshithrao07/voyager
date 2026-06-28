const fs = require('fs');
const content = fs.readFileSync('src/components/CreateWorkflowView.tsx', 'utf-8');

const startTag = "{mode === 'ai' ? (";
const endTag = ") : (\n        <div className=\"flex flex-1 min-h-0 flex-col bg-transparent\">\n        <div className=\"grid flex-1 min-h-0 grid-cols-1 overflow-hidden xl:grid-cols-[minmax(0,1fr)_360px]\">";

const startIndex = content.indexOf(startTag);
const endIndex = content.indexOf(endTag);

if (startIndex === -1 || endIndex === -1) {
  console.error("Tags not found");
  process.exit(1);
}

const replacement = `{mode === 'ai' ? (
        <div className="flex flex-1 min-h-0 bg-transparent overflow-hidden">
          <div className="flex flex-1 flex-col overflow-hidden relative">
            <div className="flex-1 overflow-y-auto p-8 pb-[200px]">
              {messages.length === 0 ? (
                <div className="mt-16 flex flex-col items-center text-center">
                  <div className="inline-flex items-center justify-center gap-1.5">
                    <img src="/voyager-logo.svg" alt="" className="h-24 w-24 shrink-0 md:h-28 md:w-28" />
                    <div className="font-mono-sm text-[46px] font-semibold leading-none tracking-normal text-primary md:text-[58px]">Voyager</div>
                  </div>
                  <p className="mt-2 w-full max-w-[430px] font-mono-sm text-label-mono uppercase text-secondary/70">Smooth sailing for complex workflows</p>
                </div>
              ) : (
                <div className="mx-auto w-full max-w-[900px] space-y-6">
                  {messages.map((msg) => (
                    <div key={msg.id} className={\`flex \${msg.role === 'user' ? 'justify-end' : 'justify-start'}\`}>
                      <div className={\`max-w-[80%] rounded-lg p-4 text-body-md \${msg.role === 'user' ? 'bg-surface-container-high text-on-surface' : 'bg-transparent text-on-surface'}\`}>
                        {msg.role === 'user' ? (
                          <div className="whitespace-pre-wrap">{msg.content}</div>
                        ) : (
                          <div className="prose prose-invert prose-sm max-w-none">
                            <ReactMarkdown>{msg.content}</ReactMarkdown>
                          </div>
                        )}
                      </div>
                    </div>
                  ))}
                  {generating && (
                    <div className="flex justify-start">
                      <div className="flex items-center gap-2 max-w-[80%] rounded-lg p-4 text-body-md text-on-surface-variant">
                        <Loader2 className="animate-spin" size={16} />
                        Generating ASL...
                      </div>
                    </div>
                  )}
                  {error && (
                    <div className="mt-4 rounded-DEFAULT border border-status-error/25 bg-status-error/10 p-3 text-body-sm text-status-error">
                      <div className="flex items-start gap-2">
                        <AlertCircle className="mt-0.5 shrink-0" size={16} />
                        <div>{error}</div>
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>

            <div className="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-surface-base via-surface-base/90 to-transparent pt-12 pb-6 px-8 pointer-events-none">
              <div className="mx-auto w-full max-w-[900px] pointer-events-auto">
                <div className="relative rounded-lg border border-border-subtle bg-surface-container-low p-4 pb-16 transition-colors focus-within:border-secondary shadow-lg">
                  <textarea
                    ref={instructionTextareaRef}
                    value={instruction}
                    onChange={(event) => setInstruction(event.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && !e.shiftKey) {
                        e.preventDefault();
                        handleGenerate();
                      }
                    }}
                    rows={1}
                    className="max-h-[220px] min-h-[56px] w-full resize-none overflow-hidden border-0 bg-transparent pb-8 font-mono-sm text-body-lg text-secondary shadow-none outline-none placeholder:text-secondary/45 focus:border-0 focus:outline-none focus:ring-0 focus-visible:outline-none"
                    placeholder="Message Voyager..."
                    disabled={generating}
                  />

                  <div ref={modelPickerRef} className="absolute bottom-4 left-4 w-fit">
                    <button
                      type="button"
                      onClick={() => setModelPickerOpen((open) => !open)}
                      disabled={generating}
                      className="flex h-9 w-fit items-center justify-start gap-2 rounded-DEFAULT px-2 text-left text-body-md text-on-surface transition-colors hover:bg-surface-container-high disabled:cursor-not-allowed disabled:opacity-60"
                    >
                      <span className="flex min-w-0 items-center gap-2">
                        <Bot size={14} className="shrink-0 text-primary" />
                        <span className={\`truncate font-mono-sm text-label-mono \${selectedModel ? 'text-on-surface' : 'text-on-surface-variant'}\`}>
                          {selectedModel?.label || 'Select model'}
                        </span>
                      </span>
                      <ChevronDown size={14} className="shrink-0 text-on-surface-variant transition-transform" />
                    </button>

                    {modelPickerOpen && (
                      <div className="absolute left-0 bottom-[48px] z-50 w-[448px] rounded-DEFAULT border border-border-subtle bg-surface-container-lowest p-2 shadow-[0_18px_60px_rgba(0,0,0,0.55)]">
                        <div className="flex gap-2">
                          <input
                            value={modelSearch}
                            onChange={(event) => setModelSearch(event.target.value)}
                            className="h-10 min-w-0 flex-1 rounded-DEFAULT border border-primary/25 bg-surface-container px-3 font-mono-sm text-label-mono text-primary outline-none placeholder:text-on-surface-variant/55 focus:border-primary/60"
                            placeholder="Search models..."
                          />
                          <button
                            type="button"
                            onClick={() => {
                              setModelPickerOpen(false);
                              setAddModelOpen(true);
                            }}
                            className="flex h-10 w-10 items-center justify-center rounded-DEFAULT border border-primary/25 bg-surface-container text-primary transition-colors hover:border-primary/60 hover:bg-surface-container-high"
                            title="Add model"
                          >
                            <Plus size={16} />
                          </button>
                        </div>

                        <div className="mt-2 space-y-1 max-h-[300px] overflow-y-auto">
                          {filteredModels.length === 0 ? (
                            <div className="px-2 py-5 text-center text-body-sm text-on-surface-variant">
                              No models added.
                            </div>
                          ) : filteredModels.map((model) => (
                            <button
                              key={model.id}
                              type="button"
                              onClick={() => {
                                setModelId(model.id);
                                setModelPickerOpen(false);
                              }}
                              className="grid h-8 w-full grid-cols-[minmax(0,1fr)_minmax(140px,1fr)_16px] items-center gap-3 rounded-DEFAULT px-2 text-left transition-colors hover:bg-surface-container"
                            >
                              <span className="flex min-w-0 items-center gap-2">
                                <Bot size={14} className="shrink-0 text-primary" />
                                <span className="truncate font-mono-sm text-[12px] font-semibold text-primary">{model.label}</span>
                              </span>
                              <span className="truncate font-mono-sm text-[11px] text-on-surface-variant">{model.endpoint}</span>
                              <span className={\`h-2 w-2 rounded-full \${model.id === modelId ? 'bg-primary' : 'bg-border-muted'}\`} />
                            </button>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>

                  <button
                    type="button"
                    onClick={handleGenerate}
                    disabled={!canGenerate}
                    className="absolute bottom-4 right-4 flex h-11 w-11 items-center justify-center rounded-DEFAULT border border-primary/25 bg-primary/35 text-on-surface transition-colors hover:bg-primary hover:text-on-primary disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {generating ? <Loader2 className="animate-spin" size={18} /> : <span className="material-symbols-outlined text-[20px]">arrow_upward</span>}
                  </button>
                </div>
              </div>
            </div>
          </div>

          {isEditorOpen && (
            <div className="flex w-[400px] xl:w-[500px] shrink-0 flex-col border-l border-border-subtle bg-surface-base relative">
              <div className="flex h-12 items-center justify-between border-b border-border-subtle px-4">
                <div className="flex items-center gap-2 font-mono-sm text-[12px] text-on-surface-variant">
                  <Braces size={14} />
                  Generated ASL
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => setIsEditorOpen(false)}
                    className="text-on-surface-variant hover:text-on-surface p-1 rounded-DEFAULT hover:bg-surface-container transition-colors"
                  >
                    <X size={16} />
                  </button>
                </div>
              </div>
              <div className="flex-1 overflow-hidden relative">
                <Editor
                  height="100%"
                  defaultLanguage="json"
                  theme="vs-dark"
                  value={definitionText}
                  onChange={(value) => setDefinitionText(value || '')}
                  options={{
                    minimap: { enabled: false },
                    scrollBeyondLastLine: false,
                    fontSize: 13,
                    fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
                    wordWrap: 'on',
                    tabSize: 2,
                    lineNumbersMinChars: 3,
                    padding: { top: 16, bottom: 16 },
                  }}
                />
              </div>
            </div>
          )}

          {addModelOpen && (
            <div className="fixed inset-0 z-[70] flex items-center justify-center bg-black/55 p-6 pointer-events-auto">
              <div className="flex max-h-[86vh] w-full max-w-4xl flex-col overflow-hidden rounded-lg border border-primary/20 bg-surface-lowest shadow-[0_24px_90px_rgba(0,0,0,0.65)]">
                <div className="flex h-16 shrink-0 items-center justify-between px-6 shadow-[inset_0_-1px_rgba(255,255,255,0.08)]">
                  <div className="flex items-center gap-2 font-display text-[20px] font-semibold text-primary">
                    <Sparkles size={18} />
                    Settings
                  </div>
                  <button
                    type="button"
                    onClick={() => setAddModelOpen(false)}
                    className="flex h-8 w-8 items-center justify-center rounded-DEFAULT border border-primary/30 text-primary transition-colors hover:bg-surface-container"
                    aria-label="Close add model"
                  >
                    <X size={16} />
                  </button>
                </div>

                <div className="grid min-h-0 flex-1 grid-cols-[200px_1fr] overflow-hidden">
                  <aside className="bg-surface-container-lowest p-3 shadow-[inset_-1px_0_rgba(255,255,255,0.08)]">
                    <div className="rounded-DEFAULT bg-surface-container px-3 py-2 text-body-sm font-medium text-primary">
                      Add Models
                    </div>
                    <div className="mt-3 space-y-1 text-body-sm text-on-surface-variant">
                      <div className="px-3 py-2">Added Models</div>
                      <div className="px-3 py-2">AI Defaults</div>
                      <div className="px-3 py-2">Search</div>
                    </div>
                  </aside>

                  <main className="space-y-3 overflow-y-auto p-6">
                    <section className="rounded-lg border border-primary/20 bg-surface-base p-4">
                      <div className="flex items-center justify-between gap-4">
                        <div className="flex items-center gap-3">
                          <Monitor size={18} className="text-primary" />
                          <div>
                            <h3 className="font-headline-md text-headline-md font-semibold text-primary">Add Local Models</h3>
                            <p className="mt-1 text-body-sm text-on-surface-variant">Add a local model server endpoint.</p>
                          </div>
                        </div>
                        <div className="flex gap-2">
                          <button type="button" className="flex h-9 items-center gap-2 rounded-DEFAULT border border-primary/30 px-3 text-body-sm text-primary">
                            <Play size={14} />
                            Test
                          </button>
                          <button type="button" className="flex h-9 w-9 items-center justify-center rounded-DEFAULT border border-primary/30 text-primary">
                            <MoreHorizontal size={16} />
                          </button>
                        </div>
                      </div>

                      <div className="mt-4 grid grid-cols-[140px_1fr_72px] gap-2">
                        <input
                          value={localModelName}
                          onChange={(event) => setLocalModelName(event.target.value)}
                          className={fieldClass}
                          placeholder="Model name"
                        />
                        <input
                          value={localEndpoint}
                          onChange={(event) => setLocalEndpoint(event.target.value)}
                          className={fieldClass}
                          placeholder="Endpoint URL, e.g. http://localhost:11434/v1"
                        />
                        <button
                          type="button"
                          onClick={addLocalModel}
                          className="mt-1 h-9 rounded-DEFAULT bg-primary px-3 text-body-sm font-medium text-surface-lowest transition-colors hover:bg-primary-fixed"
                        >
                          Add
                        </button>
                      </div>
                    </section>

                    <section className="rounded-lg border border-primary/20 bg-surface-base p-4">
                      <div className="flex items-center justify-between gap-4">
                        <div className="flex items-center gap-3">
                          <Globe2 size={18} className="text-primary" />
                          <div>
                            <h3 className="font-headline-md text-headline-md font-semibold text-primary">Add API Models</h3>
                            <p className="mt-1 text-body-sm text-on-surface-variant">Connect a cloud provider endpoint.</p>
                          </div>
                        </div>
                        <div className="flex gap-2">
                          <button type="button" className="flex h-9 items-center gap-2 rounded-DEFAULT border border-primary/30 px-3 text-body-sm text-primary">
                            <Play size={14} />
                            Test
                          </button>
                          <button type="button" className="flex h-9 w-9 items-center justify-center rounded-DEFAULT border border-primary/30 text-primary">
                            <MoreHorizontal size={16} />
                          </button>
                        </div>
                      </div>

                      <div className="mt-4 grid grid-cols-[160px_1fr_72px] gap-2">
                        <select
                          value={apiProvider}
                          onChange={(event) => setApiProvider(event.target.value)}
                          className={fieldClass}
                        >
                          <option>DeepSeek</option>
                          <option>OpenAI</option>
                          <option>Anthropic</option>
                          <option>OpenRouter</option>
                        </select>
                        <input
                          value={apiEndpoint}
                          onChange={(event) => setApiEndpoint(event.target.value)}
                          className={fieldClass}
                          placeholder="https://api.deepseek.com/v1"
                        />
                        <button
                          type="button"
                          onClick={addApiModel}
                          className="mt-1 h-9 rounded-DEFAULT bg-primary px-3 text-body-sm font-medium text-surface-lowest transition-colors hover:bg-primary-fixed"
                        >
                          Add
                        </button>
                      </div>
                      <div className="mt-2 grid grid-cols-2 gap-2">
                        <input
                          value={apiModelName}
                          onChange={(event) => setApiModelName(event.target.value)}
                          className={fieldClass}
                          placeholder="Model name, e.g. deepseek-chat"
                        />
                        <input
                          value={apiKey}
                          onChange={(event) => setApiKey(event.target.value)}
                          className={fieldClass}
                          placeholder="API key"
                          type="password"
                        />
                      </div>
                    </section>
                  </main>
                </div>
              </div>
            </div>
          )}
        </div>
`;

const newContent = content.slice(0, startIndex) + replacement + content.slice(endIndex);
fs.writeFileSync('src/components/CreateWorkflowView.tsx', newContent);
console.log('File updated successfully.');
