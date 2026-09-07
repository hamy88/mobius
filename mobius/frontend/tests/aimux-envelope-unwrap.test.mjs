/**
 * aimux-envelope-unwrap.test.mjs — 验证 aimux exec 信封在代码模式卡片里的解包.
 *
 * 用户报告: 代码模式卡片的具体内容是
 *   stdout: {"output": "...", "original_token_count": 360, "wall_time_seconds": 6.424, "exit_code": 0}
 * 这种字段类型时, 应该把输出正文提取出来, 不然换行都有问题.
 *
 * 场景 = assistant 的 mcp__aimux__remote_exec_command tool_use + 其后的 type:user 纯 tool_result
 * (block content 是信封 JSON 串). mergeBashToolResultItems 把结果合并回发起方卡片,
 * BashResultPanel 拿到的 stdout 必须是解包后的 output 正文 (真实换行), 且 meta 带执行元信息.
 */
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import { pathToFileURL } from 'node:url'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const srcPath = path.resolve(__dirname, '../src/components/viewer/entry-extract.ts')

const bundled = await build({
  entryPoints: [srcPath],
  bundle: true,
  format: 'esm',
  target: 'node18',
  write: false,
  logLevel: 'silent',
})
const code = bundled.outputFiles[0].text
const dataUrl = 'data:text/javascript;base64,' + Buffer.from(code).toString('base64')
const mod = await import(dataUrl)

const { extractBashToolResultRecords, mergeBashToolResultItems, extractMcpToolResult } = mod

let passed = 0
let failed = 0
function test(name, fn) {
  try {
    fn()
    passed++
    console.log(`  ✓ ${name}`)
  } catch (err) {
    failed++
    console.error(`  ✗ ${name}`)
    console.error(`    ${err.message}`)
    if (process.env.VERBOSE) console.error(err.stack)
  }
}

// ── 用户报告的真实样例 (dlc 机器上的 vllm 调试输出, 含 traceback + 中文) ──────
const realOutput = [
  'root@dlc6zqkne3sdpcak-master-0:~# cd /mnt/workspace/vllm_dp && source /etc/profile.d/ppu_env.sh 2>/dev/null; python3 - <<\'PYEOF\' 2>&1 | tail -40',
  '',
  '> import json',
  '',
  'Traceback (most recent call last):',
  '  File "<stdin>", line 8, in <module>',
  'json.decoder.JSONDecodeError: Extra data: line 1 column 132 (char 131)',
  'root@dlc6zqkne3sdpcak-master-0:/mnt/workspace/vllm_dp# echo "RC=$?"; echo __AIMUX_EXIT_ba555720__:$?',
  '',
  'RC=0',
  '',
].join('\n')
const realEnvelope = JSON.stringify({
  output: realOutput,
  original_token_count: 360,
  wall_time_seconds: 6.424,
  exit_code: 0,
})

// assistant 发起 mcp__aimux__remote_exec_command (与线上真实 JSONL 同构)
const toolUseEntry = {
  type: 'assistant',
  uuid: 'assistant-uuid-1',
  message: {
    role: 'assistant',
    content: [
      {
        type: 'tool_use',
        name: 'mcp__aimux__remote_exec_command',
        id: 'call_02_x4BXprxURQZWn0C2TKOz4506',
        input: { cmd: "python3 - <<'PYEOF'", workdir: '/mnt/workspace/vllm_dp' },
      },
    ],
  },
}

// 其后的 type:user 纯 tool_result entry (block content = 信封 JSON 串)
function envelopeResultEntry(text) {
  return {
    type: 'user',
    uuid: 'result-uuid-1',
    parentUuid: 'assistant-uuid-1',
    message: {
      role: 'user',
      content: [
        {
          type: 'tool_result',
          tool_use_id: 'call_02_x4BXprxURQZWn0C2TKOz4506',
          content: [{ type: 'text', text }],
        },
      ],
    },
    toolUseResult: [{ type: 'text', text }],
  }
}

test('extractBashToolResultRecords: 信封 tool_result 解包 — stdout 是 output 正文而非转义 JSON', () => {
  const records = extractBashToolResultRecords(envelopeResultEntry(realEnvelope), 2)
  assert.equal(records.length, 1)
  const r = records[0]
  assert.equal(r.stdout, realOutput)
  assert.equal(r.content, realOutput)
  // 换行必须是真实换行, 不能是 \n 字面量
  assert.ok(r.stdout.includes('\n'), 'stdout 必须含真实换行')
  assert.ok(!r.stdout.includes('\\n'), 'stdout 不能含转义 \\n 字面量')
  assert.ok(!r.stdout.startsWith('{"output"'), 'stdout 不能还是原始 JSON 信封')
})

