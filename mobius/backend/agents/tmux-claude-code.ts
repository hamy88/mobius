/**
 * tmux-claude-code.ts — TmuxClaudeCodeBackend.
 *
 * One tmux window per MOBIUS session_id in the fixed `imac_claude_code_agent_hub` session of a
 * dedicated tmux server, window name = session_id. Each window runs `proxychains -q -f
 * ~/proxychains_config_for_llm_models.conf claude --dangerously-skip-permissions ...` (interactive TUI).
 *
 * Write:     tmux load-buffer + paste-buffer -p (bracketed) + send-keys Enter×3
 * Read:      tail of ~/.claude/projects/<cwd-enc>/<uuid>.jsonl
 * Interrupt: tmux send-keys C-c × 3 (the TUI swallows the 1st; 3 measured reliable)
 * Terminate: tmux kill-window
 *
 * Cross-process restart: runtime persists to MOBIUS_DATA_PATH/hub-runtime.json. On backend reload the
 * tmux windows are still alive, so we just reload the (sessionId → agentSessionId, jsonlPath) map and
 * never kill a running claude.
 *
 * Implements the 5 AgentBackend methods plus isAlive / isWorking / listSessions / getHistory /
 * isJobGoalAccomplished.
 *
 * Task running flag: every prompt submission drops <cwd>/.imac/flags/<sessionId>/running.flag, which
 * the agent removes on completion (success or failure — see the context injected by session-context.js).
 * isJobGoalAccomplished reads that file's presence.
 */
const { spawnSync } = require('child_process')
const path = require('path')
const fs = require('fs')
const os = require('os')
const crypto = require('crypto')

import { AgentBackend } from './base'
import type { HistorySnapshot, QueryOpts } from './base'
const {
  getHistorySnapshot,
  writeMobiusCoreEntry,
  flushPendingOpeners,
} = require('../services/mobius-agent-history')
const { watch: watchJsonlFile } = require('../services/jsonl-watcher')
const {
  timeConsumeWaterfallFromBackend,
  clearTimeConsumeWaterfallForBackend,
} = require('../services/time-consume-waterfall')
const { recordPromptPaste } = require('../services/agent-prompt-events')
const {
  runningFlagPathOf,
  failedFlagPathOf,
  safeWriteRunningFlag,
  safeRemoveRunningFlag,
  safeRemoveFlagDir,
} = require('../utils/session-flags')
const { MOBIUS_DATA_PATH } = require('../config')
const { AGENT_TMUX_SOCKET, log, tmux } = require('./tmux-operation-log')
const { take_tmux_window_text } = require('./tmux_utils')
const { ensureSessionWithProxy } = require('../services/model-access')

// ── Constants ───────────────────────────────────────────
const HUB = 'imac_claude_code_agent_hub'
const HOME = os.homedir()
// Env-var proxy config (formerly proxy_envs.bash): prefer the new name, fall back to the old file.
const PROXY_ENVS_FILE = path.join(HOME, 'proxy_envs.conf')
const PROXY_ENVS_FILE_LEGACY = path.join(HOME, 'proxy_envs.bash')
/*
 * Resolve which proxy env-var file counts as configured: the new
 * proxy_envs.conf when present, otherwise the legacy proxy_envs.bash.
 *
 * Only the prereq check asks this; the spawn command tries both names
 * itself, so a host set up before the rename keeps working with no
 * migration.
 */
function resolveProxyEnvsFile() {
  return fs.existsSync(PROXY_ENVS_FILE) ? PROXY_ENVS_FILE : PROXY_ENVS_FILE_LEGACY
}
// Model proxychains config (formerly proxy_claude.conf); the legacy file is reused while it exists.
const PROXY_CONF = path.join(HOME, 'proxychains_config_for_llm_models.conf')
const RUNTIME_FILE = path.join(MOBIUS_DATA_PATH, 'hub-runtime.json')
// archive: one row per session ever started (sessionId → jsonlPath/agentSessionId/cwd...), kept past
// terminate, so getHistory still resolves a jsonl after an admin closes the window or a cleaner reaps it.
const ARCHIVE_FILE = path.join(MOBIUS_DATA_PATH, 'hub-archive.json')

// claude TUI ready poll: watch for the footer "bypass permissions on" (present after the splash).
const READY_POLL_MS = 250
const READY_TIMEOUT_MS = 25000
const READY_SENTINEL = 'bypass permissions on'

// First entry into a new directory raises claude's "trust this folder" dialog
// (--dangerously-skip-permissions does not skip it). The cwd is a platform-created workspace, so the
// default "Yes, I trust this folder" is fine; claude persists the trust for that directory.
const TRUST_PROMPT_SENTINELS = [
  'trust this folder',
  'Is this a project you created or one you trust',
  'Do you trust the files',
]
const TRUST_PRESS_INTERVAL_MS = 1500

// One-time first-run onboarding dialogs (text style, welcome screen) block TUI ready: auto-confirm with Enter.
const ONBOARDING_PROMPT_SENTINELS = [
  'Choose the text style',
  'Let\'s get started',
  'Welcome to Claude Code',
]
const ONBOARDING_PRESS_INTERVAL_MS = 1500

// "Detected a custom API key in your environment" dialog: claude sees the ANTHROPIC_API_KEY env var
// and asks whether to use it. Press "1" for Yes (use that key) to dismiss the dialog.
const API_KEY_PROMPT_SENTINELS = [
  'Detected a custom API key in your environment',
  'Do you want to use this API key',
]
const API_KEY_PRESS_INTERVAL_MS = 1500

// "WARNING: Claude Code running in Bypass Permissions mode" dialog: newer claude shows this one-time
// confirmation under --dangerously-skip-permissions. The default is "1. No, exit", so it takes
// "2" + Enter to accept and reach the normal TUI.
const BYPASS_WARN_SENTINELS = [
  'WARNING: Claude Code running in Bypass Permissions mode',
  'Yes, I accept',
]
const BYPASS_WARN_INTERVAL_MS = 1500

// User-level claude config; projects[absPath] holds the directory trust flag.
const CLAUDE_CONFIG = path.join(HOME, '.claude.json')

/*
 * Pre-mark the project directory as trusted in this service user's
 * ~/.claude.json, so claude's first-run "Do you trust the files in this
 * folder?" dialog never appears in the TUI. Done this way because no
 * official CLI sets that flag (`claude project` only purges) and
 * --dangerously-skip-permissions does not skip the trust dialog either;
 * -p/--bare is incompatible with the interactive TUI.
 *
 * Idempotent: an entry already marked trusted is left untouched, and the
 * key is only ADDed for an entry that is missing or untrusted — an
 * existing entry belongs to the claude processes running there, so
 * overwriting it could drop their state. tmp file + atomic rename keeps
 * the shared config readable at all times.
 *
 * Returns false on any failure and never throws: startup must not be
 * blocked by this, because the ready poll's screenshot auto-confirm with
 * Enter is the fallback (belt and braces).
 */
function ensureProjectTrusted(cwd: string) {
  try {
    // 信任按绝对路径记账，先归一化再查表
    // Trust is keyed by absolute path, normalize before looking it up
    const abs = path.resolve(cwd)
    // 配置文件还没生成，交给截屏兜底，不凭空造一个
    // No config yet, leave it to the screenshot fallback, do not invent one
    if (!fs.existsSync(CLAUDE_CONFIG)) return false
    const j = JSON.parse(fs.readFileSync(CLAUDE_CONFIG, 'utf8'))
    if (!j.projects || typeof j.projects !== 'object') j.projects = {}
    const cur = j.projects[abs]
    // 已经信任过就直接返回，不动别人的状态
    // Already trusted, return without touching anyone else's state
    if (cur && cur.hasTrustDialogAccepted === true) return true
    // 保留原字段只置信任位，整条覆盖会丢状态
    // Keep existing keys, set only the trust bit; a full overwrite loses state
    j.projects[abs] = { ...(cur || {}), hasTrustDialogAccepted: true }
    // 先写临时文件再原子改名，配置不会被读到一半
    // Write tmp then rename atomically, the config is never read half-written
    const tmp = `${CLAUDE_CONFIG}.imac-tmp-${process.pid}-${Date.now()}`
    fs.writeFileSync(tmp, JSON.stringify(j, null, 2))
    fs.renameSync(tmp, CLAUDE_CONFIG)
    log(`[tmux-claude-code] 预置目录信任: ${abs} → ~/.claude.json`)
    return true
  } catch (e) {
    // 失败只告警不抛出，启动照常走截屏自动确认
    // Warn instead of throwing, startup still has the screenshot auto-confirm
    console.warn(`[tmux-claude-code] 预置目录信任失败 (走截屏兜底): ${e.message}`)
    return false
  }
}

// Paste landing probe + fallback
const PASTE_PROBE_TIMEOUT_MS = 8000
const PASTE_PROBE_INTERVAL_MS = 200
const PASTE_SLEEP_BASE_MS = 800
const PASTE_SLEEP_MAX_MS = 5000
// Submit-Enter retry: the TUI's input-mode switch after bracketed paste (-p) occasionally swallows
// the first Enter. Re-send N times idempotently (paste is atomic so extra Enters never split the
// message; once submitted the box is empty and Enter is a no-op).
const SUBMIT_ENTER_ATTEMPTS = 3
const SUBMIT_ENTER_INTERVAL_MS = 500
const INITIAL_CONTEXT_DELAY_MS = 5000
const INITIAL_CONTEXT_GREETING_CHOICES = ['hello', 'greeting', 'are you there', 'good day']

// ── Module-level helpers (stateless) ────────────────────
/*
 * Whether the shared tmux hub session exists. A missing hub is a normal
 * answer, not an error (the first createNewSession brings it up), so the
 * exit status is used as the boolean directly.
 * Only for callers that just want to know; anything that needs the hub to
 * be there uses ensureHub.
 */
function hubExists() {
  return tmux(['has-session', '-t', HUB]).status === 0
}

/*
 * Make sure the hub session that hosts one window per mobius session
 * exists. Idempotent, so every spawn path can call it unconditionally;
 * the hub runs no agent itself, it only holds windows, and the
 * placeholder window created here (name "_root") is never addressed again.
 */
function ensureHub() {
  if (hubExists()) return
  const r = tmux(['new-session', '-d', '-s', HUB, '-n', '_root'])
  // 建不出hub说明tmux有问题，在这里报错最清楚
  // A hub that cannot be created means tmux is broken, fail here
  if (r.status !== 0) throw new Error(`tmux new-session 失败: ${r.stderr}`)
  log(`[tmux-claude-code] created tmux session ${HUB}`)
}

/*
 * Whether a window named after the mobius sessionId exists in the hub.
 * A live query, deliberately never the cached rows: control flow (create,
 * terminate, pause, recovery) decides life and death from this answer, and
 * a 3s-stale "exists" could kill a window that was just created.
 * A tmux failure is reported as "does not exist": the caller then treats
 * it as an absent window (and re-spawns), which also covers the hub being
 * gone.
 */
function windowExists(name: string) {
  const r = tmux(['list-windows', '-t', HUB, '-F', '#{window_name}'])
  // 查询失败当作不存在，调用方会按没有窗口处理
  // A failed query counts as absent, callers then treat it as no window
  if (r.status !== 0) return false
  // 按整行比对，前缀相同的sessionId不会互相误判
  // Compare whole lines, so ids sharing a prefix never match each other
  return r.stdout.split('\n').includes(name)
}

