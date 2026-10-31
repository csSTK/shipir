import { loader } from '@monaco-editor/react'
import * as monaco from 'monaco-editor'
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'
import YamlWorker from './yaml.worker.ts?worker'

// Bundle Monaco from node_modules: no CDN loader, workers come from Vite.
// One shared setup: two editors that each assigned MonacoEnvironment let the last-loaded module win,
// which handed the YAML label a plain editor worker.
self.MonacoEnvironment = {
  getWorker: (_id, label) => (label === 'yaml' ? new YamlWorker() : new EditorWorker()),
}
loader.config({ monaco })

export { monaco }
