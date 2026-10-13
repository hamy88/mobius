import assert from 'node:assert/strict'
import { build } from 'esbuild'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const bundled = await build({
  entryPoints: [path.resolve(here, '../src/components/viewer/entry-extract.ts')],
  bundle: true,
  format: 'esm',
  target: 'node18',
  write: false,
  logLevel: 'silent',
})
const moduleUrl = `data:text/javascript;base64,${Buffer.from(bundled.outputFiles[0].text).toString('base64')}`
const { entryDisplayFiles } = await import(moduleUrl)

const bashEntry = (command) => ({
  type: 'assistant',
  message: { content: [{ type: 'tool_use', name: 'Bash', input: { command } }] },
})

assert.deepEqual(entryDisplayFiles(bashEntry('display-files /tmp/a.txt /tmp/main.py')), [
  { path: '/tmp/a.txt' },
  { path: '/tmp/main.py' },
])
assert.deepEqual(entryDisplayFiles(bashEntry('display-files --remote dev-1 --root /srv/app "src/a file.py"')), [
  { path: 'src/a file.py', remote: 'dev-1', root: '/srv/app' },
])
assert.deepEqual(entryDisplayFiles(bashEntry("bash -c 'display-files --remote=dev-2 --root=/workspace README.md' && display-files README.md")), [
  { path: 'README.md', remote: 'dev-2', root: '/workspace' },
  { path: 'README.md' },
])
assert.deepEqual(entryDisplayFiles({
  type: 'response_item',
  payload: { type: 'function_call', name: 'exec_command', arguments: JSON.stringify({ cmd: 'display-files /work/index.html' }) },
}), [{ path: '/work/index.html' }])
assert.deepEqual(entryDisplayFiles(bashEntry('echo display-files /tmp/ignored.py')), [])
console.log('display-files parser tests passed')
