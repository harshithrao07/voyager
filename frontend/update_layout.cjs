const fs = require('fs');
let content = fs.readFileSync('src/components/CreateWorkflowView.tsx', 'utf-8');

const returnStatement = '  return (\n    <div className="voyager-main-bg flex h-full min-h-0 flex-col text-on-surface">';

const chatInputStart = '                <div className="relative rounded-lg border border-border-subtle bg-surface-container-low p-4 pb-16 transition-colors focus-within:border-secondary shadow-lg">';
const chatInputEnd = '                </div>\n              </div>\n            </div>\n          </div>';

// Extract the input node part
const startIndex = content.indexOf(chatInputStart);
if (startIndex === -1) {
    console.error('start index not found');
    process.exit(1);
}

// We know where it ends roughly, let's find the closing of the chat input box.
// It ends with the arrow_upward button.
const buttonEnd = '</button>\n                </div>';
const buttonEndIndex = content.indexOf(buttonEnd, startIndex);
if (buttonEndIndex === -1) {
    console.error('end index not found');
    process.exit(1);
}

const chatInputCode = content.substring(startIndex, buttonEndIndex + buttonEnd.length);

// Now define chatInputNode right before return
const chatInputDef = `  const chatInputNode = (\n${chatInputCode.split('\n').map(l => '  ' + l).join('\n')}\n  );\n\n`;

content = content.replace(returnStatement, chatInputDef + returnStatement);

// Now replace the AI mode block
const startTag = "{mode === 'ai' ? (";
const endTag = "          {isEditorOpen && (";

const aiModeStartIndex = content.indexOf(startTag);
const aiModeEndIndex = content.indexOf(endTag);

const newAiMode = `{mode === 'ai' ? (
        <div className="flex flex-1 min-h-0 bg-transparent overflow-hidden">
          <div className="flex flex-1 flex-col overflow-hidden relative">
            {messages.length === 0 ? (
              <div className="flex flex-1 flex-col items-center justify-center p-8 pb-[10vh]">
                <div className="mb-12 flex flex-col items-center text-center">
                  <div className="inline-flex items-center justify-center gap-1.5">
                    <img src="/voyager-logo.svg" alt="" className="h-24 w-24 shrink-0 md:h-28 md:w-28" />
                    <div className="font-mono-sm text-[46px] font-semibold leading-none tracking-normal text-primary md:text-[58px]">Voyager</div>
                  </div>
                  <p className="mt-2 w-full max-w-[430px] font-mono-sm text-label-mono uppercase text-secondary/70">Smooth sailing for complex workflows</p>
                </div>
                <div className="w-full max-w-[900px] pointer-events-auto">
                  {chatInputNode}
                </div>
              </div>
            ) : (
              <>
                <div className="flex-1 overflow-y-auto p-8 pb-[200px]">
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
                </div>
                <div className="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-surface-base via-surface-base/90 to-transparent pt-12 pb-6 px-8 pointer-events-none">
                  <div className="mx-auto w-full max-w-[900px] pointer-events-auto">
                    {chatInputNode}
                  </div>
                </div>
              </>
            )}
          </div>

`;

content = content.slice(0, aiModeStartIndex) + newAiMode + content.slice(aiModeEndIndex);

fs.writeFileSync('src/components/CreateWorkflowView.tsx', content);
console.log('Success');
