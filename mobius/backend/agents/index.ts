/**
 * agents/index.ts — backend factory + module-level singleton registry.
 *
 * Usage:
 *   const agents = require('../agents')
 *   const backend = agents.get('tmux-claude-code')
 *   await backend.createNewSession({...})
 *
 * Registered backends:
 *   - 'tmux-claude-code'   TUI + tmux paste-buffer + jsonl file tail
 *   - 'tmux-codex'         Codex TUI + tmux paste-buffer + $CODEX_HOME rollout jsonl tail
 *   - 'deepseek-harness'   harness process, fed by its own event stream
 */
const { TmuxClaudeCodeBackend } = require('./tmux-claude-code')
const { TmuxCodexBackend } = require('./tmux-codex')
const { DeepSeekHarnessBackend } = require('./deepseek-harness')

const singletons: Record<string, any> = {}

// One instance per backend name, created lazily on first use.
function get(name: string): any {
  if (!singletons[name]) {
    switch (name) {
      case 'tmux-claude-code': singletons[name] = new TmuxClaudeCodeBackend(); break
      case 'tmux-codex':       singletons[name] = new TmuxCodexBackend(); break
      case 'deepseek-harness':  singletons[name] = new DeepSeekHarnessBackend(); break
      default: throw new Error(`unknown agent backend: ${name}`)
    }
  }
  return singletons[name]
}

// Serves both CJS consumers (`import agents from '../agents'` in services/routes) and
// named imports: default points at the same registry object.
export { get }
export default { get }