// list-windows result cache (status queries only).
// /status and the syncer poll every 2~5s; one /status calls list-windows 3 times (isAlive, isWorking
// with its inner isAlive, listSessions), all spawnSync and blocking Node's single event loop. Reusing
// the parsed rows within LIST_WINDOWS_TTL_MS (3s) cuts that to 0~1 spawnSync per /status, removing
// the "one slow tmux → event loop occupied → every request in that window queues" avalanche.
// Control flow (windowExists in create/terminate/pause/recovery) still queries live.
const LIST_WINDOWS_TTL_MS = 3 * 1000
let _listWindowsCache: { ts: number; rows: string[][] } | null = null // { ts: number, rows: string[][] }

// isWorking's tail window over the transcript. Must dwarf a single record: claude-code's injected
// context user entry (🚁🍕 project+memory) and long assistant output reach 30KB+ on one line, so the
// old 16KB window held not even one — the first thinking phase's only user marker fell out, leaving
// metadata only (attachment/mode/permission-mode/ai-title...) → nothing matched → working falsely
// false, stuck "idle" for minutes until the first assistant record landed. 256KB clears the giant
// injection to reach the nearest user/assistant marker; read cost is negligible (plain file read, no tmux).
const CLAUDE_WORKING_TAIL_BYTES = 256 * 1024

// Claude Code writes /compact as synthetic `type:user` records: a continuation summary, the local
// command itself, and finally a local-command stdout record such as
// `<local-command-stdout>Compacted ...</local-command-stdout>`. The latter is a completion marker,
// not a new user turn. Keep this narrow so an in-flight compact stays working until its completion
// acknowledgement is written.
//
// Claude Code 2.1.x embeds ANSI dim codes inside the receipt:
//   <local-command-stdout>\x1b[2mCompacted (...)\x1b[22m</local-command-stdout>
// Strip ANSI escapes before matching, otherwise the dim code between the tag and the word keeps the
// idle TUI stuck in `working` forever.
const ANSI_ESCAPE_RE = /\x1b\[[0-9;]*[a-zA-Z]/g
/*
 * Whether a jsonl user entry is the completion receipt of a /compact, the
 * "<local-command-stdout>Compacted ...</local-command-stdout>" record.
 * Claude Code encodes the whole compact bookkeeping as synthetic
 * type:user records, so without this check a finished compact looks like
 * a fresh user turn and an idle TUI stays "working" forever; this receipt
 * is what proves the compact is over, and isWorking / containDequeueEvent
 * both rely on that reading.
 * Deliberately narrow: only this receipt matches, so a compact still in
 * flight keeps reading as working until its acknowledgement is written.
 */
function isCompactCompletionUserEvent(entry: any) {
  if (!entry || entry.type !== 'user') return false
  // content可能是字符串或文本块数组，先统一取纯文本
  // content is a string or text blocks, reduce it to plain text first
  const content = entry.message?.content
  const text = Array.isArray(content)
    ? content
      .filter((block) => block && typeof block === 'object' && block.type === 'text')
      .map((block) => block.text || '')
      .join('\n')
    : content
  // 先剥掉ANSI转义再匹配，暗色码会夹在标签和单词之间
  // Strip ANSI before matching, the dim code sits between tag and word
  return typeof text === 'string'
    && /<local-command-stdout>\s*Compacted\b/i.test(String(text).replace(ANSI_ESCAPE_RE, ''))
}

// Max entries getPendingRequests reverse-scans: pending requests always sit at the jsonl tail, so the
// last N entries suffice — the whole tail is never walked. Reverse scanning is truncation-safe (a
// consumption always follows its enqueue, i.e. is seen earlier in reverse → no false pending);
// truncation can only hide deeply buried old pending (best-effort).
const MAX_PENDING_SCAN_ENTRIES = 20

// realTimeInfo: matches claude TUI's current status line. Core anchor = the parenthesized group that
// starts with elapsed "(<elapsed> · ...)", elapsed being Ns | Mm Ss | Hh Mm Ss. Two shapes:
//   - "(6m 36s · ↓ 20.0k tokens · thinking more)" — with token throughput
//   - "(29s · thinking more)"                     — elapsed + status word only (no tokens yet)
// On a hit the whole line is returned (spinner + task description + group). `\(` followed by a
// digit+time unit excludes output such as "(3 files changed)".
const CLAUDE_STATUS_LINE_RE = /\(\d+(?:s|m\s+\d+s|h\s+\d+m\s+\d+s)[^()]*\)/u
// claude TUI's auto-retry state after an API connection failure. The line has none of the elapsed
// group a normal status line carries:
//   ✻ Unable to connect to API (ConnectionRefused) · Retrying in 25s · attempt 10/10
// Kept as a separate case beside CLAUDE_STATUS_LINE_RE so it can be dropped alone if it misfires,
// without touching the other rule.
const CLAUDE_RETRYING_LINE_RE = /·\s*Retrying\s+in\s+\d+s\s*·/i

/*
 * Pick claude's current status line out of a captured pane, or "" when
 * there is none. Scan from the bottom: the TUI renders only the newest
 * status, so the first match is the live one and the older scrollback
 * lines that also match are ignored.
 * Either shape counts, the elapsed-time status group or the API-retry line
 * (see the two regexes above). The whole line is returned on purpose, so
 * the UI keeps the spinner, the task description and the token counters
 * that surround the matched group.
 */
function findClaudeRealTimeInfo(paneText: string) {
  const lines = String(paneText || '').split('\n')
  // 自下往上扫，最近的一行才是当前状态
  // Scan bottom-up, the nearest line is the current state
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i]
    if (line && (CLAUDE_STATUS_LINE_RE.test(line) || CLAUDE_RETRYING_LINE_RE.test(line))) {
      return line.trim()
    }
  }
  return ''
}
// claude TUI's "waiting for background agents" status line. After the main agent dispatches
// background sub-agents (Task tool) the round's JSONL often ends on end_turn (Task is
// fire-and-forget) while the TUI shows "✻ Waiting for 3 background agents to finish". The reverse
// JSONL scan then returns false → misread as idle, possibly reaped as idle by inactive-tmux-cleaner.
// The JSONL cannot express this wait state, so isWorking falls back to capture-pane when the JSONL
// looks finished (see the end of isWorking); a hit here (N≥1) still means working.
// Not anchored on the spinner glyph (✻ varies per frame), case-insensitive, and [1-9]\d* excludes
// N=0 (the line disappears once done).
const CLAUDE_BG_AGENTS_WAITING_RE = /Waiting\s+for\s+[1-9]\d*\s+background\s+agents?\s+to\s+finish/i

// claude TUI "dangerous operation permission box". Even under --dangerously-skip-permissions /
// "bypass permissions on" in the footer, claude still raises a one-time confirmation for some
// dangerous operations. At least two known texts:
//   Dangerous rm operation on working directory or its ancestor: /home/.../verify-overflow
//   Dangerous rm operation on possibly-empty variable path: "$BASE/$f"
//   Do you want to proceed?   1. Yes   ❯ 2. No   Esc to cancel · Tab to amend · ctrl+e to explain
// The agent then sits on the box waiting for input, the TUI stops advancing, and the session looks
// idle/hung. realTimeInfo detects the box → pulls the full danger_warning line → fire-and-forget
// self-heal (Esc to cancel → wait 5s → resume telling the agent to skip or use a gentler command),
// without blocking the /status poll. Matches the whole "Dangerous <kind> operation on <reason>:
// <target>" line; <reason> is not restricted to specific wording, so new check kinds Claude adds stay
// compatible. The heal still requires the proceed + Esc dialog traits on screen together, so plain
// "Dangerous" text in ordinary output never triggers it.
const CLAUDE_DANGER_OPERATION_RE = /Dangerous\s+\S[^\n]*?operation\s+on\s+[^:\n]+:[^\n]*/i
// Self-heal throttle: one heal at a time per session, and the same warning text never re-triggers
// inside the cooldown — a dirty Esc plus the 5s pane cache would otherwise have realTimeInfo firing
// repeatedly and turn the agent into a broken record.
const DANGER_HEAL_COOLDOWN_MS = 30 * 1000
const _dangerHealState = new Map() // sessionId → { healing: boolean, lastWarning: string, lastTs: number }

// Plain-text tail cache for capture-pane (5s TTL). isWorking's fallback and realTimeInfo share one
// spawn, capping capture-pane at ≤1/5s/session. ANSI escapes stripped; failure or no hit returns "".
const PANE_TAIL_TTL_MS = 5 * 1000
const _paneTailCache = new Map() // sessionId → { ts: number, text: string }
/*
 * Plain-text tail of a session's pane: last 25 lines, ANSI stripped.
 * Shared by isWorking's background-agent fallback and realTimeInfo.
 *
 * The 5s TTL cache exists because capture-pane is a blocking spawnSync.
 * /status polls every 2s and both callers used to spawn their own, so one
 * entry per session caps it at one spawn per 5s.
 *
 * Never throws: a failed or empty capture is cached as "" like any other
 * blank screen.
 */
function capturePaneTail(sessionId: string) {
  const now = Date.now()
  const cached = _paneTailCache.get(sessionId)
  // 命中缓存直接返回，空串也算命中，失败不重复截屏
  // Serve a cache hit at once, "" included, so failures are not re-captured
  if (cached && now - cached.ts < PANE_TAIL_TTL_MS) return cached.text
  let text = ''
  try {
    // -p在此指打印到标准输出，-S -25只截尾部
    // -p is print-to-stdout here, -S -25 keeps only the tail
    const pane = tmux(['capture-pane', '-pt', `${HUB}:${sessionId}`, '-p', '-S', '-25'])
    if (pane.status === 0 && pane.stdout) {
      // 去掉ANSI转义，调用方只做纯文本匹配
      // Strip ANSI escapes, callers only match plain text
      text = pane.stdout.replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '')
    }
  } catch { /* best-effort: a failure returns "" */ }
  _paneTailCache.set(sessionId, { ts: now, text })
  return text
}

/*
 * Hard-kill probe for /stop: reads the pane's newest text directly,
 * bypassing the 5s cache, so it shows the state after the C-c burst and
 * whether a turn is still running.
 *
 * Two busy anchors count: the elapsed-time status line, and "Waiting for N
 * background agents to finish", the one state the jsonl cannot express.
 *
 * This only escalates a stop that already failed. _pauseImpl asks twice
 * before killing, so a hit alone never kills anything; a failed or empty
 * capture returns false (no escalation), so a normally soft-stopped window
 * is never killed.
 */
function claudePaneStillBusy(sessionId: string) {
  let text = ''
  try {
    const pane = tmux(['capture-pane', '-pt', `${HUB}:${sessionId}`, '-p', '-S', '-25'])
    if (pane.status === 0 && pane.stdout) {
      text = pane.stdout.replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '')
    }
  } catch { /* best-effort: a failure means not busy, no escalation */ }
  if (!text) return false
  // 任一忙锚点命中即仍在跑，都不命中说明已空闲
  // Either anchor hit means running, neither means back to idle
  return CLAUDE_STATUS_LINE_RE.test(text) || CLAUDE_BG_AGENTS_WAITING_RE.test(text)
}

