import Editor from '@monaco-editor/react';

interface Props {
  definition: any;
}

export function AslCodeViewer({ definition }: Props) {
  const jsonString = definition ? JSON.stringify(definition, null, 2) : '// Generate a workflow to see ASL JSON here';

  return (
    <div style={{ height: '100%', width: '100%' }}>
      <Editor
        height="100%"
        defaultLanguage="json"
        theme="vs-dark"
        value={jsonString}
        options={{
          readOnly: true,
          minimap: { enabled: false },
          scrollBeyondLastLine: false,
          fontSize: 14,
          fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
          wordWrap: 'on'
        }}
      />
    </div>
  );
}
