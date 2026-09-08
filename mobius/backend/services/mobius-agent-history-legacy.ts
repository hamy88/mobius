/**
 * mobius-agent-history-legacy.ts — 懒迁移 (backfill): 冻结的旧 .mobius.jsonl → agent-history-store.
 *
 * 独立文件存在的意义: 迁移是有寿命的代码, 全部会话迁移完成后按此清单整体删除:
 *   1. 删除本文件
 *   2. mobius-agent-history.ts 中 grep "[legacy-migration]" 的调用点一并删掉
 *   3. ingest_state.legacy_read_bytes 列成为死列 (无害, 可留)
 *   4. tests/agent-history-store.js 的 backfill 场景 (场景 C) 一并删掉
 *
 * 依赖方向: 本文件是叶子模块 (只依赖 fs + jsonl-watcher), 主模块单方向引用本文件,
 * 删除时不牵连任何常驻逻辑.
 */
import * as fs from 'fs'
import * as watcher from './jsonl-watcher'
import type { PendingRow } from './mobius-agent-history'

// 与主模块同步的小副本 (避免运行时循环依赖; 各自 ~4 行, 改动须两处同步):
function legacyPathOf(jsonlPath: string): string {
  return jsonlPath.endsWith('.jsonl')
    ? jsonlPath.slice(0, -'.jsonl'.length) + '.mobius.jsonl'
    : jsonlPath + '.mobius.jsonl'
}

// 排除串: 与主模块 isExcludedSystemReminder / 前端 jsonl-round-helpers.ts 同源, 改动须三处同步.
const BLACKBOARD_MARKER = '[Research Blackboard 更新提醒]'
const RUNNING_FLAG_MARKER = 'It seems that the running flag is still present'

function parseTimestampMs(entry: any): number | null {
  const candidates = [entry?.timestamp, entry?.created_at, entry?.payload?.timestamp, entry?.message?.created_at]
  for (const raw of candidates) {
    if (!raw) continue
    const ms = new Date(raw).getTime()
    if (Number.isFinite(ms)) return ms
  }
  return null
}

interface LegacyItem {
  entry: any
  json: string
  ts: number | null
  opener: boolean
}

export interface LegacyBackfill {
  /** 取出 ts 严格早于原生行时间戳的迁移条目 (同刻原生优先, 与归并序一致). */
  takeUpTo(nativeTsMs: number | null): PendingRow[]
  /** 扫描结束后取出剩余全部迁移条目. */
  takeAll(): PendingRow[]
  /** 旧文件里已有该锚点的 task_state 快照 → 累积器再产出的同锚点快照跳过 (防双份). */
  hasSnapshotAnchor(anchorUuid: string): boolean
  /** 迁移源已消费字节 (书签落点). */
  consumedBytes(): number
}

/**
 * 读冻结的旧 .mobius.jsonl 全量, 产出按时间戳排序的迁移队列.
 * 文件不存在 / 为空 → null (该会话无迁移源, 纯原生轨扫描).
 * user_input/compact 卡 (排除两个系统提醒串) 标记为开轮行, 其余为普通成员行.
 */
export function loadLegacyBackfill(primaryPath: string): LegacyBackfill | null {
  const legacyPath = legacyPathOf(primaryPath)
  if (!legacyPath || !fs.existsSync(legacyPath)) return null
  const r = watcher.readAll(legacyPath, { maxLines: 5_000_000, tailCount: 0 })
  const items: LegacyItem[] = []
  const anchors = new Set<string>()
  for (const entry of r.entries || []) {
    const kind = entry?.mobius?.kind
    const content = entry?.message?.content
    const text = typeof content === 'string' ? content : ''
    const opener = (kind === 'user_input' || kind === 'compact')
      && !(text.includes(BLACKBOARD_MARKER) || text.includes(RUNNING_FLAG_MARKER))
    if (entry?.type === 'task_state' && typeof entry?.mobius?.anchor_uuid === 'string') {
      anchors.add(entry.mobius.anchor_uuid)
    }
    items.push({ entry, json: JSON.stringify(entry), ts: parseTimestampMs(entry), opener })
  }
  if (items.length === 0) return null
  items.sort((a, b) => (a.ts ?? 0) - (b.ts ?? 0))

  let cursor = 0
  const toRow = (item: LegacyItem): PendingRow => ({
    entry: item.entry,
    json: item.json,
    origin: 'legacy',
    roundOpener: item.opener,
    ts: item.ts,
  })

  return {
    takeUpTo(nativeTsMs: number | null): PendingRow[] {
      const out: PendingRow[] = []
      while (cursor < items.length) {
        const head = items[cursor]
        if (head.ts == null) break
        if (nativeTsMs != null && !(head.ts < nativeTsMs)) break
        cursor++
        out.push(toRow(head))
      }
      return out
    },
    takeAll(): PendingRow[] {
      const out: PendingRow[] = []
      while (cursor < items.length) {
        out.push(toRow(items[cursor]))
        cursor++
      }
      return out
    },
    hasSnapshotAnchor(anchorUuid: string): boolean {
      return anchors.has(anchorUuid)
    },
    consumedBytes(): number {
      try { return fs.statSync(legacyPath).size } catch { return 0 }
    },
  }
}