/*
 * Whether the claude TUI is stuck on the dangerous-operation permission
 * box, and which command it warns about: { pending, warning }, warning
 * being the full "Dangerous <kind> operation on <reason>: <target>" line,
 * or null.
 *
 * All three traits must be on screen at once (the danger line, "Do you
 * want to proceed?" and "Esc to cancel"), so a stale danger sentence left
 * in the scrollback, or printed by ordinary output, cannot fake a dialog.
 */
function detectDangerPermission(text: string) {
  if (!text) return { pending: false, warning: null }
  const m = text.match(CLAUDE_DANGER_OPERATION_RE)
  // 没有危险命令行就不是这个框
  // No danger line means this is not that dialog
  if (!m) return { pending: false, warning: null }
  // 三个特征必须同时在屏，缺一个就不认
  // All three traits must be on screen at once, one missing means no dialog
  if (!/Do you want to proceed\?/.test(text) || !/Esc to cancel/.test(text)) {
    return { pending: false, warning: null }
  }
  // 返回整行警告，自愈提示词要原样引用它
  // Return the whole warning line, the heal quotes it back verbatim
  return { pending: true, warning: m[0].trim() }
}

/*
 * Parsed `list-windows -F` rows for the hub, served from a 3s cache. The
 * columns are window_name, pane_pid, window_index, window_activity,
 * pane_dead and pane_current_command — what isAlive / listSessions / the
 * syncer read back on every poll.
 *
 * Status queries poll every 2~5s, and one /status used to spawn
 * list-windows three times over (isAlive, isWorking's inner isAlive,
 * listSessions), each one a blocking spawnSync; a single slow tmux then
 * stalled the event loop and queued every request behind it.
 *
 * Control flow must NOT use this cache: acting on rows up to 3s old could
 * kill a window that was just created. Create / terminate / pause call
 * windowExists instead.
 */
function listWindowsRowsCached() {
  const now = Date.now()
  if (_listWindowsCache && now - _listWindowsCache.ts < LIST_WINDOWS_TTL_MS) {
    return _listWindowsCache.rows
  }
  const r = tmux(['list-windows', '-t', HUB, '-F', '#{window_name}|#{pane_pid}|#{window_index}|#{window_activity}|#{pane_dead}|#{pane_current_command}'])
  // tmux失败也缓存空表，避免轮询里反复失败
  // A failed tmux caches an empty list, so polls do not repeat the failure
  const rows = r.status === 0
    ? r.stdout.trim().split('\n').filter(Boolean).map((l: string) => l.split('|'))
    : []
  _listWindowsCache = { ts: now, rows }
  return rows
}

/*
 * Map a cwd to the directory name claude uses under ~/.claude/projects/:
 * every character outside [a-zA-Z0-9] becomes '-', so the mapping is lossy
 * and the name cannot be turned back into a path.
 * e.g. /home/u/cc-workspace/foo_bar → -home-u-cc-workspace-foo-bar
 */
function encodeCwd(cwd: string) {
  return cwd.replace(/[^a-zA-Z0-9]/g, '-')
}

/*
 * Absolute path of the transcript claude writes for one session:
 * ~/.claude/projects/<encoded cwd>/<claude session uuid>.jsonl.
 * Derived, never probed: callers run fs.existsSync themselves when they
 * must know whether the file is there (a resume whose jsonl is missing
 * degrades to a fresh session, see _spawnWindow).
 */
function jsonlPathOf(cwd: string, claudeSessionId: string) {
  return path.join(HOME, '.claude', 'projects', encodeCwd(cwd), `${claudeSessionId}.jsonl`)
}

/*
 * Quote one value for the bash -lc command line, escaping embedded single
 * quotes the '\'' way (close quote, escaped quote, reopen).
 * Needed because the claude arguments are joined into a single shell
 * string: a model id or a path with a space would otherwise split into two
 * arguments.
 */
