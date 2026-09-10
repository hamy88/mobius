/**
 * agent-history-store.js — 历史存储 (agent-history-store.db) 的行为测试.
 *
 * 覆盖: 开轮/归组、排除串、幂等 sync、backfill 迁移归并 (含 task_state 载体)、
 * pending_round_openers 卡住态自愈、文件变小报错、删除级联、事件订阅.
 */
const assert = require('assert')
const fs = require('fs')
const os = require('os')
const path = require('path')

// 必须在 require 服务之前设置: 库路径在模块加载时定死.
process.env.MOBIUS_AGENT_HISTORY_STORE_PATH = path.join(os.tmpdir(), `agent-history-store-test-${process.pid}.db`)

const store = require('../backend/services/mobius-agent-history')

const BASE_MS = Date.parse('2026-01-01T00:00:00Z')
function iso(stepSec) { return new Date(BASE_MS + stepSec * 1000).toISOString() }

function nativeEntry(stepSec, extra = {}) {
  return {
    type: 'assistant',
    uuid: `nat-${stepSec}-${Math.random().toString(36).slice(2, 8)}`,
    timestamp: iso(stepSec),
    message: { role: 'assistant', content: [{ type: 'text', text: `原生输出 ${stepSec}` }] },
    ...extra,
  }
}

function legacyUserCard(content, stepSec) {
  return {
    type: 'user',
    uuid: `leg-${stepSec}`,
    timestamp: iso(stepSec),
    message: { role: 'user', content },
    entrypoint: 'mobius',
    mobius: { schema_version: 1, source: 'session.send', kind: 'user_input' },
  }
}

function writeLines(file, entries) {
  fs.mkdirSync(path.dirname(file), { recursive: true })
  fs.writeFileSync(file, entries.map((e) => JSON.stringify(e)).join('\n') + '\n')
}

function appendLines(file, entries) {
  fs.appendFileSync(file, entries.map((e) => JSON.stringify(e)).join('\n') + '\n')
}

