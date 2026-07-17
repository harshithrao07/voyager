import { useRef, type ChangeEvent } from 'react';
import { toast } from 'sonner';

export type TemplateImportHandler = (raw: string, fileName?: string) => boolean | void;

export function useTemplateFilePicker(onImportTemplate?: TemplateImportHandler) {
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const openTemplatePicker = () => fileInputRef.current?.click();

  const handleTemplateFile = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    // The picker filter is advisory on some platforms, so the extension is re-checked here.
    if (!file.name.toLowerCase().endsWith('.json')) {
      toast.error('Import failed. Only .json templates can be imported.');
      return;
    }
    let raw: string;
    try {
      raw = await file.text();
    } catch {
      toast.error(`Import failed. Could not read ${file.name}.`);
      return;
    }
    onImportTemplate?.(raw, file.name);
  };

  const templateFileInput = (
    <input
      ref={fileInputRef}
      type="file"
      data-testid="workflow-template-file"
      accept=".json,application/json"
      className="hidden"
      onChange={handleTemplateFile}
    />
  );

  return { openTemplatePicker, templateFileInput };
}
