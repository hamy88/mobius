// 列表级本地缓存 (stale-while-revalidate): 新标签页/整页刷新时先秒显本地缓存,
// 后台静默刷新。背景: /u/:user 与 /u/:user/p/:project 的数据此前只有 zustand 内存
// 缓存, 顶栏用户名面包屑 newTab 行为(期望行为)每次开新 JS 上下文, 内存缓存永远
// miss → 每次都白屏等 API 1-2s。本模块把「已排序的最终列表」落到 localStorage,
// 下次进入同页时立即渲染, 网络回来后无感更新。
//
// 与 shell.tsx 里 RecentSessionsPanel 的私有缓存同款模式, 抽出来给页面级列表复用:
//   - 按用户隔离 key (不同账号互不可见);
//   - 写入失败 (配额满/隐私模式) 静默忽略, 不影响主流程;
//   - 只缓存数组本体, 损坏/超限时读侧直接判 miss。

// 单列表缓存上限 (字符数): projects 全量 ~600KB, 5MB 配额下留足多列表共存空间。
const MAX_CACHE_CHARS = 3_000_000

export interface ListCacheEntry<T> {
  ts: number
  list: T[]
}

function cacheKey(scope: string, userId?: string): string {
  return userId ? `mobius:list-cache:${scope}:${userId}` : ''
}

export function readListCache<T>(scope: string, userId?: string): ListCacheEntry<T> | null {
  const key = cacheKey(scope, userId)
  if (!key || typeof window === 'undefined') return null
  try {
    const raw = window.localStorage.getItem(key)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed.ts !== 'number' || !Array.isArray(parsed.list)) return null
    return { ts: parsed.ts, list: parsed.list as T[] }
  } catch {
    return null
  }
}

export function writeListCache<T>(scope: string, userId: string | undefined, list: T[]): void {
  const key = cacheKey(scope, userId)
  if (!key || typeof window === 'undefined') return
  try {
    const payload = JSON.stringify({ ts: Date.now(), list })
    if (payload.length > MAX_CACHE_CHARS) return
    window.localStorage.setItem(key, payload)
  } catch {
    /* 配额满或隐私模式: 忽略 */
  }
}
