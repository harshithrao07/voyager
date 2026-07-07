import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import Editor from '@monaco-editor/react';
import { Tree, type MoveHandler, type NodeRendererProps, type RenameHandler, type TreeApi } from 'react-arborist';
import { Layout as DockLayout, Model, type IJsonModel, type TabNode } from 'flexlayout-react';
import 'flexlayout-react/style/dark.css';
import {
  AlertTriangle,
  ArrowLeft,
  Braces,
  ChevronDown,
  Copy,
  FileCode2,
  FileJson2,
  Folder,
  FolderPlus,
  GripVertical,
  Info,
  Loader2,
  Maximize2,
  Minimize2,
  MoreVertical,
  PanelRight,
  Pencil,
  Network,
  Play,
  Plus,
  Save,
  SlidersHorizontal,
  Trash2,
  X,
} from 'lucide-react';
import { executeFunctionCode } from '../../api';
import type {
  FunctionLanguageDTO,
  FunctionRunRequest,
  FunctionRunResult,
  FunctionSourceMode,
  FunctionVersionRequest,
} from '../../api';

type WorkbenchFile = {
  path: string;
  content: string;
};

type FileTreeNode = {
  id: string;
  name: string;
  path: string;
  kind: 'file' | 'directory';
  children?: FileTreeNode[];
};

type TestCase = {
  id: string;
  name: string;
  input: string;
  expectedOutput: string;
  expectedError: string;
};

type MultiFileValidation = {
  allowedExtensions: string[];
  entryPath: string;
  errors: string[];
};

type SingleFileValidation = {
  entryPath: string;
  errors: string[];
};

type FileContextMenu = {
  nodeId: string;
  path: string;
  kind: FileTreeNode['kind'];
  x: number;
  y: number;
};

type Props = {
  resourceLabel: string;
  languages: FunctionLanguageDTO[];
  languageId?: string;
  onLanguageChange?: (value: string) => void;
  description?: string;
  onDescriptionChange?: (value: string) => void;
  metadata: ReactNode;
  publishLabel?: string;
  saveDraftLabel?: string;
  onCancel: () => void;
  saveDraftDisabled?: boolean;
  onSaveDraft?: (request: FunctionVersionRequest) => Promise<void>;
  onPublish: (request: FunctionVersionRequest) => Promise<void>;
  publishDisabled?: boolean;
};

const controlClass =
  'h-9 w-full rounded-lg border border-border-subtle bg-surface-container-lowest px-3 text-[12px] text-on-surface outline-none transition-colors placeholder:text-on-surface-variant/45 focus:border-primary/60';
const selectControlClass = `${controlClass} py-0 leading-[34px]`;
const labelClass = 'mb-1.5 flex items-center gap-1.5 text-[11px] text-on-surface-variant';

