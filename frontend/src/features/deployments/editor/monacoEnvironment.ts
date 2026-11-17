import { loader } from '@monaco-editor/react'
import * as monaco from 'monaco-editor'
import EditorWorker from 'monaco-editor/editor/editor.worker?worker'
import YamlWorker from './yaml.worker.ts?worker'

// Bundle Monaco from node_modules: no CDN loader, workers come from Vite.
// One shared setup: two editors that each assigned MonacoEnvironment let the last-loaded module win,
// which handed the YAML label a plain editor worker.
self.MonacoEnvironment = {
  getWorker: (_id, label) => (label === 'yaml' ? new YamlWorker() : new EditorWorker()),
}
loader.config({ monaco })

/**
 * `monaco` as monaco-yaml needs it. monaco-yaml -> monaco-worker-manager@2.0.1 calls
 * `monaco.editor.createWebWorker({ createData, label, moduleId })`, the API before monaco-editor 0.57.
 * 0.57 wants `{ worker }` and a started worker: Monaco's own languages first post "ignore", then the
 * create data (see `monaco-editor/esm/vs/internal/common/workers.js`). This adapter does the same with the
 * YAML worker. Remove it once monaco-worker-manager supports monaco-editor 0.57 (check each Renovate bump).
 */
export const monacoForYaml: typeof monaco = {
  ...monaco,
  editor: {
    ...monaco.editor,
    createWebWorker: ((opts: { createData?: unknown }) => {
      const worker = new YamlWorker()
      worker.postMessage('ignore')
      worker.postMessage(opts.createData)
      return monaco.editor.createWebWorker({ worker })
    }) as typeof monaco.editor.createWebWorker,
  },
}

export { monaco }
