/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { configDefaults } from 'vitest/config'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: [
      {
        // monaco-worker-manager@2.0.1 (via monaco-yaml@5.5.1, both latest) imports the pre-0.57 path.
        // monaco-editor 0.57 maps `./*.js` to `esm/vs/*.js`, so that path no longer resolves.
        // Remove once monaco-worker-manager/monaco-yaml import `monaco-editor/editor/editor.worker.js`.
        find: /^monaco-editor\/esm\/vs\/editor\/editor\.worker\.js$/,
        replacement: 'monaco-editor/editor/editor.worker.js',
      },
    ],
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    globals: false,
    // Parallel runs on a CPU-loaded machine blow the 5s default (shipr-k31n.44/.56); a real hang still fails.
    testTimeout: 15_000,
    exclude: [...configDefaults.exclude, 'e2e/**', 'e2e-csp/**'],
  },
})