function toNumber(value: string): number | undefined {
  if (value.trim() === '') return undefined;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function languageToMonaco(languageName: string | undefined) {
  const name = (languageName || '').toLowerCase();
  if (name.includes('python')) return 'python';
  if (name.includes('javascript') || name.includes('node') || name.includes('typescript')) return 'javascript';
  if (name.includes('java')) return 'java';
  if (name.includes('c++') || name.includes('cpp')) return 'cpp';
  if (name.includes('c#')) return 'csharp';
  if (name.includes('go')) return 'go';
  if (name.includes('ruby')) return 'ruby';
  if (name.includes('rust')) return 'rust';
  if (name.includes('php')) return 'php';
  if (name.includes('json')) return 'json';
  return 'plaintext';
}

function fileLanguage(path: string, selectedLanguage: string | undefined) {
  if (path.endsWith('.json')) return 'json';
  if (path.endsWith('.ts') || path.endsWith('.tsx')) return 'typescript';
  if (path.endsWith('.js') || path.endsWith('.jsx')) return 'javascript';
  if (path.endsWith('.py')) return 'python';
  if (path.endsWith('.java')) return 'java';
  if (path.endsWith('.go')) return 'go';
  if (path.endsWith('.rs')) return 'rust';
  if (path.endsWith('.cpp') || path.endsWith('.cc') || path.endsWith('.cxx')) return 'cpp';
  if (path.endsWith('.c')) return 'c';
  if (path.endsWith('.cs')) return 'csharp';
  if (path.endsWith('.rb')) return 'ruby';
  if (path.endsWith('.php')) return 'php';
  if (path.endsWith('.kt')) return 'kotlin';
  if (path.endsWith('.swift')) return 'swift';
  if (path.endsWith('.sh')) return 'shell';
  return languageToMonaco(selectedLanguage);
}

function normalizeFilePath(path: string) {
  return path.trim().replace(/^\.\//, '');
}

function fileExtension(path: string) {
  const fileName = normalizeFilePath(path).split('/').pop() || '';
  const dotIndex = fileName.lastIndexOf('.');
  return dotIndex > 0 ? fileName.slice(dotIndex).toLowerCase() : '';
}

/**
 * Maps a Judge0 language name to the conventional entrypoint filename and
 * extension, so the editor creates a file that matches the selected language
 * (e.g. Python -> main.py, Java -> Main.java, C++ -> main.cpp).
 */
function langMeta(languageName: string | undefined): { ext: string; entry: string } {
  const name = (languageName || '').toLowerCase();
  if (name.includes('python')) return { ext: 'py', entry: 'main.py' };
  if (name.includes('typescript')) return { ext: 'ts', entry: 'main.ts' };
  if (name.includes('javascript') || name.includes('node')) return { ext: 'js', entry: 'main.js' };
  if (name.includes('c++') || name.includes('cpp')) return { ext: 'cpp', entry: 'main.cpp' };
  if (name.includes('c#') || name.includes('csharp')) return { ext: 'cs', entry: 'Main.cs' };
  if (name.includes('kotlin')) return { ext: 'kt', entry: 'Main.kt' };
  if (name.includes('java')) return { ext: 'java', entry: 'Main.java' };
  if (name.includes('go') && !name.includes('cobol')) return { ext: 'go', entry: 'main.go' };
  if (name.includes('rust')) return { ext: 'rs', entry: 'main.rs' };
  if (name.includes('ruby')) return { ext: 'rb', entry: 'main.rb' };
  if (name.includes('php')) return { ext: 'php', entry: 'main.php' };
  if (name.includes('swift')) return { ext: 'swift', entry: 'main.swift' };
  if (name.includes('bash') || name.includes('shell')) return { ext: 'sh', entry: 'main.sh' };
  if (name.startsWith('c ') || name === 'c') return { ext: 'c', entry: 'main.c' };
  return { ext: 'txt', entry: 'main.txt' };
}

const DEFAULT_ENTRY_PATTERN = /^(?:src\/)?(?:main|Main)\.[a-zA-Z]+$/;
const STARTER_SOURCE_MARKER = 'Voyager passes the workflow state input';
const DATA_FILE_EXTENSIONS = ['.json', '.txt', '.md', '.yaml', '.yml', '.env'];
const MAX_SINGLE_FILE_CHARS = 256 * 1024;
const FUNCTION_WORKBENCH_LAYOUT: IJsonModel = {
  global: {
    tabEnableClose: false,
    tabEnableRename: false,
    tabSetEnableClose: false,
    tabSetEnableCloseButton: false,
    tabSetEnableMaximize: true,
    tabSetEnableTabScrollbar: true,
    tabSetMinHeight: 120,
    tabSetMinWidth: 150,
  },
  layout: {
    type: 'row',
    weight: 100,
    children: [
      {
        type: 'tabset',
        id: 'function-files-tabset',
        weight: 20,
        children: [
          { type: 'tab', id: 'function-files-tab', name: 'Files', component: 'files', enableClose: false },
        ],
      },
      {
        type: 'row',
        id: 'function-main-column',
        weight: 80,
        children: [
          {
            type: 'tabset',
            id: 'function-editor-tabset',
            weight: 68,
            children: [
              { type: 'tab', id: 'function-editor-tab', name: 'Editor', component: 'editor', enableClose: false },
            ],
          },
          {
            type: 'row',
            id: 'function-test-row',
            weight: 32,
            children: [
              {
                type: 'tabset',
                id: 'function-cases-tabset',
                weight: 28,
                children: [
                  { type: 'tab', id: 'function-cases-tab', name: 'Test cases', component: 'cases', enableClose: false },
                ],
              },
              {
                type: 'tabset',
                id: 'function-input-tabset',
                weight: 36,
                children: [
                  { type: 'tab', id: 'function-input-tab', name: 'Run input', component: 'input', enableClose: false },
                ],
              },
              {
                type: 'tabset',
                id: 'function-output-tabset',
                weight: 36,
                children: [
                  { type: 'tab', id: 'function-output-tab', name: 'Output', component: 'output', enableClose: false },
                ],
              },
            ],
          },
        ],
      },
    ],
  },
};

function uniqueExtensions(values: string[]) {
  return [...new Set(values.map((value) => value.toLowerCase()))];
}

function allowedFileExtensions(languageName: string | undefined): string[] {
  const name = (languageName || '').toLowerCase();
  const primary = `.${langMeta(languageName).ext}`;
  if (name.includes('typescript')) return uniqueExtensions(['.ts', '.js', ...DATA_FILE_EXTENSIONS]);
  if (name.includes('javascript') || name.includes('node')) return uniqueExtensions(['.js', '.mjs', '.cjs', ...DATA_FILE_EXTENSIONS]);
  if (name.includes('java')) return uniqueExtensions(['.java', '.properties', ...DATA_FILE_EXTENSIONS]);
  if (name.includes('kotlin')) return uniqueExtensions(['.kt', '.kts', ...DATA_FILE_EXTENSIONS]);
  if (name.includes('c++') || name.includes('cpp')) return uniqueExtensions(['.cpp', '.cc', '.cxx', '.h', '.hpp', '.hh', ...DATA_FILE_EXTENSIONS]);
  if (name.startsWith('c ') || name === 'c') return uniqueExtensions(['.c', '.h', ...DATA_FILE_EXTENSIONS]);
  if (name.includes('c#') || name.includes('csharp')) return uniqueExtensions(['.cs', ...DATA_FILE_EXTENSIONS]);
  if (name.includes('go') && !name.includes('cobol')) return uniqueExtensions(['.go', '.mod', '.sum', ...DATA_FILE_EXTENSIONS]);
  if (name.includes('rust')) return uniqueExtensions(['.rs', '.toml', ...DATA_FILE_EXTENSIONS]);
  if (name.includes('ruby')) return uniqueExtensions(['.rb', ...DATA_FILE_EXTENSIONS]);
  if (name.includes('php')) return uniqueExtensions(['.php', ...DATA_FILE_EXTENSIONS]);
  if (name.includes('swift')) return uniqueExtensions(['.swift', ...DATA_FILE_EXTENSIONS]);
  if (name.includes('bash') || name.includes('shell')) return uniqueExtensions(['.sh', '.bash', ...DATA_FILE_EXTENSIONS]);
  return uniqueExtensions([primary, ...DATA_FILE_EXTENSIONS]);
}

function samePath(left: string, right: string) {
  return normalizeFilePath(left).toLowerCase() === normalizeFilePath(right).toLowerCase();
}

function fileNodeId(path: string) {
  return `file:${normalizeFilePath(path)}`;
}

function directoryNodeId(path: string) {
  return `dir:${normalizeFilePath(path) || '__root__'}`;
}

function parentDirectory(path: string) {
  const parts = normalizeFilePath(path).split('/').filter(Boolean);
  parts.pop();
  return parts.join('/');
}

function joinTreePath(parentPath: string, name: string) {
  const cleanParent = normalizeFilePath(parentPath).replace(/\/$/, '');
  const cleanName = normalizeFilePath(name).replace(/^\/+/, '');
  return cleanParent ? `${cleanParent}/${cleanName}` : cleanName;
}

function isPathInsideDirectory(path: string, directoryPath: string) {
  const normalizedPath = normalizeFilePath(path);
  const normalizedDirectory = normalizeFilePath(directoryPath);
  return normalizedPath === normalizedDirectory || normalizedPath.startsWith(`${normalizedDirectory}/`);
}

function validateTreeName(name: string, kind: FileTreeNode['kind']) {
  const value = name.trim();
  if (!value) return `${kind === 'directory' ? 'Folder' : 'File'} name cannot be blank.`;
  if (value.includes('/') || value.includes('\\')) return 'Rename only the item name here, not the full path.';
  if (value === '.' || value === '..') return 'Name cannot be . or ...';
  if (!/^[A-Za-z0-9._-]+$/.test(value)) return 'Use letters, numbers, dots, dashes, and underscores only.';
  return null;
}

function uniqueDirectoryPath(files: WorkbenchFile[], directories: string[], preferredPath: string) {
  const normalized = normalizeFilePath(preferredPath).replace(/\/$/, '');
  const exists = (path: string) => (
    directories.some((directory) => samePath(directory, path))
    || files.some((file) => samePath(file.path, path) || isPathInsideDirectory(file.path, path))
  );
  if (!exists(normalized)) return normalized;

  let index = 2;
  let nextPath = `${normalized}-${index}`;
  while (exists(nextPath)) {
    index += 1;
    nextPath = `${normalized}-${index}`;
  }
  return nextPath;
}

function uniqueFilePath(files: WorkbenchFile[], preferredPath: string) {
  const normalized = normalizeFilePath(preferredPath);
  if (!files.some((file) => samePath(file.path, normalized))) return normalized;

  const extension = fileExtension(normalized);
  const withoutExtension = extension ? normalized.slice(0, -extension.length) : normalized;
  let index = 2;
  let nextPath = `${withoutExtension}-${index}${extension}`;
  while (files.some((file) => samePath(file.path, nextPath))) {
    index += 1;
    nextPath = `${withoutExtension}-${index}${extension}`;
  }
  return nextPath;
}

function buildFileTree(files: WorkbenchFile[], directories: string[]): FileTreeNode[] {
  const root: FileTreeNode[] = [];
  const directoryMap = new Map<string, FileTreeNode>();

  const ensureDirectory = (directoryPath: string) => {
    const normalized = normalizeFilePath(directoryPath).replace(/\/$/, '');
    if (!normalized) return null;
    const existing = directoryMap.get(normalized.toLowerCase());
    if (existing) return existing;

    const parentPath = parentDirectory(normalized);
    const name = normalized.split('/').pop() || normalized;
    const node: FileTreeNode = {
      id: directoryNodeId(normalized),
      name,
      path: normalized,
      kind: 'directory',
      children: [],
    };
    directoryMap.set(normalized.toLowerCase(), node);

    const parent = ensureDirectory(parentPath);
    (parent?.children || root).push(node);
    return node;
  };

  directories.forEach((directory) => ensureDirectory(directory));

  files.forEach((file) => {
    const path = normalizeFilePath(file.path);
    if (!path) return;
    const parentPath = parentDirectory(path);
    const parent = ensureDirectory(parentPath);
    const name = path.split('/').pop() || path;
    (parent?.children || root).push({
      id: fileNodeId(path),
      name,
      path,
      kind: 'file',
    });
  });

  const sortNodes = (nodes: FileTreeNode[]) => {
    nodes.sort((left, right) => {
      if (left.kind !== right.kind) return left.kind === 'directory' ? -1 : 1;
      return left.name.localeCompare(right.name);
    });
    nodes.forEach((node) => {
      if (node.children) sortNodes(node.children);
    });
  };

  sortNodes(root);
  return root;
}

function flattenFileTree(nodes: FileTreeNode[], map = new Map<string, FileTreeNode>()) {
  for (const node of nodes) {
    map.set(node.id, node);
    if (node.children) flattenFileTree(node.children, map);
  }
  return map;
}

function validateSingleFile(sourceCode: string, languageName: string | undefined, languageId: string | undefined): SingleFileValidation {
  const entryPath = langMeta(languageName).entry;
  const errors: string[] = [];

  if (!languageId) {
    errors.push('Select a runtime language.');
  }
  if (sourceCode.trim().length === 0) {
    errors.push(`${entryPath} needs code before it can run.`);
  }
  if (sourceCode.length > MAX_SINGLE_FILE_CHARS) {
    errors.push(`${entryPath}: keep single-file source under 256 KB or switch to multi-file.`);
  }

  return { entryPath, errors };
}

function validateMultiFile(files: WorkbenchFile[], languageName: string | undefined): MultiFileValidation {
  const entryPath = langMeta(languageName).entry;
  const allowedExtensions = allowedFileExtensions(languageName);
  const errors: string[] = [];
  const seen = new Map<string, number>();
  const entryFile = files.find((file) => samePath(file.path, entryPath));

  if (files.length === 0) {
    errors.push('Add at least one file.');
  }

  if (!entryFile) {
    errors.push(`${entryPath} is required and cannot be deleted or renamed.`);
  } else if (entryFile.content.trim().length === 0) {
    errors.push(`${entryPath} needs code before it can run.`);
  }

  for (const file of files) {
    const path = normalizeFilePath(file.path);
    const key = path.toLowerCase();
    seen.set(key, (seen.get(key) || 0) + 1);

    if (!path) {
      errors.push('File path cannot be blank.');
      continue;
    }
    if (file.path.includes('\\')) {
      errors.push(`${file.path}: use / instead of \\ in file paths.`);
    }
    if (path.startsWith('/') || path.includes('//')) {
      errors.push(`${file.path}: use a relative file path.`);
    }
    if (path.split('/').some((segment) => segment === '.' || segment === '..' || segment === '')) {
      errors.push(`${file.path}: path cannot contain empty, . or .. segments.`);
    }
    if (!/^[A-Za-z0-9._/-]+$/.test(path)) {
      errors.push(`${file.path}: use letters, numbers, dots, dashes, underscores, and / only.`);
    }

    const extension = fileExtension(path);
    if (!extension) {
      errors.push(`${file.path}: add a file extension.`);
    } else if (!allowedExtensions.includes(extension)) {
      errors.push(`${file.path}: .${extension.replace('.', '')} does not match the selected ${languageName || 'runtime'} function.`);
    }
  }

  for (const [path, count] of seen.entries()) {
    if (count > 1) {
      errors.push(`${path}: duplicate file path.`);
    }
  }

  return { allowedExtensions, entryPath, errors: [...new Set(errors)] };
}

function starterSource(languageName: string | undefined = 'python'): string {
  const name = languageName.toLowerCase();
  if (name.includes('javascript') || name.includes('node') || name.includes('typescript')) {
    return `const fs = require("fs");

// ${STARTER_SOURCE_MARKER} to your function through stdin.
// Write JSON to stdout so the next workflow state can consume it.
const rawInput = fs.readFileSync(0, "utf8");
const payload = rawInput.trim() ? JSON.parse(rawInput) : {};

const result = {
  received: payload,
  ok: true,
};

process.stdout.write(JSON.stringify(result));
`;
  }
  if (name.includes('java')) {
    return `import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

public class Main {
    public static void main(String[] args) throws Exception {
        // ${STARTER_SOURCE_MARKER} to your function through stdin.
        String input = new BufferedReader(new InputStreamReader(System.in))
                .lines()
                .collect(Collectors.joining("\\n"));
        String payload = input.isBlank() ? "{}" : input;

        // Write JSON to stdout so the next workflow state can consume it.
        System.out.println(payload);
    }
}
`;
  }
  if (name.includes('go') && !name.includes('cobol')) {
    return `package main

import (
    "fmt"
    "io"
    "os"
    "strings"
)

func main() {
    // ${STARTER_SOURCE_MARKER} to your function through stdin.
    input, _ := io.ReadAll(os.Stdin)
    payload := strings.TrimSpace(string(input))
    if payload == "" {
        payload = "{}"
    }

    // Write JSON to stdout so the next workflow state can consume it.
    fmt.Println(payload)
}
`;
  }
  if (name.includes('c++') || name.includes('cpp') || name.startsWith('c ') || name === 'c') {
    return `#include <iostream>
#include <sstream>
#include <string>

int main() {
    // ${STARTER_SOURCE_MARKER} to your function through stdin.
    std::ostringstream buffer;
    buffer << std::cin.rdbuf();
    std::string payload = buffer.str().empty() ? "{}" : buffer.str();

    // Write JSON to stdout so the next workflow state can consume it.
    std::cout << payload << std::endl;
    return 0;
}
`;
  }
  if (name.includes('c#') || name.includes('csharp')) {
    return `using System;

public class Program {
    public static void Main() {
        // ${STARTER_SOURCE_MARKER} to your function through stdin.
        var payload = Console.In.ReadToEnd();
        if (string.IsNullOrWhiteSpace(payload)) {
            payload = "{}";
        }

        // Write JSON to stdout so the next workflow state can consume it.
        Console.WriteLine(payload);
    }
}
`;
  }
  if (name.includes('ruby')) {
    return `require "json"

# ${STARTER_SOURCE_MARKER} to your function through stdin.
raw_input = STDIN.read
payload = raw_input.strip.empty? ? {} : JSON.parse(raw_input)

# Write JSON to stdout so the next workflow state can consume it.
puts JSON.generate({ received: payload, ok: true })
`;
  }
  if (name.includes('php')) {
    return `<?php
// ${STARTER_SOURCE_MARKER} to your function through stdin.
$rawInput = stream_get_contents(STDIN);
$payload = trim($rawInput) === '' ? [] : json_decode($rawInput, true);

// Write JSON to stdout so the next workflow state can consume it.
echo json_encode(["received" => $payload, "ok" => true]);
`;
  }
  if (name.includes('bash') || name.includes('shell')) {
    return `#!/usr/bin/env bash

# ${STARTER_SOURCE_MARKER} to your function through stdin.
payload="$(cat)"
if [ -z "$payload" ]; then
  payload="{}"
fi

# Write JSON to stdout so the next workflow state can consume it.
printf '%s\\n' "$payload"
`;
  }
  return `import json
import sys

# ${STARTER_SOURCE_MARKER} to your function through stdin.
# Write JSON to stdout so the next workflow state can consume it.
def main():
    raw_input = sys.stdin.read()
    payload = json.loads(raw_input) if raw_input.strip() else {}
    result = {
        "received": payload,
        "ok": True,
    }
    json.dump(result, sys.stdout)

if __name__ == "__main__":
    main()
`;
}

function isStarterSource(value: string) {
  return value.includes(STARTER_SOURCE_MARKER);
}

// Restrict the language dropdown to modern, popular languages rather than the
// full Judge0 catalogue (which includes many legacy/esoteric runtimes).
const MODERN_LANGUAGE_KEYWORDS = [
  'python', 'javascript', 'typescript', 'java', 'kotlin', 'c++', 'c#', 'go',
  'rust', 'ruby', 'php', 'swift', 'bash',
];

export function isModernLanguage(name: string): boolean {
  const value = name.toLowerCase();
  if (MODERN_LANGUAGE_KEYWORDS.some((keyword) => value.includes(keyword))) return true;
  return value.startsWith('c (') || value === 'c';
}

function crcTable() {
  const table: number[] = [];
  for (let i = 0; i < 256; i += 1) {
    let c = i;
    for (let j = 0; j < 8; j += 1) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[i] = c >>> 0;
  }
  return table;
}

const crcLookup = crcTable();

function crc32(bytes: Uint8Array) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc = crcLookup[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function write16(target: Uint8Array, offset: number, value: number) {
  target[offset] = value & 0xff;
  target[offset + 1] = (value >>> 8) & 0xff;
}

function write32(target: Uint8Array, offset: number, value: number) {
  target[offset] = value & 0xff;
  target[offset + 1] = (value >>> 8) & 0xff;
  target[offset + 2] = (value >>> 16) & 0xff;
  target[offset + 3] = (value >>> 24) & 0xff;
}

function dosParts(date = new Date()) {
  const time =
    (date.getHours() << 11)
    | (date.getMinutes() << 5)
    | Math.floor(date.getSeconds() / 2);
  const dosDate =
    ((date.getFullYear() - 1980) << 9)
    | ((date.getMonth() + 1) << 5)
    | date.getDate();
  return { time, dosDate };
}

function combine(parts: Uint8Array[]) {
  const length = parts.reduce((sum, part) => sum + part.length, 0);
  const output = new Uint8Array(length);
  let offset = 0;
  for (const part of parts) {
    output.set(part, offset);
    offset += part.length;
  }
  return output;
}

function bytesToBase64(bytes: Uint8Array) {
  let binary = '';
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary);
}

function filesToZipBase64(files: WorkbenchFile[]) {
  const encoder = new TextEncoder();
  const { time, dosDate } = dosParts();
  const localParts: Uint8Array[] = [];
  const centralParts: Uint8Array[] = [];
  let offset = 0;

  for (const file of files) {
    const nameBytes = encoder.encode(file.path.replace(/^\/+/, ''));
    const dataBytes = encoder.encode(file.content);
    const crc = crc32(dataBytes);

    const local = new Uint8Array(30 + nameBytes.length + dataBytes.length);
    write32(local, 0, 0x04034b50);
    write16(local, 4, 20);
    write16(local, 6, 0);
    write16(local, 8, 0);
    write16(local, 10, time);
    write16(local, 12, dosDate);
    write32(local, 14, crc);
    write32(local, 18, dataBytes.length);
    write32(local, 22, dataBytes.length);
    write16(local, 26, nameBytes.length);
    write16(local, 28, 0);
    local.set(nameBytes, 30);
    local.set(dataBytes, 30 + nameBytes.length);
    localParts.push(local);

    const central = new Uint8Array(46 + nameBytes.length);
    write32(central, 0, 0x02014b50);
    write16(central, 4, 20);
    write16(central, 6, 20);
    write16(central, 8, 0);
    write16(central, 10, 0);
    write16(central, 12, time);
    write16(central, 14, dosDate);
    write32(central, 16, crc);
    write32(central, 20, dataBytes.length);
    write32(central, 24, dataBytes.length);
    write16(central, 28, nameBytes.length);
    write16(central, 30, 0);
    write16(central, 32, 0);
    write16(central, 34, 0);
    write16(central, 36, 0);
    write32(central, 38, 0);
    write32(central, 42, offset);
    central.set(nameBytes, 46);
    centralParts.push(central);

    offset += local.length;
  }

  const central = combine(centralParts);
  const end = new Uint8Array(22);
  write32(end, 0, 0x06054b50);
  write16(end, 8, files.length);
  write16(end, 10, files.length);
  write32(end, 12, central.length);
  write32(end, 16, offset);
  write16(end, 20, 0);

  return bytesToBase64(combine([...localParts, central, end]));
}

export function FunctionVersionWorkbench({
  resourceLabel,
  languages,
  languageId: languageIdProp,
  onLanguageChange,
  description,
  onDescriptionChange,
  metadata,
  publishLabel = 'Publish version',
  saveDraftLabel = 'Save draft',
  onCancel,
  saveDraftDisabled,
  onSaveDraft,
  onPublish,
  publishDisabled,
}: Props) {
  const scrollContainerRef = useRef<HTMLDivElement | null>(null);
  const editorShellRef = useRef<HTMLDivElement | null>(null);
  const fileTreeRef = useRef<TreeApi<FileTreeNode> | undefined>(undefined);
  const fileTreeShellRef = useRef<HTMLDivElement | null>(null);
  const [workbenchLayoutModel] = useState(() => Model.fromJson(FUNCTION_WORKBENCH_LAYOUT));
  const [sourceMode, setSourceMode] = useState<FunctionSourceMode>('SINGLE_FILE');
  const [internalLanguageId, setInternalLanguageId] = useState('');
  const controlledLanguage = languageIdProp !== undefined;
  const languageId = controlledLanguage ? languageIdProp : internalLanguageId;
  const setLanguageId = useCallback((value: string) => {
    if (controlledLanguage) {
      onLanguageChange?.(value);
    } else {
      setInternalLanguageId(value);
    }
  }, [controlledLanguage, onLanguageChange]);
  const [sourceCode, setSourceCode] = useState(() => starterSource());
  const [files, setFiles] = useState<WorkbenchFile[]>(() => [{ path: 'main.py', content: starterSource() }]);
  const [manualDirectories, setManualDirectories] = useState<string[]>([]);
  const [activePath, setActivePath] = useState('main.py');
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [useDefaults, setUseDefaults] = useState(true);
  const [compilerOptions, setCompilerOptions] = useState('');
  const [commandLineArguments, setCommandLineArguments] = useState('');
  const [cpu, setCpu] = useState('');
  const [wall, setWall] = useState('');
  const [memory, setMemory] = useState('');
  const [fileSize, setFileSize] = useState('');
  const [outputBytes, setOutputBytes] = useState('');
  const [enableNetwork, setEnableNetwork] = useState(false);
  const [testCases, setTestCases] = useState<TestCase[]>([]);
  const [activeCaseId, setActiveCaseId] = useState('');
  const [runningCaseId, setRunningCaseId] = useState<string | null>(null);
  const [runResults, setRunResults] = useState<Record<string, FunctionRunResult>>({});
  const [publishing, setPublishing] = useState(false);
  const [drafting, setDrafting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editorWheelActive, setEditorWheelActive] = useState(false);
  const [splitEditorOpen, setSplitEditorOpen] = useState(false);
  const [splitPath, setSplitPath] = useState('');
  const [editorFullscreen, setEditorFullscreen] = useState(false);
  const [fileMenu, setFileMenu] = useState<FileContextMenu | null>(null);
  const [pendingEditNodeId, setPendingEditNodeId] = useState('');
  const [fileTreeHeight, setFileTreeHeight] = useState(320);

  const sortedLanguages = useMemo(() => {
    const modern = languages.filter((language) => isModernLanguage(language.name));
    const pool = modern.length > 0 ? modern : languages;
    return [...pool].sort((a, b) => a.name.localeCompare(b.name));
  }, [languages]);
  const selectedLanguage = sortedLanguages.find((language) => String(language.id) === languageId);
  const activeFile = files.find((file) => file.path === activePath) || files[0];
  const activeCase = testCases.find((testCase) => testCase.id === activeCaseId) || testCases[0];
  const fileTreeData = useMemo(() => buildFileTree(files, manualDirectories), [files, manualDirectories]);
  const fileTreeNodeById = useMemo(() => flattenFileTree(fileTreeData), [fileTreeData]);
  const singleFileValidation = useMemo(
    () => validateSingleFile(sourceCode, selectedLanguage?.name, languageId),
    [languageId, selectedLanguage?.name, sourceCode],
  );
  const multiFileValidation = useMemo(
    () => validateMultiFile(files, selectedLanguage?.name),
    [files, selectedLanguage?.name],
  );
  const activeValidationErrors = sourceMode === 'SINGLE_FILE' ? singleFileValidation.errors : multiFileValidation.errors;
  const hasSingleFileErrors = sourceMode === 'SINGLE_FILE' && singleFileValidation.errors.length > 0;
  const hasMultiFileErrors = sourceMode === 'MULTI_FILE' && multiFileValidation.errors.length > 0;
  const hasValidationErrors = hasSingleFileErrors || hasMultiFileErrors;
  const activeFileIsEntry = Boolean(activeFile && samePath(activeFile.path, multiFileValidation.entryPath));
  const splitFile = sourceMode === 'MULTI_FILE'
    ? (files.find((file) => file.path === splitPath) || files.find((file) => file.path !== activeFile?.path) || activeFile)
    : undefined;
  const fileMenuNode = fileMenu ? fileTreeNodeById.get(fileMenu.nodeId) : undefined;
  const canPublishVersion =
    !publishDisabled
    && Boolean(languageId)
    && !hasValidationErrors
    && (sourceMode === 'SINGLE_FILE'
      ? sourceCode.trim().length > 0
      : files.some((file) => file.path.trim() && file.content.trim()));
  const canSaveDraftVersion = Boolean(onSaveDraft) && !drafting && !saveDraftDisabled && Boolean(languageId) && !hasValidationErrors;

  useEffect(() => {
    if (languageId || sortedLanguages.length === 0) return;
    const python3 = sortedLanguages.find((language) => {
      const name = language.name.toLowerCase();
      return name.includes('python') && name.includes('3');
    });
    const python = sortedLanguages.find((language) => language.name.toLowerCase().includes('python'));
    setLanguageId(String((python3 || python || sortedLanguages[0]).id));
  }, [languageId, setLanguageId, sortedLanguages]);

  // Keep untouched starter code aligned with the selected language.
  useEffect(() => {
    if (!selectedLanguage) return;
    const entry = langMeta(selectedLanguage.name).entry;
    const nextSource = starterSource(selectedLanguage.name);
    setSourceCode((current) => (
      current.trim() === '' || isStarterSource(current) ? nextSource : current
    ));
    setFiles((current) => {
      if (
        current.length === 1
        && (current[0].content.trim() === '' || isStarterSource(current[0].content))
        && current[0].path !== entry
        && DEFAULT_ENTRY_PATTERN.test(current[0].path)
      ) {
        return [{ path: entry, content: nextSource }];
      }
      if (
        current.length === 1
        && (current[0].content.trim() === '' || isStarterSource(current[0].content))
      ) {
        return [{ ...current[0], content: nextSource }];
      }
      return current;
    });
  }, [selectedLanguage]);

  // Keep the active file valid after a rename/removal.
  useEffect(() => {
    if (files.length > 0 && !files.some((file) => file.path === activePath)) {
      setActivePath(files[0].path);
    }
  }, [files, activePath]);

  useEffect(() => {
    const element = fileTreeShellRef.current;
    if (!element) return undefined;
    const resizeObserver = new ResizeObserver(([entry]) => {
      setFileTreeHeight(Math.max(180, Math.floor(entry.contentRect.height)));
    });
    resizeObserver.observe(element);
    return () => resizeObserver.disconnect();
  }, [sourceMode, editorFullscreen]);

  useEffect(() => {
    if (!pendingEditNodeId || !fileTreeNodeById.has(pendingEditNodeId)) return;
    fileTreeRef.current?.openParents(pendingEditNodeId);
    fileTreeRef.current?.edit(pendingEditNodeId);
    setPendingEditNodeId('');
  }, [fileTreeNodeById, pendingEditNodeId]);

  useEffect(() => {
    if (sourceMode !== 'MULTI_FILE') {
      setSplitEditorOpen(false);
      setFileMenu(null);
      return;
    }
    if (splitPath && !files.some((file) => file.path === splitPath)) {
      setSplitPath(files.find((file) => file.path !== activePath)?.path || files[0]?.path || '');
    }
  }, [activePath, files, sourceMode, splitPath]);

  useEffect(() => {
    if (!fileMenu) return undefined;
    const closeMenu = () => setFileMenu(null);
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeMenu();
    };
    window.addEventListener('click', closeMenu);
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('click', closeMenu);
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [fileMenu]);

  const updateFileContent = (path: string, content: string) => {
    setFiles((current) => current.map((file) => (
      file.path === path ? { ...file, content } : file
    )));
  };

  const updateActiveFile = (content: string) => {
    if (!activeFile) return;
    updateFileContent(activeFile.path, content);
  };

  const addFile = (directory = '') => {
    const extension = langMeta(selectedLanguage?.name).ext;
    const safeDirectory = normalizeFilePath(directory).replace(/\/$/, '');
    const path = uniqueFilePath(files, joinTreePath(safeDirectory, `file-${files.length + 1}.${extension}`));
    setFiles((current) => [...current, { path, content: '' }]);
    setActivePath(path);
    setFileMenu(null);
    setPendingEditNodeId(fileNodeId(path));
    return path;
  };

  const addDirectory = (directory = '') => {
    const parentPath = normalizeFilePath(directory).replace(/\/$/, '');
    const path = uniqueDirectoryPath(files, manualDirectories, joinTreePath(parentPath, 'new-folder'));
    setManualDirectories((current) => [...current, path]);
    setFileMenu(null);
    setPendingEditNodeId(directoryNodeId(path));
    return path;
  };

  const renameFilePath = (fromPath: string, toPath: string) => {
    const nextPath = normalizeFilePath(toPath);
    if (samePath(fromPath, multiFileValidation.entryPath)) return false;
    if (!nextPath) {
      setError('File path cannot be blank.');
      return false;
    }
    if (files.some((file) => file.path !== fromPath && samePath(file.path, nextPath))) {
      setError(`${nextPath}: duplicate file path.`);
      return false;
    }

    setFiles((current) => current.map((file) => (
      file.path === fromPath ? { ...file, path: nextPath } : file
    )));
    if (activePath === fromPath) setActivePath(nextPath);
    if (splitPath === fromPath) setSplitPath(nextPath);
    setError(null);
    return true;
  };

  const renameDirectoryPath = (fromPath: string, toPath: string) => {
    const nextPath = normalizeFilePath(toPath).replace(/\/$/, '');
    if (!nextPath) {
      setError('Folder path cannot be blank.');
      return false;
    }
    if (nextPath.startsWith(`${normalizeFilePath(fromPath)}/`)) {
      setError('Folder cannot be moved inside itself.');
      return false;
    }
    if (files.some((file) => samePath(file.path, nextPath))) {
      setError(`${nextPath}: a file already uses that path.`);
      return false;
    }
    if (
      manualDirectories.some((directory) => !samePath(directory, fromPath) && samePath(directory, nextPath))
      || files.some((file) => !isPathInsideDirectory(file.path, fromPath) && isPathInsideDirectory(file.path, nextPath))
    ) {
      setError(`${nextPath}: folder already exists.`);
      return false;
    }

    const movePath = (path: string) => (
      isPathInsideDirectory(path, fromPath)
        ? `${nextPath}${normalizeFilePath(path).slice(normalizeFilePath(fromPath).length)}`
        : path
    );

    setFiles((current) => current.map((file) => ({ ...file, path: movePath(file.path) })));
    setManualDirectories((current) => [...new Set(current.map(movePath))]);
    if (isPathInsideDirectory(activePath, fromPath)) setActivePath(movePath(activePath));
    if (splitPath && isPathInsideDirectory(splitPath, fromPath)) setSplitPath(movePath(splitPath));
    setError(null);
    return true;
  };

  const startRenameNode = (nodeId: string) => {
    const node = fileTreeNodeById.get(nodeId);
    if (!node || (node.kind === 'file' && samePath(node.path, multiFileValidation.entryPath))) return;
    fileTreeRef.current?.edit(nodeId);
    setFileMenu(null);
  };

  const renameTreeNode: RenameHandler<FileTreeNode> = ({ name, node }) => {
    const validationError = validateTreeName(name, node.data.kind);
    if (validationError) {
      setError(validationError);
      return;
    }
    const nextPath = joinTreePath(parentDirectory(node.data.path), name.trim());
    if (node.data.kind === 'file') {
      renameFilePath(node.data.path, nextPath);
    } else {
      renameDirectoryPath(node.data.path, nextPath);
    }
  };

  const removeTreeNodes = (nodes: FileTreeNode[]) => {
    if (nodes.length === 0) return;
    const deletesEntry = nodes.some((node) => (
      node.kind === 'file'
        ? samePath(node.path, multiFileValidation.entryPath)
        : isPathInsideDirectory(multiFileValidation.entryPath, node.path)
    ));
    if (deletesEntry) {
      setError(`${multiFileValidation.entryPath} is required and cannot be deleted.`);
      setFileMenu(null);
      return;
    }

    const nextFiles = files.filter((file) => !nodes.some((node) => (
      node.kind === 'file' ? samePath(file.path, node.path) : isPathInsideDirectory(file.path, node.path)
    )));
    if (nextFiles.length === 0) {
      setError('Keep at least one file in the function.');
      setFileMenu(null);
      return;
    }

    const shouldDeleteDirectory = (directory: string) => nodes.some((node) => (
      node.kind === 'directory' && isPathInsideDirectory(directory, node.path)
    ));

    setFiles(nextFiles);
    setManualDirectories((current) => current.filter((directory) => !shouldDeleteDirectory(directory)));
    if (!nextFiles.some((file) => file.path === activePath)) setActivePath(nextFiles[0]?.path || '');
    if (splitPath && !nextFiles.some((file) => file.path === splitPath)) setSplitPath(nextFiles.find((file) => file.path !== activePath)?.path || nextFiles[0]?.path || '');
    setError(null);
    setFileMenu(null);
  };

  const removeActiveFile = () => {
    if (!activeFile || activeFileIsEntry) return;
    removeTreeNodes([{ id: fileNodeId(activeFile.path), name: activeFile.path.split('/').pop() || activeFile.path, path: activeFile.path, kind: 'file' }]);
  };

  const moveTreeNodes: MoveHandler<FileTreeNode> = ({ dragNodes, parentNode }) => {
    const parentPath = parentNode?.data.path || '';
    const draggedNodes = dragNodes.map((node) => node.data);
    if (draggedNodes.some((node) => node.kind === 'file' && samePath(node.path, multiFileValidation.entryPath))) {
      setError(`${multiFileValidation.entryPath} must stay at the function root.`);
      return;
    }
    if (draggedNodes.some((node) => node.kind === 'directory' && parentPath && isPathInsideDirectory(parentPath, node.path))) {
      setError('Folder cannot be moved inside itself.');
      return;
    }

    const replacements = new Map<string, string>();
    let nextFiles = files;
    let nextDirectories = manualDirectories;

    for (const dragged of draggedNodes) {
      const nextBasePath = dragged.kind === 'file'
        ? uniqueFilePath(nextFiles, joinTreePath(parentPath, dragged.name))
        : uniqueDirectoryPath(nextFiles, nextDirectories, joinTreePath(parentPath, dragged.name));
      replacements.set(dragged.path, nextBasePath);

      const movePath = (path: string) => (
        dragged.kind === 'file'
          ? (samePath(path, dragged.path) ? nextBasePath : path)
          : (isPathInsideDirectory(path, dragged.path) ? `${nextBasePath}${normalizeFilePath(path).slice(normalizeFilePath(dragged.path).length)}` : path)
      );

      nextFiles = nextFiles.map((file) => ({ ...file, path: movePath(file.path) }));
      nextDirectories = [...new Set(nextDirectories.map(movePath))];
      if (dragged.kind === 'directory' && !nextDirectories.some((directory) => samePath(directory, nextBasePath))) {
        nextDirectories = [...nextDirectories, nextBasePath];
      }
    }

    const remapOpenFile = (path: string) => {
      for (const [fromPath, toPath] of replacements.entries()) {
        if (samePath(path, fromPath)) return toPath;
        if (isPathInsideDirectory(path, fromPath)) {
          return `${toPath}${normalizeFilePath(path).slice(normalizeFilePath(fromPath).length)}`;
        }
      }
      return path;
    };

    setFiles(nextFiles);
    setManualDirectories(nextDirectories);
    setActivePath(remapOpenFile(activePath));
    if (splitPath) setSplitPath(remapOpenFile(splitPath));
    setError(null);
  };

  const openFileInSplit = (path: string) => {
    setSplitPath(path);
    setSplitEditorOpen(true);
    setFileMenu(null);
  };

  const toggleSplitEditor = () => {
    if (sourceMode !== 'MULTI_FILE') return;
    if (!splitEditorOpen && !splitPath) {
      setSplitPath(files.find((file) => file.path !== activeFile?.path)?.path || activeFile?.path || '');
    }
    setSplitEditorOpen((current) => !current);
  };

  const switchSourceMode = (nextMode: FunctionSourceMode) => {
    if (nextMode === sourceMode) return;
    if (nextMode === 'MULTI_FILE') {
      const entryPath = langMeta(selectedLanguage?.name).entry;
      setFiles((current) => {
        const hasEntry = current.some((file) => samePath(file.path, entryPath));
        if (hasEntry) {
          return current;
        }
        return [{ path: entryPath, content: sourceCode || starterSource(selectedLanguage?.name) }, ...current];
      });
      setActivePath(entryPath);
    } else {
      const entry = files.find((file) => samePath(file.path, multiFileValidation.entryPath));
      if (entry && (sourceCode.trim() === '' || isStarterSource(sourceCode))) {
        setSourceCode(entry.content);
      }
    }
    setSourceMode(nextMode);
  };

  const updateActiveCase = (patch: Partial<TestCase>) => {
    setTestCases((current) => current.map((testCase) => (
      testCase.id === activeCase.id ? { ...testCase, ...patch } : testCase
    )));
  };

  const addCase = () => {
    const id = `case-${Date.now()}`;
    const next = {
      id,
      name: `Case ${testCases.length + 1}`,
      input: '{\n  \n}',
      expectedOutput: '{\n  \n}',
      expectedError: '',
    };
    setTestCases((current) => [...current, next]);
    setActiveCaseId(id);
  };

  const buildRequest = (): FunctionVersionRequest => {
    const cleanedFiles = files
      .map((file) => ({ path: file.path.trim(), content: file.content }))
      .filter((file) => file.path.length > 0);

    if (sourceMode === 'MULTI_FILE' && cleanedFiles.length === 0) {
      throw new Error('Add at least one file for a multi-file function.');
    }
    if (sourceMode === 'SINGLE_FILE' && singleFileValidation.errors.length > 0) {
      throw new Error(singleFileValidation.errors[0]);
    }
    if (sourceMode === 'MULTI_FILE' && multiFileValidation.errors.length > 0) {
      throw new Error(multiFileValidation.errors[0]);
    }

    return {
      sourceMode,
      languageId: Number(languageId),
      sourceCode: sourceMode === 'SINGLE_FILE' ? sourceCode : undefined,
      additionalFilesBase64: sourceMode === 'MULTI_FILE' ? filesToZipBase64(cleanedFiles) : undefined,
      compilerOptions: useDefaults ? undefined : compilerOptions.trim() || undefined,
      commandLineArguments: useDefaults ? undefined : commandLineArguments.trim() || undefined,
      cpuTimeLimitSeconds: useDefaults ? undefined : toNumber(cpu),
      wallTimeLimitSeconds: useDefaults ? undefined : toNumber(wall),
      memoryLimitKb: useDefaults ? undefined : toNumber(memory),
      maxFileSizeKb: useDefaults ? undefined : toNumber(fileSize),
      maxOutputBytes: useDefaults ? undefined : toNumber(outputBytes),
      enableNetwork: useDefaults ? undefined : enableNetwork,
    };
  };

  const buildRunRequest = (input: unknown): FunctionRunRequest => {
    const cleanedFiles = files
      .map((file) => ({ path: file.path.trim(), content: file.content }))
      .filter((file) => file.path.length > 0);
    return {
      languageId: Number(languageId),
      sourceMode,
      sourceCode: sourceMode === 'SINGLE_FILE' ? sourceCode : undefined,
      additionalFilesBase64: sourceMode === 'MULTI_FILE' ? filesToZipBase64(cleanedFiles) : undefined,
      compilerOptions: useDefaults ? undefined : compilerOptions.trim() || undefined,
      commandLineArguments: useDefaults ? undefined : commandLineArguments.trim() || undefined,
      cpuTimeLimitSeconds: useDefaults ? undefined : toNumber(cpu),
      wallTimeLimitSeconds: useDefaults ? undefined : toNumber(wall),
      memoryLimitKb: useDefaults ? undefined : toNumber(memory),
      maxFileSizeKb: useDefaults ? undefined : toNumber(fileSize),
      maxOutputBytes: useDefaults ? undefined : toNumber(outputBytes),
      enableNetwork: useDefaults ? undefined : enableNetwork,
      input,
    };
  };

  const runCase = async (testCase: TestCase) => {
    if (runningCaseId) return;
    if (!languageId) { setError('Select a language before running.'); return; }
    if (activeValidationErrors.length > 0) {
      setError(activeValidationErrors[0]);
      return;
    }
    const hasSource = sourceMode === 'SINGLE_FILE'
      ? sourceCode.trim().length > 0
      : files.some((file) => file.path.trim() && file.content.trim());
    if (!hasSource) { setError('Add code before running.'); return; }
    let input: unknown = {};
    if (testCase.input.trim()) {
      try {
        input = JSON.parse(testCase.input);
      } catch {
        setError(`${testCase.name}: input is not valid JSON.`);
        return;
      }
    }
    setError(null);
    setActiveCaseId(testCase.id);
    setRunningCaseId(testCase.id);
    try {
      const result = await executeFunctionCode(buildRunRequest(input));
      setRunResults((current) => ({ ...current, [testCase.id]: result }));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setRunningCaseId(null);
    }
  };

  const runAll = async () => {
    for (const testCase of testCases) {
      await runCase(testCase);
    }
  };

  const deleteCase = (id: string) => {
    setTestCases((current) => current.filter((testCase) => testCase.id !== id));
    setRunResults((current) => {
      const next = { ...current };
      delete next[id];
      return next;
    });
    setActiveCaseId((current) => (current === id ? '' : current));
  };

  const caseStatus = (testCase: TestCase): 'pass' | 'fail' | 'none' => {
    const result = runResults[testCase.id];
    if (!result) return 'none';
    if (result.status !== 'SUCCEEDED') return 'fail';
    const expected = testCase.expectedOutput.trim();
    if (!expected) return 'pass';
    try {
      return JSON.stringify(JSON.parse(expected)) === JSON.stringify(result.output ?? null) ? 'pass' : 'fail';
    } catch {
      return (result.stdout || '').trim() === expected ? 'pass' : 'fail';
    }
  };

  const publish = async () => {
    if (publishing) return;
    if (!languageId) {
      setError('Select a language before publishing.');
      return;
    }
    if (activeValidationErrors.length > 0) {
      setError(activeValidationErrors[0]);
      return;
    }
    if (!canPublishVersion) return;
    setPublishing(true);
    setError(null);
    try {
      await onPublish(buildRequest());
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setPublishing(false);
    }
  };

  const saveDraft = async () => {
    if (!onSaveDraft || drafting || saveDraftDisabled) return;
    if (!languageId) {
      setError('Select a language before saving a draft.');
      return;
    }
    if (activeValidationErrors.length > 0) {
      setError(activeValidationErrors[0]);
      return;
    }
    setDrafting(true);
    setError(null);
    try {
      await onSaveDraft({ ...buildRequest(), status: 'DRAFT' });
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setDrafting(false);
    }
  };

  const copyResource = async () => {
    try {
      await navigator.clipboard.writeText(resourceLabel);
    } catch {
      setError('Could not copy resource URI.');
    }
  };

  const copyFilePath = async (path: string) => {
    try {
      await navigator.clipboard.writeText(path);
      setFileMenu(null);
    } catch {
      setError('Could not copy file path.');
    }
  };

  const showSplitEditor = sourceMode === 'MULTI_FILE' && splitEditorOpen && Boolean(splitFile);
  const filesPanelDisabled = sourceMode !== 'MULTI_FILE';
  const editorSectionClass = editorFullscreen
    ? 'function-workbench-fullscreen function-workbench-dock fixed inset-0 z-[120] h-screen min-h-0 overflow-hidden rounded-none border-0 bg-surface-container-lowest shadow-2xl shadow-primary/20'
    : 'function-workbench-dock relative mt-2 h-[680px] min-h-[520px] overflow-hidden rounded-lg border border-border-subtle bg-surface-container-lowest/55';

  const filesPanel = (
    <div className={`flex h-full min-h-0 flex-col bg-surface-container-lowest/70 ${filesPanelDisabled ? 'select-none' : ''}`} aria-disabled={filesPanelDisabled}>
      <div className="flex h-10 shrink-0 items-center justify-between border-b border-border-subtle px-3">
        <span className="font-mono-sm text-[11px] uppercase tracking-[0.07em] text-on-surface-variant">Files</span>
        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={() => addFile()}
            disabled={filesPanelDisabled}
            className="rounded-md p-1 text-on-surface-variant hover:bg-surface-container-low hover:text-primary disabled:pointer-events-none disabled:opacity-35"
            title={filesPanelDisabled ? 'Switch to multi-file mode to add files' : 'Add file'}
          >
            <Plus size={14} />
          </button>
          <button
            type="button"
            onClick={() => addDirectory()}
            disabled={filesPanelDisabled}
            className="rounded-md p-1 text-on-surface-variant hover:bg-surface-container-low hover:text-primary disabled:pointer-events-none disabled:opacity-35"
            title={filesPanelDisabled ? 'Switch to multi-file mode to create folders' : 'New directory'}
          >
            <FolderPlus size={14} />
          </button>
          <button
            type="button"
            onClick={removeActiveFile}
            disabled={filesPanelDisabled || files.length <= 1 || activeFileIsEntry}
            className="rounded-md p-1 text-on-surface-variant hover:bg-surface-container-low hover:text-status-error disabled:opacity-40"
            title={filesPanelDisabled ? 'Switch to multi-file mode to remove files' : activeFileIsEntry ? `${multiFileValidation.entryPath} is required` : 'Remove selected file'}
          >
            <Trash2 size={13} />
          </button>
        </div>
      </div>
      {sourceMode !== 'MULTI_FILE' ? (
        <div className="pointer-events-none flex flex-1 items-center justify-center p-4 text-center text-[12px] leading-5 text-on-surface-variant">
          Switch source mode to multi-file to manage folders and supporting files.
        </div>
      ) : (
        <>
          <div ref={fileTreeShellRef} className="min-h-0 flex-1 p-1.5">
            <Tree<FileTreeNode>
              ref={fileTreeRef}
              data={fileTreeData}
              idAccessor="id"
              childrenAccessor="children"
              width="100%"
              height={fileTreeHeight}
              rowHeight={30}
              indent={14}
              openByDefault
              disableMultiSelection
              selection={activeFile ? fileNodeId(activeFile.path) : undefined}
              disableEdit={(node) => node.kind === 'file' && samePath(node.path, multiFileValidation.entryPath)}
              disableDrag={(node) => node.kind === 'file' && samePath(node.path, multiFileValidation.entryPath)}
              disableDrop={({ parentNode, dragNodes }) => {
                if (parentNode && parentNode.data.kind !== 'directory') return true;
                return dragNodes.some((node) => (
                  node.data.kind === 'file' && samePath(node.data.path, multiFileValidation.entryPath)
                ));
              }}
              onActivate={(node) => {
                if (node.data.kind === 'file') {
                  setActivePath(node.data.path);
                } else {
                  node.toggle();
                }
              }}
              onRename={renameTreeNode}
              onMove={moveTreeNodes}
            >
              {({ node, style, dragHandle }: NodeRendererProps<FileTreeNode>) => {
                const isEntry = node.data.kind === 'file' && samePath(node.data.path, multiFileValidation.entryPath);
                const isActive = node.data.kind === 'file' && samePath(node.data.path, activeFile?.path || '');
                return (
                  <div
                    ref={dragHandle}
                    style={style}
                    onContextMenu={(event) => {
                      event.preventDefault();
                      if (node.data.kind === 'file') setActivePath(node.data.path);
                      setFileMenu({
                        nodeId: node.id,
                        path: node.data.path,
                        kind: node.data.kind,
                        x: event.clientX,
                        y: event.clientY,
                      });
                    }}
                    className={`group flex h-[30px] min-w-0 items-center gap-1.5 rounded-md px-2 text-[12px] transition-colors ${isActive ? 'bg-surface-container text-on-surface' : node.willReceiveDrop ? 'bg-primary/15 text-primary' : 'text-on-surface-variant hover:bg-surface-container-low hover:text-on-surface'}`}
                  >
                    <GripVertical size={12} className="shrink-0 cursor-grab text-on-surface-variant/55 opacity-0 transition-opacity group-hover:opacity-100" />
                    <button
                      type="button"
                      onClick={(event) => {
                        node.handleClick(event);
                        if (node.data.kind === 'file') {
                          setActivePath(node.data.path);
                        } else {
                          node.toggle();
                        }
                      }}
                      className="flex min-w-0 flex-1 items-center gap-2 text-left"
                    >
                      {node.data.kind === 'directory' ? (
                        <>
                          <ChevronDown size={12} className={`shrink-0 transition-transform ${node.isOpen ? '' : '-rotate-90'}`} />
                          <Folder size={14} className="shrink-0 text-secondary" />
                        </>
                      ) : (
                        <>
                          <span className="w-3 shrink-0" />
                          {node.data.path.endsWith('.json') ? <FileJson2 size={13} className="shrink-0 text-secondary" /> : <FileCode2 size={13} className="shrink-0 text-primary" />}
                        </>
                      )}
                      {node.isEditing ? (
                        <input
                          autoFocus
                          defaultValue={node.data.name}
                          onClick={(event) => event.stopPropagation()}
                          onBlur={(event) => node.submit(event.currentTarget.value)}
                          onKeyDown={(event) => {
                            if (event.key === 'Enter') node.submit(event.currentTarget.value);
                            if (event.key === 'Escape') node.reset();
                          }}
                          className="h-6 min-w-0 flex-1 rounded border border-primary/45 bg-surface-container-lowest px-2 font-mono-sm text-[11px] text-on-surface outline-none"
                        />
                      ) : (
                        <span className="min-w-0 flex-1 truncate">{node.data.name}</span>
                      )}
                    </button>
                    {isEntry && (
                      <span className="rounded border border-secondary/30 px-1 font-mono-sm text-[9px] uppercase tracking-[0.06em] text-secondary">main</span>
                    )}
                    <button
                      type="button"
                      onClick={(event) => {
                        event.stopPropagation();
                        setFileMenu({
                          nodeId: node.id,
                          path: node.data.path,
                          kind: node.data.kind,
                          x: event.clientX,
                          y: event.clientY,
                        });
                      }}
                      className="shrink-0 rounded p-1 text-on-surface-variant opacity-0 transition-opacity hover:bg-surface-container-low hover:text-on-surface group-hover:opacity-100"
                      title="File actions"
                    >
                      <MoreVertical size={12} />
                    </button>
                  </div>
                );
              }}
            </Tree>
          </div>
          <div className={`border-t border-border-subtle p-3 text-[11px] leading-5 ${multiFileValidation.errors.length > 0 ? 'text-status-error' : 'text-on-surface-variant'}`}>
            <div className="font-semibold text-on-surface">Multi-file rules</div>
            <div>Required entry: <span className="font-mono-sm text-secondary">{multiFileValidation.entryPath}</span></div>
            <div>Allowed: <span className="font-mono-sm">{multiFileValidation.allowedExtensions.join(', ')}</span></div>
            <div>Code files must match the selected runtime; data and config files are allowed.</div>
            {multiFileValidation.errors.length > 0 && (
              <ul className="mt-2 list-disc space-y-1 pl-4">
                {multiFileValidation.errors.map((validationError) => (
                  <li key={validationError}>{validationError}</li>
                ))}
              </ul>
            )}
          </div>
        </>
      )}
    </div>
  );

  const editorPanel = (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex h-10 shrink-0 items-center justify-between border-b border-border-subtle">
        <div className="flex h-full min-w-0 flex-1 items-center overflow-hidden">
          {sourceMode === 'SINGLE_FILE' ? (
            <div className="flex h-full items-center gap-2 border-r border-primary/40 border-t-2 border-t-primary px-4 text-[12px] text-on-surface">
              <FileCode2 size={14} className="text-primary" />
              {langMeta(selectedLanguage?.name).entry}
              <X size={13} className="text-primary" />
            </div>
          ) : (
            <div className="flex h-full min-w-0 flex-1 items-center overflow-x-auto overflow-y-hidden">
              {files.map((file) => (
                <button
                  key={file.path}
                  type="button"
                  onClick={() => setActivePath(file.path)}
                  className={`flex h-full min-w-[118px] max-w-[180px] shrink-0 items-center gap-2 border-r px-3 text-[12px] transition-colors ${file.path === activeFile?.path
                      ? 'border-primary/40 border-t-2 border-t-primary text-on-surface'
                      : 'border-border-subtle text-on-surface-variant hover:text-on-surface'
                    }`}
                  title={file.path}
                >
                  {file.path.endsWith('.json') ? <Braces size={13} className="shrink-0 text-secondary" /> : <FileCode2 size={13} className="shrink-0 text-primary" />}
                  <span className="min-w-0 truncate">{file.path.split('/').pop()}</span>
                </button>
              ))}
            </div>
          )}
        </div>
        <div className="flex h-full shrink-0 items-center gap-1 border-l border-border-subtle px-2">
          {sourceMode === 'MULTI_FILE' && (
            <button
              type="button"
              onClick={toggleSplitEditor}
              className={`rounded-md p-1.5 transition-colors ${splitEditorOpen ? 'bg-primary/15 text-primary' : 'text-on-surface-variant hover:bg-surface-container-low hover:text-on-surface'}`}
              title={splitEditorOpen ? 'Close split editor' : 'Open split editor'}
            >
              <PanelRight size={14} />
            </button>
          )}
          <button
            type="button"
            onClick={() => setEditorFullscreen((current) => !current)}
            className="rounded-md p-1.5 text-on-surface-variant transition-colors hover:bg-surface-container-low hover:text-on-surface"
            title={editorFullscreen ? 'Exit fullscreen' : 'Fullscreen editor'}
          >
            {editorFullscreen ? <Minimize2 size={14} /> : <Maximize2 size={14} />}
          </button>
        </div>
      </div>

      {sourceMode === 'MULTI_FILE' && activeFile && (
        <div className="flex items-center gap-2 border-b border-border-subtle bg-surface-container-lowest/70 px-3 py-2">
          <input
            value={activeFile.path}
            onChange={(event) => {
              if (activeFileIsEntry) return;
              const nextPath = event.target.value;
              setFiles((current) => current.map((file) => (
                file.path === activeFile.path ? { ...file, path: nextPath } : file
              )));
              setActivePath(nextPath);
            }}
            readOnly={activeFileIsEntry}
            title={activeFileIsEntry ? `${multiFileValidation.entryPath} is required and cannot be renamed` : 'File path'}
            className={`h-7 w-full max-w-[360px] rounded-md border border-border-subtle bg-surface-container-low px-2 font-mono-sm text-[11px] text-on-surface outline-none focus:border-primary/55 ${activeFileIsEntry ? 'cursor-not-allowed text-on-surface-variant' : ''}`}
          />
          {activeFileIsEntry && (
            <span className="font-mono-sm text-[10px] text-secondary">required entry</span>
          )}
          {hasMultiFileErrors && (
            <span className="min-w-0 truncate text-[11px] text-status-error">{multiFileValidation.errors[0]}</span>
          )}
        </div>
      )}
      {sourceMode === 'SINGLE_FILE' && singleFileValidation.errors.length > 0 && (
        <div className="flex items-center gap-2 border-b border-status-error/30 bg-status-error/10 px-3 py-2 text-[11px] text-status-error">
          <AlertTriangle size={13} className="shrink-0" />
          <span>{singleFileValidation.errors[0]}</span>
        </div>
      )}

      <div
        ref={editorShellRef}
        onMouseDownCapture={() => setEditorWheelActive(true)}
        onWheelCapture={(event) => {
          if (editorWheelActive) return;
          const scrollContainer = scrollContainerRef.current;
          if (!scrollContainer) return;
          event.preventDefault();
          event.stopPropagation();
          scrollContainer.scrollBy({ left: event.deltaX, top: event.deltaY });
        }}
        className="relative min-h-0 flex-1"
      >
        {sourceMode === 'SINGLE_FILE' ? (
          <Editor
            height="100%"
            theme="vs-dark"
            language={languageToMonaco(selectedLanguage?.name)}
            value={sourceCode}
            onChange={(value) => setSourceCode(value || '')}
            options={{
              minimap: { enabled: true },
              scrollBeyondLastLine: false,
              fontSize: 12,
              lineHeight: 20,
              fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
              wordWrap: 'on',
              tabSize: 2,
              lineNumbersMinChars: 3,
              padding: { top: 14, bottom: 14 },
            }}
          />
        ) : (
          <div className={`grid h-full min-h-0 ${showSplitEditor ? 'lg:grid-cols-2' : 'grid-cols-1'}`}>
            <div className={`flex min-h-0 min-w-0 flex-col ${showSplitEditor ? 'border-r border-border-subtle' : ''}`}>
              {showSplitEditor && (
                <div className="flex h-8 shrink-0 items-center gap-2 border-b border-border-subtle bg-surface-container-lowest/75 px-3 font-mono-sm text-[11px] text-on-surface-variant">
                  <span className="truncate">{activeFile?.path || 'No file'}</span>
                </div>
              )}
              <div className="min-h-0 flex-1">
                <Editor
                  height="100%"
                  theme="vs-dark"
                  language={fileLanguage(activeFile?.path || '', selectedLanguage?.name)}
                  value={activeFile?.content || ''}
                  onChange={(value) => updateActiveFile(value || '')}
                  options={{
                    minimap: { enabled: true },
                    scrollBeyondLastLine: false,
                    fontSize: 12,
                    lineHeight: 20,
                    fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
                    wordWrap: 'on',
                    tabSize: 2,
                    lineNumbersMinChars: 3,
                    padding: { top: 14, bottom: 14 },
                  }}
                />
              </div>
            </div>
            {showSplitEditor && splitFile && (
              <div className="flex min-h-0 min-w-0 flex-col">
                <div className="flex h-8 shrink-0 items-center gap-2 border-b border-border-subtle bg-surface-container-lowest/75 px-3">
                  <select
                    value={splitFile.path}
                    onChange={(event) => setSplitPath(event.target.value)}
                    className="h-6 min-w-0 flex-1 rounded-md border border-border-subtle bg-surface-container-low px-2 font-mono-sm text-[11px] text-on-surface outline-none focus:border-primary/55"
                  >
                    {files.map((file) => (
                      <option key={file.path} value={file.path}>{file.path}</option>
                    ))}
                  </select>
                  <button
                    type="button"
                    onClick={() => setSplitEditorOpen(false)}
                    className="rounded-md p-1 text-on-surface-variant hover:bg-surface-container-low hover:text-on-surface"
                    title="Close split"
                  >
                    <X size={13} />
                  </button>
                </div>
                <div className="min-h-0 flex-1">
                  <Editor
                    height="100%"
                    theme="vs-dark"
                    language={fileLanguage(splitFile.path, selectedLanguage?.name)}
                    value={splitFile.content}
                    onChange={(value) => updateFileContent(splitFile.path, value || '')}
                    options={{
                      minimap: { enabled: true },
                      scrollBeyondLastLine: false,
                      fontSize: 12,
                      lineHeight: 20,
                      fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
                      wordWrap: 'on',
                      tabSize: 2,
                      lineNumbersMinChars: 3,
                      padding: { top: 14, bottom: 14 },
                    }}
                  />
                </div>
              </div>
            )}
          </div>
        )}
      </div>
      <div className="flex h-8 shrink-0 items-center justify-between gap-5 border-t border-border-subtle px-3 font-mono-sm text-[11px] text-on-surface-variant">
        <span className={activeValidationErrors.length > 0 ? 'text-status-error' : 'text-on-surface-variant'}>
          {activeValidationErrors.length > 0 ? activeValidationErrors[0] : sourceMode === 'SINGLE_FILE' ? `Single file: ${singleFileValidation.entryPath}` : `${files.length} file${files.length === 1 ? '' : 's'}`}
        </span>
        <span className="flex items-center gap-5">
          <span>{selectedLanguage?.name || 'No language'}</span>
          <span>UTF-8</span>
          <span>LF</span>
          <span className="h-2 w-2 rounded-full bg-secondary" />
        </span>
      </div>
    </div>
  );

  const casesPanel = (
    <div className="flex h-full min-h-0 flex-col p-3">
      <div className="mb-3 space-y-2">
        <div className="flex items-center justify-between gap-2">
          <span className="font-mono-sm text-[11px] uppercase tracking-[0.07em] text-on-surface-variant">Test cases</span>
          <span className="rounded-md border border-border-subtle px-1.5 py-0.5 font-mono-sm text-[10px] text-on-surface-variant">{testCases.length}</span>
        </div>
        <div className={`grid gap-2 ${testCases.length > 0 ? 'grid-cols-2' : 'grid-cols-1'}`}>
          {testCases.length > 0 && (
            <button type="button" onClick={runAll} disabled={runningCaseId !== null} className="flex h-9 min-w-0 items-center justify-center gap-1.5 rounded-lg border border-primary/45 px-3 text-[11px] text-primary hover:bg-primary/10 disabled:opacity-50" title="Run all cases">
              <Play size={12} />
              <span className="truncate">Run all</span>
            </button>
          )}
          <button type="button" onClick={addCase} className="flex h-9 min-w-0 items-center justify-center gap-1.5 rounded-lg border border-border-subtle px-3 text-[11px] text-on-surface-variant hover:text-on-surface">
            <Plus size={13} />
            <span className="truncate">Add case</span>
          </button>
        </div>
      </div>
      {testCases.length === 0 ? (
        <div className="flex flex-1 items-center justify-center rounded-lg border border-dashed border-border-subtle px-3 py-6 text-center text-[11px] leading-5 text-on-surface-variant">
          No test cases. Add one to run your code against sample input.
        </div>
      ) : (
        <div className="min-h-0 flex-1 space-y-2 overflow-y-auto pr-1">
          {testCases.map((testCase) => {
            const active = testCase.id === activeCase?.id;
            const status = caseStatus(testCase);
            const running = runningCaseId === testCase.id;
            return (
              <div
                key={testCase.id}
                className={`group flex h-10 w-full items-center gap-2 rounded-lg border px-3 text-[12px] transition-colors ${active ? 'border-primary/55 bg-primary/10 text-on-surface' : 'border-border-subtle text-on-surface-variant hover:text-on-surface'}`}
              >
                {running ? (
                  <Loader2 size={12} className="shrink-0 animate-spin text-primary" />
                ) : (
                  <span className={`h-2 w-2 shrink-0 rounded-full ${status === 'pass' ? 'bg-status-success' : status === 'fail' ? 'bg-status-error' : 'bg-on-surface-variant/45'}`} />
                )}
                <button type="button" onClick={() => setActiveCaseId(testCase.id)} className="min-w-0 flex-1 truncate text-left">
                  {testCase.name}
                </button>
                <button type="button" onClick={() => deleteCase(testCase.id)} className="shrink-0 text-on-surface-variant opacity-0 transition-opacity hover:text-status-error group-hover:opacity-100" title="Delete case">
                  <Trash2 size={13} />
                </button>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );

  const inputPanel = !activeCase ? (
    <div className="flex h-full items-center justify-center p-6 text-center text-[12px] text-on-surface-variant">
      Add a test case to run your code against JSON input.
    </div>
  ) : (
    <div className="flex h-full min-h-0 flex-col p-3">
      <div className="mb-2 flex items-center justify-between gap-2">
        <input
          value={activeCase.name}
          onChange={(event) => updateActiveCase({ name: event.target.value })}
          className="h-7 min-w-0 flex-1 rounded-md border border-transparent bg-transparent px-1 text-[12px] font-semibold text-on-surface outline-none focus:border-border-subtle focus:bg-surface-container-lowest"
        />
        <span className="flex shrink-0 items-center gap-1 text-[11px] text-on-surface-variant" title="Judge0 pipes this JSON to your program's standard input (stdin).">
          JSON stdin
          <Info size={12} />
        </span>
      </div>
      <textarea
        value={activeCase.input}
        onChange={(event) => updateActiveCase({ input: event.target.value })}
        spellCheck={false}
        placeholder={'{\n  "amount": 100\n}'}
        className="min-h-[92px] flex-1 resize-none rounded-lg border border-border-subtle bg-surface-container-lowest p-3 font-mono-sm text-[12px] leading-relaxed text-on-surface outline-none placeholder:text-on-surface-variant/40 focus:border-primary/50"
      />
      <button
        type="button"
        onClick={() => runCase(activeCase)}
        disabled={runningCaseId !== null || !languageId}
        className="mt-2 flex h-8 w-full items-center justify-center gap-2 rounded-lg border border-primary bg-primary text-[12px] font-semibold text-on-primary transition-colors hover:bg-primary-fixed-dim disabled:cursor-not-allowed disabled:opacity-50"
      >
        {runningCaseId === activeCase.id ? <Loader2 size={13} className="animate-spin" /> : <Play size={13} />}
        Run case
      </button>
    </div>
  );

  const outputPanel = !activeCase ? (
    <div className="flex h-full items-center justify-center p-6 text-center text-[12px] text-on-surface-variant">
      Add a test case to define expected output and view results.
    </div>
  ) : (
    <div className="h-full min-h-0 space-y-2 overflow-y-auto p-3">
      <label className="block text-[12px] text-on-surface">
        Expected output (stdout)
        <textarea
          value={activeCase.expectedOutput}
          onChange={(event) => updateActiveCase({ expectedOutput: event.target.value })}
          spellCheck={false}
          placeholder="Leave blank to just check it runs"
          className="mt-1 h-[72px] w-full resize-none rounded-lg border border-border-subtle bg-surface-container-lowest p-3 font-mono-sm text-[12px] leading-relaxed text-secondary outline-none placeholder:text-on-surface-variant/40 focus:border-primary/50"
        />
      </label>
      {(() => {
        const result = runResults[activeCase.id];
        if (!result) {
          return <div className="rounded-lg border border-dashed border-border-subtle px-3 py-3 text-center text-[11px] text-on-surface-variant">Run the case to see actual output.</div>;
        }
        const status = caseStatus(activeCase);
        return (
          <div className="space-y-2 rounded-lg border border-border-subtle bg-surface-base p-2.5">
            <div className="flex items-center gap-2">
              <span className={`rounded-full border px-2 py-0.5 font-mono-sm text-[10px] uppercase tracking-[0.06em] ${status === 'pass' ? 'border-status-success/40 bg-status-success/10 text-status-success' : 'border-status-error/40 bg-status-error/10 text-status-error'}`}>
                {status === 'pass' ? 'Pass' : 'Fail'}
              </span>
              <span className="font-mono-sm text-[10px] text-on-surface-variant">{result.status}</span>
              {result.timeSeconds != null && <span className="font-mono-sm text-[10px] text-on-surface-variant">{result.timeSeconds}s</span>}
            </div>
            {result.errorName && <ResultBlock label={result.errorName} body={result.errorMessage || ''} tone="error" />}
            {result.output != null && <ResultBlock label="Actual output" body={JSON.stringify(result.output, null, 2)} />}
            {result.stdout && result.output == null && <ResultBlock label="stdout" body={result.stdout} />}
            {result.stderr && <ResultBlock label="stderr" body={result.stderr} tone="error" />}
            {result.compileOutput && <ResultBlock label="compile output" body={result.compileOutput} tone="error" />}
          </div>
        );
      })()}
    </div>
  );

  const dockFactory = (node: TabNode) => {
    switch (node.getComponent()) {
      case 'files':
        return filesPanel;
      case 'editor':
        return editorPanel;
      case 'cases':
        return casesPanel;
      case 'input':
        return inputPanel;
      case 'output':
        return outputPanel;
      default:
        return null;
    }
  };

  return (
    <div className="flex h-full min-h-0 w-full flex-1 flex-col bg-[radial-gradient(ellipse_at_50%_0%,rgba(242,121,90,0.08),transparent_35%),linear-gradient(180deg,rgba(9,17,29,0.98),rgba(5,11,19,0.98))]">
      <div className="flex h-14 shrink-0 items-center justify-between gap-3 border-b border-border-subtle px-4">
        <div className="flex min-w-0 flex-1 items-center gap-3">
          <button
            type="button"
            onClick={onCancel}
            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-border-subtle bg-surface-container-lowest text-on-surface-variant transition-colors hover:border-primary/45 hover:text-on-surface"
            title="Back to functions"
            aria-label="Back to functions"
          >
            <ArrowLeft size={15} />
          </button>
          <button
            type="button"
            onClick={copyResource}
            className="flex h-8 max-w-[420px] min-w-0 items-center gap-2 rounded-lg border border-border-subtle bg-surface-container-lowest px-3 font-mono-sm text-[11px] text-on-surface-variant transition-colors hover:border-primary/45 hover:text-on-surface"
            title="Copy function resource"
            aria-label="Copy function resource"
          >
            <span className="min-w-0 truncate">{resourceLabel}</span>
            <Copy size={13} className="shrink-0 text-primary" />
          </button>
        </div>
        <div className="flex min-w-0 shrink-0 items-center gap-2">
          <button type="button" onClick={onCancel} className="h-8 rounded-lg border border-border-subtle px-3 text-[12px] text-on-surface transition-colors hover:bg-surface-container-low">
            Cancel
          </button>
          {onSaveDraft && (
            <button
              type="button"
              onClick={saveDraft}
              disabled={!canSaveDraftVersion}
              className="flex h-8 items-center gap-1.5 rounded-lg border border-border-subtle px-3 text-[12px] text-on-surface transition-colors hover:border-primary/45 disabled:opacity-50"
            >
              {drafting ? <Loader2 size={13} className="animate-spin" /> : <Save size={13} />}
              {saveDraftLabel}
            </button>
          )}
          <button
            type="button"
            disabled
            className="hidden h-8 items-center gap-1.5 rounded-lg border border-border-subtle px-3 text-[12px] text-on-surface-variant opacity-60 lg:flex"
            title="Publish the version before running tests"
          >
            <Play size={13} />
            Run test
          </button>
          <button
            type="button"
            onClick={publish}
            disabled={!canPublishVersion || publishing}
            className="flex h-8 items-center gap-1.5 rounded-lg border border-primary bg-primary px-3 text-[12px] font-semibold text-on-primary shadow-[0_12px_32px_rgba(242,121,90,0.22)] transition-colors hover:bg-primary-fixed-dim disabled:cursor-not-allowed disabled:opacity-50"
          >
            {publishing ? <Loader2 size={13} className="animate-spin" /> : <Play size={13} />}
            {publishLabel}
            <ChevronDown size={13} />
          </button>
        </div>
      </div>

      <div
        ref={scrollContainerRef}
        onMouseDownCapture={(event) => {
          if (!editorShellRef.current?.contains(event.target as Node)) {
            setEditorWheelActive(false);
          }
        }}
        className="min-h-0 flex-1 overflow-y-auto p-3"
      >
        <section className="rounded-lg border border-border-subtle bg-surface-container-lowest/60 p-3 shadow-[inset_0_0_0_1px_rgba(255,255,255,0.018)]">
          {metadata}
          <div className={`mt-3 grid gap-3 ${onDescriptionChange
              ? 'lg:grid-cols-[minmax(0,1fr)_178px_176px]'
              : controlledLanguage
                ? 'lg:grid-cols-[178px_176px]'
                : 'lg:grid-cols-[196px_178px_176px]'
            }`}>
            {!controlledLanguage && (
              <div>
                <span className={labelClass}>Language</span>
                <select value={languageId} onChange={(event) => setLanguageId(event.target.value)} className={selectControlClass}>
                  <option value="">Select language</option>
                  {sortedLanguages.map((language) => (
                    <option key={language.id} value={language.id}>{language.name}</option>
                  ))}
                </select>
              </div>
            )}
            {onDescriptionChange && (
              <div>
                <span className={labelClass}>Description</span>
                <input
                  value={description || ''}
                  onChange={(event) => onDescriptionChange(event.target.value)}
                  placeholder="What this function does"
                  className={controlClass}
                />
              </div>
            )}
            <div>
              <span className={labelClass}>Source mode</span>
              <select value={sourceMode} onChange={(event) => switchSourceMode(event.target.value as FunctionSourceMode)} className={selectControlClass}>
                <option value="SINGLE_FILE">Single file</option>
                <option value="MULTI_FILE">Multi-file</option>
              </select>
            </div>
            <div className="flex items-end">
              <button
                type="button"
                onClick={() => setSettingsOpen(true)}
                className="flex h-9 w-full items-center justify-center gap-2 rounded-lg border border-border-subtle bg-surface-container-lowest px-3 text-[12px] text-on-surface transition-colors hover:border-primary/45 hover:bg-surface-container-low"
              >
                <SlidersHorizontal size={14} />
                Execution settings
                <ChevronDown size={13} />
              </button>
            </div>
          </div>
          <div className="mt-3 flex items-start gap-2 rounded-lg border border-secondary/25 bg-secondary/10 px-3 py-2 text-[11px] leading-5 text-on-surface-variant">
            <Info size={14} className="mt-0.5 shrink-0 text-secondary" />
            <span>
              <span className="font-semibold text-on-surface">Note:</span> Workflows send input to your function through <span className="font-mono-sm text-secondary">stdin</span>. Write JSON to <span className="font-mono-sm text-secondary">stdout</span> so the next state receives it.
            </span>
          </div>
        </section>

        <section className={editorSectionClass}>
          <DockLayout model={workbenchLayoutModel} factory={dockFactory} realtimeResize />
        </section>
        {error && (
          <div className="mt-2 flex items-start gap-2 rounded-lg border border-status-error/35 bg-status-error/10 px-3 py-2 text-[12px] text-status-error">
            <AlertTriangle size={15} className="mt-0.5 shrink-0" />
            <span>{error}</span>
          </div>
        )}
      </div>

      {fileMenu && fileMenuNode && (
        <div
          className="fixed z-[140] w-44 overflow-hidden rounded-lg border border-border-subtle bg-surface-container-lowest py-1 text-[12px] text-on-surface shadow-2xl shadow-black/40"
          style={{ left: fileMenu.x, top: fileMenu.y }}
          onClick={(event) => event.stopPropagation()}
        >
          {fileMenuNode.kind === 'file' && (
            <button
              type="button"
              onClick={() => openFileInSplit(fileMenuNode.path)}
              className="flex h-8 w-full items-center gap-2 px-3 text-left hover:bg-surface-container-low"
            >
              <PanelRight size={13} className="text-primary" />
              Open in split
            </button>
          )}
          <button
            type="button"
            onClick={() => startRenameNode(fileMenuNode.id)}
            disabled={fileMenuNode.kind === 'file' && samePath(fileMenuNode.path, multiFileValidation.entryPath)}
            className="flex h-8 w-full items-center gap-2 px-3 text-left hover:bg-surface-container-low disabled:cursor-not-allowed disabled:opacity-45"
          >
            <Pencil size={13} className="text-secondary" />
            Rename
          </button>
          <button
            type="button"
            onClick={() => copyFilePath(fileMenuNode.path)}
            className="flex h-8 w-full items-center gap-2 px-3 text-left hover:bg-surface-container-low"
          >
            <Copy size={13} className="text-on-surface-variant" />
            Copy path
          </button>
          <div className="my-1 border-t border-border-subtle" />
          <button
            type="button"
            onClick={() => addFile(fileMenuNode.kind === 'directory' ? fileMenuNode.path : parentDirectory(fileMenuNode.path))}
            className="flex h-8 w-full items-center gap-2 px-3 text-left hover:bg-surface-container-low"
          >
            <Plus size={13} className="text-primary" />
            New file here
          </button>
          <button
            type="button"
            onClick={() => addDirectory(fileMenuNode.kind === 'directory' ? fileMenuNode.path : parentDirectory(fileMenuNode.path))}
            className="flex h-8 w-full items-center gap-2 px-3 text-left hover:bg-surface-container-low"
          >
            <FolderPlus size={13} className="text-primary" />
            New directory
          </button>
          <div className="my-1 border-t border-border-subtle" />
          <button
            type="button"
            onClick={() => removeTreeNodes([fileMenuNode])}
            disabled={files.length <= 1 || (fileMenuNode.kind === 'file' ? samePath(fileMenuNode.path, multiFileValidation.entryPath) : isPathInsideDirectory(multiFileValidation.entryPath, fileMenuNode.path))}
            className="flex h-8 w-full items-center gap-2 px-3 text-left text-status-error hover:bg-status-error/10 disabled:cursor-not-allowed disabled:opacity-45"
          >
            <Trash2 size={13} />
            Delete
          </button>
        </div>
      )}

      {settingsOpen && (
        <ExecutionSettingsModal
          useDefaults={useDefaults}
          setUseDefaults={setUseDefaults}
          compilerOptions={compilerOptions}
          setCompilerOptions={setCompilerOptions}
          commandLineArguments={commandLineArguments}
          setCommandLineArguments={setCommandLineArguments}
          cpu={cpu}
          setCpu={setCpu}
          wall={wall}
          setWall={setWall}
          memory={memory}
          setMemory={setMemory}
          fileSize={fileSize}
          setFileSize={setFileSize}
          outputBytes={outputBytes}
          setOutputBytes={setOutputBytes}
          enableNetwork={enableNetwork}
          setEnableNetwork={setEnableNetwork}
          onClose={() => setSettingsOpen(false)}
        />
      )}
    </div>
  );
}

function ResultBlock({ label, body, tone }: { label: string; body: string; tone?: 'error' }) {
  return (
    <div>
      <div className={`mb-1 font-mono-sm text-[10px] uppercase tracking-[0.06em] ${tone === 'error' ? 'text-status-error' : 'text-on-surface-variant'}`}>{label}</div>
      <pre className="max-h-40 overflow-auto rounded-md border border-border-subtle bg-surface-lowest p-2 font-mono-sm text-[11px] leading-relaxed text-on-surface">{body}</pre>
    </div>
  );
}

function ExecutionSettingsModal({
  useDefaults,
  setUseDefaults,
  compilerOptions,
  setCompilerOptions,
  commandLineArguments,
  setCommandLineArguments,
  cpu,
  setCpu,
  wall,
  setWall,
  memory,
  setMemory,
  fileSize,
  setFileSize,
  outputBytes,
  setOutputBytes,
  enableNetwork,
  setEnableNetwork,
  onClose,
}: {
  useDefaults: boolean;
  setUseDefaults: (value: boolean) => void;
  compilerOptions: string;
  setCompilerOptions: (value: string) => void;
  commandLineArguments: string;
  setCommandLineArguments: (value: string) => void;
  cpu: string;
  setCpu: (value: string) => void;
  wall: string;
  setWall: (value: string) => void;
  memory: string;
  setMemory: (value: string) => void;
  fileSize: string;
  setFileSize: (value: string) => void;
  outputBytes: string;
  setOutputBytes: (value: string) => void;
  enableNetwork: boolean;
  setEnableNetwork: (value: boolean) => void;
  onClose: () => void;
}) {

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/58 px-4 backdrop-blur-sm">
      <div className="w-full max-w-[560px] rounded-xl border border-border-subtle bg-[linear-gradient(145deg,rgba(14,25,39,0.98),rgba(6,12,22,0.98))] p-4 shadow-[0_28px_90px_rgba(0,0,0,0.55)]">
        <div className="mb-4 flex items-start justify-between gap-3">
          <div>
            <div className="flex items-center gap-2 text-[15px] font-semibold text-on-surface">
              <SlidersHorizontal size={16} />
              Execution settings
            </div>
            <p className="mt-1 text-[12px] text-on-surface-variant">Override workspace defaults for this function version.</p>
          </div>
          <button type="button" onClick={onClose} className="rounded-md p-1 text-on-surface-variant hover:bg-surface-container-low hover:text-on-surface" aria-label="Close settings">
            <X size={17} />
          </button>
        </div>

        <div className="space-y-3">
          <label className="flex items-center justify-between rounded-lg border border-border-subtle bg-surface-container-lowest p-3">
            <span>
              <span className="block text-[12px] font-semibold text-on-surface">Use workspace defaults</span>
              <span className="mt-0.5 block text-[11px] text-on-surface-variant">When enabled, settings below are ignored.</span>
            </span>
            <input
              type="checkbox"
              checked={useDefaults}
              onChange={(event) => setUseDefaults(event.target.checked)}
              className="h-5 w-10 rounded-full border-border-subtle bg-surface-container text-primary focus:ring-primary"
            />
          </label>

          <div className="rounded-lg border border-border-subtle bg-surface-container-lowest p-3">
            <div className="mb-3 flex items-center gap-2 text-[12px] font-semibold text-on-surface">
              <Braces size={14} />
              Resource limits
            </div>
            <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
              <SettingInput label="CPU time (s)" value={cpu} onChange={setCpu} disabled={useDefaults} />
              <SettingInput label="Wall time (s)" value={wall} onChange={setWall} disabled={useDefaults} />
              <SettingInput label="Memory (KB)" value={memory} onChange={setMemory} disabled={useDefaults} />
              <SettingInput label="Max file size (KB)" value={fileSize} onChange={setFileSize} disabled={useDefaults} />
              <SettingInput label="Max output (bytes)" value={outputBytes} onChange={setOutputBytes} disabled={useDefaults} />
            </div>
          </div>

          <div className="grid gap-3">
            <SettingText label="Compiler options" value={compilerOptions} onChange={setCompilerOptions} placeholder="-O2 -pipe" disabled={useDefaults} />
            <SettingText label="Command-line arguments" value={commandLineArguments} onChange={setCommandLineArguments} placeholder="--fast-mode" disabled={useDefaults} />
          </div>

          <label className="flex items-center justify-between rounded-lg border border-border-subtle bg-surface-container-lowest p-3">
            <span className="flex items-center gap-2">
              <Network size={14} className="text-on-surface-variant" />
              <span>
                <span className="block text-[12px] font-semibold text-on-surface">Network access</span>
                <span className="mt-0.5 block text-[11px] text-on-surface-variant">Allow outbound network connections during execution.</span>
              </span>
            </span>
            <input
              type="checkbox"
              checked={enableNetwork}
              disabled={useDefaults}
              onChange={(event) => setEnableNetwork(event.target.checked)}
              className="h-5 w-5 rounded border-border-subtle bg-surface-container text-primary focus:ring-primary disabled:opacity-50"
            />
          </label>

          <details className="rounded-lg border border-border-subtle bg-surface-container-lowest p-3">
            <summary className="flex items-center gap-2 text-[12px] font-semibold text-on-surface">
              <Info size={14} />
              About these settings
            </summary>
            <p className="mt-2 text-[11px] leading-5 text-on-surface-variant">
              These values are sent to Judge0 when the version is published. Blank values use the backend defaults.
            </p>
          </details>

        </div>

        <div className="mt-5 flex justify-end gap-2">
          <button type="button" onClick={onClose} className="h-9 rounded-lg border border-border-subtle px-4 text-[12px] text-on-surface transition-colors hover:bg-surface-container-low">
            Cancel
          </button>
          <button type="button" onClick={onClose} className="h-9 rounded-lg border border-primary bg-primary px-4 text-[12px] font-semibold text-on-primary transition-colors hover:bg-primary-fixed-dim">
            Save settings
          </button>
        </div>
      </div>
    </div>
  );
}

function SettingInput({ label, value, onChange, disabled }: { label: string; value: string; onChange: (value: string) => void; disabled: boolean }) {
  return (
    <label className="block">
      <span className="mb-1 block text-[11px] text-on-surface-variant">{label}</span>
      <input
        value={value}
        onChange={(event) => onChange(event.target.value)}
        disabled={disabled}
        inputMode="decimal"
        className="h-8 w-full rounded-md border border-border-subtle bg-surface-container-low px-2 font-mono-sm text-[11px] text-on-surface outline-none focus:border-primary/50 disabled:opacity-45"
      />
    </label>
  );
}

function SettingText({
  label,
  value,
  onChange,
  placeholder,
  disabled,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  disabled: boolean;
}) {
  return (
    <label className="grid gap-1 rounded-lg border border-border-subtle bg-surface-container-lowest p-3 md:grid-cols-[220px_minmax(0,1fr)] md:items-center">
      <span>
        <span className="block text-[12px] font-semibold text-on-surface">{label}</span>
        <span className="mt-0.5 block text-[11px] text-on-surface-variant">Passed to Judge0 when applicable.</span>
      </span>
      <input
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        disabled={disabled}
        className="h-9 rounded-DEFAULT border border-primary/30 bg-surface-container px-3 font-mono-sm text-[11px] text-on-surface outline-none placeholder:text-on-surface-variant/45 focus:border-primary/60 disabled:opacity-45"
      />
    </label>
  );
}
