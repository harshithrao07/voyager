import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
    rules: {
      // Workflow definitions and provider payloads are deliberately schema-less at the
      // UI boundary. Keeping `any` available here avoids pretending those values have a
      // narrower contract than the backend or an external provider actually guarantees.
      '@typescript-eslint/no-explicit-any': 'off',
      // Route hydration, editor resets, and server-backed selection changes intentionally
      // synchronize local UI state from external state in effects.
      'react-hooks/set-state-in-effect': 'off',
      'react-refresh/only-export-components': ['error', {
        allowConstantExport: true,
        allowExportNames: [
          'ALL_TIMEZONES',
          'TIMEZONE_GROUPS',
          'fileLanguage',
          'languageToMonaco',
          'timezoneLabel',
        ],
      }],
    },
  },
])
