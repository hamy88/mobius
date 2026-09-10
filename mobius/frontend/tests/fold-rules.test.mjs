/** Default-fold rules for entries whose content is not readable in the UI. */
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const result = await build({
  entryPoints: [path.resolve(__dirname, '../src/components/viewer/fold-rules.ts')],
  bundle: true,
  format: 'esm',
  target: 'node18',
  write: false,
  logLevel: 'silent',
})
const dataUrl = 'data:text/javascript;base64,' + Buffer.from(result.outputFiles[0].text).toString('base64')
const foldRules = await import(dataUrl)

const encryptedReasoning = {
  type: 'response_item',
  payload: {
    type: 'reasoning',
    encrypted_content: 'gAAAAA-encrypted-reasoning',
  },
}
const readableReasoning = {
  type: 'response_item',
  payload: {
    type: 'reasoning',
    summary: ['readable reasoning'],
  },
}

const collapsed = foldRules.computeCollapsedByEncryptedReasoning([
  { entry: encryptedReasoning, lineNo: 12 },
  { entry: readableReasoning, lineNo: 13 },
  { entry: { type: 'assistant', message: { content: [{ type: 'text', text: 'answer' }] } }, lineNo: 14 },
])

assert.deepEqual([...collapsed], [12])
console.log('fold-rules: encrypted reasoning default-collapse test passed')
