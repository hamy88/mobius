#!/bin/sh
# monitor.sh - poll one agent session's status in a loop, one round every 3s.
#
# Usage:
#   bash mobius/backend/agents/monitor.sh --session=<sessionId> --type=<claude|codex|deepseek>
#   bash mobius/backend/agents/monitor.sh --session=b89eb46e --type=claude
# Each round prints:
#   isAlive / isWorking / getRecentError / getHistory / getSessionTitle / realTimeInfo
#
# Notes: requires the AgentBackend singleton directly, sharing the server's persisted
# mapping files. Read-only: never creates or terminates a session. Ctrl-C to exit.
# Loads the repo-root .env / .env.default first (existing env vars win); without them
# config.js falls back to the container DB_PATH=/data, which fails with EACCES.
set -eu

SESSION_ID=""
AGENT_TYPE=""

for arg in "$@"; do
  case "$arg" in
    --session=*) SESSION_ID="${arg#--session=}" ;;
    --type=*)    AGENT_TYPE="${arg#--type=}" ;;
    --help|-h)
      sed -n '2,12p' "$0"; exit 0 ;;
    *)
      echo "未知参数: $arg" >&2; echo "用法: $0 --session=<sessionId> --type=<claude|codex|deepseek>" >&2; exit 1 ;;
  esac
done

if [ -z "$SESSION_ID" ] || [ -z "$AGENT_TYPE" ]; then
  echo "用法: $0 --session=<sessionId> --type=<claude|codex|deepseek>" >&2
  exit 1
fi

# Run from agents/ so the relative requires resolve
cd "$(dirname "$0")"

# Backend services are .ts, loaded through the same tsx hook the server uses
TSX="$PWD/../../node_modules/.bin/tsx"
if [ ! -x "$TSX" ]; then
  echo "找不到 tsx: $TSX (需要在 mobius/ 下 npm install)" >&2
  exit 1
fi

export MONITOR_SESSION_ID="$SESSION_ID"
export MONITOR_AGENT_TYPE="$AGENT_TYPE"

exec node --require "$PWD/../../node_modules/tsx/dist/cjs/index.cjs" - <<'EOF'
// Load the repo-root .env then .env.default, in the same order start_product.py uses
// (existing env vars win). This must happen before require('./index'): config.js reads
// process.env while the module is loading.
;(() => {
  const fs = require('fs')
  const path = require('path')
  for (const name of ['.env', '.env.default']) {
    const file = path.resolve(process.cwd(), '../../..', name)
    if (!fs.existsSync(file)) continue
    for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
      const m = line.match(/^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/)
      if (!m) continue
      let value = m[2].trim()
      if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
        value = value.slice(1, -1)
      }
      if (!(m[1] in process.env)) process.env[m[1]] = value
    }
  }
})()

const { get } = require('./index')

const sessionId = process.env.MONITOR_SESSION_ID
const agentType = process.env.MONITOR_AGENT_TYPE

const TYPE_TO_BACKEND = {
  claude: 'tmux-claude-code',
  codex: 'tmux-codex',
  deepseek: 'deepseek-harness',
}
const backendName = TYPE_TO_BACKEND[agentType]
if (!backendName) {
  console.error(`未知 --type: ${agentType} (可选 claude / codex / deepseek)`)
  process.exit(1)
}

const backend = get(backendName)
console.log(`[monitor] backend=${backendName} session=${sessionId} (Ctrl-C 退出)`)

// Squash a value into one readable line; truncate long ones rather than flooding the terminal.
function fmt(value, maxLen = 300) {
  let text
  if (value === null || value === undefined) text = String(value)
  else if (typeof value === 'string') text = value
  else text = JSON.stringify(value)
  if (text === undefined) text = String(value) // JSON.stringify(undefined)
  text = text.replace(/\s+/g, ' ').trim()
  if (!text) text = '(空)'
  return text.length > maxLen ? text.slice(0, maxLen) + ` …(共${text.length}字符)` : text
}

// A history can hold hundreds of entries, so print a summary plus the last entry.
function fmtHistory(hist) {
  if (!hist || typeof hist !== 'object') return fmt(hist)
  const entries = Array.isArray(hist.entries) ? hist.entries : []
  const last = entries[entries.length - 1]
  const lastPreview = last
    ? `末条[type=${last.type ?? '?'} ts=${last.timestamp ?? last.ts ?? '?'}] ${fmt(last, 160)}`
    : '(无条目)'
  return `entries=${entries.length} sentinel=${fmt(hist.sentinel, 80)} | ${lastPreview}`
}

// Run a probe, turning a thrown error into readable text.
function safe(fn, fallback = '(调用失败)') {
  try { return fn() } catch (e) { return `(调用失败: ${e?.message || e})` }
}

let round = 0
function tick() {
  round++
  const now = new Date().toISOString().replace('T', ' ').slice(0, 19)
  console.log(`\n===== #${round} ${now} =====`)
  console.log(`isAlive        : ${fmt(safe(() => backend.isAlive(sessionId)))}`)
  console.log(`isWorking      : ${fmt(safe(() => backend.isWorking(sessionId)))}`)
  console.log(`getRecentError : ${fmt(safe(() => backend.getRecentError(sessionId)))}`)
  console.log(`getHistory     : ${fmt(safe(() => fmtHistory(backend.getHistory(sessionId))))}`)
  console.log(`getSessionTitle: ${fmt(safe(() => backend.getSessionTitle(sessionId)))}`)
  console.log(`realTimeInfo   : ${fmt(safe(() => backend.realTimeInfo(sessionId)))}`)
}

tick()
setInterval(tick, 3000)
EOF