test('extractBashToolResultRecords: 信封解包后 meta 带耗时/tokens/exit 元信息', () => {
  const records = extractBashToolResultRecords(envelopeResultEntry(realEnvelope), 2)
  const meta = records[0].meta || []
  const labels = meta.map((m) => `${m.label}=${m.value}`)
  assert.ok(labels.includes('耗时=6.424s'), `meta 应含 耗时=6.424s, 实际: ${labels}`)
  assert.ok(labels.includes('tokens=360'), `meta 应含 tokens=360, 实际: ${labels}`)
  assert.ok(labels.includes('exit=0'), `meta 应含 exit=0, 实际: ${labels}`)
})

test('extractBashToolResultRecords: exit_code 非 0 → isError=true', () => {
  const failing = JSON.stringify({ output: 'boom', original_token_count: 1, wall_time_seconds: 0.1, exit_code: 1 })
  const records = extractBashToolResultRecords(envelopeResultEntry(failing), 2)
  assert.equal(records[0].isError, true)
})

test('extractBashToolResultRecords: exit_code=0 → isError=false', () => {
  const records = extractBashToolResultRecords(envelopeResultEntry(realEnvelope), 2)
  assert.equal(records[0].isError, false)
})

test('mergeBashToolResultItems: 信封结果合并回发起方卡片, 拿到的是解包正文', () => {
  const items = mergeBashToolResultItems([toolUseEntry, envelopeResultEntry(realEnvelope)], 0)
  // 纯 tool_result entry 被合并隐藏, 只剩发起方
  assert.equal(items.length, 1)
  assert.equal(items[0].bashResults.length, 1)
  const r = items[0].bashResults[0]
  assert.equal(r.stdout, realOutput)
  assert.ok(r.stdout.includes('\n'), '合并结果也必须是解包后的真实换行文本')
  assert.ok(!r.stdout.includes('\\n'), '合并结果不能是转义 JSON')
  assert.ok((r.meta || []).some((m) => m.label === 'exit' && m.value === '0'))
})

test('extractBashToolResultRecords: 普通非信封 tool_result 不受影响 (原样保留)', () => {
  const plain = 'plain output\nsecond line'
  const records = extractBashToolResultRecords(envelopeResultEntry(plain), 2)
  assert.equal(records[0].stdout, '')
  assert.equal(records[0].content, plain)
  assert.equal(records[0].meta, undefined)
})

test('extractBashToolResultRecords: {"output":"..."} 无元信息字段不误判 (结构化数据保护)', () => {
  const notEnvelope = JSON.stringify({ output: 'structured', rows: 3 })
  const records = extractBashToolResultRecords(envelopeResultEntry(notEnvelope), 2)
  assert.equal(records[0].content, notEnvelope, '无 exec 元信息字段的结构化 JSON 不解包')
  assert.equal(records[0].meta, undefined)
})

test('extractBashToolResultRecords: 非 JSON 纯文本 (含 { 开头结尾的边缘) 不误判', () => {
  const weird = '{ not json at all }'
  const records = extractBashToolResultRecords(envelopeResultEntry(weird), 2)
  assert.equal(records[0].content, weird)
})

test('codex function_call_output 路径: 信封同样解包', () => {
  const codexResult = {
    type: 'response_item',
    uuid: 'codex-result-1',
    payload: {
      type: 'function_call_output',
      call_id: 'call_9',
      output: realEnvelope,
    },
  }
  const records = extractBashToolResultRecords(codexResult, 3)
  assert.equal(records.length, 1)
  assert.equal(records[0].stdout, realOutput)
  assert.ok((records[0].meta || []).some((m) => m.label === '耗时'))
})

test('extractMcpToolResult: 独立返回卡 (emerald) 的 meta 现在也含 exit', () => {
  const mcp = extractMcpToolResult(envelopeResultEntry(realEnvelope))
  assert.ok(mcp, '独立信封卡仍应命中')
  const labels = (mcp.meta || []).map((m) => `${m.label}=${m.value}`)
  assert.ok(labels.includes('exit=0'), `独立卡 meta 也应含 exit=0, 实际: ${labels}`)
})

// Windows 路径 + ANSI 控制序列的真实线上样例 (来自 cute-mountain 会话)
test('Windows GBK 乱码 + 反斜杠路径信封: 解包后反斜杠保留原样', () => {
  const winOutput = 'C:\\Users\\fuqingxu-hub\\Desktop\\MobiusOS>dir /b\nagentjet-latex\nBest API'
  const winEnvelope = JSON.stringify({ output: winOutput, original_token_count: 269, wall_time_seconds: 0.408, exit_code: 0 })
  const records = extractBashToolResultRecords(envelopeResultEntry(winEnvelope), 2)
  assert.equal(records[0].stdout, winOutput)
  assert.ok(records[0].stdout.includes('C:\\Users'), '反斜杠路径保留原样')
})

console.log(`\n${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
