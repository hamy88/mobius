/**
 * viewer/display-dedup.ts — 展示层序列去重 (纯函数, 无 React 依赖).
 *
 * 旧"次要条目过滤" (session-jsonl-filter.ts) 里的类型判定已并入
 * entry-classify 的 isHiddenJsonlNoiseEntry (统一为无开关的一层);
 * 这里只保留其中需要序列上下文的三条展示规则, 作为常驻去重:
 *   1. 连续重复条目折叠 (claude-code 偶发逐字节重写)
 *   2. codex event_msg.user_message 镜像已见过的 user 卡 → 藏镜像
 *   3. mobius 原文卡与原生 user 卡同文 → 藏 mobius 卡 (双轨记录的展示合并)
 */
import type { AnyEntry } from './types'

// 序号类字段不参与重复判定 (行号每次读取都变).
const JSONL_SEQUENCE_KEYS = new Set(['line_no', 'lineNo', '_line_no', '_lineNo', '__line_no', '__lineNo'])

function normalizeForDuplicateCheck(value: any, depth = 0): any {
  if (value === null || typeof value !== 'object') return value
  if (Array.isArray(value)) return value.map((item) => normalizeForDuplicateCheck(item, depth + 1))
  const out: Record<string, any> = {}
  for (const key of Object.keys(value).sort()) {
    if (depth === 0 && JSONL_SEQUENCE_KEYS.has(key)) continue
    out[key] = normalizeForDuplicateCheck(value[key], depth + 1)
  }
  return out
}

function duplicateSignature(entry: any): string {
  try {
    const encoded = JSON.stringify(normalizeForDuplicateCheck(entry))
    return typeof encoded === 'string' ? encoded : String(entry)
  } catch {
    return String(entry)
  }
}

function userContentOf(entry: any): string | null {
  if (entry?.type !== 'user') return null
  const content = entry?.message?.content
  return typeof content === 'string' ? content : null
}

function entryHasMobiusField(entry: any): boolean {
  return Boolean(entry && Object.prototype.hasOwnProperty.call(entry, 'mobius') && entry.mobius)
}

/**
 * 输入一段按序条目, 返回去重后的条目 (顺序不变).
 * 调用方: 普通视图按组各跑一次; 简易视图对窗口跑一次.
 */
export function filterDisplayDuplicates(entries: AnyEntry[]): AnyEntry[] {
  // 先全量收集"原生 user 卡"的文本, 供 mobius 重复卡判定 (双卡可能乱序到达).
  const plainUserContents = new Set<string>()
  for (const entry of entries) {
    if (entry?.type === 'user' && !entryHasMobiusField(entry)) {
      const content = userContentOf(entry)
      if (content !== null) plainUserContents.add(content)
    }
  }

  const out: AnyEntry[] = []
  const seenUserMessages = new Set<string>()
  let prevSignature: string | null = null
  for (const entry of entries) {
    const signature = duplicateSignature(entry)
    if (signature === prevSignature) continue  // 连续重复折叠
    prevSignature = signature

    // codex 镜像: event_msg.user_message 复述了之前已见的 user 卡文本.
    if (entry?.type === 'event_msg') {
      const message = entry?.payload?.message
      if (typeof message === 'string' && seenUserMessages.has(message)) continue
    }
    // mobius 原文卡与原生 user 卡同文 → 藏 mobius 卡.
    if (entry?.type === 'user' && entryHasMobiusField(entry)) {
      const content = userContentOf(entry)
      if (content !== null && plainUserContents.has(content)) continue
    }

    const userMessage = userContentOf(entry)
    if (userMessage !== null) seenUserMessages.add(userMessage)
    out.push(entry)
  }
  return out
}
