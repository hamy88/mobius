/**
 * 全局统一时间格式化工具（CST, UTC+8 + 24h 制）
 *
 * Issue 8f64748a: 用户可见时间字段统一北京时间 + 24h 制。
 * - 展示层强制使用 Asia/Shanghai 时区（不依赖浏览器本地时区）
 * - 24h 制 + 零填充
 * - 后端传入 ISO UTC 字符串 / Date / epoch ms 均可
 *
 * 边界（保持原样，不走本工具）：
 * - RFC 协议字段（Date header / Last-Modified）
 * - 数据库存储
 * - 日志 / JSONL / 文件系统时间戳
 * - cron 表达式
 * - 内部 API payload
 */

const CST_OFFSET_MS = 8 * 60 * 60 * 1000

/** 把 Date / epoch ms / ISO 字符串归一为 epoch ms；解析失败返回 null。 */
function toEpochMs(input: string | number | Date | null | undefined): number | null {
  if (input == null) return null
  if (input instanceof Date) {
    const ms = input.getTime()
    return Number.isFinite(ms) ? ms : null
  }
  if (typeof input === 'number') return Number.isFinite(input) ? input : null
  if (typeof input === 'string') {
    const trimmed = input.trim()
    if (!trimmed) return null
    const ms = Date.parse(trimmed)
    return Number.isFinite(ms) ? ms : null
  }
  return null
}

/** 在 epoch ms 基础上 +8 小时得到 CST 的 Date 对象。 */
function toCstDate(epochMs: number): Date {
  return new Date(epochMs + CST_OFFSET_MS)
}

const pad2 = (n: number): string => String(n).padStart(2, '0')

/** `YYYY-MM-DD HH:MM:SS` (CST) */
export function formatCstDateTime(input: string | number | Date | null | undefined): string {
  const ms = toEpochMs(input)
  if (ms == null) return ''
  const d = toCstDate(ms)
  return `${d.getUTCFullYear()}-${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())} ${pad2(d.getUTCHours())}:${pad2(d.getUTCMinutes())}:${pad2(d.getUTCSeconds())}`
}

/** `YYYY-MM-DD HH:MM` (CST) */
export function formatCstDateTimeShort(input: string | number | Date | null | undefined): string {
  const ms = toEpochMs(input)
  if (ms == null) return ''
  const d = toCstDate(ms)
  return `${d.getUTCFullYear()}-${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())} ${pad2(d.getUTCHours())}:${pad2(d.getUTCMinutes())}`
}

/** `HH:MM` (CST) - 仅时分 */
export function formatCstTimeOfDay(input: string | number | Date | null | undefined): string {
  const ms = toEpochMs(input)
  if (ms == null) return ''
  const d = toCstDate(ms)
  return `${pad2(d.getUTCHours())}:${pad2(d.getUTCMinutes())}`
}

/** `MM-DD HH:MM` (CST) - 月日 + 时分 */
export function formatCstMonthDayTime(input: string | number | Date | null | undefined): string {
  const ms = toEpochMs(input)
  if (ms == null) return ''
  const d = toCstDate(ms)
  return `${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())} ${pad2(d.getUTCHours())}:${pad2(d.getUTCMinutes())}`
}

/** 距今的相对时间描述（CST）；超过 24h 用 MM-DD HH:MM。 */
export function formatCstRelative(input: string | number | Date | null | undefined, nowMs: number = Date.now()): string {
  const ms = toEpochMs(input)
  if (ms == null) return ''
  const diff = nowMs - ms
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.max(1, Math.floor(diff / 60_000))}分钟前`
  if (diff < 86_400_000) return `${Math.max(1, Math.floor(diff / 3_600_000))}小时前`
  return formatCstMonthDayTime(ms)
}