async function main() {
  // ── A. 新会话: 发送链路开轮, 原生行归当前组 ──────────────────────────
  const dirA = fs.mkdtempSync(path.join(os.tmpdir(), 'ahs-a-'))
  const jsonlA = path.join(dirA, 'sess-a.jsonl')
  const sidA = `test-ahs-a-${process.pid}`

  const events = []
  const unsub = store.subscribeSessionEvents(sidA, (ev) => events.push(ev))

  assert.strictEqual(store.writeMobiusCoreEntry({ sessionId: sidA, content: '第一问', primaryPath: jsonlA }), true)
  let g = store.getGroups(sidA)
  assert.strictEqual(g.groups.length, 1, '开轮: 恰好一组')
  assert.strictEqual(g.groups[0].seq, 1)
  assert.strictEqual(g.groups[0].user_summary, '第一问')
  assert.strictEqual(g.groups[0].version, 1)
  assert.strictEqual(g.groups[0].entry_count, 1)
  assert.ok(events.some((e) => e.type === 'group_created' && e.payload.group.user_summary === '第一问'), 'group_created 事件')

  appendLines(jsonlA, [nativeEntry(10), nativeEntry(20)])
  let r = store.syncSession(sidA, jsonlA)
  assert.strictEqual(r.ok, true)
  g = store.getGroups(sidA)
  assert.strictEqual(g.groups[0].entry_count, 3, '原生行归当前组')
  let ge = store.getGroupEntries(sidA, 1)
  assert.strictEqual(ge.entries.length, 3)
  assert.strictEqual(ge.entries[0].message.content, '第一问', '开轮卡在组内首位')
  assert.strictEqual(ge.version, 3)
  assert.ok(events.some((e) => e.type === 'entries' && e.payload.group_id === '1' && e.payload.entries.length === 2), 'entries 事件')

  // 幂等: 重复 sync 不重复落条
  store.syncSession(sidA, jsonlA)
  assert.strictEqual(store.getGroups(sidA).groups[0].entry_count, 3, 'sync 幂等')

  // ── B. 排除串: 系统提醒不开轮, 归当前组 ──────────────────────────────
  store.writeMobiusCoreEntry({ sessionId: sidA, content: '[Research Blackboard 更新提醒] 新消息到达', primaryPath: jsonlA })
  store.writeMobiusCoreEntry({ sessionId: sidA, content: 'It seems that the running flag is still present, did you hit problems?', primaryPath: jsonlA })
  g = store.getGroups(sidA)
  assert.strictEqual(g.groups.length, 1, '排除串不开轮')
  ge = store.getGroupEntries(sidA, 1)
  assert.strictEqual(ge.entries.length, 5, '排除串条目归当前组')

  // ── 第二轮 ──────────────────────────────────────────────────────────
  store.writeMobiusCoreEntry({ sessionId: sidA, content: '第二问', primaryPath: jsonlA })
  appendLines(jsonlA, [nativeEntry(30)])
  store.syncSession(sidA, jsonlA)
  g = store.getGroups(sidA)
  assert.strictEqual(g.groups.length, 2)
  assert.strictEqual(g.groups[1].user_summary, '第二问')
  assert.strictEqual(g.groups[1].entry_count, 2)
  assert.ok(g.session_version > 0)

  unsub()

  // ── E. 文件变小: 报错并持久化 ────────────────────────────────────────
  fs.writeFileSync(jsonlA, '{"type":"assistant"}\n')
  r = store.syncSession(sidA, jsonlA)
  assert.strictEqual(r.ok, false, '文件变小 → sync 失败')
  assert.ok(String(r.error).includes('变小'), '错误信息')
  r = store.syncSession(sidA, jsonlA)
  assert.strictEqual(r.ok, false, 'error 持久化: 后续 sync 仍失败')
  assert.strictEqual(store.getGroups(sidA).groups.length, 2, '报错后元数据仍可读')

  // ── C. backfill: 冻结 legacy 文件与原生轨按时间戳归并切分 ────────────
  const dirC = fs.mkdtempSync(path.join(os.tmpdir(), 'ahs-c-'))
  const jsonlC = path.join(dirC, 'sess-c.jsonl')
  const legacyC = jsonlC.replace(/\.jsonl$/, '.mobius.jsonl')
  const sidC = `test-ahs-c-${process.pid}`

  writeLines(legacyC, [
    legacyUserCard('旧问题一', 10),
    { type: 'task_state', uuid: 'leg-ts-1', timestamp: iso(25), message: { role: 'user', content: '' }, entrypoint: 'mobius', mobius: { kind: 'task_state', anchor_uuid: 'nat-20', tasks: [] } },
    legacyUserCard('旧问题二', 50),
    legacyUserCard('[Research Blackboard 更新提醒] 不开轮', 70),
  ])
  writeLines(jsonlC, [
    nativeEntry(20),
    nativeEntry(40),
    nativeEntry(60),
    nativeEntry(80),
  ])
  r = store.syncSession(sidC, jsonlC)
  assert.strictEqual(r.ok, true)
  g = store.getGroups(sidC)
  assert.strictEqual(g.groups.length, 2, 'legacy 两张开轮卡 → 两组 (排除串不算)')
  assert.strictEqual(g.groups[0].user_summary, '旧问题一')
  // 归并序: ts40 原生行早于旧问题二(ts50) 的开轮卡 → 归组1
  assert.strictEqual(g.groups[0].entry_count, 4, '组1 = 开轮卡 + ts20 + ts25 task_state + ts40')
  assert.strictEqual(g.groups[1].entry_count, 4, '组2 = 开轮卡 + ts60 + ts70 排除串 + ts80')
  ge = store.getGroupEntries(sidC, 1)
  assert.strictEqual(ge.entries[0].message.content, '旧问题一')
  assert.strictEqual(ge.entries[1].timestamp, iso(20))
  assert.strictEqual(ge.entries[2].type, 'task_state')
  assert.strictEqual(ge.entries[3].timestamp, iso(40))
  // legacy 文件只读一次: 再 sync 只读原生增量
  appendLines(jsonlC, [nativeEntry(100)])
  store.syncSession(sidC, jsonlC)
  assert.strictEqual(store.getGroups(sidC).groups[1].entry_count, 5)

  // getHistorySnapshot: 全量按到达序 (assistant 快照/标题扫描的兼容读法)
  const snap = store.getHistorySnapshot(sidC, jsonlC)
  assert.ok(snap.entries.length >= 8)
  assert.ok(snap.entries[0].message && snap.entries[0].message.content === '旧问题一')

  // ── D. pending_round_openers 卡住态自愈 ──────────────────────────────
  const dirD = fs.mkdtempSync(path.join(os.tmpdir(), 'ahs-d-'))
  const jsonlD = path.join(dirD, 'sess-d.jsonl')
  const sidD = `test-ahs-d-${process.pid}`
  writeLines(jsonlD, [nativeEntry(5)])
  store.syncSession(sidD, jsonlD) // 建状态行 + pre 组

  const Database = require('better-sqlite3')
  const raw = new Database(process.env.MOBIUS_AGENT_HISTORY_STORE_PATH)
  const openerJson = JSON.stringify({
    type: 'user', uuid: 'pend-opener-1', timestamp: iso(1),
    message: { role: 'user', content: '卡住的开轮' }, entrypoint: 'mobius', mobius: { kind: 'user_input' },
  })
  raw.prepare(`INSERT INTO entries (session_id, uuid, seq, group_seq, seq_in_group, round_opener, origin, ts, json)
               VALUES (?, 'pend-opener-1', 999, NULL, NULL, 1, 'direct', ?, ?)`)
    .run(sidD, BASE_MS + 1000, openerJson)
  raw.prepare('UPDATE ingest_state SET pending_round_openers = ? WHERE session_id = ?').run('["pend-opener-1"]', sidD)
  raw.close()

  r = store.syncSession(sidD, jsonlD)
  assert.strictEqual(r.ok, true)
  g = store.getGroups(sidD)
  const recovered = g.groups.find((x) => x.user_summary === '卡住的开轮')
  assert.ok(recovered, 'pending 自愈: 组已开出')
  ge = store.getGroupEntries(sidD, recovered.seq)
  assert.strictEqual(ge.entries[0].uuid, 'pend-opener-1')

  // ── F. task 快照: 原生 TaskCreate 行触发 task_state 载体落库 ─────────
  const dirF = fs.mkdtempSync(path.join(os.tmpdir(), 'ahs-f-'))
  const jsonlF = path.join(dirF, 'sess-f.jsonl')
  const sidF = `test-ahs-f-${process.pid}`
  store.writeMobiusCoreEntry({ sessionId: sidF, content: '带任务的轮', primaryPath: jsonlF })
  writeLines(jsonlF, [
    {
      type: 'assistant', uuid: 'nat-task-1', timestamp: iso(1),
      message: { role: 'assistant', content: [{ type: 'tool_use', id: 'tu-1', name: 'TaskCreate', input: { subject: '任务甲', description: '描述' } }] },
    },
  ])
  store.syncSession(sidF, jsonlF)
  ge = store.getGroupEntries(sidF, 1)
  const snapshot = ge.entries.find((e) => e.type === 'task_state')
  assert.ok(snapshot, 'TaskCreate 行后落 task_state 快照')
  assert.strictEqual(snapshot.mobius.anchor_uuid, 'nat-task-1')
  assert.ok(snapshot.mobius.tasks.some((t) => t.subject === '任务甲'))

  // ── G. 删除级联 ──────────────────────────────────────────────────────
  store.deleteSessionData(sidA)
  assert.strictEqual(store.getGroups(sidA).groups.length, 0)
  assert.strictEqual(store.getGroupEntries(sidA, 1), null)

  console.log('✅ agent-history-store: all assertions passed')
}

main().then(
  () => { try { fs.rmSync(process.env.MOBIUS_AGENT_HISTORY_STORE_PATH, { force: true }) } catch {} process.exit(0) },
  (e) => { console.error(e); process.exit(1) },
)