function shellQuote(s: string) {
  return `'${String(s).replace(/'/g, `'\\''`)}'`
}

/*
 * Normalize the legacy per-session useProxy flag, which arrives from json:
 * a boolean, 0/1, or the strings "0" / "1" / "false" / "true".
 * Anything unrecognized (null, undefined, a stray object) takes the
 * caller's fallback rather than false: in that older data an absent field
 * meant "not configured", not "off".
 */
function normalizeUseProxy(value: unknown, fallback = true) {
  if (value === false || value === 0 || value === '0' || value === 'false') return false
  if (value === true || value === 1 || value === '1' || value === 'true') return true
  return !!fallback
}

/*
 * Normalize the four proxy modes to 'direct' | 'env' | 'proxychains' |
 * 'env_proxychains'.
 *
 * The legacy booleans of rows written before modes existed keep their old
 * meaning: true → 'env_proxychains' (the old env + proxychains double
 * track), false/null → 'direct'. Anything unmapped takes the fallback, so
 * a bad value in a persisted row cannot silently select a proxy path.
 */
function normalizeProxyMode4(value: unknown, fallback = 'direct') {
  if (value === 'env' || value === 'proxychains' || value === 'env_proxychains') return value
  if (value === 'direct') return 'direct'
  if (value === true || value === 1 || value === '1' || value === 'true') return 'env_proxychains'
  if (value === false || value === 0 || value === '0' || value === 'false') return 'direct'
  return fallback
}

/*
 * The single decision point for a session's proxy settings: both dispatch
 * and spawn call it, which is what keeps forceNoProxy / useProxy /
 * proxyMode from ever contradicting each other within one session.
 *
 * forceNoProxy wins outright (mode 'direct'); otherwise the explicit
 * 4-value mode is used, and only a null mode falls back to the legacy
 * useProxy boolean (true → 'env_proxychains', false → 'direct').
 *
 * The returned useProxy is derived from the mode ('direct' ⇔ false) rather
 * than copied from the argument, and fallbackUseProxy supplies the legacy
 * value for an old persisted row that carries neither a mode nor a usable
 * flag.
 */
function resolveClaudeProxyMode(useProxy: boolean, forceNoProxy: boolean = false, fallbackUseProxy: boolean = false, proxyMode: string | null = null) {
  const forced = !!forceNoProxy
  // 强制直连时不再看任何模式，一律判为direct
  // A forced direct ignores every mode and resolves to 'direct'
  const mode = forced
    ? 'direct'
    : normalizeProxyMode4(proxyMode, normalizeUseProxy(useProxy, fallbackUseProxy) ? 'env_proxychains' : 'direct')
  return {
    forceNoProxy: forced,
    useProxy: mode !== 'direct',
    proxyMode: mode,
  }
}

/*
 * Which proxy dependencies the given mode is missing, as display strings
 * ("file: ...", "bin (PATH): ..."; [] when complete). env modes need the
 * env-var file, proxychains modes need the proxychains config (new name,
 * legacy proxy_claude.conf accepted) plus the binary on PATH.
 *
 * Returned rather than thrown so both callers can pick their own severity:
 * preflight warns (sessions on other modes still start),
 * assertProxyAvailable fails the spawn.
 */
function proxyPrereqMissing(mode = 'env_proxychains') {
  const missing: string[] = []
  // 只查该模式用到的依赖，没用到的不算缺失
  // Check only what the mode uses, unused deps are not missing
  const needEnv = mode === 'env' || mode === 'env_proxychains'
  const needChains = mode === 'proxychains' || mode === 'env_proxychains'
  if (needEnv && !fs.existsSync(resolveProxyEnvsFile())) missing.push(`file: ${resolveProxyEnvsFile()}`)
  if (needChains) {
    // 旧名配置仍算数，有一个在就算齐
    // The legacy conf name still counts, either file satisfies it
    if (!fs.existsSync(PROXY_CONF) && !fs.existsSync(path.join(HOME, 'proxy_claude.conf'))) missing.push(`file: ${PROXY_CONF}`)
    if (spawnSync('which', ['proxychains']).status !== 0) missing.push('bin (PATH): proxychains')
  }
  return missing
}

/*
 * Throwing wrapper around proxyPrereqMissing for the spawn path: a session
 * configured to go through a proxy must never quietly start up direct,
 * since that would leak the traffic the operator wanted routed, so a
 * missing dependency fails the spawn with the full list.
 * The message names the mode, so it stays clear which one was attempted.
 */
function assertProxyAvailable(mode = 'env_proxychains') {
  const missing = proxyPrereqMissing(mode)
  if (missing.length) throw new Error(`代理依赖缺失 (${mode}): ${missing.join(', ')}`)
}

/*
 * Read the launch fields this backend needs out of a dispatch: model,
 * settingsPath and the proxy trio, plus captureStream. The flat legacy
 * fields (opts.model, opts.useProxy, ...) are only a compatibility
 * fallback for callers that never went through the model registry;
 * modelLaunchOptions wins when it is present.
 *
 * Absent values stay null / false / 'direct' rather than being invented,
 * so the callers, which prefer their persisted runtime row, can tell "not
 * given" from "given as X". Keep useProxy and proxyMode in step: a forced
 * direct pins both.
 */
function unpackLaunch(opts: ClaudeDispatchOpts): { model: string | null; settingsPath: string | null; useProxy: boolean; proxyMode: string; forceNoProxy: boolean; captureStream: boolean } {
  const launch = (opts?.modelLaunchOptions || {}) as Record<string, any>
  // 布尔字段要求严格为true，字符串不算
  // The booleans need a strict true, a string "true" does not count
  return {
    model: launch.model || opts.model || null,
    settingsPath: launch.settingsPath || opts.settingsPath || null,
    useProxy: launch.forceNoProxy ? false : (launch.useProxy === true || opts.useProxy === true),
    proxyMode: launch.forceNoProxy ? 'direct' : (launch.proxyMode || opts.proxyMode || 'direct'),
    forceNoProxy: launch.forceNoProxy === true || opts.forceNoProxy === true,
    captureStream: launch.captureStream === true,
  }
}

/*
 * Drop (and refresh) the session's running flag,
 * <root>/.imac/flags/<sessionId>/running.flag: the out-of-process signal
 * that a task is in flight. The agent removes it when the task ends,
 * success or failure, per the session-context hint, and
 * isJobGoalAccomplished reads its presence.
 *
 * root is flagRoot, the project repo root (bind_path): the same as cwd
 * outside a worktree, but the repo root rather than cwd inside one, so the
 * agent's first step of cleaning / rebuilding the worktree cannot delete
 * the flag by mistake.
 *
 * safeWriteRunningFlag swallows fs errors: a flag that cannot be written
 * must not fail the prompt dispatch.
 */
function markRunning(root: string | null | undefined, sessionId: string) {
  return safeWriteRunningFlag(root, sessionId, {}, 'tmux-claude-code')
}

/*
 * Remove running.flag, i.e. declare the task finished from our side. Only
 * the interrupt-only /stop path calls it (empty prompt: nothing new is
 * queued, so nothing may stay marked as running); on a normal completion
 * the agent removes the flag itself.
 */
function clearRunning(root: string | null | undefined, sessionId: string) {
  return safeRemoveRunningFlag(root, sessionId, 'tmux-claude-code')
}

/*
 * Trailing run of printable ASCII (5~15 chars) at the end of the prompt,
 * used as the capture-pane probe that proves a paste landed.
 * CJK and box-drawing characters do not render glyph-for-glyph in a tmux
 * pane, so only an ASCII tail can be matched back reliably. Trailing
 * whitespace is skipped first; a tail shorter than 5 chars, or one cut
 * short by a non-ASCII character, returns null, which sends the caller to
 * the length-scaled sleep instead.
 */
function findAsciiTailMarker(text: string) {
  const ASCII = /[\x20-\x7E]/
  let i = text.length - 1
  // 先跳过末尾空白，否则探针会带上看不见的字符
  // Skip trailing whitespace first, or the probe carries invisible chars
  while (i >= 0 && /\s/.test(text[i])) i--
  let tail = ''
  // 从后往前收可打印ASCII，遇非ASCII即停
  // Collect printable ASCII backwards, stop at non-ASCII (cap 15)
  while (i >= 0 && tail.length < 15) {
    if (!ASCII.test(text[i])) break
    tail = text[i] + tail
    i--
  }
  // 太短的探针在面板里到处能撞上，宁可不探改用等待
  // A probe this short matches all over the pane, sleep instead
  return tail.length >= 5 ? tail : null
}

/*
 * Promise-based pause, the only sleep idiom this backend uses: the async
 * paths (ready poll, paste probe, submit-echo gaps) must not block node's
 * event loop the way a spawnSync('sleep') would — every session shares
 * that loop with the HTTP server, so a blocking sleep freezes them all.
 */
function sleep(ms: number) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

function pickInitialContextPlan() {
  const roll = Math.random()
  if (roll < 1 / 3) return 'greeting_then_context'
  if (roll < 2 / 3) return 'direct_context'
  return 'delay_then_context'
}

function pickInitialContextGreeting() {
  const index = Math.floor(Math.random() * INITIAL_CONTEXT_GREETING_CHOICES.length)
  return INITIAL_CONTEXT_GREETING_CHOICES[index]
}

// ── Startup preflight (once at module load; a missing dep is a hard failure) ──
;(function preflight() {
  const missing: string[] = []
  for (const bin of ['tmux', 'claude']) {
    if (spawnSync('which', [bin]).status !== 0) missing.push(`bin (PATH): ${bin}`)
  }
  if (missing.length) {
    console.error('[tmux-claude-code] ❌ preflight 失败, 拒绝启动:')
    for (const m of missing) console.error('   - ' + m)
    process.exit(1)
  }
  const proxyMissing = proxyPrereqMissing('env_proxychains')
  if (proxyMissing.length) {
    console.warn(`[tmux-claude-code] ⚠️  代理依赖不完整; 直连/纯 env 挡的会话仍可启动: ${proxyMissing.join(', ')}`)
  }
  log(`[tmux-claude-code] ✅ preflight pass (SOCKET=${AGENT_TMUX_SOCKET}, HUB=${HUB})`)
})()

// ── Backend ────────────────────────────────────────────
// ── getPendingRequests helpers: parse "enqueued but unconsumed" requests out of Claude
// Code's in-memory queue ───────────────────────────────────
// Guide (issue_knowledge/d1600424): enqueue = enqueued; the same request later appearing as
// type:user (consumed directly while idle) or attachment.type:queued_command (injected mid-turn
// while busy) = consumed, no longer pending. dequeue/remove carry no content and cannot serve as a
// "delivered" ACK, so they never decide it.

// Normalize request text: collapse runs of whitespace and trim, removing the newline/indent
// differences the TUI introduces when writing to disk.
function normalizeRequestText(value: unknown) {
  if (typeof value !== 'string') return ''
  return value.replace(/\s+/g, ' ').trim()
}

// Request-text signature from a "consuming" entry; '' for anything else.
//   - type:user                 → message.content (string or array of text blocks)
//   - attachment:queued_command → attachment.content / text / command
function consumedRequestSignature(entry: any) {
  if (!entry || typeof entry !== 'object') return ''
  if (entry.type === 'user') {
    const c = entry.message?.content
    if (typeof c === 'string') return normalizeRequestText(c)
    if (Array.isArray(c)) {
      const text = c
        .filter((b) => b && typeof b === 'object' && b.type === 'text')
        .map((b) => b.text || '')
        .join('\n')
      return normalizeRequestText(text)
    }
    return ''
  }
  if (entry.type === 'attachment' && entry.attachment?.type === 'queued_command') {
    const a = entry.attachment
    return normalizeRequestText(a.content || a.text || a.command || '')
  }
  return ''
}

// Same-request test: equal full signatures, or one fully contains the other (tolerating an agent's
// wrapper). Deliberately "full-text contains" rather than a prefix fingerprint — many mobius prompts
// share the same opening prefix, so prefixes would match different tasks to each other.
function isSameQueuedRequest(sigA: string, sigB: string) {
  if (!sigA || !sigB) return false
  if (sigA === sigB) return true
  if (sigA.length >= 40 && sigB.includes(sigA)) return true
  if (sigB.length >= 40 && sigA.includes(sigB)) return true
  return false
}

// Resolve the aimux binary to spawn as a stdio MCP server (desktop/TUI sessions that opted into
// add_remote_aimux_mcp). Mirrors tmux-codex.js + aimux-remote.ts AIMUX_BIN_CANDIDATES, kept inline
// to avoid crossing the .js/.ts boundary.
function resolveAimuxBin() {
  const candidates = [
    process.env.AIMUX_BIN,
    path.join(os.homedir(), '.local', 'bin', 'aimux'),
    path.join(__dirname, '..', '..', '.venv-aimux', 'bin', 'aimux'),
  ]
  for (const c of candidates) { if (c && fs.existsSync(c)) return c }
  return 'aimux'
}

// Resolve the guling live-trading MCP (HTTP / streamable-http) server config from env, so the
// Xiaomo assistant session can read funds/positions (mcp__guling__position / balance) directly,
// without going through Hermes. The bearer token is a credential and MUST live in .env
// (MOBIUS_GULING_MCP_URL / MOBIUS_GULING_MCP_TOKEN) — never in source. Returns a
// { type:'http', url, headers } entry ready to drop into the per-session --mcp-config mcpServers,
// or null when unset (→ injection is a no-op).
function resolveGulingMcp() {
  const url = (process.env.MOBIUS_GULING_MCP_URL || '').trim()
  const token = (process.env.MOBIUS_GULING_MCP_TOKEN || '').trim()
  if (!url || !token) return null
  return { type: 'http', url, headers: { Authorization: `Bearer ${token}` } }
}


// Runtime entry: the live state of one tmux window + claude TUI per mobius session.
interface ClaudeRuntimeEntry {
  agentSessionId: string
  cwd: string
  flagRoot: string
  model: string | null
  useProxy: boolean
  proxyMode?: string
  settingsPath: string | null
  withProxyPath?: string | null
  captureStream?: boolean
  forceNoProxy: boolean
  displayName: string | null
  jsonlPath: string
  startedAt: number
  watch: { stop?: () => void } | null
}

// dispatch contract: the arg shape shared by createNewSession / queue / pause (whole
// modelLaunchOptions plus the legacy flat fields).
interface ClaudeDispatchOpts {
  sessionId: string
  prompt?: string
  initialPrompt?: string
  cwd?: string
  flagRoot?: string
  displayName?: string
  agentSessionId?: string | null
  isInitialContextPrompt?: boolean
  mobiusPromptRecord?: Record<string, unknown> | null
  suppressRunningFlag?: boolean
  urgent?: boolean
  aimuxRemoteName?: string
  enableGulingMcp?: boolean
  modelLaunchOptions?: Record<string, unknown>
  model?: string | null
  useProxy?: boolean
  proxyMode?: string
  settingsPath?: string | null
  forceNoProxy?: boolean
  [key: string]: unknown
}

class TmuxClaudeCodeBackend extends AgentBackend {
  declare runtime: Map<string, ClaudeRuntimeEntry>
  constructor() {
    super({ name: 'tmux-claude-code', runtimeFile: RUNTIME_FILE, archiveFile: ARCHIVE_FILE })
    // runtime: sessionId → { agentSessionId, cwd, flagRoot, model, settingsPath, displayName, jsonlPath, startedAt, watch }
    this.runtime = new Map()
    this._restoreFromPersisted()
  }

  // On backend startup, pull the sessionId → agentSessionId/jsonlPath mapping back from
  // hub-runtime.json. The process (claude TUI inside tmux) may of course still be alive — we never
  // restart it, only start a jsonl watcher to tail it.
  _restoreFromPersisted() {
    let total = 0
    for (const [sid, p] of Object.entries(this.persisted) as Array<[string, any]>) {
      total++
      if (!p?.jsonlPath || !fs.existsSync(p.jsonlPath)) {
        log(`[tmux-claude-code] runtime 条目 ${sid} 被丢弃 (jsonl 缺失: ${p?.jsonlPath})`)
        continue
      }
      this.runtime.set(sid, {
        agentSessionId: p.agentSessionId || null,
        cwd: p.cwd,
        flagRoot: p.flagRoot || p.cwd,
        model: p.model || null,
        useProxy: normalizeUseProxy(p.useProxy, true),
        settingsPath: p.settingsPath || null,
        forceNoProxy: !!p.forceNoProxy,
        displayName: p.displayName || null,
        jsonlPath: p.jsonlPath,
        startedAt: p.startedAt || 0,
        watch: null,
      })
      this._ensureWatcher(sid)
    }
    log(`[tmux-claude-code] runtime 加载 ${this.runtime.size}/${total} 条`)
  }

  // One jsonl-watcher per session (unique to this backend); new lines go to _emitRaw for all
  // subscribers. Never restarted when already live. startOffset=current size: pushes only the delta,
  // the history store covers the initial content.
  _ensureWatcher(sessionId: string) {
    const entry = this.runtime.get(sessionId)
    if (!entry?.jsonlPath || entry.watch) return
    let startOffset = 0
    try { startOffset = fs.existsSync(entry.jsonlPath) ? fs.statSync(entry.jsonlPath).size : 0 } catch {}
    entry.watch = watchJsonlFile({
      path: entry.jsonlPath,
      startOffset,
      onEntry: (raw: any) => this._emitRaw(sessionId, raw),
      onError: (e: unknown) => console.warn(`[tmux-claude-code/watch ${sessionId}] ${(e as Error)?.message || e}`),
    })
  }

  // ── Public methods (wrapped by the base-class lock) ─────
  createNewSession(opts: ClaudeDispatchOpts) {
    this._writeMobiusPromptEarly(opts)
    return this._withLock(opts?.sessionId, () => this._createImpl(opts))
  }
  pauseCurrentAndResumeFromSession(opts: ClaudeDispatchOpts) {
    this._writeMobiusPromptEarly(opts)
    return this._withLock(opts?.sessionId, () => this._pauseImpl(opts))
  }
  noPauseCurrentAndQueueQueryAtSession(opts: ClaudeDispatchOpts) {
    this._writeMobiusPromptEarly(opts)
    return this._withLock(opts?.sessionId, () => this._queueImpl(opts))
  }
  pauseCurrentToDequeueQuery(sessionId: string) {
    return this._withLock(sessionId, async () => {
      if (!sessionId) throw new Error('需要 sessionId')
      if (!windowExists(sessionId)) return
      // A single C-c interrupts the current turn (one is enough in practice) so the agent stops and
      // picks up the next queued instruction. Appends no prompt and sends no M-Enter — unlike the
      // expedited pauseCurrentAndResumeFromSession path, this only interrupts.
      tmux(['send-keys', '-t', `${HUB}:${sessionId}`, 'C-c'])
      await new Promise((r) => setTimeout(r, 250))
    })
  }

  // Early opener: write the user card and open the round as soon as dispatch arrives (before the lock
  // and the spawn), otherwise the first sync during spawn wins the race and the preamble lands in "round 0".
  _writeMobiusPromptEarly(opts: ClaudeDispatchOpts) {
    if (!opts?.sessionId || !opts?.mobiusPromptRecord) return
    try { this.harnessWriteMobiusCoreEntry(opts.sessionId, opts.mobiusPromptRecord, opts.cwd) } catch {}
  }
  terminateSession(sessionId: string) {
    return this._withLock(sessionId, async () => {
      const r = await this._terminateImpl(sessionId)
      // Termination fallback: flush pending_round_openers now — a crashed agent may never produce a dequeue.
      try { flushPendingOpeners(sessionId) } catch {}
      return r
    })
  }

  // ── Status queries (no lock; safe to run concurrently with writes) ──
  isAlive(sessionId: string) {
    // Status queries use the cache (3s TTL); control flow (create/terminate) must use the live windowExists.
    return listWindowsRowsCached().some((cols: string[]) => cols[0] === sessionId)
  }

  isWorking(sessionId: string) {
    if (!this.isAlive(sessionId)) return false
    const entry = this.runtime.get(sessionId)
    if (!entry?.jsonlPath) return false
    let lines
    try {
      if (!fs.existsSync(entry.jsonlPath)) return false
      const stat = fs.statSync(entry.jsonlPath)
      if (stat.size === 0) return false
      const len = Math.min(stat.size, CLAUDE_WORKING_TAIL_BYTES)
      const buf = Buffer.alloc(len)
      const fd = fs.openSync(entry.jsonlPath, 'r')
      try { fs.readSync(fd, buf, 0, len, stat.size - len) } finally { fs.closeSync(fd) }
      lines = buf.toString('utf8').split('\n').filter(Boolean)
    } catch { return false }

    // Reverse scan, whitelist logic — only user / assistant / system+selected subtypes decide the
    // state; every other type (attachment / last-prompt / custom-title / agent-name / permission-mode /
    // file-history-snapshot / queue-operation, plus any metadata the TUI or gateway adds later) is
    // skipped, so new metadata types can no longer break the check.
    for (let i = lines.length - 1; i >= 0; i--) {
      let e
      try { e = JSON.parse(lines[i]) } catch { continue }
      if (e.type === 'assistant') {
        // missing stop_reason / 'tool_use' → still running; end_turn / max_tokens / stop_sequence → done
        const sr = e.message?.stop_reason
        return !sr || sr === 'tool_use'
      }
      if (e.type === 'user') {
        // `/compact` finishes with a synthetic local-command stdout record. Treat it like an
        // end_turn, or an idle TUI stays in `working` — the compact bookkeeping is itself encoded
        // as user events and has no assistant stop_reason.
        if (isCompactCompletionUserEvent(e)) return false
        return true
      }
      if (e.type === 'system') {
        const sub = e.subtype
        if (sub === 'init' || sub === 'hook_started' || sub === 'hook_response') return true
        // turn_duration / stop_hook_summary / away_summary / any future subtype → skip
      }
      // every other type is skipped
    }
    // JSONL looks finished (nearest whitelisted record is an end_turn assistant), but the TUI may be
    // waiting for background agents — a state JSONL cannot express, so fall back to the pane's
    // "Waiting for N background agents to finish". A hit still counts as working (no false idle/reap).
    return CLAUDE_BG_AGENTS_WAITING_RE.test(capturePaneTail(sessionId))
  }

  // Dequeue detection: in Claude Code "human input actually reached the agent" = the entry carries
  // origin.kind=='human'. Both on-disk shapes count — a top-level origin (hand-typed type:user) or an
  // origin inside attachment (queued_command injection). /compact's completion receipt
  // (<local-command-stdout>Compacted) is also a dequeue signal: its opener (kind=compact) opens the
  // round only then. Everything else (system/assistant/tool...) is not.
  containDequeueEvent(entry: any, pendingInputs: string[] = []): boolean {
    if (!entry || typeof entry !== 'object') return false
    if (entry.operation === 'dequeue') return true
    if (entry.origin?.kind === 'human') return true
    if (entry.attachment?.origin?.kind === 'human') return true
    if (isCompactCompletionUserEvent(entry)) return true
    // Claude can omit origin when a slash-prefixed path falls through slash command parsing. While
    // any such input is pending, accept the next valid JSON entry as the dequeue signal
    // (intentionally permissive).
    if (pendingInputs.some((input) => typeof input === 'string' && input.trimStart().startsWith('/'))) return true
    return false
  }

  // Pending requests: prompts "enqueued but not yet consumed" in Claude Code's in-memory queue.
  //
  // Performance (big files stay fast): read only the trailing CLAUDE_WORKING_TAIL_BYTES bytes (a 100MB
  // jsonl still reads 256KB — independent of total size) and parse only the nearest
  // MAX_PENDING_SCAN_ENTRIES entries, never the whole tail. Pending requests always sit at the tail,
  // so the scan runs **backwards**:
  //   - a "consuming" entry (user/queued_command) records its signature;
  //   - an enqueue with no match among the consumptions already scanned (those come later in the
  //     file) is still queued.
  // Truncation-safe by construction: a consumption always follows its enqueue (earlier in reverse), so
  // an in-window consumption was already seen and a consumed request is never misread as pending.
  // mobius decoration entries live in .mobius.jsonl rather than this native file, so content matching
  // sees no send-mirror pollution.
  // Returns [{ content, enqueuedAt }], ordered by enqueue time; empty = nothing queued.
  getPendingRequests(sessionId: string) {
    const jsonlPath = this._resolveJsonlPath(sessionId)
    if (!jsonlPath) return []
    let tailLines
    try {
      if (!fs.existsSync(jsonlPath)) return []
      const stat = fs.statSync(jsonlPath)
      if (stat.size === 0) return []
      const len = Math.min(stat.size, CLAUDE_WORKING_TAIL_BYTES)
      const buf = Buffer.alloc(len)
      const fd = fs.openSync(jsonlPath, 'r')
      try { fs.readSync(fd, buf, 0, len, stat.size - len) } finally { fs.closeSync(fd) }
      tailLines = buf.toString('utf8').split('\n').filter(Boolean)
    } catch { return [] }

    const recent = tailLines.slice(-MAX_PENDING_SCAN_ENTRIES)
    const consumedSigs: any[] = []
    const pending: any[] = []
    for (let i = recent.length - 1; i >= 0; i--) {
      let e
      try { e = JSON.parse(recent[i]) } catch { continue }
      if (!e || typeof e !== 'object') continue

      const consumedSig = consumedRequestSignature(e)
      if (consumedSig) { consumedSigs.push(consumedSig); continue }

      if (e.type === 'queue-operation' && e.operation === 'enqueue') {
        const content = typeof e.content === 'string' ? e.content : null
        if (!content) continue
        const sig = normalizeRequestText(content)
        if (!consumedSigs.some((cs) => isSameQueuedRequest(sig, cs))) {
          pending.push({ content, enqueuedAt: e.timestamp || null })
        }
      }
    }
    pending.reverse() // reverse collection → back to enqueue order (oldest pending first)
    return pending
  }

  // Whether the task is done: the session drops running.flag at start and the agent removes it on
  // completion, success or failure. No flag → done (accomplished=true). Unknown session → false
  // (cannot tell). Anchored on flagRoot (the repo root), falling back to cwd for old entries.
  isJobGoalAccomplished(sessionId: string) {
    const entry = this.runtime.get(sessionId)
    const root = entry?.flagRoot || entry?.cwd
    if (!root) return false
    return !fs.existsSync(runningFlagPathOf(root, sessionId))
  }

  // Whether the task failed: failed.flag present → true. Unknown session (no root) → false (cannot
  // tell). Same anchor as isJobGoalAccomplished (flagRoot, the repo root; cwd for old entries).
  isFailed(sessionId: string) {
    const entry = this.runtime.get(sessionId)
    const root = entry?.flagRoot || entry?.cwd
    if (!root) return false
    return fs.existsSync(failedFlagPathOf(root, sessionId))
  }

  listSessions() {
    return listWindowsRowsCached().map((cols: string[]) => {
      const [name, pid, idx, activity, paneDead, paneCurrentCommand] = cols
      const entry = this.runtime.get(name)
      const lastActivitySec = Number(activity)
      const lastActivityMs = Number.isFinite(lastActivitySec) && lastActivitySec > 0
        ? lastActivitySec * 1000
        : null
      return {
        sessionId: name,
        agentSessionId: entry?.agentSessionId || null,
        pid: Number(pid),
        index: Number(idx),
        lastActivityMs,
        lastActivityAt: lastActivityMs ? new Date(lastActivityMs).toISOString() : null,
        tmuxOpen: true,
        paneDead: paneDead === '1',
        paneCurrentCommand: paneCurrentCommand || null,
      }
    })
  }

  // Live status line for the session page's LIVE card: capture the pane's last 15 lines and pick the
  // claude TUI status line. Not alive / not working → "". A 5s TTL cache (empty results included) caps
  // capture-pane at ≤1/5s. Nice-to-have only: failures return "" silently, never throwing into /status.
  realTimeInfo(sessionId: string): string {
    // Reuses capturePaneTail's 5s cache — one capture-pane spawn shared with isWorking's fallback.
    // isWorking already captured in grey areas like "waiting for background agents", so this hits it.
    try {
      if (this.isAlive(sessionId) && this.isWorking(sessionId)) {
        const paneText = capturePaneTail(sessionId)
        // Dangerous-operation permission box: claude pops it even under "bypass permissions on", and an
        // agent waiting on it stalls the TUI → the session looks hung. tool_use pending makes isWorking
        // true, which covers this state; the heal (Esc + wait 5s + resume) is fire-and-forget.
        const danger = detectDangerPermission(paneText)
        if (danger.pending && danger.warning) this._maybeHealDangerPermission(sessionId, danger.warning)
        const info = findClaudeRealTimeInfo(paneText)
        if (info) return info
      }
    } catch { /* best-effort: a failure returns "" */ }
    return ''
  }

  // Danger-box heal throttle: one heal at a time per session, and the same warning never re-triggers
  // inside the cooldown. realTimeInfo calls it on a detectDangerPermission hit; fire-and-forget, it
  // returns at once and never blocks the /status poll.
  _maybeHealDangerPermission(sessionId: string, warning: string) {
    const st = _dangerHealState.get(sessionId) || { healing: false, lastWarning: '', lastTs: 0 }
    const now = Date.now()
    if (st.healing) return  // already healing, do not repeat
    if (warning === st.lastWarning && now - st.lastTs < DANGER_HEAL_COOLDOWN_MS) return  // same box handled recently
    _dangerHealState.set(sessionId, { healing: true, lastWarning: warning, lastTs: now })
    this._healDangerPermission(sessionId, warning)
      .catch((e) => log(`[tmux-claude-code] danger heal 失败 session=${sessionId}: ${e?.message || e}`))
      .finally(() => {
        const cur = _dangerHealState.get(sessionId)
        if (cur) _dangerHealState.set(sessionId, { ...cur, healing: false })
      })
  }

  // Danger-box heal, main flow (detached async, never blocks realTimeInfo / /status):
  //   1) Esc cancels the box (claude returns to the pending/input state without running the dangerous
  //      command; the dialog itself says "Esc to cancel")
  //   2) wait 5s for the TUI to settle, so the dialog is fully gone
  //   3) pauseCurrentAndResumeFromSession sends "$warning, please skip or try commands that are less
  //      aggressive." (internally C-c×3 to interrupt the turn, then queues the new prompt; even if the
  //      agent is still in its old turn after the Esc it gets reset)
  async _healDangerPermission(sessionId: string, warning: string) {
    if (!windowExists(sessionId)) return
    tmux(['send-keys', '-t', `${HUB}:${sessionId}`, 'Escape'])
    _paneTailCache.delete(sessionId)  // invalidate the cache so later checks see the screen after the cancel
    log(`[tmux-claude-code] danger permission 检测到, 已 Esc 取消 (session=${sessionId}): ${warning}`)
    await new Promise((r) => setTimeout(r, 5000))
    if (!windowExists(sessionId)) return
    await this.pauseCurrentAndResumeFromSession({
      sessionId,
      prompt: `${warning}, please skip or try commands that are less aggressive.`,
      urgent: false,
    })
    log(`[tmux-claude-code] danger permission 已 resume 提示 agent 跳过/换温和命令 (session=${sessionId})`)
  }

  // Three-level lookup for sessionId → jsonl file path:
  //   - runtime (Map, in-process)    — sessions currently alive
  //   - persisted (hub-runtime.json) — live, cleared on terminate
  //   - archive (hub-archive.json)   — every session ever, kept past terminate; how history is
  //                                    found after an admin closes the window
  _resolveJsonlPath(sessionId: string): string | null {
    return this.runtime.get(sessionId)?.jsonlPath
        || this._lookupPersistedJsonlPath(sessionId)
        || this._lookupArchivedJsonlPath(sessionId)
        || null
  }

  // History snapshot from the agent-history-store DB (native jsonl deltas are backfilled before the read).
  getHistory(sessionId: string, _opts: QueryOpts = {}): HistorySnapshot {
    const pendingInputs = this.getPendingRequests(sessionId).map((item: any) => typeof item === 'string' ? item : item?.content).filter((item): item is string => typeof item === 'string')
    return getHistorySnapshot(sessionId, this._resolveJsonlPath(sessionId), this.containDequeueEvent.bind(this), pendingInputs) as HistorySnapshot
  }

  get_time_consume_waterfall(sessionId: string, opts: QueryOpts = {}) {
    return timeConsumeWaterfallFromBackend(this, sessionId, opts)
  }

  clear_time_consume_waterfall(sessionId: string, opts: QueryOpts = {}) {
    return clearTimeConsumeWaterfallForBackend(this, sessionId, opts)
  }

  // Subscribe to the raw stream: the base EventEmitter fed by this backend's watcher.
  // Backfill is agent-history-store's job now; there is no fromSentinel resume semantics any more.
  getAgentRawThoughtStream(sessionId: string, listener: (raw: unknown) => void, opts: QueryOpts = {}) {
    return super.getAgentRawThoughtStream(sessionId, listener, opts)
  }

  // The send path writes the user_input/compact card = opens a new round (into agent-history-store, no
  // file). No bound runtime jsonl path is required: the call site moved to the dispatch entry, so a new
  // session opens its round during spawn with the path left null for the first sync to claim.
  harnessWriteMobiusCoreEntry(sessionId: string, mobiusPromptRecord: Record<string, unknown> | null | undefined, cwdHint?: string) {
    if (!mobiusPromptRecord) return false
    const entry = this.runtime.get(sessionId)
    try {
      return writeMobiusCoreEntry({
        sessionId,
        agentSessionId: entry?.agentSessionId || null,
        cwd: entry?.cwd || cwdHint || null,
        backendName: this.name,
        primaryPath: entry?.jsonlPath || null,
        ...mobiusPromptRecord,
        containDequeueEvent: this.containDequeueEvent.bind(this),
        pendingInputs: this.getPendingRequests(sessionId).map((item: any) => typeof item === 'string' ? item : item?.content).filter((item): item is string => typeof item === 'string'),
      })
    } catch (e) {
      console.warn(`[tmux-claude-code] mobius core entry failed (${sessionId}): ${(e as Error)?.message || e}`)
      return false
    }
  }

  // ── Internals ──────────────────────────────────────────
  async _createImpl(opts: ClaudeDispatchOpts) {
    const { sessionId, cwd, flagRoot, displayName, initialPrompt, agentSessionId, isInitialContextPrompt = false, aimuxRemoteName, enableGulingMcp = false } = opts
    const { model, useProxy, proxyMode, settingsPath, forceNoProxy, captureStream } = unpackLaunch(opts)
    if (!sessionId || !cwd) throw new Error('createNewSession 需要 sessionId + cwd')
    if (!initialPrompt) throw new Error('createNewSession 需要 initialPrompt')
    if (!fs.existsSync(cwd)) throw new Error(`cwd 不存在: ${cwd}`)

    // tmux-mode trait: windows survive a backend restart. An existing live window is reused (matching
    // the original hub.startSession idempotence), deliberately unlike the stream-json "always create
    // fresh" semantics.
    if (!windowExists(sessionId)) {
      await this._spawnWindow({ sessionId, cwd, flagRoot, model, useProxy, proxyMode, displayName, agentSessionId, settingsPath, captureStream, forceNoProxy, aimuxRemoteName, enableGulingMcp })
    } else {
      // window exists but the runtime entry may not (first reload after a restart) — build one
      if (!this.runtime.has(sessionId) && agentSessionId) {
        const jp = jsonlPathOf(cwd, agentSessionId)
        const finalSettingsPath = settingsPath || null
        const resolved = resolveClaudeProxyMode(useProxy, forceNoProxy, false, proxyMode)
        this.runtime.set(sessionId, {
          agentSessionId, cwd, flagRoot: flagRoot || cwd, model: model || null, useProxy: resolved.useProxy,
          settingsPath: finalSettingsPath, forceNoProxy: resolved.forceNoProxy, displayName: displayName || null,
          jsonlPath: jp, startedAt: Date.now(), watch: null,
        })
        this._persistEntry(sessionId, {
          agentSessionId, cwd, flagRoot: flagRoot || cwd, model, useProxy: resolved.useProxy,
          settingsPath: finalSettingsPath, forceNoProxy: resolved.forceNoProxy, displayName,
          jsonlPath: jp, startedAt: Date.now(),
        })
        this._ensureWatcher(sessionId)
      }
    }

    const entry = this.runtime.get(sessionId)
    await this._sendMaybeInitialContextPrompt(sessionId, initialPrompt, isInitialContextPrompt)
    markRunning(flagRoot || entry?.flagRoot || entry?.cwd || cwd, sessionId)
    return {
      sessionId,
      agentSessionId: entry?.agentSessionId || null,
      jsonlPath: entry?.jsonlPath || null,
      startedAt: entry?.startedAt || Date.now(),
    }
  }

  // Permissive variant — spawns from opts when nothing is alive (chat makes no first/follow-up
  // distinction, so everything comes through here).
  async _queueImpl(opts: ClaudeDispatchOpts) {
    const { sessionId, prompt, cwd, flagRoot, displayName, agentSessionId, isInitialContextPrompt = false, mobiusPromptRecord = null, suppressRunningFlag = false, aimuxRemoteName, enableGulingMcp = false } = opts
    let { model, useProxy, proxyMode: proxyModeArg, settingsPath, forceNoProxy, captureStream } = unpackLaunch(opts)
    if (!sessionId) throw new Error('需要 sessionId')
    if (!prompt) throw new Error('需要 prompt')

    if (!windowExists(sessionId)) {
      // No live window → we must be able to spawn. cwd prefers opts, else the runtime persisted row.
      const persisted = this.runtime.get(sessionId)
      const finalCwd = cwd || persisted?.cwd
      const finalAgentSid = agentSessionId || persisted?.agentSessionId
      const finalSettingsPath = settingsPath || persisted?.settingsPath || null
      const proxyMode = resolveClaudeProxyMode(
        useProxy,
        forceNoProxy || persisted?.forceNoProxy,
        persisted?.useProxy ?? false,
        proxyModeArg ?? persisted?.proxyMode,
      )
      if (!finalCwd) throw new Error(`session ${sessionId} 没活 window 且无 cwd, 无法 spawn`)
      await this._spawnWindow({
        sessionId,
        cwd: finalCwd,
        flagRoot: flagRoot || persisted?.flagRoot || finalCwd,
        model: model || persisted?.model,
        useProxy: proxyMode.useProxy,
        proxyMode: proxyMode.proxyMode,
        settingsPath: finalSettingsPath,
        captureStream: captureStream || (persisted?.captureStream ?? false),
        forceNoProxy: proxyMode.forceNoProxy,
        displayName: displayName ?? (persisted?.displayName ?? undefined),
        agentSessionId: finalAgentSid ?? undefined,
        aimuxRemoteName,
        enableGulingMcp,
      })
    }
    await this._sendMaybeInitialContextPrompt(sessionId, prompt, isInitialContextPrompt)
    const entry = this.runtime.get(sessionId)
    if (!suppressRunningFlag) markRunning(flagRoot || entry?.flagRoot || entry?.cwd || cwd, sessionId)
  }

  async _pauseImpl({ sessionId, prompt, cwd, flagRoot, urgent = false, mobiusPromptRecord = null }: ClaudeDispatchOpts) {
    if (!sessionId) throw new Error('需要 sessionId')
    const persisted = this.runtime.get(sessionId)

    if (windowExists(sessionId)) {
      if (urgent) {
        // Urgent: a single C-c interrupts the turn (one is enough in practice). Space it with
        // await setTimeout, never spawnSync('sleep') — that blocks the event loop and freezes node.
        tmux(['send-keys', '-t', `${HUB}:${sessionId}`, 'C-c'])
        await new Promise(r => setTimeout(r, 250))
        // After the interrupt old input may be back in the box; Alt+Enter first to separate it, or it
        // sticks to the new prompt
        tmux(['send-keys', '-t', `${HUB}:${sessionId}`, 'M-Enter'])
        await new Promise(r => setTimeout(r, 80))
      } else {
        // /stop: 3 C-c's interrupt the turn without killing the window; in practice the TUI swallows one.
        for (let i = 0; i < 3; i++) {
          tmux(['send-keys', '-t', `${HUB}:${sessionId}`, 'C-c'])
          if (i < 2) await new Promise(r => setTimeout(r, 50))
        }
        // give the claude TUI a moment to digest the interrupt
        await new Promise(r => setTimeout(r, 300))
        // Fallback: C-c×3 is occasionally swallowed or stuck on a dialog and the turn never stops.
        // Only in the empty-prompt soft stop (/stop) case do we escalate to a tmux kill-window hard
        // stop, guaranteeing /stop always stops the background agent (a dead window is respawned by
        // _queueImpl on the next message, so the session still continues); the urgent+new-prompt path
        // keeps the window for input and never hard-kills. Double confirmation (capture busy → wait
        // 700ms more → still busy) avoids killing a window that soft-stopped normally, which a briefly
        // lingering status line would otherwise cause.
        if (!prompt) {
          _paneTailCache.delete(sessionId)
          if (claudePaneStillBusy(sessionId)) {
            await new Promise(r => setTimeout(r, 700))
            _paneTailCache.delete(sessionId)
            if (windowExists(sessionId) && claudePaneStillBusy(sessionId)) {
              tmux(['kill-window', '-t', `${HUB}:${sessionId}`])
              log(`[tmux-claude-code] /stop fallback: C-c×3 未停止, kill-window=${sessionId}`)
            }
          }
        }
      }
    }

    if (!prompt) {
      clearRunning(flagRoot || persisted?.flagRoot || persisted?.cwd || cwd, sessionId)
      return  // empty prompt = interrupt only, send nothing
    }

    // go through the queue path (includes the respawn-if-dead logic)
    await this._queueImpl({
      sessionId,
      prompt,
      cwd: persisted?.cwd ?? undefined,
      flagRoot: persisted?.flagRoot ?? undefined,
      model: persisted?.model ?? undefined,
      useProxy: persisted?.useProxy,
      displayName: persisted?.displayName ?? undefined,
      agentSessionId: persisted?.agentSessionId ?? undefined,
      isInitialContextPrompt: false,
      mobiusPromptRecord,
    })
  }

  // Returns { sessionId, killed, wasWorking } so the caller (the delete route) can raise a notice:
  // killed=true means a live background claude code really was killed; wasWorking=true means it was
  // still in a turn (a running task got forcibly interrupted).
  async _terminateImpl(sessionId: string) {
    const wasAlive = windowExists(sessionId)
    // isWorking re-checks isAlive internally; sample while the window is still there.
    const wasWorking = wasAlive && this.isWorking(sessionId)
    const entry = this.runtime.get(sessionId)
    if (entry?.watch?.stop) { try { entry.watch.stop() } catch {} }
    // Clean up the per-session withproxy (digital-rain token file).
    if (entry?.withProxyPath) { try { fs.unlinkSync(entry.withProxyPath) } catch {} }
    this.runtime.delete(sessionId)
    this._forgetPersisted(sessionId)
    if (wasAlive) {
      tmux(['kill-window', '-t', `${HUB}:${sessionId}`])
      log(`[tmux-claude-code] terminate: killed window=${sessionId} (wasWorking=${wasWorking})`)
    }
    // Also drop the running flag dir (no litter if the agent never removed it)
    const flagRoot = entry?.flagRoot || entry?.cwd
    if (flagRoot) {
      safeRemoveFlagDir(flagRoot, sessionId, 'tmux-claude-code')
    }
    return { sessionId, killed: wasAlive, wasWorking }
  }

  // ── Low-level tmux operations ──────────────────────────
  // Start a new Claude Code tmux window and register its runtime state in memory and on disk.
  async _spawnWindow({ sessionId, cwd, flagRoot, model, useProxy, proxyMode: proxyModeArg, displayName, agentSessionId, settingsPath, captureStream = false, forceNoProxy = false, aimuxRemoteName, enableGulingMcp = false }: ClaudeDispatchOpts) {
    // dispatch-level fields may be null: normalized into non-null working values here (the persisted
    // fallback happens in the caller).
    if (!sessionId || !cwd) throw new Error('_spawnWindow 需要 sessionId + cwd')
    const finalDisplayName = displayName || null
    const finalAgentSid = agentSessionId || null
    const finalFlagRoot = flagRoot || cwd
    // Make sure the tmux hub session hosting agent windows exists.
    ensureHub()
    // The running flag defaults to cwd; a caller-supplied flagRoot (a stable path such as the repo
    // root) wins.
    const effFlagRoot = finalFlagRoot
    // A fresh start uses the caller's session values; with no historical runtime it defaults to no proxy.
    // settingsPath is resolved to an absolute path so later bash commands are not cwd-sensitive.
    let finalSettingsPath = settingsPath ? path.resolve(settingsPath) : null
    // captureStream (digital rain): generate a per-session withproxy carrying sessionId/agent at spawn.
    let withProxyPath: string | null = null
    if (captureStream && finalSettingsPath) {
      try {
        withProxyPath = ensureSessionWithProxy(finalSettingsPath, { sessionId, agent: finalDisplayName })
        finalSettingsPath = withProxyPath
      } catch (e: any) {
        console.warn(`[tmux-claude-code] per-session withproxy 生成失败, 回落原 settings (${sessionId}): ${e?.message || e}`)
        withProxyPath = null
      }
    }
    // When a settings file is given, confirm it really exists before starting.
    if (finalSettingsPath && !fs.existsSync(finalSettingsPath)) {
      // Fail outright on a missing settings file, so Claude never starts silently on the default config.
      throw new Error(`Claude Code settings 文件不存在: ${finalSettingsPath}`)
    }
    // Settings and proxy are independent: the proxy branch passes --settings too.
    const proxyMode = resolveClaudeProxyMode(!!useProxy, !!forceNoProxy, false, proxyModeArg ?? null)
    const finalForceNoProxy = proxyMode.forceNoProxy
    const finalUseProxy = proxyMode.useProxy
    const finalProxyMode = proxyMode.proxyMode
    // With a proxy, check the deps for that mode (env only the env file, proxychains only conf+bin).
    if (finalUseProxy) assertProxyAvailable(finalProxyMode)

    // Resume guard: an old session's jsonl may live outside our path (it came from the old SDK chain).
    // A non-null agentSessionId means the caller wants to resume an old Claude session.
    let useResume = !!finalAgentSid
    // Before resuming, confirm the target jsonl is visible under the current cwd.
    if (useResume && finalAgentSid && !fs.existsSync(jsonlPathOf(cwd, finalAgentSid))) {
      // Warn on a missing jsonl and degrade to a new session.
      console.warn(`[tmux-claude-code] resume target jsonl 不存在 (${agentSessionId}), fallback 为新 session`)
      // Turn the resume path off; a fresh Claude session id is generated below.
      useResume = false
    }
    // resume reuses the old agentSessionId; a new session gets a fresh UUID.
    const claudeSessionId = useResume ? finalAgentSid! : crypto.randomUUID()

    // Tools to disallow: AskUserQuestion/ExitPlanMode/EnterPlanMode permanently (so the agent never
    // stops to wait for a human or gets stuck in plan mode). With the guling live-trading MCP
    // injected, also disallow its real order-placing tools (buy/sell/cancel/switch_account) and keep
    // only the read-only queries (position/balance/orders/settlement/watchlist), so the AI cannot
    // trigger a real trade.
    const disallowedTools = ['AskUserQuestion', 'ExitPlanMode', 'EnterPlanMode']

    // stdio/http MCP servers to inject (a session-level --mcp-config <json-file> with a top-level
    // mcpServers, additive — never --strict-mcp-config — and servers passed via --mcp-config count as
    // explicitly trusted, so no .mcp.json trust dialog appears). One file per session.
    const mcpServers: Record<string, any> = {}
    // TUI sessions (add_remote_aimux_mcp): the aimux stdio MCP, letting claude drive a remote
    // workstation through the remote_* tools (remote_exec_command/write_stdin/apply_patch/view_image/ping).
    if (aimuxRemoteName) {
      mcpServers.aimux = { command: resolveAimuxBin(), args: ['mcp', 'serve', '--remote', aimuxRemoteName] }
    }
    // Xiaomo assistant sessions (enableGulingMcp): the guling live-trading MCP (HTTP), letting claude
    // read funds/positions directly. The token comes from env; unset means resolveGulingMcp() returns
    // null → skip.
    if (enableGulingMcp) {
      const guling = resolveGulingMcp()
      if (guling) {
        mcpServers.guling = guling
        disallowedTools.push('mcp__guling__buy', 'mcp__guling__sell', 'mcp__guling__cancel', 'mcp__guling__switch_account')
      }
    }

    // Assemble the argument list handed to the claude CLI.
    const claudeArgs = [
      // Skip permission prompts so the background agent can act on its own.
      `--dangerously-skip-permissions`,
      `--disallowedTools ${disallowedTools.join(',')}`,
      // --resume for a resume, --session-id to bind a fresh session to a fixed id.
      useResume ? `--resume ${claudeSessionId}` : `--session-id ${claudeSessionId}`,
    ]
    // Append --model (shell-escaped) when the caller pinned a model.
    if (model) claudeArgs.push(`--model ${shellQuote(model)}`)
    // With any MCP server to inject, write the per-session config file and pass it to claude.
    if (Object.keys(mcpServers).length > 0) {
      const mcpConfigPath = path.join(os.tmpdir(), `mobius-mcp-${sessionId}-${crypto.randomUUID().slice(0, 8)}.json`)
      fs.writeFileSync(mcpConfigPath, JSON.stringify({ mcpServers }))
      claudeArgs.push(`--mcp-config ${shellQuote(mcpConfigPath)}`)
    }
    // Prefer the caller's settings file; otherwise the default Mobius Claude settings.
    const settingsArg = finalSettingsPath
      ? `--settings ${shellQuote(finalSettingsPath)}`
      : `--settings "$HOME/.claude/mobiusdefault.settings.json"`

    // bash -lc chain: proxychains only per session config, but the IDE IPC env is always cleared.
    // These are the fragments of the bash -lc command; null entries are filtered out below.
    const cmd = [
      // env mode: load the env-var proxy (new name first, legacy .bash as fallback).
      (finalProxyMode === 'env' || finalProxyMode === 'env_proxychains')
        ? `set -a && (source "$HOME/proxy_envs.conf" 2>/dev/null || source "$HOME/proxy_envs.bash") && set +a`
        : null,
      // Clear VS Code IPC env so the CLI cannot attach to the host IDE by mistake.
      `unset VSCODE_IPC_HOOK_CLI VSCODE_GIT_IPC_HANDLE VSCODE_GIT_ASKPASS_NODE VSCODE_GIT_ASKPASS_MAIN`,
      // Mark the process as running in a controlled sandbox.
      `export IS_SANDBOX=1`,
      // Flush the transcript at turn checkpoints (it used to batch asynchronously every 100ms).
      `export CLAUDE_CODE_EAGER_FLUSH=1`,
      // Mode split: direct/env exec bare; proxychains/env_proxychains wrap in chains. settingsArg
      // must be there in both branches — the proxy branch used to omit --settings, silently dropping
      // a proxied session's settings file (channel/key/permissions/withproxy.json) back to the global
      // default.
      (finalProxyMode === 'proxychains' || finalProxyMode === 'env_proxychains')
        ? `exec proxychains -q -f "$HOME/proxychains_config_for_llm_models.conf" claude ${settingsArg} ${claudeArgs.join(' ')}`
        : `exec claude ${settingsArg} ${claudeArgs.join(' ')}`,
      // Drop empty fragments and join with && so a failed step stops the chain.
    ].filter(Boolean).join(' && ')

    // Main path: pre-set the trust so the TUI never even shows "trust this folder" (screenshot
    // fallback when it fails). Writing the trusted state up front cuts the startup prompts.
    ensureProjectTrusted(cwd)

    // Create the background window in the hub session, running bash -lc cmd inside cwd.
    const r = tmux(['new-window', '-d', '-t', HUB, '-n', sessionId, '-c', cwd, 'bash', '-lc', cmd])
    // Carry stderr out on failure, to pin down command-level problems.
    if (r.status !== 0) throw new Error(`tmux new-window 失败: ${r.stderr}`)
    // Log window, cwd, Claude session, proxy and settings.
    log(`[tmux-claude-code] started: window=${sessionId} cwd=${cwd} claude_session=${claudeSessionId} proxy_mode=${finalProxyMode}${finalSettingsPath ? ` settings=${finalSettingsPath}` : ''}`)

    // Wait for TUI ready (the footer "bypass permissions on" appears)
    // Deadline for the TUI-ready wait.
    const deadline = Date.now() + READY_TIMEOUT_MS
    // Set true once the ready sentinel is seen.
    let ready = false
    // Last auto trust confirm, for rate limiting.
    let lastTrustPress = 0
    // Last auto onboarding confirm, for rate limiting.
    let lastOnboardingPress = 0
    // Last auto API-key confirm, for rate limiting.
    let lastApiKeyPress = 0
    // Last auto bypass-warning confirm, for rate limiting.
    let lastBypassPress = 0
    const target = `${HUB}:${sessionId}`
    // Poll the tmux pane until the deadline.
    while (Date.now() < deadline) {
      // A failed capture counts as an empty screen; the next round retries.
      const { text: screen } = take_tmux_window_text(target, 100)
      // The ready sentinel ends the wait.
      if (screen.includes(READY_SENTINEL)) { ready = true; break }
      // Trust dialog: "❯ 1. Yes, I trust this folder" is already highlighted, so Enter confirms it.
      // The rate-limited re-send covers the TUI occasionally swallowing send-keys Enter, until the
      // dialog goes away. A trust prompt on screen enters the auto-confirm logic.
      if (TRUST_PROMPT_SENTINELS.some(s => screen.includes(s))) {
        // now, to test the next-keypress interval.
        const now = Date.now()
        // Never flood the TUI with Enter.
        if (now - lastTrustPress > TRUST_PRESS_INTERVAL_MS) {
          // Confirm the trusted directory.
          tmux(['send-keys', '-t', `${HUB}:${sessionId}`, 'Enter'])
          // Record the Enter time.
          lastTrustPress = now
          // Note the auto-confirmed trust dialog.
          log(`[tmux-claude-code] window=${sessionId} 检测到目录信任对话框, 已自动确认信任 (cwd=${cwd})`)
        }
      }
      // First-run onboarding dialog (text style, welcome screen): auto-confirm with Enter.
      if (ONBOARDING_PROMPT_SENTINELS.some(s => screen.includes(s))) {
        const now = Date.now()
        if (now - lastOnboardingPress > ONBOARDING_PRESS_INTERVAL_MS) {
          tmux(['send-keys', '-t', `${HUB}:${sessionId}`, 'Enter'])
          lastOnboardingPress = now
          log(`[tmux-claude-code] window=${sessionId} 检测到首次启动引导对话框, 已自动确认`)
        }
      }
      // "Detected a custom API key" dialog: press "1" to use the env key.
      if (API_KEY_PROMPT_SENTINELS.some(s => screen.includes(s))) {
        const now = Date.now()
        if (now - lastApiKeyPress > API_KEY_PRESS_INTERVAL_MS) {
          tmux(['send-keys', '-t', `${HUB}:${sessionId}`, '1'])
          tmux(['send-keys', '-t', `${HUB}:${sessionId}`, 'Enter'])
          lastApiKeyPress = now
          log(`[tmux-claude-code] window=${sessionId} 检测到 API Key 对话框, 已自动选择使用环境变量 Key`)
        }
      }
      // "Bypass Permissions mode" warning: "2" + Enter accepts (option 1 = No/exit, option 2 = Yes/accept).
      if (BYPASS_WARN_SENTINELS.some(s => screen.includes(s))) {
        const now = Date.now()
        if (now - lastBypassPress > BYPASS_WARN_INTERVAL_MS) {
          tmux(['send-keys', '-t', `${HUB}:${sessionId}`, '2'])
          tmux(['send-keys', '-t', `${HUB}:${sessionId}`, 'Enter'])
          lastBypassPress = now
          log(`[tmux-claude-code] window=${sessionId} 检测到 Bypass Permissions 警告, 已自动确认接受`)
        }
      }
      // Wait one poll interval, then look at the screen again.
      await new Promise(r => setTimeout(r, READY_POLL_MS))
    }
    // Not ready in time: clean up the window just created and throw.
    if (!ready) {
      // Never leave an unusable background window behind.
      tmux(['kill-window', '-t', `${HUB}:${sessionId}`])
      // Hand the timeout and cwd to the caller.
      throw new Error(`claude TUI 未在 ${READY_TIMEOUT_MS}ms 内 ready (cwd=${cwd}).`)
    }
    // The TUI is usable.
    log(`[tmux-claude-code] window=${sessionId} TUI ready`)

    // jsonl path for this Claude session.
    const jp = jsonlPathOf(cwd, claudeSessionId)
    // Write the window's runtime state into the in-memory map.
    this.runtime.set(sessionId, {
      // claude's internal session id.
      agentSessionId: claudeSessionId,
      // cwd, flag root, model and proxy settings.
      cwd, flagRoot: effFlagRoot, model: model || null, useProxy: finalUseProxy, proxyMode: finalProxyMode,
      // settings, per-session withproxy, force-no-proxy and display name.
      settingsPath: finalSettingsPath, withProxyPath, captureStream: !!captureStream, forceNoProxy: finalForceNoProxy, displayName: displayName || null,
      // jsonl path, start time and the watcher placeholder.
      jsonlPath: jp, startedAt: Date.now(), watch: null,
    })
    // Persist the same core state so a service restart can restore it.
    this._persistEntry(sessionId, {
      // Persist claude's session id, cwd and flag root.
      agentSessionId: claudeSessionId, cwd, flagRoot: effFlagRoot,
      // Persist model, proxy, settings and display name.
      model: model || null, useProxy: finalUseProxy,
      settingsPath: finalSettingsPath, withProxyPath, captureStream: !!captureStream, forceNoProxy: finalForceNoProxy, displayName: displayName || null,
      // Persist jsonl path and start time.
      jsonlPath: jp, startedAt: Date.now(),
    })
    // Start the jsonl watcher so agent output keeps flowing in.
    this._ensureWatcher(sessionId)

    // Window start drops the running flag; every prompt submission refreshes it and the agent removes
    // it on completion per the session-context hint. flagRoot is anchored at the repo root (≠ cwd
    // inside a worktree), so rebuilding the worktree cannot delete the flag. Foreign logic reads the
    // flag to know the session is running.
    markRunning(effFlagRoot, sessionId)
  }

  // tmux load-buffer + paste-buffer -p + marker landing probe + spaced re-send Enter×3.
  // -p (bracketed paste) is mandatory: otherwise a \n inside the text is read as Return, a multi-line
  // message submits early at the first newline and the rest plus the trailing explicit Enter become a
  // second message (the root cause of multi-line splits). -p was removed historically because the
  // explicit Enter after it was occasionally swallowed → fixed by the confirm-style re-send.
  async _sendMaybeInitialContextPrompt(sessionId: string, text: string, isInitialContextPrompt?: boolean) {
    if (!isInitialContextPrompt) {
      await this._sendPromptToWindow(sessionId, text)
      return
    }

    const plan = pickInitialContextPlan()
    if (plan === 'greeting_then_context') {
      const greeting = pickInitialContextGreeting()
      log(`[tmux-claude-code] initial context plan=${plan} greeting=${JSON.stringify(greeting)} delay_ms=${INITIAL_CONTEXT_DELAY_MS}`)
      await this._sendPromptToWindow(sessionId, greeting)
      await sleep(INITIAL_CONTEXT_DELAY_MS)
      await this._sendPromptToWindow(sessionId, text)
      return
    }

    if (plan === 'delay_then_context') {
      log(`[tmux-claude-code] initial context plan=${plan} delay_ms=${INITIAL_CONTEXT_DELAY_MS}`)
      await sleep(INITIAL_CONTEXT_DELAY_MS)
      await this._sendPromptToWindow(sessionId, text)
      return
    }

    log(`[tmux-claude-code] initial context plan=${plan}`)
    await this._sendPromptToWindow(sessionId, text)
  }

  async _sendPromptToWindow(sessionId: string, text: string) {
    if (!windowExists(sessionId)) {
      throw new Error(`window ${sessionId} 不存在`)
    }

    const marker = findAsciiTailMarker(text)
    log(`[tmux-claude-code] sendPrompt window=${sessionId} len=${text.length} marker=${marker ? JSON.stringify(marker) : '(none)'}`)

    const bufName = `imac_${process.pid}_${Date.now()}`
    const r1 = tmux(['load-buffer', '-b', bufName, '-'], { input: text })
    if (r1.status !== 0) throw new Error(`tmux load-buffer 失败: ${r1.stderr}`)

    const r2 = tmux(['paste-buffer', '-p', '-d', '-b', bufName, '-t', `${HUB}:${sessionId}`])
    if (r2.status !== 0) {
      tmux(['delete-buffer', '-b', bufName])
      throw new Error(`tmux paste-buffer 失败: ${r2.stderr}`)
    }

    if (marker) {
      // probe for the marker (= the paste really landed in the TUI input box)
      const deadline = Date.now() + PASTE_PROBE_TIMEOUT_MS
      let saw = false
      while (Date.now() < deadline) {
        await new Promise(r => setTimeout(r, PASTE_PROBE_INTERVAL_MS))
        const pane = tmux(['capture-pane', '-pt', `${HUB}:${sessionId}`, '-p', '-S', '-80'])
        if (pane.status === 0 && pane.stdout.includes(marker)) { saw = true; break }
      }
      if (!saw) console.warn(`[tmux-claude-code] paste marker 未出现 (${PASTE_PROBE_TIMEOUT_MS}ms 内), Enter 仍发送`)
    } else {
      // no ASCII marker → fall back to sleep, scaled linearly with text length
      const sleepMs = Math.min(PASTE_SLEEP_MAX_MS, Math.max(PASTE_SLEEP_BASE_MS, Math.floor(text.length * 0.5)))
      await new Promise(r => setTimeout(r, sleepMs))
    }

    // Submit: bracketed paste (-p) is atomic so extra trailing Enters never split the message, while
    // the TUI's input-mode switch occasionally swallows the first one — hence the same idempotent idea
    // as "C-c×3 / trust-dialog re-press": re-send N times with a gap. Once submitted the box is empty
    // and a spare Enter is a no-op in the claude TUI.
    for (let i = 0; i < SUBMIT_ENTER_ATTEMPTS; i++) {
      const r = tmux(['send-keys', '-t', `${HUB}:${sessionId}`, 'Enter'])
      if (r.status !== 0) throw new Error(`tmux send-keys Enter 失败: ${r.stderr}`)
      if (i < SUBMIT_ENTER_ATTEMPTS - 1) await new Promise(r => setTimeout(r, SUBMIT_ENTER_INTERVAL_MS))
    }

    recordPromptPaste({ backendName: this.name, sessionId, contentLength: text.length })
  }
}

module.exports = {
  TmuxClaudeCodeBackend,
  HUB,
  encodeCwd,
  jsonlPathOf,
  runningFlagPathOf,
  failedFlagPathOf,
  findClaudeRealTimeInfo,
  detectDangerPermission,
  isCompactCompletionUserEvent,
  resolveClaudeProxyMode,
}

// marker: make this file a module (top-level declarations file-private) for tsc
export {}
