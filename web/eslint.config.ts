import pluginVitest from '@vitest/eslint-plugin'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import skipFormatting from 'eslint-config-prettier/flat'
import { globalIgnores } from 'eslint/config'
import pluginPlaywright from 'eslint-plugin-playwright'
import pluginVue from 'eslint-plugin-vue'
export default defineConfigWithVueTs(
  { name: 'app/files-to-lint', files: ['**/*.{vue,ts,mts,tsx}'] },
  globalIgnores([
    '**/coverage/**',
    '**/dist/**',
    '**/node_modules/**',
    '**/playwright-report/**',
    '**/src/generated/**',
    '**/test-results/**',
  ]),
  ...pluginVue.configs['flat/essential'],
  vueTsConfigs.recommended,
  { ...pluginPlaywright.configs['flat/recommended'], files: ['e2e/**/*.{test,spec}.{ts,tsx}'] },
  {
    ...pluginVitest.configs.recommended,
    files: ['src/**/*.{test,spec}.{ts,tsx}', 'tests/**/*.ts'],
  },
  skipFormatting,
)
