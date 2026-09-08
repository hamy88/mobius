/**
 * viewer/JsonlView.tsx — jsonl 视图顶层组件 (group 驱动).
 *
 * 数据源是 agent-history-store 的快照 (协议 ① 的组元数据 + ② 的按需组条目):
 *  - 每个组渲染一个 RoundGroup; 未加载的组零条目驻留, 只显示元数据摘要头;
 *    展开 (或搜索命中) 时通过 onEnsureGroupEntries 走 ② 整组拉取.
 *  - 组内条目走与旧版相同的流水线: mergeBashToolResultItems → 噪声过滤 → 任务计划,
 *    每组独立跑 (快照与锚点同组).
 *  - 前端不再分组: buildRounds 退役, 组结构完全来自后端.
 *  - lineNo 是跨组唯一的全局序号 (组基址 + 组内序), 搜索跳转/强制展开靠它精确定位.
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { VirtualizedBlockList } from '../jsonl-virtual-list'
import type { AnyEntry, JsonlViewItem, JsonlRenderBlock, Round } from './types'
import { mergeBashToolResultItems } from './entry-extract'
import { collectResolvedCallIds } from './tool-status'
import { RoundGroup } from './RoundGroups'
import { isHiddenJsonlNoiseEntry } from './entry-classify'
import { filterDisplayDuplicates } from './display-dedup'
import { computeCollapsedByForgottenFlag } from './fold-rules'
import { buildTaskPlans } from './task-progress'
import type { HistorySnapshot, SessionHistoryStore } from '../../services/agent-history-store'
import {
  ROUND_HEADER_PALETTES,
  ROUND_HEADER_PALETTE_STORAGE_KEY,
  normalizeRoundHeaderPaletteIndex,
  readRoundHeaderPaletteIndex,
  saveRoundHeaderPaletteIndex,
} from './round-header-palette'

// 单组条目渲染窗口上限: 巨轮只渲染尾部窗口 (虚拟列表保证视口流畅,
// 这里限制的是首次进组的流水线成本).
const GROUP_ENTRY_WINDOW = 256

function JsonlInitialSkeleton() {
  return (
    <div className="jsonl-initial-skeleton" aria-live="polite" role="status">
      <div className="mb-3 flex items-center gap-2 text-[12px]" style={{ color: 'var(--text-muted)' }}>
        <span className="relative inline-flex h-3.5 w-3.5 flex-shrink-0">
          <span className="absolute inset-0 rounded-full border-2 border-[var(--text-muted)] opacity-20" />
          <span className="absolute inset-0 rounded-full border-2 border-transparent border-t-[var(--text-muted)] animate-spin" />
        </span>
        <span className="mobius-status-marquee">正在加载会话数据...</span>
      </div>
      <div className="space-y-2" aria-hidden="true">
        {Array.from({ length: 6 }).map((_, index) => (
          <div key={index} className="jsonl-initial-skeleton__card">
            <div className="jsonl-initial-skeleton__line w-1/3" />
            <div className="jsonl-initial-skeleton__line w-5/6" />
            <div className="jsonl-initial-skeleton__line w-2/3" />
          </div>
        ))}
      </div>
    </div>
  )
}

// 与 renderBlocks 里 round block 的 key 公式严格一致 (跳转按 data-block-key 查 DOM 必须同口径).
function roundKeyOf(groupId: string): string {
  return `round:${groupId}`
}

// 搜索命中的 (uuid, ts) 在已渲染的组条目里定位条目; 找不到返回 null.
function findItemInRounds(rounds: Round[], uuid: string | null | undefined, ts: string | null | undefined): JsonlViewItem | null {
  if (uuid) {
    for (const r of rounds) {
      for (const it of r?.items || []) {
        if (it?.entry?.uuid === uuid || it?.entry?.id === uuid) return it
      }
    }
  }
  if (ts) {
    for (const r of rounds) {
      for (const it of r?.items || []) {
        if ((it?.entry?.timestamp || it?.entry?.created_at) === ts) return it
      }
    }
    const targetTime = Date.parse(ts)
    if (Number.isFinite(targetTime)) {
      for (const r of rounds) {
        for (const it of r?.items || []) {
          const value = it?.entry?.timestamp || it?.entry?.created_at || ''
          if (Date.parse(value) === targetTime) return it
        }
      }
    }
  }
  return null
}

// 组条目 → 渲染流水线 (与旧版整列表流水线相同, 逐组独立跑; lineNo = 组基址 + 组内序).
function buildRoundFromEntries(entries: AnyEntry[], roundNum: number, baseLineNo: number): Round {
  const windowed = entries.length > GROUP_ENTRY_WINDOW ? entries.slice(-GROUP_ENTRY_WINDOW) : entries
  const deduped = filterDisplayDuplicates(windowed)
  const merged = mergeBashToolResultItems(deduped, baseLineNo)
  const visible = merged.filter((item) => !isHiddenJsonlNoiseEntry(item.entry))
  return { roundNum, items: visible.map((item, index) => ({ ...item, relIdx: index })) }
}

// ── 逐组派生数据的 WeakMap 缓存 (items 数组在快照 rev 不变时引用稳定) ─────────

const toolStatusCache = new WeakMap<AnyEntry[], ReturnType<typeof collectResolvedCallIds> | null>()
// 状态集合必须扫"与渲染相同的窗口切片"的原始条目:
//  1. 收集器吃 AnyEntry (在元素顶层找 tool_result 字段), 末端 JsonlViewItem 形状不对;
//  2. merge/过滤会吞掉纯 tool_result 条目, 末端列表里已没有结果载体.
// 扫原始窗口 (含被 merge/过滤隐藏的条目) 才能配出 tool_use → result 的完成态.
function toolStatusMapFor(entries: AnyEntry[]) {
  const hit = toolStatusCache.get(entries)
  if (hit !== undefined) return hit
  const windowed = entries.length > GROUP_ENTRY_WINDOW ? entries.slice(-GROUP_ENTRY_WINDOW) : entries
  const value = collectResolvedCallIds(windowed)
  toolStatusCache.set(entries, value)
  return value
}

const collapsedCache = new WeakMap<AnyEntry[], Set<number>>()
function collapsedLineNosFor(entries: AnyEntry[], items: JsonlViewItem[]) {
  const hit = collapsedCache.get(entries)
  if (hit) return hit
  const next = computeCollapsedByForgottenFlag(items)
  collapsedCache.set(entries, next)
  return next
}

const plansCache = new WeakMap<AnyEntry[], ReturnType<typeof buildTaskPlans>['plans']>()
function taskPlansFor(entries: AnyEntry[], items: JsonlViewItem[]) {
  const hit = plansCache.get(entries)
  if (hit) return hit
  const { plans } = buildTaskPlans(items)
  plansCache.set(entries, plans)
  return plans
}

export function JsonlView({
  snapshot,
  store,
  title,
  emptyLoadingText,
  initialLoading,
  showMeta = true,
  scrollToEntryUuid,
  scrollToMatchTs,
  onScrollResolved,
}: {
  // agent-history-store 的快照 (rev 驱动重渲染).
  snapshot: HistorySnapshot
  // store 实例: 视图只发状态机转移意图 (开/合/重试), 不直接碰数据.
  store: SessionHistoryStore | null
  title?: string
  emptyLoadingText?: string
  initialLoading?: boolean
  // false 时 jsonl 卡片标题里不再显示 "#序号" 和 "MM-DD HH:MM:SS" 时间戳前缀.
  showMeta?: boolean
  // 搜索结果跳转: 命中条目 uuid / timestamp; 未加载的组先 ② 再定位.
  scrollToEntryUuid?: string | null
  scrollToMatchTs?: string | null
  onScrollResolved?: () => void
}) {
  const groups = snapshot.groups
  const [roundHeaderPaletteIndex, setRoundHeaderPaletteIndex] = useState(readRoundHeaderPaletteIndex)
  const [roundHeaderPaletteAnnouncement, setRoundHeaderPaletteAnnouncement] = useState('')
  const roundHeaderPalette = ROUND_HEADER_PALETTES[roundHeaderPaletteIndex]

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.repeat || event.altKey || event.metaKey || !event.ctrlKey || !event.shiftKey || event.key.toLowerCase() !== 'k') return
      event.preventDefault()
      const next = (roundHeaderPaletteIndex + 1) % ROUND_HEADER_PALETTES.length
      saveRoundHeaderPaletteIndex(next)
      setRoundHeaderPaletteAnnouncement(`轮次背景已切换为${ROUND_HEADER_PALETTES[next].name}，第 ${next + 1} 种，共 ${ROUND_HEADER_PALETTES.length} 种`)
      setRoundHeaderPaletteIndex(next)
    }
    const onStorage = (event: StorageEvent) => {
      if (event.key !== ROUND_HEADER_PALETTE_STORAGE_KEY) return
      setRoundHeaderPaletteIndex(normalizeRoundHeaderPaletteIndex(event.newValue))
    }
    window.addEventListener('keydown', onKeyDown)
    window.addEventListener('storage', onStorage)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
      window.removeEventListener('storage', onStorage)
    }
  }, [roundHeaderPaletteIndex])

  // 组 → Round: 条目已加载才建 items; 未加载 = 零条目驻留, 只渲染元数据头.
  // lineNo 组基址累加, 保证跨组唯一 (搜索跳转按 data-jsonl-line-no 全局查询).
  const rounds = useMemo(() => {
    let baseLineNo = 0
    return groups.map((meta) => {
      const entries = snapshot.entriesByGroup.get(meta.id) || []
      const round = entries.length > 0 ? buildRoundFromEntries(entries, meta.seq, baseLineNo) : { roundNum: meta.seq, items: [] as any[] }
      baseLineNo += entries.length
      return { meta, round, state: (snapshot.groupRuntime.get(meta.id)?.state) || 'closed', entries }
    })
  }, [snapshot]) // eslint-disable-line react-hooks/exhaustive-deps

  const headerTitle = title === undefined ? 'JSONL' : title
  const loadedGroups = rounds.filter((r) => r.entries.length > 0).length
  const totalEntryCount = groups.reduce((sum, g) => sum + (g.entry_count || 0), 0)
  // 末轮摘要: 直接用组元数据 (不再从条目派生).
  const lastRoundUserSummary = groups.length > 0 ? (groups[groups.length - 1].user_summary || '') : ''
  const onlyGroup = groups.length === 1

  // 点击 header "末轮" 摘要 -> 跳转到最后一个组.
  const headerRef = useRef<HTMLDivElement>(null)
  const [internalTarget, setInternalTarget] = useState<{ key: string; offset: number } | null>(null)
  const jumpToLastRound = () => {
    if (groups.length === 0) return
    setInternalTarget({ key: roundKeyOf(groups[groups.length - 1].id), offset: headerRef.current?.offsetHeight ?? 0 })
  }

  // 搜索结果跳转: 已加载 → 定位; 未加载 → 按 opener_ts 区间找所属组先 ②
  // (快照 rev 变化后本 effect 重跑, 条目到位再精确定位).
  const [extTarget, setExtTarget] = useState<{ key: string; offset: number } | null>(null)
  const [extFocusLineNo, setExtFocusLineNo] = useState<number | null>(null)
  const extActive = !!(scrollToEntryUuid || scrollToMatchTs)
  const onResolvedRef = useRef(onScrollResolved)
  onResolvedRef.current = onScrollResolved
  const storeRef = useRef(store)
  storeRef.current = store
  useEffect(() => {
    if (!extActive) { setExtTarget(null); setExtFocusLineNo(null); return }
    if (initialLoading) { setExtTarget(null); return }
    const matchItem = findItemInRounds(rounds.map((r) => r.round), scrollToEntryUuid ?? null, scrollToMatchTs ?? null)
    if (matchItem) {
      const owner = rounds.find((r) => r.round.items.some((it) => it.lineNo === matchItem.lineNo))
      setExtFocusLineNo(matchItem.lineNo)
      setExtTarget({ key: owner ? roundKeyOf(owner.meta.id) : roundKeyOf(groups[0]?.id || ''), offset: headerRef.current?.offsetHeight ?? 0 })
      return
    }
    // 未命中: 时间戳区间定位所属组 (元数据里有每组的 opener_ts), 触发该组加载.
    const ts = scrollToMatchTs ?? null
    const targetMs = ts ? Date.parse(ts) : NaN
    if (!Number.isFinite(targetMs)) {
      // 只有 uuid 没有时间兜底: 从最后一组往前逐组补载直到找到 (有界, 一般一两轮就命中).
      const firstUnloaded = [...rounds].reverse().find((r) => r.entries.length === 0)
      if (firstUnloaded) { storeRef.current?.ensureGroupEntries(firstUnloaded.meta.id); return }
      onResolvedRef.current?.()
      return
    }
    let owner: (typeof rounds)[number] | undefined
    for (const r of rounds) {
      const openerMs = Date.parse(r.meta.opener_ts || '')
      if (Number.isFinite(openerMs) && openerMs <= targetMs) owner = r
      else if (Number.isFinite(openerMs) && openerMs > targetMs) break
    }
    if (!owner) owner = rounds[0]
    if (!owner) { onResolvedRef.current?.(); return }
    if (owner.entries.length === 0) { storeRef.current?.ensureGroupEntries(owner.meta.id); return }
    // 组已加载但条目里没有 (被噪声过滤/窗口截掉): 至少滚到所属组.
    setExtFocusLineNo(null)
    setExtTarget({ key: roundKeyOf(owner.meta.id), offset: headerRef.current?.offsetHeight ?? 0 })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [extActive, scrollToEntryUuid, scrollToMatchTs, initialLoading, snapshot.rev])

  const activeTarget = extTarget ?? internalTarget

  const renderBlocks = useMemo<JsonlRenderBlock[]>(() => {
    return rounds.map((r, index) => ({
      key: roundKeyOf(r.meta.id),
      kind: 'round' as const,
      round: r.round,
      index,
    }))
  }, [rounds])

  const renderBlock = (block: JsonlRenderBlock) => {
    if (block.kind !== 'round') return null
    const r = rounds[block.index]
    if (!r) return null
    const entries = r.entries.length > 0 ? r.entries : null
    const rt = snapshot.groupRuntime.get(r.meta.id)
    return (
      <RoundGroup
        round={r.round}
        isLast={block.index === rounds.length - 1}
        isSecondLast={block.index === rounds.length - 2}
        onlyGroup={onlyGroup}
        open={r.state !== 'closed'}
        sticky={!!rt?.sticky}
        loading={r.state === 'open-loading'}
        failed={!!rt?.lastError}
        resident={!!entries}
        onUserToggle={() => store?.toggleGroup(r.meta.id)}
        onAutoOpen={() => store?.openGroup(r.meta.id, 'auto')}
        onAutoClose={() => store?.closeGroup(r.meta.id, 'auto')}
        onRetry={() => store?.retryGroup(r.meta.id)}
        forceOpen={block.key === extTarget?.key && extFocusLineNo !== null}
        showMeta={showMeta}
        toolStatusMap={entries ? toolStatusMapFor(entries) : null}
        collapseLineNos={entries ? collapsedLineNosFor(entries, r.round.items) : undefined}
        focusLineNo={extFocusLineNo}
        headerPalette={roundHeaderPalette}
        taskPlans={entries ? taskPlansFor(entries, r.round.items) : null}
      />
    )
  }

  // 空
  if (groups.length === 0) {
    if (initialLoading) return <JsonlInitialSkeleton />
    if (emptyLoadingText) {
      return (
        <div className="rounded-2xl border border-amber-500/30 bg-amber-500/[0.05] px-4 py-4 text-[12px] text-amber-200 card-enter" aria-live="polite">
          <div className="flex items-center gap-3">
            <span className="relative inline-flex w-4 h-4 flex-shrink-0">
              <span className="absolute inset-0 rounded-full border-2 border-amber-300/20" />
              <span className="absolute inset-0 rounded-full border-2 border-transparent border-t-amber-300 animate-spin" />
            </span>
            <span className="font-medium mobius-status-marquee">{emptyLoadingText}</span>
          </div>
        </div>
      )
    }
    return (
      <div className="text-[12px] text-center py-8 text-[var(--text-muted)]" aria-live="polite" role="status">
        暂无对话内容
      </div>
    )
  }

  // 非空
  return (
    <div className="text-[12px]">
      <span className="sr-only" aria-live="polite" aria-atomic="true">{roundHeaderPaletteAnnouncement}</span>
      <div ref={headerRef} className="flex items-center gap-2 px-1 py-1 sticky top-0 z-10 backdrop-blur-lg bg-[var(--bg-page)]/80">
        {headerTitle && <span className="min-w-0 truncate text-[var(--text-secondary)] font-semibold" title={headerTitle}>{headerTitle}</span>}
        {groups.length > 0 && <span className="text-[var(--text-muted)] text-[11px]">{groups.length} 轮</span>}
        {loadedGroups < groups.length && (
          <span className="text-[var(--text-muted)] text-[11px]" title="展开对应轮次时按需加载明细">已载 {loadedGroups}/{groups.length} 轮 · 共 {totalEntryCount} 条</span>
        )}
        {lastRoundUserSummary && (
          <button
            type="button"
            onClick={jumpToLastRound}
            className="min-w-0 flex-1 truncate text-[11px] text-[var(--text-muted)] hover:text-[var(--text-secondary)] bg-transparent border-0 p-0 cursor-pointer text-left transition-colors"
            title={`点击跳转到末轮：${lastRoundUserSummary}`}
          >
            <span className="opacity-60">末轮 ·</span> {lastRoundUserSummary}
          </button>
        )}
      </div>
      <VirtualizedBlockList
        blocks={renderBlocks}
        renderBlock={renderBlock}
        scrollToKey={activeTarget?.key ?? null}
        scrollToEntryLineNo={extFocusLineNo}
        scrollOffset={activeTarget?.offset ?? 0}
        onScrollToKeyDone={() => {
          if (extTarget && extFocusLineNo !== null) return
          if (extTarget) onResolvedRef.current?.()
          setExtTarget(null)
          setInternalTarget(null)
        }}
        onScrollToEntryDone={() => {
          if (!extTarget || extFocusLineNo === null) return
          onResolvedRef.current?.()
          setExtTarget(null)
          setInternalTarget(null)
        }}
      />
    </div>
  )
}
